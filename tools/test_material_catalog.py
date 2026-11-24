"""Exercise alias resolution and invalid build inputs before APK packaging."""
import json
import tempfile
import unittest
from pathlib import Path

from build_material_catalog import build


class MaterialCatalogTest(unittest.TestCase):
    def test_alias_inherits_identity_fields_without_spectral_arrays(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "base.json").write_text(json.dumps({"info": {
                "stock": "base", "name": "Base", "stage": "filming", "type": "positive",
                "channel_model": "bw", "target_print": "paper", "antihalation": "weak",
            }, "curves": [1, 2, 3]}), encoding="utf-8")
            (root / "alias.json").write_text(json.dumps({"derived_from": "base", "info": {"name": "Alias"}}), encoding="utf-8")
            result = build(root)
            alias = next(x for x in result["materials"] if x["id"] == "alias")
            self.assertEqual(alias, {"id": "alias", "name": "Alias", "stage": "filming", "type": "positive",
                "target_print": "paper", "monochrome": True, "antihalation": "weak", "derived_from": "base"})
            self.assertNotIn("curves", json.dumps(result))
            self.assertEqual(result, build(root))

    def test_empty_or_nested_aliases_fail_build(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            with self.assertRaises(ValueError):
                build(root)
            for name, source in {
                "base": {"info": {"stock": "base", "stage": "filming", "type": "negative"}},
                "alias": {"derived_from": "base", "info": {"name": "Alias"}},
                "nested": {"derived_from": "alias", "info": {"name": "Nested"}},
            }.items():
                (root / f"{name}.json").write_text(json.dumps(source), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "Nested"):
                build(root)

    def test_unknown_type_fails_instead_of_silently_using_negative(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "future.json").write_text(json.dumps({"info": {
                "stock": "future", "stage": "filming", "type": "unknown",
            }}), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "Invalid material type"):
                build(root)


if __name__ == "__main__":
    unittest.main()
