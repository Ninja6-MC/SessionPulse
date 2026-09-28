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
  * `v1.0.0-rc.1` (Release candidate)

**Before 1.0.0, every release is a pre-release.** Alphas are for internal testing and
betas for public testing; `1.0.0` is the first stable release. Alphas are still public:
each one is a GitHub pre-release, is published to Hangar's *Alpha* channel, which is
marked unstable and hidden by default, and reaches Modrinth's *alpha* channel once the
Modrinth project is approved. Do not push an unsuffixed tag below `v1.0.0`: `release.yml`
publishes every unsuffixed tag as a stable release, as *Latest* on GitHub and on the
*release* channel of Modrinth and Hangar.

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
| **Beta / RC** | `vX.Y.Z-beta.N`, `vX.Y.Z-rc.N` | `main` | Feature-Complete | GitHub Releases (*Pre-release*), Modrinth (*beta*), Paper Hangar (*Beta*) |
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

Tag the release on `main`:

```bash
git tag -a v0.1.0-alpha.1 -m "v0.1.0-alpha.1"
git push origin v0.1.0-alpha.1
```

Push release tags one at a time, by name. GitHub fires no tag push events at all when more
than three tags are pushed at once, so `git push --tags` after several tags publishes
nothing and reports nothing.

### Step 3: Automated CI Actions
GitHub Actions (`.github/workflows/release.yml`) will:
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
   and SHA-256 digests. Retain them as one Actions artifact for 30 days.
9. Download the candidate in three independent smoke jobs. Each checks the manifest,
   inventory, digests, embedded `plugin.yml` version, relocation, notices and service
   entries before running the existing Paper, Folia or Spigot 1.21.11 protocol smoke
   path on that exact jar. A fourth job binds three passing receipts to the candidate
   ID and digest and retains the evidence for 30 days.
10. Wait for approval on the protected `release` environment. The publisher downloads
    and checks the retained candidate and evidence, and resolves the tag on `origin`
    before every destination. A moved tag, expired artifact, failed test record or
    changed byte stops publication.
    Before approval, inspect the verified candidate summary and complete registry
    absence reconciliation as described below. No omitted public API result authorizes
    an upload by itself.
11. Publish the unchanged jar to GitHub Releases, Modrinth and Paper Hangar in that
    order. Pre-releases retain their tier; only stable tags become GitHub Latest. Both
    registries declare EssentialsX as an optional, unpinned dependency
   (`dependencies` on the `mc-publish` step; an external-URL dependency pointing at its
   Modrinth page in `hangarPublish`, since EssentialsX is not on Hangar). Keep the two
   in step with `softdepend` in `plugin.yml`.
