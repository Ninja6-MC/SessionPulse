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


class SharedStateOwnerTest(unittest.TestCase):
    def test_highest_stable_owns_even_beside_a_newer_pre_release(self):
        self.assertEqual(publisher.shared_state_owner(["0.2.0", "0.3.0-beta.1", "0.1.0"]), "0.2.0")

    def test_stable_hotfix_below_a_newer_stable_does_not_own(self):
        self.assertEqual(publisher.shared_state_owner(["1.1.0", "1.0.1"]), "1.1.0")

    def test_stable_hotfix_after_a_newer_pre_release_owns(self):
        self.assertEqual(publisher.shared_state_owner(["1.0.1", "1.1.0-beta.1"]), "1.0.1")

    def test_highest_pre_release_owns_while_there_is_no_stable_version(self):
        self.assertEqual(publisher.shared_state_owner(["1.0.0-alpha.3", "1.0.0-rc.1", "1.0.0-beta.9"]), "1.0.0-rc.1")

    def test_nothing_public_or_not_a_release_version_has_no_owner(self):
        self.assertIsNone(publisher.shared_state_owner([]))
        self.assertIsNone(publisher.shared_state_owner(["latest", "1.0"]))

    def test_stable_only_never_gives_the_state_to_a_pre_release(self):
        self.assertIsNone(publisher.shared_state_owner(["1.0.0-rc.1", "0.9.0-beta.2"], stable_only=True))
        self.assertEqual(publisher.shared_state_owner(["1.0.0", "1.1.0-beta.1"], stable_only=True), "1.0.0")

    def test_orders_prereleases_below_their_release(self):
        ordered = ["0.9.0", "1.0.0-alpha.2", "1.0.0-alpha.10", "1.0.0-beta.1", "1.0.0-rc.1", "1.0.0", "1.0.1-beta.1"]
        keys = [publisher.version_key(version) for version in ordered]
        self.assertEqual(keys, sorted(keys))


class OwnsAfterUploadTest(unittest.TestCase):
    def test_pre_release_while_a_stable_version_exists_cannot_own(self):
        self.assertFalse(publisher.owns_after_upload("1.1.0-beta.1", ["1.0.0"]))
        self.assertFalse(publisher.owns_after_upload("1.1.0-beta.1", ["1.0.0"], stable_only=True))

    def test_stable_hotfix_below_a_newer_stable_cannot_own(self):
        self.assertFalse(publisher.owns_after_upload("1.0.1", ["1.1.0", "1.0.0"]))
        self.assertFalse(publisher.owns_after_upload("1.0.1", ["1.1.0", "1.0.0"], stable_only=True))

    def test_stable_hotfix_after_a_newer_pre_release_owns(self):
        self.assertTrue(publisher.owns_after_upload("1.0.1", ["1.0.0", "1.1.0-beta.1"]))
        self.assertTrue(publisher.owns_after_upload("1.0.1", ["1.0.0"], stable_only=True))

    def test_pre_release_owns_the_page_but_never_the_latest_marker_before_a_stable(self):
        self.assertTrue(publisher.owns_after_upload("0.3.0-beta.1", ["0.2.0-beta.4"]))
        self.assertFalse(publisher.owns_after_upload("0.3.0-beta.1", [], stable_only=True))

    def test_already_published_owner_still_owns(self):
        self.assertTrue(publisher.owns_after_upload("1.0.0", ["1.0.0", "0.9.0"]))


def hangar_page(items, count, offset=0):
    return 200, {"pagination": {"count": count, "offset": offset},
                 "result": [{"name": name, "visibility": visibility, "channel": {"name": channel}}
                            for name, visibility, channel in items]}


def shared_args(destination="hangar", tag="v1.0.1", **changes):
    return type("Args", (), {"destination": destination, "repo": "Ninja6-MC/SessionPulse", "tag": tag,
                             "project": "SessionPulse", "github": "true", "hangar": "true", **changes})


