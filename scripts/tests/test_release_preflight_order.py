import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
WORKFLOWS = ROOT / ".github" / "workflows"
PUBLISHER = (WORKFLOWS / "release.yml").read_text()
CANDIDATE = (WORKFLOWS / "release-candidate.yml").read_text()
PREFLIGHT = (ROOT / "scripts" / "release-preflight.sh").read_text()
PUBLISH_JOBS = ("publish-github", "publish-modrinth", "publish-hangar")
TAG_GUARD = "github.ref == format('refs/tags/{0}', inputs.tag)"


def jobs(workflow):
    body = workflow.split("\njobs:\n", 1)[1]
    names = list(re.finditer(r"^  ([a-z][a-z-]*):\n", body, re.M))
    return {match.group(1): body[match.end():names[index + 1].start() if index + 1 < len(names) else len(body)]
            for index, match in enumerate(names)}


def steps(job):
    return ["      - " + step for step in re.split(r"^      - ", job, flags=re.M)[1:]]


def step_index(job, name):
    found = [index for index, step in enumerate(steps(job)) if f"name: {name}\n" in step]
    if len(found) != 1:
        raise AssertionError(f"expected one step named {name!r}, found {len(found)}")
    return found[0]


class PublisherShapeTest(unittest.TestCase):
    def test_dispatch_only_with_per_run_inputs(self):
        trigger = PUBLISHER.split("\non:\n", 1)[1].split("\nconcurrency:", 1)[0]
        self.assertIn("  workflow_dispatch:\n", trigger)
        self.assertNotIn("push:", trigger)
        for name in ("tag", "candidate_run_id", "destinations", "modrinth_absence_confirmed",
                     "hangar_absence_confirmed", "nonce"):
            self.assertRegex(trigger, rf"\n      {name}:\n")
        for name in ("modrinth_absence_confirmed", "hangar_absence_confirmed"):
            block = re.split(r"\n      [a-z_]+:\n", trigger.split(f"      {name}:\n", 1)[1], maxsplit=1)[0]
            self.assertIn("default: false", block, name)
            self.assertIn("type: boolean", block, name)
        self.assertIn("default: github,modrinth,hangar", trigger)
        self.assertRegex(PUBLISHER, r"\nrun-name: .*\$\{\{ inputs\.tag \}\}.*\$\{\{ inputs\.nonce \}\}")

    def test_concurrency_is_keyed_on_the_ref_not_the_input(self):
        concurrency = PUBLISHER.split("\nconcurrency:\n", 1)[1].split("\n\n", 1)[0]
        self.assertIn("group: release-publish-${{ github.ref }}", concurrency)
        self.assertNotIn("inputs.", concurrency)
        self.assertIn("cancel-in-progress: false", concurrency)

    def test_every_job_runs_only_on_the_tag_it_names(self):
        graph = jobs(PUBLISHER)
        self.assertEqual(set(graph), {"plan", *PUBLISH_JOBS})
        for name, job in graph.items():
            self.assertIsNotNone(re.search(r"^    if: " + re.escape(TAG_GUARD) + r"( &&|\n)", job, re.M), name)

    def test_plan_validates_tag_input_against_release_grammar(self):
        pattern = r'"\$TAG" =~ (\^v\S+\$) \]\]'
        self.assertEqual(re.search(pattern, jobs(PUBLISHER)["plan"]).group(1),
                         re.search(pattern, CANDIDATE).group(1))
        self.assertIn('test "$GITHUB_REF" = "refs/tags/$TAG"', jobs(PUBLISHER)["plan"])

    def test_plan_holds_no_environment_or_publishing_secret(self):
        plan = jobs(PUBLISHER)["plan"]
        self.assertNotIn("environment:", plan)
        self.assertNotIn("secrets.", plan)
        self.assertNotIn("contents: write", plan)

    def test_one_approval_releases_every_publish_job(self):
        for name in PUBLISH_JOBS:
            job = jobs(PUBLISHER)[name]
            self.assertIn("\n    needs: [plan]\n", job, name)
            self.assertIn("\n    environment: release\n", job, name)
        self.assertIn("contents: write", jobs(PUBLISHER)["publish-github"])
        for name in ("publish-modrinth", "publish-hangar"):
            self.assertNotIn("contents: write", jobs(PUBLISHER)[name])


