"""The HTTP client: read and create only, a write guard for dry runs, exact money, pagination."""

from __future__ import annotations

import unittest
from decimal import Decimal

from catalogue_loader.client import (
    LOGIN_PATH,
    ApiClient,
    HttpRequest,
    HttpResponse,
    Transport,
    decode_body,
    encode_body,
    error_code,
)
from catalogue_loader.errors import ApiError, AuthError, ProtocolError, WriteBlockedError

from .fakes import ADMIN_TOKEN, IDENTIFIER, PASSWORD, FakeApi
from .fixtures import NOW


class Recorder:
    """Wraps a transport and keeps the requests, so headers and bodies can be inspected."""

    def __init__(self, inner: FakeApi) -> None:
        self.inner = inner
        self.requests: list[HttpRequest] = []

    def __call__(self, request: HttpRequest) -> HttpResponse:
        self.requests.append(request)
        return self.inner.transport(request)


class NoUpdateSurfaceTest(unittest.TestCase):
    def test_the_client_has_no_update_delete_or_deactivate_operation(self) -> None:
        forbidden = ("update", "patch", "put", "delete", "remove", "close", "deactivate", "disable", "archive")
        names = [name for name in dir(ApiClient) if not name.startswith("_")]
        offenders = [n for n in names if any(word in n.lower() for word in forbidden)]
        self.assertEqual([], offenders)
        self.assertEqual(
            ["create_category", "create_product", "create_slot", "create_zone", "list_categories",
             "list_products", "list_slots", "list_zones", "login"],
            sorted(names),
        )


class WriteGuardTest(unittest.TestCase):
    def test_a_read_only_client_refuses_every_create(self) -> None:
        fake = FakeApi(NOW)
        client = ApiClient("https://api.example.test", fake.transport, token=ADMIN_TOKEN)
        for create in (client.create_category, client.create_product, client.create_zone, client.create_slot):
            with self.assertRaises(WriteBlockedError):
                create({})
        self.assertEqual([], fake.calls)  # refused before anything was sent

    def test_a_read_only_client_can_read_and_log_in(self) -> None:
        fake = FakeApi(NOW)
        client = ApiClient("https://api.example.test", fake.transport)
        self.assertEqual(ADMIN_TOKEN, client.login(IDENTIFIER, PASSWORD))
        self.assertEqual([], client.list_categories())
        self.assertEqual([("POST", LOGIN_PATH), ("GET", "/api/v1/admin/categories")], fake.calls)

    def test_a_writing_client_can_create(self) -> None:
        fake = FakeApi(NOW)
        client = ApiClient("https://api.example.test", fake.transport, token=ADMIN_TOKEN, allow_writes=True)
        created = client.create_category({"name": "Example", "slug": "example"})
        self.assertEqual("example", created["slug"])


class WireFormatTest(unittest.TestCase):
    def test_money_is_written_as_a_json_number(self) -> None:
        self.assertEqual(b'{"basePrice":123.45}', encode_body({"basePrice": Decimal("123.45")}))
        self.assertEqual(b'{"fee":11.0}', encode_body({"fee": Decimal("11.00")}))

    def test_every_valid_amount_round_trips_exactly(self) -> None:
        for text in ("0.00", "0.01", "0.10", "1200.00", "123.45", "99999.99", "9999999999.99", "5000000.07"):
            with self.subTest(text=text):
                sent = decode_body(encode_body({"x": Decimal(text)}))["x"]
                self.assertEqual(Decimal(text), sent)

    def test_responses_keep_decimals_exact(self) -> None:
        value = decode_body(b'{"basePrice": 123.45, "n": 3}')
        self.assertIsInstance(value["basePrice"], Decimal)
        self.assertEqual(Decimal("123.45"), value["basePrice"])
        self.assertEqual(3, value["n"])

    def test_error_codes_come_from_the_api_and_nothing_else(self) -> None:
        self.assertEqual("SLUG_TAKEN", error_code(b'{"code": "SLUG_TAKEN", "detail": "secret thing"}', 409))
        self.assertEqual("HTTP_409", error_code(b'{"code": "has spaces and detail"}', 409))
        self.assertEqual("HTTP_500", error_code(b"<html>boom</html>", 500))
        self.assertEqual("HTTP_502", error_code(b"[1, 2]", 502))

    def test_a_body_that_is_not_json_is_a_protocol_error(self) -> None:
        with self.assertRaises(ProtocolError):
            decode_body(b"not json")


