#!/usr/bin/env python3
"""Fail-closed destination preflight for release promotion and retries."""

import argparse
import hashlib
import json
import os
import subprocess
import sys
import tempfile
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

from importlib.machinery import SourceFileLoader

candidate = SourceFileLoader("candidate", str(Path(__file__).with_name("release-candidate.py"))).load_module()


def request(url, headers=None):
    try:
        with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "SessionPulse-release", **(headers or {})}), timeout=30) as response:
            return response.status, json.load(response)
    except urllib.error.HTTPError as error:
        if error.code == 404:
            return 404, None
        raise


def hangar_session():
    key = os.environ.get("HANGAR_API_TOKEN")
    if not key:
        raise ValueError("Hangar lookup requires the release environment token")
    url = "https://hangar.papermc.io/api/v1/authenticate?" + urllib.parse.urlencode({"apiKey": key})
    try:
        with urllib.request.urlopen(urllib.request.Request(url, data=b"", method="POST",
                                    headers={"User-Agent": "SessionPulse-release"}), timeout=30) as response:
            session = json.load(response)
    except (OSError, ValueError, urllib.error.URLError):
        raise ValueError("Hangar authentication failed") from None
    token = session.get("token")
    if not token:
        raise ValueError("Hangar authentication returned no session token")
    return {"Authorization": f"HangarAuth {token}"}


def tag_sha(tag):
    lines = subprocess.check_output(["git", "ls-remote", "origin", f"refs/tags/{tag}", f"refs/tags/{tag}^{{}}"], text=True).splitlines()
    peeled = [line.split()[0] for line in lines if line.endswith(f"refs/tags/{tag}^{{}}")]
    direct = [line.split()[0] for line in lines if line.endswith(f"refs/tags/{tag}")]
    if len(peeled) > 1 or len(direct) != 1:
        raise ValueError("Tag is missing or ambiguous on origin")
    return peeled[0] if peeled else direct[0]


def reconciled_absence(manifest, destination, project):
    """Only a candidate-bound maintainer inventory audit can resolve uncertain absence."""
    raw = os.environ.get("RELEASE_ABSENCE_RECONCILIATION", "")
    if not raw.strip():
        raise ValueError("Missing release environment RELEASE_ABSENCE_RECONCILIATION; audit the registry owner inventory and set the candidate record before approval")
    try:
        record = json.loads(raw)
    except ValueError:
        raise ValueError("RELEASE_ABSENCE_RECONCILIATION is malformed JSON") from None
    if not isinstance(record, dict):
        raise ValueError("RELEASE_ABSENCE_RECONCILIATION must be a JSON object")
    for key in ("candidate_id", "tag", "version", "source_sha"):
        if record.get(key) != manifest.get(key):
            raise ValueError(f"Release absence reconciliation has mismatched {key}")
    confirmed = record.get("confirmed_absent")
    if not isinstance(confirmed, dict) or confirmed.get(destination) != project:
        raise ValueError(f"Release absence reconciliation does not confirm {destination} project {project}")
    return "absent"


def github(directory, manifest, repo):
    tag = manifest["tag"]
    process = subprocess.run(["gh", "api", f"repos/{repo}/releases/tags/{tag}"], capture_output=True, text=True)
    if process.returncode:
        if "HTTP 404" in process.stderr:
            return "absent"
        raise ValueError(f"GitHub release query failed: {process.stderr.strip()}")
    release = json.loads(process.stdout)
    expected_pre = manifest["channel"] != "release"
    if release.get("tag_name") != tag or release.get("prerelease") != expected_pre or release.get("draft"):
        raise ValueError("Existing GitHub release metadata conflicts with candidate")
    if release.get("body", "").rstrip("\n") != (directory / "release-body.md").read_text().rstrip("\n"):
        raise ValueError("Existing GitHub release notes conflict with candidate")
    jar = f"SessionPulse-{manifest['version']}.jar"
    expected = {jar, f"{jar}.sha256"}
    assets = release.get("assets", [])
    if {asset.get("name") for asset in assets} != expected:
        raise ValueError("Existing GitHub release asset inventory conflicts with candidate")
    with tempfile.TemporaryDirectory() as temp:
        for asset in assets:
            download = subprocess.run(["gh", "release", "download", tag, "-R", repo,
                                       "--dir", temp, "--pattern", asset["name"]], capture_output=True)
            if download.returncode:
                raise ValueError(f"Cannot download existing GitHub asset {asset['name']}")
            path = Path(temp) / asset["name"]
            if not path.is_file() or candidate.digest(path) != manifest["files"][asset["name"]]:
                raise ValueError(f"Existing GitHub asset {asset['name']} differs from candidate")
    if not expected_pre:
        latest = subprocess.run(["gh", "api", f"repos/{repo}/releases/latest"], capture_output=True, text=True)
        if latest.returncode or json.loads(latest.stdout).get("id") != release.get("id"):
            raise ValueError("Existing stable release is not GitHub Latest")
    return "complete"


