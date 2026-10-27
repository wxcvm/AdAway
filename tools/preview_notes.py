"""Generate the one preview note used both as an attachment and release body."""
import argparse
import os
from pathlib import Path
import re

def render_notes(changes, version, env):
    if not changes.strip() or not changes.startswith("# Preview changes"):
        raise ValueError("Missing or empty preview changes summary")
    source = env["GITHUB_SHA"]
    run = env["GITHUB_RUN_ID"]
    repo = env["GITHUB_REPOSITORY"]
    if not re.fullmatch(r"[0-9a-f]{40}", source) or not run.isdecimal():
        raise ValueError("Invalid source or CI identity")
    if repo not in ("wxcvm/AdAway", "wxcvm/AdAway-Windows"):
        raise ValueError("Unexpected preview repository")
    if not re.fullmatch(r"[0-9A-Za-z.+-]+", version):
        raise ValueError("Invalid preview version")
    return (changes.rstrip() + "\n\n## Build identity\n\n"
        + "- Version: " + version + "\n"
        + "- Source: [" + source + "](https://github.com/" + repo + "/commit/" + source + ")\n"
        + "- CI: [run " + run + "](https://github.com/" + repo + "/actions/runs/" + run + ")\n")

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--version", required=True)
    parser.add_argument("--changes", type=Path, default=Path("docs/preview-changes.md"))
    parser.add_argument("--output", type=Path, default=Path("release-assets/PREVIEW-NOTES.md"))
    args = parser.parse_args()
    notes = render_notes(args.changes.read_text(encoding="utf-8"), args.version, os.environ)
    args.output.write_text(notes, encoding="utf-8")
