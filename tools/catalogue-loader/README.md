# Catalogue loader

Creates the product catalogue, delivery zones and delivery slots described by a JSON manifest,
through the existing admin API. Python 3.11 or newer, standard library only.

**It is create-only.** It adds what is missing and reports what differs. It never updates,
closes, deactivates or deletes a record. A run is a **dry run unless `--apply` is given**.

Nothing in this tool changes the backend, the schema, or any business rule.

## Why create-only

The admin API has no upsert, no delete and no version on its records:

- `PATCH /categories/{id}`, `/products/{id}` and `/delivery-zones/{id}` **replace the whole
  record** (the `ProductWrite` contract says so: "on update every field is replaced"). A client
  that sends fewer fields silently nulls the rest.
- Nothing carries an ETag or a version, so a read followed by a write cannot be made safe against
  someone editing the same record in between. Production data is edited by hand in the admin
  API, so that race is real.
- `PATCH /delivery-slots/{id}` changes only capacity and open/closed, and never the times.

A safe update would need a backend change (a version check), which is out of scope. So v1 reports
**DRIFT** for an existing record that differs from the manifest, and leaves it alone. `--update`
exists only to refuse, with that explanation.

## Quick start

Run from this directory (`tools/catalogue-loader`). Use `py` instead of `python` on Windows.

```sh
# 1. Check the manifest. No network, no credentials.
python -m catalogue_loader --manifest my-manifest.json --validate-only

# 2. Dry run (the default): reads the API, prints a plan, writes nothing.
python -m catalogue_loader --manifest my-manifest.json \
    --base-url https://staging.example.org --access-token-file ~/secrets/admin-token

# 3. Apply. To a remote host you must type the host again.
python -m catalogue_loader --manifest my-manifest.json \
    --base-url https://staging.example.org --access-token-file ~/secrets/admin-token \
    --apply --confirm-target staging.example.org
```

`examples/example-manifest.json` is **entirely fictional**. Copy it, replace every value with
the real business data, and keep the real manifest in a reviewed pull request. No real
catalogue, fee or delivery window ships with this tool.

## Credentials

The tool never takes a secret on the command line and never writes one to disk.

- **Preferred: an operator-supplied access token.** `--access-token-file FILE` holds the token
  of an ADMIN account. Access tokens are short-lived, so fetch one just before the run.
- **Or a password login:** `--identifier ID --password-file FILE`. The tool signs in once and
  keeps the token in memory only. The identifier (a phone or e-mail) is personal data and is
  never printed.
- Use **your own** ADMIN account, one operator per run. Every write is audited under that
  account. Do not create a shared or service ADMIN account for this.
- On POSIX a credential file readable by group or others (anything looser than `0600`) is
  refused.
- **Admin MFA** is a launch gate and does not exist yet. When it does, a password login will
  stop working for admins; use the access-token route.

## Manifest

JSON, `version` 1. Every section is optional, but at least one is required. Unknown fields are
rejected, as are duplicate keys. **Money is a string** (`"1200.00"`), never a JSON number.

| Section | Natural key | Fields (required in **bold**) |
|---|---|---|
| `categories` | `slug` | **slug**, **name**, sortOrder (0), active (true) |
| `products` | `slug` | **slug**, **name**, **category** (a category slug), **basePrice**, **minLeadHours**, **productionUnits**, description, cancellationCutoffHours (24), status (DRAFT), imageUrl (https only) |
| `zones` | `code` | **code** (`A-Z0-9_`), **name**, **active**, deliveryFee (required if active), minOrderAmount, sortOrder (0) |
| `slotTemplate` | start and end time | horizonDays (14, at most 62), **rules**: list of {**weekdays**, **windows**: list of {**start**, **end**, **capacityUnits**, active}} |

A product's `category` may be a category in the same manifest or one that already exists on the
server. Products are created in `DRAFT` unless the manifest says otherwise; publishing is a
deliberate manifest change.

Customization groups, disallowed combinations and ingredient mapping are **not** loaded (deferred).

### Delivery slots

A weekly template is expanded into concrete slots for a rolling window of `horizonDays` Dhaka
calendar days, **starting with today**. Weekdays are `mon` to `sun`. Times are Dhaka local time,
`HH:MM`, and a window must end after it starts on the same day (`23:59` is the latest end).