class SharedInventoryTest(unittest.TestCase):
    @patch.object(publisher.destination, "request")
    def test_hangar_inventory_reads_every_page_on_every_channel(self, request):
        request.side_effect = [
            hangar_page([("1.0.0-beta.1", "public", "Beta")] * 25, 27),
            hangar_page([("1.0.0", "public", "Release"), ("1.1.0", "hidden", "Release")], 27, 25),
        ]
        published = publisher.public_hangar_versions("SessionPulse")
        self.assertEqual(request.call_count, 2)
        self.assertIn("offset=25", request.call_args.args[0])
        self.assertNotIn("channel=", request.call_args.args[0])
        self.assertIn("1.0.0", published)
        self.assertNotIn("1.1.0", published)

    @patch.object(publisher.destination, "request")
    def test_hangar_inventory_fails_closed(self, request):
        for response in ((404, None), (200, {"result": []}), (200, {"pagination": {"count": 3}, "result": []})):
            request.side_effect = [response]
            with self.subTest(response=response), self.assertRaises(ValueError):
                publisher.public_hangar_versions("SessionPulse")

    @patch.object(publisher.subprocess, "run")
    def test_github_inventory_lists_published_stable_releases(self, run):
        run.return_value = type("Process", (), {"returncode": 0, "stdout": "v1.1.0\nv1.0.0\nnot-a-tag\n", "stderr": ""})
        self.assertEqual(publisher.public_github_stable("Ninja6-MC/SessionPulse"), ["1.1.0", "1.0.0"])
        command = run.call_args.args[0]
        self.assertIn("--paginate", command)
        self.assertIn("select(.draft | not) | select(.prerelease | not)", command[-1])
        run.return_value = type("Process", (), {"returncode": 1, "stdout": "", "stderr": "HTTP 502"})
        with self.assertRaisesRegex(ValueError, "Cannot list GitHub releases"):
            publisher.public_github_stable("Ninja6-MC/SessionPulse")


@patch.dict(publisher.os.environ, {"GITHUB_OUTPUT": "", "GITHUB_STEP_SUMMARY": ""})
class SharedPlanTest(unittest.TestCase):
    def plan(self, tag, github, hangar, **changes):
        with patch.object(publisher, "public_github_stable", return_value=github), \
                patch.object(publisher, "public_hangar_versions", return_value=hangar), \
                patch.object(publisher, "write_outputs") as outputs, _quiet() as log:
            publisher.shared_plan(shared_args(tag=tag, **changes))
        return outputs.call_args.args[0], log.getvalue()

    def test_pre_release_while_a_stable_version_exists_asks_for_nothing(self):
        outputs, log = self.plan("v1.1.0-beta.1", ["1.0.0"], ["1.0.0"])
        self.assertEqual(outputs, {"shared_github": "false", "shared_hangar": "false"})
        self.assertIn("owned now by v1.0.0", log)

    def test_stable_hotfix_below_a_newer_stable_asks_for_nothing(self):
        outputs, _ = self.plan("v1.0.1", ["1.1.0", "1.0.0"], ["1.1.0", "1.0.0"])
        self.assertEqual(outputs, {"shared_github": "false", "shared_hangar": "false"})

    def test_stable_hotfix_after_a_newer_pre_release_needs_both(self):
        outputs, _ = self.plan("v1.0.1", ["1.0.0"], ["1.0.0", "1.1.0-beta.1"])
        self.assertEqual(outputs, {"shared_github": "true", "shared_hangar": "true"})

    def test_first_pre_releases_own_the_page_only(self):
        outputs, _ = self.plan("v0.3.0-beta.1", [], ["0.2.0-beta.4"])
        self.assertEqual(outputs, {"shared_github": "false", "shared_hangar": "true"})

    def test_unselected_destination_is_not_read(self):
        with patch.object(publisher, "public_github_stable") as github, \
                patch.object(publisher, "public_hangar_versions", return_value=[]), \
                patch.object(publisher, "write_outputs") as outputs, _quiet():
            publisher.shared_plan(shared_args(tag="v1.0.0", github="false"))
        github.assert_not_called()
        self.assertEqual(outputs.call_args.args[0], {"shared_github": "false", "shared_hangar": "true"})

    def test_rejects_a_tag_outside_the_release_grammar(self):
        for tag in ("1.0.0", "v1.0", "v1.0.0-preview.1"):
            with self.subTest(tag=tag), self.assertRaisesRegex(ValueError, "Invalid release tag"):
                publisher.shared_plan(shared_args(tag=tag))


