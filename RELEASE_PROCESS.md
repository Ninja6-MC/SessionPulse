# SessionPulse Release Lifecycle & Publishing Guide

This document defines the release policies, branch strategy, versioning rules, and multi-platform publishing procedures for **SessionPulse**.

---

## 1. Versioning Rules (SemVer 2.0.0)

Every release follows `MAJOR.MINOR.PATCH[-PRERELEASE]`:
* **MAJOR (`X.0.0`)**: Incompatible API breaks, architectural redesigns, or config schema breaks.
* **MINOR (`1.X.0`)**: New features (e.g. Velocity companion, new notification channels, PlaceholderAPI support).
* **PATCH (`1.0.X`)**: Bug fixes and performance patches.
* **Pre-releases**:
  * `v1.0.0-alpha.1` (Internal experimental builds)
  * `v1.0.0-beta.1` (Public feature-complete testing builds)
  * `v1.0.0-rc.1` (Release candidate; `1.0.0` and later only)

**Before 1.0.0, every release is a pre-release.** Alphas are for internal testing and
betas for public testing; `1.0.0` is the first stable release. Alphas are still public:
each one is a GitHub pre-release, is published to Hangar's *Alpha* channel, which is
marked unstable and hidden by default, and reaches Modrinth's *alpha* channel once the
Modrinth project is approved. Do not push an unsuffixed tag below `v1.0.0`: the release
workflows publish every unsuffixed tag as a stable release, as *Latest* on GitHub and on the
*release* channel of Modrinth and Hangar.

**Do not tag a release candidate below `v1.0.0`.** An `-rc.N` tag promises that the same
bytes become the unsuffixed release, and below `v1.0.0` that release can never be cut. Use
`-alpha.N` and `-beta.N` until then; the first release candidate is `v1.0.0-rc.1`.
`v0.1.0-rc.1` was published before this rule and stays as it is.

---

## 2. Release Tiers & Distribution Channels

Every tier is cut by tagging a commit on `main`. The tag decides the tier, not the branch —
there is no separate release branch.

```
                    [ feat/… fix/… docs/… topic branches ]
                                      │
                                      ▼
                   [ PR squashed via Tier-2 Merge Queue ]
                                      │
                ┌─────────────────────┴─────────────────────┐
                ▼                                           ▼
   ┌─────────────────────────┐                 ┌─────────────────────────┐
   │  ALPHA (vX.Y.Z-alpha.N) │                 │  BETA / RC (-beta, -rc) │
   │ • Experimental          │                 │ • Feature-Complete      │
   │ • Internal Testing      │                 │ • Public Testing        │
   │ • GitHub Pre-release    │                 │ • GitHub Pre-release    │
   │ • Modrinth alpha        │                 │ • Modrinth beta         │
   │ • Hangar Alpha          │                 │ • Hangar Beta           │
   └────────────┬────────────┘                 └────────────┬────────────┘
                │                                           │
                └─────────────────────┬─────────────────────┘
                                      ▼
                         ┌─────────────────────────┐
                         │   MARKET / GA (vX.Y.Z)  │
                         │ • Production Stable     │
                         │ • GitHub Latest Release │
                         │ • Modrinth release      │
                         │ • Hangar Release        │
                         │ • SpigotMC (manual)     │
                         └─────────────────────────┘
```

| Tier | Git Tag Pattern | Source Branch | Stability Level | Published Channels |
| :--- | :--- | :--- | :--- | :--- |
| **Alpha** | `vX.Y.Z-alpha.N` | `main` | Experimental | GitHub Releases (*Pre-release*), Modrinth (*alpha*), Paper Hangar (*Alpha*) |
| **Beta / RC** | `vX.Y.Z-beta.N`, `vX.Y.Z-rc.N` (X >= 1) | `main` | Feature-Complete | GitHub Releases (*Pre-release*), Modrinth (*beta*), Paper Hangar (*Beta*) |
| **Market (GA)** | `vX.Y.Z` | `main` | Production Stable | GitHub Releases (*Latest*), Modrinth (*release*), Paper Hangar (*Release*), SpigotMC (*manual*) |

Notes on the table:

