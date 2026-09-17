"""Приём справочников и акций из УТ в SmartSale.

Запускается по расписанию (cron), отдельным процессом, а не внутри веб-сервера:
обмен не должен конкурировать с запросами телефонов и его удобно отлаживать и
читать в логах отдельно.

    /srv/smartsale/venv/bin/python -m app.sync_from_ut

Каталог SmartSale — производный от УТ: `uuid` каждой сущности = `uid` ссылки в
1С, приём это upsert по uuid (есть — обновляем поля из УТ, нет — создаём).
Поля, которые ведутся только в SmartSale (кредитный лимит, блокировка клиента,
штрихкод, кратность упаковки), при обновлении НЕ трогаем: их источник — кабинет,
а не УТ, и обмен не должен их затирать.

Порядок важен из-за ссылок: сначала организации/склады/виды цен, затем
категории и товары, затем цены и остатки (ссылаются на товары), затем клиенты
(ссылаются на виды цен и агентов), затем акции (ссылаются на товары/сегменты).
"""

import logging
import os
import sys
from collections import defaultdict
from datetime import date, datetime, timezone
from decimal import Decimal, InvalidOperation

from dotenv import load_dotenv

# .env лежит рядом с каталогом кода (/srv/smartsale/.env, код в app_src/app).
# systemd грузит его сам, но cron запускает процесс без окружения сервиса.
load_dotenv(os.path.join(os.path.dirname(__file__), "..", "..", ".env"))

from sqlalchemy import select  # noqa: E402  (после load_dotenv — читает DATABASE_URL)
from sqlalchemy.orm import Session  # noqa: E402

from . import ut_client  # noqa: E402
from .auth import check_secret, hash_secret  # noqa: E402
from .db import SessionLocal, engine  # noqa: E402
from .models import (  # noqa: E402
    Base, Customer, Organization, Price, PriceType, Product, ProductCategory,
    Promotion, PromotionProduct, PromotionThreshold, Route, RouteStop, Stock,
    Task, User, Warehouse,
)

log = logging.getLogger("sync_from_ut")


# --- преобразование значений -------------------------------------------------

def _dec(значение) -> Decimal:
    """Число из строки УТ. Пустое/битое → 0. Разделитель — точка."""
    if значение in (None, ""):
        return Decimal(0)
    try:
        return Decimal(str(значение))
    except (InvalidOperation, ValueError):
        return Decimal(0)


def _float(значение):
    """Координата из строки. Пустая → None (не 0: 0,0 — это точка в океане)."""
    if значение in (None, ""):
        return None
    try:
        return float(str(значение).replace(",", "."))
    except (ValueError, TypeError):
        return None


def _date(значение):
    """Дата «ГГГГ-ММ-ДД» → date, пустая → None."""
    if not значение:
        return None
    try:
        return date.fromisoformat(str(значение)[:10])
    except ValueError:
        return None


def _карта(session: Session, модель) -> dict[str, object]:
    """Существующие записи модели: uuid → объект. Разом, чтобы не дёргать базу
    на каждую строку приёма."""
    return {о.uuid: о for о in session.scalars(select(модель)).all()}


# --- разделы приёма ----------------------------------------------------------

def _принять_мету(session: Session) -> None:
    данные = ut_client.получить("meta")

    орг_карта = _карта(session, Organization)
    for э in данные.get("organizations", []):
        о = орг_карта.get(э["uid"]) or Organization(uuid=э["uid"])
        о.name = э.get("name", "")
        о.inn = э.get("inn", "")
        о.active = bool(э.get("active", True))
        if о.id is None:
            session.add(о)
            орг_карта[э["uid"]] = о

    скл_карта = _карта(session, Warehouse)
    for э in данные.get("warehouses", []):
        с = скл_карта.get(э["uid"]) or Warehouse(uuid=э["uid"])
        с.name = э.get("name", "")
        с.active = bool(э.get("active", True))
        if с.id is None:
            session.add(с)
            скл_карта[э["uid"]] = с

    вид_карта = _карта(session, PriceType)
    for э in данные.get("price_types", []):
        в = вид_карта.get(э["uid"]) or PriceType(uuid=э["uid"])
        в.name = э.get("name", "")
        в.active = True
        if в.id is None:
            session.add(в)
            вид_карта[э["uid"]] = в

    session.commit()
    # Менеджеры (агенты) из /meta намеренно не создаём: пользователю-агенту
    # нужны логин и пароль для входа в приложение, их УТ не даёт. Агент
    # заводится в кабинете с uuid = uid пользователя УТ, и клиент к нему
    # прицепляется по этому uuid (см. _принять_клиентов).


