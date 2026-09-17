"""Модель данных торгового приложения.

Приложение самостоятельное: справочники ведутся здесь, а не зеркалятся из
1С. Сопоставление с учётной системой делается на стороне 1С, поэтому полей
вида «ссылка 1С» в схеме нет намеренно — иначе пришлось бы поддерживать
соответствие в двух местах и мирить расхождения.

Вместо этого у каждой сущности, которая может выйти наружу, есть `uuid` —
собственный неизменяемый идентификатор. Он и служит ключом сопоставления:
код и наименование меняются, идентификатор — нет.

Деньги и количества хранятся Numeric, а не float: float даёт 0.1 + 0.2 =
0.30000000000000004, и на суммировании накладной это вылезает расхождением
в копейку, которое потом никто не может объяснить.
"""

# datetime импортируется модулем, а не именами. У документов есть колонка
# `date`, и при `from datetime import date` она перекрывает одноимённый тип:
# SQLAlchemy разбирает аннотации с учётом пространства имён класса, соседние
# поля вроде delivery_date переставали считаться необязательными и получали
# NOT NULL. Ошибка вылезала только на вставке заказа без даты доставки.
import datetime
import uuid as uuid_lib
from decimal import Decimal

from sqlalchemy import (
    Boolean, Date, DateTime, ForeignKey, Index, Integer, Numeric, SmallInteger,
    String, Text, UniqueConstraint, func,
)
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column, relationship


def new_uuid() -> str:
    return str(uuid_lib.uuid4())


# Суммы: до 999 999 999 999.99. Для сума это триллион, с запасом.
Money = Numeric(15, 2)
# Количества: три знака — весовой товар продаётся килограммами с граммами.
Qty = Numeric(15, 3)
# Проценты скидок и НДС.
Percent = Numeric(6, 2)


class Base(DeclarativeBase):
    pass


class Timestamped:
    """Отметка последнего изменения.

    Нужна не для отчётности, а для синхронизации: телефон присылает время
    прошлого обмена и получает только то, что изменилось после. Без такого
    поля пришлось бы каждый раз выкачивать весь каталог, а это на плохой
    связи в поле неприемлемо.
    """

    updated_at: Mapped[datetime.datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), onupdate=func.now(),
        index=True)


# --- пользователи и устройства -----------------------------------------------

class User(Base, Timestamped):
    """Пользователь: сотрудник офиса или торговый агент.

    Агент отличается от остальных не только правами: к нему привязаны
    клиенты, маршруты и заказы, поэтому роль хранится полем, а не выводится
    из наличия связей.
    """

    __tablename__ = "users"

    id: Mapped[int] = mapped_column(primary_key=True)
    uuid: Mapped[str] = mapped_column(String(36), unique=True, default=new_uuid)
    login: Mapped[str] = mapped_column(String(64), unique=True, index=True)
    password_hash: Mapped[str] = mapped_column(String(255))
    full_name: Mapped[str] = mapped_column(String(255))
    phone: Mapped[str] = mapped_column(String(32), default="")

    # admin — всё; supervisor — свои агенты и их заказы; operator — заказы и
    # клиенты без настроек; warehouse — только отгрузка; agent — только
    # мобильное приложение, в веб-кабинет не пускаем.
    role: Mapped[str] = mapped_column(String(16), default="agent", index=True)

    # Чей это агент. Супервизор видит заказы своих подчинённых.
    supervisor_id: Mapped[int | None] = mapped_column(ForeignKey("users.id"))

    active: Mapped[bool] = mapped_column(Boolean, default=True, index=True)
    must_change_password: Mapped[bool] = mapped_column(Boolean, default=True)
    last_login: Mapped[datetime.datetime | None] = mapped_column(DateTime(timezone=True))
    created_at: Mapped[datetime.datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now())

    devices: Mapped[list["Device"]] = relationship(
        back_populates="user", cascade="all, delete-orphan")

    @property
    def is_agent(self) -> bool:
        return self.role == "agent"

    @property
    def is_admin(self) -> bool:
        return self.role == "admin"

    @property
    def short_name(self) -> str:
        """Фамилия и инициалы: в списках полное ФИО не помещается."""
        части = self.full_name.split()
        if len(части) < 2:
            return self.full_name
        инициалы = "".join(f"{ч[0]}." for ч in части[1:3])
        return f"{части[0]} {инициалы}"


