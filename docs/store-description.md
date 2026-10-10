<!-- Generated from README.md by scripts/store-description.py. Do not edit.
     Paste everything below this comment into the Modrinth description editor.
     The release workflow syncs it to the Hangar resource page. -->

Part of the [Ninja6-MC](https://github.com/Ninja6-MC) plugin suite.

---

## Status

**Pre-release.** Every version before 1.0.0 is a pre-release, and 1.0.0 will be the first
stable one. Until then, configuration keys and behaviour may still change between
versions; read the changelog before upgrading.

Alpha builds are published as GitHub pre-releases, as Modrinth alpha versions and to
Hangar's Alpha channel, which Hangar hides by default. Beta builds are published as
GitHub pre-releases, as Modrinth beta versions and to Hangar's Beta channel.

---

## What it does

SessionPulse watches how long each player has been online and sends configurable,
non-intrusive health reminders - hydration, posture, eye breaks - at milestones
you define. It never kicks anyone by default; enforcement is an optional layer for
servers that want structured rest breaks (families, schools, wellness communities).

### Features

- **Configurable milestones** - any number of one-time alerts keyed by minutes played.
- **Recurring overtime** - optional repeating reminders after a threshold (marathon safety net).
- **Optional enforcement** - graceful disconnect + rejoin cooldown, disabled by default.
- **No reset by rejoining** - playtime accumulates across sessions and resets only after
  a configurable time offline.
- **AFK-aware** - pauses the session clock when idle (EssentialsX hook or built-in detection).
- **Multi-platform** - Paper, Spigot and Folia, via FoliaLib + Adventure.
- **Rich formatting** - MiniMessage on all platforms, action bar, sound, optional title.
- **Folia-ready** - region-safe schedulers throughout.

---

## Requirements

The same plugin JAR runs on the following server versions:

| Server | Minecraft versions | Server Java runtime |
| --- | --- | --- |
| Paper | 1.20.4 to 1.21.11, and 26.3 | Java 21 for 1.x; Java 25 for 26.3 |
| Spigot | 1.20.4 to 1.21.11, and 26.3 | Java 21 for 1.x; Java 25 for 26.3 |
| Folia | 1.20.4 to 1.21.11 | Java 21 |

The plugin remains compiled for Java 21; upgrading an older supported server does not
require Java 25. Minecraft 26.3 support starts with **0.2.0-beta.1** and applies to Paper
and Spigot. Folia has no published 26.3 build and is not claimed for that version.
Minecraft 26.1 and 26.2 are not included in the declared support list.

Modrinth lists 0.2.0-beta.1 for Paper and Spigot because its version filters cannot
represent Folia's separate Minecraft range. Folia users can download the same JAR
from GitHub Releases for the supported older versions.

All three platforms have CI checks, including gameplay checks on Paper and Spigot 26.3
and Paper, Spigot and Folia 1.21.11. Purpur is a Paper fork and is expected to work, but
it is not tested.

- **EssentialsX** is optional. Where it is installed, SessionPulse can use its AFK state;
  where it is not, a built-in idle timer is used instead.

EssentialsX 2.22.0 logs an unsupported-server-version warning on Minecraft 26.3.
SessionPulse's AFK integration tests do not establish support for other EssentialsX
features on that version. Check EssentialsX's own compatibility guidance before
upgrading a server that depends on it.

---

## Install

1. Download the jar from
   [GitHub Releases](https://github.com/Ninja6-MC/SessionPulse/releases). Pre-releases
   are listed there too, marked *Pre-release*. The same builds are published to Modrinth
   and Hangar.
2. Drop it into your server's `plugins/` directory and start the server.
3. `plugins/SessionPulse/config.yml` is generated on that first start, with every setting
   at its default - enforcement off, two health milestones, overtime off.
4. Edit it and run `/spulse reload`; nothing in it needs a restart.

To build from source instead:

```bash
./gradlew build
```

The shaded jar is written to `build/libs/`.

---

## Commands

The command is `/spulse`, with the alias `/sessionpulse`.

| Command | Permission | What it does |
| --- | --- | --- |
| `/spulse time` | `sessionpulse.use` | Your own counted playtime and lifetime playtime. |
| `/spulse time <player>` | `sessionpulse.admin` | Another player's playtime, online or offline. |
| `/spulse top` | `sessionpulse.use` | The ten longest lifetime playtimes. |
| `/spulse reset <player>` | `sessionpulse.admin` | Clears a player's counted playtime and any rejoin cooldown. |
| `/spulse reload` | `sessionpulse.admin` | Re-reads `config.yml`, without a restart. |

---

## Permissions

| Node | Default | Grants |
| --- | --- | --- |
| `sessionpulse.use` | everyone | `/spulse time` for yourself, and `/spulse top`. |
| `sessionpulse.admin` | operators | `/spulse time <player>`, `reset` and `reload`. Includes `sessionpulse.use`. |
| `sessionpulse.exempt` | nobody | No reminders and no enforcement. Playtime is still counted. |

---

## Documentation

- [Configuration Reference](https://github.com/Ninja6-MC/SessionPulse/blob/main/docs/CONFIG.md) - every key in `config.yml`, its range and
  default, and what happens to a value that cannot be used.
- [Admin Guide](https://github.com/Ninja6-MC/SessionPulse/blob/main/docs/ADMIN_GUIDE.md) - commands, permissions, the counted window, AFK
  detection, enabling enforcement safely, stored data and troubleshooting.
- [Changelog](https://github.com/Ninja6-MC/SessionPulse/blob/main/CHANGELOG.md) - what changed in each version.

---

## Support

- **Questions and setup help:** [Discord](https://discord.gg/KEHaeHC8FB).
- **Bugs and feature requests:**
  [GitHub Issues](https://github.com/Ninja6-MC/SessionPulse/issues). Include the plugin
  version, the server software and version, and the steps that produced the problem.
- **Security vulnerabilities:** report them privately, as described in the
  [security policy](https://github.com/Ninja6-MC/.github/blob/main/SECURITY.md), never
  in a public issue.

---

## License

[GNU General Public License v3.0](https://github.com/Ninja6-MC/SessionPulse/blob/main/LICENSE).