def _принять_товары(session: Session) -> None:
    элементы = ut_client.получить_страницами("products")
    группы = [э for э in элементы if э.get("is_group")]
    товары = [э for э in элементы if not э.get("is_group")]

    # Категории двумя проходами: сперва создаём/обновляем все, потом
    # проставляем родителя — родитель может прийти позже потомка (порядок
    # обхода в УТ по ссылке, а не по иерархии).
    кат_карта = _карта(session, ProductCategory)
    for э in группы:
        к = кат_карта.get(э["uid"]) or ProductCategory(uuid=э["uid"])
        к.name = э.get("name", "")
        к.active = bool(э.get("active", True))
        if к.id is None:
            session.add(к)
            кат_карта[э["uid"]] = к
    session.flush()

    for э in группы:
        к = кат_карта.get(э["uid"])
        родитель = кат_карта.get(э.get("parent_uid", ""))
        к.parent_id = родитель.id if родитель else None

    товар_карта = _карта(session, Product)
    for э in товары:
        т = товар_карта.get(э["uid"]) or Product(uuid=э["uid"])
        категория = кат_карта.get(э.get("parent_uid", ""))
        т.category_id = категория.id if категория else None
        т.code = э.get("code", "")
        т.name = э.get("name", "")
        т.unit = э.get("unit", "") or "шт"
        т.vat_rate = _dec(э.get("vat_rate"))
        т.active = bool(э.get("active", True))
        if т.id is None:
            session.add(т)
            товар_карта[э["uid"]] = т

    session.commit()


def _принять_цены(session: Session) -> None:
    товары = {т.uuid: т.id for т in session.scalars(select(Product)).all()}
    виды = {в.uuid: в.id for в in session.scalars(select(PriceType)).all()}
    цены = {(ц.product_id, ц.price_type_id): ц
            for ц in session.scalars(select(Price)).all()}

    for э in ut_client.получить_список("prices"):
        товар_id = товары.get(э.get("product_uid"))
        вид_id = виды.get(э.get("price_type_uid"))
        if товар_id is None or вид_id is None:
            continue
        ц = цены.get((товар_id, вид_id)) or Price(
            product_id=товар_id, price_type_id=вид_id)
        ц.price = _dec(э.get("price"))
        if ц.id is None:
            session.add(ц)
            цены[(товар_id, вид_id)] = ц

    session.commit()


def _принять_остатки(session: Session) -> None:
    # В SmartSale остаток хранится как qty − reserved. УТ отдаёт свободный
    # остаток напрямую (уже за вычетом резерва к отгрузке): кладём его как qty
    # при нулевом резерве, чтобы free на телефоне совпал с УТ. Резерв
    # SmartSale ведёт сам при подтверждении заказов — но каталог УТ-ведомый,
    # заказы в SmartSale не подтверждаются, поэтому резерв здесь всегда 0.
    товары = {т.uuid: т.id for т in session.scalars(select(Product)).all()}
    склады = {с.uuid: с.id for с in session.scalars(select(Warehouse)).all()}
    остатки = {(о.product_id, о.warehouse_id): о
               for о in session.scalars(select(Stock)).all()}

    виденные = set()
    for э in ut_client.получить_список("stocks"):
        товар_id = товары.get(э.get("product_uid"))
        склад_id = склады.get(э.get("warehouse_uid"))
        if товар_id is None or склад_id is None:
            continue
        о = остатки.get((товар_id, склад_id)) or Stock(
            product_id=товар_id, warehouse_id=склад_id)
        о.qty = _dec(э.get("free"))
        о.reserved = Decimal(0)
        if о.id is None:
            session.add(о)
            остатки[(товар_id, склад_id)] = о
        виденные.add((товар_id, склад_id))

    # Распроданное УТ не присылает (в остатках только ненулевые позиции).
    # Пропавшую пару «товар — склад» гасим в ноль, иначе на телефоне
    # распроданный товар остался бы в наличии, и его продали бы повторно.
    for ключ, о in остатки.items():
        if ключ not in виденные and о.qty != Decimal(0):
            о.qty = Decimal(0)
            о.reserved = Decimal(0)

    session.commit()