@patch.dict(publisher.os.environ, {"GITHUB_STEP_SUMMARY": ""})
class SharedWriteTest(unittest.TestCase):
    def write(self, destination, tag, github=(), hangar=(), latest=None):
        with patch.object(publisher, "public_github_stable", return_value=list(github)) as read_github, \
                patch.object(publisher, "public_hangar_versions", return_value=list(hangar)) as read_hangar, \
                patch.object(publisher, "gh_json", return_value={"tag_name": latest or tag}) as self.read_latest, \
                patch.object(publisher.subprocess, "run") as run, _quiet() as log:
            run.return_value = type("Process", (), {"returncode": 0})
            publisher.shared_write(shared_args(destination=destination, tag=tag))
        return run, log.getvalue(), read_github, read_hangar

    def test_pre_release_while_a_stable_version_exists_is_refused(self):
        run, log, _, _ = self.write("hangar", "v1.1.0-beta.1", hangar=["1.0.0", "1.1.0-beta.1"])
        run.assert_not_called()
        self.assertIn("Skipping the Hangar project page: v1.1.0-beta.1 does not own it; v1.0.0 does", log)
        run, log, _, _ = self.write("github", "v1.1.0-beta.1", github=["1.0.0"])
        run.assert_not_called()
        self.assertIn("v1.0.0 does", log)

    def test_stable_hotfix_below_a_newer_stable_is_refused(self):
        for destination in ("github", "hangar"):
            with self.subTest(destination=destination):
                run, log, _, _ = self.write(destination, "v1.0.1", github=["1.1.0", "1.0.1"], hangar=["1.1.0", "1.0.1"])
                run.assert_not_called()
                self.assertIn("v1.1.0 does", log)

    def test_stable_hotfix_after_a_newer_pre_release_writes(self):
        run, log, _, _ = self.write("hangar", "v1.0.1", hangar=["1.0.0", "1.1.0-beta.1", "1.0.1"])
        run.assert_called_once_with(["./gradlew", "syncPluginPublicationMainResourcePagePageToHangar",
                                     "-PhangarProject=SessionPulse", "--no-daemon"])
        self.assertIn("v1.0.1 owns the Hangar project page", log)
        run, _, _, _ = self.write("github", "v1.0.1", github=["1.0.0", "1.0.1"])
        run.assert_called_once_with(["gh", "release", "edit", "v1.0.1", "--repo", "Ninja6-MC/SessionPulse", "--latest"])

    def test_owner_sets_latest_and_reads_it_back(self):
        run, log, _, _ = self.write("github", "v1.0.1", github=["1.0.0", "1.0.1"])
        run.assert_called_once_with(["gh", "release", "edit", "v1.0.1", "--repo", "Ninja6-MC/SessionPulse", "--latest"])
        self.read_latest.assert_called_once_with("repos/Ninja6-MC/SessionPulse/releases/latest")
        self.assertIn("GitHub latest is now v1.0.1", log)
        with self.assertRaisesRegex(ValueError, "GitHub latest is v1.0.0 after marking v1.0.1"):
            self.write("github", "v1.0.1", github=["1.0.0", "1.0.1"], latest="v1.0.0")

    def test_non_owner_stable_leaves_latest_alone(self):
        run, log, _, _ = self.write("github", "v1.0.1", github=["1.1.0", "1.0.1"])
        run.assert_not_called()
        self.read_latest.assert_not_called()
        self.assertIn("Skipping the GitHub latest marker: v1.0.1 does not own it; v1.1.0 does", log)

    def test_hangar_write_needs_no_latest_read(self):
        self.write("hangar", "v1.0.1", hangar=["1.0.1"])
        self.read_latest.assert_not_called()

    def test_pre_release_never_takes_the_latest_marker(self):
        run, log, _, _ = self.write("github", "v0.3.0-beta.1", github=[])
        run.assert_not_called()
        self.assertIn("no published version does", log)

    def test_version_not_yet_public_is_refused(self):
        run, _, _, _ = self.write("hangar", "v1.2.0", hangar=["1.1.0"])
        run.assert_not_called()

    def test_ownership_is_read_from_the_live_inventory_of_that_destination_only(self):
        _, _, read_github, read_hangar = self.write("hangar", "v1.0.1", hangar=["1.0.1"])
        read_hangar.assert_called_once_with("SessionPulse")
        read_github.assert_not_called()
        _, _, read_github, read_hangar = self.write("github", "v1.0.1", github=["1.0.1"])
        read_github.assert_called_once_with("Ninja6-MC/SessionPulse")
        read_hangar.assert_not_called()

    def test_owner_rechecked_at_write_time_superseded_since_plan(self):
        # Plan predicted ownership; a higher stable became public while this job waited.
        self.assertTrue(publisher.owns_after_upload("1.0.1", ["1.0.0"]))
        run, log, _, _ = self.write("hangar", "v1.0.1", hangar=["1.0.0", "1.0.1", "1.0.2"])
        run.assert_not_called()
        self.assertIn("v1.0.2 does", log)

    def test_failed_write_fails_the_step(self):
        with patch.object(publisher, "public_hangar_versions", return_value=["1.0.1"]), \
                patch.object(publisher.subprocess, "run", return_value=type("Process", (), {"returncode": 1})), \
                _quiet(), self.assertRaisesRegex(ValueError, "Writing the Hangar project page for v1.0.1 failed"):
            publisher.shared_write(shared_args(destination="hangar", tag="v1.0.1"))


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
