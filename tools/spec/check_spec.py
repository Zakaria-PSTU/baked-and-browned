"""Specification consistency checks for Baked & Browned (run in CI).

Checks:
  1. docs/api-contract.yaml is valid OpenAPI 3.0, refs resolve, operationIds unique,
     every /admin operation is bearer-secured and documents 403.
  2. Backward compatibility with the published v1.0.0 contract (tools/spec/baselines).
  3. Migration SQL in docs/database.md parses with the PostgreSQL grammar.
  4. backend/src/main/resources/db/migration/*.sql are byte-identical to the SQL
     blocks in docs/database.md (ADR-0015). Use --write-migrations to regenerate.
  5. Database enums / check lists match OpenAPI enums; API max lengths fit DB columns.

Usage:  python tools/spec/check_spec.py [--write-migrations]
"""
import argparse
import re
import sys
from pathlib import Path

import pglast
import yaml
from openapi_spec_validator import validate

ROOT = Path(__file__).resolve().parents[2]
DOCS = ROOT / "docs"
BASELINE = Path(__file__).resolve().parent / "baselines" / "api-contract-1.0.0.yaml"
MIGRATIONS_DIR = ROOT / "backend" / "src" / "main" / "resources" / "db" / "migration"
HTTP_METHODS = {"get", "post", "put", "patch", "delete"}

problems: list[str] = []


def ok(msg: str) -> None:
    print(f"  OK   {msg}")


def bad(msg: str) -> None:
    problems.append(msg)
    print(f"  FAIL {msg}")


def check(condition: bool, msg: str) -> None:
    (ok if condition else bad)(msg)


# ---------------------------------------------------------------- helpers
def refs(node):
    if isinstance(node, dict):
        for k, v in node.items():
            if k == "$ref":
                yield v
            else:
                yield from refs(v)
    elif isinstance(node, list):
        for v in node:
            yield from refs(v)


def resolves(spec, ref: str) -> bool:
    cur = spec
    for part in ref.lstrip("#/").split("/"):
        if not isinstance(cur, dict) or part not in cur:
            return False
        cur = cur[part]
    return True


def deref(spec, node):
    if isinstance(node, dict) and "$ref" in node:
        return spec["components"]["schemas"][node["$ref"].split("/")[-1]]
    return node


def flatten(spec, node):
    """(properties, required) for a schema, merging allOf."""
    node = deref(spec, node)
    props, req = dict(node.get("properties", {})), set(node.get("required", []))
    for part in node.get("allOf", []):
        p, r = flatten(spec, part)
        props.update(p)
        req |= r
    return props, req


def operations(spec):
    for path, item in spec["paths"].items():
        for method, op in item.items():
            if method in HTTP_METHODS:
                yield path, method, op


def migration_blocks(db_md: str) -> dict[str, str]:
    """Map migration file name -> SQL from each '## Vn ... (`Vn__name.sql`)' section."""
    blocks: dict[str, str] = {}
    for m in re.finditer(r"^## V\d+[^\n]*?`(V\d+__\w+\.sql)`[^\n]*\n(.*?)(?=^## |\Z)", db_md, re.M | re.S):
        sql = re.search(r"```sql\n(.*?)```", m.group(2), re.S)
        if sql:
            blocks[m.group(1)] = sql.group(1)
    return blocks


# ---------------------------------------------------------------- checks
def check_openapi(new, old) -> None:
    print("[1] OpenAPI validity")
    for name, spec in (("baseline 1.0.0", old), (f"current {new['info']['version']}", new)):
        try:
            validate(spec)
            ok(f"{name} is a valid OpenAPI document")
        except Exception as e:  # noqa: BLE001 - report any validator failure
            bad(f"{name} invalid: {str(e).splitlines()[0]}")
    unresolved = sorted({r for r in refs(new) if not resolves(new, r)})
    check(not unresolved, f"all $refs resolve {unresolved or ''}")
    ids = [op.get("operationId") for _, _, op in operations(new)]
    check(None not in ids, "every operation has an operationId")
    dups = sorted({i for i in ids if i and ids.count(i) > 1})
    check(not dups, f"{len(ids)} operationIds unique {dups or ''}")
    admin = [(p, m, op) for p, m, op in operations(new) if p.startswith("/admin")]
    check(all(op.get("security") == [{"bearerAuth": []}] for _, _, op in admin),
          "every /admin operation declares bearerAuth")
    check(all("403" in op["responses"] for _, _, op in admin), "every /admin operation documents 403")


