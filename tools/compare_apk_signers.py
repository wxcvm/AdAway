"""Fail-closed comparison of apksigner-verified APK signing certificates.

Accepts both pre-37 Build Tools "Signer #1 certificate ..." and 37+
"V2 Signer: certificate ..."/"V3.0 Signer: certificate ..." output.
Call ONLY after apksigner verify returned zero for both APK files.
No keystore material is accessed by this parser.
"""
import pathlib
import re
import sys

CERT = re.compile(
    r"^(?:Signer\s+(?:#\d+|\([^)]*\))\s+|V\d+(?:\.\d+)?\s+Signer:\s*)"
    r"certificate\s+SHA-256\s+digest:\s*([0-9a-fA-F]{64})\s*$"
)

def fingerprint(contents: str) -> str:
    digests = {match.group(1).lower() for line in contents.splitlines()
               if (match := CERT.fullmatch(line.strip()))}
    if len(digests) != 1:
        raise ValueError(
            "expected exactly one unique APK signer SHA-256 certificate; "
            f"found {len(digests)} (multiple signers or unknown apksigner format)"
        )
    return digests.pop()

def main() -> int:
    if len(sys.argv) != 3:
        print("usage: compare_apk_signers.py verified-production.txt verified-preview.txt",
              file=sys.stderr)
        return 2
    try:
        production = fingerprint(pathlib.Path(sys.argv[1]).read_text())
        preview = fingerprint(pathlib.Path(sys.argv[2]).read_text())
        if production != preview:
            raise ValueError("APK signer certificate mismatch: preview publication refused")
        print("PASS: production and preview share APK signing certificate SHA-256", preview)
        return 0
    except (OSError, ValueError) as exc:
        print("ERROR:", exc, file=sys.stderr)
        return 1

if __name__ == "__main__":
    raise SystemExit(main())
