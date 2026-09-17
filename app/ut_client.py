"""Клиент к HTTP-сервису расширения УТ.

SmartSale забирает из УТ справочники и акции. Инициатор — SmartSale: УТ сама
наружу не ходит. Аутентификация двойная и обе части обязательны:

  - Basic веб-сервера публикации. Логин 1С кодируется в UTF-8: кириллический
    логин через стандартный Basic ломается (IIS отдаёт 401.5), поэтому
    заголовок собираем руками из UTF-8 байт, а не средствами urllib.
  - Токен канала заголовком X-SmartSale-Token — поверх Basic, отдельным
    заголовком: в публикации 1С заголовок Authorization занят Basic.

На stdlib (urllib), без сторонних зависимостей: запросы простые — GET с JSON
в ответе, и тащить ради них лишнюю библиотеку незачем.
"""

import base64
import json
import urllib.error
import urllib.parse
import urllib.request

from .config import UT_BASE_URL, UT_LOGIN, UT_PASSWORD, UT_TIMEOUT, UT_TOKEN


class ОшибкаУТ(Exception):
    """Обмен с УТ не удался: сеть, код ответа или разбор тела."""


def _заголовки() -> dict[str, str]:
    креды = f"{UT_LOGIN}:{UT_PASSWORD}".encode("utf-8")
    return {
        "Authorization": "Basic " + base64.b64encode(креды).decode("ascii"),
        "X-SmartSale-Token": UT_TOKEN,
        "Accept": "application/json",
    }


def _проверить_настройки() -> None:
    если_пусто = [имя for имя, знач in (
        ("UT_BASE_URL", UT_BASE_URL), ("UT_LOGIN", UT_LOGIN),
        ("UT_TOKEN", UT_TOKEN)) if not знач]
    if если_пусто:
        raise ОшибкаУТ("не заданы настройки обмена с УТ: " + ", ".join(если_пусто))


def получить(путь: str, параметры: dict | None = None) -> dict:
    """GET к методу сервиса. Возвращает разобранный JSON-объект.

    Путь — без ведущего слэша относительно UT_BASE_URL, например "meta" или
    "products". Параметры уходят строкой запроса.
    """
    _проверить_настройки()

    адрес = UT_BASE_URL.rstrip("/") + "/" + путь.lstrip("/")
    if параметры:
        адрес += "?" + urllib.parse.urlencode(параметры)

    запрос = urllib.request.Request(адрес, headers=_заголовки(), method="GET")

    try:
        with urllib.request.urlopen(запрос, timeout=UT_TIMEOUT) as ответ:
            тело = ответ.read().decode("utf-8")
    except urllib.error.HTTPError as ошибка:
        # Тело ошибки от сервиса — наши JSON с полем error либо страница
        # веб-сервера; и то и другое полезно в логе разбора.
        подробность = ""
        try:
            подробность = ошибка.read().decode("utf-8", "replace")[:500]
        except Exception:
            pass
        raise ОшибкаУТ(f"{путь}: HTTP {ошибка.code} {подробность}") from ошибка
    except urllib.error.URLError as ошибка:
        raise ОшибкаУТ(f"{путь}: нет связи с УТ ({ошибка.reason})") from ошибка

    try:
        данные = json.loads(тело)
    except json.JSONDecodeError as ошибка:
        raise ОшибкаУТ(f"{путь}: ответ не JSON") from ошибка

    if not isinstance(данные, dict):
        raise ОшибкаУТ(f"{путь}: ожидался объект JSON")

    return данные


def получить_страницами(путь: str) -> list[dict]:
    """Полный список для курсорных методов (/products, /customers).

    Сервис отдаёт `items`, `more` и `next`: пока more=true, повторяем запрос,
    подставляя next в параметр after. Курсор — uid последнего элемента, порядок
    по ссылке; см. протокол сервиса.
    """
    все: list[dict] = []
    курсор = ""
    while True:
        параметры = {"after": курсор} if курсор else None
        пакет = получить(путь, параметры)
        все.extend(пакет.get("items", []))
        if not пакет.get("more"):
            break
        следующий = пакет.get("next", "")
        # Защита от зацикливания: сервис обещал ещё, но курсор пуст ИЛИ не
        # сдвинулся с прошлой страницы (баг курсора на стороне УТ). Иначе
        # цикл крутился бы вечно, накапливая один и тот же ответ до нехватки
        # памяти.
        if not следующий or следующий == курсор:
            break
        курсор = следующий
    return все


