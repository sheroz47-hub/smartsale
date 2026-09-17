"""Отправка документов SmartSale в УТ (обратный канал).

Заказы и оплаты (ПКО), снятые агентами на телефоне и принятые сервером
SmartSale, уходят в 1С:УТ (методы /orders и /payments расширения). Приём в УТ
идемпотентен по client_uid, и здесь тоже: отметки в ut_exports не дают
отправить документ дважды. Запуск по cron, отдельным процессом.

    /srv/smartsale-obmen/venv/bin/python -m app.push_to_ut

Идентификаторы уже сходятся с УТ (каталог УТ-ведомый): customer.uuid = uid
партнёра, product.uuid = uid номенклатуры, agent.uuid = uid пользователя УТ,
и этот uid уходит в УТ параметром agent (он задаёт организацию/склад/кассу).
Скидка на телефоне — процентом на строку; в УТ уходит СУММОЙ ручной скидки
(discount_amount = кол·цена·процент/100). Бонусная строка с процентом 100
превращается в полную скидку — строка с нулевой суммой.
"""

import logging
import os
import sys
from collections import defaultdict
from decimal import ROUND_HALF_UP, Decimal

from dotenv import load_dotenv

load_dotenv(os.path.join(os.path.dirname(__file__), "..", "..", ".env"))

from sqlalchemy import select  # noqa: E402
from sqlalchemy.orm import Session  # noqa: E402

from . import ut_client  # noqa: E402
from .db import SessionLocal, engine  # noqa: E402
from .models import (  # noqa: E402
    Base, Customer, Order, Payment, Product, User, UtExport,
)

log = logging.getLogger("push_to_ut")
ЦЕНТ = Decimal("0.01")


def _отправленные(session: Session, вид: str) -> set[str]:
    return set(session.scalars(
        select(UtExport.client_uid).where(UtExport.kind == вид)).all())


def _записать_итоги(session: Session, вид: str, результаты: list[dict]) -> None:
    for р in результаты:
        cuid = р.get("client_uid")
        if not cuid:
            continue
        if р.get("status") == "accepted":
            session.add(UtExport(
                client_uid=cuid, kind=вид, ut_number=р.get("number", "")))
            continue
        # Отказ НЕ фиксируем как постоянный: причина часто исправима (у клиента
        # ещё нет договора, ссылка не синхронизована, временная блокировка), а
        # терять документ с деньгами нельзя. Повторим на следующем прогоне —
        # УТ по client_uid дубль не создаст. Причина — в логе; постоянно
        # «битый» документ будет виден по повторяющемуся предупреждению.
        log.warning("%s %s отклонён УТ, повтор на следующем прогоне: %s",
                    вид, cuid, р.get("error", ""))
    session.commit()


