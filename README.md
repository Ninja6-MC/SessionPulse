# SessionPulse

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/assets/icon-transparent-dark.svg">
    <img src="docs/assets/icon-transparent-light.svg" width="160" height="160" alt="">
  </picture>
</p>

<p align="center">
  <b>Gentle session health reminders, playtime tracking, and optional session limits for PaperMC, Spigot &amp; Folia.</b>
</p>

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-GPLv3-blue.svg" alt="License: GPL v3" /></a>
</p>

Part of the [Ninja6-MC](https://github.com/Ninja6-MC) plugin suite.

---

## Status

🚧 **Pre-release** — the features below are implemented and exercised by CI, but no
version has been tagged yet. Once the first tag is cut, builds will appear on
[GitHub Releases](https://github.com/Ninja6-MC/SessionPulse/releases). Until then,
build from source or take the jar from a CI run.

---

## What it does

SessionPulse watches how long each player has been online and sends configurable,
non-intrusive health reminders — hydration, posture, eye breaks — at milestones
you define. It never kicks anyone by default; enforcement is an optional layer for
servers that want structured rest breaks (families, schools, wellness communities).

### Features

- **Configurable milestones** — any number of one-time alerts keyed by session minute.
- **Recurring overtime** — optional repeating reminders after a threshold (marathon safety net).
- **Optional enforcement** — graceful disconnect + rejoin cooldown, disabled by default.
- **AFK-aware** — pauses the session clock when idle (EssentialsX hook or built-in detection).
- **Multi-platform** — Paper, Spigot, Purpur, Folia via FoliaLib + Adventure.
- **Rich formatting** — MiniMessage on all platforms, action bar, sound, optional title.
- **Folia-ready** — region-safe schedulers throughout.

---

## Install

SessionPulse requires **Java 21**.

No version has been tagged yet, so there is no jar to download. Build one from source:

```bash
./gradlew build
```

The shaded jar is written to `build/libs/`. Alternatively, take it from the build
artifacts of a CI run on `main`.

Drop the jar into your server's `plugins/` directory and start the server.
`plugins/SessionPulse/config.yml` is generated on that first start, with every setting
at its default — enforcement off, two health milestones, overtime off. Edit it and run
`/spulse reload`; nothing in it needs a restart.

EssentialsX is an optional soft dependency: where it is installed, SessionPulse can use
its AFK state, and where it is not, a built-in idle timer is used instead.

---

## Documentation

- [Configuration Reference](docs/CONFIG.md) — every key in `config.yml`, its range and
  default, and what happens to a value that cannot be used.
- [Admin Guide](docs/ADMIN_GUIDE.md) — commands, permissions, the counted window, AFK
  detection, enabling enforcement safely, stored data and troubleshooting.

---

## Brand Assets

Two directories hold artwork, and they have opposite rules.

[`docs/assets/`](docs/assets/README.md) is **this repository's own** icon suite, authored
here and generated from [`icon-master.svg`](docs/assets/icon-master.svg) via
[`scripts/export-icons.mjs`](scripts/export-icons.mjs). Edit the master and re-export; do
not edit a generated file.

[`assets/`](assets/) holds the **shared organisation marks** and is **machine-managed**.
It is delivered by pull request from the asset-sync pipeline in the organisation's `brand`
repository, and its master lives there, not here. Anything hand-edited in it is overwritten
by the next sync, so changes to those marks belong in `brand`. See `N6-REPO-03` in the org
standards register, and [`docs/assets/README.md`](docs/assets/README.md) for the pipeline
and master by name.

Both conform to the Ninja6-MC brand identity system. (That system lives in the
organisation's private `brand` repository, so it is named rather than linked - a link
would 404 for everyone reading this.)

---

## License

[GNU General Public License v3.0](LICENSE).

---

<p align="center">
  <a href="https://github.com/Ninja6-MC"><img src="assets/ninja6-primary-256.png" width="48" height="48" alt=""></a>
</p>

<p align="center">
  <sub>A <a href="https://github.com/Ninja6-MC">Ninja6</a> project.</sub>
</p>
