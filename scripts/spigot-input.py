#!/usr/bin/env python3
"""Verify the immutable inputs used to compile each Spigot gameplay server."""

import argparse
import hashlib
import json
from pathlib import Path


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def load(minecraft):
    if minecraft not in ("1.21.11", "26.3"):
        raise ValueError("Unpinned Spigot Minecraft version")
    path = Path(__file__).parent / "server-inputs" / f"spigot-{minecraft}.json"
    record = json.loads(path.read_text())
    record["input_sha256"] = digest(path)
    return record


def verify(record, metadata=None, buildtools=None):
    if metadata and json.loads(Path(metadata).read_text()) != record["source_metadata"]:
        raise ValueError("Spigot revision metadata differs from pinned source inputs")
    if buildtools and digest(buildtools) != record["buildtools_sha256"]:
        raise ValueError("BuildTools JAR differs from pinned input digest")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--minecraft", required=True)
    parser.add_argument("--field", choices=("revision", "buildtools_build", "buildtools_sha256"))
    parser.add_argument("--metadata")
    parser.add_argument("--buildtools")
    parser.add_argument("--output")
    parser.add_argument("--github-output")
    args = parser.parse_args()
    record = load(args.minecraft)
    verify(record, args.metadata, args.buildtools)
    if args.field:
        print(record[args.field])
    if args.output:
        Path(args.output).write_text(json.dumps(record, sort_keys=True) + "\n")
    if args.github_output:
        with Path(args.github_output).open("a") as out:
            for key in ("revision", "buildtools_build", "buildtools_sha256", "input_sha256"):
                out.write(f"{key}={record[key]}\n")


if __name__ == "__main__":
    main()
