import argparse
import importlib.util
import io
import itertools
import re
import shutil
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts" / "release-description.py"
SPEC = importlib.util.spec_from_file_location("release_description", SCRIPT)
description = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(description)

PUBLISHER = ROOT / ".github" / "workflows" / "release.yml"
REPOSITORY = "Ninja6-MC/SessionPulse"
FILES = {"v0.2.0-beta.2": "Beta two page\n", "v0.2.0": "Stable page\n", "v0.3.0-beta.1": "Newer beta page\n"}


SCRATCH = tempfile.mkdtemp(prefix="release-description-")
COUNTER = itertools.count()


def tearDownModule():
    shutil.rmtree(SCRATCH, ignore_errors=True)


class Run:
    """The pieces of the world the commands read: public versions, the body, and the files by tag."""

    def __init__(self, tag, versions, body, owners=None):
        self.tag = tag
        self.versions = versions
        self.body = body
        # confirm reads the owner twice per pass; a list models an owner that changes while it waits.
        self.owners = list(owners) if owners else None
        self.output = Path(SCRATCH, f"{next(COUNTER)}.output")
        self.summary = Path(SCRATCH, f"{next(COUNTER)}.summary")
        self.environment_checks = 0

    def owner(self, project):
        if self.owners:
            return self.owners.pop(0) if len(self.owners) > 1 else self.owners[0]
        owner = description.publisher.shared_state_owner(self.versions)
        return f"v{owner}" if owner else None

    def args(self, action="check"):
        return argparse.Namespace(action=action, repository=REPOSITORY, tag=self.tag, project="sessionpulse",
                                  output=str(self.output))

    def execute(self, action):
        def environment(repository):
            self.environment_checks += 1

        patches = (patch.object(description, "public_owner", self.owner),
                   patch.object(description, "public_body", lambda project: self.body),
                   patch.object(description, "source_body", lambda tag: FILES[tag].strip()),
                   patch.object(description, "check_environment", environment),
                   patch.dict("os.environ", {"GITHUB_STEP_SUMMARY": str(self.summary)}))
        with redirect_stdout(io.StringIO()):
            for item in patches:
                item.start()
            try:
                getattr(description, action)(self.args(action))
            finally:
                patch.stopall()

    def pasted(self):
        return self.output.read_text() if self.output.exists() else ""

    def summarized(self):
        return self.summary.read_text() if self.summary.exists() else ""


class NormalizeTest(unittest.TestCase):
    def test_ignores_only_line_endings_and_trailing_whitespace(self):
        self.assertTrue(description.same("a  \r\nb\t\r\n\r\n", "a\nb"))
        self.assertTrue(description.same("a\rb", "a\nb\n"))
        self.assertFalse(description.same("a b", "a  b"))
        self.assertFalse(description.same(" a", "a"))
        self.assertFalse(description.same("a\n\nb", "a\nb"))


class CheckTest(unittest.TestCase):
    def test_matching_body_ends_the_step(self):
        run = Run("v0.2.0-beta.2", ["0.2.0-beta.2"], "Beta two page")
        run.execute("check")
        self.assertIn("paste_needed=false", run.pasted())
        self.assertEqual(run.environment_checks, 0)
        self.assertIn("already matches v0.2.0-beta.2", run.summarized())

    def test_differing_body_asks_for_a_paste_naming_file_and_tag(self):
        run = Run("v0.2.0-beta.2", ["0.2.0-beta.2"], "An older page")
        run.execute("check")
        self.assertIn("paste_needed=true", run.pasted())
        self.assertEqual(run.environment_checks, 1)
        text = run.summarized()
        self.assertIn("docs/store-description.md at v0.2.0-beta.2", text)
        self.assertIn(f"https://github.com/{REPOSITORY}/blob/v0.2.0-beta.2/docs/store-description.md", text)
        self.assertIn("newer owner found at approval time replaces this tag", text)

    def test_whitespace_only_difference_is_a_match(self):
        run = Run("v0.2.0-beta.2", ["0.2.0-beta.2"], "Beta two page  \r\n\r\n")
        run.execute("check")
        self.assertIn("paste_needed=false", run.pasted())

    def test_a_tag_that_does_not_own_the_page_is_skipped(self):
        for tag, versions in (("v0.2.0-beta.2", ["0.2.0-beta.2", "0.2.0"]),
                              ("v0.2.0", ["0.2.0", "0.3.0-beta.1", "0.2.1"]),
                              ("v0.2.0-beta.2", [])):
            with self.subTest(tag=tag):
                run = Run(tag, versions, "Not the file at all")
                run.execute("check")
                self.assertIn("paste_needed=false", run.pasted())
                self.assertEqual(run.environment_checks, 0)
                self.assertIn(f"skipped for {tag}", run.summarized())

    def test_a_pre_release_does_not_take_the_page_from_a_stable_version(self):
        run = Run("v0.3.0-beta.1", ["0.2.0", "0.3.0-beta.1"], "Stable page")
        run.execute("check")
        self.assertIn("paste_needed=false", run.pasted())
        self.assertIn("the page owner is v0.2.0", run.summarized())


