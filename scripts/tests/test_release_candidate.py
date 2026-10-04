import importlib.util
import io
import json
import tempfile
import unittest
from argparse import Namespace
from pathlib import Path
from contextlib import redirect_stdout
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
                              output=str(Path(self.temp.name) / "evidence.json"), evidence=None,
                              smoke_evidence=str(Path(self.temp.name) / "smoke.json"))
        self.jar = self.directory / "SessionPulse-1.2.3-rc.1.jar"
        with ZipFile(self.jar, "w") as archive:
            for name in release_candidate.CONTENTS:
                archive.writestr(name, "version: '1.2.3-rc.1'\n" if name == "plugin.yml" else "fixture")
            archive.writestr("META-INF/services/com.ninja6.sessionpulse.lib.kyori.Example", "example")
        (self.directory / f"{self.jar.name}.sha256").write_text(f"{release_candidate.digest(self.jar)}  {self.jar.name}\n")
        (self.directory / "release-notes.md").write_text("Notes\n")
        (self.directory / "release-body.md").write_text("Notes\n\nRequires Java 21.\n")
        release_candidate.create(self.args)

    def write_smoke(self, platform, minecraft="1.21.11"):
        self.args.platform = platform
        record = {"platform": platform, "mc_version": minecraft,
                  "plugin_sha": release_candidate.digest(self.jar), "server_sha": "c" * 64,
                  "bot_sha": "d" * 64, "bot_version": minecraft,
                  "java_runtime": 'openjdk version "' + ("25" if minecraft == "26.3" else "21") + '.0.4"',
                  "build_id": "147", "channel": "BETA", "result": "passed", "gameplay": True}
        if platform == "spigot":
            path = SCRIPT.parent / "server-inputs" / f"spigot-{minecraft}.json"
            record["spigot_input"] = json.loads(path.read_text())
            record["spigot_input"]["input_sha256"] = release_candidate.digest(path)
        Path(self.args.smoke_evidence).write_text(json.dumps(record))
        self.args.output = str(self.receipts / f"{platform}-{minecraft}.json")
        return record

    def write_all_receipts(self):
        for platform, minecraft in release_candidate.SMOKE_CASES:
            self.write_smoke(platform, minecraft)
            release_candidate.receipt(self.args)

    def test_five_smoke_receipts_bind_candidate_and_runtime_matrix(self):
        self.assertEqual(release_candidate.verify(self.args)["channel"], "beta")
        self.write_all_receipts()
        self.args.output = str(Path(self.temp.name) / "evidence.json")
        release_candidate.evidence(self.args)
        self.args.evidence = self.args.output
        release_candidate.check_evidence(self.args)
        evidence = json.loads(Path(self.args.output).read_text())
        self.assertEqual(len(evidence["servers"]), 5)

    def test_reconciliation_summary_uses_verified_candidate_identity(self):
        self.args.modrinth_project = "sessionpulse"
        self.args.hangar_project = "SessionPulse"
        output = io.StringIO()
        with redirect_stdout(output):
            release_candidate.show_reconciliation(self.args)
        self.assertEqual(json.loads(output.getvalue()), {
            "candidate_id": "52-1-aaaaaaaaaaaa", "source_sha": "a" * 40,
            "tag": "v1.2.3-rc.1", "version": "1.2.3-rc.1",
            "confirmed_absent": {"modrinth": "sessionpulse", "hangar": "SessionPulse"},
        })
        self.args.sha = "b" * 40
        with redirect_stdout(io.StringIO()):
            with self.assertRaisesRegex(ValueError, "candidate_id"):
                release_candidate.show_reconciliation(self.args)

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
        self.write_smoke("paper")
        release_candidate.receipt(self.args)
        with self.assertRaisesRegex(ValueError, "five smoke receipts"):
            release_candidate.evidence(self.args)
        self.write_all_receipts()
        path = self.receipts / "spigot-26.3.json"
        record = json.loads(path.read_text())
        record["jar_sha256"] = "0" * 64
        path.write_text(json.dumps(record))
        with self.assertRaisesRegex(ValueError, "mismatched jar_sha256"):
            release_candidate.evidence(self.args)

    def test_rejects_wrong_runtime_protocol_digest_and_boot_only_record(self):
        for key, value, message in (("java_runtime", 'openjdk version "21.0.4"', "Java 25"),
                                     ("bot_version", "1.21.11", "bot_version"),
                                     ("plugin_sha", "0" * 64, "plugin_sha"),
                                     ("gameplay", False, "gameplay assertions")):
            record = self.write_smoke("paper", "26.3")
            record[key] = value
            Path(self.args.smoke_evidence).write_text(json.dumps(record))
            with self.subTest(key=key), self.assertRaisesRegex(ValueError, message):
                release_candidate.receipt(self.args)

    def test_rejects_spigot_source_ref_drift(self):
        record = self.write_smoke("spigot", "26.3")
        record["spigot_input"]["source_metadata"]["refs"]["Spigot"] = "0" * 40
        Path(self.args.smoke_evidence).write_text(json.dumps(record))
        with self.assertRaisesRegex(ValueError, "Spigot source inputs"):
            release_candidate.receipt(self.args)

    def test_rejects_duplicate_mislabeled_and_tampered_combined_evidence(self):
        self.write_all_receipts()
        path = self.receipts / "paper-26.3.json"
        original = json.loads(path.read_text())
        record = json.loads(path.read_text())
        record["gameplay"]["mc_version"] = "1.21.11"
        record["gameplay"]["bot_version"] = "1.21.11"
        record["gameplay"]["java_runtime"] = 'openjdk version "21.0.4"'
        path.write_text(json.dumps(record))
        with self.assertRaisesRegex(ValueError, "Duplicate"):
            release_candidate.evidence(self.args)
        record = dict(original, platform="spigot")
        path.write_text(json.dumps(record))
        with self.assertRaisesRegex(ValueError, "mislabeled"):
            release_candidate.evidence(self.args)
        path.write_text(json.dumps(original))
        self.args.output = str(Path(self.temp.name) / "evidence.json")
        release_candidate.evidence(self.args)
        self.args.evidence = self.args.output
        record = json.loads(Path(self.args.output).read_text())
        record["servers"] = record["servers"][:-1]
        Path(self.args.output).write_text(json.dumps(record))
        with self.assertRaisesRegex(ValueError, "incomplete"):
            release_candidate.check_evidence(self.args)

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
