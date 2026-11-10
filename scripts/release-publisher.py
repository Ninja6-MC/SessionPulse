#!/usr/bin/env python3
"""Guards for the dispatched release publisher (.github/workflows/release.yml).

destinations  parse the dispatch's comma-separated destination list
provenance    accept only a successful candidate run for this tag and pin its attempt
guard         refuse a true absence attestation on a later attempt or beside another publisher run
hangar-sync   decide whether this version may overwrite the Hangar project page
"""

import argparse
import json
import os
import re
import subprocess
import sys
import urllib.parse
from importlib.machinery import SourceFileLoader
from pathlib import Path

destination = SourceFileLoader("destination", str(Path(__file__).with_name("release-destination.py"))).load_module()
candidate = destination.candidate

DESTINATIONS = ("github", "modrinth", "hangar")
CANDIDATE_WORKFLOW = ".github/workflows/release-candidate.yml"
PUBLISHER_WORKFLOW = "release.yml"
RANK = {"alpha": 0, "beta": 1, "rc": 2, None: 3}


def parse_destinations(value):
    names = [name.strip() for name in value.split(",")]
    if not value.strip() or any(not name for name in names):
        raise ValueError("Destinations must be a comma-separated list such as github,modrinth,hangar")
    unknown = sorted(set(names) - set(DESTINATIONS))
    if unknown:
        raise ValueError(f"Unknown destination {', '.join(unknown)}; expected any of {', '.join(DESTINATIONS)}")
    if len(set(names)) != len(names):
        raise ValueError("A destination is listed twice")
    return [name for name in DESTINATIONS if name in names]


def check_candidate_run(run, tag, tag_commit):
    """The run must be this repository's candidate workflow, built from the pushed tag."""
    checks = (
        ("workflow", run.get("path"), CANDIDATE_WORKFLOW),
        ("event", run.get("event"), "push"),
        ("tag", run.get("head_branch"), tag),
        ("commit", run.get("head_sha"), tag_commit),
        ("status", run.get("status"), "completed"),
        ("conclusion", run.get("conclusion"), "success"),
    )
    for field, actual, expected in checks:
        if actual != expected:
            raise ValueError(f"Candidate run {run.get('id')} has {field} {actual!r}, expected {expected!r}")


def pin_attempt(artifacts, run_id):
    """Pin the newest candidate attempt; its evidence must come from the same attempt."""
    pattern = re.compile(rf"release-candidate-{re.escape(str(run_id))}-([1-9][0-9]*)\Z")
    attempts = sorted(int(match.group(1)) for artifact in artifacts
                      if (match := pattern.fullmatch(artifact.get("name", ""))))
    if not attempts:
        raise ValueError(f"Candidate run {run_id} retained no release candidate artifact")
    attempt = attempts[-1]
    for kind in ("candidate", "evidence"):
        name = f"release-{kind}-{run_id}-{attempt}"
        matches = [artifact for artifact in artifacts if artifact.get("name") == name]
        if len(matches) != 1:
            raise ValueError(f"Candidate run {run_id} has no single {name} artifact")
        if matches[0].get("expired") is not False:
            raise ValueError(f"{name} has expired. Nothing is rebuilt for this tag: record each destination "
                             "still missing as not published in RELEASE_PROCESS.md; the next release reaches it")
    return attempt


def check_attempt(attempt, attested):
    if attested and str(attempt) != "1":
        raise ValueError(f"A true absence attestation is valid only on attempt 1, not attempt {attempt}. "
                         "Re-inspect the owner inventory and start a new dispatch")


def check_concurrent(runs, current_id, created_at, tag):
    """Another publisher run may have written a version the attestation did not see."""
    for run in runs:
        if str(run.get("id")) == str(current_id) or run.get("head_branch") != tag:
            continue
        if run.get("status") != "completed":
            # The concurrency group holds a later dispatch until this run ends,
            # so it cannot have written anything yet; it refuses itself instead.
            if (run.get("created_at") or "") > created_at:
                continue
            raise ValueError(f"Publisher run {run.get('id')} for {tag} is {run.get('status')}; "
                             "a true absence attestation cannot be used beside it")
        if (run.get("updated_at") or "") > created_at:
            raise ValueError(f"Publisher run {run.get('id')} for {tag} finished after this run was created; "
                             "re-inspect the owner inventory and start a new dispatch")


def version_key(version):
    match = candidate.TAG.fullmatch("v" + version)
    if not match:
        return None
    major, minor, patch = (int(part) for part in match.group(1).split("."))
    return major, minor, patch, RANK[match.group(2)], int(match.group(3) or 0)


