"""API мобильного приложения агента.

Синхронизация односторонняя по каждому виду данных, и это сознательно:
справочники агент только читает, документы только создаёт. При таком
разделении слияния изменений не возникает вовсе — а слияние и есть самое
дорогое в офлайн-приложениях.

Забор справочников инкрементальный: телефон присылает время прошлого обмена
и получает изменившееся после. Время берётся серверное и возвращается в
ответе — часы на телефонах уходят на минуты, и доверять им нельзя.

Отправка документов идемпотентна по client_uid: связь на рынке рвётся
посреди запроса, телефон повторяет отправку, и без ключа в базе появлялись
бы двойные заказы.
"""

import json
import logging
import re
import time
from datetime import date, datetime, timezone
from decimal import Decimal

from fastapi import APIRouter, Depends, HTTPException, Request
from pydantic import BaseModel, Field
from sqlalchemy import select
from sqlalchemy.orm import Session

from . import services
from .auth import (
    check_secret, current_device, find_user, issue_device_token,
)
from .config import CURRENCY, REQUIRE_VISIT_GPS, SYNC_PAGE_SIZE
from .db import get_session
from .models import (
    Customer, Device, Order, OrderLine, Payment, Price, PriceType, Product,
    ProductCategory, Promotion, Route, RouteStop, Stock, SyncLog, User, Visit,
    Warehouse,
)

log = logging.getLogger("api")
router = APIRouter(prefix="/api/v1")

# Версия протокола. Телефон присылает свою; расхождение по старшей части
# означает, что приложение надо обновить, — молча работать с чужим форматом
# опаснее, чем отказать.
PROTOCOL = "1.0"


def _момент(значение: datetime | None) -> str | None:
    """Время наружу — всегда UTC с суффиксом Z.

    Не «+00:00»: это значение возвращается телефону и приезжает обратно
    строкой запроса, а знак плюс в строке запроса означает пробел. Клиент,
    забывший его закодировать, получал бы отлуп на ровном месте.
    """
    if значение is None:
        return None
    return значение.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%fZ")


def _число(значение: Decimal | None) -> str:
    """Числа отдаём строками.

    JSON не различает 1.10 и 1.1, а разбор чисел с плавающей точкой на
    телефоне даёт свою погрешность. Строка доезжает ровно такой, какой ушла,
    и на устройстве кладётся в BigDecimal.
    """
    return str(значение if значение is not None else 0)


# --- вход --------------------------------------------------------------------

class ЗапросВхода(BaseModel):
    login: str
    password: str
    device_id: str = Field(min_length=8, max_length=64)
    device_name: str = ""
    app_version: str = ""
    protocol: str = PROTOCOL


@router.post("/auth/login")
def login(данные: ЗапросВхода, request: Request,
          session: Session = Depends(get_session)):
    """Вход агента и привязка устройства.

    Устройство создаётся при первом входе. Повторный вход с того же аппарата
    перевыпускает токен — так агент восстанавливается после переустановки
    приложения, не дожидаясь администратора.
    """
    if данные.protocol.split(".")[0] != PROTOCOL.split(".")[0]:
        raise HTTPException(
            status_code=426,
            detail="версия приложения устарела, обновите его")

    пользователь = find_user(session, данные.login)
    # Одинаковый ответ на неверный логин и неверный пароль: разные подсказали
    # бы, какие логины существуют.
    if (пользователь is None or not пользователь.active
            or not check_secret(данные.password, пользователь.password_hash)):
        raise HTTPException(status_code=401, detail="неверный логин или пароль")

    if not пользователь.is_agent:
        raise HTTPException(
            status_code=403,
            detail="приложение только для торговых агентов")

    устройство = session.scalar(select(Device).where(
        Device.user_id == пользователь.id,
        Device.device_id == данные.device_id))

    if устройство is None:
        устройство = Device(
            user_id=пользователь.id, device_id=данные.device_id,
            name=данные.device_name[:128])
        session.add(устройство)
        session.flush()
    elif not устройство.active:
        # Отключённое администратором устройство паролем не воскрешается:
        # отключают его обычно потому, что телефон потерян.
        raise HTTPException(
            status_code=403,
            detail="устройство отключено, обратитесь к администратору")

    устройство.app_version = данные.app_version[:32]
    устройство.name = данные.device_name[:128] or устройство.name
    токен = issue_device_token(session, устройство)
    пользователь.last_login = datetime.now(timezone.utc)
    session.commit()

    return {
        "token": токен,
        "protocol": PROTOCOL,
        "server_time": _момент(datetime.now(timezone.utc)),
        "currency": CURRENCY,
        "user": {
            "uuid": пользователь.uuid,
            "full_name": пользователь.full_name,
            "login": пользователь.login,
        },
    }


