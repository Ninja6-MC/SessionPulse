# Changelog

All notable changes to SessionPulse will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/), and this project
adheres to [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added
- Initial project scaffolding and repository setup.
- Gradle build: Java 21 toolchain, Shadow, `spigot-api` compile target, JUnit 6,
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
  message goes through `Notifier`, which renders through `BukkitAudiences` on all
  platforms, because the relocated Adventure is not the copy Paper ships.
- Boot legs in CI that enable and disable the plugin on Paper and Folia, and a build
  step that opens the packaged jar and proves the relocation applied.
- `config/PluginConfig`, an immutable configuration snapshot, with the `Milestone`,
  `OvertimePolicy`, `EnforcementPolicy` and `AfkMode` types it reads. A reload builds a
  new snapshot and swaps the plugin's reference to it, so a long-lived service holds
  `() -> config` rather than the object.
- The default `config.yml`, shipped and commented: every value carries its clamp range
  and the reason it exists. A scalar outside its range is clamped and the correction is
  logged with both numbers; a milestone entry that cannot be used is skipped and named by
  its position in the file, never replaced with a default. Nothing in configuration
  loading throws, so a malformed file produces warnings and a working plugin.
- Boot-test assertions that the shipped `config.yml` reaches the data folder and loads on
  a real server without producing a single validation warning.
- `session/SessionTracker` and the counted window: active seconds that accumulate across
  sessions and reset only after a player has been offline longer than
  `tracking.window-reset-hours`. Milestones and enforcement read the window rather than the
  current session, so quitting and rejoining cannot buy a fresh allowance. Lifetime playtime
  is a separate total that never resets. In-session time is measured monotonically and
  everything persisted is wall clock, because `System.nanoTime()` has no meaning across a
  restart.
- `storage/YamlDataStorage`: counted windows, lifetime playtime, last-seen and cooldowns
  persist to `data.yml` and survive a restart. Writes are in memory; the file is written by
  a periodic async flush, by a one-shot flush after every quit and cooldown, and
  synchronously at disable, and every write is forced to disk before it replaces the file.
  A `data.yml` that cannot be parsed, is blank, or has the wrong shape is copied aside to
  `data.yml.unreadable` rather than overwritten; a misshapen one is then repaired so the
  next start finds a clean file. A file stamped with a newer `schema-version` is never
  written over.
- `notify/Notifier`, the one door to a player's screen: chat, action bar, title and sound
  rendered from MiniMessage, with the configured prefix on chat only, and a legacy
  section-sign render for the String-only kick and pre-login screens that keeps hex
  colours. `notify/Placeholders` fills `<player>`, `<hours>`, `<minutes>` and
  `<cooldown>` as text, never as markup, so a player name cannot inject a click event.
- Configured milestones fire once per counted window, through `Notifier` on the player's
  own region. A reload, a rejoin or a restart in the same window does not fire them again,
  and a player holding `sessionpulse.exempt` never receives one.
- The overtime reminder, when enabled, repeats at `after-minutes` and every
  `every-minutes` after that, read against the counted window, so time spent AFK brings
  none forward and a rejoin, restart or reload never replays one. On a shared minute the
  milestone is sent first, on all of its channels including title and action bar, and the
  overtime chat line after it. Enabling or lowering overtime mid-session starts at the next
  point on the series.
- AFK awareness: the counted window pauses for a player who has stopped playing, as
  `tracking.afk.mode` says. AUTO uses EssentialsX where it is installed with `auto-afk`
  above 0, and the built-in idle timer otherwise; players without `essentials.afk.auto`
  are also paused by the idle timer, and a manual `/afk` always counts. The idle timer
  counts moving into another block, steering a vehicle, clicks, inventory clicks, chat and
  commands, but not head rotation, pressure plates or riding a minecart. Water streams,
  bubble columns and knockback arrive as ordinary movement and still count, so a player
  parked in a current is not detected by the idle timer. EssentialsX is reached by reflection on the player's
  own region, so a server without it, or one that removes it mid-run, falls back rather
  than failing. The detector in force is named in the log at startup and on reload.
  `plugin.yml` soft-depends on `Essentials`.
- Optional enforcement, off by default: with `enforcement.enabled`, a player whose counted
  window reaches `at-minutes` is disconnected with `kick-message` and refused at pre-login
  until `cooldown-minutes` have passed, with the time remaining on the refusal screen. The
  cooldown is written and flushed before the disconnect, so a restart or a crash does not
  clear it. The enforced break ends the counted window, so a player readmitted after the
  cooldown starts a fresh allowance instead of being disconnected again at once; lifetime
  playtime is kept. A reload that turns enforcement on applies on the next tick, and with
  enforcement off the login gate admits everyone. Players holding `sessionpulse.exempt`
  are never disconnected.
- `/spulse time`, `top`, `reset` and `reload`, with tab completion. `time` shows a player
  their own counted window and lifetime playtime; with `sessionpulse.admin` it takes any
  name, online or offline, and an offline window that the next join would reset is shown
  as zero. `top` ranks the ten longest lifetimes, online players included. `reset` clears a
  player's counted window and any cooldown, online or offline, keeping lifetime and
  last-seen, so milestones fire again as the new window crosses them. `reload` re-reads
  `config.yml`, reschedules the flush and the session tick by their own handles, and
  leaves the configuration in force if the file does not parse. Completion offers only
  what the sender may run, and only the online names they can see.
- A gameplay smoke test on the Paper and Folia 1.21.11 legs: a protocol bot joins a
  real server and the leg asserts, from what the bot received, that a milestone
  delivers its chat line, action bar, title, subtitle and sound, that enforcement kicks
  at the threshold, that the cooldown refuses the next login, and that the login is let
  through once the cooldown lapses. `boot-test.sh` is renamed `smoke-test.sh`, and the
  1.20.4 legs remain boot-only.
- `SessionPulseProbeBot`, the MCProtocolLib client behind it, built by the
  `botClientJar` task into `build/test-fixtures` and never wired into `build`.
- A platform argument for `scripts/dev-server.sh` (`[paper|folia] [mc-version]`, with
  the old version-only form still booting Paper), and `scripts/dev-server.ps1`, the
  same dev server for Windows PowerShell 5.1.
