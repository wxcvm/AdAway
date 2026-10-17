import unittest
from compare_apk_signers import fingerprint

DIGEST = "a1" * 32
OTHER = "b2" * 32

class ApksignerCompatTest(unittest.TestCase):
    def test_classic_build_tools(self):
        self.assertEqual(fingerprint(
            "Signer #1 certificate SHA-256 digest: " + DIGEST + "\n"
            "Signer #1 public key SHA-256 digest: " + OTHER + "\n"
        ), DIGEST)

    def test_build_tools_37_v2_and_v3_same_certificate(self):
        self.assertEqual(fingerprint(
            "V2 Signer: certificate SHA-256 digest: " + DIGEST + "\n"
            "V3.0 Signer: certificate SHA-256 digest: " + DIGEST + "\n"
        ), DIGEST)

    def test_versioned_legacy_signer(self):
        self.assertEqual(fingerprint(
            "Signer (minSdkVersion=24, maxSdkVersion=32) certificate SHA-256 digest: " + DIGEST
        ), DIGEST)

    def test_reject_mismatched_signing_schemes(self):
        with self.assertRaises(ValueError):
            fingerprint("V2 Signer: certificate SHA-256 digest: " + DIGEST + "\n"
                        "V3 Signer: certificate SHA-256 digest: " + OTHER + "\n")

    def test_reject_stamp_public_key_or_missing_signer(self):
        for value in (
            "",
            "V2 Signer: public key SHA-256 digest: " + DIGEST,
            "Source Stamp Signer: certificate SHA-256 digest: " + DIGEST,
            "Certificate SHA-256 digest: " + DIGEST,
            "V2 Signer: certificate SHA-256 digest: bad",
        ):
            with self.subTest(value=value), self.assertRaises(ValueError):
                fingerprint(value)

if __name__ == "__main__":
    unittest.main()
