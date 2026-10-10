#!/usr/bin/env python3
"""Fail-closed destination preflight for release promotion and retries."""

import argparse
import base64
import binascii
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
    # The project status enum is documented at https://docs.modrinth.com/api/operations/getproject/.
    # Anonymous accessibility alone can include withheld or unlisted projects.
    if public_project.get("status") != "approved":
        raise ValueError("Modrinth project is not approved; resolve project review before publication")


def require_modrinth_upload_access(project):
    token = os.environ["MODRINTH_TOKEN"]
    headers = {"Authorization": token}
    try:
        user_status, user = request("https://api.modrinth.com/v2/user", headers)
        project_url = f"https://api.modrinth.com/v2/project/{urllib.parse.quote(project, safe='')}"
        project_status, details = request(project_url, headers)
        if user_status != 200 or not isinstance(user, dict) or not user.get("id"):
            raise ValueError("Cannot identify Modrinth release token owner")
        if (project_status != 200 or not isinstance(details, dict)
                or project not in (details.get("slug"), details.get("id"))):
            raise ValueError(f"Modrinth release target {project} does not match the authenticated project")
        members_status, members = request(project_url + "/members", headers)
    except urllib.error.HTTPError as error:
        if error.code in (401, 403):
            raise ValueError("Modrinth release token needs USER_READ and PROJECT_READ scopes for upload-access preflight") from None
        raise
    if members_status != 200 or not isinstance(members, list):
        raise ValueError(f"Cannot inspect Modrinth project {project} members")
    for member in members:
        if (isinstance(member, dict) and isinstance(member.get("user"), dict)
                and member["user"].get("id") == user["id"] and member.get("accepted") is True
                and isinstance(member.get("permissions"), int)
                and not isinstance(member["permissions"], bool)
                and member["permissions"] & 1):
            return
    raise ValueError(f"Modrinth release token owner lacks accepted upload permission on project {project}")


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


def require_hangar_target(manifest, project, owner):
    project_url = "https://hangar.papermc.io/api/v1/projects/" + "/".join(
        urllib.parse.quote(part, safe="") for part in (owner, project))
    status, details = request(project_url)
    namespace = details.get("namespace") if isinstance(details, dict) else None
    project_id = details.get("id") if isinstance(details, dict) else None
    if (status != 200 or not isinstance(namespace, dict)
            or namespace.get("owner") != owner or namespace.get("slug") != project
            or details.get("visibility") != "public"
            or not isinstance(project_id, int) or isinstance(project_id, bool) or project_id <= 0):
        raise ValueError(f"Hangar project {owner}/{project} is missing or does not match the public release target")
    status, channels = request(f"https://hangar.papermc.io/api/internal/channels/{project_id}")
    if status != 200 or not isinstance(channels, list):
        raise ValueError(f"Cannot inspect Hangar channels for project {owner}/{project}")
    if not any(isinstance(channel, dict) and channel.get("name") == manifest["hangar_channel"]
               and channel.get("projectId") == project_id for channel in channels):
        raise ValueError(f"Hangar project {owner}/{project} has no {manifest['hangar_channel']} channel")
    return project_id


def require_hangar_upload_access(project_id):
    session = hangar_session()
    try:
        jwt = session["Authorization"].split(" ", 1)[1]
        payload = jwt.split(".")[1]
        claims = json.loads(base64.urlsafe_b64decode(payload + "=" * (-len(payload) % 4)))
        key_permissions = int(claims["permissions"], 2)
    except (binascii.Error, KeyError, IndexError, ValueError):
        raise ValueError("Cannot inspect Hangar release token permissions") from None
    required = (1 << 9) | (1 << 12)  # edit_page and create_version
    if key_permissions & required != required:
        raise ValueError("Hangar release token needs create_version and edit_page permissions")
    query = urllib.parse.urlencode({"project": project_id,
                                    "permissions": ["create_version", "edit_page"]}, doseq=True)
    status, result = request("https://hangar.papermc.io/api/v1/permissions/hasAll?" + query, session)
    if status != 200 or not isinstance(result, dict) or result.get("result") is not True:
        raise ValueError(f"Hangar release token owner lacks create_version or edit_page on project {project_id}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("destination", choices=("github", "modrinth", "hangar", "inventory"))
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
    parser.add_argument("--require-modrinth-upload-access", action="store_true")
    parser.add_argument("--require-hangar-target", action="store_true")
    parser.add_argument("--require-hangar-upload-access", action="store_true")
    args = parser.parse_args()
    candidate.check_evidence(args)
    manifest = candidate.verify(args)
    if tag_sha(args.tag) != manifest["source_sha"]:
        raise ValueError("Origin tag no longer points to candidate source commit")
    directory = Path(args.directory)
    if args.destination == "inventory":
        state = reconciled_absence(manifest, "hangar", args.project)
    elif args.destination == "github":
        state = github(directory, manifest, os.environ["GITHUB_REPOSITORY"])
    elif args.destination == "modrinth":
        state = modrinth(manifest, args.project, directory)
        if args.require_public_project:
            require_public_modrinth_project(args.project)
        if args.require_modrinth_upload_access:
            require_modrinth_upload_access(args.project)
    else:
        state = hangar(manifest, args.project, directory)
        if args.require_hangar_target:
            project_id = require_hangar_target(manifest, args.project, os.environ["GITHUB_REPOSITORY"].split("/")[0])
            if args.require_hangar_upload_access:
                require_hangar_upload_access(project_id)
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
