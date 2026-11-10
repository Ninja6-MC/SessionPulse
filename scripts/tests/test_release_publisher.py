import importlib.util
import unittest
from pathlib import Path
from unittest.mock import patch

SCRIPT = Path(__file__).resolve().parents[1] / "release-publisher.py"
SPEC = importlib.util.spec_from_file_location("release_publisher", SCRIPT)
publisher = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(publisher)

TAG = "v1.2.3-beta.1"
COMMIT = "a" * 40


def candidate_run(**changes):
    return {"id": 52, "path": ".github/workflows/release-candidate.yml", "event": "push",
            "head_branch": TAG, "head_sha": COMMIT, "status": "completed", "conclusion": "success", **changes}


def artifact(name, expired=False):
    return {"name": name, "expired": expired}


class DestinationsTest(unittest.TestCase):
    def test_parses_any_subset_in_publication_order(self):
        self.assertEqual(publisher.parse_destinations("github,modrinth,hangar"), ["github", "modrinth", "hangar"])
        self.assertEqual(publisher.parse_destinations("hangar, github"), ["github", "hangar"])
        self.assertEqual(publisher.parse_destinations("modrinth"), ["modrinth"])

    def test_rejects_empty_unknown_and_duplicate_entries(self):
        for value in ("", " ", "github,", ",hangar", "github,,hangar"):
            with self.assertRaisesRegex(ValueError, "comma-separated"):
                publisher.parse_destinations(value)
        for value in ("spigot", "github,Modrinth", "all"):
            with self.assertRaisesRegex(ValueError, "Unknown destination"):
                publisher.parse_destinations(value)
        with self.assertRaisesRegex(ValueError, "twice"):
            publisher.parse_destinations("hangar,hangar")


class CandidateProvenanceTest(unittest.TestCase):
    def test_accepts_successful_candidate_run_for_tag_commit(self):
        publisher.check_candidate_run(candidate_run(), TAG, COMMIT)

    def test_rejects_run_from_another_workflow_event_tag_commit_or_outcome(self):
        for field, value in (("path", ".github/workflows/ci.yml"), ("path", ".github/workflows/release.yml"),
                             ("event", "workflow_dispatch"), ("head_branch", "v1.2.3-beta.2"),
                             ("head_branch", "main"), ("head_sha", "b" * 40), ("status", "in_progress"),
                             ("conclusion", "failure"), ("conclusion", "cancelled")):
            with self.subTest(field=field, value=value):
                with self.assertRaisesRegex(ValueError, "Candidate run 52 has"):
                    publisher.check_candidate_run(candidate_run(**{field: value}), TAG, COMMIT)

    def test_pins_newest_attempt_with_its_own_evidence(self):
        artifacts = [artifact("release-candidate-52-1"), artifact("release-evidence-52-1"),
                     artifact("release-candidate-52-2"), artifact("release-evidence-52-2"),
                     artifact("release-candidate-520-3"), artifact("smoke-paper-1.21.11-52-2")]
        self.assertEqual(publisher.pin_attempt(artifacts, "52"), 2)

    def test_rejects_missing_or_mismatched_evidence(self):
        with self.assertRaisesRegex(ValueError, "no release candidate artifact"):
            publisher.pin_attempt([artifact("release-evidence-52-1")], "52")
        with self.assertRaisesRegex(ValueError, "no single release-evidence-52-2"):
            publisher.pin_attempt([artifact("release-candidate-52-2"), artifact("release-evidence-52-1")], "52")

    def test_expired_candidate_stops_without_rebuilding(self):
        for expired in ("release-candidate-52-1", "release-evidence-52-1"):
            artifacts = [artifact("release-candidate-52-1", expired == "release-candidate-52-1"),
                         artifact("release-evidence-52-1", expired == "release-evidence-52-1")]
            with self.assertRaisesRegex(ValueError, "expired. Nothing is rebuilt"):
                publisher.pin_attempt(artifacts, "52")

    @patch.object(publisher.destination, "tag_sha", return_value=COMMIT)
    @patch.object(publisher, "gh_json")
    def test_provenance_rejects_candidate_run_for_moved_tag(self, gh_json, tag_sha):
        gh_json.side_effect = [candidate_run(head_sha="c" * 40)]
        args = type("Args", (), {"repo": "Ninja6-MC/SessionPulse", "tag": TAG, "run_id": "52"})
        with self.assertRaisesRegex(ValueError, "commit"):
            publisher.provenance(args)
        args.run_id = "52; rm"
        with self.assertRaisesRegex(ValueError, "workflow run ID"):
            publisher.provenance(args)


