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

---

## 2. Release Tiers & Distribution Channels

Every tier is cut by tagging a commit on `main`. The tag decides the tier, not the branch —
there is no separate release branch.

```
                    [ feat/… fix/… docs/… topic branches ]
                                      │
                                      ▼
                            [ PR squashed into main ]
                                      │
                ┌─────────────────────┴─────────────────────┐
                ▼                                           ▼
   ┌─────────────────────────┐                 ┌─────────────────────────┐
   │  ALPHA (vX.Y.Z-alpha.N) │                 │  BETA / RC (-beta, -rc) │
   │ • Experimental          │                 │ • Feature-Complete      │
   │ • Internal / Staging    │                 │ • Public Testing        │
   │ • GitHub Pre-release    │                 │ • GitHub Pre-release    │
   │ • Modrinth alpha        │                 │ • Modrinth beta         │
   │                         │                 │ • Hangar Beta           │
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
| **Alpha** | `vX.Y.Z-alpha.N` | `main` | Experimental | GitHub Releases (*Pre-release*), Modrinth (*alpha*) |
| **Beta / RC** | `vX.Y.Z-beta.N`, `vX.Y.Z-rc.N` | `main` | Feature-Complete | GitHub Releases (*Pre-release*), Modrinth (*beta*), Paper Hangar (*Beta*) |
| **Market (GA)** | `vX.Y.Z` | `main` | Production Stable | GitHub Releases (*Latest*), Modrinth (*release*), Paper Hangar (*Release*), SpigotMC (*manual*) |

Notes on the table:

* **Modrinth has no "featured" channel.** A stable tag publishes a *release* version.
  Featuring it on the project page is done by hand on Modrinth afterwards.
* **SpigotMC is manual.** SpigotMC has no upload API; post the GA jar from the GitHub
  release as a resource update yourself.
* **Alpha skips Hangar.** Alphas go to GitHub and Modrinth only. SpiralGenesis differs: it
  publishes alphas to a Hangar *Alpha* channel. SessionPulse's Hangar project needs only
  *Beta* and *Release* channels.
* **Release candidates publish as beta.** Neither registry has a release-candidate tier.

---

## 3. How to Execute a Release

### Step 1: Pre-Release Checklist
1. All target PRs merged into `main`, and **CI green on the commit you are about to tag**.
   This is the gate. The release job runs `test` and `shadowJar`, not `build`, so the
   sources and javadoc jars, and everything else `build` checks, are proven only by CI.
2. Run test suite locally:
   ```bash
   ./gradlew test
   ```
3. Update `CHANGELOG.md`. A stable release needs a `## [X.Y.Z]` section, merged to `main`
   before tagging. A pre-release may ship from `## [Unreleased]`.

### Step 2: Cut the Tag

Tag the release on `main`:

```bash
git tag -a v0.1.0 -m "v0.1.0"
git push origin v0.1.0
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
6. Build the shaded, relocated `SessionPulse-<version>.jar` (FoliaLib and Adventure
   relocated under `com.ninja6.sessionpulse.lib`, with `META-INF/LICENSE` and
   `META-INF/THIRD_PARTY_NOTICES.md`), and check it is the only jar in `build/libs`.
7. Compute its SHA-256 checksum (`SessionPulse-<version>.jar.sha256`, holding a bare
   filename so `sha256sum -c` works beside the downloaded jar).
8. Publish the jar, the checksum and the notes to GitHub Releases, as a pre-release for
   `-alpha`, `-beta` and `-rc` tags and as the latest release for a stable tag.
9. Publish to Modrinth and to Paper Hangar (beta, rc and stable only). Once the GitHub
   release exists the two registries are independent: a Modrinth failure fails the run
   but does not stop the Hangar step.

**Requires Java 21.** The plugin is built for Java 21 and declares it on Modrinth; every
server must run on Java 21 or newer to load it, including 1.20.4-1.20.6, which
Minecraft itself allows on Java 17.

### First Run

The first pre-release tag pushed to this repository is the rehearsal for the pipeline.
Issue #32 stays open until that run produces a complete GitHub pre-release (jar, checksum
and notes) with both registry steps skipped, because no registry token is configured yet.
Add the registry tokens after that run, not before.

### Repository Secrets

| Secret | Used by | Publishing is skipped if absent |
| :--- | :--- | :--- |
| `MODRINTH_TOKEN` | Modrinth step (`mc-publish`) | Yes |
| `HANGAR_API_TOKEN` | Hangar step (`publishPluginPublicationToHangar`) | Yes |

Neither is required for a release to succeed. Without them the workflow still tests,
builds and publishes to GitHub Releases, and simply skips the registry it has no token
for. The workflow uses no deployment environment.

Each token reaches only the step that uploads with it. An early step records whether each
secret is set, without printing it, and the publish steps are gated on that; the tests and
the Gradle build never see either token.

### Repository Variables

| Variable | Used by | Default when unset |
| :--- | :--- | :--- |
| `MODRINTH_PROJECT` | Modrinth step, as the project ID or slug | `sessionpulse` |
| `HANGAR_PROJECT` | Hangar step, as the project slug (`-PhangarProject`) | `SessionPulse` |

Set them only if a registry project is created under a different slug.

### When a Registry Step Fails

Publishing is **not atomic**. The GitHub release is created first; after it, the Modrinth
and Hangar steps are independent of each other, and whatever succeeded stays published.

**Prefer "Re-run failed jobs"** on the failed workflow run. The job runs again from the
start on a fresh runner: it re-tests and rebuilds the jar there, replaces the jar and
`.sha256` on the GitHub release with the rebuilt pair, and tries both registries again.
The registry that succeeded the first time rejects the duplicate version and that step
fails, so the re-run ends red even when it did its job; read the step results, not the
run's colour.

If a re-run is not possible, publish the failed registry by hand:

* **Modrinth:** download the jar from the GitHub release and upload it as a new version
  with the same version number, channel, loaders, game versions and changelog as the
  workflow uses. This keeps the bytes matching the release's `.sha256`.
* **Hangar:** from a checkout of the tagged commit, with `HANGAR_API_TOKEN` set and the
  release notes saved as `build/release-notes.md`, run
  `./gradlew publishPluginPublicationToHangar -PpluginVersion=<version> -PhangarChannel=<Beta|Release> -PhangarProject=<HANGAR_PROJECT or SessionPulse>`.
  The task rebuilds the jar locally, so the bytes uploaded to Hangar will not match the
  `.sha256` on the GitHub release. Without `build/release-notes.md` the Hangar changelog
  falls back to "See CHANGELOG.md for this version."

The Minecraft versions declared to both registries are one explicit list, kept in
`build.gradle.kts` (`releaseGameVersions`) and in `release.yml` (`game-versions`): 1.20.4
to 1.20.6 and 1.21 to 1.21.11. Edit both together.
