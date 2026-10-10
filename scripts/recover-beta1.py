#!/usr/bin/env python3
"""Verify provenance of the already published beta.1 before page-only recovery."""

import argparse
import json
import os
import subprocess
from importlib.machinery import SourceFileLoader
from pathlib import Path


destination = SourceFileLoader("destination", str(Path(__file__).with_name("release-destination.py"))).load_module()
REPOSITORY = "Ninja6-MC/SessionPulse"
CONTROL_TAG = "v-recover-sessionpulse-beta1"
TAG = "v0.2.0-beta.1"
SOURCE = "311f732e427ecd54f4190518a696aaac59590eb6"
RUN = "38048881901"
JAR_SHA256 = "6d1c8b47fd33b235193eda642f007e38a145810f0c72dea06e0749c2ff264a07"
ARTIFACTS = {f"release-candidate-{RUN}-1": 11668199275,
             f"release-evidence-{RUN}-1": 11668299901}
JOBS = {"Build Release Candidate", "Verify Candidate Evidence",
        "Candidate Smoke (paper)", "Candidate Smoke (folia)", "Candidate Smoke (spigot)",
        "Candidate Smoke (paper 26.3)", "Candidate Smoke (spigot 26.3)"}


def command(*args):
    return subprocess.check_output(args, text=True).strip()


def api(path):
    return json.loads(command("gh", "api", f"repos/{REPOSITORY}/{path}"))


def check_run(run, jobs, artifacts):
    expected = {"id": int(RUN), "event": "push", "head_branch": TAG, "head_sha": SOURCE,
                "path": ".github/workflows/release.yml", "run_attempt": 1, "status": "completed"}
    for key, value in expected.items():
        if run.get(key) != value:
            raise ValueError(f"Original release run has mismatched {key}")
    # Publication failed after upload; candidate and evidence must independently pass.
    for name in JOBS:
        matching = [job for job in jobs if job.get("name") == name]
        if len(matching) != 1 or matching[0].get("conclusion") != "success":
            raise ValueError(f"Original candidate job did not pass: {name}")
    for name, artifact_id in ARTIFACTS.items():
        matching = [artifact for artifact in artifacts if artifact.get("name") == name]
        if (len(matching) != 1 or matching[0].get("id") != artifact_id
                or matching[0].get("expired") is not False
                or (matching[0].get("workflow_run") or {}).get("head_sha") != SOURCE):
            raise ValueError(f"Retained artifact provenance differs: {name}")


def check_context():
    expected = {"GITHUB_REPOSITORY": REPOSITORY, "GITHUB_EVENT_NAME": "workflow_dispatch",
                "GITHUB_REF": f"refs/tags/{CONTROL_TAG}",
                "GITHUB_WORKFLOW_REF": f"{REPOSITORY}/.github/workflows/recover-beta1.yml@refs/tags/{CONTROL_TAG}"}
    for key, value in expected.items():
        if os.environ.get(key) != value:
            raise ValueError(f"Recovery requires fixed control-tag context: {key}")
    sha = os.environ.get("GITHUB_SHA")
    if not sha or command("git", "rev-parse", "HEAD") != sha or destination.tag_sha(CONTROL_TAG) != sha:
        raise ValueError("Recovery control tag or checkout changed")
    if destination.tag_sha(TAG) != SOURCE:
        raise ValueError("Published beta.1 tag changed")
    command("git", "fetch", "--no-tags", "origin", "+refs/heads/main:refs/remotes/origin/main")
    for commit in (sha, SOURCE):
        subprocess.run(["git", "merge-base", "--is-ancestor", commit, "origin/main"], check=True)


def verify(directory, evidence):
    check_context()
    run = api(f"actions/runs/{RUN}/attempts/1")
    jobs = api(f"actions/runs/{RUN}/attempts/1/jobs?per_page=100")
    artifacts = api(f"actions/runs/{RUN}/artifacts?per_page=100")
    if jobs.get("total_count", 101) > 100 or artifacts.get("total_count", 101) > 100:
        raise ValueError("Original run inventory requires pagination")
    check_run(run, jobs["jobs"], artifacts["artifacts"])
    args = argparse.Namespace(directory=directory, evidence=evidence, tag=TAG,
                              sha=SOURCE, run_id=RUN, attempt="1")
    destination.candidate.check_evidence(args)
    manifest = destination.candidate.verify(args)
    if manifest["files"]["SessionPulse-0.2.0-beta.1.jar"] != JAR_SHA256:
        raise ValueError("Retained beta.1 JAR differs from the published candidate")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--directory", default="candidate")
    parser.add_argument("--evidence", default="evidence.json")
    args = parser.parse_args()
    verify(args.directory, args.evidence)