def is_newest(version, published):
    own = version_key(version)
    if own is None:
        raise ValueError(f"Cannot order version {version}")
    return all(key <= own for key in map(version_key, published) if key is not None)


def gh_json(path):
    process = subprocess.run(["gh", "api", path], capture_output=True, text=True)
    if process.returncode:
        raise ValueError(f"GitHub API {path} failed: {process.stderr.strip()}")
    return json.loads(process.stdout)


def write_outputs(values):
    output = os.environ.get("GITHUB_OUTPUT")
    if output:
        with open(output, "a", encoding="utf-8") as stream:
            for key, value in values.items():
                stream.write(f"{key}={value}\n")


def provenance(args):
    if not re.fullmatch(r"[1-9][0-9]*", args.run_id):
        raise ValueError("candidate_run_id must be a workflow run ID")
    tag_commit = destination.tag_sha(args.tag)
    check_candidate_run(gh_json(f"repos/{args.repo}/actions/runs/{args.run_id}"), args.tag, tag_commit)
    artifacts = gh_json(f"repos/{args.repo}/actions/runs/{args.run_id}/artifacts?per_page=100")
    attempt = pin_attempt(artifacts.get("artifacts", []), args.run_id)
    print(f"Candidate run {args.run_id} attempt {attempt} built {args.tag} at {tag_commit}")
    write_outputs({"attempt": attempt, "sha": tag_commit})


def guard(args):
    attested = args.attested == "true"
    if args.check_attempt:
        check_attempt(args.attempt, attested)
    if not attested:
        print("No absence attestation in use")
        return
    created_at = gh_json(f"repos/{args.repo}/actions/runs/{args.run_id}")["created_at"]
    query = urllib.parse.urlencode({"branch": args.tag, "per_page": 100})
    runs = gh_json(f"repos/{args.repo}/actions/workflows/{PUBLISHER_WORKFLOW}/runs?{query}")
    check_concurrent(runs.get("workflow_runs", []), args.run_id, created_at, args.tag)
    print(f"No other publisher run for {args.tag} overlaps run {args.run_id}")


def hangar_sync(args):
    published, offset = [], 0
    while True:
        query = urllib.parse.urlencode({"channel": args.channel, "limit": 25, "offset": offset})
        status, page = destination.request(
            f"https://hangar.papermc.io/api/v1/projects/{urllib.parse.quote(args.project, safe='')}/versions?{query}")
        if status != 200 or not isinstance(page, dict) or not isinstance(page.get("result"), list):
            raise ValueError("Cannot list public Hangar versions")
        published += [item.get("name", "") for item in page["result"] if isinstance(item, dict)
                      and item.get("visibility") == "public"
                      and (item.get("channel") or {}).get("name") == args.channel]
        offset += len(page["result"])
        if not page["result"] or offset >= (page.get("pagination") or {}).get("count", 0):
            break
    newest = is_newest(args.version, published)
    if newest:
        print(f"{args.version} is the newest public {args.channel} version; syncing the project page")
    else:
        print(f"Skipping Hangar page sync: a newer public {args.channel} version than {args.version} exists")
    write_outputs({"sync": str(newest).lower()})


def destinations(args):
    selected = parse_destinations(args.value)
    print("Destinations: " + ", ".join(selected))
    write_outputs({name: str(name in selected).lower() for name in DESTINATIONS})


def main():
    parser = argparse.ArgumentParser()
    commands = parser.add_subparsers(dest="command", required=True)
    parse = commands.add_parser("destinations")
    parse.add_argument("--value", required=True)
    parse.set_defaults(handler=destinations)
    origin = commands.add_parser("provenance")
    origin.add_argument("--repo", required=True)
    origin.add_argument("--tag", required=True)
    origin.add_argument("--run-id", required=True)
    origin.set_defaults(handler=provenance)
    attestation = commands.add_parser("guard")
    attestation.add_argument("--repo", required=True)
    attestation.add_argument("--tag", required=True)
    attestation.add_argument("--run-id", required=True)
    attestation.add_argument("--attempt", required=True)
    attestation.add_argument("--attested", choices=("true", "false"), required=True)
    attestation.add_argument("--check-attempt", action="store_true")
    attestation.set_defaults(handler=guard)
    page = commands.add_parser("hangar-sync")
    page.add_argument("--project", required=True)
    page.add_argument("--version", required=True)
    page.add_argument("--channel", required=True)
    page.set_defaults(handler=hangar_sync)
    args = parser.parse_args()
    args.handler(args)


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, KeyError, json.JSONDecodeError) as error:
        sys.exit(str(error))
