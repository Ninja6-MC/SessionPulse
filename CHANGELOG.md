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