def check_compatibility(new, old) -> None:
    print("[2] Backward compatibility with v1.0.0")
    before = len(problems)
    for path, method, op in operations(old):
        label = f"{method.upper()} {path}"
        nop = new["paths"].get(path, {}).get(method)
        if nop is None:
            bad(f"operation removed: {label}")
            continue
        if nop.get("operationId") != op.get("operationId"):
            bad(f"operationId changed: {label}")
        if nop.get("security") != op.get("security"):
            bad(f"security changed: {label}")
        for code in op["responses"]:
            if code not in nop["responses"]:
                bad(f"response {code} removed: {label}")
        for ctype, media in op.get("requestBody", {}).get("content", {}).items():
            nmedia = nop.get("requestBody", {}).get("content", {}).get(ctype)
            if nmedia is None:
                bad(f"request content-type {ctype} removed: {label}")
                continue
            added = flatten(new, nmedia["schema"])[1] - flatten(old, media["schema"])[1]
            if added:
                bad(f"new required request properties {sorted(added)}: {label}")
        for code, resp in op["responses"].items():
            oc = resp.get("content", {}).get("application/json")
            if not oc:
                continue
            nc = nop["responses"].get(code, {}).get("content", {}).get("application/json")
            if not nc:
                bad(f"response body removed {code}: {label}")
                continue
            os_, ns_ = oc["schema"], nc["schema"]
            if os_.get("type") == "array":
                os_, ns_ = os_["items"], ns_["items"]
            dropped = flatten(old, os_)[1] - flatten(new, ns_)[1]
            if dropped:
                bad(f"response required properties dropped {sorted(dropped)}: {code} {label}")
    for sname, schema in old["components"]["schemas"].items():
        if sname not in new["components"]["schemas"]:
            bad(f"schema removed: {sname}")
            continue
        oprops, _ = flatten(old, schema)
        nprops, _ = flatten(new, new["components"]["schemas"][sname])
        for pname, pdef in oprops.items():
            if pname not in nprops:
                bad(f"{sname}.{pname} removed")
                continue
            oe, ne = deref(old, pdef).get("enum"), deref(new, nprops[pname]).get("enum")
            if oe and (not ne or set(oe) - set(ne)):
                bad(f"{sname}.{pname} enum narrowed")
    if len(problems) == before:
        ok("no operation, response, schema, property or enum value removed; no new required request fields")


def check_sql(db_md: str, blocks: dict[str, str]) -> None:
    print("[3] Migration SQL syntax")
    expected = ["V1__init.sql", "V2__foundation_hardening.sql", "V3__delivery_zones.sql",
                "V4__ordering_policy.sql", "V5__payments_manual_refunds.sql", "V6__notifications.sql",
                "V7__refund_policy.sql"]
    check(list(blocks) == expected, f"documented migrations in order: {list(blocks)}")
    for name, sql in blocks.items():
        try:
            ok(f"{name}: {len(pglast.parse_sql(sql))} statements parse")
        except pglast.parser.ParseError as e:
            bad(f"{name}: {e}")
    check(not re.search(r"INSERT\s+INTO\s+users", "".join(blocks.values()), re.I),
          "no users or credentials seeded by migrations")


def check_migration_files(blocks: dict[str, str], write: bool) -> None:
    print("[4] Migration files match docs/database.md")
    if write:
        MIGRATIONS_DIR.mkdir(parents=True, exist_ok=True)
        for name, sql in blocks.items():
            (MIGRATIONS_DIR / name).write_bytes(sql.encode("utf-8"))
            print(f"  WROTE {name}")
    on_disk = sorted(p.name for p in MIGRATIONS_DIR.glob("*.sql")) if MIGRATIONS_DIR.exists() else []
    check(on_disk == sorted(blocks), f"migration directory holds exactly the documented files {on_disk}")
    for name, sql in blocks.items():
        path = MIGRATIONS_DIR / name
        if path.exists():
            check(path.read_bytes() == sql.encode("utf-8"),
                  f"{name} identical to doc (regenerate with --write-migrations)")


