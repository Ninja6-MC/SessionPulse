# SessionPulse Admin Guide

Running SessionPulse on a server: installing it, the commands and permissions, how the
counted window and the AFK detectors behave, how to turn enforcement on without locking
anybody out, and what to do when something looks wrong.

For the meaning of every key in `config.yml`, see the
[Configuration Reference](CONFIG.md).

---

## Contents

1. [Installing](#1-installing)
2. [Commands](#2-commands)
3. [Permissions](#3-permissions)
4. [The counted window](#4-the-counted-window)
5. [AFK detection](#5-afk-detection)
6. [Milestones and overtime](#6-milestones-and-overtime)
7. [Turning enforcement on safely](#7-turning-enforcement-on-safely)
8. [Surprising behaviour](#8-surprising-behaviour)
9. [Stored data](#9-stored-data)
10. [Troubleshooting](#10-troubleshooting)

---

## 1. Installing

SessionPulse requires **Java 21**. It declares API version 1.20 and is declared for
Minecraft 1.20.4 through 1.21.11.

Paper, Spigot and Folia are each exercised by the project's CI. Purpur is a Paper fork;
the plugin is expected to run there, but CI does not test it.

Download `SessionPulse-<version>.jar` from
[GitHub Releases](https://github.com/Ninja6-MC/SessionPulse/releases). Every version
before 1.0.0 is a pre-release and is listed there marked *Pre-release*; 1.0.0 will be the
first stable one, so expect configuration keys and behaviour to change between versions
until then, and read the changelog before upgrading. Alpha builds are published as GitHub
pre-releases, as Modrinth alpha versions and to Hangar's Alpha channel, which Hangar
hides by default; beta builds go to Modrinth's beta and Hangar's Beta channels.

Each jar on GitHub Releases has a `.sha256` file beside it. Download both into the same
directory and check the jar before installing it:

```bash
sha256sum -c SessionPulse-<version>.jar.sha256
```

On Windows, compare the hash printed by `Get-FileHash SessionPulse-<version>.jar` in
PowerShell with the one in the `.sha256` file.

If no published jar suits you, build it from source instead:

```bash
./gradlew build
```

The shaded jar appears under `build/libs/`.

Drop the jar into your server's `plugins/` directory and start the server. On first start
the plugin writes `plugins/SessionPulse/config.yml`; `data.yml` is created by the first
flush.

EssentialsX is an optional soft dependency. If it is present, SessionPulse can use its
AFK state; if it is absent, the built-in idle timer is used instead. See
[section 5](#5-afk-detection).

A healthy start logs, among other lines:

```
SessionPulse enabled (scheduler: <platform scheduler>).
Configuration loaded: 2 milestone(s), overtime off, enforcement off.
Storage loaded: 0 player record(s) from data.yml.
```

---

## 2. Commands

The command is `/spulse`, with the alias `/sessionpulse`.

| Command | Who | What it does |
| --- | --- | --- |
| `/spulse time` | anyone | Your own counted window and lifetime playtime. |
| `/spulse time <player>` | admin | Another player's counted window and lifetime, online or offline. |
| `/spulse top` | anyone | The ten longest lifetime playtimes. |
| `/spulse reset <player>` | admin | Clears a player's counted window and any cooldown. |
| `/spulse reload` | admin | Re-reads `config.yml`. |

**`time`.** Naming another player requires `sessionpulse.admin`, and the permission is
checked before the lookup, so an unprivileged player cannot use the command to discover
who exists. Offline players are supported. An offline counted window that the player's
next join would reset is reported as zero, because that is what it will be. A record
that holds only a cooldown — no playtime — answers `No playtime recorded for <player>.`
From the console the command must always name a player; the console has no window of its
own.

**`top`.** Ranks by **lifetime** playtime, not by the counted window, and includes both
stored records and players online right now. Records with zero lifetime, or with no
stored name, are skipped. Ties are broken by name and then by UUID, so the order is
stable between runs.

**`reset`.** Works on online and offline players. It clears the counted window and any
cooldown; lifetime playtime and last-seen are kept. Milestones re-arm, so they fire
again as the new window crosses them. The target is not told. A player holding
`sessionpulse.exempt` can be reset like anyone else — that is how you lift a cooldown
written before the exemption was granted.

The reply names what it actually did: `Reset <player>'s counted window and cooldown.`
only when a cooldown was still running. A cooldown that had already lapsed is cleared
without being mentioned. An unknown name answers `No playtime recorded for <player>.`

**`reload`.** Re-reads every key. Nothing in `config.yml` requires a restart. If the
file does not parse, the configuration already in force is kept — see
[section 10](#10-troubleshooting).

Help output and tab completion show only the subcommands the sender may actually run,
and completion offers only the online names that sender can already see.

---

## 3. Permissions

| Node | Default | Grants |
| --- | --- | --- |
| `sessionpulse.use` | everyone | `/spulse time` for yourself, `/spulse top`. |
| `sessionpulse.admin` | operators | `time <player>`, `reset`, `reload`. |
| `sessionpulse.exempt` | nobody | Never receives reminders, is never disconnected. |

`sessionpulse.admin` declares `sessionpulse.use` as a child, so a permissions manager
such as LuckPerms will show `sessionpulse.use` as inherited by anyone holding
`sessionpulse.admin`. You do not need to grant both.

`sessionpulse.exempt` exempts a player from milestones, overtime and enforcement. It
does **not** stop their time being counted: exempt players still accrue a counted window
and lifetime playtime, and they still appear in `/spulse top`.

---

## 4. The counted window

Every player has two totals.

**Lifetime playtime** never resets. It is what `/spulse top` ranks.

**The counted window** is the total that milestones, overtime and enforcement all read.
It accumulates across sessions, and it resets to zero **only at a join**, and only when
the player has been offline for strictly longer than `tracking.window-reset-hours`. A
player's first ever join starts a fresh window. Because the window is what everything
reads, quitting and rejoining cannot buy a fresh allowance — the point of the design.

Time is credited in intervals while the player is online and not AFK; an interval during
which the player was AFK is not credited.

`last-seen` is stamped when a player quits and again at every periodic flush, so a crash
leaves it at most one `flush-interval-minutes` stale — and costs at most that much
counted time. A quit writes and flushes immediately, a milestone or overtime point
checkpoints and flushes immediately, and a clean shutdown finalises everything
synchronously.

---

## 5. AFK detection

Which detector is in force is decided at startup, again on every reload, and again
whenever EssentialsX is enabled or disabled while the server is running. The detector in
force is named in the log each time it is resolved. The EssentialsX behaviour here was
verified against EssentialsX 2.20.1.

| `tracking.afk.mode` | EssentialsX state | Who is treated as AFK |
| --- | --- | --- |
| `"OFF"` | any | Nobody. The clock never pauses. |
| `BUILT_IN` | any | Everyone, by the idle timer. |
| `ESSENTIALS` | installed and bound | Only players EssentialsX marks AFK. A warning is logged if EssentialsX's own `auto-afk` is off. |
| `ESSENTIALS` | absent, or the bind failed | Nobody, with a warning. |
| `AUTO` | bound, `auto-afk` above 0 | Players holding `essentials.afk.auto` are marked by EssentialsX; everyone else is AFK if either EssentialsX or the idle timer says so. |
| `AUTO` | bound, `auto-afk` 0 or off | The idle timer. |
| `AUTO` | absent | The idle timer. |
| `AUTO` | bind failed | The idle timer, with a warning. |

`essentials.afk.auto` is not granted by default, which is why `AUTO` blends the two: on
a typical server EssentialsX marks almost nobody automatically, and the idle timer
covers the rest.

If the EssentialsX detector stops working part-way through a run, SessionPulse falls
back rather than failing.

**A manual `/afk` only counts where EssentialsX is bound.** Under `BUILT_IN`, under
`"OFF"`, and under `AUTO` on a server without a usable EssentialsX, `/afk` pauses
nothing — only the idle timer does.

**What the idle timer counts as input:**

- moving into another block;
- steering a vehicle other than a minecart;
- interacting or clicking;
- inventory clicks;
- chat;
- commands.

An event that another plugin cancels still counts as input. Moving *within* the same
block does not count, and neither do head rotation alone, pressure plates, or riding a
minecart as a passenger.

Knockback, water streams, bubble columns and a boat carried by a current all arrive as
ordinary movement, so they count as input too. A player parked in a current is never
detected as idle by the timer.

AFK verdicts are re-evaluated on a tick, so a verdict can lag the player's last input by
one tick. That does not affect the counted totals in any way an operator would notice.

---

## 6. Milestones and overtime

Milestones are one-time alerts keyed by minute of the counted window; overtime is a
repeating chat reminder on its own schedule. Both are configured in `config.yml` — see
the [Configuration Reference](CONFIG.md) for the fields and the rules that decide
whether an entry is used.

What matters operationally:

- Each milestone fires **once per counted window**. A rejoin, a restart or a reload in
  the same window does not fire it again.
- A milestone or overtime point that passes while the player is offline is **lost, not
  repeated**. The player is not caught up on it at their next join.
- A milestone you add, and an overtime series you enable or lower, **never fire for a
  point already behind the current window**. The next point on the series is the first
  that can fire.
- Where an overtime point lands on a milestone minute, the milestone is delivered first
  on all of its channels, and the overtime chat line after it.
- Write short thresholds with `<minutes>`, not `<hours>`: `<hours>` is truncated to one
  decimal, so a 20-minute milestone reads `0.3` and anything under 6 minutes reads `0.0`.
  See [Placeholders](CONFIG.md#9-placeholders).
- Players holding `sessionpulse.exempt` receive neither.

If an entry never fires, check the log for a warning naming it by its position in the
list; a skipped entry is named there and nowhere else.

---

## 7. Turning enforcement on safely

Enforcement is off by default. With `enforcement.enabled: false` the login gate admits
everyone, which makes that one line a release valve you can reach for at any time.

When it is on, a player whose counted window reaches `enforcement.at-minutes` is
disconnected with `enforcement.kick-message`, and refused at pre-login until
`enforcement.cooldown-minutes` have passed. The refusal screen shows the time remaining.

The order of operations when it fires: the exemption is checked, the configuration is
re-checked, the session is confirmed to still be the live one, the cooldown is
written, the counted window is reset and checkpointed, everything is flushed to disk,
and only then is the player disconnected. That is why a restart or a crash does not
clear a cooldown.

Because the disconnect resets the counted window, a player readmitted after the cooldown
starts a fresh `at-minutes` rather than being disconnected again at once. Lifetime
playtime is kept.

**Before you turn it on:**

1. **Do not leave `tracking.afk.mode` at `"OFF"`.** With no AFK detection, time spent
   away from the keyboard counts towards the disconnect.
2. **Grant `sessionpulse.exempt` to staff** before enabling, not after — see the
   caveats below.
3. **Check `/spulse time` for a few regulars first.** The counted window may already be
   past your intended `at-minutes`, and a reload that turns enforcement on disconnects
   anyone already over it on the next tick.
4. **Keep `enabled: false` in mind as the way out.** A reload with it back to `false`
   opens the gate again immediately.

**Caveats to know about:**

- An exempt player never has a cooldown written, but the login gate does not check
  exemptions. Granting `sessionpulse.exempt` therefore does **not** lift a cooldown
  written before the grant. Use `/spulse reset <player>`.
- An exemption is consumed per connection, so a player who loses it mid-session is
  covered only until they reconnect.
- If another plugin cancels the disconnect, the player stays online. The cooldown has
  already been written by then.
- If the player disconnects on their own in the moment before the disconnect task runs,
  nothing happens at all: no kick, and no cooldown is written. Their next connection is
  judged afresh on its first tick.
- `enforcement.cooldown-minutes` applies to the next disconnect only. Changing it does
  not shorten a cooldown already running.

---

## 8. Surprising behaviour

Things that are working as intended but do not look like it.

- **Exempt players still appear in `/spulse top`** and still accrue both totals. The
  exemption is about what is sent to them, not what is counted.
- **`/spulse time` can report zero for an offline player** who has a stored window. It
  reports what the window will be at their next join, and that join will reset it.
- **A system clock moved backwards lengthens a cooldown already on record**, because the
  cooldown is stored as a wall-clock expiry; a clock moved forwards shortens one, and can
  end it early. The same backwards jump makes a player's offline gap negative, so their
  counted window is never reset by it; a forwards jump can reset a window early.
- **The disconnect and refusal screens lose gradients and hover events.** Those two
  screens predate modern text formatting and are rendered through the legacy serializer.
  Plain colours survive.
- **A reload never replays anything.** See [section 6](#6-milestones-and-overtime).
- **A hard crash costs at most one `flush-interval-minutes`** of counted time, and leaves
  `last-seen` at most that stale.
- **`A session observer threw for <name>. Counting continues.`** in the log means one
  reminder or enforcement check failed for one player. Counting is unaffected; report it,
  but the server is fine.

---

## 9. Stored data

Everything persistent lives in `plugins/SessionPulse/data.yml`.

```yaml
schema-version: 1
players:
  <uuid>:
    name: Steve
    lifetime-seconds: 0
    window-start: 0
    window-seconds: 0
    last-seen: 0
    cooldown-expires: 0
```

| Field | Meaning |
| --- | --- |
| `name` | Last known name, used by `/spulse top` and by name lookups. |
| `lifetime-seconds` | Lifetime playtime. Never reset by the plugin. |
| `window-start` | Wall-clock start of the counted window, in milliseconds. |
| `window-seconds` | Counted seconds in the current window. |
| `last-seen` | Wall clock in milliseconds; `0` means never seen. |
| `cooldown-expires` | Wall clock in milliseconds; `0` means no cooldown. |

Writes are held in memory and written out by the periodic flush, by a one-shot flush
after every quit and every written cooldown, and synchronously at shutdown. Each write
is forced to disk before it replaces the file.

Keys the plugin does not recognise are preserved rather than dropped.

A `data.yml` that cannot be parsed, is blank, or has the wrong shape is **copied aside**
rather than overwritten — to `data.yml.unreadable`, or `data.yml.unreadable-1` and so on
if that name is taken — and the plugin starts from an empty set. A misshapen file is then
repaired in place so the next start finds a clean one. If the copy aside itself fails,
the plugin refuses to write at all rather than destroy the file; that is logged.

A `data.yml` carrying a **newer `schema-version`** than this plugin understands is never
written over. Downgrading the plugin therefore does not destroy data written by a newer
one — but the newer data is not read either.

Numbers are read the same way as in `config.yml`: a quoted number is fine, while a
negative or non-numeric value is read as `0` with a warning.

---

## 10. Troubleshooting

**Nothing I changed in `config.yml` took effect, and the log looks normal.**
The most likely cause is a `config.yml` that is not valid YAML at startup. There is no
parse guard on that path: the server's own YAML loader logs its error and carries on with
an empty document, so the plugin's **built-in defaults** are in force — enforcement off,
the two default milestones — and the `Configuration loaded: ...` line still reads
normally, because it is describing those defaults. Scroll back for a YAML error from the
server itself, fix the file, and run `/spulse reload`. A reload will tell you plainly
whether it parses.

**`/spulse reload` said it failed.**
The reply is `Reload failed. The error is in the console.`, and the console carries one
SEVERE record, `/spulse reload failed.`, with the parse error in its stack trace. On this
path the configuration already in force is kept, so the server keeps running on your last
good settings. Fix the file and reload again.

**A setting is not the value I wrote.**
Look for a warning naming both your value and the value in force — an out-of-range number
is clamped and says so. A value the plugin could not read at all is replaced by its
default, also with a warning. Warnings repeat on every load and reload, so they are still
there after a reload.

**A milestone never fires.**
Either the entry was skipped, or the point is behind the current counted window. A
skipped entry is named in the log by its **position in the list**, counting from 1 and
counting skipped entries too, so "entry 3" is the third `- minute:` block in your file.
The `Configuration loaded: N milestone(s)` line tells you how many survived.

**Nobody ever goes AFK.**
Check `tracking.afk.mode` against the table in [section 5](#5-afk-detection). `"OFF"`
marks nobody by design, and `ESSENTIALS` marks nobody when EssentialsX is absent or could
not be bound — both cases are in the log. Remember that a bare `OFF` without quotes is
read by YAML as `false` and falls back to `AUTO`.

**`/afk` does not pause the clock.**
A manual `/afk` only counts where EssentialsX is bound. Without it, only the idle timer
applies, and it is driven by input, not by a command.

**A player is never detected as idle even when clearly away.**
Water streams, bubble columns and knockback all arrive as ordinary movement and count as
input. A player parked in a current is never idle to the timer.

**A player cannot rejoin and I want them let back in.**
`/spulse reset <player>` clears the cooldown along with the counted window. Granting
`sessionpulse.exempt` will not do it — the login gate does not check exemptions.

**A player is missing from `/spulse top`.**
Records with zero lifetime playtime, or with no stored name, are skipped.

**`data.yml.unreadable` appeared.**
The plugin found a `data.yml` it could not use — unparseable, blank, or the wrong shape —
and set it aside instead of overwriting it. The copy is the old data; the live file is
clean. If instead the log says writes are refused, the copy aside failed and nothing is
being saved until you move the file out of the way yourself.

**`plugin.yml does not declare /spulse; the command is unavailable.`**
The jar is damaged or was repackaged. Rebuild or re-download it.