class RequestShapeTest(unittest.TestCase):
    def test_the_token_is_sent_only_to_admin_endpoints_never_in_the_login_call(self) -> None:
        recorder = Recorder(FakeApi(NOW))
        client = ApiClient("https://api.example.test", recorder)
        client.login(IDENTIFIER, PASSWORD)
        client.list_zones()
        login, read = recorder.requests
        self.assertNotIn("Authorization", login.headers)
        self.assertEqual(f"Bearer {ADMIN_TOKEN}", read.headers["Authorization"])

    def test_a_trailing_slash_on_the_base_url_is_harmless(self) -> None:
        recorder = Recorder(FakeApi(NOW))
        ApiClient("https://api.example.test/", recorder, token=ADMIN_TOKEN).list_zones()
        self.assertEqual("https://api.example.test/api/v1/admin/delivery-zones", recorder.requests[0].url)

    def test_a_failed_login_is_an_auth_error_without_the_answer(self) -> None:
        client = ApiClient("https://api.example.test", FakeApi(NOW).transport)
        with self.assertRaises(AuthError) as caught:
            client.login(IDENTIFIER, "wrong-password-SENTINEL")
        self.assertNotIn("wrong-password-SENTINEL", str(caught.exception))

    def test_other_errors_carry_status_and_code_only(self) -> None:
        fake = FakeApi(NOW)
        fake.fail("GET", "/api/v1/admin/categories", 500, "INTERNAL_ERROR")
        client = ApiClient("https://api.example.test", fake.transport, token=ADMIN_TOKEN)
        with self.assertRaises(ApiError) as caught:
            client.list_categories()
        self.assertEqual((500, "INTERNAL_ERROR"), (caught.exception.status, caught.exception.code))


class PaginationTest(unittest.TestCase):
    def _fake_with_products(self, count: int) -> FakeApi:
        fake = FakeApi(NOW)
        category = fake.add_category("c", "C")
        for i in range(count):
            fake.add_product(f"p-{i}", f"P {i}", category, "1.00")
        return fake

    def test_every_page_is_read(self) -> None:
        for count, pages in ((0, 1), (1, 1), (100, 1), (101, 2), (250, 3), (300, 3)):
            with self.subTest(count=count):
                recorder = Recorder(self._fake_with_products(count))
                items = ApiClient("https://api.example.test", recorder, token=ADMIN_TOKEN).list_products()
                self.assertEqual(count, len(items))
                self.assertEqual(pages, len(recorder.requests))

    def test_pages_are_requested_at_the_maximum_size(self) -> None:
        recorder = Recorder(self._fake_with_products(3))
        ApiClient("https://api.example.test", recorder, token=ADMIN_TOKEN).list_products()
        self.assertIn("size=100", recorder.requests[0].url)

    def test_a_record_seen_on_two_pages_counts_once(self) -> None:
        fake = self._fake_with_products(150)
        first_page = fake.transport

        def shifting(request: HttpRequest) -> HttpResponse:
            response = first_page(request)
            if "page=1" in request.url:  # page 1 repeats the last record of page 0
                data = decode_body(response.body)
                data["content"].insert(0, list(fake.products.values())[99])
                return HttpResponse(200, encode_body(data))
            return response

        items = ApiClient("https://api.example.test", shifting, token=ADMIN_TOKEN).list_products()
        self.assertEqual(150, len(items))

    def test_a_malformed_page_is_a_protocol_error(self) -> None:
        def answering(body: bytes) -> Transport:
            def transport(request: HttpRequest) -> HttpResponse:
                return HttpResponse(200, body)

            return transport

        for body in (b"[]", b'{"content": 1, "totalElements": 1}', b'{"content": [], "totalElements": "x"}'):
            with self.subTest(body=body):
                client = ApiClient("https://api.example.test", answering(body), token="t")
                with self.assertRaises(ProtocolError):
                    client.list_products()

    def test_an_endless_listing_is_stopped(self) -> None:
        full = decode_body(encode_body({"content": [{"id": "x"}], "totalElements": 10**9}))
        counter = {"n": 0}

        def endless(request: HttpRequest) -> HttpResponse:
            counter["n"] += 1
            full["content"] = [{"id": f"id-{counter['n']}"}]
            return HttpResponse(200, encode_body(full))

        with self.assertRaises(ProtocolError):
            ApiClient("https://api.example.test", endless, token="t").list_products()


if __name__ == "__main__":
    unittest.main()