def _принять_агентов(session: Session) -> None:
    """Агенты — из менеджеров УТ (/meta managers). uuid агента SmartSale = uid
    пользователя УТ, по нему клиент цепляется к агенту (основной менеджер).

    Логин и пароль УТ не отдаёт, а войти в приложение агенту нужно по ним.
    Поэтому при СОЗДАНИИ ставим логин = uid (уникален, администратор
    переименует в кабинете) и пустой пароль — вход заблокирован, пока админ не
    задаст пароль. На обновлении логин/пароль/роль НЕ трогаем: их ведёт кабинет.
    """
    данные = ut_client.получить("meta")
    агенты = _карта(session, User)

    for э in данные.get("managers", []):
        uid = э.get("uid")
        if not uid:
            continue
        п = агенты.get(uid)
        if п is None:
            п = User(
                uuid=uid, login=uid, full_name="", password_hash="",
                role="agent", must_change_password=True)
            session.add(п)
            агенты[uid] = п
        п.full_name = э.get("name", "") or п.full_name
        п.active = bool(э.get("active", True))

    # Онбординг: логин и временный пароль агента заданы в УТ и приходят в
    # agent_accounts. УТ ведущий — при расхождении пароль/логин перезаписываем.
    # Хеш ставим, только когда присланный пароль не совпадает с хранимым, иначе
    # каждый обмен менял бы соль без нужды.
    #
    # Логин приводим к нижнему регистру: аутентификация (auth.find_user) и
    # заведение в кабинете (main) ищут/пишут login в lower(), иначе агент с
    # заглавной буквой в логине не войдёт. Логин уникален — чужой логин не
    # занимаем (иначе UNIQUE-нарушение уронило бы весь раздel), а логируем.
    занятые = {п.login: uid for uid, п in агенты.items()}
    for э in данные.get("agent_accounts", []):
        uid = э.get("uid")
        if not uid:
            continue
        п = агенты.get(uid)
        if п is None:
            п = User(
                uuid=uid, login=uid, full_name="", password_hash="",
                role="agent", must_change_password=True)
            session.add(п)
            агенты[uid] = п
            занятые[п.login] = uid
        логин = (э.get("login") or "").strip().lower()
        if логин and п.login != логин:
            хозяин = занятые.get(логин)
            if хозяин is not None and хозяин != uid:
                log.warning("логин «%s» уже занят другим агентом — пропущен", логин)
            else:
                занятые.pop(п.login, None)
                п.login = логин
                занятые[логин] = uid
        пароль = э.get("password") or ""
        if пароль and not check_secret(пароль, п.password_hash):
            п.password_hash = hash_secret(пароль)

    session.commit()


def _принять_клиентов(session: Session) -> None:
    виды = {в.uuid: в.id for в in session.scalars(select(PriceType)).all()}
    агенты = {п.uuid: п.id for п in session.scalars(select(User)).all()}
    клиенты = _карта(session, Customer)

    for э in ut_client.получить_страницами("customers"):
        к = клиенты.get(э["uid"]) or Customer(uuid=э["uid"])
        к.code = э.get("code", "")
        к.name = э.get("name", "")
        к.legal_name = э.get("legal_name", "")
        к.inn = э.get("inn", "")
        к.phone = э.get("phone", "")
        к.address = э.get("address", "")
        # Координаты точки уточняет агент на месте (адреса на рынках по названию
        # не находятся). Из УТ берём только как первичный ориентир и только
        # когда своих ещё нет — иначе приём затирал бы точную точку агента.
        if к.lat is None:
            широта, долгота = _float(э.get("lat")), _float(э.get("lon"))
            if широта is not None:
                к.lat, к.lon = широта, долгота
        к.price_type_id = виды.get(э.get("price_type_uid"))
        # Агент — пользователь SmartSale с uuid = uid менеджера УТ. Нет такого
        # (агент ещё не заведён в кабинете) → клиент без агента, в приложении
        # не покажется, пока агента не создадут.
        к.agent_id = агенты.get(э.get("manager_uid"))
        к.has_contract = bool(э.get("has_contract", True))
        к.active = bool(э.get("active", True))
        # payment_type, credit_limit, deferral_days, blocked ведутся в кабинете
        # SmartSale — при обновлении из УТ не трогаем.
        if к.id is None:
            session.add(к)
            клиенты[э["uid"]] = к

    session.commit()


