"""Веб-бэкофис: заказы, клиенты, каталог, дебиторка, отгрузка, агенты."""

import logging
from datetime import date, datetime, timedelta, timezone
from decimal import Decimal, InvalidOperation

from fastapi import Depends, FastAPI, Form, HTTPException, Request
from fastapi.responses import RedirectResponse
from fastapi.templating import Jinja2Templates
from sqlalchemy import func, or_, select
from sqlalchemy.orm import Session

from . import services
from .api import router as api_router
from .auth import (
    COOKIE, admin_only, check_secret, current_user, find_user, hash_secret,
    make_session, office_only, random_password, с_поясом, НужнаСессия,
)
from .config import CURRENCY, SECRET_KEY, SESSION_HOURS
from .db import SessionLocal, engine, get_session
from .models import (
    Base, Customer, Device, Order, OrderLine, Payment, Price, PriceType,
    Product, ProductCategory, Route, RouteStop, Shipment, Stock, SyncLog, User,
    Visit, Warehouse,
)

log = logging.getLogger("smartsale")

app = FastAPI(title="SmartSale", docs_url=None, redoc_url=None)
app.include_router(api_router)

templates = Jinja2Templates(directory="app/templates")
templates.env.globals["currency"] = CURRENCY

СТАТУСЫ_ЗАКАЗА = {
    "new": "новый",
    "confirmed": "подтверждён",
    "picking": "в сборке",
    "shipped": "отгружен",
    "cancelled": "отменён",
}
templates.env.globals["статусы_заказа"] = СТАТУСЫ_ЗАКАЗА


def деньги(значение) -> str:
    """Сумма с разделителями разрядов. Суммы в сумах шестизначные и длиннее,
    без разделителей их не прочитать."""
    if значение is None:
        return "—"
    return f"{Decimal(значение):,.0f}".replace(",", " ")


templates.env.filters["деньги"] = деньги
templates.env.filters["кол"] = lambda з: f"{Decimal(з):g}" if з is not None else "—"


@app.on_event("startup")
def startup():
    if not SECRET_KEY:
        # Без ключа подпись куки предсказуема, вход подделывается. Падаем
        # сразу, а не работаем незаметно небезопасно.
        raise RuntimeError("не задан SECRET_KEY — приложение не запускается")

    Base.metadata.create_all(engine)

    with SessionLocal() as session:
        есть = session.scalar(select(func.count(User.id)))
        if not есть:
            пароль = random_password(12)
            session.add(User(
                login="admin", password_hash=hash_secret(пароль),
                full_name="Администратор", role="admin",
                must_change_password=True))
            session.commit()
            # Единственный раз, когда пароль виден. Дальше только сброс.
            log.warning("создана учётная запись admin, пароль: %s", пароль)


@app.exception_handler(НужнаСессия)
def нет_сессии(request: Request, exc: НужнаСессия):
    if request.url.path.startswith("/api/"):
        return RedirectResponse("/login", status_code=307)
    return RedirectResponse(f"/login?next={request.url.path}", status_code=303)


# --- вход --------------------------------------------------------------------

@app.get("/login")
def login_form(request: Request, next: str = "/", error: str = ""):
    return templates.TemplateResponse(
        request, "login.html", {"next": next, "error": error})


@app.post("/login")
def login(request: Request, login: str = Form(...), password: str = Form(...),
          next: str = Form("/"), session: Session = Depends(get_session)):
    пользователь = find_user(session, login)
    if (пользователь is None or not пользователь.active
            or not check_secret(password, пользователь.password_hash)):
        return templates.TemplateResponse(
            request, "login.html",
            {"next": next, "error": "неверный логин или пароль"},
            status_code=401)

    if пользователь.is_agent:
        return templates.TemplateResponse(
            request, "login.html",
            {"next": next,
             "error": "агенту доступно мобильное приложение, не кабинет"},
            status_code=403)

    пользователь.last_login = datetime.now(timezone.utc)
    session.commit()

    ответ = RedirectResponse(next or "/", status_code=303)
    ответ.set_cookie(
        COOKIE, make_session(пользователь.id), httponly=True, samesite="lax",
        max_age=SESSION_HOURS * 3600)
    return ответ


