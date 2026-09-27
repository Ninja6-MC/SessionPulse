from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "snapshot-version.py"


class SnapshotVersionTest(unittest.TestCase):
    def setUp(self):
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary_directory.cleanup)
        self.repo = Path(self.temporary_directory.name)
        self.git("init", "-q")
        self.git("config", "user.name", "Snapshot Test")
        self.git("config", "user.email", "snapshot@example.invalid")
        self.commit()

    def git(self, *args):
        return subprocess.check_output(("git", *args), cwd=self.repo, text=True).strip()

    def commit(self):
        self.git("commit", "-q", "--allow-empty", "-m", "test")

    def version(self):
        return subprocess.check_output((sys.executable, str(SCRIPT)), cwd=self.repo, text=True).strip()

    def test_exact_valid_release_tag_still_includes_commit(self):
        self.git("tag", "v0.1.0-beta.1")
        self.assertEqual(self.version(), f"0.1.0-beta.1-0-g{self.git('rev-parse', '--short', 'HEAD')}")

    def test_commit_after_valid_release_tag(self):
        self.git("tag", "v0.1.0-rc.1")
        self.commit()
        self.assertEqual(self.version(), f"0.1.0-rc.1-1-g{self.git('rev-parse', '--short', 'HEAD')}")

    def test_nearer_invalid_tags_are_ignored(self):
        self.git("tag", "v0.1.0-alpha.1")
        self.commit()
        self.git("tag", "v9.9.9-preview.1")
        self.git("tag", "v2.0.0-rc.1-extra")
        self.git("tag", "v3.0")
        self.commit()
        self.assertEqual(self.version(), f"0.1.0-alpha.1-2-g{self.git('rev-parse', '--short', 'HEAD')}")

    def test_valid_unreachable_tag_is_ignored(self):
        build_commit = self.git("rev-parse", "HEAD")
        self.git("checkout", "-q", "--orphan", "other")
        self.commit()
        self.git("tag", "v9.9.9")
        self.git("checkout", "-q", "--detach", build_commit)
        self.assertEqual(self.version(), f"0.0.0-SNAPSHOT-g{self.git('rev-parse', '--short', 'HEAD')}")

    def test_no_valid_release_tag_uses_hash_fallback(self):
        self.git("tag", "v1.2.3-preview.1")
        self.git("tag", "v1.2.3.4")
        self.assertEqual(self.version(), f"0.0.0-SNAPSHOT-g{self.git('rev-parse', '--short', 'HEAD')}")


if __name__ == "__main__":
    unittest.main()