class ConfirmTest(unittest.TestCase):
    def test_matching_body_passes(self):
        run = Run("v0.2.0-beta.2", ["0.2.0-beta.2"], "Beta two page \r\n")
        run.execute("confirm")
        self.assertIn("matches", run.summarized())
        self.assertNotIn("superseded", run.summarized())

    def test_differing_body_fails_with_the_file_to_paste(self):
        run = Run("v0.2.0-beta.2", ["0.2.0-beta.2"], "An older page")
        with self.assertRaisesRegex(ValueError, "paste .*v0.2.0-beta.2/docs/store-description.md"):
            run.execute("confirm")
        self.assertIn("```diff", run.summarized())
        self.assertIn("-Beta two page", run.summarized())
        self.assertIn("+An older page", run.summarized())

    def test_whitespace_only_difference_passes(self):
        run = Run("v0.2.0-beta.2", ["0.2.0-beta.2"], "Beta two page\t\n\n\n")
        run.execute("confirm")

    def test_a_tag_that_does_not_own_the_page_is_compared_with_the_owner_file(self):
        stale = Run("v0.2.0-beta.2", ["0.2.0-beta.2", "0.2.0"], "Beta two page")
        with self.assertRaisesRegex(ValueError, "v0.2.0/docs/store-description.md"):
            stale.execute("confirm")
        self.assertIn("v0.2.0-beta.2 was superseded: the Modrinth page owner is now v0.2.0", stale.summarized())
        current = Run("v0.2.0-beta.2", ["0.2.0-beta.2", "0.2.0"], "Stable page")
        current.execute("confirm")
        self.assertIn("was superseded", current.summarized())

    def test_a_tag_superseded_while_waiting_is_judged_against_the_new_owner(self):
        # check saw v0.2.0-beta.2 as owner and the body matched its file; by approval v0.2.0 owns the page.
        run = Run("v0.2.0-beta.2", [], "Beta two page")
        run.owners = ["v0.2.0"]
        with self.assertRaisesRegex(ValueError, "does not match docs/store-description.md at v0.2.0"):
            run.execute("confirm")
        self.assertIn("superseded: the Modrinth page owner is now v0.2.0", run.summarized())

    def test_an_owner_that_keeps_changing_is_not_trusted(self):
        run = Run("v0.2.0-beta.2", [], "Beta two page")
        owners = iter(["v0.2.0-beta.2", "v0.2.0", "v0.2.0", "v0.3.0-beta.1"] * 3)
        run.owner = lambda project: next(owners)
        with self.assertRaisesRegex(ValueError, "kept changing"):
            run.execute("confirm")

    def test_no_public_owner_fails(self):
        run = Run("v0.2.0-beta.2", [], "Beta two page")
        run.owner = lambda project: None
        with self.assertRaisesRegex(ValueError, "no public version"):
            run.execute("confirm")


class OwnerLookupTest(unittest.TestCase):
    def owner(self, versions):
        with patch.object(description.destination, "request", return_value=(200, versions)):
            return description.public_owner("sessionpulse")

    def test_only_listed_versions_count(self):
        versions = [{"version_number": "0.2.0-beta.2", "status": "listed"},
                    {"version_number": "0.3.0", "status": "draft"},
                    {"version_number": "0.4.0", "status": "unlisted"},
                    {"version_number": "0.5.0", "status": "scheduled"}]
        self.assertEqual(self.owner(versions), "v0.2.0-beta.2")

    def test_no_listed_version_has_no_owner(self):
        self.assertIsNone(self.owner([{"version_number": "0.3.0", "status": "draft"}]))

    def test_an_unreadable_list_is_an_error(self):
        for response in ((404, None), (200, {})):
            with patch.object(description.destination, "request", return_value=response):
                with self.assertRaisesRegex(ValueError, "Cannot list"):
                    description.public_owner("sessionpulse")


