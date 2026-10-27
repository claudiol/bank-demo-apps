"""Exercise the Northwater ledger against a temporary database."""

from __future__ import annotations

import os
import re
import tempfile
from pathlib import Path

os.environ["NORTHWATER_DB"] = str(Path(tempfile.mkdtemp()) / "northwater.db")
os.environ["NORTHWATER_SECRET"] = "test-secret-value"

from fastapi.testclient import TestClient

import app as bank


def csrf_of(html: str) -> str:
    match = re.search(r'name="csrf" value="([^"]+)"', html)
    if not match:
        raise SystemExit("csrf token missing\n" + html[:800])
    return match.group(1)


def nonce_of(html: str) -> str:
    match = re.search(r'name="nonce" value="([^"]+)"', html)
    if not match:
        raise SystemExit("nonce missing\n" + html[:800])
    return match.group(1)


def check(condition: bool, message: str, response=None) -> None:
    if condition:
        return
    if response is not None:
        print(response.status_code)
        print(response.text[:2000])
    raise SystemExit(message)


def account_row(email: str, kind: str):
    with bank.connect() as conn:
        return conn.execute(
            """
            SELECT a.*
            FROM accounts a
            JOIN users u ON u.id = a.user_id
            WHERE u.email = ? AND a.kind = ?
            """,
            (email, kind),
        ).fetchone()


def balance(account_id: int) -> int:
    with bank.connect() as conn:
        return conn.execute(
            "SELECT balance_cents FROM accounts WHERE id = ?",
            (account_id,),
        ).fetchone()["balance_cents"]


def sign_in(client: TestClient, email: str, password: str = bank.DEMO_PASSWORD):
    page = client.get("/")
    check(page.status_code == 200, "login page failed", page)
    response = client.post(
        "/login",
        data={"csrf": csrf_of(page.text), "email": email, "password": password},
        follow_redirects=True,
    )
    check(response.status_code == 200, "sign-in failed", response)
    return response