def _принять_маршруты(session: Session) -> None:
    """Маршруты агентов из УТ (meta.agent_routes): агент → день недели →
    клиенты с порядком. Пересобираем Route/RouteStop целиком: УТ ведущий.
    Маршрут — на пару (агент, день); пропавшие из УТ гасим (active=false),
    чтобы снятая точка не осталась на телефоне.
    """
    данные = ut_client.получить("meta")
    агенты = {п.uuid: п.id for п in session.scalars(select(User)).all()}
    клиенты = {к.uuid: к.id for к in session.scalars(select(Customer)).all()}

    группы: dict[tuple[int, int], list[tuple[int, int]]] = defaultdict(list)
    for р in данные.get("agent_routes", []):
        агент_id = агенты.get(р.get("agent_uid"))
        клиент_id = клиенты.get(р.get("customer_uid"))
        if агент_id is None or клиент_id is None:
            continue
        день = int(_dec(р.get("weekday")))
        if день < 1 or день > 7:
            continue
        группы[(агент_id, день)].append((клиент_id, int(_dec(р.get("sort_order")))))

    маршруты = {(м.agent_id, м.weekday): м
                for м in session.scalars(select(Route)).all()}
    виденные = set()
    for (агент_id, день), точки in группы.items():
        м = маршруты.get((агент_id, день))
        if м is None:
            м = Route(agent_id=агент_id, weekday=день, name=f"День {день}",
                      active=True)
            session.add(м)
            session.flush()
            маршруты[(агент_id, день)] = м
        else:
            м.active = True
        # Точки маршрута переписываем целиком. flush ПОСЛЕ clear обязателен:
        # без него SQLAlchemy на commit вставит новые RouteStop раньше, чем
        # удалит старые, и та же пара (маршрут, клиент) нарушит UNIQUE —
        # раздел падал бы на каждом повторном обмене.
        м.stops.clear()
        session.flush()
        for клиент_id, порядок in точки:
            м.stops.append(RouteStop(customer_id=клиент_id, sort_order=порядок))
        виденные.add((агент_id, день))

    # Гасим пропавшие маршруты ТОЛЬКО если из УТ реально пришли данные: пустой
    # agent_routes (нет прав на регистр, сбой) иначе молча снёс бы все маршруты
    # с телефонов.
    if данные.get("agent_routes"):
        for ключ, м in маршруты.items():
            if ключ not in виденные and м.active:
                м.active = False
                м.stops.clear()

    session.commit()


def _принять_задания(session: Session) -> None:
    """Задания агентам из УТ (meta.agent_tasks): агент, клиент, дата, текст.
    Upsert по uuid = uid справочника SmartSale_Задания. Поля выполнения (done,
    comment, done_at, done_pushed) ставит агент/обратный канал — приём их НЕ
    трогает.

    УТ отдаёт только активные и невыполненные задания. Пропавшее из выдачи
    гасим active=false ТОЛЬКО когда данные реально пришли (как у маршрутов):
    пустой agent_tasks при сбое иначе снёс бы задания с телефонов. Важное
    исключение — уже выполненные задания (done): их УТ перестаёт отдавать сразу
    после приёма выполнения, и гасить их по отсутствию не нужно, иначе агент,
    ещё не забравший подтверждение, потерял бы отметку. Поэтому гасим только
    невыполненные.
    """
    данные = ut_client.получить("meta")
    задания_ут = данные.get("agent_tasks")
    агенты = {п.uuid: п.id for п in session.scalars(select(User)).all()}
    клиенты = {к.uuid: к.id for к in session.scalars(select(Customer)).all()}
    задания = _карта(session, Task)

    виденные = set()
    for э in задания_ут or []:
        uid = э.get("uid")
        if not uid:
            continue
        з = задания.get(uid) or Task(uuid=uid)
        з.agent_id = агенты.get(э.get("agent_uid"))
        з.customer_id = клиенты.get(э.get("customer_uid"))
        з.date = _date(э.get("date"))
        з.text = э.get("text", "")
        з.active = True
        if з.id is None:
            session.add(з)
            задания[uid] = з
        виденные.add(uid)

    if задания_ут:
        for uid, з in задания.items():
            if uid not in виденные and з.active and not з.done:
                з.active = False

    session.commit()


