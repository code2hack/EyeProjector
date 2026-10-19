import importlib.util
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("save_credential", Path(__file__).parents[1] / "scripts/save-credential.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class CredentialTests(unittest.TestCase):
    def test_private_external_file_and_existing_value_preserved(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            repo = root / "repo"
            repo.mkdir()
            destination = root / "private/credential"
            self.assertEqual(module.save_credential(destination, repo, "fixture-only"), str(destination))
            self.assertEqual(destination.stat().st_mode & 0o777, 0o600)
            self.assertEqual(destination.read_text(), "fixture-only")
            with self.assertRaises(FileExistsError):
                module.save_credential(destination, repo, "replacement-fixture")
            self.assertEqual(destination.read_text(), "fixture-only")
            self.assertEqual(list(destination.parent.iterdir()), [destination])
            with self.assertRaises(ValueError):
                module.save_credential(repo / "credential", repo, "fixture-only")

    def test_double_entry_matches_and_prints_metadata_only(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            repo = root / "repo"
            repo.mkdir()
            destination = root / "private/credential"
            output = io.StringIO()
            argv = ["capture", "--file", str(destination), "--repo-root", str(repo), "--owner-request", "fixture-request"]
            with patch.object(sys, "argv", argv), patch.object(sys.stdin, "isatty", return_value=True), patch.object(sys.stderr, "isatty", return_value=True), patch.object(module.getpass, "getpass", side_effect=["fixture-only", "fixture-only"]) as reader, patch.object(sys, "stdout", output):
                module.main()
            self.assertEqual(reader.call_count, 2)
            self.assertNotIn("fixture-only", output.getvalue())
            self.assertEqual(json.loads(output.getvalue())["path"], str(destination))

    def test_mismatch_or_non_tty_leaves_no_file(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            destination = root / "private/credential"
            argv = ["capture", "--file", str(destination), "--repo-root", str(root / "repo"), "--owner-request", "fixture-request"]
            with patch.object(sys, "argv", argv), patch.object(sys.stdin, "isatty", return_value=True), patch.object(sys.stderr, "isatty", return_value=True), patch.object(module.getpass, "getpass", side_effect=["first-fixture", "different-fixture"]):
                with self.assertRaises(ValueError):
                    module.main()
            self.assertFalse(destination.exists())
            with patch.object(sys, "argv", argv), patch.object(sys.stdin, "isatty", return_value=False), patch.object(module.getpass, "getpass") as reader:
                with self.assertRaises(ValueError):
                    module.main()
                reader.assert_not_called()
            self.assertFalse(destination.exists())


if __name__ == "__main__":
    unittest.main()
