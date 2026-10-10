# Changelog

All notable changes to SessionPulse will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/), and this project
adheres to [Semantic Versioning](https://semver.org/).

## [Unreleased]

## [0.2.0-beta.1]

### Added
- Minecraft 26.3 support on Paper and Spigot, using the same plugin JAR as older
  supported servers. Minecraft 26.3 servers require Java 25; the plugin remains
  compiled for Java 21. Folia support remains Minecraft 1.20.4 through 1.21.11.

## [0.1.0]

### Added
- Paper, Spigot and Folia support on Minecraft 1.20.4 to 1.21.11. Requires Java 21.
- `/spulse` (alias `/sessionpulse`) with the `sessionpulse.use`, `sessionpulse.admin` and
  `sessionpulse.exempt` permission nodes; `sessionpulse.admin` includes
  `sessionpulse.use`.
- The counted window: active time that accumulates across sessions and resets only after a
  player has been offline longer than `tracking.window-reset-hours`. Milestones and
  enforcement read the window rather than the current session, so quitting and rejoining
  cannot buy a fresh allowance. Lifetime playtime is a separate total that never resets.
- A commented default `config.yml`: every value carries its clamp range and the reason it
  exists. A value outside its range is clamped and the correction is logged with both
  numbers; a milestone entry that cannot be used is skipped and named by its position in
  the file, never replaced with a default. A malformed file produces warnings and a
  working plugin, never a failed load.
- Counted windows, lifetime playtime, last-seen and cooldowns persist to `data.yml` and
  survive a restart. The file is flushed periodically, after every quit and cooldown, and
  at shutdown. A `data.yml` that cannot be read is copied aside to `data.yml.unreadable`
  rather than overwritten, and a file stamped with a newer `schema-version` is never
  written over.
- Messages in MiniMessage on every platform, by chat, action bar, title and sound, with
  the configured prefix on chat only. The kick and login-refusal screens keep hex colours.
  `<player>`, `<hours>`, `<minutes>` and `<cooldown>` are filled as text, never as markup,
  so a player name cannot inject a click event.
- Milestones fire once per counted window. A reload, a rejoin or a restart in the same
  window does not fire them again, and a player holding `sessionpulse.exempt` never
  receives one.
- Overtime reminders, when enabled, repeat at `after-minutes` and every `every-minutes`
  after that, read against the counted window, so a rejoin, restart or reload never
  replays one. On a shared minute the milestone is sent first and the overtime line after
  it. Enabling or lowering overtime mid-session starts at the next point on the series.
- AFK awareness: the counted window pauses for a player who has stopped playing, as
  `tracking.afk.mode` says. AUTO uses EssentialsX where it is installed with `auto-afk`
  above 0, and the built-in idle timer otherwise. Players without `essentials.afk.auto`,
  which EssentialsX does not grant by default, are also paused by the idle timer, and a
  manual `/afk` counts wherever EssentialsX is bound. The idle timer counts moving into
  another block, steering a vehicle, clicks, inventory clicks, chat and commands, but not
  head rotation, pressure plates or riding a minecart. Water streams, bubble columns and
  knockback still count as movement, so a player parked in a current is not detected by
  the idle timer. EssentialsX is optional, and a server that removes it mid-run falls back
  to the idle timer. The detector in force is named in the log at startup and on reload.
- Optional enforcement, off by default: with `enforcement.enabled`, a player whose counted
  window reaches `at-minutes` is disconnected with `kick-message` and refused at login
  until `cooldown-minutes` have passed, with the time remaining on the refusal screen. The
  cooldown is saved before the disconnect, so a restart or a crash does not clear it. The
  enforced break ends the counted window, so a readmitted player starts a fresh allowance;
  lifetime playtime is kept. With enforcement off, the login check admits everyone, and a
  reload that turns it on applies on the next tick. Players holding `sessionpulse.exempt`
  are never disconnected.
- `/spulse time`, `top`, `reset` and `reload`, with tab completion. `time` shows a player
  their own counted window and lifetime playtime; with `sessionpulse.admin` it takes any
  name, online or offline, and an offline window that the next join would reset is shown
  as zero. `top` ranks the ten longest lifetimes, online players included. `reset` clears
  a player's counted window and any cooldown, online or offline, keeping lifetime and
  last-seen, so milestones fire again. `reload` re-reads `config.yml` and keeps the
  configuration in force if the file does not parse. Completion offers only what the
  sender may run, and only the online names they can see.
- The Admin Guide (`docs/ADMIN_GUIDE.md`) and configuration reference (`docs/CONFIG.md`).
- The licence notices of the bundled Adventure and FoliaLib components, in
  `META-INF/LICENSE` and `META-INF/THIRD_PARTY_NOTICES.md` inside the plugin jar.
