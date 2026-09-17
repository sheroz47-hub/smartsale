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

from sqlalchemy import delete, select  # noqa: E402
from sqlalchemy.orm import Session  # noqa: E402

from . import ut_client  # noqa: E402
from .db import SessionLocal, engine  # noqa: E402
from .models import (  # noqa: E402
    Audit, Base, Customer, CustomerGeoPush, Order, Payment, Product, Task,
    TaskPhoto, User, UtExport,
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
                "delivery_date": (заказ.delivery_date.isoformat()
                                  if заказ.delivery_date else ""),
                "delivery_time_from": заказ.delivery_time_from,
                "delivery_time_to": заказ.delivery_time_to,
                "delivery_address": заказ.delivery_address,
                "contact_name": заказ.contact_name,
                "contact_phone": заказ.contact_phone,
                "delivery_method": заказ.delivery_method,
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


def _отправить_задания(session: Session) -> int:
    """Отметки выполнения заданий в УТ (метод /tasks). Идемпотентность — своя,
    флагом задания done_pushed (а не ut_exports: у заданий нет client_uid,
    ключ — uuid из УТ). Шлём выполненные и ещё не отправленные; принятые
    помечаем pushed, отклонённые повторяем на следующем прогоне.
    """
    задания = session.scalars(
        select(Task).where(Task.done, Task.done_pushed.is_(False))).all()
    if not задания:
        return 0

    по_uid = {з.uuid: з for з in задания}
    пакет = {"tasks": [
        {"uid": з.uuid, "comment": з.comment} for з in задания]}
    try:
        ответ = ut_client.отправить("tasks", пакет)
    except ut_client.ОшибкаУТ:
        log.exception("отправка отметок выполнения заданий не удалась")
        return 1

    for р in ответ.get("results", []):
        uid = р.get("uid")
        задание = по_uid.get(uid)
        if задание is None:
            continue
        if р.get("status") == "accepted":
            задание.done_pushed = True
        else:
            # Не помечаем: причина может быть исправимой (задание ещё не
            # синхронизовано ссылкой). Повторим на следующем прогоне.
            log.warning("отметка задания %s отклонена УТ, повтор позже: %s",
                        uid, р.get("error", ""))
    session.commit()
    return 0


def _отправить_фото(session: Session) -> int:
    """Фотоотчёты заданий в УТ (метод /tasks/photo, бинарём). Шина хранит фото
    лишь до доставки: принятое удаляем. Отказ УТ по существу (задание не
    найдено — status rejected) терминальный: тоже удаляем, иначе «мёртвое» фото
    гонялось бы вечно (к несуществующему заданию его не приложить). Сбой связи/
    хранения — исключение ОшибкаУТ: фото оставляем, повторим на следующем
    прогоне.
    """
    снимки = session.scalars(select(TaskPhoto)).all()
    if not снимки:
        return 0

    ошибок = 0
    for снимок in снимки:
        try:
            ответ = ut_client.отправить_двоичные(
                "tasks/photo", снимок.content,
                {"task": снимок.task_uuid, "name": снимок.name})
        except ut_client.ОшибкаУТ:
            ошибок += 1
            log.exception("отправка фото %s задания %s не удалась",
                          снимок.name, снимок.task_uuid)
            continue
        if ответ.get("status") == "accepted":
            session.delete(снимок)
        else:
            log.warning("фото %s задания %s отклонено УТ, снято с очереди: %s",
                        снимок.name, снимок.task_uuid, ответ.get("error", ""))
            session.delete(снимок)
        session.commit()
    return ошибок


def _отправить_координаты(session: Session) -> int:
    """Уточнённые координаты клиентов в УТ (метод /customers/geo, пачкой).

    Принятую точку убираем из очереди — но ТОЛЬКО если агент не уточнил её ещё
    раз между чтением и сейчас (строка с тем же клиентом, но новым `at` —
    свежее значение, затирать его нельзя, уйдёт следующим прогоном).

    Отказ УТ по существу («нет контрагента», «не заведены виды координат») —
    НЕ терминальный: строку оставляем. Причина исправима (заведут контрагента/
    виды), а терять уточнение агента нельзя — иначе следующая выгрузка
    /customers откатит точку и на телефоне, и это ровно тот молчаливый регресс,
    что видит пользователь. Строка одна на клиента (перезапись), очередь не
    растёт. Сбой связи — вся пачка остаётся до следующего прогона.
    """
    точки = session.scalars(select(CustomerGeoPush)).all()
    if not точки:
        return 0

    снимок_at = {т.customer_uuid: т.at for т in точки}
    пакет = {"locations": [
        {"customer_uid": т.customer_uuid, "lat": т.lat, "lon": т.lon}
        for т in точки]}
    try:
        ответ = ut_client.отправить("customers/geo", пакет)
    except ut_client.ОшибкаУТ:
        log.exception("отправка координат клиентов не удалась")
        return 1

    for р in ответ.get("results", []):
        cuid = р.get("customer_uid")
        if cuid not in снимок_at:
            continue
        if р.get("status") == "accepted":
            session.execute(delete(CustomerGeoPush).where(
                CustomerGeoPush.customer_uuid == cuid,
                CustomerGeoPush.at == снимок_at[cuid]))
        else:
            log.warning("координаты клиента %s отклонены УТ, оставлены в очереди: %s",
                        cuid, р.get("error", ""))
    session.commit()
    return 0


def _отправить_аудиты(session: Session) -> int:
    """Результаты аудита точек в УТ (метод /audits, пачкой). Идемпотентность —
    флагом Audit.pushed (ключ — client_uid). Принято или отклонено по существу
    (клиент/вопрос не найден) — ставим pushed (терминально, повтор не поможет);
    сбой связи — вся пачка остаётся до следующего прогона.
    """
    аудиты = session.scalars(select(Audit).where(Audit.pushed.is_(False))).all()
    if not аудиты:
        return 0

    клиенты = {к.id: к.uuid for к in session.scalars(select(Customer)).all()}
    агенты = {п.id: п.uuid for п in session.scalars(select(User)).all()}

    по_uid = {}
    пакет = {"audits": []}
    for аудит in аудиты:
        клиент_uid = клиенты.get(аудит.customer_id)
        if not клиент_uid:
            log.warning("аудит %s без клиента — пропущен", аудит.client_uid)
            continue
        по_uid[аудит.client_uid] = аудит
        пакет["audits"].append({
            "client_uid": аудит.client_uid,
            "customer_uid": клиент_uid,
            "agent_uid": агенты.get(аудит.agent_id, ""),
            "date": аудит.date.isoformat(),
            "answers": [
                {"question_uid": о.question_uuid, "value": о.value}
                for о in аудит.answers],
        })
    if not пакет["audits"]:
        return 0

    try:
        ответ = ut_client.отправить("audits", пакет)
    except ut_client.ОшибкаУТ:
        log.exception("отправка аудитов не удалась")
        return 1

    for р in ответ.get("results", []):
        аудит = по_uid.get(р.get("client_uid"))
        if аудит is None:
            continue
        аудит.pushed = True
        if р.get("status") != "accepted":
            аудит.error = р.get("error", "")
            log.warning("аудит %s отклонён УТ: %s",
                        аудит.client_uid, р.get("error", ""))
    session.commit()
    return 0


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
        try:
            ошибок += _отправить_задания(session)
        except Exception:
            ошибок += 1
            session.rollback()
            log.exception("сбой отправки отметок заданий")
        try:
            ошибок += _отправить_фото(session)
        except Exception:
            ошибок += 1
            session.rollback()
            log.exception("сбой отправки фотоотчётов")
        try:
            ошибок += _отправить_координаты(session)
        except Exception:
            ошибок += 1
            session.rollback()
            log.exception("сбой отправки координат")
        try:
            ошибок += _отправить_аудиты(session)
        except Exception:
            ошибок += 1
            session.rollback()
            log.exception("сбой отправки аудитов")
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
