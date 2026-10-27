"""Northwater — a local demonstration bank.

Balances live in a SQLite file on this machine. No payment is sent anywhere.
"""

from __future__ import annotations

import hashlib
import os
import re
import secrets
import sqlite3
from contextlib import asynccontextmanager, contextmanager, suppress
from datetime import date, datetime
from pathlib import Path
from urllib.parse import urlencode

from fastapi import FastAPI, Form, Request
from fastapi.responses import FileResponse, RedirectResponse
from fastapi.staticfiles import StaticFiles
from fastapi.templating import Jinja2Templates
from starlette.middleware.base import BaseHTTPMiddleware
from starlette.middleware.sessions import SessionMiddleware

ROOT = Path(__file__).resolve().parent
SEED_VERSION = "1"
DEMO_PASSWORD = "northwater"
ROUTING_NUMBER = "260000000"
PBKDF2_ROUNDS = 100_000
MAX_CENTS = 100_000_000
PAYEES = (
    "Linden Apartments",
    "City Power & Light",
    "Harbor Market",
    "Metro Transit",
)
CATEGORY_LABELS = {
    "opening": "Opening",
    "income": "Income",
    "groceries": "Groceries",
    "housing": "Housing",
    "utilities": "Utilities",
    "transport": "Transport",
    "transfer": "Transfer",
    "internal": "Transfer",
    "shopping": "Shopping",
    "health": "Health",
    "dining": "Dining",
    "adjustment": "Adjustment",
    "interest": "Interest",
    "bill": "Bill",
}

SCHEMA = """
CREATE TABLE users (
  id INTEGER PRIMARY KEY,
  email TEXT NOT NULL UNIQUE,
  full_name TEXT NOT NULL,
  city TEXT NOT NULL,
  member_since TEXT NOT NULL,
  password_hash TEXT NOT NULL
);
CREATE TABLE accounts (
  id INTEGER PRIMARY KEY,
  user_id INTEGER NOT NULL REFERENCES users(id),
  nickname TEXT NOT NULL,
  kind TEXT NOT NULL CHECK (kind IN ('checking', 'savings')),
  account_number TEXT NOT NULL UNIQUE,
  routing_number TEXT NOT NULL,
  balance_cents INTEGER NOT NULL CHECK (balance_cents >= 0),
  opened_on TEXT NOT NULL
);
CREATE TABLE transactions (
  id INTEGER PRIMARY KEY,
  account_id INTEGER NOT NULL REFERENCES accounts(id),
  posted_at TEXT NOT NULL,
  description TEXT NOT NULL,
  memo TEXT,
  amount_cents INTEGER NOT NULL CHECK (amount_cents != 0),
  balance_after_cents INTEGER NOT NULL CHECK (balance_after_cents >= 0),
  category TEXT NOT NULL,
  transfer_group TEXT
);
CREATE TABLE transfers (
  id INTEGER PRIMARY KEY,
  confirmation TEXT NOT NULL UNIQUE,
  nonce TEXT NOT NULL UNIQUE,
  initiator_id INTEGER NOT NULL REFERENCES users(id),
  kind TEXT NOT NULL CHECK (kind IN ('transfer', 'bill')),
  scope TEXT NOT NULL CHECK (scope IN ('own', 'member', 'bill')),
  from_account_id INTEGER NOT NULL REFERENCES accounts(id),
  to_account_id INTEGER REFERENCES accounts(id),
  payee_name TEXT,
  amount_cents INTEGER NOT NULL CHECK (amount_cents > 0),
  memo TEXT,
  created_at TEXT NOT NULL
);
CREATE TABLE meta (
  key TEXT PRIMARY KEY,
  value TEXT NOT NULL
);
CREATE INDEX idx_accounts_user ON accounts(user_id);
CREATE INDEX idx_tx_account_date ON transactions(account_id, posted_at DESC, id DESC);
"""

AVERY_CHECKING = [
    ("2026-07-01", "Opening balance", 250_000, "opening"),
    ("2026-07-02", "Northwind Studio payroll", 428_000, "income"),
    ("2026-07-03", "Harbor Market", -8_640, "groceries"),
    ("2026-07-05", "Linden Apartments", -185_000, "housing"),
    ("2026-07-08", "City Power & Light", -14_220, "utilities"),
    ("2026-07-11", "Metro Transit", -9_200, "transport"),
    ("2026-07-16", "Northwind Studio payroll", 428_000, "income"),
    ("2026-07-18", "Transfer to Reserve Savings", -100_000, "internal"),
    ("2026-07-22", "Elm Street Books", -3_480, "shopping"),
    ("2026-07-30", "Northwind Studio payroll", 428_000, "income"),
    ("2026-08-02", "Linden Apartments", -185_000, "housing"),
    ("2026-08-04", "Harbor Market", -11_230, "groceries"),
    ("2026-08-09", "Cedar Clinic", -4_500, "health"),
    ("2026-08-13", "Northwind Studio payroll", 428_000, "income"),
    ("2026-08-15", "Transfer to Reserve Savings", -150_000, "internal"),
    ("2026-08-21", "Oat & Rye", -1_860, "dining"),
    ("2026-08-27", "Northwind Studio payroll", 428_000, "income"),
    ("2026-09-02", "Linden Apartments", -185_000, "housing"),
    ("2026-09-03", "City Power & Light", -13_640, "utilities"),
    ("2026-09-10", "Northwind Studio payroll", 428_000, "income"),
    ("2026-09-14", "Fee reversal", 1_500, "adjustment"),
    ("2026-09-18", "Harbor Market", -9_740, "groceries"),
    ("2026-09-24", "Northwind Studio payroll", 428_000, "income"),
    ("2026-09-28", "Transfer to Reserve Savings", -200_000, "internal"),
    ("2026-10-02", "Linden Apartments", -185_000, "housing"),
    ("2026-10-08", "Northwind Studio payroll", 428_000, "income"),
    ("2026-10-12", "Field & Vine", -6_420, "dining"),
    ("2026-10-16", "Received from Jordan Hale", 7_500, "transfer"),
    ("2026-10-22", "Northwind Studio payroll", 428_000, "income"),
]
AVERY_SAVINGS = [
    ("2026-07-01", "Opening balance", 1_800_000, "opening"),
    ("2026-07-18", "Transfer from Everyday Checking", 100_000, "internal"),
    ("2026-08-01", "Savings interest", None, "interest"),
    ("2026-08-15", "Transfer from Everyday Checking", 150_000, "internal"),
    ("2026-09-01", "Savings interest", None, "interest"),
    ("2026-09-28", "Transfer from Everyday Checking", 200_000, "internal"),
    ("2026-10-01", "Savings interest", None, "interest"),
]
JORDAN_CHECKING = [
    ("2026-08-01", "Opening balance", 410_000, "opening"),
    ("2026-08-03", "Hale Ceramics payroll", 364_000, "income"),
    ("2026-08-04", "Rent", -160_000, "housing"),
    ("2026-08-17", "Hale Ceramics payroll", 364_000, "income"),
    ("2026-08-20", "Market basket", -7_820, "groceries"),
    ("2026-08-31", "Hale Ceramics payroll", 364_000, "income"),
    ("2026-09-04", "Rent", -160_000, "housing"),
    ("2026-09-14", "Hale Ceramics payroll", 364_000, "income"),
    ("2026-09-19", "Studio electric", -9_430, "utilities"),
    ("2026-09-28", "Hale Ceramics payroll", 364_000, "income"),
    ("2026-10-02", "Rent", -160_000, "housing"),
    ("2026-10-05", "Transfer to Rainy Day Savings", -50_000, "internal"),
    ("2026-10-12", "Hale Ceramics payroll", 364_000, "income"),
    ("2026-10-16", "Sent to Avery Chen", -7_500, "transfer"),
    ("2026-10-20", "Kiln & Co supply", -12_640, "shopping"),
]
JORDAN_SAVINGS = [
    ("2026-08-01", "Opening balance", 960_000, "opening"),
    ("2026-09-01", "Savings interest", None, "interest"),
    ("2026-10-01", "Savings interest", None, "interest"),
    ("2026-10-05", "Transfer from Everyday Checking", 50_000, "internal"),
]