class Device(Base):
    """Телефон агента.

    Токен хранится хешем, как пароль: база с токенами в открытом виде — это
    готовый доступ ко всем учёткам агентов.

    Устройство привязывается к пользователю при первом входе. Второй телефон
    с тем же логином создаст вторую запись — это нормально (агент сменил
    аппарат), а старую отключает администратор. Заказы при этом не теряются:
    они привязаны к пользователю, а не к устройству.
    """

    __tablename__ = "devices"
    __table_args__ = (
        UniqueConstraint("user_id", "device_id", name="uq_device"),
    )

    id: Mapped[int] = mapped_column(primary_key=True)
    user_id: Mapped[int] = mapped_column(
        ForeignKey("users.id", ondelete="CASCADE"), index=True)
    # Идентификатор, который телефон генерирует сам при первой установке.
    device_id: Mapped[str] = mapped_column(String(64), index=True)
    name: Mapped[str] = mapped_column(String(128), default="")
    token_hash: Mapped[str] = mapped_column(String(255), default="")
    token_expires: Mapped[datetime.datetime | None] = mapped_column(DateTime(timezone=True))
    app_version: Mapped[str] = mapped_column(String(32), default="")
    active: Mapped[bool] = mapped_column(Boolean, default=True)
    last_seen: Mapped[datetime.datetime | None] = mapped_column(DateTime(timezone=True))
    # Когда телефон в последний раз успешно забрал справочники. Нужно для
    # разбора жалоб «у меня старые цены».
    last_pull: Mapped[datetime.datetime | None] = mapped_column(DateTime(timezone=True))
    created_at: Mapped[datetime.datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now())

    user: Mapped["User"] = relationship(back_populates="devices")


# --- организационные справочники ---------------------------------------------

class Organization(Base, Timestamped):
    """Юрлицо, от имени которого продаём."""

    __tablename__ = "organizations"

    id: Mapped[int] = mapped_column(primary_key=True)
    uuid: Mapped[str] = mapped_column(String(36), unique=True, default=new_uuid)
    name: Mapped[str] = mapped_column(String(255))
    inn: Mapped[str] = mapped_column(String(32), default="")
    active: Mapped[bool] = mapped_column(Boolean, default=True, index=True)


class Warehouse(Base, Timestamped):
    """Склад отгрузки."""

    __tablename__ = "warehouses"

    id: Mapped[int] = mapped_column(primary_key=True)
    uuid: Mapped[str] = mapped_column(String(36), unique=True, default=new_uuid)
    code: Mapped[str] = mapped_column(String(32), default="")
    name: Mapped[str] = mapped_column(String(255))
    active: Mapped[bool] = mapped_column(Boolean, default=True, index=True)


# --- каталог -----------------------------------------------------------------

class ProductCategory(Base, Timestamped):
    """Группа номенклатуры. Иерархия одноуровневой ссылкой на родителя."""

    __tablename__ = "product_categories"

    id: Mapped[int] = mapped_column(primary_key=True)
    uuid: Mapped[str] = mapped_column(String(36), unique=True, default=new_uuid)
    parent_id: Mapped[int | None] = mapped_column(
        ForeignKey("product_categories.id"), index=True)
    name: Mapped[str] = mapped_column(String(255))
    sort_order: Mapped[int] = mapped_column(Integer, default=100)
    active: Mapped[bool] = mapped_column(Boolean, default=True, index=True)


class Product(Base, Timestamped):
    """Товар.

    Кратность упаковки хранится отдельно от единицы измерения: агент в поле
    набирает коробками, а склад считает штуками, и пересчёт должен быть
    один и тот же на телефоне и на сервере.
    """

    __tablename__ = "products"

    id: Mapped[int] = mapped_column(primary_key=True)
    uuid: Mapped[str] = mapped_column(String(36), unique=True, default=new_uuid)
    category_id: Mapped[int | None] = mapped_column(
        ForeignKey("product_categories.id"), index=True)
    code: Mapped[str] = mapped_column(String(64), default="", index=True)
    name: Mapped[str] = mapped_column(String(255), index=True)
    unit: Mapped[str] = mapped_column(String(16), default="шт")
    # Сколько единиц в упаковке. 0 или 1 — упаковками не торгуем.
    package_qty: Mapped[Decimal] = mapped_column(Qty, default=Decimal(1))
    package_name: Mapped[str] = mapped_column(String(32), default="")
    barcode: Mapped[str] = mapped_column(String(64), default="", index=True)
    vat_rate: Mapped[Decimal] = mapped_column(Percent, default=Decimal(0))
    weight: Mapped[Decimal] = mapped_column(Qty, default=Decimal(0))
    # Путь к картинке относительно каталога media. Телефон тянет её отдельно
    # и только по требованию: гнать картинки в общей синхронизации — это
    # десятки мегабайт на мобильном интернете.
    image_path: Mapped[str] = mapped_column(String(255), default="")
    active: Mapped[bool] = mapped_column(Boolean, default=True, index=True)

    category: Mapped["ProductCategory | None"] = relationship()


