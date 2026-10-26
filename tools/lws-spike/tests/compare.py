"""Compare actual HTTP evidence from separately run, identical backends."""

import argparse
import json
import pathlib


def compare(left, right):
    def rows(data):
        events = [event for event in data["events"] if event["test"] == "http-family"]
        if len(events) != 2 or any(len(event["rows"]) != 5 for event in events):
            raise AssertionError("Incomplete response evidence")
        return {event["family"]: event["rows"] for event in events}

    expected = {"127.0.0.1", "::1"}
    a, b = rows(left), rows(right)
    if set(a) != expected or set(b) != expected or a != b:
        raise AssertionError(
            "HTTP status, type, declared/actual length, ETag or hash differs"
        )
    return {
        "passed": True,
        "compared_responses": sum(map(len, a.values())),
        "fields": ["status", "type", "declared", "length", "sha256", "etag"],
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("left")
    parser.add_argument("right")
    parser.add_argument("output")
    args = parser.parse_args()
    result = compare(
        json.loads(pathlib.Path(args.left).read_text()),
        json.loads(pathlib.Path(args.right).read_text()),
    )
    pathlib.Path(args.output).write_text(
        json.dumps(result, indent=2) + "\n", encoding="utf8"
    )
    print(json.dumps(result))
