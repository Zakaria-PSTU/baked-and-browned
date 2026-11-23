# Catalogue loader

Creates the product catalogue, delivery zones and delivery slots described by a JSON manifest,
through the existing admin API. Python 3.11 or newer, standard library only. The tests and
`mypy --strict` run in CI on Python 3.11 (Linux), and have also been run on Python 3.14 (Windows).

**It is create-only.** It adds what is missing and reports what differs. It never updates,
closes, deactivates or deletes a record. A run is a **dry run unless `--apply` is given**.

**The six delivery zones that ship with the database are configured by hand**, not by this tool:
see "Shipped delivery zones" below.

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

## Shipped delivery zones (configure by hand)

The database ships with six delivery zones: `DHANMONDI`, `MOHAMMADPUR`, `MIRPUR`, `UTTARA`,
`GULSHAN` and `BANANI`. Migration V3 creates them as reference examples: **inactive, with no fee
and no minimum**. They exist in every environment, their `code` and `name` are unique, and a
customer cannot choose a zone until it has a fee and is active.

This tool cannot configure them. It is create-only, so a manifest entry for one of these codes is
reported as `DRIFT` (`deliveryFee`, `active`), exits 4 and changes nothing. Set them up by hand,
once per environment, through the admin API. `BASE` is the API origin, for example
`https://staging.example.org`.

1. Put a fresh ADMIN access token in a header file, `headers.txt`, with one line
   `Authorization: Bearer <access token>`. Make it readable only by you, and delete it afterwards.
   A file keeps the token out of the command line and the shell history.
2. **Read the current state.** List the zones and note the zone's `id`. The zone must still be in
   its seed state (no fee, no minimum, `active` false). If it is not, **stop** and find out who
   changed it before going further.
   ```sh
   curl -sS -H @headers.txt "$BASE/api/v1/admin/delivery-zones"
   ```
3. **Re-read the same zone immediately before the PATCH,** and compare every field with step 2.
   **Stop** if any value has changed. Re-reading narrows the window for a concurrent change; it
   does not remove it (see "Concurrent changes" below).
4. **PATCH the complete record.** Send all six fields, and take `code`, `name` and `sortOrder`
   from the re-read so that they are preserved, unless a change to them has been approved. A
   field left out is reset, not kept (see the warnings below):
   ```sh
   curl -sS -X PATCH "$BASE/api/v1/admin/delivery-zones/<ZONE_ID>" \
     -H @headers.txt -H "Content-Type: application/json" \
     -d '{"code":"<CODE>","name":"<NAME>","deliveryFee":<FEE>,"minOrderAmount":<MINIMUM or null>,"active":true,"sortOrder":<ORDER>}'
   ```
5. **Read the zone again** from the admin list and compare each saved value with what you sent.
   **Stop** if any value differs.
   ```sh
   curl -sS -H @headers.txt "$BASE/api/v1/admin/delivery-zones"
   ```
6. **Check customer visibility.** This list needs no token. A zone appears here only when it is
   active and has a fee, so activation is what makes it available to customers at checkout:
   ```sh
   curl -sS "$BASE/api/v1/delivery-zones"
   ```
7. **Check the audit trail.** Each change that alters a zone writes one `DELIVERY_ZONE_UPDATED`
   event. Confirm there is one event per intended change and none for anything else.
8. **Run the loader dry run** against a manifest that holds the approved final values. Every zone
   must report `NOOP`. Treat any `DRIFT` as a stop condition: a value differs from the approved
   state, and the cause must be found before anything else is written.

Two behaviours of the API that will catch you out (both rehearsed against the real backend):

- **`PATCH` replaces the whole record.** A field you leave out is reset, not kept: an omitted
  `minOrderAmount` becomes `null` and an omitted `sortOrder` becomes `0`. Always send all six
  fields.
- **`active: true` needs a `deliveryFee`.** Without one the API answers 422 `ZONE_FEE_REQUIRED` and
  leaves the zone unchanged.