@router.post("/auth/logout")
def logout(device: Device = Depends(current_device),
           session: Session = Depends(get_session)):
    """Выход: токен гасится, устройство остаётся привязанным."""
    device.token_hash = ""
    device.token_expires = None
    session.commit()
    return {"ok": True}


# --- забор справочников ------------------------------------------------------

@router.get("/sync/pull")
def pull(request: Request, since: str = "",
         device: Device = Depends(current_device),
         session: Session = Depends(get_session)):
    """Справочники, изменившиеся после `since` (ISO-8601, UTC).

    Пустой `since` — первый обмен, отдаётся всё. Отключённые записи приходят
    вместе с активными, с признаком active=false: телефон обязан их погасить,
    а не просто не увидеть. Иначе снятый с продажи товар останется в каталоге
    навсегда.
    """
    начало = time.monotonic()
    отсечка = _разобрать_момент(since)
    агент: User = device.user

    def изменённые(модель, запрос=None):
        запрос = запрос if запрос is not None else select(модель)
        if отсечка is not None:
            запрос = запрос.where(модель.updated_at > отсечка)
        return session.scalars(
            запрос.order_by(модель.updated_at).limit(SYNC_PAGE_SIZE)).all()

    # Момент фиксируется до выборки. Возьми его после — записи, изменённые во
    # время выполнения запроса, попали бы в отсечку, но не в выдачу, и
    # потерялись бы навсегда.
    серверное_время = datetime.now(timezone.utc)

    склады = изменённые(Warehouse)
    виды_цен = изменённые(PriceType)
    категории = изменённые(ProductCategory)
    товары = изменённые(Product)

    # Клиенты только свои: агенту незачем видеть чужую клиентскую базу, а на
    # телефоне это ещё и лишние мегабайты.
    клиенты = изменённые(
        Customer, select(Customer).where(Customer.agent_id == агент.id))

    цены = session.scalars(
        select(Price).where(Price.updated_at > отсечка)
        if отсечка is not None else select(Price)
    ).all()

    остатки = session.scalars(
        select(Stock).where(Stock.updated_at > отсечка)
        if отсечка is not None else select(Stock)
    ).all()

    маршруты = изменённые(Route, select(Route).where(Route.agent_id == агент.id))

    # Акции — условия для движка скидок на телефоне. Приходят всем агентам
    # одинаково (отбор по клиенту/сегменту делает движок при наборе заказа).
    # Отданные с active=false — погашенные: телефон обязан их убрать.
    акции = изменённые(Promotion)

    ответ = {
        "server_time": _момент(серверное_время),
        "protocol": PROTOCOL,
        # Признак того, что выдача упёрлась в размер страницы: телефон должен
        # сразу повторить запрос с новым since, не дожидаясь расписания.
        "more": any(len(x) >= SYNC_PAGE_SIZE
                    for x in (товары, клиенты, категории, акции)),
        "warehouses": [{
            "uuid": с.uuid, "code": с.code, "name": с.name, "active": с.active,
        } for с in склады],
        "price_types": [{
            "uuid": в.uuid, "code": в.code, "name": в.name,
            "is_default": в.is_default, "active": в.active,
        } for в in виды_цен],
        "categories": [{
            "uuid": к.uuid, "name": к.name,
            "parent_uuid": _uuid_категории(session, к.parent_id),
            "sort_order": к.sort_order, "active": к.active,
        } for к in категории],
        "products": [{
            "uuid": т.uuid, "code": т.code, "name": т.name, "unit": т.unit,
            "package_qty": _число(т.package_qty), "package_name": т.package_name,
            "barcode": т.barcode, "vat_rate": _число(т.vat_rate),
            "category_uuid": _uuid_категории(session, т.category_id),
            "has_image": bool(т.image_path), "active": т.active,
        } for т in товары],
        "prices": [{
            "product_uuid": ц.product.uuid,
            "price_type_uuid": ц.price_type.uuid,
            "price": _число(ц.price),
        } for ц in цены],
        "stocks": [{
            "product_uuid": о.product.uuid,
            "warehouse_uuid": о.warehouse.uuid,
            "free": _число(о.free),
        } for о in остатки],
        "customers": [{
            "uuid": к.uuid, "code": к.code, "name": к.name,
            "legal_name": к.legal_name, "inn": к.inn, "phone": к.phone,
            "address": к.address, "lat": к.lat, "lon": к.lon,
            "price_type_uuid": _uuid_вида_цены(session, к.price_type_id),
            "payment_type": к.payment_type,
            "credit_limit": _число(к.credit_limit),
            "deferral_days": к.deferral_days,
            "blocked": к.blocked, "blocked_reason": к.blocked_reason,
            "has_contract": к.has_contract,
            "debt": _число(services.customer_debt(session, к.id)),
            "overdue": _число(services.customer_overdue(session, к.id)),
            "active": к.active,
        } for к in клиенты],
        "routes": [{
            "uuid": м.uuid, "name": м.name, "weekday": м.weekday,
            "active": м.active,
            "stops": [{
                "customer_uuid": т.customer.uuid, "sort_order": т.sort_order,
            } for т in м.stops],
        } for м in маршруты],
        "promotions": [{
            "uuid": а.uuid, "name": а.name, "mechanic": а.mechanic,
            "date_from": а.date_from.isoformat() if а.date_from else "",
            "date_to": а.date_to.isoformat() if а.date_to else "",
            "segment_uuid": а.segment_uuid, "priority": а.priority,
            "percent": _число(а.percent),
            "buy_qty": _число(а.buy_qty),
            "bonus_product_uuid": а.bonus_product_uuid,
            "bonus_qty": _число(а.bonus_qty),
            "active": а.active,
            "products": [{
                "uuid": т.product_uuid, "is_group": т.is_group,
            } for т in а.products],
            "thresholds": [{
                "min_qty": _число(п.min_qty), "min_sum": _число(п.min_sum),
                "percent": _число(п.percent),
            } for п in а.thresholds],
        } for а in акции],
    }

    device.last_pull = серверное_время
    session.add(SyncLog(
        device_id=device.id, user_id=агент.id, direction="pull",
        counts=json.dumps({к: len(v) for к, v in ответ.items()
                           if isinstance(v, list)}, ensure_ascii=False),
        duration_ms=int((time.monotonic() - начало) * 1000)))
    session.commit()

    return ответ


