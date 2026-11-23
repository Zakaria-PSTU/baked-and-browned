"""The shipped example must stay valid, fictional, and loadable end to end."""

from __future__ import annotations

import io
import unittest
from pathlib import Path

from catalogue_loader import cli
from catalogue_loader.manifest import load_manifest

from .fixtures import LoaderTestCase

EXAMPLE = Path(__file__).resolve().parent.parent / "examples" / "example-manifest.json"


class ExampleManifestTest(LoaderTestCase):
    def test_the_example_validates(self) -> None:
        manifest = load_manifest(EXAMPLE)
        self.assertEqual(("example-cakes",), tuple(c.slug for c in manifest.categories))

    def test_every_name_and_code_in_the_example_is_marked_as_an_example(self) -> None:
        manifest = load_manifest(EXAMPLE)
        labels = [c.slug for c in manifest.categories] + [p.slug for p in manifest.products]
        labels += [z.code.lower() for z in manifest.zones]
        self.assertTrue(all(label.startswith("example") for label in labels), labels)

    def test_the_example_loads_into_an_empty_fake_server_and_converges(self) -> None:
        text = EXAMPLE.read_text(encoding="utf-8")
        path = self._write("copy.json", text)

        def run() -> int:
            return cli.main(
                ["--manifest", str(path), "--base-url", "http://127.0.0.1:1", "--allow-insecure-local",
                 "--apply", "--access-token-file", str(self.token_file)],
                transport=self.fake.transport, stdout=io.StringIO(), stderr=io.StringIO(),
            )

        self.assertEqual(0, run())
        self.assertEqual(0, run())
        self.assertEqual((1, 1, 2), (len(self.fake.categories), len(self.fake.products), len(self.fake.zones)))


if __name__ == "__main__":
    unittest.main()