@app.get("/logout")
def logout():
    ответ = RedirectResponse("/login", status_code=303)
    ответ.delete_cookie(COOKIE)
    return ответ


@app.post("/password")
def change_password(request: Request, old: str = Form(...), new: str = Form(...),
                    user: User = Depends(current_user),
                    session: Session = Depends(get_session)):
    if not check_secret(old, user.password_hash):
        return RedirectResponse("/?error=неверный+текущий+пароль", status_code=303)
    if len(new) < 8:
        return RedirectResponse("/?error=пароль+короче+восьми+знаков", status_code=303)

    user.password_hash = hash_secret(new)
    user.must_change_password = False
    session.commit()
    return RedirectResponse("/?ok=пароль+изменён", status_code=303)


# --- сводка ------------------------------------------------------------------

@app.get("/")
def index(request: Request, user: User = Depends(current_user),
          session: Session = Depends(get_session)):
    сегодня = date.today()

    новых = session.scalar(select(func.count(Order.id)).where(
        Order.status == "new")) or 0
    к_отгрузке = session.scalar(select(func.count(Order.id)).where(
        Order.status.in_(("confirmed", "picking")))) or 0
    сумма_дня = session.scalar(select(func.coalesce(func.sum(Order.amount), 0)).where(
        Order.date == сегодня, Order.status != "cancelled")) or Decimal(0)

    последние = session.scalars(
        select(Order).order_by(Order.received_at.desc()).limit(15)).all()

    # Агенты с несданными деньгами: это первое, что смотрят утром.
    агенты = session.scalars(
        select(User).where(User.role == "agent", User.active.is_(True))).all()
    касса = [(а, services.agent_cash_on_hand(session, а.id)) for а in агенты]
    касса = [(а, с) for а, с in касса if с > 0]

    молчащие = [а for а in агенты if _молчит(session, а)]

    return templates.TemplateResponse(request, "index.html", {
        "user": user, "новых": новых, "к_отгрузке": к_отгрузке,
        "сумма_дня": сумма_дня, "последние": последние, "касса": касса,
        "молчащие": молчащие,
        "ok": request.query_params.get("ok", ""),
        "error": request.query_params.get("error", ""),
    })


def _молчит(session: Session, агент: User) -> bool:
    """Агент, чей телефон не выходил на связь больше суток.

    Признак не отчётный, а рабочий: заказы, снятые за день, лежат в телефоне
    и в офисе их не видно. Чем позже это заметят, тем позже поедет машина.
    """
    последний = с_поясом(session.scalar(
        select(func.max(Device.last_seen)).where(Device.user_id == агент.id)))
    if последний is None:
        return False
    return последний < datetime.now(timezone.utc) - timedelta(days=1)


# --- заказы ------------------------------------------------------------------

@app.get("/orders")
def orders(request: Request, status: str = "", q: str = "",
           user: User = Depends(current_user),
           session: Session = Depends(get_session)):
    запрос = select(Order).order_by(Order.received_at.desc())
    if status:
        запрос = запрос.where(Order.status == status)
    if q:
        запрос = запрос.join(Customer).where(or_(
            Order.number.ilike(f"%{q}%"),
            Customer.name.ilike(f"%{q}%")))

    # Супервизор видит только своих агентов: чужие заказы ему не нужны, а
    # правку чужого заказа объяснить потом невозможно.
    if user.role == "supervisor":
        свои = select(User.id).where(User.supervisor_id == user.id)
        запрос = запрос.where(Order.agent_id.in_(свои))

    return templates.TemplateResponse(request, "orders.html", {
        "user": user, "заказы": session.scalars(запрос.limit(200)).all(),
        "status": status, "q": q,
    })


@app.get("/orders/{order_id}")
def order_card(request: Request, order_id: int,
               user: User = Depends(current_user),
               session: Session = Depends(get_session)):
    заказ = session.get(Order, order_id)
    if заказ is None:
        raise HTTPException(status_code=404, detail="заказ не найден")

    остатки = {}
    if заказ.warehouse_id:
        for строка in заказ.lines:
            остаток = session.scalar(select(Stock).where(
                Stock.product_id == строка.product_id,
                Stock.warehouse_id == заказ.warehouse_id))
            остатки[строка.product_id] = остаток.free if остаток else Decimal(0)

    отгрузки = session.scalars(
        select(Shipment).where(Shipment.order_id == заказ.id)).all()

    return templates.TemplateResponse(request, "order.html", {
        "user": user, "заказ": заказ, "остатки": остатки, "отгрузки": отгрузки,
        "долг": services.customer_debt(session, заказ.customer_id),
        "error": request.query_params.get("error", ""),
    })