def _uuid_категории(session: Session, category_id: int | None) -> str | None:
    if category_id is None:
        return None
    категория = session.get(ProductCategory, category_id)
    return категория.uuid if категория else None


def _uuid_вида_цены(session: Session, price_type_id: int | None) -> str | None:
    if price_type_id is None:
        return None
    вид = session.get(PriceType, price_type_id)
    return вид.uuid if вид else None


def _разобрать_момент(значение: str) -> datetime | None:
    if not значение:
        return None

    текст = значение.strip().replace("Z", "+00:00")
    try:
        момент = datetime.fromisoformat(текст)
    except ValueError:
        # Второй заход: пробел перед смещением — это след незакодированного
        # плюса из строки запроса. Заменяем только его, а не все пробелы:
        # «2026-08-14 09:00:00» — допустимая запись, и ломать её нельзя.
        исправленный = re.sub(r" (\d{2}:\d{2})$", r"+\1", текст)
        try:
            момент = datetime.fromisoformat(исправленный)
        except ValueError:
            raise HTTPException(status_code=400, detail="неверный формат since")
    if момент.tzinfo is None:
        момент = момент.replace(tzinfo=timezone.utc)
    return момент


# --- отправка документов -----------------------------------------------------

class СтрокаЗаказа(BaseModel):
    product_uuid: str
    qty: Decimal
    price: Decimal
    discount_percent: Decimal = Decimal(0)


