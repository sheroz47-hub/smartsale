import sys
sys.path.insert(0, ".")
from dotenv import load_dotenv
load_dotenv("/srv/smartsale-obmen/.env")
from sqlalchemy import text
from app.db import engine

# Права агента приходят обменом из УТ (регистр SmartSale_СкладыАгентов).
# Умолчания разрешительные, чтобы включение прав не заблокировало уже
# работающих агентов; доставка — точечно, поэтому false.
СТОЛБЦЫ = [
    ("can_order", "true"),
    ("can_payment", "true"),
    ("can_delivery", "false"),
    ("can_audit", "true"),
    ("can_new_client", "true"),
]

with engine.begin() as c:
    for имя, умолчание in СТОЛБЦЫ:
        c.execute(text(
            f"ALTER TABLE users ADD COLUMN IF NOT EXISTS "
            f"{имя} boolean NOT NULL DEFAULT {умолчание}"))
print("alter ok")