class PlanTest(unittest.TestCase):
    plan = jobs(PUBLISHER)["plan"]

    def test_candidate_provenance_precedes_artifact_download(self):
        provenance = step_index(self.plan, "Verify candidate run provenance")
        downloads = [index for index, step in enumerate(steps(self.plan)) if "download-artifact" in step]
        self.assertEqual(len(downloads), 2)
        self.assertLess(provenance, min(downloads))
        for index in downloads:
            step = steps(self.plan)[index]
            self.assertIn("run-id: ${{ inputs.candidate_run_id }}", step)
            self.assertIn("-${{ steps.candidate.outputs.attempt }}", step)
        self.assertIn("release-publisher.py provenance", steps(self.plan)[provenance])

    def test_plan_rejects_missing_attestation_from_public_lookups(self):
        step = steps(self.plan)[step_index(self.plan, "Require an attestation for each destination not publicly complete")]
        self.assertIn("lookup modrinth --project \"$MODRINTH_PROJECT\" --public-only --require-public-project", step)
        self.assertIn("lookup hangar --project \"$HANGAR_PROJECT\" --public-only --require-hangar-target", step)
        self.assertIn("--absence-confirmed", step)
        self.assertNotIn("TOKEN", step.replace("GH_TOKEN", ""))

    def test_plan_refuses_attestation_beside_another_publisher_run(self):
        step = steps(self.plan)[step_index(self.plan, "Refuse attestations beside another publisher run")]
        self.assertIn("release-publisher.py guard", step)
        self.assertIn("inputs.modrinth_absence_confirmed || inputs.hangar_absence_confirmed", step)
        self.assertLess(step_index(self.plan, "Refuse attestations beside another publisher run"),
                        step_index(self.plan, "Require an attestation for each destination not publicly complete"))

    def test_plan_dry_runs_hangar_task_graph(self):
        self.assertIn("check-hangar-task-graph.py", steps(self.plan)[step_index(self.plan, "Validate Hangar task graph")])


