# SessionPulse Configuration Reference

Every key in `config.yml`, what it accepts, and what SessionPulse does when the value
cannot be used. For running the plugin day to day — commands, permissions, enforcement,
troubleshooting — see the [Admin Guide](ADMIN_GUIDE.md).

`config.yml` is written into `plugins/SessionPulse/` the first time the plugin starts.
The shipped file carries the same ranges and reasons in comments; this page is the
complete reference, including the failure behaviour the comments only summarise.

---

## Contents

1. [How values are read](#1-how-values-are-read)
2. [When the file cannot be parsed](#2-when-the-file-cannot-be-parsed)
3. [Reloading](#3-reloading)
4. [`tracking`](#4-tracking)
5. [`reminders`](#5-reminders)
6. [`reminders.milestones`](#6-remindersmilestones)
7. [`reminders.overtime`](#7-remindersovertime)
8. [`enforcement`](#8-enforcement)
9. [Placeholders](#9-placeholders)

---

## 1. How values are read

Nothing in configuration loading throws. A malformed file produces warnings in the
server log and a working plugin. Every rule below applies on startup and again on
every `/spulse reload`, so a warning you ignore is repeated each time.

| Situation | What happens |
| --- | --- |
| Key absent | The default is used, silently. No warning. |
| Whole number where a number belongs | Used. |
| Integral decimal, e.g. `300.0` | Read as `300`, silently. No warning. |
| Quoted number, e.g. `"300"` | Read as `300`, silently. No warning. |
| Fractional number, e.g. `300.5` | Default is used, with a warning. |
| Text where a number belongs | Default is used, with a warning. |
| Number outside its range | Clamped to the nearest end of the range, with a warning naming both the value you wrote and the value in force. |
| `true` or `false`, quoted or bare, where a boolean belongs | Used. |
| Anything else where a boolean belongs | Default is used, with a warning. |
| Blank or empty `tracking.afk.mode` | `AUTO` is used, with no warning. |
| Unrecognised `tracking.afk.mode` | `AUTO` is used, with a warning. |
| Message with an unclosed or out-of-order MiniMessage tag | Default is used, with a warning. |
| Non-text where a message belongs | Default is used, with a warning. |

Notes on the edges of that table:

- **Ranges are clamped; milestone minutes are not.** A milestone minute outside its
  range makes the plugin skip that entry rather than invent a minute for it. See
  [section 6](#6-remindersmilestones).
- **Enum values are trimmed and case-insensitive.** `auto`, ` AUTO ` and `Auto` are
  the same value.
- **Write `OFF` in quotes, as `"OFF"`.** YAML reads a bare `OFF` as the boolean
  `false`, which is not a mode, so it falls back to `AUTO`.
- **Only two MiniMessage mistakes are detected:** a tag left open, and tags closed out
  of order. An unknown tag such as `<bogus>` is not an error — MiniMessage renders it
  as the literal text you typed, which is also why `<player>`, `<hours>` and the other
  placeholders can be written plainly.
- **The log quotes the value as you wrote it**, so you can find it in the file.

After loading, the log carries one summary line of the form
`Configuration loaded: N milestone(s), overtime on|off, enforcement on|off.` It is
worth reading after every edit: it is the quickest confirmation that the number of
milestones you expect survived.

---

## 2. When the file cannot be parsed

A `config.yml` that is not valid YAML behaves **differently at startup and on reload**,
and the difference matters.

**At startup** there is no guard. The server's own YAML loader logs its error and then
carries on with an empty document, which means the plugin's built-in defaults are in
force: enforcement off, the two default milestones, overtime off, and every other
default on this page. Nothing in SessionPulse's own log looks unusual — the
`Configuration loaded: ...` line is printed normally, describing the defaults. **The
YAML error from the server is the only sign**, and your settings are not applied.

**On `/spulse reload`** the configuration already in force is kept. The plugin records
one SEVERE line, `/spulse reload failed.`, with the parse error in its stack trace, and
the sender is told `Reload failed. The error is in the console.` Nothing about the
running configuration changes.

So: a broken file that survives until a restart silently reverts you to defaults, while
a broken file caught by a reload does not. Reload after every edit, and read the log.

**If you delete `config.yml`**, the plugin writes the shipped file back and loads the
defaults from it.

---

## 3. Reloading

`/spulse reload` re-reads every key on this page. Nothing here requires a server
restart. Alongside re-reading values, a reload:

- re-resolves which AFK detector is in force, and names it in the log;
- re-reads EssentialsX's own `auto-afk` setting;
- reschedules the periodic flush on the new `flush-interval-minutes`.

Two things a reload deliberately does **not** do:

- **It does not replay a point already passed.** A milestone you add, or an overtime
  series you enable or lower, never fires for a counted window that is already past
  that point; the next point on the series is the first one that can fire.
- **It does not forgive an existing cooldown.** See the Admin Guide's enforcement
  section.

Turning `enforcement.enabled` on applies on the next tick, which means anyone already
past `at-minutes` is disconnected immediately.

---

## 4. `tracking`

| Key | Type | Default | Range |
| --- | --- | --- | --- |
| `tracking.window-reset-hours` | whole number | `8` | 1–168 |
| `tracking.afk.mode` | `AUTO`, `ESSENTIALS`, `BUILT_IN`, `"OFF"` | `AUTO` | — |
| `tracking.afk.idle-seconds` | whole number | `300` | 30–3600 |
| `tracking.flush-interval-minutes` | whole number | `5` | 1–60 |

**`window-reset-hours`** — hours a player must be offline before their counted window
resets to zero. Milestones, overtime and enforcement all read the counted window rather
than the current session, so quitting and rejoining cannot buy a fresh allowance. The
value is read at each join, so a reload takes effect for the next player who connects.
The window resets only when the offline gap is strictly greater than this many hours.

**`afk.mode`** — whether, and how, the counted window pauses for a player who has
stopped playing. The Admin Guide has the full table of what each mode does with and
without EssentialsX installed. In short: `AUTO` uses EssentialsX where it is usable and
the built-in idle timer otherwise; `ESSENTIALS` requires EssentialsX and marks nobody
without it; `BUILT_IN` always uses the idle timer; `"OFF"` never pauses. `"OFF"` is the
wrong choice anywhere enforcement is on, because time spent away from the keyboard then
counts towards the disconnect.

**`afk.idle-seconds`** — seconds without input before the built-in idle timer treats a
player as idle. It applies under `BUILT_IN`, and under `AUTO` wherever the timer is the
detector in force. What counts as input is listed in the Admin Guide.

**`flush-interval-minutes`** — minutes between writes of every counted window to
`data.yml`. This is what a crash costs: at `5`, at most five minutes of counted play is
lost, and `last-seen` is at most that stale. A quit, and a written cooldown, each
trigger their own immediate write regardless of this interval.

---

## 5. `reminders`

| Key | Type | Default |
| --- | --- | --- |
| `reminders.prefix` | MiniMessage text | `<gray>[<aqua>SessionPulse</aqua>]</gray> ` |

The prefix is prepended to **every chat line the plugin sends**: milestone chat
messages, overtime reminders, and every `/spulse` reply — to players and to the
console alike. It is *not* used on the action bar, in a title, on the disconnect
screen, or on the login refusal screen.

Set it to `""` for no prefix. Unlike a milestone, a prefix cannot be skipped when it is
malformed — there is only one of it — so an unusable value falls back to the default
above and says so in the log.

The prefix accepts the same placeholders as the message it precedes. Because an unknown
tag renders as literal text, a prefix containing `<player>` shows the tag verbatim on
any reply that has no player to fill in — the header line of `/spulse top`, for
example. Keep placeholders out of the prefix unless you are certain every line that
carries it sets them.

---

## 6. `reminders.milestones`

One-time alerts keyed by minute of the counted window. Any number of entries; this is a
list and "any number" is literal. Each fires once per counted window, on the player's
own region.

| Field | Required | Notes |
| --- | --- | --- |
| `minute` | yes | Whole number, 1–10080. Never clamped — see below. |
| `message` | no | MiniMessage, sent in chat with the prefix. |
| `action-bar` | no | MiniMessage, shown on the action bar. No prefix. |
| `title` | no | MiniMessage title. No prefix. |
| `subtitle` | no | MiniMessage subtitle. Shown with `title`. |
| `sound` | no | A Bukkit sound name, e.g. `BLOCK_NOTE_BLOCK_CHIME`. |

Every field except `minute` is optional and an entry may use any combination of them.

**An entry is skipped — never silently replaced with a default — when it:**

- is not a map (a bare string in the list, for instance);
- has no `minute`;
- has a `minute` that is not a whole number;
- has a `minute` outside 1–10080;
- has a `minute` another entry already uses;
- has a `message`, `action-bar`, `title` or `subtitle` that is not valid MiniMessage;
- has none of `message`, `action-bar`, `title`, `subtitle` or `sound`, because it is a
  timer that fires into nothing.

Each skip is a warning naming the entry **by its position in the list**, counting from
1 and counting every entry as it appears in the file, including the ones that were
skipped. "Entry 3" is always the third `- minute:` block, whatever happened to the
first two. As with every number, an integral decimal such as `60.0` or a quoted `"60"`
is read as `60` without complaint; only a fractional or non-numeric minute is a problem.

Minutes are never clamped into range. There is no minute that could stand in for one
you did not choose, and inventing one would fire an alert at everyone who joins.

An unknown `sound` costs the sound and not the milestone: the entry still fires,
silently, with a warning naming it.

**The whole block:**

| You write | Result |
| --- | --- |
| No `milestones` key at all | The two built-in milestones (60 and 120 minutes) are restored. "No preference expressed." |
| `milestones: []` | No milestones. Honoured with no complaint. |
| `milestones:` set to something that is not a list | The two built-in milestones are used, with a warning. |

Surviving entries are sorted by minute before use, whatever order you wrote them in.
Players holding `sessionpulse.exempt` never receive a milestone.

---

## 7. `reminders.overtime`

| Key | Type | Default | Range |
| --- | --- | --- | --- |
| `reminders.overtime.enabled` | boolean | `false` | — |
| `reminders.overtime.after-minutes` | whole number | `180` | 1–10080 |
| `reminders.overtime.every-minutes` | whole number | `30` | 1–1440 |
| `reminders.overtime.message` | MiniMessage text | `<red>You have been playing for <white><hours></white> hours.</red>` | — |

The repeating reminder, counted on its own schedule and independent of the milestones
above. It fires at `after-minutes` of the counted window and then every `every-minutes`
after that. It is chat only: no action bar, no title, no sound.

A server that wants something said at 60 and 120 minutes should use milestones; this is
for saying something for as long as somebody is still online.

Where an overtime point lands on a milestone minute, the milestone is sent first on all
of its channels, and the overtime chat line after it.

The floor of `1` on `every-minutes` is not cosmetic: a `0` would schedule a reminder
every tick.

---

## 8. `enforcement`

| Key | Type | Default | Range |
| --- | --- | --- | --- |
| `enforcement.enabled` | boolean | `false` | — |
| `enforcement.at-minutes` | whole number | `240` | 1–10080 |
| `enforcement.kick-message` | MiniMessage text | `<yellow>Time for a break.</yellow>` | — |
| `enforcement.cooldown-minutes` | whole number | `30` | 1–1440 |

The optional layer that ends a session rather than commenting on it. It is off by
default, and with `enabled: false` the login gate admits everyone.

**`at-minutes`** is measured against the counted window, not the session. Reading the
session would let a player quit and rejoin for a fresh allowance, which is the whole
reason the counted window exists.

**`kick-message`** is shown on the disconnect screen, and the same text refuses a rejoin
during the cooldown. Minecraft's disconnect screen predates modern text formatting, so
this one message is rendered through the legacy serializer: plain colours survive,
gradients and hover events do not. An unusable value falls back to the built-in default
rather than disconnecting somebody with a blank screen.

**`cooldown-minutes`** is the whole of the enforced break. The disconnect resets the
counted window, so after the cooldown the player starts a fresh `at-minutes` rather
than being disconnected again at once. The cooldown is written to disk and flushed
before the disconnect, so a restart or a crash does not clear it. It applies to the
next disconnect only — changing this value does not shorten a cooldown already running.

To disable enforcement, set `enabled: false`. There is no `0` that means "no break".

Players holding `sessionpulse.exempt` are never disconnected and never have a cooldown
written for them. Granting `sessionpulse.exempt` does not lift a cooldown that already
exists; use `/spulse reset <player>` for that.

---

## 9. Placeholders

Placeholders are filled in as **text, never as markup**, so a player name cannot inject
a colour or a click event into your message.

| Placeholder | Value |
| --- | --- |
| `<player>` | The player's name. |
| `<hours>` | Counted hours to one decimal place, truncated (not rounded). |
| `<minutes>` | Counted whole minutes, truncated. |
| `<cooldown>` | Minutes before the player may rejoin, rounded up. |
| `<rank>` | Position in `/spulse top`. Command replies only. |
| `<lifetime>` | Lifetime hours to one decimal place, truncated. Command replies only. |

Which message gets which:

| Message | Placeholders filled |
| --- | --- |
| `reminders.prefix` | Whatever the line it precedes fills in. |
| A milestone's `message`, `action-bar`, `title`, `subtitle` | `<player>`, `<hours>`, `<minutes>` |
| `reminders.overtime.message` | `<player>`, `<hours>`, `<minutes>` |
| `enforcement.kick-message`, on the disconnect screen | `<player>`, `<hours>`, `<minutes>` (the counted window that was reached), `<cooldown>` (the full cooldown) |
| `enforcement.kick-message`, on the login refusal screen | `<player>`, `<hours>` and `<minutes>` (both `at-minutes`, because the disconnect reset the window), `<cooldown>` (the time remaining) |

A placeholder that the message does not support is not an error: MiniMessage renders
the unknown tag as the literal text you typed. That is the mechanism behind the prefix
caveat in [section 5](#5-reminders).
