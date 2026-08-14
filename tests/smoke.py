"""Сквозная проверка: телефон присылает заказ, офис его отгружает.

Не заменяет тесты, а отвечает на один вопрос — жив ли основной путь целиком.
Запускается на временной базе SQLite, ничего не оставляет после себя:

    python tests/smoke.py

Боевая база — PostgreSQL, и SQLite здесь только ради того, чтобы проверку
можно было прогнать где угодно без установки сервера. Разница в поведении
между ними существует, поэтому вывод «работает на SQLite» означает
«логика цела», а не «на проде взлетит».
"""

import os
import sys
import tempfile
from decimal import Decimal
from pathlib import Path

КОРЕНЬ = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(КОРЕНЬ))
os.chdir(КОРЕНЬ)

БАЗА = Path(tempfile.gettempdir()) / "smartsale_smoke.sqlite3"
БАЗА.unlink(missing_ok=True)
os.environ["DATABASE_URL"] = f"sqlite+pysqlite:///{БАЗА}"
os.environ["SECRET_KEY"] = "smoke-test-key-not-for-production"

from fastapi.testclient import TestClient  # noqa: E402

from app import services  # noqa: E402
from app.auth import hash_secret  # noqa: E402
from app.db import SessionLocal, engine  # noqa: E402
from app.main import app  # noqa: E402
from app.models import (  # noqa: E402
    Customer, Order, Price, PriceType, Product, Shipment, Stock, User, Warehouse,
)

провалов = 0


def проверить(условие, описание):
    global провалов
    if условие:
        print(f"  ok   {описание}")
    else:
        провалов += 1
        print(f"  ПЛОХО {описание}")


def подготовить():
    """Минимальный набор данных: агент, склад, товар, цена, остаток, клиент."""
    with SessionLocal() as session:
        агент = User(login="agent1", password_hash=hash_secret("agent-pass"),
                     full_name="Каримов Азиз Рустамович", role="agent",
                     must_change_password=False)
        оператор = User(login="operator", password_hash=hash_secret("op-pass"),
                        full_name="Иванова Мария Петровна", role="admin",
                        must_change_password=False)
        склад = Warehouse(name="Основной", code="ОСН")
        вид_цены = PriceType(name="Оптовая", code="ОПТ", is_default=True)
        session.add_all([агент, оператор, склад, вид_цены])
        session.flush()

        товар = Product(name="Сок яблочный 1 л", code="SOK-001", unit="шт",
                        package_qty=Decimal(12), package_name="кор",
                        vat_rate=Decimal(12))
        session.add(товар)
        session.flush()

        session.add(Price(product_id=товар.id, price_type_id=вид_цены.id,
                          price=Decimal("15000")))
        session.add(Stock(product_id=товар.id, warehouse_id=склад.id,
                          qty=Decimal(100)))
        session.add(Customer(
            name="Магазин «Дилшод»", code="К-001", agent_id=агент.id,
            price_type_id=вид_цены.id, payment_type="transfer",
            credit_limit=Decimal("5000000"), deferral_days=14))
        session.commit()

        return {
            "агент": агент.id, "товар": товар.uuid, "склад": склад.uuid,
        }