class FormError(Exception):
    def __init__(self, message: str):
        super().__init__(message)
        self.message = message


def db_path() -> Path:
    return Path(os.environ.get("NORTHWATER_DB", ROOT / "data" / "northwater.db"))


def load_secret() -> str:
    configured = os.environ.get("NORTHWATER_SECRET")
    if configured and len(configured) >= 16:
        return configured
    path = db_path().parent / "secret.key"
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.exists():
        current = path.read_text().strip()
        if len(current) >= 16:
            return current
    value = secrets.token_hex(32)
    path.write_text(value)
    with suppress(OSError):
        os.chmod(path, 0o600)
    return value


def hash_password(password: str, salt: bytes | None = None) -> str:
    if salt is None:
        salt = secrets.token_bytes(16)
    digest = hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt, PBKDF2_ROUNDS)
    return f"pbkdf2_sha256${PBKDF2_ROUNDS}${salt.hex()}${digest.hex()}"


DUMMY_HASH = hash_password("not-a-real-password", salt=bytes(16))


def verify_password(password: str, stored: str) -> bool:
    try:
        algorithm, rounds_text, salt_hex, digest_hex = stored.split("$")
        if algorithm != "pbkdf2_sha256":
            return False
        rounds = int(rounds_text)
        salt = bytes.fromhex(salt_hex)
        expected = bytes.fromhex(digest_hex)
    except (ValueError, TypeError):
        return False
    if rounds < 1 or rounds > 1_000_000:
        return False
    candidate = hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt, rounds)
    return secrets.compare_digest(candidate, expected)


def tokens_match(left: str, right: str) -> bool:
    if not isinstance(left, str) or not isinstance(right, str):
        return False
    try:
        return secrets.compare_digest(left, right)
    except (TypeError, ValueError):
        return False


def money_parts(cents: int) -> tuple[str, str]:
    amount = abs(int(cents))
    return f"{amount // 100:,}", f"{amount % 100:02d}"


def format_money(cents: int, signed: bool = False) -> str:
    dollars, frac = money_parts(cents)
    body = f"${dollars}.{frac}"
    if not signed or cents == 0:
        return body
    if cents > 0:
        return f"+{body}"
    return f"−{body}"


def parse_amount(raw: str) -> int:
    if not isinstance(raw, str):
        raise FormError("Enter a dollar amount like 25.00.")
    text = raw.strip().replace("$", "").replace(",", "").replace(" ", "")
    if not re.fullmatch(r"\d{1,7}(\.\d{1,2})?", text):
        raise FormError("Enter a dollar amount like 25.00.")
    if "." in text:
        whole, frac = text.split(".")
        frac = frac.ljust(2, "0")
    else:
        whole, frac = text, "00"
    cents = int(whole) * 100 + int(frac)
    if cents <= 0:
        raise FormError("Enter an amount greater than zero.")
    if cents > MAX_CENTS:
        raise FormError("Enter an amount up to $1,000,000.00.")
    return cents


def normalize_account_number(raw: str) -> str:
    text = re.sub(r"[\s-]", "", raw.strip())
    if not re.fullmatch(r"\d{10}", text):
        raise FormError("Enter a 10-digit Northwater account number.")
    return text


def clean_line(raw: str, *, limit: int) -> str:
    if not isinstance(raw, str):
        return ""
    text = raw.strip()
    if any(ord(ch) < 32 or ord(ch) == 127 for ch in text):
        raise FormError("That field can't include line breaks.")
    if len(text) > limit:
        raise FormError(f"Keep that field to {limit} characters.")
    return text


def mask_number(number: str) -> str:
    return f"ending {number[-4:]}"


def pretty_date(value: str, today: date) -> str:
    posted = date.fromisoformat(value[:10])
    if posted.year == today.year:
        return posted.strftime("%b %-d")
    return posted.strftime("%b %-d, %Y")


def long_date(value: date) -> str:
    return value.strftime("%A, %B %-d, %Y")


def month_day_year(value: str) -> str:
    posted = date.fromisoformat(value[:10])
    return posted.strftime("%B %-d, %Y")


def month_year(value: str) -> str:
    posted = date.fromisoformat(value[:10])
    return posted.strftime("%B %Y")


def pretty_timestamp(value: str) -> str:
    moment = datetime.fromisoformat(value)
    return moment.strftime("%B %-d, %Y at %-I:%M %p")


def greeting_for(moment: datetime) -> str:
    if moment.hour < 12:
        return "Good morning"
    if moment.hour < 17:
        return "Good afternoon"
    return "Good evening"


def monthly_interest(balance_cents: int) -> int:
    """3.80% APY, credited monthly and rounded half up to the cent."""
    if balance_cents <= 0:
        return 0
    return (balance_cents * 19 + 3000) // 6000


def materialize(events: list[tuple]) -> tuple[list[dict], int]:
    ordered = [event for _, event in sorted(enumerate(events), key=lambda item: (item[1][0], item[0]))]
    balance = 0
    rows = []
    for posted_at, description, amount, category in ordered:
        if amount is None:
            amount = monthly_interest(balance)
        if amount == 0:
            continue
        balance += amount
        if balance < 0:
            raise RuntimeError(f"{description} on {posted_at} overdraws the account ({balance} cents)")
        rows.append(
            {
                "posted_at": posted_at,
                "description": description,
                "amount_cents": amount,
                "balance_after_cents": balance,
                "category": category,
            }
        )
    return rows, balance


