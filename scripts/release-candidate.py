#!/usr/bin/env python3
"""Create and verify a retained SessionPulse release candidate and its test record."""

import argparse
import hashlib
import json
import re
import sys
from pathlib import Path
from zipfile import ZipFile

TAG = re.compile(r"v([0-9]+\.[0-9]+\.[0-9]+)(?:-(alpha|beta|rc)\.([0-9]+))?\Z")
CONTENTS = (
    "plugin.yml",
    "com/ninja6/sessionpulse/SessionPulsePlugin.class",
    "com/ninja6/sessionpulse/lib/folialib/FoliaLib.class",
    "com/ninja6/sessionpulse/lib/folialib/impl/FoliaImplementation.class",
    "com/ninja6/sessionpulse/lib/folialib/impl/SpigotImplementation.class",
    "com/ninja6/sessionpulse/lib/kyori/adventure/text/Component.class",
    "META-INF/LICENSE",
    "META-INF/THIRD_PARTY_NOTICES.md",
)
DESTINATIONS = ["github", "modrinth", "hangar"]
# Folia 26.3 is unavailable. Each advertised 26.3 platform must supply its own gameplay record.
SMOKE_CASES = {("paper", "1.21.11"), ("folia", "1.21.11"), ("spigot", "1.21.11"),
               ("paper", "26.3"), ("spigot", "26.3")}


def validate_smoke(record, jar_sha):
    case = (record.get("platform"), record.get("mc_version"))
    if case not in SMOKE_CASES:
        raise ValueError(f"Unexpected smoke case: {case}")
    if record.get("plugin_sha") != jar_sha:
        raise ValueError("Smoke gameplay record has mismatched plugin_sha")
    if record.get("result") != "passed" or record.get("gameplay") is not True:
        raise ValueError("Smoke gameplay record must pass gameplay assertions")
    if record.get("bot_version") != case[1]:
        raise ValueError("Smoke bot_version differs from server version")
    java = "25" if case[1] == "26.3" else "21"
    if not re.search(r'\bversion "' + java + r'(?:[."]|$)', record.get("java_runtime", "")):
        raise ValueError(f"Smoke runtime must be Java {java}")
    for key in ("server_sha", "bot_sha"):
        if not re.fullmatch(r"[a-f0-9]{64}", record.get(key, "")):
            raise ValueError(f"Smoke record has invalid {key}")
    if not record.get("build_id") or not record.get("channel"):
        raise ValueError("Smoke record must identify server build and channel")
    if case[0] == "spigot":
        path = Path(__file__).parent / "server-inputs" / f"spigot-{case[1]}.json"
        expected = json.loads(path.read_text())
        expected["input_sha256"] = digest(path)
        if record.get("spigot_input") != expected:
            raise ValueError("Smoke Spigot source inputs differ from pinned revision")
    return case


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def release(tag):
    match = TAG.fullmatch(tag)
    if not match:
        raise ValueError(f"Invalid release tag: {tag}")
    version = tag[1:]
    kind = match.group(2)
    channel = "alpha" if kind == "alpha" else "beta" if kind else "release"
    return version, channel, {"alpha": "Alpha", "beta": "Beta", "release": "Release"}[channel]


def check_jar(path, version):
    with ZipFile(path) as archive:
        names = archive.namelist()
        for name in CONTENTS:
            if names.count(name) != 1:
                raise ValueError(f"Expected exactly one {name} in {path}")
        descriptor = archive.read("plugin.yml").decode("utf-8")
        versions = re.findall(r"(?m)^version: ['\"]?([^'\"\r\n]+)['\"]?$", descriptor)
        if versions != [version]:
            raise ValueError(f"plugin.yml version {versions} differs from {version}")
        if not any(name.startswith("META-INF/services/com.ninja6.sessionpulse.lib.kyori") for name in names):
            raise ValueError("Relocated Adventure service files missing")
        if any(name.startswith(("com/tcoded/", "net/kyori/")) for name in names):
            raise ValueError("Unrelocated library classes in release JAR")


