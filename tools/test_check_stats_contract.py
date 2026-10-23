import unittest
from check_stats_contract import fields


class StatsContractExtractionTest(unittest.TestCase):
    def test_json_fields_and_short_names_without_ternary_values(self):
        source = r'''
        reply("{\"family\":%u,\"ts\":%u,\"h\":[]}", family, timestamp);
        log("%s", ready ? "listening" : "unavailable");
        log("%s", loaded ? "loaded" : "true");
        '''
        self.assertEqual({'family', 'ts', 'h'}, fields(source))


if __name__ == '__main__':
    unittest.main()