def category_net(rows: list[dict], category: str) -> int:
    return sum(row["amount_cents"] for row in rows if row["category"] == category)


def build_customers() -> list[dict]:
    today_iso = date.today().isoformat()
    avery_checking = list(AVERY_CHECKING)
    jordan_checking = list(JORDAN_CHECKING)
    if today_iso >= "2026-07-01":
        avery_checking.append((today_iso, "Little Peak Coffee", -475, "dining"))
    if today_iso >= "2026-08-01":
        jordan_checking.append((today_iso, "Farmers market", -1_820, "groceries"))

    avery_c_rows, avery_c_balance = materialize(avery_checking)
    avery_s_rows, avery_s_balance = materialize(AVERY_SAVINGS)
    jordan_c_rows, jordan_c_balance = materialize(jordan_checking)
    jordan_s_rows, jordan_s_balance = materialize(JORDAN_SAVINGS)
    if category_net(avery_c_rows + avery_s_rows, "internal") != 0:
        raise RuntimeError("Avery's transfers between accounts do not balance")
    if category_net(jordan_c_rows + jordan_s_rows, "internal") != 0:
        raise RuntimeError("Jordan's transfers between accounts do not balance")
    if category_net(avery_c_rows + avery_s_rows + jordan_c_rows + jordan_s_rows, "transfer") != 0:
        raise RuntimeError("Transfers between customers do not balance")

    def account(nickname: str, kind: str, number: str, opened_on: str, rows: list[dict], balance: int) -> dict:
        return {
            "nickname": nickname,
            "kind": kind,
            "number": number,
            "opened_on": opened_on,
            "rows": rows,
            "balance": balance,
        }

    return [
        {
            "email": "avery@northwater.demo",
            "full_name": "Avery Chen",
            "city": "Providence",
            "member_since": "2019-03-12",
            "accounts": [
                account("Everyday Checking", "checking", "4810291842", "2019-03-12", avery_c_rows, avery_c_balance),
                account("Reserve Savings", "savings", "4810299033", "2019-03-12", avery_s_rows, avery_s_balance),
            ],
        },
        {
            "email": "jordan@northwater.demo",
            "full_name": "Jordan Hale",
            "city": "Pawtucket",
            "member_since": "2021-08-02",
            "accounts": [
                account("Everyday Checking", "checking", "5501925510", "2021-08-02", jordan_c_rows, jordan_c_balance),
                account("Rainy Day Savings", "savings", "5501927721", "2021-08-02", jordan_s_rows, jordan_s_balance),
            ],
        },
    ]


def demo_people() -> list[dict]:
    return [{"full_name": person["full_name"], "email": person["email"]} for person in build_customers()]


@contextmanager
def connect():
    conn = sqlite3.connect(db_path(), timeout=5)
    try:
        conn.row_factory = sqlite3.Row
        conn.isolation_level = None
        conn.execute("PRAGMA foreign_keys = ON")
        conn.execute("PRAGMA busy_timeout = 5000")
        yield conn
    finally:
        conn.close()


def assert_ledger(conn: sqlite3.Connection) -> None:
    accounts = conn.execute("SELECT id, nickname, balance_cents FROM accounts").fetchall()
    if not accounts:
        raise RuntimeError("ledger has no accounts")
    for account in accounts:
        rows = conn.execute(
            """
            SELECT amount_cents, balance_after_cents, description, posted_at
            FROM transactions
            WHERE account_id = ?
            ORDER BY posted_at, id
            """,
            (account["id"],),
        ).fetchall()
        if not rows:
            raise RuntimeError(f"{account['nickname']} has no transactions")
        running = 0
        for row in rows:
            running += row["amount_cents"]
            if running != row["balance_after_cents"]:
                raise RuntimeError(
                    f"{account['nickname']} broke on {row['posted_at']} {row['description']}: "
                    f"running {running}, stored {row['balance_after_cents']}"
                )
        if running != account["balance_cents"]:
            raise RuntimeError(f"{account['nickname']} balance {account['balance_cents']} does not match the ledger {running}")

def seed(conn: sqlite3.Connection) -> None:
    customers = build_customers()
    password_hash = hash_password(DEMO_PASSWORD)
    conn.execute("BEGIN")
    try:
        for customer in customers:
            cursor = conn.execute(
                """
                INSERT INTO users (email, full_name, city, member_since, password_hash)
                VALUES (?, ?, ?, ?, ?)
                """,
                (
                    customer["email"],
                    customer["full_name"],
                    customer["city"],
                    customer["member_since"],
                    password_hash,
                ),
            )
            user_id = cursor.lastrowid
            for account in customer["accounts"]:
                cursor = conn.execute(
                    """
                    INSERT INTO accounts
                      (user_id, nickname, kind, account_number, routing_number, balance_cents, opened_on)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """,
                    (
                        user_id,
                        account["nickname"],
                        account["kind"],
                        account["number"],
                        ROUTING_NUMBER,
                        account["balance"],
                        account["opened_on"],
                    ),
                )
                account_id = cursor.lastrowid
                for row in account["rows"]:
                    conn.execute(
                        """
                        INSERT INTO transactions
                          (account_id, posted_at, description, memo, amount_cents, balance_after_cents, category, transfer_group)
                        VALUES (?, ?, ?, NULL, ?, ?, ?, NULL)
                        """,
                        (
                            account_id,
                            row["posted_at"],
                            row["description"],
                            row["amount_cents"],
                            row["balance_after_cents"],
                            row["category"],
                        ),
                    )
        conn.execute("INSERT INTO meta (key, value) VALUES ('seed_version', ?)", (SEED_VERSION,))
        assert_ledger(conn)
        conn.execute("COMMIT")
    except Exception:
        conn.execute("ROLLBACK")
        raise


def init_db() -> None:
    path = db_path()
    path.parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(path)
    try:
        conn.row_factory = sqlite3.Row
        conn.isolation_level = None
        conn.execute("PRAGMA foreign_keys = ON")
        conn.execute("PRAGMA journal_mode = WAL")
        has_meta = conn.execute("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'meta'").fetchone()
        version = None
        if has_meta:
            row = conn.execute("SELECT value FROM meta WHERE key = 'seed_version'").fetchone()
            version = row["value"] if row else None
        if version != SEED_VERSION:
            conn.executescript(
                """
                DROP TABLE IF EXISTS transactions;
                DROP TABLE IF EXISTS transfers;
                DROP TABLE IF EXISTS accounts;
                DROP TABLE IF EXISTS users;
                DROP TABLE IF EXISTS meta;
                """
                + SCHEMA
            )
            seed(conn)
        else:
            assert_ledger(conn)
    finally:
        conn.close()