class AttestationGuardTest(unittest.TestCase):
    def args(self, attempt="1", attested="true", check_attempt=True):
        return type("Args", (), {"repo": "Ninja6-MC/SessionPulse", "tag": TAG, "run_id": "90",
                                 "attempt": attempt, "attested": attested, "check_attempt": check_attempt})

    def test_true_attestation_refused_after_first_attempt(self):
        publisher.check_attempt("1", True)
        publisher.check_attempt("2", False)
        for attempt in ("2", "3"):
            with self.assertRaisesRegex(ValueError, "only on attempt 1"):
                publisher.check_attempt(attempt, True)

    def test_overlapping_publisher_run_refuses_attestation(self):
        created = "2026-11-10T10:00:00Z"
        runs = [{"id": 90, "head_branch": TAG, "status": "in_progress", "updated_at": "2026-11-10T10:05:00Z"},
                {"id": 80, "head_branch": TAG, "status": "completed", "updated_at": "2026-11-10T09:00:00Z"},
                {"id": 70, "head_branch": "v1.2.3-beta.0", "status": "in_progress", "updated_at": created}]
        publisher.check_concurrent(runs, "90", created, TAG)
        waiting = {"id": 92, "head_branch": TAG, "status": "pending", "created_at": "2026-11-10T10:01:00Z"}
        publisher.check_concurrent(runs + [waiting], "90", created, TAG)
        for other in ({"id": 91, "head_branch": TAG, "status": "queued", "updated_at": created},
                      {"id": 89, "head_branch": TAG, "status": "in_progress", "updated_at": created},
                      {"id": 88, "head_branch": TAG, "status": "completed", "updated_at": "2026-11-10T10:00:01Z"}):
            with self.subTest(other=other):
                with self.assertRaisesRegex(ValueError, f"Publisher run {other['id']}"):
                    publisher.check_concurrent(runs + [other], "90", created, TAG)

    @patch.object(publisher, "gh_json")
    def test_guard_checks_attempt_before_any_lookup(self, gh_json):
        with self.assertRaisesRegex(ValueError, "only on attempt 1"):
            publisher.guard(self.args(attempt="2"))
        gh_json.assert_not_called()
        publisher.guard(self.args(attempt="2", attested="false"))
        gh_json.assert_not_called()

    @patch.object(publisher, "gh_json")
    def test_guard_refuses_concurrent_publisher(self, gh_json):
        gh_json.side_effect = [{"created_at": "2026-11-10T10:00:00Z"},
                               {"workflow_runs": [{"id": 91, "head_branch": TAG, "status": "in_progress"}]}]
        with self.assertRaisesRegex(ValueError, "Publisher run 91"):
            publisher.guard(self.args())
        self.assertIn("workflows/release.yml/runs?branch=v1.2.3-beta.1", gh_json.call_args.args[0])


class HangarPageSyncTest(unittest.TestCase):
    def test_orders_prereleases_below_their_release(self):
        ordered = ["0.9.0", "1.0.0-alpha.2", "1.0.0-alpha.10", "1.0.0-beta.1", "1.0.0-rc.1", "1.0.0", "1.0.1-beta.1"]
        keys = [publisher.version_key(version) for version in ordered]
        self.assertEqual(keys, sorted(keys))

    def test_only_newest_public_version_syncs(self):
        self.assertTrue(publisher.is_newest("1.0.0-beta.2", ["1.0.0-beta.1", "1.0.0-beta.2", "0.9.0-beta.4"]))
        self.assertTrue(publisher.is_newest("1.0.0-beta.1", []))
        self.assertFalse(publisher.is_newest("1.0.0-beta.1", ["1.0.0-beta.2"]))
        self.assertFalse(publisher.is_newest("1.0.0-rc.1", ["1.0.1-beta.1"]))

    @patch.object(publisher.destination, "request")
    def test_sync_decision_reads_every_public_page_on_channel(self, request):
        def page(names, offset, count):
            return 200, {"pagination": {"count": count, "offset": offset},
                         "result": [{"name": name, "visibility": visibility, "channel": {"name": channel}}
                                    for name, visibility, channel in names]}
        request.side_effect = [
            page([("1.0.0-beta.1", "public", "Beta")] * 25, 0, 27),
            page([("1.0.0-beta.3", "public", "Beta"), ("1.0.0-beta.4", "hidden", "Beta")], 25, 27),
        ]
        args = type("Args", (), {"project": "SessionPulse", "version": "1.0.0-beta.2", "channel": "Beta"})
        with patch.dict(publisher.os.environ, {"GITHUB_OUTPUT": ""}), _quiet() as output:
            publisher.hangar_sync(args)
        self.assertEqual(request.call_count, 2)
        self.assertIn("Skipping", output.getvalue())
        self.assertIn("offset=25", request.call_args.args[0])

    @patch.object(publisher, "write_outputs")
    @patch.object(publisher.destination, "request")
    def test_newer_version_skips_sync_and_logs_it(self, request, write_outputs):
        request.return_value = (200, {"pagination": {"count": 2}, "result": [
            {"name": "1.0.0-beta.3", "visibility": "public", "channel": {"name": "Beta"}},
            {"name": "1.0.0", "visibility": "public", "channel": {"name": "Release"}}]})
        args = type("Args", (), {"project": "SessionPulse", "version": "1.0.0-beta.2", "channel": "Beta"})
        with _quiet() as output:
            publisher.hangar_sync(args)
        write_outputs.assert_called_once_with({"sync": "false"})
        self.assertIn("Skipping Hangar page sync", output.getvalue())
        request.return_value = (200, {"pagination": {"count": 1}, "result": [
            {"name": "1.0.0", "visibility": "public", "channel": {"name": "Release"}}]})
        with _quiet():
            publisher.hangar_sync(args)
        write_outputs.assert_called_with({"sync": "true"})


def _quiet():
    import contextlib
    import io

    class Capture(contextlib.redirect_stdout):
        def __init__(self):
            self.buffer = io.StringIO()
            super().__init__(self.buffer)

        def __enter__(self):
            super().__enter__()
            return self.buffer

    return Capture()


if __name__ == "__main__":
    unittest.main()
