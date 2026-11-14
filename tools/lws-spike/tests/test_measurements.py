import unittest
from benchmark import summarize


def samples(ratio=1.0):
    rows = []
    for logging in (False, True):
        for case in ("empty", "text", "image", "https-keep", "tls-cold"):
            for clients in (1, 16, 64):
                for index in range(5):
                    for backend in ("lws", "mongoose"):
                        scale = ratio if backend == "lws" else 1
                        rows.append(
                            dict(
                                logging=logging,
                                case=case,
                                clients=clients,
                                round=index,
                                backend=backend,
                                p95_ms=scale,
                                cpu_per_request_us=scale,
                                peak_rss=scale * 100,
                                requests=100,
                                errors=0,
                            )
                        )
    return rows


class MeasurementEvidenceTest(unittest.TestCase):
    def test_complete_stable_performance_is_not_automatic_migration_go(self):
        result = summarize(samples(1.1), False)
        self.assertEqual("PERFORMANCE-GATE-PASS", result["performance_verdict"])
        self.assertEqual("INCONCLUSIVE", result["migration_verdict"])

    def test_fifteen_percent_regression_is_not_hidden(self):
        self.assertEqual("NO-GO", summarize(samples(1.2), False)["performance_verdict"])

    def test_missing_round_or_duplicate_round_is_inconclusive(self):
        rows = samples()
        rows.pop()
        self.assertEqual("INCONCLUSIVE", summarize(rows, False)["performance_verdict"])
        rows = samples()
        rows[-1]["round"] = 0
        self.assertEqual("INCONCLUSIVE", summarize(rows, False)["performance_verdict"])

    def test_noise_zero_cpu_or_errors_are_not_passes(self):
        rows = samples()
        rows[0]["p95_ms"] = 2
        self.assertEqual("INCONCLUSIVE", summarize(rows, False)["performance_verdict"])
        rows = samples()
        rows[0]["cpu_per_request_us"] = 0
        self.assertEqual("INCONCLUSIVE", summarize(rows, False)["performance_verdict"])
        rows = samples()
        rows[0]["errors"] = 1
        self.assertEqual("INCONCLUSIVE", summarize(rows, False)["performance_verdict"])

    def test_quick_mode_never_accepts_migration(self):
        self.assertEqual(
            "INCONCLUSIVE", summarize(samples(), True)["performance_verdict"]
        )


if __name__ == "__main__":
    unittest.main()