def user_view(row: sqlite3.Row) -> dict:
    return {
        "id": row["id"],
        "full_name": row["full_name"],
        "first_name": row["full_name"].split()[0],
        "email": row["email"],
        "city": row["city"],
        "member_since": month_year(row["member_since"]),
    }


def account_view(row: sqlite3.Row) -> dict:
    dollars, cents_part = money_parts(row["balance_cents"])
    return {
        "id": row["id"],
        "nickname": row["nickname"],
        "kind": row["kind"],
        "kind_label": "Checking" if row["kind"] == "checking" else "Savings",
        "mask": mask_number(row["account_number"]),
        "number": row["account_number"],
        "routing": row["routing_number"],
        "balance": format_money(row["balance_cents"]),
        "balance_cents": row["balance_cents"],
        "dollars": dollars,
        "cents_part": cents_part,
        "opened": month_day_year(row["opened_on"]),
        "apy": "3.80% APY" if row["kind"] == "savings" else "",
    }


def tx_view(row: sqlite3.Row, today: date) -> dict:
    amount = row["amount_cents"]
    view = {
        "posted": pretty_date(row["posted_at"], today),
        "description": row["description"],
        "memo": row["memo"] or "",
        "category": CATEGORY_LABELS.get(row["category"], row["category"]),
        "amount": format_money(amount, signed=True),
        "direction": "in" if amount > 0 else "out",
        "balance": format_money(row["balance_after_cents"]),
        "account_id": row["account_id"],
    }
    if "nickname" in row:
        view["account"] = f"{row['nickname']} · {mask_number(row['account_number'])}"
    return view


def list_accounts(conn: sqlite3.Connection, user_id: int) -> list[sqlite3.Row]:
    return conn.execute(
        """
        SELECT * FROM accounts
        WHERE user_id = ?
        ORDER BY CASE kind WHEN 'checking' THEN 0 ELSE 1 END, id
        """,
        (user_id,),
    ).fetchall()


def owned_account(conn: sqlite3.Connection, user_id: int, account_id: int) -> sqlite3.Row | None:
    return conn.execute(
        "SELECT * FROM accounts WHERE id = ? AND user_id = ?",
        (account_id, user_id),
    ).fetchone()


def flow_totals(rows, *, skip_internal: bool) -> tuple[int, int]:
    skipped = {"opening"}
    if skip_internal:
        skipped.add("internal")
    incoming = 0
    outgoing = 0
    for row in rows:
        if row["category"] in skipped:
            continue
        amount = row["amount_cents"]
        if amount > 0:
            incoming += amount
        elif amount < 0:
            outgoing -= amount
    return incoming, outgoing


def month_rows(conn: sqlite3.Connection, user_id: int, today: date, account_id: int | None):
    start = today.replace(day=1).isoformat()
    if account_id is None:
        return conn.execute(
            """
            SELECT t.amount_cents, t.category
            FROM transactions t
            JOIN accounts a ON a.id = t.account_id
            WHERE a.user_id = ? AND t.posted_at >= ?
            """,
            (user_id, start),
        ).fetchall()
    return conn.execute(
        """
        SELECT amount_cents, category
        FROM transactions
        WHERE account_id = ? AND posted_at >= ?
        """,
        (account_id, start),
    ).fetchall()


def activity_sentence(incoming: int, outgoing: int, today: date, *, skip_internal: bool) -> str:
    month = today.strftime("%B")
    if incoming == 0 and outgoing == 0:
        sentence = f"Nothing posted in {month}"
    else:
        sentence = f"{format_money(incoming)} in and {format_money(outgoing)} out in {month}"
    if skip_internal:
        sentence += ", not counting transfers between your own accounts"
    return sentence + "."


def current_user(request: Request, conn: sqlite3.Connection) -> sqlite3.Row | None:
    user_id = request.session.get("user_id")
    if type(user_id) is not int:
        return None
    return conn.execute("SELECT * FROM users WHERE id = ?", (user_id,)).fetchone()


def ensure_csrf(request: Request) -> str:
    token = request.session.get("csrf")
    if not isinstance(token, str) or len(token) < 16:
        token = secrets.token_urlsafe(32)
        request.session["csrf"] = token
    return token


def csrf_ok(request: Request, token: str) -> bool:
    expected = request.session.get("csrf")
    if not isinstance(expected, str):
        return False
    return tokens_match(expected, token)


def redirect(path: str) -> RedirectResponse:
    return RedirectResponse(path, status_code=303)


def render(request: Request, conn: sqlite3.Connection, template_name: str, *, status_code: int = 200, **context):
    user = current_user(request, conn)
    active = context.pop("active", "")
    payload = {
        "request": request,
        "csrf": ensure_csrf(request),
        "user": user_view(user) if user else None,
        "flash": request.session.pop("flash", None),
        "active": active,
        "year": date.today().year,
        "error": context.pop("error", None),
        "email": context.pop("email", ""),
    }
    payload.update(context)
    return templates.TemplateResponse(request, template_name, payload, status_code=status_code)


def reject(request: Request, conn: sqlite3.Connection, message: str, status_code: int = 403):
    return render(
        request,
        conn,
        "rejected.html",
        status_code=status_code,
        heading="That didn't go through",
        message=message,
    )


def flash(request: Request, kind: str, text: str) -> None:
    request.session["flash"] = {"kind": kind, "text": text}


def like_pattern(query: str) -> str:
    escaped = query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
    return f"%{escaped}%"


def chosen_account_id(accounts: list[sqlite3.Row], requested: str | None) -> int:
    ids = {row["id"] for row in accounts}
    if requested and requested.isdigit() and int(requested) in ids:
        return int(requested)
    for row in accounts:
        if row["kind"] == "checking":
            return row["id"]
    return accounts[0]["id"]


def account_choices(conn: sqlite3.Connection, user_id: int) -> list[dict]:
    return [account_view(row) for row in list_accounts(conn, user_id)]


def parse_account_id(raw: str) -> int:
    if not isinstance(raw, str) or not raw.isdigit():
        raise FormError("Choose an account.")
    return int(raw)


