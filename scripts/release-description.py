#!/usr/bin/env python3
"""Confirm that the public Modrinth project body is the committed description (N6-REL-06).

check    compare the public body with docs/store-description.md at the tag, if this tag owns the page
confirm  after the maintainer's paste: re-check the owner, then compare with the file at the owner's tag

Neither command writes anything or holds a secret. The paste into the Modrinth description
editor is the write; see RELEASE_PROCESS.md, Store Descriptions.
"""

import argparse
import difflib
import json
import os
import subprocess
import sys
import urllib.parse
from importlib.machinery import SourceFileLoader
from pathlib import Path

publisher = SourceFileLoader("publisher", str(Path(__file__).with_name("release-publisher.py"))).load_module()
destination = publisher.destination
candidate = publisher.candidate

DESCRIPTION = "docs/store-description.md"
ENVIRONMENT = "modrinth-description"


def normalize(text):
    """Line endings and trailing whitespace are the only differences ignored."""
    lines = text.replace("\r\n", "\n").replace("\r", "\n").split("\n")
    return "\n".join(line.rstrip() for line in lines).rstrip("\n")


def same(expected, actual):
    return normalize(expected) == normalize(actual)


def difference(expected, actual):
    lines = difflib.unified_diff(normalize(expected).splitlines(), normalize(actual).splitlines(),
                                 fromfile="file at owner tag", tofile="Modrinth body", n=1, lineterm="")
    return "\n".join(line[:200] for line in list(lines)[:16])


def run(*command):
    return subprocess.run(command, check=True, text=True, capture_output=True).stdout


def source_body(tag):
    """The description at the tag, without its generated comment, as pasted into Modrinth."""
    if not candidate.TAG.fullmatch(tag):
        raise ValueError(f"{tag} is not a release tag")
    if run("git", "rev-parse", f"refs/tags/{tag}^{{commit}}").strip() != destination.tag_sha(tag):
        raise ValueError(f"Release tag {tag} differs between this checkout and origin")
    text = run("git", "show", f"refs/tags/{tag}:{DESCRIPTION}")
    if not text.startswith("<!--") or "-->" not in text:
        raise ValueError(f"{DESCRIPTION} at {tag} lacks its generated comment")
    return text.split("-->", 1)[1].strip()


def project_url(project):
    return f"https://api.modrinth.com/v2/project/{urllib.parse.quote(project, safe='')}"


def public_body(project):
    status, data = destination.request(project_url(project))
    if status != 200 or not isinstance(data, dict) or not isinstance(data.get("body"), str):
        raise ValueError("The public Modrinth project body is unavailable")
    return data["body"]


def public_owner(project):
    """Tag of the version that owns the project page, judged by the listed public versions."""
    status, versions = destination.request(project_url(project) + "/version")
    if status != 200 or not isinstance(versions, list):
        raise ValueError("Cannot list the public Modrinth versions")
    listed = [item.get("version_number") for item in versions
              if isinstance(item, dict) and item.get("status") == "listed"
              and isinstance(item.get("version_number"), str)]
    owner = publisher.shared_state_owner(listed)
    return f"v{owner}" if owner else None


def file_link(repository, tag):
    return f"https://github.com/{repository}/blob/{tag}/{DESCRIPTION}"


def summary(text):
    print(text)
    path = os.environ.get("GITHUB_STEP_SUMMARY")
    if path:
        with open(path, "a", encoding="utf-8") as stream:
            stream.write(text + "\n\n")


def api(path):
    return json.loads(run("gh", "api", path))


