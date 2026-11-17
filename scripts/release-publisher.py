#!/usr/bin/env python3
"""Guards for the dispatched release publisher (.github/workflows/release.yml).

destinations  parse the dispatch's comma-separated destination list
provenance    accept only a successful candidate run for this tag and pin its attempt
guard         refuse a true absence attestation on a later attempt or beside another publisher run
shared-plan   predict, before approval, which shared state this tag can own after its uploads
shared-write  recheck ownership of the Hangar page or the GitHub latest marker, then write it
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
# Shared state per destination (N6-REL-05). GitHub never marks a pre-release as latest,
# so its marker follows stable versions only and stays unset until the first one.
SHARED_STATE = {"github": "GitHub latest marker", "hangar": "Hangar project page"}
STABLE_ONLY = {"github": True, "hangar": False}


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


def shared_state_owner(published, stable_only=False):
    """The version that owns a destination's shared state (N6-REL-05).

    The highest stable version published there, or the highest pre-release while it has
    no stable version. `published` holds the version numbers public on that destination;
    anything that is not a release version is ignored. With `stable_only`, as for the
    GitHub latest marker, a pre-release never owns. Returns None when none qualifies.
    """
    keyed = [(key, version) for version in published if (key := version_key(version)) is not None]
    stable = [item for item in keyed if item[0][3] == RANK[None]]
    return max(stable if stable or stable_only else keyed, default=(None, None))[1]


def owns_after_upload(version, published, stable_only=False):
    """Whether `version` would own the state once its own upload is public. Plan uses this
    only to skip a job that cannot write; the write itself rechecks the live inventory."""
    return shared_state_owner([*published, version], stable_only) == version


def tag_version(tag):
    if not tag.startswith("v") or version_key(tag[1:]) is None:
        raise ValueError(f"Invalid release tag {tag}")
    return tag[1:]


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


def public_hangar_versions(project):
    """Every public Hangar version of the project, on every channel."""
    published, offset = [], 0
    while True:
        query = urllib.parse.urlencode({"limit": 25, "offset": offset})
        status, page = destination.request(
            f"https://hangar.papermc.io/api/v1/projects/{urllib.parse.quote(project, safe='')}/versions?{query}")
        if status != 200 or not isinstance(page, dict) or not isinstance(page.get("result"), list):
            raise ValueError("Cannot list public Hangar versions")
        count = (page.get("pagination") or {}).get("count")
        if not isinstance(count, int) or (not page["result"] and offset < count):
            raise ValueError("Hangar version pagination is incomplete")
        published += [item.get("name", "") for item in page["result"]
                      if isinstance(item, dict) and item.get("visibility") == "public"]
        offset += len(page["result"])
        if offset >= count:
            return published


def public_github_stable(repo):
    """Tags of the published, non-draft stable GitHub releases, as version numbers."""
    process = subprocess.run(["gh", "api", "--paginate", f"repos/{repo}/releases?per_page=100", "--jq",
                              ".[] | select(.draft | not) | select(.prerelease | not) | .tag_name"],
                             capture_output=True, text=True)
    if process.returncode:
        raise ValueError(f"Cannot list GitHub releases: {process.stderr.strip()}")
    return [tag[1:] for tag in process.stdout.split() if tag.startswith("v")]


def shared_inventory(name, args):
    return public_github_stable(args.repo) if name == "github" else public_hangar_versions(args.project)


def summary(text):
    print(text)
    path = os.environ.get("GITHUB_STEP_SUMMARY")
    if path:
        with open(path, "a", encoding="utf-8") as stream:
            stream.write(text + "\n\n")


def shared_plan(args):
    version = tag_version(args.tag)
    outputs = {}
    for name, label in SHARED_STATE.items():
        if getattr(args, name) != "true":
            summary(f"{label}: not selected in this run")
            outputs[f"shared_{name}"] = "false"
            continue
        published = shared_inventory(name, args)
        owner = shared_state_owner(published, STABLE_ONLY[name])
        owns = owns_after_upload(version, published, STABLE_ONLY[name])
        verdict = "can own it after its upload; the write rechecks under the shared-state lock" if owns else "cannot own it"
        summary(f"{label}: owned now by {'v' + owner if owner else 'no version'}; {args.tag} {verdict}")
        outputs[f"shared_{name}"] = str(owns).lower()
    write_outputs(outputs)


WRITES = {
    "github": lambda args: ["gh", "release", "edit", args.tag, "--repo", args.repo, "--latest"],
    "hangar": lambda args: ["./gradlew", "syncPluginPublicationMainResourcePagePageToHangar",
                            f"-PhangarProject={args.project}", "--no-daemon"],
}


def shared_write(args):
    """Runs in the repository-wide shared-state concurrency group. The inventory is read
    here, immediately before the write, and never taken from plan."""
    version = tag_version(args.tag)
    label = SHARED_STATE[args.destination]
    owner = shared_state_owner(shared_inventory(args.destination, args), STABLE_ONLY[args.destination])
    if owner != version:
        summary(f"Skipping the {label}: {args.tag} does not own it; "
                f"{'v' + owner + ' does' if owner else 'no published version does'}")
        return
    summary(f"{args.tag} owns the {label}; writing it")
    if subprocess.run(WRITES[args.destination](args)).returncode:
        raise ValueError(f"Writing the {label} for {args.tag} failed")


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
    forecast = commands.add_parser("shared-plan")
    forecast.add_argument("--repo", required=True)
    forecast.add_argument("--tag", required=True)
    forecast.add_argument("--project", required=True)
    forecast.add_argument("--github", choices=("true", "false"), required=True)
    forecast.add_argument("--hangar", choices=("true", "false"), required=True)
    forecast.set_defaults(handler=shared_plan)
    write = commands.add_parser("shared-write")
    write.add_argument("--destination", choices=tuple(SHARED_STATE), required=True)
    write.add_argument("--repo", required=True)
    write.add_argument("--tag", required=True)
    write.add_argument("--project", required=True)
    write.set_defaults(handler=shared_write)
    args = parser.parse_args()
    args.handler(args)


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, KeyError, json.JSONDecodeError) as error:
        sys.exit(str(error))