@app.post("/orders/{order_id}/confirm")
def order_confirm(order_id: int, user: User = Depends(office_only),
                  session: Session = Depends(get_session)):
    заказ = _заказ(session, order_id)
    try:
        services.confirm_order(session, заказ)
    except services.ОшибкаПравил as ошибка:
        session.rollback()
        return RedirectResponse(f"/orders/{order_id}?error={ошибка}", status_code=303)
    session.commit()
    return RedirectResponse(f"/orders/{order_id}", status_code=303)


@app.post("/orders/{order_id}/cancel")
def order_cancel(order_id: int, reason: str = Form(""),
                 user: User = Depends(office_only),
                 session: Session = Depends(get_session)):
    заказ = _заказ(session, order_id)
    try:
        services.cancel_order(session, заказ, reason)
    except services.ОшибкаПравил as ошибка:
        session.rollback()
        return RedirectResponse(f"/orders/{order_id}?error={ошибка}", status_code=303)
    session.commit()
    return RedirectResponse(f"/orders/{order_id}", status_code=303)


@app.post("/orders/{order_id}/ship")
def order_ship(order_id: int, user: User = Depends(current_user),
               session: Session = Depends(get_session)):
    заказ = _заказ(session, order_id)
    try:
        отгрузка = services.ship_order(session, заказ, user.id)
    except services.ОшибкаПравил as ошибка:
        session.rollback()
        return RedirectResponse(f"/orders/{order_id}?error={ошибка}", status_code=303)
    session.commit()
    return RedirectResponse(f"/shipments/{отгрузка.id}", status_code=303)


def _заказ(session: Session, order_id: int) -> Order:
    заказ = session.get(Order, order_id)
    if заказ is None:
        raise HTTPException(status_code=404, detail="заказ не найден")
    return заказ


# --- отгрузки ----------------------------------------------------------------

@app.get("/shipments")
def shipments(request: Request, user: User = Depends(current_user),
              session: Session = Depends(get_session)):
    список = session.scalars(
        select(Shipment).order_by(Shipment.date.desc(), Shipment.id.desc())
        .limit(200)).all()
    return templates.TemplateResponse(request, "shipments.html", {
        "user": user, "отгрузки": список})


@app.get("/shipments/{shipment_id}")
def shipment_card(request: Request, shipment_id: int,
                  user: User = Depends(current_user),
                  session: Session = Depends(get_session)):
    отгрузка = session.get(Shipment, shipment_id)
    if отгрузка is None:
        raise HTTPException(status_code=404, detail="отгрузка не найдена")
    return templates.TemplateResponse(request, "shipment.html", {
        "user": user, "отгрузка": отгрузка})


# --- клиенты -----------------------------------------------------------------

@app.get("/customers")
def customers(request: Request, q: str = "", agent: int = 0,
              user: User = Depends(current_user),
              session: Session = Depends(get_session)):
    запрос = select(Customer).order_by(Customer.name)
    if q:
        запрос = запрос.where(or_(
            Customer.name.ilike(f"%{q}%"),
            Customer.code.ilike(f"%{q}%"),
            Customer.inn.ilike(f"%{q}%")))
    if agent:
        запрос = запрос.where(Customer.agent_id == agent)

    список = session.scalars(запрос.limit(300)).all()
    долги = {к.id: services.customer_debt(session, к.id) for к in список}

    return templates.TemplateResponse(request, "customers.html", {
        "user": user, "клиенты": список, "долги": долги, "q": q, "agent": agent,
        "агенты": _агенты(session),
    })