class PriceType(Base, Timestamped):
    """Вид цены: розница, опт, дилер."""

    __tablename__ = "price_types"

    id: Mapped[int] = mapped_column(primary_key=True)
    uuid: Mapped[str] = mapped_column(String(36), unique=True, default=new_uuid)
    code: Mapped[str] = mapped_column(String(32), default="")
    name: Mapped[str] = mapped_column(String(128))
    # Вид цены по умолчанию для клиентов, у которых свой не задан.
    is_default: Mapped[bool] = mapped_column(Boolean, default=False)
    active: Mapped[bool] = mapped_column(Boolean, default=True, index=True)


class Price(Base, Timestamped):
    """Цена товара по виду цены.

    Одна действующая запись на пару «товар — вид цены». История цен здесь не
    ведётся: в заказе цена всё равно фиксируется строкой, и заказ прошлого
    месяца не поедет, даже если прайс переписали.
    """

    __tablename__ = "prices"
    __table_args__ = (
        UniqueConstraint("product_id", "price_type_id", name="uq_price"),
    )

    id: Mapped[int] = mapped_column(primary_key=True)
    product_id: Mapped[int] = mapped_column(
        ForeignKey("products.id", ondelete="CASCADE"), index=True)
    price_type_id: Mapped[int] = mapped_column(
        ForeignKey("price_types.id", ondelete="CASCADE"), index=True)
    price: Mapped[Decimal] = mapped_column(Money, default=Decimal(0))

    product: Mapped["Product"] = relationship()
    price_type: Mapped["PriceType"] = relationship()


class Stock(Base, Timestamped):
    """Остаток товара на складе.

    Свободный остаток считается как qty − reserved. Резерв растёт при
    подтверждении заказа и гасится при отгрузке: без него два агента продадут
    одну и ту же последнюю коробку, и разбираться будет склад.
    """

    __tablename__ = "stocks"
    __table_args__ = (
        UniqueConstraint("product_id", "warehouse_id", name="uq_stock"),
    )

    id: Mapped[int] = mapped_column(primary_key=True)
    product_id: Mapped[int] = mapped_column(
        ForeignKey("products.id", ondelete="CASCADE"), index=True)
    warehouse_id: Mapped[int] = mapped_column(
        ForeignKey("warehouses.id", ondelete="CASCADE"), index=True)
    qty: Mapped[Decimal] = mapped_column(Qty, default=Decimal(0))
    reserved: Mapped[Decimal] = mapped_column(Qty, default=Decimal(0))

    product: Mapped["Product"] = relationship()
    warehouse: Mapped["Warehouse"] = relationship()

    @property
    def free(self) -> Decimal:
        return self.qty - self.reserved


class StockMove(Base):
    """Движение по складу: что и почему изменило остаток.

    Остаток хранится агрегатом в Stock ради скорости выдачи на телефон, а
    здесь лежит история. Без неё расхождение остатка не расследуется вовсе:
    видно только «сейчас минус три», но не видно, кто их списал.
    """

    __tablename__ = "stock_moves"

    id: Mapped[int] = mapped_column(primary_key=True)
    product_id: Mapped[int] = mapped_column(ForeignKey("products.id"), index=True)
    warehouse_id: Mapped[int] = mapped_column(ForeignKey("warehouses.id"), index=True)
    # Со знаком: приход положительный, расход отрицательный.
    qty: Mapped[Decimal] = mapped_column(Qty, default=Decimal(0))
    reserved_delta: Mapped[Decimal] = mapped_column(Qty, default=Decimal(0))
    # order, shipment, return, manual, inventory
    doc_type: Mapped[str] = mapped_column(String(16), index=True)
    doc_id: Mapped[int | None] = mapped_column(Integer, index=True)
    comment: Mapped[str] = mapped_column(String(255), default="")
    user_id: Mapped[int | None] = mapped_column(ForeignKey("users.id"))
    at: Mapped[datetime.datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), index=True)