def _отправить_заказы(session: Session) -> int:
    отправлены = _отправленные(session, "order")
    запрос = select(Order).where(Order.status != "cancelled")
    if отправлены:
        запрос = запрос.where(Order.client_uid.not_in(отправлены))
    заказы = session.scalars(запрос).all()
    if not заказы:
        return 0

    товары = {т.id: т.uuid for т in session.scalars(select(Product)).all()}
    клиенты = {к.id: к.uuid for к in session.scalars(select(Customer)).all()}
    агенты = {п.id: п.uuid for п in session.scalars(select(User)).all()}

    группы: dict[str, list] = defaultdict(list)
    for заказ in заказы:
        агент_uid = агенты.get(заказ.agent_id)
        клиент_uid = клиенты.get(заказ.customer_id)
        if not агент_uid or not клиент_uid:
            log.warning("заказ %s без агента/клиента — пропущен", заказ.number)
            continue
        группы[агент_uid].append((заказ, клиент_uid))

    ошибок = 0
    for агент_uid, список in группы.items():
        пакет = {"orders": []}
        for заказ, клиент_uid in список:
            строки = []
            неполный = False
            for линия in заказ.lines:
                товар_uid = товары.get(линия.product_id)
                if not товар_uid:
                    # Товара ещё нет в SmartSale (не подтянулся из УТ). Отправить
                    # заказ без этой строки = урезать документ и потерять её
                    # молча. Откладываем заказ ЦЕЛИКОМ (без отметки в ut_exports)
                    # — уйдёт на следующем прогоне, когда каталог синхронизуется.
                    log.warning("заказ %s: товар не сопоставлен — отложен целиком",
                                заказ.number)
                    неполный = True
                    break
                скидка = (линия.qty * линия.price * линия.discount_percent
                          / Decimal(100)).quantize(ЦЕНТ, ROUND_HALF_UP)
                строки.append({
                    "product_uid": товар_uid,
                    "qty": str(линия.qty),
                    "price": str(линия.price),
                    "discount_amount": str(скидка),
                })
            if неполный:
                continue
            пакет["orders"].append({
                "client_uid": заказ.client_uid,
                "customer_uid": клиент_uid,
                "date": заказ.date.isoformat(),
                "comment": заказ.comment,
                "lines": строки,
            })
        if not пакет["orders"]:
            continue
        try:
            ответ = ut_client.отправить("orders", пакет, {"agent": агент_uid})
        except ut_client.ОшибкаУТ:
            ошибок += 1
            log.exception("отправка заказов агента %s не удалась", агент_uid)
            continue
        _записать_итоги(session, "order", ответ.get("results", []))
    return ошибок


def _отправить_оплаты(session: Session) -> int:
    отправлены = _отправленные(session, "payment")
    запрос = select(Payment)
    if отправлены:
        запрос = запрос.where(Payment.client_uid.not_in(отправлены))
    оплаты = session.scalars(запрос).all()
    if not оплаты:
        return 0

    клиенты = {к.id: к.uuid for к in session.scalars(select(Customer)).all()}
    агенты = {п.id: п.uuid for п in session.scalars(select(User)).all()}

    группы: dict[str, list] = defaultdict(list)
    for оплата in оплаты:
        агент_uid = агенты.get(оплата.agent_id)
        клиент_uid = клиенты.get(оплата.customer_id)
        if not агент_uid or not клиент_uid:
            log.warning("оплата %s без агента/клиента — пропущена", оплата.number)
            continue
        группы[агент_uid].append((оплата, клиент_uid))

    ошибок = 0
    for агент_uid, список in группы.items():
        пакет = {"payments": []}
        for оплата, клиент_uid in список:
            пакет["payments"].append({
                "client_uid": оплата.client_uid,
                "customer_uid": клиент_uid,
                "amount": str(оплата.amount),
                "date": оплата.date.isoformat(),
                "comment": оплата.comment,
            })
        try:
            ответ = ut_client.отправить("payments", пакет, {"agent": агент_uid})
        except ut_client.ОшибкаУТ:
            ошибок += 1
            log.exception("отправка оплат агента %s не удалась", агент_uid)
            continue
        _записать_итоги(session, "payment", ответ.get("results", []))
    return ошибок


def run_push() -> int:
    """Отправка накопленных документов в УТ. Возвращает число упавших групп
    (0 — всё ушло). Сбой связи по одному агенту не роняет остальных: документ
    без отметки уйдёт на следующем прогоне."""
    Base.metadata.create_all(engine)
    ошибок = 0
    with SessionLocal() as session:
        try:
            ошибок += _отправить_заказы(session)
        except Exception:
            ошибок += 1
            session.rollback()
            log.exception("сбой отправки заказов")
        try:
            ошибок += _отправить_оплаты(session)
        except Exception:
            ошибок += 1
            session.rollback()
            log.exception("сбой отправки оплат")
    return ошибок


def main() -> None:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s %(message)s")
    ошибок = run_push()
    if ошибок:
        log.error("отправка завершена с ошибками: групп упало %d", ошибок)
        sys.exit(1)
    log.info("отправка в УТ завершена")


if __name__ == "__main__":
    main()