def _принять_акции(session: Session) -> None:
    элементы = ut_client.получить_список("promotions")
    пришедшие = {э["uid"] for э in элементы if э.get("uid")}
    акции = _карта(session, Promotion)

    for э in элементы:
        uid = э.get("uid")
        if not uid:
            continue
        а = акции.get(uid) or Promotion(uuid=uid)
        а.name = э.get("name", "")
        а.mechanic = э.get("mechanic", "")
        а.date_from = _date(э.get("date_from"))
        а.date_to = _date(э.get("date_to"))
        а.segment_uuid = э.get("segment_uuid", "")
        а.priority = int(_dec(э.get("priority")))
        а.percent = _dec(э.get("percent"))
        а.buy_qty = _dec(э.get("buy_qty"))
        а.bonus_product_uuid = э.get("bonus_product_uid", "")
        а.bonus_qty = _dec(э.get("bonus_qty"))
        а.active = True
        # Товары и пороги переписываем целиком: состав акции проще заменить,
        # чем сверять построчно, а объём мал.
        а.products = [
            PromotionProduct(
                product_uuid=т.get("uid", ""), is_group=bool(т.get("is_group")))
            for т in э.get("products", []) if т.get("uid")]
        а.thresholds = [
            PromotionThreshold(
                min_qty=_dec(п.get("min_qty")), min_sum=_dec(п.get("min_sum")),
                percent=_dec(п.get("percent")))
            for п in э.get("thresholds", [])]
        # Бампаем отметку изменения вручную: замена состава (товары/пороги) —
        # это DELETE/INSERT в дочерних таблицах, родительскую строку он не
        # трогает, и onupdate по promotions не сработал бы. Без этого акция с
        # изменённым только составом не попала бы в инкрементальный /sync/pull.
        # Акций немного, лишний повторный проезд по ним на телефон не в тягость.
        а.updated_at = datetime.now(timezone.utc)
        if а.id is None:
            session.add(а)
            акции[uid] = а

    # Пропавшие из выдачи гасим (в УТ деактивированы или удалены). Телефон
    # узнает об отмене обычной синхронизацией по active=false.
    for uid, а in акции.items():
        if uid not in пришедшие and а.active:
            а.active = False

    session.commit()


# --- запуск ------------------------------------------------------------------

РАЗДЕЛЫ = (
    ("организации/склады/виды цен", _принять_мету),
    ("товары и категории", _принять_товары),
    ("цены", _принять_цены),
    ("остатки", _принять_остатки),
    ("агенты", _принять_агентов),
    ("клиенты", _принять_клиентов),
    ("маршруты", _принять_маршруты),
    ("задания", _принять_задания),
    ("акции", _принять_акции),
)


def run_sync() -> int:
    """Полный приём. Возвращает число упавших разделов (0 — всё прошло).

    Разделы независимы и коммитятся по отдельности: сбой цен не должен
    откатывать уже принятые товары. Упавший раздел логируется и не роняет
    остальные — на следующем запуске он повторится.
    """
    # Создаём недостающие таблицы: cron может запустить приём раньше первого
    # рестарта веб-сервера после деплоя, а таблицы акций создаёт create_all.
    # Существующие таблицы create_all не меняет — это безопасно.
    Base.metadata.create_all(engine)

    ошибок = 0
    with SessionLocal() as session:
        for имя, обработчик in РАЗДЕЛЫ:
            try:
                обработчик(session)
                log.info("принято: %s", имя)
            except Exception:
                ошибок += 1
                session.rollback()
                log.exception("сбой приёма раздела «%s»", имя)
    return ошибок


def main() -> None:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s %(message)s")
    ошибок = run_sync()
    if ошибок:
        log.error("приём завершён с ошибками: разделов упало %d", ошибок)
        sys.exit(1)
    log.info("приём завершён успешно")


if __name__ == "__main__":
    main()
