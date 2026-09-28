import importlib.util
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "check-hangar-task-graph.py"
SPEC = importlib.util.spec_from_file_location("hangar_task_graph", SCRIPT)
module = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(module)


class HangarTaskGraphTest(unittest.TestCase):
    def test_publish_file_without_build_task(self):
        module.verify(":publishPluginPublicationToHangar SKIPPED\nBUILD SUCCESSFUL in 2s\n")

    def test_rejects_shadow_jar_dependency(self):
        with self.assertRaisesRegex(ValueError, "shadowJar"):
            module.verify(":shadowJar SKIPPED\n:publishPluginPublicationToHangar SKIPPED\n")

    def test_rejects_other_artifact_modifying_task(self):
        with self.assertRaisesRegex(ValueError, "processResources"):
            module.verify(":processResources SKIPPED\n:publishPluginPublicationToHangar SKIPPED\n")


if __name__ == "__main__":
    unittest.main()