12. Sync the Hangar resource page from `docs/store-description.md`, in the Hangar step
    and only after its version upload succeeded. See [Store Descriptions](#store-descriptions).

**Requires Java 21.** The plugin is built for Java 21 and declares it on Modrinth; every
server must run on Java 21 or newer to load it, including 1.20.4-1.20.6, which
Minecraft itself allows on Java 17.

### Release Environment and Secrets

The `release` environment requires maintainer approval, accepts `v*` tags only and
disallows administrator bypass. Build, smoke and evidence jobs have read permission and
receive no publishing credentials. Only the gated publisher has `contents: write` and
registry secrets. Never approve a deployment from automation.

| Environment secret | Used by | Required scope |
| :--- | :--- | :--- |
| `MODRINTH_TOKEN` | Modrinth lookup and upload | Read projects and versions; create versions on the SessionPulse project |
| `HANGAR_API_TOKEN` | Hangar lookup, version upload and page sync | Project member's key with `view_public_info`, `create_version`, `edit_page` |

A Hangar API key holding only `create_version` still uploads the version, but Hangar
rejects the page sync that follows. That fails the job whenever the page text changed;
when it did not, the only sign is an `Error using endpoint` line in the step log. Replace
the key with one holding both permissions before the first release that runs the sync.

Both tokens are required. Issue #79 tracks their migration: create new tokens, add them
as `release` environment secrets, delete the repository secrets, and revoke the old
tokens. Set the **environment variable** `RELEASE_SECRETS_MIGRATED=true` only after
those checks. The publisher fails before any destination write while it is unset. A
repository secret with the same name can otherwise be resolved by a job using the
environment, so the migration cannot be certified by workflow syntax alone.

### Repository Variables

| Variable | Used by | Default when unset |
| :--- | :--- | :--- |
| `MODRINTH_PROJECT` | Modrinth step, as the project ID or slug | `sessionpulse` |
| `HANGAR_PROJECT` | Hangar step, as the project slug (`-PhangarProject`) | `SessionPulse` |

Set them only if a registry project is created under a different slug.

### Partial Publication and Retry

The destination order is GitHub, Modrinth, Hangar, then Hangar page sync. Publication is
not atomic. Use **Re-run failed jobs** on the original run while its candidate and test
evidence still exist; the publisher uses the original candidate attempt and needs a new
environment approval. Do not re-run all jobs to manufacture a replacement candidate.
The publisher checks GitHub's downloaded assets and release metadata, Modrinth's CDN
download and channel, and Hangar's file digest, CDN download and channel. Matching
destinations are skipped; missing destinations receive the retained jar. Each upload
is checked against the public consumer download before the next destination proceeds.
Conflicting or uncertain bytes stop the retry for maintainer reconciliation rather
than overwriting a version. Inspect the published files against `manifest.json`, then
decide how to recover the remaining work.

Registry lookups use the environment tokens, but Modrinth's version list excludes
draft and unlisted versions even when authenticated, and Hangar can conceal
soft-deleted versions. An omitted version or HTTP 404 therefore stops publication
by default. Inspect the owner inventory in each registry, including drafts, unlisted,
scheduled, hidden and deleted versions, before confirming that the intended version
does not exist. Resolve any existing nonpublic version manually; do not attest that
it is absent or create another version over it.

After that audit, set the `release` environment variable
`RELEASE_ABSENCE_RECONCILIATION` to JSON using the exact fields from the verified
candidate's manifest and only the audited destinations, then approve that candidate:

```json
{
  "candidate_id": "<manifest candidate_id>",
  "source_sha": "<manifest source_sha>",
  "tag": "<manifest tag>",
  "version": "<manifest version>",
  "confirmed_absent": {
    "modrinth": "sessionpulse",
    "hangar": "SessionPulse"
  }
}
```

Use the configured project IDs or slugs if their repository variables differ from
the defaults. This record is rejected for a different candidate, tag, version,
source commit, project or destination. Replace it for each new candidate; it cannot
authorize a rebuild or a new run's candidate. Re-runs of the publisher retain the
same candidate and require approval again. Matching existing destinations are
checked before this record is considered, so a stale absence assertion cannot
override conflicting bytes or nonpublic metadata.

Only Modrinth `listed` status and Hangar `public` visibility count as complete,
with matching version, notes and channel, successful anonymous version lookup and
matching anonymously downloaded CDN bytes. Draft, scheduled, archived, unlisted,
new, needs-approval, hidden and soft-deleted versions require reconciliation.
Retain the read permissions above when rotating tokens; authentication or lookup
failure stops publication.

A failed page sync after a successful Hangar upload can be retried: the matching
version is verified and skipped, then the page sync runs again. Missing or expired
retention artifacts, an incomplete test record, or a moved tag require manual
reconciliation. Never rebuild a jar for the same release tag.

The Minecraft versions declared to both registries are one explicit list, kept in
`build.gradle.kts` (`releaseGameVersions`) and in `release.yml` (`game-versions`): 1.20.4
to 1.20.6 and 1.21 to 1.21.11. Edit both together.

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
  step runs `syncPluginPublicationMainResourcePagePageToHangar`, which sends
  `docs/store-description.md` without its generated comment to the resource page
  (`PATCH /api/v1/pages/edit/<project>`, permission `edit_page`). The page therefore
  follows the tagged commit's README, on every tier that publishes to Hangar. The Gradle
  plugin only logs a rejected edit, so the task then reads the page back from the public
  `GET /api/v1/pages/main/<project>` endpoint and fails unless it matches exactly; the
  project has to be publicly visible for that read to succeed. Do not edit the page on
  Hangar by hand: the next release overwrites it.