* **Modrinth has no "featured" channel.** A stable tag publishes a *release* version.
  Featuring it on the project page is done by hand on Modrinth afterwards.
* **SpigotMC is manual.** SpigotMC has no upload API; post the GA jar from the GitHub
  release as a resource update yourself.
* **Alphas publish to Hangar's *Alpha* channel**, as in SpiralGenesis. Hangar marks that
  channel unstable and hides it by default, so an alpha is public but is listed in the
  Versions tab only when the *Alpha* channel filter is selected.
* **The Hangar channels must exist first.** The Hangar project needs channels named
  exactly `Alpha`, `Beta` and `Release`; the workflow selects the channel by name.
* **Release candidates publish as beta.** Neither registry has a release-candidate tier.

---

## 3. How to Execute a Release

### Step 1: Pre-Release Checklist
1. All target PRs merged into `main` via the Tier-2 Merge Queue, and **CI green on the commit you are about to tag**.
   This is the gate. The release job runs `test` and `shadowJar`, not `build`, so the
   sources and javadoc jars, and everything else `build` checks, are proven only by CI.
2. Run test suite locally:
   ```bash
   ./gradlew test
   ```
3. Update `CHANGELOG.md`. A stable release needs a `## [X.Y.Z]` section, merged to `main`
   before tagging. Date the `## [X.Y.Z]` heading in that same pull request; the extractor
   matches the heading with or without a date, so an undated one still releases.

   Pre-releases take their notes from the base version's section once it exists. For a
   `vX.Y.Z-*` tag the extractor tries `## [X.Y.Z-pre.N]`, then `## [X.Y.Z]`, then
   `## [Unreleased]`, so a `## [X.Y.Z]` section takes precedence over `## [Unreleased]`
   for every pre-release of that version. Changes aimed at that version go under
   `## [X.Y.Z]`; `## [Unreleased]` is used only when no section for the base version
   exists. Below 1.0.0 the section stays undated, because no stable tag is cut for it.

### Step 2: Cut the Tag

Cut the tag with `scripts/release.sh` (or `scripts\release.ps1` on Windows, which runs the
same script under Git Bash) from the main checkout, never from a linked worktree. Pass the
version without the leading `v`, and run it with `--dry-run` first:

```bash
scripts/release.sh 0.2.0-beta.2 --dry-run
scripts/release.sh 0.2.0-beta.2
```

Before it tags anything, the script refuses to continue unless:

* the version matches the tag grammar `release-candidate.yml` accepts, and is neither unsuffixed nor
  `-rc.N` below 1.0.0 (section 1);
* it runs in the main checkout, on `main`, with no uncommitted changes, and `main` equals
  `origin/main` after a fetch;
* the tag exists neither locally nor on `origin`;
* the latest *Continuous Integration* push run on `main` for that commit succeeded (with
  no usable `gh` it only warns, and that check is yours to make);
* `CHANGELOG.md` has the section the extractor will use. A stable version needs
  `## [X.Y.Z]`; a pre-release without any section only warns.

It then prints the tag, commit, channel and release notes. `--dry-run` stops there.
Without it, the script asks for the tag to be typed back, creates an annotated tag and
pushes that one tag by name. It then waits for the *Release Candidate* run, prints the
owner-inventory checklist, asks whether each registry's version is absent (type `absent`
or `present`; there is no default) and dispatches *Publish Release* on the tag with those
answers. Approve the `release` environment in the browser once its `plan` job has passed.
Without `gh`, the script stops after the push; dispatch later with `--resume` (see
*Partial Publication and Retry*). The extractor and tag grammar are copies of the ones in
`release-candidate.yml`; `scripts/tests/test_release_script.py` fails if they drift apart.

If the script cannot be used, tag the release on `main` by hand after making the same
checks:

```bash
git tag -a v0.1.0-alpha.1 -m "v0.1.0-alpha.1"
git push origin v0.1.0-alpha.1
```

Push release tags one at a time, by name. GitHub fires no tag push events at all when more
than three tags are pushed at once, so `git push --tags` after several tags builds no
candidate and reports nothing. A hand-cut tag still needs the publisher dispatched:
`scripts/release.sh <version> --resume github,modrinth,hangar --candidate-run <run ID>`.