def prepare_transfer(
    conn: sqlite3.Connection,
    user_id: int,
    from_raw: str,
    destination: str,
    account_number: str,
    amount_raw: str,
    memo_raw: str,
) -> dict:
    from_id = parse_account_id(from_raw)
    source = owned_account(conn, user_id, from_id)
    if source is None:
        raise FormError("Choose an account of yours.")
    memo = clean_line(memo_raw, limit=80)
    amount = parse_amount(amount_raw)
    if amount > source["balance_cents"]:
        raise FormError(f"{source['nickname']} has {format_money(source['balance_cents'])} available.")

    to_id = None
    scope = ""
    if destination.startswith("account:"):
        to_id = parse_account_id(destination.split(":", 1)[1])
        if to_id == from_id:
            raise FormError("Choose a different account.")
        dest = owned_account(conn, user_id, to_id)
        if dest is None:
            raise FormError("Choose one of your accounts.")
        scope = "own"
    elif destination == "external":
        number = normalize_account_number(account_number)
        dest = conn.execute(
            """
            SELECT a.*, u.full_name AS owner_name
            FROM accounts a
            JOIN users u ON u.id = a.user_id
            WHERE a.account_number = ?
            """,
            (number,),
        ).fetchone()
        if dest is None:
            raise FormError("No Northwater account uses that number.")
        if dest["user_id"] == user_id:
            raise FormError("That account is yours. Choose it from the list instead.")
        to_id = dest["id"]
        scope = "member"
    else:
        raise FormError("Choose where the money should go.")

    return {
        "nonce": secrets.token_urlsafe(16),
        "kind": "transfer",
        "scope": scope,
        "from_id": from_id,
        "to_id": to_id,
        "payee": "",
        "amount_cents": amount,
        "memo": memo,
    }


def prepare_bill(
    conn: sqlite3.Connection,
    user_id: int,
    from_raw: str,
    payee_raw: str,
    amount_raw: str,
    memo_raw: str,
) -> dict:
    from_id = parse_account_id(from_raw)
    source = owned_account(conn, user_id, from_id)
    if source is None:
        raise FormError("Choose an account of yours.")
    payee = clean_line(payee_raw, limit=60)
    if not payee:
        raise FormError("Enter a payee.")
    memo = clean_line(memo_raw, limit=80)
    amount = parse_amount(amount_raw)
    if amount > source["balance_cents"]:
        raise FormError(f"{source['nickname']} has {format_money(source['balance_cents'])} available.")
    return {
        "nonce": secrets.token_urlsafe(16),
        "kind": "bill",
        "scope": "bill",
        "from_id": from_id,
        "to_id": None,
        "payee": payee,
        "amount_cents": amount,
        "memo": memo,
    }


def load_party(conn: sqlite3.Connection, account_id: int) -> sqlite3.Row | None:
    return conn.execute(
        """
        SELECT a.*, u.full_name AS owner_name
        FROM accounts a
        JOIN users u ON u.id = a.user_id
        WHERE a.id = ?
        """,
        (account_id,),
    ).fetchone()


def valid_pending(pending) -> bool:
    if not isinstance(pending, dict):
        return False
    required = ("nonce", "kind", "scope", "from_id", "to_id", "payee", "amount_cents", "memo")
    if any(key not in pending for key in required):
        return False
    if type(pending["from_id"]) is not int or type(pending["amount_cents"]) is not int:
        return False
    if pending["to_id"] is not None and type(pending["to_id"]) is not int:
        return False
    if not isinstance(pending["nonce"], str) or not isinstance(pending["memo"], str):
        return False
    if not isinstance(pending["payee"], str):
        return False
    return pending["kind"] in {"transfer", "bill"} and pending["scope"] in {"own", "member", "bill"}


def describe_pending(conn: sqlite3.Connection, user_id: int, pending: dict) -> dict:
    if not valid_pending(pending):
        raise FormError("That transfer is no longer available. Start again.")
    source = owned_account(conn, user_id, pending["from_id"])
    if source is None:
        raise FormError("Choose an account of yours.")
    amount = pending["amount_cents"]
    if amount <= 0 or amount > MAX_CENTS:
        raise FormError("Enter a dollar amount like 25.00.")
    if amount > source["balance_cents"]:
        raise FormError(f"{source['nickname']} has {format_money(source['balance_cents'])} available.")
    memo = pending["memo"]
    if len(memo) > 80:
        raise FormError("Keep the memo to 80 characters.")

    dest = None
    if pending["scope"] in {"own", "member"}:
        dest = load_party(conn, pending["to_id"])
        if dest is None or dest["id"] == source["id"]:
            raise FormError("Choose where the money should go.")
        if pending["scope"] == "own" and dest["user_id"] != user_id:
            raise FormError("Choose one of your accounts.")
        if pending["scope"] == "member" and dest["user_id"] == user_id:
            raise FormError("That account is yours. Choose it from the list instead.")
    elif pending["scope"] != "bill" or not pending["payee"]:
        raise FormError("That transfer is no longer available. Start again.")

    if pending["scope"] == "own":
        to_line = f"{dest['nickname']} · {mask_number(dest['account_number'])}"
        headline = f"Move {format_money(amount)}"
        confirm_label = "Move now"
        edit_href = "/transfer"
        eyebrow = "Review transfer"
    elif pending["scope"] == "member":
        to_line = f"{dest['owner_name']}, {dest['nickname']} · {dest['account_number']}"
        headline = f"Send {format_money(amount)}"
        confirm_label = "Send now"
        edit_href = "/transfer"
        eyebrow = "Review transfer"
    else:
        to_line = pending["payee"]
        headline = f"Pay {format_money(amount)}"
        confirm_label = "Pay now"
        edit_href = "/pay"
        eyebrow = "Review bill"

    remaining = source["balance_cents"] - amount
    return {
        "eyebrow": eyebrow,
        "headline": headline,
        "nonce": pending["nonce"],
        "confirm_label": confirm_label,
        "edit_href": edit_href,
        "note": "This posts immediately in the demonstration ledger. Nothing leaves this computer.",
        "lines": [
            ("From", f"{source['nickname']} · {mask_number(source['account_number'])}"),
            ("To", to_line),
            ("Memo", memo or "—"),
            ("Afterward", f"{format_money(remaining)} left in {source['nickname']}"),
            ("Timing", "Posts immediately"),
        ],
    }