class PublishJobTest(unittest.TestCase):
    first_write = {"publish-github": "Publish GitHub release if absent",
                   "publish-modrinth": "Publish Modrinth if absent",
                   "publish-hangar": "Publish Hangar if absent"}

    def test_every_job_preflights_every_selected_destination_before_writing(self):
        for name, write in self.first_write.items():
            job = jobs(PUBLISHER)[name]
            preflight = step_index(job, "Preflight every selected destination")
            self.assertLess(preflight, step_index(job, write), name)
            step = steps(job)[preflight]
            for secret in ("secrets.MODRINTH_TOKEN", "secrets.HANGAR_API_TOKEN", "vars.RELEASE_SECRETS_MIGRATED"):
                self.assertIn(secret, step, name)
            self.assertIn("bash scripts/release-preflight.sh", step)
            for selection in ("SELECT_GITHUB", "SELECT_MODRINTH", "SELECT_HANGAR"):
                self.assertIn(f"{selection}: ${{{{ needs.plan.outputs.", job)
        for destination in ("github", "modrinth", "hangar"):
            self.assertIn(f"check {destination}", PREFLIGHT)
        self.assertIn("--credentials-only", PREFLIGHT)
        self.assertIn("check-hangar-task-graph.py", PREFLIGHT)
        self.assertNotIn("--absence-confirmed", PREFLIGHT)

    def test_true_attestation_refused_on_later_attempt_immediately_before_each_registry_upload(self):
        for name, guard, write, state in (
                ("publish-modrinth", "Refuse a reused Modrinth attestation", "Publish Modrinth if absent", "steps.modrinth"),
                ("publish-hangar", "Refuse a reused Hangar attestation", "Publish Hangar if absent", "steps.hangar")):
            job = jobs(PUBLISHER)[name]
            self.assertEqual(step_index(job, guard) + 1, step_index(job, write), name)
            step = steps(job)[step_index(job, guard)]
            self.assertIn("release-publisher.py guard", step)
            self.assertIn('--attempt "$GITHUB_RUN_ATTEMPT"', step)
            self.assertIn("--check-attempt", step)
            self.assertIn(f"if: {state}.outputs.state == 'absent'", step)
            self.assertIn(f"if: {state}.outputs.state == 'absent'", steps(job)[step_index(job, write)])

    def test_plan_refuses_a_reused_attestation_before_approval(self):
        job = jobs(PUBLISHER)["plan"]
        step = steps(job)[step_index(job, "Refuse attestations beside another publisher run")]
        self.assertIn("--check-attempt", step)

    def test_hangar_upload_takes_its_attestation_from_this_dispatch(self):
        job = jobs(PUBLISHER)["publish-hangar"]
        step = steps(job)[step_index(job, "Publish Hangar if absent")]
        self.assertIn("CONFIRMED: ${{ inputs.hangar_absence_confirmed }}", step)
        self.assertNotIn("--absence-confirmed --require-absent", step)

    def test_each_registry_job_uploads_only_its_own_destination(self):
        self.assertNotIn("mc-publish", jobs(PUBLISHER)["publish-github"] + jobs(PUBLISHER)["publish-hangar"])
        self.assertNotIn("publishPluginPublicationToHangar -P", jobs(PUBLISHER)["publish-github"] + jobs(PUBLISHER)["publish-modrinth"])
        self.assertNotIn("gh release create", jobs(PUBLISHER)["publish-modrinth"] + jobs(PUBLISHER)["publish-hangar"])

    def test_modrinth_visibility_retries_only_after_its_upload(self):
        job = jobs(PUBLISHER)["publish-modrinth"]
        step = steps(job)[step_index(job, "Verify Modrinth consumer download")]
        self.assertIn("steps.modrinth.outputs.state == 'absent'", step)
        self.assertIn("--modrinth-visibility-retries", step)
        self.assertIn("--require-public-project", steps(job)[step_index(job, "Check Modrinth destination")])

    def test_hangar_page_sync_only_for_newest_version_on_channel(self):
        job = jobs(PUBLISHER)["publish-hangar"]
        decide = step_index(job, "Check this is the newest public version on its channel")
        sync = step_index(job, "Sync Hangar resource page")
        self.assertLess(step_index(job, "Verify Hangar receipt before page sync"), decide)
        self.assertEqual(decide + 1, sync)
        self.assertIn("release-publisher.py hangar-sync", steps(job)[decide])
        self.assertIn("if: steps.page.outputs.sync == 'true'", steps(job)[sync])


class CandidateWorkflowTest(unittest.TestCase):
    def test_candidate_workflow_publishes_nothing(self):
        self.assertIn("on:\n  push:\n    tags: ['v*.*.*']\n", CANDIDATE)
        for text in ("secrets.", "environment:", "contents: write", "mc-publish", "gh release create",
                     "ToHangar"):
            self.assertNotIn(text, CANDIDATE)

    def test_candidate_and_evidence_are_retained_for_ninety_days(self):
        for artifact in ("release-candidate-", "release-evidence-", "smoke-${{ matrix.platform }}"):
            block = CANDIDATE.split(f"name: {artifact}", 1)[1].split("\n      - ", 1)[0]
            self.assertIn("retention-days: 90", block, artifact)

    def test_candidate_summary_names_each_owner_inventory(self):
        evidence = jobs(CANDIDATE)["evidence"]
        for text in ("Modrinth: sign in", "draft, unlisted, scheduled and archived",
                     "Hangar: sign in", "hidden and soft-deleted", "deleted-version view",
                     "candidate_run_id"):
            self.assertIn(text, evidence)


class NoStoredAttestationTest(unittest.TestCase):
    def test_no_workflow_or_script_reads_a_stored_attestation(self):
        for path in [*WORKFLOWS.glob("*.yml"), *(ROOT / "scripts").glob("*.py"), *(ROOT / "scripts").glob("*.sh")]:
            self.assertNotIn("RELEASE_ABSENCE_RECONCILIATION", path.read_text(), path.name)
            self.assertNotIn("_absence_confirmed: ${{ vars.", path.read_text(), path.name)


if __name__ == "__main__":
    unittest.main()