### Step 3: Candidate, Attestation and Publication

A release runs two workflows. `.github/workflows/release-candidate.yml` starts on the tag
push, builds and tests the candidate and publishes nothing. `.github/workflows/release.yml`
is started by `workflow_dispatch` on the same tag once the candidate has passed, and
publishes that candidate to the destinations selected for that run. `scripts/release.sh`
does both: it pushes the tag, waits for the candidate run, asks for the attestations and
dispatches the publisher.

#### The candidate

`release-candidate.yml` will:

1. Reject the tag unless it matches `vMAJOR.MINOR.PATCH` with an optional `-alpha.N`,
   `-beta.N` or `-rc.N` suffix.
2. Reject the tag unless it points at a commit on `main` (the tagged commit must be an
   ancestor of `origin/main`).
3. Validate the Gradle wrapper jar against Gradle's published checksums.
4. Collect the release notes from `CHANGELOG.md`:
   * a stable release requires a `## [X.Y.Z]` section, and fails without one;
   * a pre-release uses `## [X.Y.Z-pre.N]` if present, else `## [X.Y.Z]`, else
     `## [Unreleased]`, else the line "No changelog section was written for this
     pre-release."

   A section ends at the next `## ` heading or at the link references at the foot of the
   file. The same notes go to GitHub, Modrinth and Hangar; the GitHub release adds a
   "Requires Java 21." line after them.
5. Compile with Java 21 and run the JUnit 6 tests.
6. Build the shaded, relocated `SessionPulse-<version>.jar` once (FoliaLib and Adventure
   relocated under `com.ninja6.sessionpulse.lib`, with `META-INF/LICENSE` and
   `META-INF/THIRD_PARTY_NOTICES.md`).
7. Compute its SHA-256 checksum (`SessionPulse-<version>.jar.sha256`, holding a bare
   filename so `sha256sum -c` works beside the downloaded jar).
8. Seal the jar, checksum and notes with a manifest recording the source SHA, tag,
   version, channel, run ID and attempt, candidate ID, destination list, file inventory
   and SHA-256 digests. Retain them as one Actions artifact for 90 days, the repository
   maximum, so a single destination can still be retried weeks later.
9. Download the candidate in independent smoke jobs. Each checks the manifest,
   inventory, digests, embedded `plugin.yml` version, relocation, notices and service
   entries before running the Paper, Folia or Spigot protocol smoke path on that exact
   jar. A final job binds the passing receipts to the candidate ID and digest, retains
   the evidence for 90 days, and prints the candidate run ID, the manifest and the
   owner-inventory checklist in the run summary.

#### Owner inventory and attestation

Registry lookups do not return every version the owner can see. Modrinth's version list
excludes draft and unlisted versions even when authenticated, and Hangar can conceal
soft-deleted versions. An omitted version or HTTP 404 is therefore not proof of absence,
and the publisher uploads to a registry only when the maintainer has inspected its owner
inventory for this exact version and attested, in that dispatch, that it is absent:

* **Modrinth:** sign in as the project owner and inspect the project's Versions
  management list. Check every available status, including draft, unlisted, scheduled
  and archived versions, plus any deleted-version view.
* **Hangar:** sign in as the project owner and inspect the project's Versions and
  management views. Check hidden and soft-deleted versions, plus any deleted-version
  view.

If a status or history view is unavailable, resolve that uncertainty before attesting;
do not infer absence from the public page. Resolve any existing nonpublic version by
hand; do not attest that it is absent or create another version over it.

An attestation is a boolean input of one dispatch, bound to that run and its candidate.
It is never stored in a repository or environment variable, a secret or a file. It is
needed only for a destination that is absent: a destination already complete with
matching bytes and metadata is verified and skipped without one. GitHub Releases can be
checked directly and needs no attestation.

#### The publisher

`release.yml` takes these inputs:

