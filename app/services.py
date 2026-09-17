"""Правила предметной области: номера, суммы, резервы, долг.

Всё, что меняет остатки и деньги, живёт здесь, а не в обработчиках. Заказ
приходит двумя путями — с телефона и из кабинета, — и повторять одни и те же
правила в двух местах значит однажды поправить их только в одном.
"""

from datetime import date, datetime, timedelta, timezone
from decimal import ROUND_HALF_UP, Decimal

from sqlalchemy import func, select
from sqlalchemy.orm import Session

from .config import AMOUNT_SCALE
from .models import (
    Counter, Customer, Order, OrderLine, Payment, Price, Product, Shipment,
    ShipmentLine, Stock, StockMove,
)

ЦЕНТ = Decimal(10) ** -AMOUNT_SCALE


class ОшибкаПравил(Exception):
    """Нарушено правило предметной области. Текст показывается человеку —
    и агенту в телефоне, и оператору в кабинете, — поэтому без жаргона."""


def округлить(значение: Decimal) -> Decimal:
    return Decimal(значение).quantize(ЦЕНТ, rounding=ROUND_HALF_UP)


# --- номера документов -------------------------------------------------------

def next_number(session: Session, counter: str, prefix: str) -> str:
    """Следующий номер документа.

    Строка счётчика блокируется до конца транзакции: два оператора,
    сохранившие заказ одновременно, иначе получили бы один номер.
    """
    row = session.execute(
        select(Counter).where(Counter.name == counter).with_for_update()
    ).scalar_one_or_none()

    if row is None:
        row = Counter(name=counter, value=0)
        session.add(row)
        session.flush()

    row.value += 1
    session.flush()
    return f"{prefix}{row.value:06d}"


# --- цены --------------------------------------------------------------------

def price_for(session: Session, product_id: int, price_type_id: int | None) -> Decimal:
    """Цена товара по виду цены. Нет цены — ноль, но заказ с нулём не пройдёт
    проверку: продавать по нулю нельзя, а молча подставлять чужой прайс —
    тем более."""
    if price_type_id is None:
        return Decimal(0)
    цена = session.scalar(
        select(Price.price).where(
            Price.product_id == product_id,
            Price.price_type_id == price_type_id))
    return цена or Decimal(0)


# --- заказ -------------------------------------------------------------------

def recalc_order(order: Order) -> None:
    """Пересчитать суммы строк и шапки.

    НДС считается «в том числе»: цены в прайсе с налогом, так принято в
    рознице, и выделять его сверху означало бы показать клиенту одну сумму,
    а выставить другую.
    """
    итог = Decimal(0)
    скидка = Decimal(0)
    ндс = Decimal(0)

    for строка in order.lines:
        # Значения по умолчанию из модели проставляются при записи в базу, а
        # пересчёт идёт до неё: у строки, только что собранной в памяти,
        # незаполненные поля равны None, а не нулю.
        строка.qty = строка.qty or Decimal(0)
        строка.price = строка.price or Decimal(0)
        строка.discount_percent = строка.discount_percent or Decimal(0)
        строка.vat_rate = строка.vat_rate or Decimal(0)

        без_скидки = округлить(строка.qty * строка.price)
        сумма_скидки = округлить(без_скидки * строка.discount_percent / 100)
        строка.amount = округлить(без_скидки - сумма_скидки)

        ставка = строка.vat_rate
        строка.vat_amount = округлить(строка.amount * ставка / (100 + ставка)) \
            if ставка else Decimal(0)

        итог += строка.amount
        скидка += сумма_скидки
        ндс += строка.vat_amount

    order.amount = округлить(итог)
    order.discount_amount = округлить(скидка)
    order.vat_amount = округлить(ндс)


