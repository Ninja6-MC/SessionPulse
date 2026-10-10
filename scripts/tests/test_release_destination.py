import importlib.util
import base64
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

    @patch.object(destination, "tag_sha", return_value="a" * 40)
    @patch.object(destination.candidate, "verify", return_value=MANIFEST)
    @patch.object(destination.candidate, "check_evidence")
    def test_recovery_inventory_requires_candidate_bound_hangar_attestation(self, check_evidence, verify, tag_sha):
        args = ["release-destination.py", "inventory", "--project", "SessionPulse",
                "--directory", str(self.directory), "--evidence", "evidence.json",
                "--tag", MANIFEST["tag"], "--sha", MANIFEST["source_sha"],
                "--run-id", "52", "--attempt", "1"]
        with patch.object(destination.sys, "argv", args):
            with patch.dict(destination.os.environ, {"RELEASE_ABSENCE_RECONCILIATION": json.dumps(
                    self.absence_record("hangar", "other-project"))}):
                with self.assertRaisesRegex(ValueError, "does not confirm hangar project SessionPulse"):
                    destination.main()
            with patch.dict(destination.os.environ, {"RELEASE_ABSENCE_RECONCILIATION": json.dumps(
                    self.absence_record("hangar", "SessionPulse"))}):
                destination.main()

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
        with self.assertRaisesRegex(ValueError, "Missing release environment RELEASE_ABSENCE_RECONCILIATION"):
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

    def test_reconciliation_missing_malformed_and_matching(self):
        with patch.dict(destination.os.environ, {"RELEASE_ABSENCE_RECONCILIATION": ""}):
            with self.assertRaisesRegex(ValueError, "Missing release environment RELEASE_ABSENCE_RECONCILIATION"):
                destination.reconciled_absence(MANIFEST, "modrinth", "sessionpulse")
        with patch.dict(destination.os.environ, {"RELEASE_ABSENCE_RECONCILIATION": "{"}):
            with self.assertRaisesRegex(ValueError, "malformed JSON"):
                destination.reconciled_absence(MANIFEST, "modrinth", "sessionpulse")
        with patch.dict(destination.os.environ, {"RELEASE_ABSENCE_RECONCILIATION": json.dumps(self.absence_record())}):
            self.assertEqual(destination.reconciled_absence(MANIFEST, "modrinth", "sessionpulse"), "absent")

    @patch.object(destination, "request")
    def test_preflight_requires_anonymous_modrinth_project(self, request):
        request.return_value = (404, None)
        with self.assertRaisesRegex(ValueError, "not anonymously accessible"):
            destination.require_public_modrinth_project("sessionpulse")
        request.return_value = (200, {"id": "project-id", "status": "approved"})
        destination.require_public_modrinth_project("sessionpulse")
        self.assertEqual(request.call_args.args[0], "https://api.modrinth.com/v2/project/sessionpulse")

    @patch.object(destination, "request")
    def test_preflight_rejects_every_nonapproved_modrinth_project_status(self, request):
        for status in ("archived", "rejected", "draft", "unlisted", "processing", "withheld",
                       "scheduled", "private", "unknown", "future-status", None, True, ["approved"]):
            with self.subTest(status=status):
                request.return_value = (200, {"id": "project-id", "status": status,
                                               "requested_status": "approved"})
                with self.assertRaisesRegex(ValueError, "project is not approved"):
                    destination.require_public_modrinth_project("sessionpulse")
        request.return_value = (200, {"id": "project-id", "requested_status": "approved"})
        with self.assertRaisesRegex(ValueError, "project is not approved"):
            destination.require_public_modrinth_project("sessionpulse")

    @patch.object(destination, "request")
    def test_preflight_rejects_malformed_anonymous_project_responses(self, request):
        for response in ((404, None), (403, {}), (200, []), (200, {}),
                         (200, {"status": "approved"}), (200, {"id": "", "status": "approved"})):
            with self.subTest(response=response):
                request.return_value = response
                with self.assertRaisesRegex(ValueError, "not anonymously accessible"):
                    destination.require_public_modrinth_project("sessionpulse")

    @patch.object(destination, "tag_sha", return_value="a" * 40)
    @patch.object(destination.candidate, "verify", return_value=MANIFEST)
    @patch.object(destination.candidate, "check_evidence")
    @patch.object(destination, "modrinth")
    @patch.object(destination, "request", return_value=(200, {"id": "project-id", "status": "withheld"}))
    def test_cli_preflight_rejects_withheld_project_for_new_upload_and_retry(self, request, modrinth,
                                                                          check_evidence, verify, tag_sha):
        args = ["release-destination.py", "modrinth", "--project", "sessionpulse",
                "--directory", str(self.directory), "--evidence", "evidence.json",
                "--tag", MANIFEST["tag"], "--sha", MANIFEST["source_sha"],
                "--run-id", "52", "--attempt", "1", "--require-public-project"]
        for state in ("absent", "complete"):
            with self.subTest(state=state), patch.object(destination.sys, "argv", args):
                modrinth.return_value = state
                with self.assertRaisesRegex(ValueError, "project is not approved"):
                    destination.main()

    @patch.object(destination, "request")
    def test_modrinth_upload_access_requires_owner_and_project_permission(self, request):
        project = {"id": "project-id", "slug": "sessionpulse"}
        member = {"user": {"id": "owner-id"}, "accepted": True, "permissions": 1}
        request.side_effect = [(200, {"id": "owner-id"}), (200, project), (200, [member])]
        destination.require_modrinth_upload_access("sessionpulse")
        self.assertEqual(request.call_args_list[2].args[0],
                         "https://api.modrinth.com/v2/project/sessionpulse/members")
        for bad_member in ({**member, "permissions": 0}, {**member, "accepted": False},
                           {**member, "user": {"id": "someone-else"}}):
            with self.subTest(member=bad_member):
                request.side_effect = [(200, {"id": "owner-id"}), (200, project), (200, [bad_member])]
                with self.assertRaisesRegex(ValueError, "lacks accepted upload permission"):
                    destination.require_modrinth_upload_access("sessionpulse")
        request.side_effect = [(200, {"id": "owner-id"}), (200, {**project, "slug": "wrong"})]
        with self.assertRaisesRegex(ValueError, "does not match"):
            destination.require_modrinth_upload_access("sessionpulse")

    @patch.object(destination, "request")
    def test_modrinth_upload_access_reports_missing_read_scopes(self, request):
        request.side_effect = destination.urllib.error.HTTPError("https://api.modrinth.com/v2/user", 401,
                                                                  "Unauthorized", {}, None)
        with self.assertRaisesRegex(ValueError, "USER_READ and PROJECT_READ"):
            destination.require_modrinth_upload_access("sessionpulse")

    @patch.object(destination, "request")
    def test_hangar_target_requires_expected_project_and_channel(self, request):
        manifest = {**MANIFEST, "hangar_channel": "Beta"}
        project = {"id": 7083, "namespace": {"owner": "Ninja6-MC", "slug": "SessionPulse"},
                   "visibility": "public"}
        channels = [{"name": "Release", "projectId": 7083}, {"name": "Beta", "projectId": 7083}]
        request.side_effect = [(200, project), (200, channels)]
        self.assertEqual(destination.require_hangar_target(manifest, "SessionPulse", "Ninja6-MC"), 7083)
        self.assertEqual(request.call_args_list[0].args[0],
                         "https://hangar.papermc.io/api/v1/projects/Ninja6-MC/SessionPulse")
        self.assertEqual(request.call_args_list[1].args[0],
                         "https://hangar.papermc.io/api/internal/channels/7083")
        for invalid in (
            (404, None),
            (200, {**project, "namespace": {"owner": "Other", "slug": "SessionPulse"}}),
            (200, {**project, "namespace": {"owner": "Ninja6-MC", "slug": "Other"}}),
        ):
            with self.subTest(project=invalid):
                request.side_effect = [invalid]
                with self.assertRaisesRegex(ValueError, "does not match"):
                    destination.require_hangar_target(manifest, "SessionPulse", "Ninja6-MC")
        for invalid_channels in ([{"name": "Release", "projectId": 7083}],
                                 [{"name": "Beta", "projectId": 9999}]):
            with self.subTest(channels=invalid_channels):
                request.side_effect = [(200, project), (200, invalid_channels)]
                with self.assertRaisesRegex(ValueError, "has no Beta channel"):
                    destination.require_hangar_target(manifest, "SessionPulse", "Ninja6-MC")

    @patch.object(destination, "request")
    def test_hangar_upload_access_checks_key_bits_and_project_permissions(self, request):
        def session(bits):
            payload = base64.urlsafe_b64encode(json.dumps({"permissions": bin(bits)[2:]}).encode()).decode().rstrip("=")
            return {"Authorization": "HangarAuth header." + payload + ".signature"}

        both = (1 << 9) | (1 << 12)
        with patch.object(destination, "hangar_session", return_value=session(both)):
            request.return_value = (200, {"result": True})
            destination.require_hangar_upload_access(7083)
            self.assertIn("project=7083", request.call_args.args[0])
            self.assertIn("permissions=create_version", request.call_args.args[0])
            self.assertIn("permissions=edit_page", request.call_args.args[0])
            request.return_value = (200, {"result": False})
            with self.assertRaisesRegex(ValueError, "owner lacks"):
                destination.require_hangar_upload_access(7083)
        with patch.object(destination, "hangar_session", return_value=session(1 << 12)):
            request.reset_mock()
            with self.assertRaisesRegex(ValueError, "needs create_version and edit_page"):
                destination.require_hangar_upload_access(7083)
            request.assert_not_called()

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
        with self.assertRaisesRegex(ValueError, "Missing release environment RELEASE_ABSENCE_RECONCILIATION"):
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