# --- клиенты -----------------------------------------------------------------

class Customer(Base, Timestamped):
    """Торговая точка.

    Юридическое лицо и точка продаж — разные вещи: у одного контрагента может
    быть десяток магазинов, отгрузка идёт в каждый, а долг считается общий.
    Поэтому реквизиты плательщика лежат здесь же полями, а не отдельной
    сущностью: на этом этапе разделять их незачем, а когда понадобится
    считать долг по юрлицу, добавится ссылка и данные переедут без потерь.
    """

    __tablename__ = "customers"

    id: Mapped[int] = mapped_column(primary_key=True)
    uuid: Mapped[str] = mapped_column(String(36), unique=True, default=new_uuid)
    code: Mapped[str] = mapped_column(String(32), default="", index=True)
    name: Mapped[str] = mapped_column(String(255), index=True)
    legal_name: Mapped[str] = mapped_column(String(255), default="")
    inn: Mapped[str] = mapped_column(String(32), default="")
    phone: Mapped[str] = mapped_column(String(32), default="")
    address: Mapped[str] = mapped_column(String(500), default="")
    # Координаты точки. Проставляются агентом при первом визите — адреса на
    # рынках и в махаллях по названию не находятся.
    lat: Mapped[float | None] = mapped_column()
    lon: Mapped[float | None] = mapped_column()

    price_type_id: Mapped[int | None] = mapped_column(
        ForeignKey("price_types.id"), index=True)
    # За кем закреплена точка. Агент видит в приложении только своих.
    agent_id: Mapped[int | None] = mapped_column(ForeignKey("users.id"), index=True)

    # cash — наличными при отгрузке, transfer — перечислением.
    payment_type: Mapped[str] = mapped_column(String(16), default="cash")
    # Разрешённый долг. 0 — только по предоплате или за наличные.
    credit_limit: Mapped[Decimal] = mapped_column(Money, default=Decimal(0))
    # Отсрочка платежа в днях: по ней считается просрочка.
    deferral_days: Mapped[int] = mapped_column(SmallInteger, default=0)
    # Запрет отгрузки: выставляется вручную, когда клиент перестал платить.
    blocked: Mapped[bool] = mapped_column(Boolean, default=False, index=True)
    blocked_reason: Mapped[str] = mapped_column(String(255), default="")

    comment: Mapped[str] = mapped_column(Text, default="")
    active: Mapped[bool] = mapped_column(Boolean, default=True, index=True)
    created_at: Mapped[datetime.datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now())

    price_type: Mapped["PriceType | None"] = relationship()
    agent: Mapped["User | None"] = relationship()


# --- маршруты и визиты -------------------------------------------------------

class Route(Base, Timestamped):
    """Маршрут агента на день недели.

    Маршрут — шаблон, а не план на конкретную дату: агент ходит по одним и
    тем же точкам каждый вторник. Отклонения фиксируются визитами, а не
    правкой маршрута.
    """

    __tablename__ = "routes"

    id: Mapped[int] = mapped_column(primary_key=True)
    uuid: Mapped[str] = mapped_column(String(36), unique=True, default=new_uuid)
    agent_id: Mapped[int] = mapped_column(ForeignKey("users.id"), index=True)
    name: Mapped[str] = mapped_column(String(128), default="")
    # 1 — понедельник, 7 — воскресенье. Как в ISO, чтобы не спорить о нуле.
    weekday: Mapped[int] = mapped_column(SmallInteger, index=True)
    active: Mapped[bool] = mapped_column(Boolean, default=True, index=True)

    agent: Mapped["User"] = relationship()
    stops: Mapped[list["RouteStop"]] = relationship(
        back_populates="route", cascade="all, delete-orphan",
        order_by="RouteStop.sort_order")


class RouteStop(Base):
    """Точка в маршруте."""

    __tablename__ = "route_stops"
    __table_args__ = (
        UniqueConstraint("route_id", "customer_id", name="uq_route_stop"),
    )

    id: Mapped[int] = mapped_column(primary_key=True)
    route_id: Mapped[int] = mapped_column(
        ForeignKey("routes.id", ondelete="CASCADE"), index=True)
    customer_id: Mapped[int] = mapped_column(ForeignKey("customers.id"), index=True)
    sort_order: Mapped[int] = mapped_column(Integer, default=100)

    route: Mapped["Route"] = relationship(back_populates="stops")
    customer: Mapped["Customer"] = relationship()