def check_order_allowed(session: Session, order: Order) -> None:
    """Можно ли принимать заказ от этого клиента.

    Проверка при приёме, а не при отгрузке: сказать агенту «клиент в стопе»,
    пока он стоит в точке, полезно — он поговорит о долге на месте. Узнать
    это через два дня на складе бесполезно.
    """
    клиент = session.get(Customer, order.customer_id)
    if клиент is None:
        raise ОшибкаПравил("клиент не найден")
    if not клиент.active:
        raise ОшибкаПравил(f"клиент «{клиент.name}» отключён")
    if клиент.blocked:
        причина = клиент.blocked_reason or "без указания причины"
        raise ОшибкаПравил(f"отгрузка клиенту «{клиент.name}» запрещена: {причина}")

    if not order.lines:
        raise ОшибкаПравил("в заказе нет строк")

    for строка in order.lines:
        if строка.qty <= 0:
            товар = session.get(Product, строка.product_id)
            имя = товар.name if товар else строка.product_id
            raise ОшибкаПравил(f"нулевое количество: {имя}")
        if строка.price <= 0:
            товар = session.get(Product, строка.product_id)
            имя = товар.name if товар else строка.product_id
            raise ОшибкаПравил(f"не задана цена: {имя}")

    # Кредитный контроль (Фаза 1). Долг, лимит и просрочка — мастер в УТ
    # (зеркалятся в Customer при обмене), не локальный расчёт: он знал только
    # заказы этого приложения. Проверяем лишь заказ «в долг» (не за наличные) —
    # наличная оплата долг не наращивает. Правила принуждаются ровно по флагам
    # договора: forbid_overdue (ЗапрещаетсяПросроченнаяЗадолженность) и
    # limit_enabled (ОграничиватьСуммуЗадолженности). Держим ту же логику, что
    # и блок на телефоне, чтобы сервер и приложение не расходились.
    if order.payment_type != "cash":
        if клиент.forbid_overdue and клиент.overdue_debt > 0:
            raise ОшибкаПравил(
                f"у клиента «{клиент.name}» просроченный долг "
                f"{клиент.overdue_debt:.0f}: отгрузка в долг запрещена")
        if клиент.limit_enabled and клиент.debt + order.amount > клиент.credit_limit:
            raise ОшибкаПравил(
                f"превышен лимит клиента «{клиент.name}»: "
                f"долг {клиент.debt:.0f}, заказ {order.amount:.0f}, "
                f"лимит {клиент.credit_limit:.0f}")


def confirm_order(session: Session, order: Order) -> None:
    """Подтвердить заказ и зарезервировать товар.

    Резерв ставится на складе заказа. Не хватает свободного остатка — заказ
    подтверждается всё равно, но с пометкой: отказывать клиенту из-за
    неточного остатка хуже, чем привезти на день позже. Дефицит видно в
    кабинете списком.
    """
    if order.status != "new":
        raise ОшибкаПравил("подтвердить можно только новый заказ")
    if order.warehouse_id is None:
        raise ОшибкаПравил("не указан склад")

    for строка in order.lines:
        остаток = _stock_row(session, строка.product_id, order.warehouse_id)
        остаток.reserved += строка.qty
        session.add(StockMove(
            product_id=строка.product_id, warehouse_id=order.warehouse_id,
            qty=Decimal(0), reserved_delta=строка.qty,
            doc_type="order", doc_id=order.id, comment="резерв по заказу"))

    order.status = "confirmed"


def cancel_order(session: Session, order: Order, reason: str) -> None:
    """Отменить заказ и снять резерв."""
    if order.status in ("shipped", "cancelled"):
        raise ОшибкаПравил("заказ уже закрыт")

    if order.status in ("confirmed", "picking"):
        _release_reserve(session, order)

    order.status = "cancelled"
    order.cancelled_reason = reason


def _release_reserve(session: Session, order: Order) -> None:
    for строка in order.lines:
        остаток = _stock_row(session, строка.product_id, order.warehouse_id)
        остаток.reserved -= строка.qty
        if остаток.reserved < 0:
            остаток.reserved = Decimal(0)
        session.add(StockMove(
            product_id=строка.product_id, warehouse_id=order.warehouse_id,
            qty=Decimal(0), reserved_delta=-строка.qty,
            doc_type="order", doc_id=order.id, comment="снятие резерва"))


def _stock_row(session: Session, product_id: int, warehouse_id: int) -> Stock:
    остаток = session.scalar(
        select(Stock).where(
            Stock.product_id == product_id,
            Stock.warehouse_id == warehouse_id).with_for_update())
    if остаток is None:
        остаток = Stock(product_id=product_id, warehouse_id=warehouse_id,
                        qty=Decimal(0), reserved=Decimal(0))
        session.add(остаток)
        session.flush()
    return остаток


