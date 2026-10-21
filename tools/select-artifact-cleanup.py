"""Select only old release-APK artifacts; preserve UI/Room/native evidence."""
from datetime import datetime
import json
import sys


def deletion_ids(document, keep=1):
    if not isinstance(document, dict) or not isinstance(document.get("artifacts"), list) or keep < 1:
        raise ValueError("Invalid artifact listing or retention")
    releases = []
    for artifact in document["artifacts"]:
        if not isinstance(artifact, dict):
            raise ValueError("Invalid artifact record")
        if artifact.get("name") != "ADBlock-release-signed":
            continue
        identity, created = artifact.get("id"), artifact.get("created_at")
        if type(identity) is not int or identity < 1 or not isinstance(created, str):
            raise ValueError("Invalid release artifact identity")
        timestamp = datetime.fromisoformat(created.replace("Z", "+00:00"))
        if timestamp.tzinfo is None:
            raise ValueError("Artifact timestamp needs timezone")
        releases.append((timestamp, identity))
    releases.sort(reverse=True)
    return [identity for _, identity in releases[keep:]]


if __name__ == "__main__":
    print("\n".join(map(str, deletion_ids(json.load(sys.stdin)))))
