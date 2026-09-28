#!/usr/bin/env python3
"""Reject Hangar publication if Gradle schedules any other task."""

import re
import sys
from pathlib import Path


def verify(output):
    tasks = re.findall(r"(?m)^:([^\s]+)\s+(?:SKIPPED|UP-TO-DATE|FROM-CACHE|NO-SOURCE)?$", output)
    if tasks != ["publishPluginPublicationToHangar"]:
        raise ValueError(f"Hangar task graph contains unexpected tasks: {tasks}")


if __name__ == "__main__":
    try:
        verify(Path(sys.argv[1]).read_text())
    except (IndexError, OSError, ValueError) as error:
        sys.exit(str(error))