def post_ledger(conn: sqlite3.Connection, user_id: int, pending: dict) -> tuple[str, str, bool]:
    if not valid_pending(pending):
        raise FormError("That transfer is no longer available. Start again.")
    scope = pending["scope"]
    from_id = pending["from_id"]
    to_id = pending["to_id"]
    amount = pending["amount_cents"]
    memo = pending["memo"] or None
    nonce = pending["nonce"]
    payee = pending["payee"]
    if amount <= 0 or amount > MAX_CENTS:
        raise FormError("Enter a dollar amount like 25.00.")
    if memo is not None and (len(memo) > 80 or any(ord(ch) < 32 or ord(ch) == 127 for ch in memo)):
        raise FormError("The memo can't include line breaks.")
    if scope == "bill" and (not payee or len(payee) > 60):
        raise FormError("Enter a payee.")

    conn.execute("BEGIN IMMEDIATE")
    try:
        source = conn.execute(
            """
            SELECT a.*, u.full_name AS owner_name
            FROM accounts a
            JOIN users u ON u.id = a.user_id
            WHERE a.id = ? AND a.user_id = ?
            """,
            (from_id, user_id),
        ).fetchone()
        if source is None:
            raise FormError("Choose an account of yours.")
        dest = None
        if scope in {"own", "member"}:
            dest = load_party(conn, to_id)
            if dest is None or dest["id"] == source["id"]:
                raise FormError("Choose where the money should go.")
            if scope == "own" and dest["user_id"] != user_id:
                raise FormError("Choose one of your accounts.")
            if scope == "member" and dest["user_id"] == user_id:
                raise FormError("That account is yours. Choose it from the list instead.")
        elif scope != "bill":
            raise FormError("That transfer is no longer available. Start again.")

        updated = conn.execute(
            """
            UPDATE accounts
            SET balance_cents = balance_cents - ?
            WHERE id = ? AND balance_cents >= ?
            """,
            (amount, source["id"], amount),
        )
        if updated.rowcount != 1:
            fresh = conn.execute(
                "SELECT balance_cents, nickname FROM accounts WHERE id = ?",
                (source["id"],),
            ).fetchone()
            available = format_money(fresh["balance_cents"]) if fresh else "$0.00"
            name = fresh["nickname"] if fresh else "That account"
            raise FormError(f"{name} has {available} available.")

        source_balance = conn.execute(
            "SELECT balance_cents FROM accounts WHERE id = ?",
            (source["id"],),
        ).fetchone()["balance_cents"]
        posted_on = date.today().isoformat()
        if scope == "bill":
            description = payee
            category = "bill"
            dest_label = payee
        elif scope == "own":
            description = f"Transfer to {dest['nickname']}"
            category = "internal"
            dest_label = dest["nickname"]
        else:
            description = f"Sent to {dest['owner_name']}"
            category = "transfer"
            dest_label = dest["owner_name"]

        conn.execute(
            """
            INSERT INTO transactions
              (account_id, posted_at, description, memo, amount_cents, balance_after_cents, category, transfer_group)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (source["id"], posted_on, description, memo, -amount, source_balance, category, nonce),
        )
        if dest is not None:
            conn.execute(
                "UPDATE accounts SET balance_cents = balance_cents + ? WHERE id = ?",
                (amount, dest["id"]),
            )
            dest_balance = conn.execute(
                "SELECT balance_cents FROM accounts WHERE id = ?",
                (dest["id"],),
            ).fetchone()["balance_cents"]
            if scope == "own":
                credit_description = f"Transfer from {source['nickname']}"
                credit_category = "internal"
            else:
                credit_description = f"Received from {source['owner_name']}"
                credit_category = "transfer"
            conn.execute(
                """
                INSERT INTO transactions
                  (account_id, posted_at, description, memo, amount_cents, balance_after_cents, category, transfer_group)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    dest["id"],
                    posted_on,
                    credit_description,
                    memo,
                    amount,
                    dest_balance,
                    credit_category,
                    nonce,
                ),
            )

        confirmation = "NW-" + secrets.token_hex(4).upper()
        created = datetime.now().replace(microsecond=0).isoformat(sep=" ")
        conn.execute(
            """
            INSERT INTO transfers
              (confirmation, nonce, initiator_id, kind, scope, from_account_id, to_account_id,
               payee_name, amount_cents, memo, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                confirmation,
                nonce,
                user_id,
                pending["kind"],
                scope,
                source["id"],
                None if dest is None else dest["id"],
                payee or None,
                amount,
                memo,
                created,
            ),
        )
        conn.execute("COMMIT")
    except FormError:
        conn.execute("ROLLBACK")
        raise
    except sqlite3.IntegrityError as err:
        conn.execute("ROLLBACK")
        existing = conn.execute(
            "SELECT confirmation FROM transfers WHERE nonce = ?",
            (nonce,),
        ).fetchone()
        if existing:
            return existing["confirmation"], "This transfer was already posted.", True
        raise FormError("Couldn't post that transfer. Review it again.") from err
    except Exception:
        conn.execute("ROLLBACK")
        raise

    if scope == "own":
        message = f"Moved {format_money(amount)} from {source['nickname']} to {dest_label}."
    elif scope == "member":
        message = f"Sent {format_money(amount)} to {dest_label} from {source['nickname']}."
    else:
        message = f"Paid {format_money(amount)} to {dest_label} from {source['nickname']}."
    return confirmation, message, False


def receipt_view(conn: sqlite3.Connection, user_id: int, confirmation: str) -> dict | None:
    if not re.fullmatch(r"NW-[0-9A-F]{8}", confirmation):
        return None
    row = conn.execute(
        """
        SELECT tr.*,
               src.nickname AS from_nickname,
               src.account_number AS from_number,
               src.user_id AS from_user_id,
               dst.nickname AS to_nickname,
               dst.account_number AS to_number,
               dst.user_id AS to_user_id,
               dst_user.full_name AS to_name
        FROM transfers tr
        JOIN accounts src ON src.id = tr.from_account_id
        LEFT JOIN accounts dst ON dst.id = tr.to_account_id
        LEFT JOIN users dst_user ON dst_user.id = dst.user_id
        WHERE tr.confirmation = ?
        """,
        (confirmation,),
    ).fetchone()
    if row is None:
        return None
    allowed = user_id == row["initiator_id"] or (row["to_user_id"] is not None and user_id == row["to_user_id"])
    if not allowed:
        return None
    if row["scope"] == "own":
        to_line = f"{row['to_nickname']} · {mask_number(row['to_number'])}"
        headline = f"Moved {format_money(row['amount_cents'])}"
    elif row["scope"] == "member":
        to_line = f"{row['to_name']}, {row['to_nickname']} · {mask_number(row['to_number'])}"
        headline = f"Sent {format_money(row['amount_cents'])}"
    else:
        to_line = row["payee_name"]
        headline = f"Paid {format_money(row['amount_cents'])}"
    return {
        "kind": row["kind"],
        "confirmation": row["confirmation"],
        "headline": headline,
        "from_id": row["from_account_id"],
        "lines": [
            ("From", f"{row['from_nickname']} · {mask_number(row['from_number'])}"),
            ("To", to_line),
            ("Amount", format_money(row["amount_cents"])),
            ("Memo", row["memo"] or "—"),
            ("Posted", pretty_timestamp(row["created_at"])),
        ],
    }


def form_values(from_id: int, **extra) -> dict:
    values = {
        "from_id": from_id,
        "amount": "",
        "memo": "",
        "error": None,
    }
    values.update(extra)
    return values


class SecurityHeaders(BaseHTTPMiddleware):
    async def dispatch(self, request, call_next):
        response = await call_next(request)
        response.headers["X-Content-Type-Options"] = "nosniff"
        response.headers["X-Frame-Options"] = "DENY"
        response.headers["Referrer-Policy"] = "same-origin"
        response.headers["Cross-Origin-Opener-Policy"] = "same-origin"
        response.headers["X-Permitted-Cross-Domain-Policies"] = "none"
        response.headers["Content-Security-Policy"] = (
            "default-src 'self'; "
            "style-src 'self'; "
            "font-src 'self'; "
            "img-src 'self'; "
            "script-src 'self'; "
            "form-action 'self'; "
            "base-uri 'self'; "
            "frame-ancestors 'none'"
        )
        content_type = response.headers.get("content-type", "")
        if content_type.startswith("text/html"):
            response.headers["Cache-Control"] = "no-store"
        return response


@asynccontextmanager
async def lifespan(app: FastAPI):
    init_db()
    yield


app = FastAPI(lifespan=lifespan, docs_url=None, redoc_url=None, openapi_url=None)
app.add_middleware(
    SessionMiddleware,
    secret_key=load_secret(),
    session_cookie="northwater",
    max_age=60 * 60 * 8,
    same_site="lax",
    https_only=False,
)
app.add_middleware(SecurityHeaders)
app.mount("/static", StaticFiles(directory=ROOT / "static"), name="static")
templates = Jinja2Templates(directory=ROOT / "templates")


@app.get("/favicon.ico", include_in_schema=False)
def favicon():
    return FileResponse(ROOT / "static" / "favicon.svg", media_type="image/svg+xml")


@app.get("/")
def home(request: Request):
    with connect() as conn:
        user = current_user(request, conn)
        if user is None:
            return render(
                request,
                conn,
                "login.html",
                demo_people=demo_people(),
                demo_password=DEMO_PASSWORD,
            )
        today = date.today()
        rows = list_accounts(conn, user["id"])
        total = sum(row["balance_cents"] for row in rows)
        dollars, cents_part = money_parts(total)
        incoming, outgoing = flow_totals(
            month_rows(conn, user["id"], today, None),
            skip_internal=True,
        )
        recent = conn.execute(
            """
            SELECT t.*, a.nickname, a.account_number
            FROM transactions t
            JOIN accounts a ON a.id = t.account_id
            WHERE a.user_id = ?
            ORDER BY t.posted_at DESC, t.id DESC
            LIMIT 8
            """,
            (user["id"],),
        ).fetchall()
        return render(
            request,
            conn,
            "dashboard.html",
            active="overview",
            greeting=greeting_for(datetime.now()),
            today_long=long_date(today),
            total_cents=total,
            total_dollars=dollars,
            cents_part=cents_part,
            summary=f"Across {len(rows)} accounts. {activity_sentence(incoming, outgoing, today, skip_internal=True)}",
            accounts=[account_view(row) for row in rows],
            transactions=[tx_view(row, today) for row in recent],
            show_account=True,
            show_category=False,
            show_balance=False,
            link_description=True,
            caption="Recent activity",
        )


@app.post("/login")
def login(
    request: Request,
    email: str = Form(""),
    password: str = Form(""),
    csrf: str = Form(""),
):
    with connect() as conn:
        if not csrf_ok(request, csrf):
            return render(
                request,
                conn,
                "login.html",
                status_code=403,
                error="The form expired. Sign in again.",
                email=email[:120],
                demo_people=demo_people(),
                demo_password=DEMO_PASSWORD,
            )
        address = email.strip().lower()
        candidate = password if isinstance(password, str) and len(password) <= 128 else ""
        user = conn.execute("SELECT * FROM users WHERE email = ?", (address,)).fetchone()
        stored = user["password_hash"] if user is not None else DUMMY_HASH
        password_ok = verify_password(candidate or "missing", stored)
        if user is None or candidate == "" or not password_ok:
            return render(
                request,
                conn,
                "login.html",
                status_code=401,
                error="Email or password is incorrect.",
                email=address[:120],
                demo_people=demo_people(),
                demo_password=DEMO_PASSWORD,
            )
        request.session.clear()
        request.session["user_id"] = user["id"]
        request.session["csrf"] = secrets.token_urlsafe(32)
        return redirect("/")


@app.post("/logout")
def logout(request: Request, csrf: str = Form("")):
    with connect() as conn:
        if not csrf_ok(request, csrf):
            return reject(request, conn, "The form expired. Try signing out again.")
        request.session.clear()
        flash(request, "ok", "Signed out.")
        return redirect("/")


@app.get("/accounts/{account_id}")
def account_detail(request: Request, account_id: int, flow: str = "all", q: str = ""):
    with connect() as conn:
        user = current_user(request, conn)
        if user is None:
            return redirect("/")
        account = owned_account(conn, user["id"], account_id)
        if account is None:
            return render(
                request,
                conn,
                "not_found.html",
                status_code=404,
                heading="That page isn't on the books.",
                message="The link may be old, or the account isn't yours.",
            )
        if flow not in {"all", "in", "out"}:
            flow = "all"
        query = q.strip()[:80] if isinstance(q, str) else ""
        if any(ord(ch) < 32 or ord(ch) == 127 for ch in query):
            query = ""
        clauses = ["account_id = ?"]
        params: list = [account_id]
        if flow == "in":
            clauses.append("amount_cents > 0 AND category != 'opening'")
        elif flow == "out":
            clauses.append("amount_cents < 0")
        if query:
            pattern = like_pattern(query)
            clauses.append("(description LIKE ? ESCAPE '\\' OR IFNULL(memo, '') LIKE ? ESCAPE '\\')")
            params.extend([pattern, pattern])
        rows = conn.execute(
            f"""
            SELECT * FROM transactions
            WHERE {" AND ".join(clauses)}
            ORDER BY posted_at DESC, id DESC
            """,
            params,
        ).fetchall()
        today = date.today()
        incoming, outgoing = flow_totals(
            month_rows(conn, user["id"], today, account_id),
            skip_internal=False,
        )
        filters = []
        for key, label in (("all", "All"), ("in", "Money in"), ("out", "Money out")):
            params = {"flow": key}
            if query:
                params["q"] = query
            filters.append(
                {
                    "href": f"/accounts/{account_id}?{urlencode(params)}",
                    "label": label,
                    "current": flow == key,
                }
            )
        return render(
            request,
            conn,
            "account.html",
            active="overview",
            account=account_view(account),
            summary=activity_sentence(incoming, outgoing, today, skip_internal=False),
            transactions=[tx_view(row, today) for row in rows],
            filters=filters,
            flow=flow,
            q=query,
            show_account=False,
            show_category=True,
            show_balance=True,
            link_description=False,
            caption=f"Statement for {account['nickname']}",
        )


@app.get("/transfer")
def transfer_form(request: Request):
    request.session.pop("pending", None)
    with connect() as conn:
        user = current_user(request, conn)
        if user is None:
            return redirect("/")
        accounts = list_accounts(conn, user["id"])
        from_id = chosen_account_id(accounts, request.query_params.get("from"))
        others = [row["id"] for row in accounts if row["id"] != from_id]
        destination = f"account:{others[0]}" if others else "external"
        return render(
            request,
            conn,
            "transfer.html",
            active="transfer",
            accounts=account_choices(conn, user["id"]),
            account_number="",
            destination=destination,
            **form_values(from_id),
        )


@app.post("/transfer")
def transfer_submit(
    request: Request,
    csrf: str = Form(""),
    from_id: str = Form(""),
    destination: str = Form(""),
    account_number: str = Form(""),
    amount: str = Form(""),
    memo: str = Form(""),
):
    with connect() as conn:
        user = current_user(request, conn)
        if user is None:
            return redirect("/")
        if not csrf_ok(request, csrf):
            return reject(request, conn, "The form expired. Start the transfer again.")
        posted = {
            "from_id": int(from_id) if from_id.isdigit() else chosen_account_id(list_accounts(conn, user["id"]), None),
            "destination": destination if destination.startswith("account:") or destination == "external" else "external",
            "account_number": account_number[:20],
            "amount": amount[:20],
            "memo": memo[:80],
        }
        try:
            pending = prepare_transfer(
                conn,
                user["id"],
                from_id,
                destination,
                account_number,
                amount,
                memo,
            )
        except FormError as exc:
            request.session.pop("pending", None)
            return render(
                request,
                conn,
                "transfer.html",
                status_code=400,
                active="transfer",
                accounts=account_choices(conn, user["id"]),
                error=exc.message,
                **posted,
            )
        request.session["pending"] = pending
        return redirect("/review")


@app.get("/pay")
def pay_form(request: Request):
    request.session.pop("pending", None)
    with connect() as conn:
        user = current_user(request, conn)
        if user is None:
            return redirect("/")
        accounts = list_accounts(conn, user["id"])
        from_id = chosen_account_id(accounts, request.query_params.get("from"))
        return render(
            request,
            conn,
            "pay.html",
            active="pay",
            accounts=account_choices(conn, user["id"]),
            payee_names=PAYEES,
            payee="",
            **form_values(from_id),
        )


@app.post("/pay")
def pay_submit(
    request: Request,
    csrf: str = Form(""),
    from_id: str = Form(""),
    payee: str = Form(""),
    amount: str = Form(""),
    memo: str = Form(""),
):
    with connect() as conn:
        user = current_user(request, conn)
        if user is None:
            return redirect("/")
        if not csrf_ok(request, csrf):
            return reject(request, conn, "The form expired. Start the payment again.")
        selected = int(from_id) if from_id.isdigit() else chosen_account_id(list_accounts(conn, user["id"]), None)
        try:
            pending = prepare_bill(conn, user["id"], from_id, payee, amount, memo)
        except FormError as exc:
            request.session.pop("pending", None)
            return render(
                request,
                conn,
                "pay.html",
                status_code=400,
                active="pay",
                accounts=account_choices(conn, user["id"]),
                payee_names=PAYEES,
                error=exc.message,
                from_id=selected,
                payee=payee[:60],
                amount=amount[:20],
                memo=memo[:80],
            )
        request.session["pending"] = pending
        return redirect("/review")


@app.get("/review")
def review(request: Request):
    with connect() as conn:
        user = current_user(request, conn)
        if user is None:
            return redirect("/")
        pending = request.session.get("pending")
        if not valid_pending(pending):
            return redirect("/transfer")
        try:
            described = describe_pending(conn, user["id"], pending)
        except FormError as exc:
            request.session.pop("pending", None)
            flash(request, "error", exc.message)
            return redirect("/pay" if pending.get("kind") == "bill" else "/transfer")
        return render(
            request,
            conn,
            "review.html",
            active="pay" if pending["kind"] == "bill" else "transfer",
            review=described,
        )


@app.post("/confirm")
def confirm(request: Request, csrf: str = Form(""), nonce: str = Form("")):
    with connect() as conn:
        user = current_user(request, conn)
        if user is None:
            return redirect("/")
        if not csrf_ok(request, csrf):
            return reject(request, conn, "The form expired. Review the transfer again.")
        pending = request.session.get("pending")
        if not valid_pending(pending) or not tokens_match(pending["nonce"], nonce):
            flash(request, "error", "That transfer is no longer available. Start again.")
            return redirect("/transfer")
        try:
            confirmation, message, _replayed = post_ledger(conn, user["id"], pending)
        except FormError as exc:
            kind = pending["kind"]
            request.session.pop("pending", None)
            flash(request, "error", exc.message)
            return redirect("/pay" if kind == "bill" else "/transfer")
        request.session.pop("pending", None)
        flash(request, "ok", message)
        return redirect(f"/receipts/{confirmation}")


@app.get("/receipts/{confirmation}")
def receipt(request: Request, confirmation: str):
    with connect() as conn:
        user = current_user(request, conn)
        if user is None:
            return redirect("/")
        view = receipt_view(conn, user["id"], confirmation)
        if view is None:
            return render(
                request,
                conn,
                "not_found.html",
                status_code=404,
                heading="That page isn't on the books.",
                message="The receipt isn't available on this customer.",
            )
        return render(
            request,
            conn,
            "receipt.html",
            active="pay" if view["kind"] == "bill" else "transfer",
            receipt=view,
        )


@app.exception_handler(404)
async def missing(request: Request, _exc: Exception):
    with connect() as conn:
        return render(
            request,
            conn,
            "not_found.html",
            status_code=404,
            heading="That page isn't on the books.",
            message="The link may be old, or the page isn't part of this demo.",
        )


if __name__ == "__main__":
    import uvicorn

    port = int(os.environ.get("PORT", "8000"))
    print(f"Northwater demo is running at http://127.0.0.1:{port}")
    print("Avery Chen    avery@northwater.demo")
    print("Jordan Hale   jordan@northwater.demo")
    print(f"Password      {DEMO_PASSWORD}")
    uvicorn.run(app, host="127.0.0.1", port=port)