class Visit(Base, Timestamped):
    """Визит агента в точку.

    Заводится телефоном, поэтому у него есть client_uid: сеть на рынке
    пропадает посреди отправки, телефон повторяет запрос, и без ключа
    идемпотентности в базе появлялись бы двойники.
    """

    __tablename__ = "visits"

    id: Mapped[int] = mapped_column(primary_key=True)
    client_uid: Mapped[str] = mapped_column(String(36), unique=True, index=True)
    agent_id: Mapped[int] = mapped_column(ForeignKey("users.id"), index=True)
    customer_id: Mapped[int] = mapped_column(ForeignKey("customers.id"), index=True)
    date: Mapped[datetime.date] = mapped_column(Date, index=True)
    started_at: Mapped[datetime.datetime | None] = mapped_column(DateTime(timezone=True))
    finished_at: Mapped[datetime.datetime | None] = mapped_column(DateTime(timezone=True))
    lat: Mapped[float | None] = mapped_column()
    lon: Mapped[float | None] = mapped_column()
    # order — оформлен заказ, no_order — отказ, closed — точка закрыта
    result: Mapped[str] = mapped_column(String(16), default="", index=True)
    comment: Mapped[str] = mapped_column(Text, default="")

    agent: Mapped["User"] = relationship()
    customer: Mapped["Customer"] = relationship()


# --- заказы ------------------------------------------------------------------

class Order(Base, Timestamped):
    """Заказ клиента.

    Жизненный путь: new → confirmed → picking → shipped, либо cancelled на
    любом шаге до отгрузки. Резерв на складе появляется при confirmed и
    снимается при shipped или cancelled.

    Номер присваивает сервер, а не телефон: у телефона нет способа выдать
    сквозной номер, не столкнувшись с соседним аппаратом. Пока заказ лежит в
    телефоне, он опознаётся по client_uid.
    """

    __tablename__ = "orders"
    __table_args__ = (
        Index("ix_orders_customer_date", "customer_id", "date"),
    )

    id: Mapped[int] = mapped_column(primary_key=True)
    uuid: Mapped[str] = mapped_column(String(36), unique=True, default=new_uuid)
    # Ключ идемпотентности: его генерирует телефон при создании заказа.
    # Повторная отправка того же заказа не создаёт второй.
    client_uid: Mapped[str] = mapped_column(String(36), unique=True, index=True)
    number: Mapped[str] = mapped_column(String(32), default="", index=True)

    customer_id: Mapped[int] = mapped_column(ForeignKey("customers.id"), index=True)
    agent_id: Mapped[int | None] = mapped_column(ForeignKey("users.id"), index=True)
    organization_id: Mapped[int | None] = mapped_column(ForeignKey("organizations.id"))
    warehouse_id: Mapped[int | None] = mapped_column(ForeignKey("warehouses.id"))
    price_type_id: Mapped[int | None] = mapped_column(ForeignKey("price_types.id"))

    date: Mapped[datetime.date] = mapped_column(Date, index=True)
    delivery_date: Mapped[datetime.date | None] = mapped_column(Date, index=True)
    payment_type: Mapped[str] = mapped_column(String(16), default="cash")

    status: Mapped[str] = mapped_column(String(16), default="new", index=True)
    amount: Mapped[Decimal] = mapped_column(Money, default=Decimal(0))
    discount_amount: Mapped[Decimal] = mapped_column(Money, default=Decimal(0))
    vat_amount: Mapped[Decimal] = mapped_column(Money, default=Decimal(0))

    comment: Mapped[str] = mapped_column(Text, default="")
    # mobile — с телефона агента, web — оператором из кабинета.
    source: Mapped[str] = mapped_column(String(16), default="mobile")
    # Когда телефон прислал заказ. Отличается от date: заказ, снятый вечером
    # без связи, приезжает утром следующего дня, и путать эти даты нельзя.
    received_at: Mapped[datetime.datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now())
    cancelled_reason: Mapped[str] = mapped_column(String(255), default="")

    customer: Mapped["Customer"] = relationship()
    agent: Mapped["User | None"] = relationship()
    warehouse: Mapped["Warehouse | None"] = relationship()
    lines: Mapped[list["OrderLine"]] = relationship(
        back_populates="order", cascade="all, delete-orphan")


