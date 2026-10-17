"""Validate proposed data shapes; these objects are never OS mutation authority."""
import copy
import json
from pathlib import Path
from jsonschema import Draft202012Validator

root = Path(__file__).resolve().parents[1]
schema = json.loads((root / "webserver/jni/traffic_core_contract.schema.json").read_text())
Draft202012Validator.check_schema(schema)
validator = Draft202012Validator(schema)
flow = {"family": 4, "protocol": 17, "sourceHex": "c0000201", "destinationHex": "c0000202",
        "sourcePort": 4000, "destinationPort": 53, "sessionGeneration": 1}
valid = [flow, {"action": "PASS", "reason": "UNCLASSIFIED"}, "UNKNOWN",
         {"family": 6, "protocol": 17, "wireHex": "60" + "00" * 39, "sessionGeneration": 1}]
for item in valid:
    validator.validate(item)
invalid = []
for key, value in [("family", 7), ("sourceHex", "c00002"), ("destinationPort", 0),
                   ("protocol", 1), ("sessionGeneration", 0), ("payloadLog", "private")]:
    item = copy.deepcopy(flow)
    item[key] = value
    invalid.append(item)
invalid += [{"action": "FORWARDED_SUCCESSFULLY", "reason": "UNCLASSIFIED"},
            {"family": 6, "protocol": 17, "wireHex": "60" + "00" * 19, "sessionGeneration": 1}]
for item in invalid:
    assert not validator.is_valid(item), item
print(f"Core schema: {len(valid)} valid and {len(invalid)} invalid shapes verified")
