import unittest

import verify_image_only_certification_p0 as verifier


class ImageOnlyP0VerifierTest(unittest.TestCase):
    def test_length_prefixed_hash_is_order_and_boundary_sensitive(self):
        self.assertNotEqual(verifier.length_hash(["ab", "c"]), verifier.length_hash(["a", "bc"]))
        self.assertNotEqual(verifier.length_hash(["a", "b"]), verifier.length_hash(["b", "a"]))

    def test_duplicate_json_keys_fail_closed(self):
        with self.assertRaises(verifier.VerificationError):
            verifier._pairs([("status", "PROPOSED"), ("status", "OPEN")])

    def test_canonical_bytes_match_jackson_style(self):
        self.assertEqual(b'{"a":true,"b":1}', verifier.canonical_bytes({"b": 1, "a": True}))


if __name__ == "__main__":
    unittest.main()