class OrderLine(Base):
    """Строка заказа.

    Цена фиксируется здесь, а не берётся из прайса при показе: прайс меняют,
    а обещанная клиенту цена меняться не должна.
    """

    __tablename__ = "order_lines"

    id: Mapped[int] = mapped_column(primary_key=True)
    order_id: Mapped[int] = mapped_column(
        ForeignKey("orders.id", ondelete="CASCADE"), index=True)
    product_id: Mapped[int] = mapped_column(ForeignKey("products.id"), index=True)
    qty: Mapped[Decimal] = mapped_column(Qty, default=Decimal(0))
    price: Mapped[Decimal] = mapped_column(Money, default=Decimal(0))
    discount_percent: Mapped[Decimal] = mapped_column(Percent, default=Decimal(0))
    amount: Mapped[Decimal] = mapped_column(Money, default=Decimal(0))
    vat_rate: Mapped[Decimal] = mapped_column(Percent, default=Decimal(0))
    vat_amount: Mapped[Decimal] = mapped_column(Money, default=Decimal(0))

    order: Mapped["Order"] = relationship(back_populates="lines")
    product: Mapped["Product"] = relationship()


# --- отгрузка ----------------------------------------------------------------

class Shipment(Base, Timestamped):
    """Отгрузка по заказу.

    Отдельный документ, а не статус заказа: отгружают частями, и заказ на сто
    коробок может закрыться тремя накладными в разные дни. Долг клиента
    считается по отгруженному, а не по заказанному.
    """

    __tablename__ = "shipments"

    id: Mapped[int] = mapped_column(primary_key=True)
    uuid: Mapped[str] = mapped_column(String(36), unique=True, default=new_uuid)
    number: Mapped[str] = mapped_column(String(32), default="", index=True)
    order_id: Mapped[int | None] = mapped_column(ForeignKey("orders.id"), index=True)
    customer_id: Mapped[int] = mapped_column(ForeignKey("customers.id"), index=True)
    warehouse_id: Mapped[int | None] = mapped_column(ForeignKey("warehouses.id"))
    date: Mapped[datetime.date] = mapped_column(Date, index=True)
    # draft — собирается, shipped — отгружено, returned — возвращено целиком
    status: Mapped[str] = mapped_column(String(16), default="draft", index=True)
    amount: Mapped[Decimal] = mapped_column(Money, default=Decimal(0))
    # Срок оплаты: дата отгрузки плюс отсрочка клиента. Хранится, а не
    # вычисляется, потому что отсрочку клиенту могут поменять задним числом,
    # а срок по уже отгруженной накладной от этого сдвигаться не должен.
    due_date: Mapped[datetime.date | None] = mapped_column(Date, index=True)
    comment: Mapped[str] = mapped_column(Text, default="")
    created_by: Mapped[int | None] = mapped_column(ForeignKey("users.id"))
    created_at: Mapped[datetime.datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now())

    customer: Mapped["Customer"] = relationship()
    order: Mapped["Order | None"] = relationship()
    lines: Mapped[list["ShipmentLine"]] = relationship(
        back_populates="shipment", cascade="all, delete-orphan")


class ShipmentLine(Base):
    __tablename__ = "shipment_lines"

    id: Mapped[int] = mapped_column(primary_key=True)
    shipment_id: Mapped[int] = mapped_column(
        ForeignKey("shipments.id", ondelete="CASCADE"), index=True)
    product_id: Mapped[int] = mapped_column(ForeignKey("products.id"), index=True)
    qty: Mapped[Decimal] = mapped_column(Qty, default=Decimal(0))
    price: Mapped[Decimal] = mapped_column(Money, default=Decimal(0))
    amount: Mapped[Decimal] = mapped_column(Money, default=Decimal(0))

    shipment: Mapped["Shipment"] = relationship(back_populates="lines")
    product: Mapped["Product"] = relationship()


# --- деньги ------------------------------------------------------------------

