# Changelog

All notable changes to SessionPulse will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/), and this project
adheres to [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added
- Initial project scaffolding and repository setup.
- Gradle build: Java 21 toolchain, Shadow, `spigot-api` compile target, JUnit 5,
  and `processResources` templating of `plugin.yml`.
- `plugin.yml` declaring `/spulse` (alias `/sessionpulse`) and the
  `sessionpulse.use`, `sessionpulse.admin` and `sessionpulse.exempt` permission
  nodes, with `sessionpulse.admin` declaring `sessionpulse.use` as a child.
- `scripts/dev-server.sh`, which boots a local Paper server with the freshly built
  plugin installed.
- Continuous Integration workflow running Gradle wrapper validation, tests and the
  full build on every push to `main` and every pull request, uploading the shaded jar
  as a build artifact.
- `platform/Scheduler`, the plugin's only scheduling seam, with `FoliaLibScheduler`
  as its sole implementation and the only class permitted to name FoliaLib. The
  blanket cancel is deliberately absent from the interface, so a reload cannot reach
  it and silently stop the counting.
- FoliaLib and Adventure shaded into the plugin jar, relocated under
  `com.ninja6.sessionpulse.lib`, with `folia-supported: true` in `plugin.yml`. Every
  message goes through `BukkitAudiences` on all platforms, because the relocated
  Adventure is not the copy Paper ships.
- Boot legs in CI that enable and disable the plugin on Paper and Folia, and a build
  step that opens the packaged jar and proves the relocation applied.