# --- отгрузка ----------------------------------------------------------------

def ship_order(session: Session, order: Order, user_id: int | None = None,
               on: date | None = None) -> Shipment:
    """Отгрузить заказ целиком и списать остаток.

    Частичная отгрузка на этом этапе не поддержана: документ уже отдельный,
    так что она добавится строками с количеством меньше заказанного, не меняя
    схему.
    """
    if order.status not in ("confirmed", "picking"):
        raise ОшибкаПравил("отгружать можно подтверждённый заказ")

    клиент = session.get(Customer, order.customer_id)
    дата = on or date.today()

    отгрузка = Shipment(
        number=next_number(session, "shipment", "О"),
        order_id=order.id,
        customer_id=order.customer_id,
        warehouse_id=order.warehouse_id,
        date=дата,
        status="shipped",
        amount=order.amount,
        due_date=дата + timedelta(days=клиент.deferral_days if клиент else 0),
        created_by=user_id,
    )
    session.add(отгрузка)
    session.flush()

    for строка in order.lines:
        отгрузка.lines.append(ShipmentLine(
            product_id=строка.product_id, qty=строка.qty,
            price=строка.price, amount=строка.amount))

        остаток = _stock_row(session, строка.product_id, order.warehouse_id)
        остаток.qty -= строка.qty
        остаток.reserved -= строка.qty
        if остаток.reserved < 0:
            остаток.reserved = Decimal(0)
        session.add(StockMove(
            product_id=строка.product_id, warehouse_id=order.warehouse_id,
            qty=-строка.qty, reserved_delta=-строка.qty,
            doc_type="shipment", doc_id=отгрузка.id, user_id=user_id,
            comment=f"отгрузка {отгрузка.number}"))

    order.status = "shipped"
    session.flush()
    return отгрузка


# --- деньги ------------------------------------------------------------------

def customer_debt(session: Session, customer_id: int) -> Decimal:
    """Долг клиента — из зеркала УТ (Customer.debt).

    Мастер долга — УТ (регистр РасчетыСКлиентамиПоСрокам): значение зеркалится
    в Customer при обмене. Раньше долг считался локально (отгрузки−оплаты
    SmartSale), но в связке с УТ отгрузок здесь нет, и единственная достоверная
    цифра приходит из УТ. Так офис (кабинет) и агент (телефон) видят один долг.
    """
    клиент = session.get(Customer, customer_id)
    return округлить(клиент.debt) if клиент else Decimal(0)


def customer_overdue(session: Session, customer_id: int,
                     on: date | None = None) -> Decimal:
    """Просроченная часть долга — из зеркала УТ (Customer.overdue_debt).

    См. customer_debt: мастер — УТ. Параметр on оставлен для совместимости
    вызовов; зеркало отражает состояние на момент последнего обмена.
    """
    клиент = session.get(Customer, customer_id)
    return округлить(клиент.overdue_debt) if клиент else Decimal(0)


def agent_cash_on_hand(session: Session, agent_id: int) -> Decimal:
    """Наличные, собранные агентом и не сданные в кассу."""
    сумма = session.scalar(
        select(func.coalesce(func.sum(Payment.amount), 0)).where(
            Payment.agent_id == agent_id,
            Payment.kind == "cash",
            Payment.collected.is_(False))) or Decimal(0)
    return округлить(Decimal(сумма))


def collect_cash(session: Session, agent_id: int,
                 payment_ids: list[int] | None = None) -> Decimal:
    """Отметить инкассацию: деньги приняты в кассу."""
    запрос = select(Payment).where(
        Payment.agent_id == agent_id,
        Payment.kind == "cash",
        Payment.collected.is_(False))
    if payment_ids:
        запрос = запрос.where(Payment.id.in_(payment_ids))

    сумма = Decimal(0)
    момент = datetime.now(timezone.utc)
    for оплата in session.scalars(запрос):
        оплата.collected = True
        оплата.collected_at = момент
        сумма += оплата.amount

    return округлить(сумма)