class Payment(Base, Timestamped):
    """Оплата от клиента.

    Может приходить с телефона (агент собрал наличные в точке), поэтому тоже
    с ключом идемпотентности.

    Привязка к накладной необязательна: клиент платит «в счёт долга», а не по
    конкретной накладной. Разнесение по накладным — задача учётной системы,
    здесь достаточно общего сальдо.
    """

    __tablename__ = "payments"

    id: Mapped[int] = mapped_column(primary_key=True)
    uuid: Mapped[str] = mapped_column(String(36), unique=True, default=new_uuid)
    client_uid: Mapped[str] = mapped_column(String(36), unique=True, index=True)
    number: Mapped[str] = mapped_column(String(32), default="", index=True)

    customer_id: Mapped[int] = mapped_column(ForeignKey("customers.id"), index=True)
    agent_id: Mapped[int | None] = mapped_column(ForeignKey("users.id"), index=True)
    shipment_id: Mapped[int | None] = mapped_column(ForeignKey("shipments.id"))

    date: Mapped[datetime.date] = mapped_column(Date, index=True)
    amount: Mapped[Decimal] = mapped_column(Money, default=Decimal(0))
    # cash — наличные у агента, transfer — на счёт, card — картой
    kind: Mapped[str] = mapped_column(String(16), default="cash", index=True)
    comment: Mapped[str] = mapped_column(Text, default="")
    # Наличные, собранные агентом, до сдачи в кассу висят на нём. Отметка
    # ставится при инкассации — иначе не видно, сколько денег у агента на
    # руках прямо сейчас.
    collected: Mapped[bool] = mapped_column(Boolean, default=False, index=True)
    collected_at: Mapped[datetime.datetime | None] = mapped_column(DateTime(timezone=True))
    received_at: Mapped[datetime.datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now())

    customer: Mapped["Customer"] = relationship()
    agent: Mapped["User | None"] = relationship()


# --- служебное ---------------------------------------------------------------

class SyncLog(Base):
    """Протокол обменов с телефонами.

    Первый вопрос при разборе «заказ не дошёл» — доходил ли телефон до
    сервера вообще. Без журнала на него нечем ответить.
    """

    __tablename__ = "sync_log"

    id: Mapped[int] = mapped_column(primary_key=True)
    device_id: Mapped[int | None] = mapped_column(ForeignKey("devices.id"), index=True)
    user_id: Mapped[int | None] = mapped_column(ForeignKey("users.id"), index=True)
    # pull — забрал справочники, push — прислал документы
    direction: Mapped[str] = mapped_column(String(8), index=True)
    counts: Mapped[str] = mapped_column(Text, default="")
    duration_ms: Mapped[int] = mapped_column(Integer, default=0)
    error: Mapped[str] = mapped_column(Text, default="")
    at: Mapped[datetime.datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), index=True)


class AuditLog(Base):
    """Кто что сделал. Заказы и оплаты — деньги, изменения фиксируются."""

    __tablename__ = "audit_log"

    id: Mapped[int] = mapped_column(primary_key=True)
    user_id: Mapped[int | None] = mapped_column(ForeignKey("users.id"))
    action: Mapped[str] = mapped_column(String(64), index=True)
    object_ref: Mapped[str] = mapped_column(String(128), default="")
    details: Mapped[str] = mapped_column(Text, default="")
    ip: Mapped[str] = mapped_column(String(45), default="")
    at: Mapped[datetime.datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), index=True)


class Counter(Base):
    """Счётчик номеров документов.

    Отдельная таблица, а не max(number)+1: два оператора, сохранившие заказ
    одновременно, получили бы один номер. Здесь номер выдаётся под блокировкой
    строки.
    """

    __tablename__ = "counters"

    name: Mapped[str] = mapped_column(String(32), primary_key=True)
    value: Mapped[int] = mapped_column(Integer, default=0)


class UtExport(Base):
    """Отметка об отправке документа SmartSale в УТ (обратный канал).

    Идемпотентность: по client_uid документ не шлём в УТ дважды. Есть строка —
    документ уже обработан: `ut_number` заполнен при приёме, `error` — при
    отказе (деловая причина, повтор не поможет). Нет строки — не отправляли
    (или был сбой связи), отправим на следующем прогоне. client_uid общий с
    телефоном и с журналом приёма УТ — одна сквозная нить идемпотентности.
    """

    __tablename__ = "ut_exports"

    # Ключ составной: одна отметка на (документ, вид). client_uid у заказа и
    # оплаты — независимые пространства uuid, но вид в ключе снимает и
    # теоретическое пересечение, и делает схему согласованной с отбором по виду.
    client_uid: Mapped[str] = mapped_column(String(36), primary_key=True)
    # order | payment
    kind: Mapped[str] = mapped_column(String(16), primary_key=True)
    ut_number: Mapped[str] = mapped_column(String(64), default="")
    error: Mapped[str] = mapped_column(Text, default="")
    at: Mapped[datetime.datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now())


