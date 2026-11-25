import copy
import unittest
from compare import compare


class DifferentialEvidenceTest(unittest.TestCase):
    def evidence(self):
        return {
            "events": [
                {
                    "test": "http-family",
                    "family": family,
                    "rows": [
                        {"status": code, "sha256": "fixture"}
                        for code in (204, 200, 200, 403, 404)
                    ],
                }
                for family in ("127.0.0.1", "::1")
            ]
        }

    def test_complete_differential(self):
        data = self.evidence()
        self.assertEqual(compare(data, copy.deepcopy(data))["compared_responses"], 10)

    def test_changed_response_is_not_equal(self):
        data = self.evidence()
        other = copy.deepcopy(data)
        other["events"][1]["rows"][0]["status"] = 200
        with self.assertRaises(AssertionError):
            compare(data, other)

    def test_two_identically_incomplete_inputs_do_not_pass(self):
        for data in (
            {"events": []},
            {"events": [{"test": "http-family", "family": "127.0.0.1", "rows": []}]},
        ):
            with self.assertRaises(AssertionError):
                compare(data, data)
