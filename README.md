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

## Build and run

Both programs listen only on this machine. Sign in as Avery Chen (`avery@northwater.demo`) or Jordan Hale (`jordan@northwater.demo`). The password is `northwater`. `PORT` changes the listen port. `NORTHWATER_DB` changes the ledger file. The first start creates that file and loads the demonstration books.

### Java

Use JDK 25, including `javac`, and Maven 3.9 or newer. Point `JAVA_HOME` at that JDK.

From `northwater-java`:

```bash
mvn -B package
java --enable-native-access=ALL-UNNAMED -jar target/northwater-1.0.0.jar
```

`package` compiles the sources, runs the tests, and builds one shaded jar at `target/northwater-1.0.0.jar`. The jar’s main class is `com.northwater.Northwater`. Open http://127.0.0.1:8080.

`--enable-native-access=ALL-UNNAMED` lets the SQLite driver load its native library on JDK 25. The same flag is set for the tests.

`mvn -B verify` runs the package build and then Checkstyle, PMD, and SpotBugs. That is what the Java CI job runs. `package` also writes a CycloneDX SBOM to `target/bom.json`.

The CI job scans that SBOM with Grype 0.121.0 and uploads `target/bom.json` and `target/grype.json`. Grype records vulnerabilities; it does not fail the build. To scan locally after the package build:

```bash
grype sbom:target/bom.json -o table
grype sbom:target/bom.json -o json --file target/grype.json
```

### Python

Use Python 3.13 or newer. From `northwater`:

```bash
python -m venv .venv
.venv/bin/pip install -r requirements.txt
.venv/bin/python app.py
```

Open http://127.0.0.1:8000. Lint and tests use the extra tools in `requirements-dev.txt`:

```bash
.venv/bin/pip install -r requirements-dev.txt
.venv/bin/ruff check .
.venv/bin/ruff format --check .
.venv/bin/mypy
.venv/bin/bandit -c pyproject.toml -r app.py test_bank.py
.venv/bin/python test_bank.py
```

The CI job writes a CycloneDX SBOM of the installed runtime requirements to `reports/sbom.cdx.json`, scans it with Grype, and uploads that SBOM with `reports/grype.json`. To do the same locally, after installing `requirements.txt`:

```bash
mkdir -p reports
.venv/bin/pip freeze > reports/requirements.lock
.venv/bin/pip install "cyclonedx-bom==7.5.0"
.venv/bin/cyclonedx-py requirements reports/requirements.lock --output-file reports/sbom.cdx.json --output-reproducible
grype sbom:reports/sbom.cdx.json -o table
grype sbom:reports/sbom.cdx.json -o json --file reports/grype.json
```

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
