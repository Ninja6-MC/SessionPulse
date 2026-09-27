from pathlib import Path
import importlib.util
import tempfile
import unittest
from zipfile import ZipFile


SCRIPT = Path(__file__).resolve().parents[1] / "verify-snapshot-artifact.py"
SPEC = importlib.util.spec_from_file_location("verify_snapshot_artifact", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)
verify = MODULE.verify


class VerifySnapshotArtifactTest(unittest.TestCase):
    def setUp(self):
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary_directory.cleanup)
        self.directory = Path(self.temporary_directory.name)
        self.version = "0.1.0-beta.1-3-gabc1234"
        self.jar = self.directory / f"SessionPulse-{self.version}.jar"

    def write_jar(self, version):
        with ZipFile(self.jar, "w") as archive:
            archive.writestr("plugin.yml", f"name: SessionPulse\nversion: '{version}'\n")

    def test_accepts_only_matching_shaded_jar(self):
        self.write_jar(self.version)
        (self.directory / "SessionPulse-0.1.0-thin.jar").touch()
        verify(self.directory, self.version)

    def test_rejects_missing_or_extra_deployable_jar(self):
        with self.assertRaises(ValueError):
            verify(self.directory, self.version)
        self.write_jar(self.version)
        (self.directory / "Other.jar").touch()
        with self.assertRaises(ValueError):
            verify(self.directory, self.version)

    def test_rejects_mismatched_embedded_version(self):
        self.write_jar("0.1.0-SNAPSHOT")
        with self.assertRaises(ValueError):
            verify(self.directory, self.version)


if __name__ == "__main__":
    unittest.main()