def check_environment(repository):
    """Read the confirm job's protections; refuse to ask for a paste the gate would not hold."""
    base = f"repos/{repository}/environments/{ENVIRONMENT}"
    try:
        environment = api(base)
        reviewers = [item for rule in environment.get("protection_rules", [])
                     if rule.get("type") == "required_reviewers" for item in rule.get("reviewers", [])]
        if not any(item.get("type") in ("User", "Team") and (item.get("reviewer") or {}).get("id") for item in reviewers):
            raise ValueError(f"{ENVIRONMENT} has no required reviewer; configure it before a paste is requested")
        if environment.get("can_admins_bypass") is not False:
            raise ValueError(f"{ENVIRONMENT} must not allow administrators to bypass its protection rules")
        policy = environment.get("deployment_branch_policy") or {}
        if policy.get("custom_branch_policies") is not True or policy.get("protected_branches") is not False:
            raise ValueError(f"{ENVIRONMENT} must restrict deployments to v* tags with custom policies")
        policies = api(f"{base}/deployment-branch-policies?per_page=100").get("branch_policies")
        if not isinstance(policies, list) or not policies or any(
                item.get("type") != "tag" or item.get("name") != "v*" for item in policies):
            raise ValueError(f"{ENVIRONMENT} must allow only the v* tag policy")
    except subprocess.CalledProcessError:
        raise ValueError(f"The job token cannot read the {ENVIRONMENT} environment; no paste requested") from None
    except (AttributeError, TypeError):
        raise ValueError(f"The {ENVIRONMENT} protection response is invalid; no paste requested") from None


def write_output(path, value):
    with open(path, "a", encoding="utf-8") as stream:
        stream.write(f"paste_needed={str(value).lower()}\n")


def check(args):
    owner = public_owner(args.project)
    if owner != args.tag:
        summary(f"Modrinth description skipped for {args.tag}: the page owner is {owner or 'none'}.")
        write_output(args.output, False)
        return
    needed = not same(source_body(owner), public_body(args.project))
    if needed:
        check_environment(args.repository)
    write_output(args.output, needed)
    if not needed:
        summary(f"The Modrinth description already matches {owner}; no paste or approval needed.")
        return
    summary(f"## Modrinth description paste required\n\n"
            f"Paste [{DESCRIPTION} at {owner}]({file_link(args.repository, owner)}) into the "
            f"[Modrinth description editor](https://modrinth.com/plugin/{urllib.parse.quote(args.project, safe='')}/settings/description), "
            "without its leading generated comment, then approve the "
            f"`{ENVIRONMENT}` environment. A newer owner found at approval time replaces this tag; "
            "the confirmation names the file to paste if it differs.")


def confirm(args):
    # The paste is the write. Ownership is read before and after the body, so the file
    # compared with is the one that owned the page when the body was read.
    for _ in range(3):
        owner = public_owner(args.project)
        if owner is None:
            raise ValueError("Modrinth has no public version that owns the description")
        expected = source_body(owner)
        actual = public_body(args.project)
        if public_owner(args.project) == owner:
            break
    else:
        raise ValueError("The Modrinth page owner kept changing; re-run this job")
    if owner != args.tag:
        summary(f"{args.tag} was superseded: the Modrinth page owner is now {owner}.")
    if not same(expected, actual):
        summary(f"The Modrinth body differs from [{DESCRIPTION} at {owner}]({file_link(args.repository, owner)}). "
                "Paste that file without its leading generated comment and re-run this job.\n\n"
                f"```diff\n{difference(expected, actual)}\n```")
        raise ValueError(f"The Modrinth body does not match {DESCRIPTION} at {owner}; paste "
                         f"{file_link(args.repository, owner)} and re-run this job")
    summary(f"The Modrinth description matches {DESCRIPTION} at its owner {owner}.")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("action", choices=("check", "confirm"))
    parser.add_argument("--repository", required=True)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--project", default="sessionpulse")
    parser.add_argument("--output")
    args = parser.parse_args()
    if not candidate.TAG.fullmatch(args.tag) or (args.action == "check" and not args.output):
        parser.error("a release tag and, for check, --output are required")
    (check if args.action == "check" else confirm)(args)


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, KeyError, json.JSONDecodeError, subprocess.CalledProcessError) as error:
        sys.exit(f"Modrinth description stopped: {error}")
