"""Вход в веб-кабинет и авторизация мобильных устройств.

Два разных механизма намеренно. Веб-кабинет — подписанная кука с коротким
сроком: за компьютером в офисе сессия должна протухать. Телефон агента —
долгоживущий токен, привязанный к устройству: агент не должен вводить пароль
посреди рынка, а отзыв делается отключением устройства в кабинете.

Пароли и токены хранятся хешем PBKDF2 из стандартной библиотеки: внешних
зависимостей не тянем, стойкости для внутренней системы достаточно.
"""

import base64
import hashlib
import hmac
import json
import os
import secrets
import time
from datetime import datetime, timedelta, timezone

from fastapi import Depends, HTTPException, Request
from sqlalchemy import select
from sqlalchemy.orm import Session

from .config import DEVICE_TOKEN_DAYS, SECRET_KEY, SESSION_HOURS
from .db import get_session
from .models import Device, User

COOKIE = "smartsale_session"
ITERATIONS = 200_000


# --- пароли и токены ---------------------------------------------------------

def hash_secret(secret: str) -> str:
    salt = os.urandom(16)
    dk = hashlib.pbkdf2_hmac("sha256", secret.encode(), salt, ITERATIONS)
    return f"pbkdf2${ITERATIONS}${salt.hex()}${dk.hex()}"


def check_secret(secret: str, stored: str) -> bool:
    try:
        _, iterations, salt_hex, hash_hex = stored.split("$")
        dk = hashlib.pbkdf2_hmac(
            "sha256", secret.encode(), bytes.fromhex(salt_hex), int(iterations))
        # Сравнение с постоянным временем: обычное == подсказывает подбор.
        return hmac.compare_digest(dk.hex(), hash_hex)
    except (ValueError, AttributeError):
        return False


def random_password(length: int = 10) -> str:
    """Пароль для новой учётки. Без похожих символов: администратор диктует
    его агенту голосом, и 0/O, 1/l/I путаются."""
    alphabet = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKMNPQRSTUVWXYZ23456789"
    return "".join(secrets.choice(alphabet) for _ in range(length))


# --- сессия веб-кабинета -----------------------------------------------------

def _sign(payload: bytes) -> str:
    signature = hmac.new(SECRET_KEY.encode(), payload, hashlib.sha256).digest()
    return base64.urlsafe_b64encode(payload).decode().rstrip("=") + "." + \
           base64.urlsafe_b64encode(signature).decode().rstrip("=")


def _unsign(token: str) -> dict | None:
    try:
        body, signature = token.split(".")
        payload = base64.urlsafe_b64decode(body + "=" * (-len(body) % 4))
        expected = hmac.new(SECRET_KEY.encode(), payload, hashlib.sha256).digest()
        got = base64.urlsafe_b64decode(signature + "=" * (-len(signature) % 4))
        if not hmac.compare_digest(expected, got):
            return None
        data = json.loads(payload)
        if data.get("exp", 0) < time.time():
            return None
        return data
    except (ValueError, TypeError, json.JSONDecodeError):
        return None


def make_session(user_id: int) -> str:
    payload = json.dumps(
        {"uid": user_id, "exp": time.time() + SESSION_HOURS * 3600}).encode()
    return _sign(payload)


class НужнаСессия(Exception):
    """Сессии нет или она недействительна.

    Отдельное исключение, а не HTTPException: браузеру нужна форма входа, а
    вызову из кода — код ответа. Зависимость FastAPI сама перенаправить не
    может, решение принимает обработчик в main.py.
    """

    def __init__(self, detail: str):
        self.detail = detail


def current_user(request: Request,
                 session: Session = Depends(get_session)) -> User:
    token = request.cookies.get(COOKIE, "")
    data = _unsign(token) if token else None
    if not data:
        raise НужнаСессия("нужен вход")

    user = session.get(User, data["uid"])
    if user is None or not user.active:
        raise НужнаСессия("учётная запись недоступна")
    # Агент в веб-кабинет не ходит: его инструмент — телефон, а кабинет
    # показывает чужие заказы и настройки.
    if user.is_agent:
        raise НужнаСессия("кабинет доступен только сотрудникам офиса")
    return user


def admin_only(user: User = Depends(current_user)) -> User:
    if not user.is_admin:
        raise HTTPException(status_code=403, detail="раздел только для администратора")
    return user


def office_only(user: User = Depends(current_user)) -> User:
    """Кто может менять документы: все, кроме кладовщика."""
    if user.role == "warehouse":
        raise HTTPException(status_code=403, detail="доступна только отгрузка")
    return user


# --- токен мобильного устройства ---------------------------------------------

def issue_device_token(session: Session, device: Device) -> str:
    """Выдать устройству новый токен и запомнить его хеш.

    Токен возвращается один раз, при входе. Восстановить его из базы нельзя —
    там только хеш; потерянный токен означает повторный вход по паролю.
    """
    token = secrets.token_urlsafe(32)
    device.token_hash = hash_secret(token)
    device.token_expires = datetime.now(timezone.utc) + timedelta(days=DEVICE_TOKEN_DAYS)
    device.active = True
    session.flush()
    # Идентификатор устройства в открытой части: иначе проверка токена
    # означала бы перебор хешей по всем устройствам.
    return f"{device.id}.{token}"


def с_поясом(значение: datetime | None) -> datetime | None:
    """Время из базы, приведённое к осведомлённому о часовом поясе.

    PostgreSQL для timestamptz возвращает время с поясом, но не всякая база
    это умеет, а сравнение наивного времени с осведомлённым роняет запрос
    целиком. Наивное считаем UTC — именно в UTC мы его и записали.
    """
    if значение is None:
        return None
    return значение if значение.tzinfo else значение.replace(tzinfo=timezone.utc)


def device_from_token(session: Session, raw: str) -> Device | None:
    try:
        device_id_str, token = raw.split(".", 1)
        device_id = int(device_id_str)
    except (ValueError, AttributeError):
        return None

    device = session.get(Device, device_id)
    if device is None or not device.active or not device.token_hash:
        return None
    if not check_secret(token, device.token_hash):
        return None
    срок = с_поясом(device.token_expires)
    if срок and срок < datetime.now(timezone.utc):
        return None
    if not device.user.active:
        return None
    return device


def current_device(request: Request,
                   session: Session = Depends(get_session)) -> Device:
    """Авторизация запросов мобильного приложения."""
    header = request.headers.get("Authorization", "")
    if not header.startswith("Bearer "):
        raise HTTPException(status_code=401, detail="нужен токен устройства")

    device = device_from_token(session, header[7:])
    if device is None:
        # Тот же код на протухший, отозванный и поддельный токен: приложению
        # во всех случаях делать одно и то же — просить вход по паролю.
        raise HTTPException(status_code=401, detail="токен недействителен")

    device.last_seen = datetime.now(timezone.utc)
    session.commit()
    return device


def find_user(session: Session, login: str) -> User | None:
    return session.scalar(select(User).where(User.login == login.strip().lower()))