@app.get("/customers/{customer_id}")
def customer_card(request: Request, customer_id: int,
                  user: User = Depends(current_user),
                  session: Session = Depends(get_session)):
    клиент = session.get(Customer, customer_id)
    if клиент is None:
        raise HTTPException(status_code=404, detail="клиент не найден")

    заказы = session.scalars(
        select(Order).where(Order.customer_id == customer_id)
        .order_by(Order.date.desc()).limit(50)).all()
    оплаты = session.scalars(
        select(Payment).where(Payment.customer_id == customer_id)
        .order_by(Payment.date.desc()).limit(50)).all()
    визиты = session.scalars(
        select(Visit).where(Visit.customer_id == customer_id)
        .order_by(Visit.date.desc()).limit(20)).all()

    return templates.TemplateResponse(request, "customer.html", {
        "user": user, "клиент": клиент, "заказы": заказы, "оплаты": оплаты,
        "визиты": визиты,
        "долг": services.customer_debt(session, customer_id),
        "просрочено": services.customer_overdue(session, customer_id),
        "агенты": _агенты(session),
        "виды_цен": session.scalars(select(PriceType).where(
            PriceType.active.is_(True))).all(),
    })


@app.post("/customers/{customer_id}")
def customer_save(customer_id: int, name: str = Form(...),
                  code: str = Form(""), inn: str = Form(""),
                  phone: str = Form(""), address: str = Form(""),
                  agent_id: str = Form(""), price_type_id: str = Form(""),
                  payment_type: str = Form("cash"),
                  credit_limit: str = Form("0"), deferral_days: str = Form("0"),
                  blocked: str = Form(""), blocked_reason: str = Form(""),
                  user: User = Depends(office_only),
                  session: Session = Depends(get_session)):
    клиент = session.get(Customer, customer_id)
    if клиент is None:
        raise HTTPException(status_code=404, detail="клиент не найден")

    клиент.name = name.strip()
    клиент.code = code.strip()
    клиент.inn = inn.strip()
    клиент.phone = phone.strip()
    клиент.address = address.strip()
    клиент.agent_id = int(agent_id) if agent_id else None
    клиент.price_type_id = int(price_type_id) if price_type_id else None
    клиент.payment_type = payment_type
    клиент.credit_limit = _десятичное(credit_limit)
    клиент.deferral_days = int(deferral_days or 0)
    клиент.blocked = bool(blocked)
    клиент.blocked_reason = blocked_reason.strip()
    session.commit()

    return RedirectResponse(f"/customers/{customer_id}", status_code=303)


@app.post("/customers")
def customer_create(name: str = Form(...), agent_id: str = Form(""),
                    user: User = Depends(office_only),
                    session: Session = Depends(get_session)):
    клиент = Customer(name=name.strip(),
                      agent_id=int(agent_id) if agent_id else None)
    session.add(клиент)
    session.commit()
    return RedirectResponse(f"/customers/{клиент.id}", status_code=303)


# --- каталог -----------------------------------------------------------------

@app.get("/catalog")
def catalog(request: Request, q: str = "", warehouse: int = 0,
            user: User = Depends(current_user),
            session: Session = Depends(get_session)):
    запрос = select(Product).order_by(Product.name)
    if q:
        запрос = запрос.where(or_(
            Product.name.ilike(f"%{q}%"),
            Product.code.ilike(f"%{q}%"),
            Product.barcode == q))

    товары = session.scalars(запрос.limit(300)).all()
    склады = session.scalars(select(Warehouse).where(
        Warehouse.active.is_(True)).order_by(Warehouse.name)).all()
    виды_цен = session.scalars(select(PriceType).where(
        PriceType.active.is_(True)).order_by(PriceType.name)).all()

    цены = {}
    for цена in session.scalars(select(Price)):
        цены.setdefault(цена.product_id, {})[цена.price_type_id] = цена.price

    остатки = {}
    запрос_остатков = select(Stock)
    if warehouse:
        запрос_остатков = запрос_остатков.where(Stock.warehouse_id == warehouse)
    for остаток in session.scalars(запрос_остатков):
        остатки[остаток.product_id] = остатки.get(остаток.product_id, Decimal(0)) \
            + остаток.free

    return templates.TemplateResponse(request, "catalog.html", {
        "user": user, "товары": товары, "склады": склады, "виды_цен": виды_цен,
        "цены": цены, "остатки": остатки, "q": q, "warehouse": warehouse,
    })