def получить_список(путь: str) -> list[dict]:
    """Список целиком, без страниц (/prices, /stocks, /promotions)."""
    return получить(путь).get("items", [])


def отправить(путь: str, тело: dict, параметры: dict | None = None) -> dict:
    """POST к методу сервиса (/orders, /payments). Тело — JSON-объект,
    ответ — разобранный JSON. Ошибки — как в получить()."""
    _проверить_настройки()

    адрес = UT_BASE_URL.rstrip("/") + "/" + путь.lstrip("/")
    if параметры:
        адрес += "?" + urllib.parse.urlencode(параметры)

    данные = json.dumps(тело, ensure_ascii=False).encode("utf-8")
    заголовки = dict(_заголовки())
    заголовки["Content-Type"] = "application/json; charset=utf-8"
    запрос = urllib.request.Request(адрес, data=данные, headers=заголовки, method="POST")

    try:
        with urllib.request.urlopen(запрос, timeout=UT_TIMEOUT) as ответ:
            текст = ответ.read().decode("utf-8")
    except urllib.error.HTTPError as ошибка:
        подробность = ""
        try:
            подробность = ошибка.read().decode("utf-8", "replace")[:500]
        except Exception:
            pass
        raise ОшибкаУТ(f"{путь}: HTTP {ошибка.code} {подробность}") from ошибка
    except urllib.error.URLError as ошибка:
        raise ОшибкаУТ(f"{путь}: нет связи с УТ ({ошибка.reason})") from ошибка

    try:
        разобранное = json.loads(текст)
    except json.JSONDecodeError as ошибка:
        raise ОшибкаУТ(f"{путь}: ответ не JSON") from ошибка

    if not isinstance(разобранное, dict):
        raise ОшибкаУТ(f"{путь}: ожидался объект JSON")

    return разобранное


def отправить_двоичные(путь: str, данные: bytes, параметры: dict | None = None,
                       тип_содержимого: str = "image/jpeg") -> dict:
    """POST бинарного тела (фото задания в /tasks/photo). Ответ — JSON-объект.

    Отдельно от отправить(): тело — не JSON, а сами байты файла; метаданные
    (задание, имя) уходят строкой запроса. Ошибки и авторизация — как у
    отправить().
    """
    _проверить_настройки()

    адрес = UT_BASE_URL.rstrip("/") + "/" + путь.lstrip("/")
    if параметры:
        адрес += "?" + urllib.parse.urlencode(параметры)

    заголовки = dict(_заголовки())
    заголовки["Content-Type"] = тип_содержимого
    запрос = urllib.request.Request(адрес, data=данные, headers=заголовки, method="POST")

    try:
        with urllib.request.urlopen(запрос, timeout=UT_TIMEOUT) as ответ:
            текст = ответ.read().decode("utf-8")
    except urllib.error.HTTPError as ошибка:
        подробность = ""
        try:
            подробность = ошибка.read().decode("utf-8", "replace")[:500]
        except Exception:
            pass
        raise ОшибкаУТ(f"{путь}: HTTP {ошибка.code} {подробность}") from ошибка
    except urllib.error.URLError as ошибка:
        raise ОшибкаУТ(f"{путь}: нет связи с УТ ({ошибка.reason})") from ошибка

    try:
        разобранное = json.loads(текст)
    except json.JSONDecodeError as ошибка:
        raise ОшибкаУТ(f"{путь}: ответ не JSON") from ошибка

    if not isinstance(разобранное, dict):
        raise ОшибкаУТ(f"{путь}: ожидался объект JSON")

    return разобранное