class SourceTest(unittest.TestCase):
    def source(self, text, tag="v0.2.0-beta.2", remote="a" * 40):
        outputs = {"rev-parse": "a" * 40 + "\n", "show": text}
        with patch.object(description, "run", side_effect=lambda *command: outputs[command[1]]), \
                patch.object(description.destination, "tag_sha", return_value=remote):
            return description.source_body(tag)

    def test_drops_the_generated_comment(self):
        self.assertEqual(self.source("<!-- Generated.\n     Paste below. -->\n\nBody\n\n"), "Body")

    def test_rejects_a_file_without_the_comment_a_moved_tag_and_a_non_release_tag(self):
        with self.assertRaisesRegex(ValueError, "lacks its generated comment"):
            self.source("Body only\n")
        with self.assertRaisesRegex(ValueError, "differs between"):
            self.source("<!-- x -->\nBody", remote="b" * 40)
        with self.assertRaisesRegex(ValueError, "not a release tag"):
            self.source("<!-- x -->\nBody", tag="main")


class EnvironmentTest(unittest.TestCase):
    GOOD = {"protection_rules": [{"type": "required_reviewers", "reviewers": [{"type": "User", "reviewer": {"id": 7}}]}],
            "deployment_branch_policy": {"custom_branch_policies": True, "protected_branches": False},
            "can_admins_bypass": False}

    def check(self, environment, policies=None):
        responses = [environment, {"branch_policies": policies if policies is not None else [{"type": "tag", "name": "v*"}]}]
        with patch.object(description, "api", side_effect=responses):
            description.check_environment(REPOSITORY)

    def test_accepts_a_reviewer_gated_environment_limited_to_v_tags(self):
        self.check(self.GOOD)

    def test_refuses_to_request_a_paste_the_gate_would_not_hold(self):
        for environment, policies in (({**self.GOOD, "protection_rules": []}, None),
                                      ({**self.GOOD, "deployment_branch_policy": None}, None),
                                      ({**self.GOOD, "can_admins_bypass": True}, None),
                                      ({key: value for key, value in self.GOOD.items() if key != "can_admins_bypass"}, None),
                                      ({**self.GOOD, "can_admins_bypass": None}, None),
                                      (self.GOOD, []),
                                      (self.GOOD, [{"type": "branch", "name": "main"}]),
                                      (self.GOOD, [{"type": "tag", "name": "v*"}, {"type": "tag", "name": "*"}])):
            with self.subTest(environment=environment, policies=policies):
                with self.assertRaisesRegex(ValueError, "modrinth-description"):
                    self.check(environment, policies)


class WorkflowTest(unittest.TestCase):
    """The jobs must exist, hold no secret and sit outside any shared-state group."""

    @classmethod
    def setUpClass(cls):
        text = PUBLISHER.read_text()
        cls.jobs = {name: body for name, body in re.findall(r"^  ([a-z-]+):\n((?:(?:    .*)?\n)+)", text, re.M)}

    def test_check_job_has_no_environment_secret_or_concurrency_group(self):
        job = self.jobs["check-modrinth-description"]
        self.assertNotIn("environment:", job)
        self.assertNotIn("secrets.", job)
        self.assertNotIn("concurrency", job)

    def test_confirm_job_waits_on_its_own_environment_without_secrets(self):
        job = self.jobs["confirm-modrinth-description"]
        self.assertRegex(job, r"environment: modrinth-description\n")
        self.assertNotIn("secrets.", job)
        self.assertNotIn("concurrency", job)
        self.assertIn("paste_needed == 'true'", job)

    def test_confirm_environment_is_not_the_release_gate(self):
        for name, job in self.jobs.items():
            if name.startswith("publish-"):
                self.assertNotIn("modrinth-description", job)


if __name__ == "__main__":
    unittest.main()