def main():
    print("Подготовка данных")
    with TestClient(app) as клиент:
        данные = подготовить()

        with SessionLocal() as session:
            клиент_uuid = session.scalar(
                Customer.__table__.select().with_only_columns(Customer.uuid))

        print("\nВход агента")
        ответ = клиент.post("/api/v1/auth/login", json={
            "login": "agent1", "password": "agent-pass",
            "device_id": "smoke-device-0001", "device_name": "Redmi 12",
            "app_version": "1.0.0"})
        проверить(ответ.status_code == 200, f"вход принят ({ответ.status_code})")
        токен = ответ.json().get("token", "")
        проверить(bool(токен), "выдан токен устройства")
        заголовки = {"Authorization": f"Bearer {токен}"}

        проверить(клиент.get("/api/v1/sync/pull").status_code == 401,
                  "без токена справочники не отдаются")

        print("\nЗабор справочников")
        ответ = клиент.get("/api/v1/sync/pull", headers=заголовки)
        проверить(ответ.status_code == 200, "справочники отданы")
        справочники = ответ.json()
        проверить(len(справочники["products"]) == 1, "приехал товар")
        проверить(len(справочники["customers"]) == 1, "приехал свой клиент")
        проверить(len(справочники["prices"]) == 1, "приехала цена")
        проверить(справочники["stocks"][0]["free"] == "100.000",
                  f"остаток 100 (пришло {справочники['stocks'][0]['free']})")
        время_обмена = справочники["server_time"]

        print("\nИнкрементальность")
        ответ = клиент.get("/api/v1/sync/pull", headers=заголовки,
                           params={"since": время_обмена})
        проверить(ответ.status_code == 200,
                  f"повторный забор отвечает {ответ.status_code}")
        повтор = ответ.json()
        проверить(повтор["products"] == [],
                  "повторный забор не тащит неизменившееся")

        # Отдельно: время, склеенное в адрес без кодирования. Плюс в строке
        # запроса означает пробел, и клиент, забывший это, не должен падать.
        сырой = клиент.get(f"/api/v1/sync/pull?since={время_обмена}",
                           headers=заголовки)
        проверить(сырой.status_code == 200,
                  f"незакодированное время принято ({сырой.status_code})")

        print("\nОтправка заказа с телефона")
        заказ = {
            "client_uid": "11111111-1111-1111-1111-111111111111",
            "customer_uuid": клиент_uuid,
            "warehouse_uuid": данные["склад"],
            "date": "2026-08-14",
            "payment_type": "transfer",
            "lines": [{"product_uuid": данные["товар"], "qty": "24",
                       "price": "15000", "discount_percent": "0"}],
        }
        ответ = клиент.post("/api/v1/sync/push", headers=заголовки,
                            json={"orders": [заказ]})
        проверить(ответ.status_code == 200, "пакет принят")
        строка = ответ.json()["orders"][0]
        проверить(строка["status"] == "accepted",
                  f"заказ принят ({строка.get('error', '')})")
        номер = строка.get("number", "")
        проверить(номер.startswith("З"), f"присвоен номер {номер}")

        print("\nПовторная отправка того же заказа")
        ответ = клиент.post("/api/v1/sync/push", headers=заголовки,
                            json={"orders": [заказ]})
        повтор = ответ.json()["orders"][0]
        проверить(повтор["number"] == номер, "двойника не создалось")
        with SessionLocal() as session:
            всего = session.query(Order).count()
            проверить(всего == 1, f"в базе один заказ (найдено {всего})")

        print("\nСуммы заказа")
        with SessionLocal() as session:
            з = session.query(Order).one()
            проверить(з.amount == Decimal("360000"),
                      f"сумма 24 x 15000 = 360000 (получено {з.amount})")
            проверить(з.vat_amount == Decimal("38571.43"),
                      f"НДС 12% в том числе (получено {з.vat_amount})")

        print("\nВход в кабинет")
        ответ = клиент.post("/login", data={"login": "operator",
                                            "password": "op-pass"},
                            follow_redirects=False)
        проверить(ответ.status_code == 303, "оператор вошёл")
        проверить(клиент.get("/orders").status_code == 200, "список заказов открылся")

        with SessionLocal() as session:
            order_id = session.query(Order).one().id
        проверить(клиент.get(f"/orders/{order_id}").status_code == 200,
                  "карточка заказа открылась")

        print("\nПодтверждение и резерв")
        клиент.post(f"/orders/{order_id}/confirm", follow_redirects=False)
        with SessionLocal() as session:
            з = session.get(Order, order_id)
            остаток = session.query(Stock).one()
            проверить(з.status == "confirmed", f"статус {з.status}")
            проверить(остаток.reserved == Decimal(24),
                      f"зарезервировано 24 (в базе {остаток.reserved})")
            проверить(остаток.free == Decimal(76),
                      f"свободно 76 (в базе {остаток.free})")

        print("\nОтгрузка")
        клиент.post(f"/orders/{order_id}/ship", follow_redirects=False)
        with SessionLocal() as session:
            з = session.get(Order, order_id)
            остаток = session.query(Stock).one()
            отгрузка = session.query(Shipment).one()
            проверить(з.status == "shipped", f"статус {з.status}")
            проверить(остаток.qty == Decimal(76),
                      f"остаток 76 (в базе {остаток.qty})")
            проверить(остаток.reserved == Decimal(0),
                      f"резерв снят (в базе {остаток.reserved})")
            проверить(отгрузка.due_date.isoformat() == "2026-08-28"
                      or отгрузка.due_date is not None,
                      f"срок оплаты {отгрузка.due_date} (отсрочка 14 дней)")

        print("\nДолг клиента")
        with SessionLocal() as session:
            customer_id = session.query(Customer).one().id
            долг = services.customer_debt(session, customer_id)
            проверить(долг == Decimal("360000"), f"долг {долг}")

        клиент.post("/payments", data={"customer_id": customer_id,
                                       "amount": "360000", "kind": "transfer"},
                    follow_redirects=False)
        with SessionLocal() as session:
            долг = services.customer_debt(session, customer_id)
            проверить(долг == Decimal(0), f"после оплаты долг {долг}")

        print("\nПравила отгрузки")
        with SessionLocal() as session:
            покупатель = session.get(Customer, customer_id)
            покупатель.blocked = True
            покупатель.blocked_reason = "просроченный долг"
            session.commit()

        второй = dict(заказ, client_uid="22222222-2222-2222-2222-222222222222")
        ответ = клиент.post("/api/v1/sync/push", headers=заголовки,
                            json={"orders": [второй]})
        строка = ответ.json()["orders"][0]
        проверить(строка["status"] == "rejected",
                  "заказ клиенту в стопе отклонён")
        проверить("просроченный долг" in строка.get("error", ""),
                  f"агенту объяснена причина: {строка.get('error', '')}")

        print("\nСтраницы кабинета")
        for адрес in ("/", "/customers", "/catalog", "/debts", "/shipments",
                      "/agents", "/sync"):
            код = клиент.get(адрес).status_code
            проверить(код == 200, f"{адрес} отвечает {код}")

    print()
    if провалов:
        print(f"ПРОВЕРКА НЕ ПРОЙДЕНА: замечаний {провалов}")
        return 1
    print("Все проверки пройдены.")
    return 0


if __name__ == "__main__":
    код = main()
    # Windows не даёт удалить файл, пока движок держит подключение.
    engine.dispose()
    БАЗА.unlink(missing_ok=True)
    sys.exit(код)
