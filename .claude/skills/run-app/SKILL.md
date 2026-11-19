---
name: run-app
description: Launch the Baked & Browned API (dev profile, throwaway Testcontainers PostgreSQL) and the Angular web app on Windows, seed a product and delivery slot, and drive the online payment flow in Chrome through the fake gateway's dev page. Use to run the app, take screenshots, or check a change in the real app.
---

# Run the app and drive it

Verified on Windows 11 with Git Bash, Docker Desktop, Node 24, JDK 25 and installed Google Chrome.

The online payment scenarios need the fake gateway's dev page (`/dev/fake-gateway`, from the
`web-dev-payment-simulator` branch). On a branch without it, steps 1 and 2 still work, but checkout
lands on "Page not found" after placing an online order.

## 1. Start both servers (background)

Ports 8080 and 4200 must be free. `S` is any scratch folder for logs.

```bash
cd backend && ./mvnw spring-boot:test-run -Dspring-boot.run.arguments="--spring.profiles.active=dev" > "$S/api.log" 2>&1   # background
cd frontend && npm start > "$S/web.log" 2>&1                                                                             # background
```

- `spring-boot:test-run` starts a throwaway PostgreSQL in Docker and a throwaway JWT key. Without
  `--spring.profiles.active=dev` there is **no fake gateway**, so checkout offers no online payment.
- The API is ready when `api.log` contains `Started BakedAndBrownedApplication` (about 30 s). The web
  app proxies `/api` to :8080 (`frontend/proxy.conf.json`).

## 2. Seed data

The database starts empty apart from delivery zones. Load one active product with stock, the
DHANMONDI zone (৳60) and a slot two days ahead, 10:00–12:00 Dhaka time:

```bash
DB=$(docker ps --filter ancestor=postgres:17.12-alpine --format '{{.Names}}' | head -1)
docker exec -i "$DB" psql -v ON_ERROR_STOP=1 -U test -d test < .claude/skills/run-app/seed.sql
curl -s http://localhost:4200/api/v1/products | head -c 200   # should list "Fudge Brownie Box"
```

Check that `$DB` is this run's container if other Testcontainers runs are active (`docker ps`).
Credentials are always `test`/`test`/`test`.

## 3. Drive it in Chrome

The repo has no browser driver. Install `playwright-core` **outside the repo** and use the installed
Chrome (`channel: 'chrome'`, no browser download):

```bash
D="$S/driver"; mkdir -p "$D" && cd "$D" && npm init -y >/dev/null && npm i playwright-core@1.49.1
cp <repo>/.claude/skills/run-app/online-payment.mjs "$D/"     # must sit next to node_modules
SHOTS_DIR="$S/shots" node online-payment.mjs success          # or: fail | lost | all
```

Each run registers a new customer, adds the product, checks out with **Card or mobile wallet**,
and lands on `/dev/fake-gateway?session=…`:

| Scenario | Expect | Time |
|---|---|---|
| `success` | Order page **Confirmed** and **Paid online.** | ~10 s |
| `fail` | **Waiting for payment** with the retry message; retrying and paying confirms it | ~15 s |
| `lost` | Return page says "still being confirmed" after ~1 min; reconciliation confirms the order ~2 min later (`Reconciled payment … with fake: CONFIRMED` in `api.log`) | ~4 min |

**Look at the screenshots.** To check the database:
`docker exec "$DB" psql -U test -d test -c "SELECT order_number, status FROM orders"`.

Selectors that matter: register `#fullName #phone #email #password #confirmPassword`; login
`#identifier #password`; checkout `#address #area #date input[name=slot]`, the **Review total**
button and `data-testid="quote"`; the fake gateway's `pay`/`fail`/`cancel`/`notify-shop` test ids.

## 4. Stop

Stopping the background shells on Windows does **not** stop java/node. End them by port:

```powershell
Get-NetTCPConnection -LocalPort 8080,4200 -State Listen | Select-Object -Expand OwningProcess -Unique | ForEach-Object { Stop-Process -Id $_ -Force }
```

Testcontainers removes the database container shortly after the JVM exits, along with all seeded data.

## Gotchas

- Paths under `C:\Users\LAPTOP 2\…` contain a space: in Node use `fileURLToPath`/`path.join`, never
  `new URL(...).pathname`.
- The "Time left to pay" countdown rounds up, so it can read 15:00 right after placing.
- Open test payments live in the API's memory; restarting the API makes their pages say "not found".
- Staying signed in after the return from :8080 works locally because both are `localhost`; it
  says nothing about production cookie settings.