def check_enums_and_limits(new, sql: str) -> None:
    print("[5] Database / API enum and length alignment")
    s = new["components"]["schemas"]
    db_enums = {m.group(1): re.findall(r"'([A-Z_]+)'", m.group(2))
                for m in re.finditer(r"CREATE TYPE (\w+) AS ENUM \(([^)]*)\)", sql)}
    for m in re.finditer(r"ALTER TYPE (\w+) ADD VALUE '([A-Z_]+)'", sql):
        db_enums.setdefault(m.group(1), []).append(m.group(2))

    def api_enum(*path):
        node = s
        for part in path:
            node = deref(new, node[part])
        return node["enum"]

    pairs = {
        "user_role": ("User", "properties", "role"),
        "product_status": ("ProductStatus",),
        "order_status": ("OrderStatus",),
        "payment_status": ("PaymentStatus",),
        "payment_method": ("PaymentMethod",),
        "actor_type": ("ActorType",),
        "manual_submission_status": ("ManualSubmissionStatus",),
        "refund_status": ("RefundStatus",),
        "inventory_movement_type": ("InventoryMovement", "properties", "movementType"),
        "reconciliation_case_type": ("ReconciliationCase", "properties", "caseType"),
        "reconciliation_case_status": ("ReconciliationCase", "properties", "status"),
    }
    for db_name, path in pairs.items():
        check(set(db_enums.get(db_name, [])) == set(api_enum(*path)),
              f"{db_name} matches {'.'.join(path)}")
    for label, rx, path in (
        ("manual provider", r"provider IN \(([^)]*)\)", ("ManualPaymentProvider",)),
        ("selection_type", r"selection_type IN \(([^)]*)\)", ("CustomizationGroup", "properties", "selectionType")),
        ("reconciliation resolution", r"resolution IN \(([^)]*)\)", ("ReconciliationCase", "properties", "resolution")),
    ):
        m = re.search(rx, sql)
        check(bool(m) and set(re.findall(r"'([A-Z_]+)'", m.group(1))) == set(api_enum(*path)),
              f"{label} check list matches {'.'.join(path)}")

    paths = new["paths"]
    limits = [
        ("CategoryWrite.name", s["CategoryWrite"]["properties"]["name"]["maxLength"], r"categories \(.*?name VARCHAR\((\d+)\)"),
        ("OptionWrite.name", s["OptionWrite"]["properties"]["name"]["maxLength"], r"customization_options \(.*?name VARCHAR\((\d+)\)"),
        ("CustomizationGroupWrite.name", s["CustomizationGroupWrite"]["properties"]["name"]["maxLength"], r"customization_groups \(.*?name VARCHAR\((\d+)\)"),
        ("DeliveryZoneWrite.name", s["DeliveryZoneWrite"]["properties"]["name"]["maxLength"], r"delivery_zones \(.*?name VARCHAR\((\d+)\)"),
        ("IngredientCreate.name", s["IngredientCreate"]["properties"]["name"]["maxLength"], r"ingredients \(.*?name VARCHAR\((\d+)\)"),
        ("IngredientCreate.unit", s["IngredientCreate"]["properties"]["unit"]["maxLength"], r"ingredients \(.*?unit VARCHAR\((\d+)\)"),
        ("manual transactionReference",
         paths["/payments/{orderId}/manual-submission"]["post"]["requestBody"]["content"]["multipart/form-data"]["schema"]["properties"]["transactionReference"]["maxLength"],
         r"transaction_reference VARCHAR\((\d+)\)"),
        ("RefundCompletion.externalReference", s["RefundCompletionRequest"]["properties"]["externalReference"]["maxLength"], r"external_reference VARCHAR\((\d+)\)"),
        ("CartLine.customTexts value", s["CartLine"]["properties"]["customTexts"]["additionalProperties"]["maxLength"], r"text_value VARCHAR\((\d+)\)"),
        ("admin status reason",
         paths["/admin/orders/{orderId}/status"]["patch"]["requestBody"]["content"]["application/json"]["schema"]["properties"]["reason"]["maxLength"],
         r"order_status_history \(.*?reason VARCHAR\((\d+)\)"),
        ("inventory adjustment reason",
         paths["/admin/inventory/{ingredientId}"]["patch"]["requestBody"]["content"]["application/json"]["schema"]["properties"]["reason"]["maxLength"],
         r"inventory_movements \(.*?reason VARCHAR\((\d+)\)"),
    ]
    for label, api_max, rx in limits:
        m = re.search(rx, sql, re.S)
        check(bool(m) and api_max <= int(m.group(1)), f"{label}: API max {api_max} fits DB {m.group(1) if m else '?'}")
    zone_rx = "^[A-Z0-9_]{2,60}$"
    check(zone_rx in sql and s["DeliveryZoneWrite"]["properties"]["code"]["pattern"] == zone_rx,
          "delivery zone code pattern identical in API and DB")
    phone = s["RegisterRequest"]["properties"]["phone"]["pattern"]
    check(phone == r"^\+8801[3-9][0-9]{8}$" and phone in sql, "phone pattern identical in API and DB ck_phone")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--write-migrations", action="store_true",
                        help="regenerate backend migration files from docs/database.md")
    args = parser.parse_args()

    new = yaml.safe_load((DOCS / "api-contract.yaml").read_text(encoding="utf-8"))
    old = yaml.safe_load(BASELINE.read_text(encoding="utf-8"))
    db_md = (DOCS / "database.md").read_text(encoding="utf-8")
    blocks = migration_blocks(db_md)

    check_openapi(new, old)
    check_compatibility(new, old)
    check_sql(db_md, blocks)
    check_migration_files(blocks, args.write_migrations)
    check_enums_and_limits(new, "".join(blocks.values()))

    print(f"\nRESULT: {'PASS' if not problems else f'{len(problems)} problem(s)'}")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
