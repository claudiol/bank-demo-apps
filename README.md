# Northwater architecture

[![Python](https://img.shields.io/github/actions/workflow/status/claudiol/bank-demo-apps/ci.yml?branch=main&job=python&label=Python)](https://github.com/claudiol/bank-demo-apps/actions/workflows/ci.yml)
[![Java](https://img.shields.io/github/actions/workflow/status/claudiol/bank-demo-apps/ci.yml?branch=main&job=java&label=Java)](https://github.com/claudiol/bank-demo-apps/actions/workflows/ci.yml)

Northwater is a fictional retail bank that runs as one program on your computer. A browser talks to that program. The program keeps customers, accounts, and a ledger in a SQLite file. Nothing is sent to a payment network, another bank, or a remote service.

The same design is implemented twice:

| | Python | Java |
|---|---|---|
| Directory | `northwater/` | `northwater-java/` |
| HTTP | FastAPI and Uvicorn | Javalin and Jetty |
| Pages | Jinja2 | Thymeleaf |
| Default address | http://127.0.0.1:8000 | http://127.0.0.1:8080 |
| Ledger file | `northwater/data/northwater.db` | `northwater-java/data/northwater.db` |

Each implementation has its own database. They do not share sessions or balances.

## Components

```mermaid
flowchart LR
    browser[Browser]
    http[HTTP application]
    pages[Page templates and static files]
    ledger[Ledger]
    db[(SQLite file)]

    browser -->|HTML forms| http
    http -->|HTML| browser
    http --> pages
    http --> ledger
    ledger --> db
```

**Browser.** The customer uses ordinary pages: sign in, overview, account statement, move money, pay a bill, review, and receipt. A small script disables a submit button after it is pressed, and shows the account-number field only when the destination is another Northwater customer. Fonts, CSS, and that script are served by the application.

**HTTP application.** This is the front door. It checks the session, checks the form token, validates input, and chooses the next page. Signed-in pages redirect to sign-in when there is no session. Unknown pages and accounts that belong to someone else return a not-found page. Responses send a content-security policy that allows only this application’s own files.

**Page templates.** The application fills HTML templates with the signed-in customer, balances, and the statement. Customer-supplied text is inserted as text, so a memo cannot become markup. A one-time notice, such as “Signed out.”, is stored in the session and removed when the next page is rendered.

**Ledger.** This is the only place that reads and writes money. Amounts are integer cents. An account balance is stored on the account and must match the running total of that account’s transactions. On startup the ledger opens the SQLite file, turns on foreign keys and write-ahead logging, and loads the demonstration books if the file is new or from an older seed.

**SQLite file.** One local file holds the whole bank. The path can be changed with `NORTHWATER_DB`. The port can be changed with `PORT`.

## What the ledger stores

- **Customers.** Two demonstration customers, Avery Chen and Jordan Hale. Passwords are stored as PBKDF2-HMAC-SHA256 hashes. Both use the demonstration password `northwater`.
- **Accounts.** Each customer has a checking account and a savings account. Account numbers and the routing number are labels in this demo. They are not wired to a real network.
- **Transactions.** Every posted amount, with the description, category, memo, and the balance after that line. The statement is this list. Searching it treats `%` and `_` as ordinary characters.
- **Transfers.** The record of a completed move or bill payment: confirmation number, a one-time nonce, the accounts involved, and the amount. Replaying the same nonce returns the original confirmation and does not move the money again.
- **Seed marker.** A version key. When it does not match the current seed, the file is rebuilt from the demonstration history.

The seed is a few months of payroll, rent, groceries, and transfers, plus monthly savings interest at 3.80% APY. Moves between a customer’s own accounts cancel out. The October transfer between Avery and Jordan cancels out across the two customers.

## How a request moves

Reading is direct. The overview and the statement ask the ledger for accounts, recent lines, and this month’s totals. Transfers between a customer’s own accounts are left out of the overview month totals and included on the account statement.

Moving money takes three steps, and the money does not move until the last one:

1. **Prepare.** The customer submits the form. The application checks the account, the destination, and the amount, then stores a pending transfer in the session. The review page describes it. Opening the form again clears that pending transfer.
2. **Confirm.** The customer posts the review form. The application checks the form token and that the nonce still matches the pending transfer.
3. **Post.** Inside one database transaction, the ledger debits the source, credits the destination when there is one, writes the statement lines, and stores the transfer. Then it commits. If the nonce was already used, it rolls back and returns the original confirmation.

A bill payment debits the customer and records the payee’s name. It does not credit another customer. A move between the customer’s own accounts is labeled an internal transfer. A move to the other customer is labeled a transfer and appears on both statements.

The pending transfer lives only in the session. The ledger is unchanged until confirm succeeds.

## Boundaries

The process binds to this machine. There is no separate API service, message queue, identity provider, or payment gateway. Sign-in is a cookie session that lasts up to eight hours. Each form carries a token that must match the session.

The two demonstration customers can see their own accounts and a receipt for a transfer they sent or received. They cannot open each other’s statements.