| Input | Meaning |
| :--- | :--- |
| `tag` | The release tag. The run must be dispatched on that tag (`--ref <tag>`); every job is skipped otherwise. |
| `candidate_run_id` | The successful *Release Candidate* run for that tag. |
| `destinations` | Comma-separated subset of `github,modrinth,hangar` to publish in this run. Defaults to all three. |
| `modrinth_absence_confirmed` | True only after the Modrinth owner inventory check above, for this run. |
| `hangar_absence_confirmed` | True only after the Hangar owner inventory check above, for this run. |
| `nonce` | Free text added to the run name, so `scripts/release.sh` can find the run it started. |

Publisher runs for one tag share a concurrency group keyed on the tag ref; a second
dispatch waits for the first to finish.

The `plan` job needs no approval and holds no publishing secret. Its failures appear
before anyone is asked to approve. It:

1. Validates the tag input against the tag grammar and the destination list, and
   rejects an attestation for a destination that is not selected.
2. Accepts the candidate run only when it is a `release-candidate.yml` run, started by a
   push of this tag, at the tag's commit on `origin`, that concluded `success`. It pins
   the newest candidate attempt in that run, with evidence from the same attempt. An
   expired candidate or evidence artifact stops the publisher (see *Expired candidates*).
3. Downloads the candidate and evidence from that run and verifies them.
4. Refuses a true attestation when another publisher run for the tag is in progress or
   finished after this run was created, since that run may have written a version the
   attestation did not see.
5. Checks each selected destination with anonymous lookups: the GitHub release, the
   Modrinth project approval and version list, and the Hangar project, channel and
   version. A registry not shown complete needs its attestation; without one the run
   stops here.
6. Dry-runs the Hangar task graph when Hangar is selected.

`publish-github`, `publish-modrinth` and `publish-hangar` then each publish one
destination. All three use the protected `release` environment and depend on `plan`
alone, so they wait at the gate together and one approval releases the run. Each job:

1. Runs the non-writing credential checks for **every destination selected in this
   dispatch**, not only its own: `RELEASE_SECRETS_MIGRATED`, both tokens present, GitHub
   releases readable, the Modrinth token accepted and the project approved, the Hangar
   key accepted, the Hangar owner, project and channel, the key's `create_version` and
   `edit_page` bits and the owner's project permissions, and the Hangar task graph. A
   failure stops every destination before its first public write, so publishing only
   some destinations is a choice made in the dispatch inputs.
2. Checks its own destination with the owner token. A complete destination with
   matching bytes and metadata is skipped; conflicting or uncertain bytes stop that job
   for maintainer reconciliation; an absent registry version without an attestation
   stops it.
3. Immediately before a registry upload, refuses a true attestation unless the run is
   on its first attempt, and checks again for an overlapping publisher run. Re-running
   a job reuses the outputs of `plan`, so this check is in the job that uploads.
4. Uploads the unchanged jar and verifies the public consumer download. Pre-releases
   retain their tier; only stable tags become GitHub Latest. Both registries declare
   EssentialsX as an optional, unpinned dependency (`dependencies` on the `mc-publish`
   step; an external-URL dependency pointing at its Modrinth page in `hangarPublish`,
   since EssentialsX is not on Hangar). Keep the two in step with `softdepend` in
   `plugin.yml`.
5. In the Hangar job only, syncs the Hangar resource page from
   `docs/store-description.md` when this version is the newest public version on its
   Hangar channel. Otherwise it logs that the sync was skipped, so an older version never
   overwrites a newer version's page. See [Store Descriptions](#store-descriptions).

A failure while uploading to one destination, such as a registry outage, does not stop
the others.

**Requires Java 21.** The plugin is built for Java 21 and declares it on Modrinth; every
server must run on Java 21 or newer to load it. Supported Paper and Spigot 26.3
servers require Java 25; the plugin compilation target stays at Java 21 for older
supported servers. Folia 26.3 is not declared because no server build is available.

### Release Environment and Secrets

The `release` environment requires maintainer approval, accepts `v*` tags only and
disallows administrator bypass. The candidate workflow and the `plan` job have read
permission and receive no publishing credentials. Only the three publish jobs use the
environment and its secrets, and only `publish-github` has `contents: write`. Never
approve a deployment from automation.

