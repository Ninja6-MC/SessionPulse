#!/usr/bin/env python3
"""Verify the only deployable CI JAR carries its snapshot version."""

import sys
from pathlib import Path
from zipfile import ZipFile


NON_DEPLOYABLE_SUFFIXES = ("-thin.jar", "-sources.jar", "-javadoc.jar")


def verify(directory: Path, version: str):
    expected = f"SessionPulse-{version}.jar"
    deployable = sorted(
        path.name for path in directory.glob("*.jar")
        if not path.name.endswith(NON_DEPLOYABLE_SUFFIXES)
    )
    if deployable != [expected]:
        raise ValueError(f"Expected only {expected} in {directory}; found: {deployable}")

    with ZipFile(directory / expected) as archive:
        if archive.namelist().count("plugin.yml") != 1:
            raise ValueError(f"Expected one plugin.yml in {expected}")
        lines = archive.read("plugin.yml").decode("utf-8").splitlines()
    values = [line.removeprefix("version: '").removesuffix("'")
              for line in lines if line.startswith("version: '") and line.endswith("'")]
    if values != [version]:
        raise ValueError(f"Expected plugin.yml version {version!r} in {expected}; found: {values}")


if __name__ == "__main__":
    try:
        verify(Path("build/libs"), sys.argv[1])
    except (IndexError, OSError, ValueError) as error:
        sys.exit(str(error))
    print(f"Verified SessionPulse-{sys.argv[1]}.jar and embedded plugin.yml version")