@app.post("/catalog/price")
def set_price(product_id: int = Form(...), price_type_id: int = Form(...),
              price: str = Form(...), user: User = Depends(office_only),
              session: Session = Depends(get_session)):
    запись = session.scalar(select(Price).where(
        Price.product_id == product_id, Price.price_type_id == price_type_id))
    if запись is None:
        запись = Price(product_id=product_id, price_type_id=price_type_id)
        session.add(запись)
    запись.price = _десятичное(price)
    session.commit()
    return RedirectResponse("/catalog", status_code=303)


@app.post("/catalog/stock")
def set_stock(product_id: int = Form(...), warehouse_id: int = Form(...),
              qty: str = Form(...), user: User = Depends(office_only),
              session: Session = Depends(get_session)):
    """Ручная установка остатка.

    Пока нет обмена с учётной системой, остаток заводится руками. Разница
    пишется движением: остаток, изменившийся неизвестно как, потом никому не
    объяснить.
    """
    from .models import StockMove

    новое = _десятичное(qty)
    остаток = session.scalar(select(Stock).where(
        Stock.product_id == product_id, Stock.warehouse_id == warehouse_id))
    if остаток is None:
        остаток = Stock(product_id=product_id, warehouse_id=warehouse_id,
                        qty=Decimal(0))
        session.add(остаток)
        session.flush()

    разница = новое - остаток.qty
    остаток.qty = новое
    session.add(StockMove(
        product_id=product_id, warehouse_id=warehouse_id, qty=разница,
        doc_type="manual", user_id=user.id, comment="установка остатка вручную"))
    session.commit()
    return RedirectResponse("/catalog", status_code=303)


# --- дебиторка ---------------------------------------------------------------

@app.get("/debts")
def debts(request: Request, only_overdue: int = 0,
          user: User = Depends(current_user),
          session: Session = Depends(get_session)):
    клиенты = session.scalars(select(Customer).where(
        Customer.active.is_(True)).order_by(Customer.name)).all()

    строки = []
    for клиент in клиенты:
        долг = services.customer_debt(session, клиент.id)
        if долг <= 0:
            continue
        просрочено = services.customer_overdue(session, клиент.id)
        if only_overdue and просрочено <= 0:
            continue
        строки.append({
            "клиент": клиент, "долг": долг, "просрочено": просрочено,
            "лимит": клиент.credit_limit,
            "превышен": клиент.credit_limit > 0 and долг > клиент.credit_limit,
        })

    строки.sort(key=lambda с: с["просрочено"], reverse=True)

    return templates.TemplateResponse(request, "debts.html", {
        "user": user, "строки": строки, "only_overdue": only_overdue,
        "итого": sum(с["долг"] for с in строки),
        "итого_просрочено": sum(с["просрочено"] for с in строки),
    })


@app.post("/payments")
def payment_create(customer_id: int = Form(...), amount: str = Form(...),
                   kind: str = Form("transfer"), comment: str = Form(""),
                   user: User = Depends(office_only),
                   session: Session = Depends(get_session)):
    """Оплата, заведённая из кабинета (перечисление на счёт)."""
    import uuid as uuid_lib

    сумма = _десятичное(amount)
    if сумма <= 0:
        raise HTTPException(status_code=400, detail="сумма должна быть больше нуля")

    оплата = Payment(
        client_uid=str(uuid_lib.uuid4()),
        number=services.next_number(session, "payment", "П"),
        customer_id=customer_id, date=date.today(), amount=сумма,
        kind=kind, comment=comment,
        # Безналичная оплата приходит сразу в кассу, инкассировать нечего.
        collected=kind != "cash",
    )
    session.add(оплата)
    session.commit()
    return RedirectResponse(f"/customers/{customer_id}", status_code=303)


# --- агенты ------------------------------------------------------------------