| Environment secret | Used by | Required scope |
| :--- | :--- | :--- |
| `MODRINTH_TOKEN` | Modrinth lookup and upload | `PROJECT_READ` and `VERSION_READ` for authenticated lookup, plus `VERSION_CREATE` for upload; Modrinth enforces the token owner's effective project permissions when uploading |
| `HANGAR_API_TOKEN` | Hangar lookup, version upload and page sync | Key covering SessionPulse, owned by a project member, with `create_version`, `edit_page` |

Before the first public write, the anonymous Modrinth project response must have
`status: approved`, as defined by the
[project API schema](https://docs.modrinth.com/api/operations/getproject/). HTTP 200
alone is insufficient: withheld, unlisted and other nonapproved states stop promotion,
even when `requested_status` is `approved`. Resolve project review before retrying;
do not change registry visibility or submit approval from the publisher.

A Hangar API key holding only `create_version` still uploads the version, but Hangar
rejects the page sync that follows. That fails the job whenever the page text changed;
when it did not, the only sign is an `Error using endpoint` line in the step log. The
preflight therefore requires both permission bits. The version lookup endpoint used here
checks project access and visibility; it does not require a separate `view_public_info`
key permission.

Both tokens are required. Issue #79 tracks their migration: create new tokens, add them
as `release` environment secrets, delete the repository secrets, and revoke the old
tokens. Set the **environment variable** `RELEASE_SECRETS_MIGRATED=true` only after
those checks. Every publish job fails before any destination write while it is unset. A
repository secret with the same name can otherwise be resolved by a job using the
environment, so the migration cannot be certified by workflow syntax alone.

#### Prerequisites only an upload can prove

The preflight cannot show these without writing. A failure there is a failure of that
destination alone: the other destinations still publish, and that destination is
retried by a new dispatch once the prerequisite is fixed.

| Prerequisite | Destination | Why the preflight cannot check it |
| :--- | :--- | :--- |
| `MODRINTH_TOKEN` holds `VERSION_CREATE`, and its owner may create versions on the project | Modrinth | A token's scopes are visible only on Modrinth, which has no read-only introspection endpoint; a write probe is not used |
| Modrinth accepts the version's loaders, game versions and dependency | Modrinth | Validated only when the version is created |
| Hangar accepts the uploaded file, platform versions and dependency | Hangar | The task-graph dry run checks the tasks, not the server's validation |

### Repository Variables

| Variable | Used by | Default when unset |
| :--- | :--- | :--- |
| `MODRINTH_PROJECT` | Modrinth step, as the project ID or slug | `sessionpulse` |
| `HANGAR_PROJECT` | Hangar step, as the project slug (`-PhangarProject`) | `SessionPulse` |

Set them only if a registry project is created under a different slug, and set them on
the repository, not on the `release` environment: the `plan` job runs without the
environment and must check the same project the publish jobs write to.

### Partial Publication and Retry

Publication is not atomic: each destination publishes in its own job. A retry uses the
retained candidate and evidence of the original candidate run and never rebuilds. The
publisher checks GitHub's downloaded assets and release metadata, Modrinth's CDN download
and channel, and Hangar's file digest, CDN download and channel. Matching destinations
are skipped; missing destinations receive the retained jar. Conflicting or uncertain
bytes stop that destination for maintainer reconciliation rather than overwriting a
version. Inspect the published files against `manifest.json`, then decide how to recover.

**Retry a destination with a new dispatch, not with *Re-run failed jobs*.** A re-run is a
later attempt of the same run, and every publish job refuses a true attestation on any
attempt but the first. Re-running a failed job is useful only when its destination is
already complete, for example when Modrinth visibility outlasted the verification
retries or the Hangar page sync failed after the upload. Everything else is a new
dispatch that selects only the destinations still missing, with a fresh owner-inventory
check:

```bash
scripts/release.sh 0.2.0-beta.2 --resume modrinth --candidate-run <candidate run ID>
```

`--resume` takes the same comma-separated destination list as the workflow input and
asks for each selected registry's attestation again.

| Failed step | State | Retry |
| :--- | :--- | :--- |
| `plan` | Nothing published | Fix the cause, then dispatch again with the same destinations |
| Preflight in a publish job | Nothing published: every job runs the same preflight | Fix the credential or project setting, then dispatch the same destinations again |
| `publish-github` | GitHub missing or partial | `--resume github`; no attestation is asked. A partial asset set stops for manual reconciliation |
| `publish-modrinth`, before or during the upload | Modrinth version may be missing or a draft | Re-inspect the Modrinth owner inventory (a failed create can leave a draft), fix the cause, then `--resume modrinth` |
| `publish-modrinth`, verification after upload | Version uploaded, not yet visible | Re-run that job once the version is public, or `--resume modrinth` and answer `present` |
| `publish-hangar`, before or during the upload | Hangar version may be missing or hidden | Re-inspect the Hangar owner inventory, fix the cause, then `--resume hangar` |
| `publish-hangar`, page sync | Version complete | Re-run that job, or `--resume hangar` and answer `present` |
| Any job, candidate expired | No destination can be published from this candidate | See *Expired candidates* |

Only Modrinth `listed` status and Hangar `public` visibility count as complete,
with matching version, notes and channel, successful anonymous version lookup and
matching anonymously downloaded CDN bytes. Draft, scheduled, archived, unlisted,
new, needs-approval, hidden and soft-deleted versions require reconciliation.
Retain the read permissions above when rotating tokens; authentication or lookup
failure stops publication. After a new Modrinth upload, final verification
allows up to six retries, 20 seconds apart, only for a missing version or an
anonymous version HTTP 404. Metadata conflicts, nonlisted version states, invalid
responses, authentication failures and downloaded byte mismatches stop immediately.
Existing-version preflight never retries or treats uncertain absence as success.

#### Expired candidates

The candidate and its evidence are retained for 90 days. An expired artifact, an
incomplete test record or a moved tag stops the publisher. Never rebuild a jar for the
same release tag. A destination still missing when its candidate expires is not
published for that tag: record it below, and the next release reaches that destination
instead.

| Tag | Destination not published | Reason |
| :--- | :--- | :--- |
| *(none)* | | |

### Declared Minecraft Versions

The Minecraft versions declared to both registries are one explicit list, kept in
`build.gradle.kts` (`releaseGameVersions`) and in `release.yml` (`game-versions`): 1.20.4
to 1.20.6, 1.21 to 1.21.11, and 26.3. Edit both together. The 26.3 entry applies to
Paper and Spigot; document Folia's separate supported range in the README and store
description. Do not fill the gap with untested 26.1 or 26.2 versions.

Modrinth cannot attach a different Minecraft range to each loader in one version.
The 0.2.0-beta.1 version therefore lists Paper and Spigot only, preventing its 26.3
entry from implying Folia or untested Purpur support. The same JAR still supports
Folia on 1.20.4 through 1.21.11 and is available from GitHub Releases; existing
Modrinth versions keep their older Folia declarations. Hangar's PAPER identifier
covers the Paper distribution; the README and store description give Folia's
separate compatibility range.

### Store Descriptions

Both project descriptions are generated from `README.md` into
`docs/store-description.md` by `scripts/store-description.py`, and CI fails when the
committed file is out of date with the README or breaks the store content checks. Edit
the README, run
`python scripts/store-description.py`, and commit both files together.

* **Modrinth** is a manual paste. `mc-publish` uploads versions and their changelogs and
  has no description input. Copy everything below the generated comment at the top of
  `docs/store-description.md` into the project's description editor on Modrinth.
  Editing a description does **not** re-enter the review queue: if the project was
  rejected, fix the description and then use *Resubmit for review*, or it stays rejected.
* **Hangar** is written by the release workflow. After the version upload, the Hangar
  job runs `syncPluginPublicationMainResourcePagePageToHangar` when the version is the
  newest public version on its Hangar channel, which sends
  `docs/store-description.md` without its generated comment to the resource page
  (`PATCH /api/v1/pages/edit/<project>`, permission `edit_page`). The page therefore
  follows the newest tagged commit's README, on every tier that publishes to Hangar. The Gradle
  plugin only logs a rejected edit, so the task then reads the page back from the public
  `GET /api/v1/pages/main/<project>` endpoint and fails unless it matches exactly; the
  project has to be publicly visible for that read to succeed. Do not edit the page on
  Hangar by hand: the next release overwrites it.