def exact_files(directory, expected):
    actual = sorted(p.name for p in directory.iterdir() if p.is_file())
    if actual != sorted(expected):
        raise ValueError(f"Candidate files differ: expected {sorted(expected)}, found {actual}")


def create(args):
    version, channel, hangar = release(args.tag)
    directory = Path(args.directory)
    jar = f"SessionPulse-{version}.jar"
    expected = [jar, f"{jar}.sha256", "release-notes.md", "release-body.md"]
    exact_files(directory, expected)
    check_jar(directory / jar, version)
    checksum = (directory / f"{jar}.sha256").read_text().strip()
    if checksum != f"{digest(directory / jar)}  {jar}":
        raise ValueError("JAR checksum file differs from candidate bytes")
    manifest = {
        "schema": 1,
        "candidate_id": f"{args.run_id}-{args.attempt}-{args.sha[:12]}",
        "artifact_name": f"release-candidate-{args.run_id}-{args.attempt}",
        "run_id": str(args.run_id),
        "attempt": str(args.attempt),
        "source_sha": args.sha,
        "tag": args.tag,
        "version": version,
        "channel": channel,
        "hangar_channel": hangar,
        "destinations": DESTINATIONS,
        "files": {name: digest(directory / name) for name in expected},
    }
    (directory / "manifest.json").write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n")
    return manifest


def verify(args):
    directory = Path(args.directory)
    manifest = json.loads((directory / "manifest.json").read_text())
    version, channel, hangar = release(args.tag)
    required = {
        "schema": 1, "candidate_id": f"{args.run_id}-{args.attempt}-{args.sha[:12]}",
        "artifact_name": f"release-candidate-{args.run_id}-{args.attempt}",
        "run_id": str(args.run_id), "attempt": str(args.attempt), "source_sha": args.sha,
        "tag": args.tag, "version": version, "channel": channel,
        "hangar_channel": hangar, "destinations": DESTINATIONS,
    }
    for key, value in required.items():
        if manifest.get(key) != value:
            raise ValueError(f"Candidate {key} differs from expected {value}")
    jar = f"SessionPulse-{version}.jar"
    expected = {jar, f"{jar}.sha256", "release-notes.md", "release-body.md"}
    files = manifest.get("files")
    if not isinstance(files, dict) or set(files) != expected:
        raise ValueError("Manifest file inventory differs from release files")
    exact_files(directory, expected | {"manifest.json"})
    for name, wanted in files.items():
        if digest(directory / name) != wanted:
            raise ValueError(f"Digest mismatch for {name}")
    if (directory / f"{jar}.sha256").read_text().strip() != f"{files[jar]}  {jar}":
        raise ValueError("Checksum file does not identify release JAR")
    check_jar(directory / jar, version)
    return manifest


def receipt(args):
    manifest = verify(args)
    if args.platform not in ("paper", "folia", "spigot"):
        raise ValueError("Unsupported smoke platform")
    gameplay = json.loads(Path(args.smoke_evidence).read_text())
    validate_smoke(gameplay, manifest["files"][f"SessionPulse-{manifest['version']}.jar"])
    if gameplay["platform"] != args.platform:
        raise ValueError("Smoke evidence platform differs from receipt")
    record = {
        "gameplay": gameplay,
        "candidate_id": manifest["candidate_id"],
        "manifest_sha256": digest(Path(args.directory) / "manifest.json"),
        "jar_sha256": manifest["files"][f"SessionPulse-{manifest['version']}.jar"],
        "platform": args.platform,
        "run_id": manifest["run_id"],
        "attempt": manifest["attempt"],
        "result": "passed",
    }
    Path(args.output).write_text(json.dumps(record, sort_keys=True) + "\n")