def modrinth(manifest, project, directory):
    token = os.environ.get("MODRINTH_TOKEN")
    if not token:
        raise ValueError("Modrinth lookup requires the release environment token")
    url = f"https://api.modrinth.com/v2/project/{urllib.parse.quote(project, safe='')}/version"
    status, versions = request(url, {"Authorization": token})
    if status != 200 or not isinstance(versions, list):
        raise ValueError("Cannot inspect Modrinth versions")
    found = [item for item in versions if item.get("version_number") == manifest["version"]]
    if not found:
        return reconciled_absence(manifest, "modrinth", project)
    if len(found) != 1:
        raise ValueError("Multiple Modrinth versions match candidate")
    version = found[0]
    if version.get("status") != "listed" or not version.get("id"):
        raise ValueError("Existing Modrinth version is not a listed public release")
    public_status, public_version = request("https://api.modrinth.com/v2/version/" + urllib.parse.quote(version["id"], safe=""))
    fields = ("id", "project_id", "version_number", "version_type", "status", "changelog", "files", "loaders", "game_versions", "dependencies")
    if public_status != 200 or not isinstance(public_version, dict) or any(public_version.get(key) != version.get(key) for key in fields):
        raise ValueError("Modrinth version is not anonymously accessible with matching metadata")
    jar = f"SessionPulse-{manifest['version']}.jar"
    files = found[0].get("files", [])
    if found[0].get("version_type") != manifest["channel"] or len(files) != 1 or files[0].get("filename") != jar:
        raise ValueError("Existing Modrinth version conflicts with candidate")
    if found[0].get("changelog", "").rstrip("\n") != (directory / "release-notes.md").read_text().rstrip("\n"):
        raise ValueError("Existing Modrinth changelog conflicts with candidate")
    file_url = files[0].get("url")
    if not file_url or not file_url.startswith("https://cdn.modrinth.com/"):
        raise ValueError("Existing Modrinth file URL is not a Modrinth CDN URL")
    with urllib.request.urlopen(urllib.request.Request(file_url, headers={"User-Agent": "SessionPulse-release"}), timeout=60) as response:
        published = hashlib.sha256(response.read()).hexdigest()
    if published != manifest["files"][jar]:
        raise ValueError("Existing Modrinth JAR bytes conflict with candidate")
    return "complete"


def require_public_modrinth_project(project):
    url = f"https://api.modrinth.com/v2/project/{urllib.parse.quote(project, safe='')}"
    status, public_project = request(url)
    if status != 200 or not isinstance(public_project, dict) or not public_project.get("id"):
        raise ValueError("Modrinth project is not anonymously accessible; resolve project review before publication")


def hangar(manifest, project, directory):
    headers = hangar_session()
    url = f"https://hangar.papermc.io/api/v1/projects/{urllib.parse.quote(project, safe='')}/versions/{urllib.parse.quote(manifest['version'], safe='')}"
    status, version = request(url, headers)
    if status == 404:
        return reconciled_absence(manifest, "hangar", project)
    if status == 200:
        if version.get("visibility") != "public":
            raise ValueError("Existing Hangar version is not public")
        public_status, public_version = request(url)
        fields = ("name", "visibility", "channel", "description", "downloads", "platformDependencies", "pluginDependencies")
        if public_status != 200 or not isinstance(public_version, dict) or any(public_version.get(key) != version.get(key) for key in fields):
            raise ValueError("Hangar version is not anonymously accessible with matching metadata")
        jar = f"SessionPulse-{manifest['version']}.jar"
        download = version.get("downloads", {}).get("PAPER", {})
        info = download.get("fileInfo") or {}
        if (version.get("name") != manifest["version"]
                or (version.get("channel") or {}).get("name") != manifest["hangar_channel"]
                or version.get("description", "").rstrip("\n") != (directory / "release-notes.md").read_text().rstrip("\n")
                or set(version.get("downloads", {})) != {"PAPER"}
                or info.get("name") != jar
                or info.get("sha256Hash", "").lower() != manifest["files"][jar]):
            raise ValueError("Existing Hangar version metadata or digest conflicts with candidate")
        file_url = download.get("downloadUrl")
        if not file_url or not file_url.startswith("https://hangarcdn.papermc.io/"):
            raise ValueError("Existing Hangar file URL is not a Hangar CDN URL")
        with urllib.request.urlopen(urllib.request.Request(file_url, headers={"User-Agent": "SessionPulse-release"}), timeout=60) as response:
            published = hashlib.sha256(response.read()).hexdigest()
        if published != manifest["files"][jar]:
            raise ValueError("Existing Hangar JAR bytes conflict with candidate")
        return "complete"
    raise ValueError(f"Cannot inspect Hangar version (HTTP {status})")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("destination", choices=("github", "modrinth", "hangar"))
    parser.add_argument("--directory", required=True)
    parser.add_argument("--evidence", required=True)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--sha", required=True)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--attempt", required=True)
    parser.add_argument("--project")
    parser.add_argument("--require-complete", action="store_true")
    parser.add_argument("--require-absent", action="store_true")
    parser.add_argument("--require-public-project", action="store_true")
    args = parser.parse_args()
    candidate.check_evidence(args)
    manifest = candidate.verify(args)
    if tag_sha(args.tag) != manifest["source_sha"]:
        raise ValueError("Origin tag no longer points to candidate source commit")
    directory = Path(args.directory)
    if args.destination == "github":
        state = github(directory, manifest, os.environ["GITHUB_REPOSITORY"])
    elif args.destination == "modrinth":
        state = modrinth(manifest, args.project, directory)
        if args.require_public_project:
            require_public_modrinth_project(args.project)
    else:
        state = hangar(manifest, args.project, directory)
    if args.require_complete and state != "complete":
        raise ValueError(f"{args.destination} is not yet complete")
    if args.require_absent and state != "absent":
        raise ValueError(f"{args.destination} became complete before upload; reconcile this run")
    print(f"{args.destination}: {state}")
    output = os.environ.get("GITHUB_OUTPUT")
    if output:
        with open(output, "a", encoding="utf-8") as stream:
            stream.write(f"state={state}\n")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, KeyError, json.JSONDecodeError, urllib.error.URLError) as error:
        sys.exit(str(error))
