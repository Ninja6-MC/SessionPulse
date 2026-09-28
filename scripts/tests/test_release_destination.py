import importlib.util
import hashlib
import io
import json
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch


SCRIPT = Path(__file__).resolve().parents[1] / "release-destination.py"
SPEC = importlib.util.spec_from_file_location("release_destination", SCRIPT)
destination = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(destination)
HANGAR_SESSION = destination.hangar_session

MANIFEST = {"candidate_id": "52-1-aaaaaaaaaaaa", "source_sha": "a" * 40, "tag": "v1.2.3-rc.1",
            "version": "1.2.3-rc.1", "channel": "beta", "files": {"SessionPulse-1.2.3-rc.1.jar": hashlib.sha256(b"candidate").hexdigest()}}


class ReleaseDestinationTest(unittest.TestCase):
    def setUp(self):
        tokens = patch.dict(destination.os.environ, {"MODRINTH_TOKEN": "test-token"})
        tokens.start()
        self.addCleanup(tokens.stop)
        session = patch.object(destination, "hangar_session", return_value={"Authorization": "HangarAuth test-session"})
        session.start()
        self.addCleanup(session.stop)
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        (self.directory / "release-notes.md").write_text("Notes\n")

    def modrinth_version(self, status="listed"):
        return {"id": "version-id", "project_id": "project-id", "status": status,
                "version_number": "1.2.3-rc.1", "version_type": "beta", "changelog": "Notes",
                "files": [{"filename": "SessionPulse-1.2.3-rc.1.jar", "url": "https://cdn.modrinth.com/file.jar",
                           "hashes": {"sha1": hashlib.sha1(b"candidate").hexdigest(), "sha512": hashlib.sha512(b"candidate").hexdigest()}}]}

    def hangar_version(self, visibility="public"):
        return {"name": "1.2.3-rc.1", "visibility": visibility, "channel": {"name": "Beta"}, "description": "Notes",
                "downloads": {"PAPER": {"fileInfo": {"name": "SessionPulse-1.2.3-rc.1.jar",
                                                       "sha256Hash": MANIFEST["files"]["SessionPulse-1.2.3-rc.1.jar"]},
                                       "downloadUrl": "https://hangarcdn.papermc.io/file.jar"}}}

    def absence_record(self, destination="modrinth", project="sessionpulse"):
        return {**{key: MANIFEST[key] for key in ("candidate_id", "tag", "version", "source_sha")},
                "confirmed_absent": {destination: project}}

    @patch.object(destination.urllib.request, "urlopen")
    def test_hangar_auth_failure_never_exposes_key_or_url(self, urlopen):
        secret = "credential-never-log-this"
        urlopen.side_effect = destination.urllib.error.URLError("https://hangar.papermc.io/api/v1/authenticate?apiKey=" + secret)
        with patch.dict(destination.os.environ, {"HANGAR_API_TOKEN": secret}):
            with self.assertRaises(ValueError) as error:
                HANGAR_SESSION()
        self.assertEqual(str(error.exception), "Hangar authentication failed")
        self.assertNotIn(secret, str(error.exception))

    @patch.object(destination.urllib.request, "urlopen")
    def test_hangar_auth_returns_header_without_exposing_key(self, urlopen):
        urlopen.return_value.__enter__.return_value = io.BytesIO(b'{"token":"test-jwt","expiresIn":3600}')
        with patch.dict(destination.os.environ, {"HANGAR_API_TOKEN": "test-key"}):
            self.assertEqual(HANGAR_SESSION(), {"Authorization": "HangarAuth test-jwt"})
        self.assertEqual(urlopen.call_args.args[0].get_method(), "POST")

    @patch.object(destination.subprocess, "check_output")
    def test_origin_tag_resolves_annotated_commit_and_rejects_missing(self, check_output):
        check_output.return_value = "a" * 40 + "\trefs/tags/v1.2.3\n" + "b" * 40 + "\trefs/tags/v1.2.3^{}\n"
        self.assertEqual(destination.tag_sha("v1.2.3"), "b" * 40)
        check_output.return_value = ""
        with self.assertRaisesRegex(ValueError, "missing"):
            destination.tag_sha("v1.2.3")

    @patch.object(destination, "github")
    @patch.object(destination, "tag_sha", return_value="b" * 40)
    @patch.object(destination.candidate, "verify", return_value={"source_sha": "a" * 40})
    @patch.object(destination.candidate, "check_evidence")
    def test_moved_tag_stops_before_destination_lookup(self, check_evidence, verify, tag_sha, github):
        args = ["release-destination.py", "github", "--directory", str(self.directory),
                "--evidence", "evidence.json", "--tag", "v1.2.3", "--sha", "a" * 40,
                "--run-id", "52", "--attempt", "1"]
        with patch.object(destination.sys, "argv", args):
            with self.assertRaisesRegex(ValueError, "no longer points"):
                destination.main()
        github.assert_not_called()

    @patch.object(destination.subprocess, "run")
    def test_github_retry_verifies_downloads_and_rejects_changed_bytes(self, run):
        jar = "SessionPulse-1.2.3-rc.1.jar"
        checksum = jar + ".sha256"
        body = "Notes\n\nRequires Java 21.\n"
        (self.directory / "release-body.md").write_text(body)
        manifest = {**MANIFEST, "tag": "v1.2.3-rc.1", "files": {
            jar: hashlib.sha256(b"candidate").hexdigest(), checksum: hashlib.sha256(b"checksum").hexdigest()}}
        release = {"tag_name": manifest["tag"], "prerelease": True, "draft": False,
                   "body": body, "assets": [{"name": jar}, {"name": checksum}]}
        conflicting = False

        def command(args, **kwargs):
            if args[:2] == ["gh", "api"]:
                return subprocess.CompletedProcess(args, 0, json.dumps(release), "")
            directory = Path(args[args.index("--dir") + 1])
            name = args[args.index("--pattern") + 1]
            data = b"different" if conflicting else b"candidate" if name == jar else b"checksum"
            (directory / name).write_bytes(data)
            return subprocess.CompletedProcess(args, 0, b"", b"")

        run.side_effect = command
        self.assertEqual(destination.github(self.directory, manifest, "Ninja6-MC/SessionPulse"), "complete")
        conflicting = True
        with self.assertRaisesRegex(ValueError, "differs"):
            destination.github(self.directory, manifest, "Ninja6-MC/SessionPulse")

    @patch.object(destination.subprocess, "run")
    def test_github_absence_and_partial_asset_upload(self, run):
        manifest = {**MANIFEST, "tag": "v1.2.3-rc.1"}
        run.return_value = subprocess.CompletedProcess([], 1, "", "HTTP 404")
        self.assertEqual(destination.github(self.directory, manifest, "Ninja6-MC/SessionPulse"), "absent")
        (self.directory / "release-body.md").write_text("Notes")
        release = {"tag_name": manifest["tag"], "prerelease": True, "draft": False,
                   "body": "Notes", "assets": []}
        run.return_value = subprocess.CompletedProcess([], 0, json.dumps(release), "")
        with self.assertRaisesRegex(ValueError, "inventory"):
            destination.github(self.directory, manifest, "Ninja6-MC/SessionPulse")

    @patch.object(destination, "request")
    def test_modrinth_omitted_draft_is_uncertain_without_reconciliation(self, request):
        request.return_value = (200, [{"version_number": "1.2.2"}])
        with self.assertRaisesRegex(ValueError, "absence is uncertain"):
            destination.modrinth(MANIFEST, "sessionpulse", self.directory)
        with patch.dict(destination.os.environ, {"RELEASE_ABSENCE_RECONCILIATION": json.dumps(self.absence_record())}):
            self.assertEqual(destination.modrinth(MANIFEST, "sessionpulse", self.directory), "absent")

    def test_absence_confirmation_is_bound_to_candidate_and_project(self):
        for key in ("candidate_id", "tag", "version", "source_sha"):
            record = self.absence_record()
            record[key] = "different"
            with patch.dict(destination.os.environ, {"RELEASE_ABSENCE_RECONCILIATION": json.dumps(record)}):
                with self.assertRaisesRegex(ValueError, "mismatched"):
                    destination.reconciled_absence(MANIFEST, "modrinth", "sessionpulse")
        with patch.dict(destination.os.environ, {"RELEASE_ABSENCE_RECONCILIATION": json.dumps(self.absence_record())}):
            with self.assertRaisesRegex(ValueError, "does not confirm"):
                destination.reconciled_absence(MANIFEST, "hangar", "SessionPulse")
            with self.assertRaisesRegex(ValueError, "does not confirm"):
                destination.reconciled_absence(MANIFEST, "modrinth", "different-project")

    @patch.object(destination.urllib.request, "urlopen")
    @patch.object(destination, "request")
    def test_modrinth_matching_version_is_skipped(self, request, urlopen):
        urlopen.return_value.__enter__.return_value = io.BytesIO(b"candidate")
        version = self.modrinth_version()
        request.side_effect = [(200, [version]), (200, version)]
        self.assertEqual(destination.modrinth(MANIFEST, "sessionpulse", self.directory), "complete")
        self.assertEqual(len(request.call_args_list[-1].args), 1)

    @patch.object(destination.urllib.request, "urlopen")
    @patch.object(destination, "request")
    def test_modrinth_conflicting_hash_stops_retry(self, request, urlopen):
        urlopen.return_value.__enter__.return_value = io.BytesIO(b"different")
        version = self.modrinth_version()
        request.side_effect = [(200, [version]), (200, version)]
        with self.assertRaisesRegex(ValueError, "conflict"):
            destination.modrinth(MANIFEST, "sessionpulse", self.directory)

    @patch.object(destination.urllib.request, "urlopen")
    @patch.object(destination, "request")
    def test_hangar_matching_version_skips_and_conflict_stops(self, request, urlopen):
        urlopen.return_value.__enter__.return_value = io.BytesIO(b"candidate")
        version = self.hangar_version()
        request.return_value = (200, version)
        self.assertEqual(destination.hangar({**MANIFEST, "hangar_channel": "Beta"}, "SessionPulse", self.directory), "complete")
        version["downloads"]["PAPER"]["fileInfo"]["sha256Hash"] = "b" * 64
        with self.assertRaisesRegex(ValueError, "conflicts"):
            destination.hangar({**MANIFEST, "hangar_channel": "Beta"}, "SessionPulse", self.directory)
        request.return_value = (404, None)
        with self.assertRaisesRegex(ValueError, "absence is uncertain"):
            destination.hangar({**MANIFEST, "hangar_channel": "Beta"}, "SessionPulse", self.directory)
        with patch.dict(destination.os.environ, {"RELEASE_ABSENCE_RECONCILIATION": json.dumps(self.absence_record("hangar", "SessionPulse"))}):
            self.assertEqual(destination.hangar({**MANIFEST, "hangar_channel": "Beta"}, "SessionPulse", self.directory), "absent")

    @patch.object(destination, "request")
    def test_modrinth_nonpublic_status_never_counts_as_complete(self, request):
        for status in ("draft", "scheduled", "archived", "unlisted", "unknown", None):
            with self.subTest(status=status):
                request.return_value = (200, [self.modrinth_version(status)])
                with self.assertRaisesRegex(ValueError, "not a listed public release"):
                    destination.modrinth(MANIFEST, "sessionpulse", self.directory)

    @patch.object(destination, "request")
    def test_hangar_nonpublic_visibility_never_counts_as_complete(self, request):
        for visibility in ("new", "needsApproval", "softDelete", "hidden", None):
            with self.subTest(visibility=visibility):
                request.return_value = (200, self.hangar_version(visibility))
                with self.assertRaisesRegex(ValueError, "not public"):
                    destination.hangar({**MANIFEST, "hangar_channel": "Beta"}, "SessionPulse", self.directory)

    @patch.object(destination, "request")
    def test_anonymous_visibility_and_metadata_must_match(self, request):
        for public in ((404, None), (200, {**self.modrinth_version(), "status": "draft"})):
            request.side_effect = [(200, [self.modrinth_version()]), public]
            with self.assertRaisesRegex(ValueError, "not anonymously accessible"):
                destination.modrinth(MANIFEST, "sessionpulse", self.directory)
        for public in ((404, None), (200, self.hangar_version("needsApproval"))):
            request.side_effect = [(200, self.hangar_version()), public]
            with self.assertRaisesRegex(ValueError, "not anonymously accessible"):
                destination.hangar({**MANIFEST, "hangar_channel": "Beta"}, "SessionPulse", self.directory)


if __name__ == "__main__":
    unittest.main()