# --- акции -------------------------------------------------------------------

class Promotion(Base, Timestamped):
    """Условие акции, настроенное в УТ.

    Считает акции движок приложения при наборе заказа, здесь — только условия,
    принятые из УТ (метод /promotions расширения). uuid = uid справочника
    SmartSale_Акции в 1С: приём — upsert по uuid, как у прочих справочников.

    Механика (`mechanic`): percent — процент на товары; volume — ступенчатая
    скидка от объёма (пороги); bonus — купи N — получи M бесплатно. Товары и
    пороги вынесены в отдельные таблицы. Сегмент клиента (`segment_uuid`) —
    uid сегмента УТ, пусто = всем; сопоставление клиента сегменту появится
    вместе с выгрузкой сегментов.

    Акции приходят полным списком активных. Пропавшая из выдачи акция гасится
    флагом active=false, а не удаляется: телефон должен узнать об отмене через
    обычную синхронизацию, а не по молчанию.
    """

    __tablename__ = "promotions"

    id: Mapped[int] = mapped_column(primary_key=True)
    uuid: Mapped[str] = mapped_column(String(36), unique=True, index=True)
    name: Mapped[str] = mapped_column(String(255), default="")
    # percent | volume | bonus
    mechanic: Mapped[str] = mapped_column(String(16), default="", index=True)
    date_from: Mapped[datetime.date | None] = mapped_column(Date)
    date_to: Mapped[datetime.date | None] = mapped_column(Date)
    # uid сегмента партнёров УТ; пусто — акция для всех клиентов.
    segment_uuid: Mapped[str] = mapped_column(String(36), default="")
    priority: Mapped[int] = mapped_column(Integer, default=0)

    # Для механики percent.
    percent: Mapped[Decimal] = mapped_column(Percent, default=Decimal(0))
    # Для механики bonus: купить N товаров условия → M бонусного товара.
    buy_qty: Mapped[Decimal] = mapped_column(Qty, default=Decimal(0))
    bonus_product_uuid: Mapped[str] = mapped_column(String(36), default="")
    bonus_qty: Mapped[Decimal] = mapped_column(Qty, default=Decimal(0))

    active: Mapped[bool] = mapped_column(Boolean, default=True, index=True)

    products: Mapped[list["PromotionProduct"]] = relationship(
        back_populates="promotion", cascade="all, delete-orphan")
    thresholds: Mapped[list["PromotionThreshold"]] = relationship(
        back_populates="promotion", cascade="all, delete-orphan")


class PromotionProduct(Base):
    """Товар (или группа) — область действия акции.

    Хранится uid УТ и признак группы, а не ссылка на products: элемент может
    быть группой номенклатуры (в products её нет — она приходит категорией),
    и раскрывает группу движок приложения по своему каталогу.
    """

    __tablename__ = "promotion_products"

    id: Mapped[int] = mapped_column(primary_key=True)
    promotion_id: Mapped[int] = mapped_column(
        ForeignKey("promotions.id", ondelete="CASCADE"), index=True)
    product_uuid: Mapped[str] = mapped_column(String(36), index=True)
    is_group: Mapped[bool] = mapped_column(Boolean, default=False)

    promotion: Mapped["Promotion"] = relationship(back_populates="products")


class PromotionThreshold(Base):
    """Ступень объёмной акции: от порога по количеству/сумме — свой процент."""

    __tablename__ = "promotion_thresholds"

    id: Mapped[int] = mapped_column(primary_key=True)
    promotion_id: Mapped[int] = mapped_column(
        ForeignKey("promotions.id", ondelete="CASCADE"), index=True)
    min_qty: Mapped[Decimal] = mapped_column(Qty, default=Decimal(0))
    min_sum: Mapped[Decimal] = mapped_column(Money, default=Decimal(0))
    percent: Mapped[Decimal] = mapped_column(Percent, default=Decimal(0))

    promotion: Mapped["Promotion"] = relationship(back_populates="thresholds")