def evidence(args):
    manifest = verify(args)
    receipts = list(Path(args.receipts).glob("*.json"))
    if len(receipts) != len(SMOKE_CASES):
        raise ValueError("Expected five smoke receipts")
    platforms = set()
    cases = set()
    servers = []
    for path in receipts:
        record = json.loads(path.read_text())
        for key, value in {
            "candidate_id": manifest["candidate_id"],
            "manifest_sha256": digest(Path(args.directory) / "manifest.json"),
            "jar_sha256": manifest["files"][f"SessionPulse-{manifest['version']}.jar"],
            "run_id": manifest["run_id"], "attempt": manifest["attempt"], "result": "passed",
        }.items():
            if record.get(key) != value:
                raise ValueError(f"Smoke receipt {path} has mismatched {key}")
        gameplay = record.get("gameplay", {})
        case = validate_smoke(gameplay, manifest["files"][f"SessionPulse-{manifest['version']}.jar"])
        if case in cases or gameplay["platform"] != record.get("platform"):
            raise ValueError("Duplicate or mislabeled smoke receipt")
        cases.add(case)
        servers.append(gameplay)
        platforms.add(record.get("platform"))
    if cases != SMOKE_CASES:
        raise ValueError(f"Smoke cases incomplete: {cases}")
    if platforms != {"paper", "folia", "spigot"}:
        raise ValueError(f"Smoke platforms incomplete: {platforms}")
    record = {"candidate_id": manifest["candidate_id"], "manifest_sha256": digest(Path(args.directory) / "manifest.json"),
              "jar_sha256": manifest["files"][f"SessionPulse-{manifest['version']}.jar"],
              "run_id": manifest["run_id"], "attempt": manifest["attempt"],
              "platforms": sorted(platforms), "servers": sorted(servers, key=lambda s: (s["platform"], s["mc_version"])), "result": "passed"}
    Path(args.output).write_text(json.dumps(record, sort_keys=True) + "\n")


def check_evidence(args):
    manifest = verify(args)
    record = json.loads(Path(args.evidence).read_text())
    for key, value in {"candidate_id": manifest["candidate_id"],
                       "manifest_sha256": digest(Path(args.directory) / "manifest.json"),
                       "jar_sha256": manifest["files"][f"SessionPulse-{manifest['version']}.jar"],
                       "run_id": manifest["run_id"], "attempt": manifest["attempt"],
                       "platforms": ["folia", "paper", "spigot"], "result": "passed"}.items():
        if record.get(key) != value:
            raise ValueError(f"Test evidence has mismatched {key}")

    servers = record.get("servers", [])
    cases = [validate_smoke(server, manifest["files"][f"SessionPulse-{manifest['version']}.jar"])
             for server in servers]
    if len(cases) != len(SMOKE_CASES) or set(cases) != SMOKE_CASES:
        raise ValueError("Test evidence has incomplete or duplicate smoke cases")


def reconciliation_record(manifest, modrinth_project, hangar_project):
    return {
        key: manifest[key] for key in ("candidate_id", "source_sha", "tag", "version")
    } | {"confirmed_absent": {"modrinth": modrinth_project, "hangar": hangar_project}}


def show_reconciliation(args):
    manifest = verify(args)
    print(json.dumps(reconciliation_record(manifest, args.modrinth_project, args.hangar_project), indent=2))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=("create", "verify", "receipt", "evidence", "check-evidence", "reconciliation"))
    parser.add_argument("--directory", required=True)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--sha", required=True)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--attempt", required=True)
    parser.add_argument("--platform")
    parser.add_argument("--receipts")
    parser.add_argument("--smoke-evidence")
    parser.add_argument("--evidence")
    parser.add_argument("--output")
    parser.add_argument("--modrinth-project", default="sessionpulse")
    parser.add_argument("--hangar-project", default="SessionPulse")
    args = parser.parse_args()
    {"create": create, "verify": verify, "receipt": receipt,
     "evidence": evidence, "check-evidence": check_evidence,
     "reconciliation": show_reconciliation}[args.command](args)


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, KeyError, json.JSONDecodeError) as error:
        sys.exit(str(error))
