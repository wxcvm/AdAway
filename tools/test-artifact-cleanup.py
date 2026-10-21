"""Exercise the actual selector, including mixed evidence and bad API input."""
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("cleanup", Path(__file__).with_name("select-artifact-cleanup.py"))
cleanup = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cleanup)


class RetentionTest(unittest.TestCase):
    def test_ui_room_and_native_evidence_survives_new_apk_upload(self):
        records = [dict(id=i, name=name, created_at=f"2026-10-{day:02}T01:00:00Z")
                   for i, name, day in [(1, "ADBlock-release-signed", 18),
                                        (2, "android-rule-workflow-ui", 21),
                                        (3, "ADBlock-release-signed", 20),
                                        (4, "room-transaction-api35", 19),
                                        (5, "native-sanitizers", 18)]]
        self.assertEqual([1], cleanup.deletion_ids(dict(artifacts=records)))

    def test_no_release_artifacts_means_no_deletions(self):
        self.assertEqual([], cleanup.deletion_ids(dict(artifacts=[dict(id=1, name="android-rule-workflow-ui")])))
        self.assertEqual([], cleanup.deletion_ids(dict(artifacts=[])))

    def test_bad_api_data_cannot_become_delete_paths(self):
        for document in ({}, dict(artifacts="failure"), dict(artifacts=[None]),
                         dict(artifacts=[dict(name="ADBlock-release-signed", id="../anything", created_at="2026-10-21")]),
                         dict(artifacts=[dict(name="ADBlock-release-signed", id=1, created_at="not-a-time")])):
            with self.assertRaises(ValueError):
                cleanup.deletion_ids(document)


if __name__ == "__main__":
    unittest.main()
