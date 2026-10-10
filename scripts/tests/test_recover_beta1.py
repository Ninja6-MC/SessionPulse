import copy
import importlib.util
import unittest
from pathlib import Path
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("recover_beta1", ROOT / "scripts" / "recover-beta1.py")
recovery = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(recovery)


class RecoverBeta1Test(unittest.TestCase):
    def records(self):
        run = {"id": int(recovery.RUN), "event": "push", "head_branch": recovery.TAG,
               "head_sha": recovery.SOURCE, "path": ".github/workflows/release.yml",
               "run_attempt": 1, "status": "completed", "conclusion": "failure"}
        jobs = [{"name": name, "conclusion": "success"} for name in recovery.JOBS]
        artifacts = [{"name": name, "id": identity, "expired": False,
                      "workflow_run": {"head_sha": recovery.SOURCE}}
                     for name, identity in recovery.ARTIFACTS.items()]
        return run, jobs, artifacts

    def test_failed_publication_can_recover_only_passing_candidate_jobs(self):
        recovery.check_run(*self.records())
        for name in recovery.JOBS:
            run, jobs, artifacts = self.records()
            next(job for job in jobs if job["name"] == name)["conclusion"] = "failure"
            with self.assertRaisesRegex(ValueError, "job did not pass"):
                recovery.check_run(run, jobs, artifacts)
        run, jobs, artifacts = self.records()
        jobs.append(copy.deepcopy(jobs[0]))
        with self.assertRaisesRegex(ValueError, "job did not pass"):
            recovery.check_run(run, jobs, artifacts)

    def test_wrong_run_source_event_attempt_and_tag_are_rejected(self):
        for key, value in (("id", 1), ("head_sha", "b" * 40), ("event", "pull_request"),
                           ("head_branch", "main"), ("run_attempt", 2),
                           ("path", ".github/workflows/ci.yml"), ("status", "in_progress")):
            run, jobs, artifacts = self.records()
            run[key] = value
            with self.subTest(key=key), self.assertRaisesRegex(ValueError, "mismatched"):
                recovery.check_run(run, jobs, artifacts)

    def test_retained_artifacts_must_have_exact_original_identity(self):
        for key, value in (("id", 1), ("expired", True), ("workflow_run", {"head_sha": "b" * 40})):
            run, jobs, artifacts = self.records()
            artifacts[0][key] = value
            with self.subTest(key=key), self.assertRaisesRegex(ValueError, "artifact provenance"):
                recovery.check_run(run, jobs, artifacts)
        for change in (lambda items: items.pop(), lambda items: items.append(copy.deepcopy(items[0]))):
            run, jobs, artifacts = self.records()
            change(artifacts)
            with self.assertRaisesRegex(ValueError, "artifact provenance"):
                recovery.check_run(run, jobs, artifacts)

    def test_context_requires_fixed_control_tag_and_main_ancestry(self):
        sha = "c" * 40
        env = {"GITHUB_REPOSITORY": recovery.REPOSITORY, "GITHUB_EVENT_NAME": "workflow_dispatch",
               "GITHUB_REF": "refs/tags/" + recovery.CONTROL_TAG, "GITHUB_SHA": sha,
               "GITHUB_WORKFLOW_REF": f"{recovery.REPOSITORY}/.github/workflows/recover-beta1.yml@refs/tags/{recovery.CONTROL_TAG}"}
        with patch.dict(recovery.os.environ, env), patch.object(recovery, "command", return_value=sha), \
                patch.object(recovery.destination, "tag_sha", side_effect=[sha, recovery.SOURCE]), \
                patch.object(recovery.subprocess, "run") as run:
            recovery.check_context()
            self.assertEqual([call.args[0][-2:] for call in run.call_args_list],
                             [[sha, "origin/main"], [recovery.SOURCE, "origin/main"]])
        for key in ("GITHUB_REF", "GITHUB_EVENT_NAME", "GITHUB_REPOSITORY", "GITHUB_WORKFLOW_REF"):
            with patch.dict(recovery.os.environ, {**env, key: "wrong"}), self.assertRaisesRegex(ValueError, "context"):
                recovery.check_context()
        with patch.dict(recovery.os.environ, env), patch.object(recovery, "command", return_value=sha), \
                patch.object(recovery.destination, "tag_sha", side_effect=[sha, "b" * 40]), \
                self.assertRaisesRegex(ValueError, "Published beta.1 tag changed"):
            recovery.check_context()

    def test_recovered_bytes_and_evidence_are_bound_to_original_candidate(self):
        run, jobs, artifacts = self.records()
        manifest = {"files": {"SessionPulse-0.2.0-beta.1.jar": recovery.JAR_SHA256}}
        with patch.object(recovery, "check_context"), \
                patch.object(recovery, "api", side_effect=[run, {"total_count": len(jobs), "jobs": jobs},
                                                           {"total_count": len(artifacts), "artifacts": artifacts}]), \
                patch.object(recovery.destination.candidate, "check_evidence") as evidence, \
                patch.object(recovery.destination.candidate, "verify", return_value=manifest) as verify:
            recovery.verify("retained", "evidence.json")
            args = evidence.call_args.args[0]
            self.assertEqual((args.directory, args.evidence, args.tag, args.sha, args.run_id, args.attempt),
                             ("retained", "evidence.json", recovery.TAG, recovery.SOURCE, recovery.RUN, "1"))
            verify.assert_called_once_with(args)
        manifest["files"]["SessionPulse-0.2.0-beta.1.jar"] = "b" * 64
        with patch.object(recovery, "check_context"), \
                patch.object(recovery, "api", side_effect=[run, {"total_count": len(jobs), "jobs": jobs},
                                                           {"total_count": len(artifacts), "artifacts": artifacts}]), \
                patch.object(recovery.destination.candidate, "check_evidence"), \
                patch.object(recovery.destination.candidate, "verify", return_value=manifest), \
                self.assertRaisesRegex(ValueError, "JAR differs"):
            recovery.verify("retained", "evidence.json")

    def test_page_sync_workflow_cannot_publish_or_rebuild_and_rechecks_after_gate(self):
        text = (ROOT / ".github/workflows/recover-beta1.yml").read_text()
        self.assertIn("environment: release", text)
        self.assertIn("group: release-refs/tags/v0.2.0-beta.1", text)
        self.assertEqual(text.count("run: python3 scripts/recover-beta1.py"), 2)
        complete = text.split("  complete:\n")[1]
        self.assertLess(complete.index("Revalidate provenance after approval"), complete.index("MODRINTH_TOKEN:"))
        self.assertLess(complete.index("--require-complete"), complete.index("./gradlew syncPlugin"))
        self.assertIn("for destination in github modrinth hangar", complete)
        for forbidden in ("publishPluginPublicationToHangar", "release create", "mc-publish@", "shadowJar", "push origin", "contents: write"):
            self.assertNotIn(forbidden, text)


if __name__ == "__main__":
    unittest.main()