class ЗаказСТелефона(BaseModel):
    client_uid: str = Field(min_length=8, max_length=36)
    customer_uuid: str
    warehouse_uuid: str | None = None
    date: date
    delivery_date: date | None = None
    payment_type: str = "cash"
    comment: str = ""
    lines: list[СтрокаЗаказа]


class ОплатаСТелефона(BaseModel):
    client_uid: str = Field(min_length=8, max_length=36)
    customer_uuid: str
    date: date
    amount: Decimal
    kind: str = "cash"
    comment: str = ""


class ВизитСТелефона(BaseModel):
    client_uid: str = Field(min_length=8, max_length=36)
    customer_uuid: str
    date: date
    started_at: datetime | None = None
    finished_at: datetime | None = None
    lat: float | None = None
    lon: float | None = None
    result: str = ""
    comment: str = ""


class ПакетОтправки(BaseModel):
    orders: list[ЗаказСТелефона] = []
    payments: list[ОплатаСТелефона] = []
    visits: list[ВизитСТелефона] = []


@router.post("/sync/push")
def push(пакет: ПакетОтправки, device: Device = Depends(current_device),
         session: Session = Depends(get_session)):
    """Приём документов с телефона.

    Каждый документ обрабатывается отдельно и отвечает за себя: один
    отклонённый заказ не должен ронять весь пакет. Телефон по ответу решает,
    что удалить из очереди, а что показать агенту как ошибку.
    """
    начало = time.monotonic()
    агент: User = device.user
    результат = {"orders": [], "payments": [], "visits": []}

    for заказ in пакет.orders:
        результат["orders"].append(_принять_заказ(session, агент, заказ))
    for оплата in пакет.payments:
        результат["payments"].append(_принять_оплату(session, агент, оплата))
    for визит in пакет.visits:
        результат["visits"].append(_принять_визит(session, агент, визит))

    session.add(SyncLog(
        device_id=device.id, user_id=агент.id, direction="push",
        counts=json.dumps({к: len(v) for к, v in результат.items()},
                          ensure_ascii=False),
        duration_ms=int((time.monotonic() - начало) * 1000)))
    session.commit()

    результат["server_time"] = _момент(datetime.now(timezone.utc))
    return результат


def _принять_заказ(session: Session, агент: User, данные: ЗаказСТелефона) -> dict:
    существующий = session.scalar(
        select(Order).where(Order.client_uid == данные.client_uid))
    if существующий is not None:
        # Повтор отправки. Возвращаем то же, что в первый раз: телефон
        # ждёт номер, чтобы показать его агенту и убрать заказ из очереди.
        return {"client_uid": данные.client_uid, "status": "accepted",
                "number": существующий.number, "state": существующий.status}

    клиент = session.scalar(
        select(Customer).where(Customer.uuid == данные.customer_uuid))
    if клиент is None:
        return _отказ(данные.client_uid, "клиент не найден на сервере")

    склад = None
    if данные.warehouse_uuid:
        склад = session.scalar(
            select(Warehouse).where(Warehouse.uuid == данные.warehouse_uuid))

    заказ = Order(
        client_uid=данные.client_uid,
        customer_id=клиент.id,
        agent_id=агент.id,
        warehouse_id=склад.id if склад else None,
        price_type_id=клиент.price_type_id,
        date=данные.date,
        delivery_date=данные.delivery_date,
        payment_type=данные.payment_type,
        comment=данные.comment,
        source="mobile",
        status="new",
    )

    for строка in данные.lines:
        товар = session.scalar(
            select(Product).where(Product.uuid == строка.product_uuid))
        if товар is None:
            return _отказ(данные.client_uid,
                          f"товар не найден: {строка.product_uuid}")
        заказ.lines.append(OrderLine(
            product_id=товар.id, qty=строка.qty, price=строка.price,
            discount_percent=строка.discount_percent, vat_rate=товар.vat_rate))

    services.recalc_order(заказ)

    try:
        services.check_order_allowed(session, заказ)
    except services.ОшибкаПравил as ошибка:
        # Заказ не сохраняем, но и не теряем: телефон получит текст и покажет
        # его агенту, а тот решит — снять позицию или звонить в офис.
        return _отказ(данные.client_uid, str(ошибка))

    заказ.number = services.next_number(session, "order", "З")
    session.add(заказ)
    session.flush()

    return {"client_uid": данные.client_uid, "status": "accepted",
            "number": заказ.number, "state": заказ.status}


