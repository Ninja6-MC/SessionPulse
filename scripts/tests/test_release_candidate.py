import importlib.util
import json
import tempfile
import unittest
from argparse import Namespace
from pathlib import Path
from zipfile import ZipFile


SCRIPT = Path(__file__).resolve().parents[1] / "release-candidate.py"
SPEC = importlib.util.spec_from_file_location("release_candidate", SCRIPT)
release_candidate = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(release_candidate)


class ReleaseCandidateTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name) / "candidate"
        self.directory.mkdir()
        self.receipts = Path(self.temp.name) / "receipts"
        self.receipts.mkdir()
        self.args = Namespace(directory=str(self.directory), tag="v1.2.3-rc.1", sha="a" * 40,
                              run_id="52", attempt="1", platform=None, receipts=str(self.receipts),
                              output=str(Path(self.temp.name) / "evidence.json"), evidence=None)
        self.jar = self.directory / "SessionPulse-1.2.3-rc.1.jar"
        with ZipFile(self.jar, "w") as archive:
            for name in release_candidate.CONTENTS:
                archive.writestr(name, "version: '1.2.3-rc.1'\n" if name == "plugin.yml" else "fixture")
            archive.writestr("META-INF/services/com.ninja6.sessionpulse.lib.kyori.Example", "example")
        (self.directory / f"{self.jar.name}.sha256").write_text(f"{release_candidate.digest(self.jar)}  {self.jar.name}\n")
        (self.directory / "release-notes.md").write_text("Notes\n")
        (self.directory / "release-body.md").write_text("Notes\n\nRequires Java 21.\n")
        release_candidate.create(self.args)

    def test_manifest_and_three_smoke_receipts_bind_candidate(self):
        self.assertEqual(release_candidate.verify(self.args)["channel"], "beta")
        for platform in ("paper", "folia", "spigot"):
            self.args.platform = platform
            self.args.output = str(self.receipts / f"smoke-{platform}.json")
            release_candidate.receipt(self.args)
        self.args.output = str(Path(self.temp.name) / "evidence.json")
        release_candidate.evidence(self.args)
        self.args.evidence = self.args.output
        release_candidate.check_evidence(self.args)

    def test_rejects_changed_jar_and_extra_file(self):
        self.jar.write_bytes(self.jar.read_bytes() + b"changed")
        with self.assertRaisesRegex(ValueError, "Digest mismatch"):
            release_candidate.verify(self.args)
        self.jar.write_bytes(self.jar.read_bytes()[:-7])
        (self.directory / "extra.jar").write_bytes(b"extra")
        with self.assertRaisesRegex(ValueError, "Candidate files differ"):
            release_candidate.verify(self.args)

    def test_rejects_wrong_tag_source_and_manifest_inventory(self):
        self.args.tag = "v1.2.3"
        with self.assertRaisesRegex(ValueError, "Candidate tag"):
            release_candidate.verify(self.args)
        self.args.tag = "v1.2.3-rc.1"
        self.args.sha = "b" * 40
        with self.assertRaisesRegex(ValueError, "Candidate candidate_id"):
            release_candidate.verify(self.args)
        self.args.sha = "a" * 40
        manifest_path = self.directory / "manifest.json"
        manifest = json.loads(manifest_path.read_text())
        manifest["files"].pop(self.jar.name)
        manifest_path.write_text(json.dumps(manifest))
        with self.assertRaisesRegex(ValueError, "inventory"):
            release_candidate.verify(self.args)

    def test_rejects_missing_or_altered_smoke_evidence(self):
        self.args.platform = "paper"
        self.args.output = str(self.receipts / "paper.json")
        release_candidate.receipt(self.args)
        with self.assertRaisesRegex(ValueError, "three smoke receipts"):
            release_candidate.evidence(self.args)
        for platform in ("folia", "spigot"):
            self.args.platform = platform
            self.args.output = str(self.receipts / f"{platform}.json")
            release_candidate.receipt(self.args)
        record = json.loads((self.receipts / "spigot.json").read_text())
        record["jar_sha256"] = "0" * 64
        (self.receipts / "spigot.json").write_text(json.dumps(record))
        with self.assertRaisesRegex(ValueError, "mismatched jar_sha256"):
            release_candidate.evidence(self.args)

    def test_rejects_wrong_embedded_version(self):
        with ZipFile(self.jar, "w") as archive:
            for name in release_candidate.CONTENTS:
                archive.writestr(name, "version: '9.9.9'\n" if name == "plugin.yml" else "fixture")
            archive.writestr("META-INF/services/com.ninja6.sessionpulse.lib.kyori.Example", "example")
        manifest_path = self.directory / "manifest.json"
        manifest = json.loads(manifest_path.read_text())
        manifest["files"][self.jar.name] = release_candidate.digest(self.jar)
        manifest_path.write_text(json.dumps(manifest))
        (self.directory / f"{self.jar.name}.sha256").write_text(f"{release_candidate.digest(self.jar)}  {self.jar.name}\n")
        manifest["files"][f"{self.jar.name}.sha256"] = release_candidate.digest(self.directory / f"{self.jar.name}.sha256")
        manifest_path.write_text(json.dumps(manifest))
        with self.assertRaisesRegex(ValueError, "plugin.yml version"):
            release_candidate.verify(self.args)


if __name__ == "__main__":
    unittest.main()
