import unittest
from pathlib import Path


WORKFLOW = Path(__file__).resolve().parents[2] / ".github" / "workflows" / "release.yml"


class ReleasePreflightOrderTest(unittest.TestCase):
    def test_destination_preflight_precedes_first_public_write(self):
        workflow = WORKFLOW.read_text()
        publish = workflow[workflow.index("  publish:\n"):]
        first_write = publish.index("      - name: Publish GitHub release if absent")
        for step in (
            "Require environment publishing tokens",
            "Preflight Modrinth before publication",
            "Preflight Hangar before publication",
            "Validate Hangar task graph before publication",
            "Check GitHub destination",
        ):
            self.assertLess(publish.index(f"      - name: {step}"), first_write, step)
        self.assertIn("--require-public-project", publish[:first_write])
        self.assertIn("--require-modrinth-upload-access", publish[:first_write])
        self.assertIn("--allow-sessionpulse-unlisted-beta", publish[:first_write])
        final_modrinth = publish.split("      - name: Verify Modrinth consumer download", 1)[1].split("      - name: Check Hangar destination", 1)[0]
        self.assertIn("steps.modrinth.outputs.state == 'absent'", final_modrinth)
        self.assertIn("--modrinth-visibility-retries", final_modrinth)
        self.assertIn("--require-hangar-target", publish[:first_write])
        self.assertIn("--require-hangar-upload-access", publish[:first_write])
        self.assertLess(first_write, publish.index("      - name: Publish Modrinth if absent"))
        self.assertLess(first_write, publish.index("      - name: Publish Hangar if absent"))

    def test_candidate_summary_names_each_owner_inventory(self):
        evidence = WORKFLOW.read_text().split("  evidence:\n", 1)[1].split("  publish:\n", 1)[0]
        for text in ("Modrinth: sign in", "draft, unlisted, scheduled and archived",
                     "Hangar: sign in", "hidden and soft-deleted", "deleted-version view"):
            self.assertIn(text, evidence)


if __name__ == "__main__":
    unittest.main()