def _принять_оплату(session: Session, агент: User,
                    данные: ОплатаСТелефона) -> dict:
    существующая = session.scalar(
        select(Payment).where(Payment.client_uid == данные.client_uid))
    if существующая is not None:
        return {"client_uid": данные.client_uid, "status": "accepted",
                "number": существующая.number}

    клиент = session.scalar(
        select(Customer).where(Customer.uuid == данные.customer_uuid))
    if клиент is None:
        return _отказ(данные.client_uid, "клиент не найден на сервере")
    if данные.amount <= 0:
        return _отказ(данные.client_uid, "сумма оплаты должна быть больше нуля")

    оплата = Payment(
        client_uid=данные.client_uid,
        number=services.next_number(session, "payment", "П"),
        customer_id=клиент.id,
        agent_id=агент.id,
        date=данные.date,
        amount=services.округлить(данные.amount),
        kind=данные.kind,
        comment=данные.comment,
    )
    session.add(оплата)
    session.flush()

    return {"client_uid": данные.client_uid, "status": "accepted",
            "number": оплата.number}


def _принять_визит(session: Session, агент: User,
                   данные: ВизитСТелефона) -> dict:
    существующий = session.scalar(
        select(Visit).where(Visit.client_uid == данные.client_uid))
    if существующий is not None:
        return {"client_uid": данные.client_uid, "status": "accepted"}

    клиент = session.scalar(
        select(Customer).where(Customer.uuid == данные.customer_uuid))
    if клиент is None:
        return _отказ(данные.client_uid, "клиент не найден на сервере")

    if REQUIRE_VISIT_GPS and (данные.lat is None or данные.lon is None):
        return _отказ(данные.client_uid, "визит без координат не принимается")

    session.add(Visit(
        client_uid=данные.client_uid,
        agent_id=агент.id,
        customer_id=клиент.id,
        date=данные.date,
        started_at=данные.started_at,
        finished_at=данные.finished_at,
        lat=данные.lat, lon=данные.lon,
        result=данные.result, comment=данные.comment,
    ))

    # Координаты первого визита заполняют карточку точки: адреса на рынках
    # по названию не находятся, а агент стоит ровно там, где нужно.
    if клиент.lat is None and данные.lat is not None:
        клиент.lat, клиент.lon = данные.lat, данные.lon

    return {"client_uid": данные.client_uid, "status": "accepted"}


def _отказ(client_uid: str, причина: str) -> dict:
    return {"client_uid": client_uid, "status": "rejected", "error": причина}


# --- состояние ранее отправленного -------------------------------------------

@router.get("/orders/state")
def orders_state(since: str = "", device: Device = Depends(current_device),
                 session: Session = Depends(get_session)):
    """Что стало с заказами агента.

    Агенту в точке нужно знать, отгрузили ли прошлый заказ и не отменил ли
    его офис. Отдельным запросом, а не в общем pull: список коротких записей
    меняется чаще справочников и тянуть его можно чаще.
    """
    отсечка = _разобрать_момент(since)
    запрос = select(Order).where(Order.agent_id == device.user_id)
    if отсечка is not None:
        запрос = запрос.where(Order.updated_at > отсечка)

    заказы = session.scalars(
        запрос.order_by(Order.updated_at).limit(SYNC_PAGE_SIZE)).all()

    return {
        "server_time": _момент(datetime.now(timezone.utc)),
        "orders": [{
            "client_uid": з.client_uid, "number": з.number,
            "customer_uuid": з.customer.uuid,
            "date": з.date.isoformat(),
            "status": з.status, "amount": _число(з.amount),
            "cancelled_reason": з.cancelled_reason,
        } for з in заказы],
    }


@router.get("/ping")
def ping():
    """Проверка связи. Без авторизации: приложению нужно уметь отличать
    «нет сети» от «не тот пароль» до всякого входа."""
    return {"ok": True, "protocol": PROTOCOL,
            "server_time": _момент(datetime.now(timezone.utc))}