def main() -> None:
    check(bank.parse_amount("25") == 2500, "whole dollars")
    check(bank.parse_amount("$1,000.50") == 100_050, "commas and symbol")
    check(bank.parse_amount("1000000.00") == bank.MAX_CENTS, "cap boundary")
    for sample in ("", "0.00", "-5", "10.005", "1000000.01", "abc"):
        try:
            bank.parse_amount(sample)
        except bank.FormError:
            continue
        raise SystemExit(f"accepted bad amount {sample!r}")

    with TestClient(bank.app) as client:
        login = client.get("/")
        check("Sign in" in login.text and "Avery Chen" in login.text, "login copy", login)
        check('name="csrf"' in login.text, "login csrf", login)

        wrong = client.post(
            "/login",
            data={"csrf": csrf_of(login.text), "email": "avery@northwater.demo", "password": "nope"},
            follow_redirects=False,
        )
        check(wrong.status_code == 401, "wrong password status", wrong)
        check("incorrect" in wrong.text, "wrong password copy", wrong)

        blocked = client.get("/transfer", follow_redirects=False)
        check(blocked.status_code == 303 and blocked.headers["location"] == "/", "auth gate", blocked)

        home = sign_in(client, "avery@northwater.demo")
        check("Available" in home.text and "Everyday Checking" in home.text, "dashboard", home)
        avery_checking = account_row("avery@northwater.demo", "checking")
        avery_savings = account_row("avery@northwater.demo", "savings")
        jordan_checking = account_row("jordan@northwater.demo", "checking")
        total = avery_checking["balance_cents"] + avery_savings["balance_cents"]
        check(f'data-cents="{total}"' in home.text, "dashboard total", home)
        check(jordan_checking["account_number"] not in home.text, "other customer's number hidden", home)

        stolen = client.get(f"/accounts/{jordan_checking['id']}")
        check(stolen.status_code == 404, "idor status", stolen)
        check(jordan_checking["account_number"] not in stolen.text, "idor body", stolen)

        statement = client.get(f"/accounts/{avery_checking['id']}")
        check(avery_checking["account_number"] in statement.text, "owner sees account number", statement)
        check("Harbor Market" in statement.text, "statement history", statement)

        money_out = client.get(f"/accounts/{avery_checking['id']}?flow=out")
        check("Linden Apartments" in money_out.text, "money out includes rent", money_out)
        check("Northwind Studio payroll" not in money_out.text, "money out hides payroll", money_out)

        found = client.get(f"/accounts/{avery_checking['id']}?q=harbor")
        check("Harbor Market" in found.text and "Northwind Studio payroll" not in found.text, "search", found)
        wildcard = client.get(f"/accounts/{avery_checking['id']}?q=%")
        check("Harbor Market" not in wildcard.text, "wildcard search escaped", wildcard)

        before_checking = balance(avery_checking["id"])
        before_savings = balance(avery_savings["id"])
        form = client.get("/transfer")
        moved = client.post(
            "/transfer",
            data={
                "csrf": csrf_of(form.text),
                "from_id": str(avery_checking["id"]),
                "destination": f"account:{avery_savings['id']}",
                "account_number": "",
                "amount": "10.25",
                "memo": "Rainy afternoon",
            },
            follow_redirects=True,
        )
        check("Move now" in moved.text and "Rainy afternoon" in moved.text, "review internal", moved)
        nonce = nonce_of(moved.text)
        receipt = client.post(
            "/confirm",
            data={"csrf": csrf_of(moved.text), "nonce": nonce},
            follow_redirects=True,
        )
        check("NW-" in receipt.text and "Moved $10.25" in receipt.text, "receipt", receipt)
        check(balance(avery_checking["id"]) == before_checking - 1025, "checking debited")
        check(balance(avery_savings["id"]) == before_savings + 1025, "savings credited")
        check(
            balance(avery_checking["id"]) + balance(avery_savings["id"]) == before_checking + before_savings,
            "internal transfer conserves cash",
        )

        replay = client.post(
            "/confirm",
            data={"csrf": csrf_of(receipt.text), "nonce": nonce},
            follow_redirects=False,
        )
        check(replay.status_code == 303, "replay does not post twice", replay)
        check(balance(avery_checking["id"]) == before_checking - 1025, "replay left the balance")

        with bank.connect() as conn:
            user_id = conn.execute(
                "SELECT id FROM users WHERE email = ?",
                ("avery@northwater.demo",),
            ).fetchone()["id"]
            pending = {
                "nonce": "replay-nonce-1",
                "kind": "transfer",
                "scope": "own",
                "from_id": avery_checking["id"],
                "to_id": avery_savings["id"],
                "payee": "",
                "amount_cents": 100,
                "memo": "",
            }
            first = bank.post_ledger(conn, user_id, pending)
            second = bank.post_ledger(conn, user_id, pending)
        check(first[0] == second[0] and second[2] is True, "database nonce replays once")
        check(balance(avery_checking["id"]) == before_checking - 1125, "replayed ledger posted once")

        same = client.post(
            "/transfer",
            data={
                "csrf": csrf_of(client.get("/transfer").text),
                "from_id": str(avery_checking["id"]),
                "destination": f"account:{avery_checking['id']}",
                "amount": "1.00",
                "memo": "",
                "account_number": "",
            },
            follow_redirects=False,
        )
        check(same.status_code == 400 and "different account" in same.text, "same account rejected", same)

        unknown = client.post(
            "/transfer",
            data={
                "csrf": csrf_of(client.get("/transfer").text),
                "from_id": str(avery_checking["id"]),
                "destination": "external",
                "account_number": "' OR 1=1 --",
                "amount": "1.00",
                "memo": "",
            },
            follow_redirects=False,
        )
        check(unknown.status_code == 400, "bad account number", unknown)
        check("No Northwater account" in unknown.text or "10-digit" in unknown.text, "bad account copy", unknown)

        over_cents = balance(avery_checking["id"]) + 1
        over = client.post(
            "/transfer",
            data={
                "csrf": csrf_of(client.get("/transfer").text),
                "from_id": str(avery_checking["id"]),
                "destination": f"account:{avery_savings['id']}",
                "amount": f"{over_cents / 100:.2f}",
                "memo": "",
                "account_number": "",
            },
            follow_redirects=False,
        )
        check(over.status_code == 400 and "available" in over.text, "overdraft", over)
        check(balance(avery_checking["id"]) == before_checking - 1125, "overdraft did not post")

        before_jordan = balance(jordan_checking["id"])
        before_avery = balance(avery_checking["id"])
        external_form = client.get("/transfer")
        external = client.post(
            "/transfer",
            data={
                "csrf": csrf_of(external_form.text),
                "from_id": str(avery_checking["id"]),
                "destination": "external",
                "account_number": jordan_checking["account_number"],
                "amount": "15.00",
                "memo": "<script>alert(1)</script>",
            },
            follow_redirects=True,
        )
        check(jordan_checking["account_number"] in external.text, "review shows the typed number", external)
        sent = client.post(
            "/confirm",
            data={"csrf": csrf_of(external.text), "nonce": nonce_of(external.text)},
            follow_redirects=True,
        )
        check("Sent $15.00" in sent.text, "member receipt", sent)
        check("<script>alert(1)</script>" not in sent.text, "script tag not raw", sent)
        check("&lt;script&gt;alert(1)&lt;/script&gt;" in sent.text, "script tag escaped", sent)
        check(balance(avery_checking["id"]) == before_avery - 1500, "sender debited")
        check(balance(jordan_checking["id"]) == before_jordan + 1500, "recipient credited")

        pay_form = client.get("/pay")
        pay_review = client.post(
            "/pay",
            data={
                "csrf": csrf_of(pay_form.text),
                "from_id": str(avery_checking["id"]),
                "payee": "Corner Bookstore",
                "amount": "12.40",
                "memo": "October",
            },
            follow_redirects=True,
        )
        check("Pay now" in pay_review.text and "Corner Bookstore" in pay_review.text, "bill review", pay_review)
        paid = client.post(
            "/confirm",
            data={"csrf": csrf_of(pay_review.text), "nonce": nonce_of(pay_review.text)},
            follow_redirects=True,
        )
        check("Paid $12.40" in paid.text, "bill receipt", paid)
        check(balance(avery_checking["id"]) == before_avery - 1500 - 1240, "bill debited")
        check(balance(jordan_checking["id"]) == before_jordan + 1500, "bill did not credit Jordan")

        with bank.connect() as conn:
            bank.assert_ledger(conn)

        signed_out = client.post(
            "/logout",
            data={"csrf": csrf_of(paid.text)},
            follow_redirects=True,
        )
        check("Sign in" in signed_out.text and "Signed out." in signed_out.text, "signed out", signed_out)
        again = client.get("/")
        check("Signed out." not in again.text, "sign-out notice is shown once", again)

        jordan_home = sign_in(client, "jordan@northwater.demo")
        check("Received from Avery Chen" in jordan_home.text, "Jordan sees the incoming transfer", jordan_home)
        check(avery_checking["account_number"] not in jordan_home.text, "Avery's number stays hidden", jordan_home)

    print("all checks passed")


if __name__ == "__main__":
    main()