- **Time zone.** Asia/Dhaka is UTC+06:00 all year: the JDK 25 zone rules the backend runs on show no
  offset transition after 2010 and none scheduled (checked 2026-11-23). The tool uses that fixed offset, because the standard library has no
  zone database on Windows. If Bangladesh ever reintroduces daylight saving time,
  `catalogue_loader/slots.py` must change. Slots are sent as UTC instants.
- **Past slots.** A window that starts within the next 60 seconds, or earlier, is skipped and
  counted as `skippedStarted`. The server would reject it, and clocks can differ slightly.
- **Rerunning later** extends the window with new days. Slots already there are left alone.
- Existing slots in the window that the template does not mention are **not touched**; they are
  counted as `existingOutsideTemplate`.

## What a run decides, per record

| Op | Meaning | Written? |
|---|---|---|
| `CREATE` | No record has this key | Yes, with `--apply` |
| `NOOP` | A record has this key and already matches | No |
| `DRIFT` | A record has this key and differs (field names are listed) | **Never** |
| `INVALID` | Cannot be applied here: unknown category, or a name another record uses | Nothing is written at all |

Outcomes after `--apply`: `CREATED`, `EXISTS_MATCHING` (a concurrent writer or a lost answer
already created the same record), `CONFLICT` (it exists but differs), `FAILED`, `NOT_RUN`.

Order is categories, products, zones, slots. **The run stops at the first failure or conflict**
and marks the rest `NOT_RUN`.

## Exit codes

| Code | Meaning |
|---|---|
| 0 | Everything created or already in sync |
| 1 | Bad usage, invalid manifest or plan; **nothing was written** |
| 2 | A write failed or conflicted, or a read failed; the run stopped |
| 3 | Credentials rejected, not an ADMIN, or the token expired mid-run |
| 4 | Existing records differ from the manifest (left alone) |

## Partial failure and rerun

The tool keeps no local state. Every run starts by reading the server, so after any failure,
including a crash or a lost connection, **just run it again**: it creates only what is still
missing. If an answer is lost after a write committed, the tool re-reads and recognises its own
record instead of failing or duplicating it. The report says exactly what was created, what
was found, and what was not attempted.

Concurrency: two operators running at once is not supported, though a 409 from a competing
create is reconciled rather than trusted.

## Rollback

There is nothing to roll back automatically, because nothing is ever overwritten, and the
API has no delete. To undo a run, use the admin API deliberately: set loader-created products
back to `DRAFT` or `ARCHIVED`, and deactivate zones, slots and categories. Products created
by the loader start in `DRAFT` (unless the manifest says `ACTIVE`), so they are not visible to
customers until published.

## Reports and secrets

The JSON report goes to stdout, or to `--report-file`. It names records by key only (slug,
zone code, slot start and end) with their op and outcome. It never contains a name, price, fee,
description, phone number, e-mail, token or password, and the final text is redacted again
against the credentials in use. A server's error message is never shown: only its status and
machine code.

## Safeguards

- Dry run is the default. A dry-run client is read-only and raises on any write attempt.
- `--base-url` must be an `https://` origin with no credentials, path or query. Plain `http://`
  is accepted only for `localhost`, `127.0.0.1` or `::1`, and only with `--allow-insecure-local`.
- `--apply` to any non-local host also needs `--confirm-target <that host>`, typed exactly.
- Redirects are never followed, so a bearer token cannot be forwarded to another host.
- Certificates are verified (Python's default).

## Tests and checks

```sh
python -B -W error -m unittest discover -s tests -t .
python -m mypy --strict catalogue_loader tests        # mypy is a dev check, not a dependency
```

Tests run against an in-memory fake of the admin API (`tests/fakes.py`), which mirrors the real
backend's rules (unique keys and 409s, 422 for past slots, 100-item pages, Dhaka-day slot
queries) and can inject failures. A few tests use a real HTTP server on `127.0.0.1`. **They do not
prove behaviour against the real Spring backend**: run a dry run and an apply against a local
stack (the `run-app` skill) before the first production use.