@app.get("/agents")
def agents(request: Request, user: User = Depends(current_user),
           session: Session = Depends(get_session)):
    список = session.scalars(
        select(User).where(User.role == "agent").order_by(User.full_name)).all()

    сведения = []
    for агент in список:
        устройства = session.scalars(
            select(Device).where(Device.user_id == агент.id)).all()
        сведения.append({
            "агент": агент,
            "устройства": устройства,
            "клиентов": session.scalar(select(func.count(Customer.id)).where(
                Customer.agent_id == агент.id)) or 0,
            "наличные": services.agent_cash_on_hand(session, агент.id),
            "заказов_месяц": session.scalar(select(func.count(Order.id)).where(
                Order.agent_id == агент.id,
                Order.date >= date.today().replace(day=1))) or 0,
        })

    return templates.TemplateResponse(request, "agents.html", {
        "user": user, "сведения": сведения,
        "ok": request.query_params.get("ok", ""),
    })


@app.post("/agents")
def agent_create(full_name: str = Form(...), login: str = Form(...),
                 phone: str = Form(""), user: User = Depends(admin_only),
                 session: Session = Depends(get_session)):
    логин = login.strip().lower()
    if find_user(session, логин):
        return RedirectResponse("/agents?ok=такой+логин+уже+есть", status_code=303)

    пароль = random_password()
    session.add(User(
        login=логин, password_hash=hash_secret(пароль),
        full_name=full_name.strip(), phone=phone.strip(), role="agent"))
    session.commit()
    # Пароль показывается один раз — записать и передать агенту.
    return RedirectResponse(
        f"/agents?ok=создан+{логин}+пароль+{пароль}", status_code=303)


@app.post("/agents/{agent_id}/reset")
def agent_reset(agent_id: int, user: User = Depends(admin_only),
                session: Session = Depends(get_session)):
    агент = session.get(User, agent_id)
    if агент is None:
        raise HTTPException(status_code=404, detail="агент не найден")
    пароль = random_password()
    агент.password_hash = hash_secret(пароль)
    session.commit()
    return RedirectResponse(
        f"/agents?ok=пароль+{агент.login}+теперь+{пароль}", status_code=303)


@app.post("/devices/{device_id}/off")
def device_off(device_id: int, user: User = Depends(admin_only),
               session: Session = Depends(get_session)):
    """Отключить устройство.

    Основной сценарий — потерянный телефон. Токен гасится немедленно, войти
    заново с этого аппарата нельзя даже с верным паролем.
    """
    устройство = session.get(Device, device_id)
    if устройство is None:
        raise HTTPException(status_code=404, detail="устройство не найдено")
    устройство.active = False
    устройство.token_hash = ""
    session.commit()
    return RedirectResponse("/agents?ok=устройство+отключено", status_code=303)


@app.post("/agents/{agent_id}/collect")
def agent_collect(agent_id: int, user: User = Depends(office_only),
                  session: Session = Depends(get_session)):
    сумма = services.collect_cash(session, agent_id)
    session.commit()
    return RedirectResponse(
        f"/agents?ok=принято+{деньги(сумма)}", status_code=303)


# --- обмены ------------------------------------------------------------------

@app.get("/sync")
def sync_log(request: Request, user: User = Depends(current_user),
             session: Session = Depends(get_session)):
    записи = session.scalars(
        select(SyncLog).order_by(SyncLog.at.desc()).limit(200)).all()
    устройства = session.scalars(
        select(Device).order_by(Device.last_seen.desc().nullslast())).all()
    # Имена подтягиваются одним запросом: журнал на двести строк иначе даёт
    # двести обращений к базе за одним и тем же десятком агентов.
    имена = {п.id: п.short_name for п in session.scalars(select(User))}
    return templates.TemplateResponse(request, "sync.html", {
        "user": user, "записи": записи, "устройства": устройства,
        "имена": имена})


@app.get("/health")
def health(session: Session = Depends(get_session)):
    """Для проверки живости из мониторинга."""
    session.execute(select(1))
    return {"ok": True}


# --- вспомогательное ---------------------------------------------------------

def _агенты(session: Session) -> list[User]:
    return session.scalars(
        select(User).where(User.role == "agent", User.active.is_(True))
        .order_by(User.full_name)).all()


def _десятичное(значение: str) -> Decimal:
    """Число из формы. Пользователь пишет и через запятую, и с пробелами."""
    текст = (значение or "0").replace(" ", "").replace(" ", "").replace(",", ".")
    try:
        return Decimal(текст)
    except InvalidOperation:
        raise HTTPException(status_code=400, detail=f"не число: {значение}")
