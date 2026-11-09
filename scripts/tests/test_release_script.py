import re
import shutil
import subprocess
import tempfile
import textwrap
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts" / "release.sh"
WORKFLOW = ROOT / ".github" / "workflows" / "release.yml"
# shutil.which searches PATH only. A bare "bash" handed to subprocess on Windows is found
# in System32 first, which is WSL and cannot see this checkout's paths.
BASH = shutil.which("bash")

CHANGELOG = textwrap.dedent("""\
    # Changelog

    ## [Unreleased]

    - Unreleased entry.

    ## [1.1.0-beta.2] - 2026-10-01

    - Beta two entry.

    ## [1.1.0]

    - One point one entry.

    ## [1.0.0] - 2026-09-01

    - One point oh entry.

    [Unreleased]: https://example.invalid/compare
    [1.0.0]: https://example.invalid/1.0.0
    """)


def awk_program(text):
    match = re.search(r"extract\(\) \{\n(.*?)\n\s*\}\n", text, re.S)
    return [line.strip() for line in match.group(1).splitlines()]


class ReleaseScriptSourceTest(unittest.TestCase):
    def test_extractor_matches_release_workflow(self):
        self.assertEqual(awk_program(SCRIPT.read_text()), awk_program(WORKFLOW.read_text()))

    def test_tag_grammar_matches_release_workflow(self):
        pattern = r'"\$TAG" =~ (\^v\S+\$) \]\]'
        workflow = re.search(pattern, WORKFLOW.read_text()).group(1)
        script = re.search(pattern.replace("TAG", "tag"), SCRIPT.read_text()).group(1)
        self.assertEqual(script, workflow)

    def test_pushes_one_tag_by_name(self):
        text = SCRIPT.read_text()
        self.assertIn('git push origin "refs/tags/$tag"', text)
        self.assertNotRegex(text, r"git push[^\n]*--tags")

    def test_checks_origin_before_local_tag(self):
        # Step 5 fetches origin's tags, so a released tag is also local. Checking the
        # local tag first would suggest `git tag -d` on a published tag.
        text = SCRIPT.read_text()
        self.assertLess(text.index('git ls-remote --exit-code --tags origin "refs/tags/$tag"'),
                        text.index('git rev-parse -q --verify "refs/tags/$tag"'))


@unittest.skipIf(BASH is None, "bash is not available")
class ReleaseScriptRulesTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        Path(self.tmp.name, "CHANGELOG.md").write_text(CHANGELOG, newline="\n")

    def run_function(self, call):
        script = SCRIPT.as_posix()
        return subprocess.run(
            [BASH, "-c", f'source "{script}" && {call}'],
            cwd=self.tmp.name, capture_output=True, text=True,
        )

    def check_version(self, version):
        return self.run_function(f'check_version "{version}"').returncode

    def notes_section(self, version):
        result = self.run_function(f'find_notes "{version}" && printf "%s" "$NOTES_SECTION"')
        self.assertEqual(result.returncode, 0, result.stderr)
        return result.stdout

    def test_accepts_versions_release_workflow_publishes(self):
        for version in ("0.2.0-alpha.1", "0.2.0-beta.2", "1.0.0", "1.0.0-rc.1",
                        "1.2.3-alpha.4", "2.0.0-beta.1", "10.0.0"):
            self.assertEqual(self.check_version(version), 0, version)

    def test_rejects_malformed_versions(self):
        for version in ("1.0", "1.0.0-beta", "1.0.0-preview.1", "1.0.0-beta.1+build",
                        "v1.0.0", "1.0.0.0", "1.0.0-BETA.1", ""):
            self.assertNotEqual(self.check_version(version), 0, version)

    def test_rejects_unsuffixed_version_below_one(self):
        for version in ("0.1.0", "0.2.0", "0.0.1"):
            result = self.run_function(f'check_version "{version}"')
            self.assertNotEqual(result.returncode, 0, version)
            self.assertIn("unsuffixed version below 1.0.0", result.stderr)

    def test_rejects_release_candidate_below_one(self):
        result = self.run_function('check_version "0.2.0-rc.1"')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("release candidate below 1.0.0", result.stderr)

    def test_prerelease_prefers_its_own_section(self):
        self.assertEqual(self.notes_section("1.1.0-beta.2"), "1.1.0-beta.2")

    def test_prerelease_falls_back_to_base_version(self):
        self.assertEqual(self.notes_section("1.1.0-beta.1"), "1.1.0")

    def test_prerelease_falls_back_to_unreleased(self):
        self.assertEqual(self.notes_section("1.2.0-alpha.1"), "Unreleased")

    def test_stable_matches_dated_and_undated_headings(self):
        self.assertEqual(self.notes_section("1.0.0"), "1.0.0")
        self.assertEqual(self.notes_section("1.1.0"), "1.1.0")

    def test_stable_never_falls_back(self):
        self.assertEqual(self.notes_section("1.2.0"), "")
        result = self.run_function('check_notes "1.2.0"')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("no '## [1.2.0]' section", result.stderr)

    def test_section_stops_at_link_references(self):
        result = self.run_function('find_notes "1.0.0" && printf "%s" "$NOTES"')
        self.assertEqual(result.stdout.strip(), "- One point oh entry.")

    def test_prerelease_without_any_section_only_warns(self):
        Path(self.tmp.name, "CHANGELOG.md").write_text("# Changelog\n", newline="\n")
        result = self.run_function('check_notes "1.2.0-beta.1"')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("warn", result.stdout)


if __name__ == "__main__":
    unittest.main()