**Concurrent changes.** The `delivery_zones` table has **no version column**, and the API has no
ETag, so the server cannot detect a change made by someone else between a read and a write. A
change made between your re-read and your PATCH is overwritten without an error. The re-read in
step 3 narrows that window but does not remove it. Work as one operator, one zone at a time, and
use the audit check in step 7 to find any change you did not make.

Every change that alters a zone is audited as `DELIVERY_ZONE_UPDATED`, and the backend stores a fee
snapshot on each order, so changing a fee does not alter orders already placed. The fees and minimums are the shop's decision, so this README
deliberately contains none. Do not add a second zone for an area that already has a shipped one:
the name `Dhanmondi` is taken, but a near-duplicate such as `Dhanmondi Area` would be accepted, and
customers could be shown both. The steps were rehearsed on a fictional zone with curl 8.7.1 on
Windows. Quote the JSON the way your shell requires.

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
- On POSIX systems (verified on Linux) a credential file that group or others can read is refused
  before any request is sent. That is any mode looser than `0600`, including the common default
  `0644`: run `chmod 600 FILE`. It applies to token files and password files alike. Windows file
  permissions are not inspected, so keep the file in a folder only you can read.
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
- TLS certificates and host names are always verified, and there is no option to turn that off (see
  "TLS and private certificate authorities").

## TLS and private certificate authorities

The tool always verifies the server's certificate chain **and** host name, and there is no option
to switch that off. A connection that fails verification is dropped during the handshake, so **no
request is sent and the bearer token is never transmitted**.

- **A public CA** (the usual case for a production domain): nothing to do.
- **A private CA** (for example a staging proxy with an internal certificate): trust it for this
  one process by setting `SSL_CERT_FILE` to a PEM file that holds the CA certificate. Do not add it
  to the operating system's certificate store for this.
  ```sh
  export SSL_CERT_FILE=/path/to/staging-ca.pem
  python -m catalogue_loader --manifest m.json --base-url https://staging.example.org --access-token-file token
  ```
  In PowerShell, set `$env:SSL_CERT_FILE = 'C:\path\staging-ca.pem'` first, then run the same
  command.

What was verified (the automated tests use a local HTTPS server; a manual run used the real backend
behind a TLS-terminating forwarder): a certificate issued by the CA named in `SSL_CERT_FILE` is
accepted. The same server without it, a certificate from a different CA, and a certificate for a
different host name are all refused with exit 2 and zero requests sent. The **real-backend** TLS run
was on Windows (Python 3.14). The automated TLS tests (a local HTTPS server) run on Windows and, in
CI, on Linux (Python 3.11). It was **not** tested on macOS, where some Python builds bring their
own certificate bundle and may behave differently.

Platform differences to know about:

- Which authorities are trusted by default is decided by the Python build and the operating
  system, not by this tool.
- The credential-file permission check runs only on POSIX systems.
- Asia/Dhaka is a fixed UTC+06:00 offset, because the standard library has no time-zone database on
  Windows.

## Tests and checks

From this directory:

```sh
python -m pip install -r requirements-dev.txt   # mypy only: a development check, not a dependency of the tool
python -B -W error -m unittest discover -s tests -t .
python -m mypy --strict --python-version 3.11 catalogue_loader tests
```

- On Linux, and in CI, **no test may be skipped**, and the CI job fails if one is. On Windows the
  POSIX-only permission tests skip.
- The TLS tests make throwaway certificates with the `openssl` command line tool (the CI job checks
  that it is present). Locally they are skipped if it is missing.
- A test fixture that stands for a credential must be created with `secret=True`, which makes it
  mode `0600`. The loader refuses a credential file that others can read on POSIX, so a fixture with
  default permissions makes many tests fail on Linux while passing on Windows.
- CI runs all of this as the "Catalogue loader (Python 3.11)" job.

Most tests run against an in-memory fake of the admin API (`tests/fakes.py`), which mirrors the real
backend's rules (unique keys and 409s, 422 for past slots, 100-item pages, Dhaka-day slot queries)
and can inject failures. A fake can share its author's misreadings, so before first use on a new
environment do a dry run against it, and before relying on a change to the loader's behaviour run
it against a local stack (the `run-app` skill).
