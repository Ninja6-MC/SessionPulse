import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "spigot-input.py"
SPEC = importlib.util.spec_from_file_location("spigot_input", SCRIPT)
module = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(module)


class SpigotInputTest(unittest.TestCase):
    def test_source_ref_or_tool_digest_drift_fails_closed(self):
        record = module.load("26.3")
        self.assertEqual(record["revision"], "4663")
        with tempfile.TemporaryDirectory() as temp:
            metadata = Path(temp) / "metadata.json"
            metadata.write_text(json.dumps(record["source_metadata"]))
            module.verify(record, metadata=metadata)
            changed = json.loads(metadata.read_text())
            changed["refs"]["Spigot"] = "0" * 40
            metadata.write_text(json.dumps(changed))
            with self.assertRaisesRegex(ValueError, "pinned source inputs"):
                module.verify(record, metadata=metadata)
            binary = Path(temp) / "BuildTools.jar"
            binary.write_bytes(b"changed executable")
            with self.assertRaisesRegex(ValueError, "pinned input digest"):
                module.verify(record, buildtools=binary)

    def test_older_protocol_inputs_remain_separate(self):
        old, new = module.load("1.21.11"), module.load("26.3")
        self.assertEqual(old["revision"], "4598")
        self.assertNotEqual(old["source_metadata"]["refs"], new["source_metadata"]["refs"])
        self.assertNotEqual(old["input_sha256"], new["input_sha256"])
        with self.assertRaisesRegex(ValueError, "Unpinned"):
            module.load("26.2")


if __name__ == "__main__":
    unittest.main()
