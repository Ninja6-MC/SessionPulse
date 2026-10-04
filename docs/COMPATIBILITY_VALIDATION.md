# Minecraft compatibility validation

Use this checklist for each platform/version claimed by a release. A startup check
proves loading and console linkage; it does not prove player output, region-thread
safety, AFK integration or enforcement. Keep the results in the compatibility issue
and link the evidence from the release validation issue.

## Platform availability

Before testing, resolve the exact build and channel from the platform's official
source. Repeat this check when choosing the release candidate; latest builds move.

- Paper: [project metadata](https://fill.papermc.io/v3/projects/paper) and
  [26.3 builds](https://fill.papermc.io/v3/projects/paper/versions/26.3/builds).
- Folia: [project metadata](https://fill.papermc.io/v3/projects/folia). A Paper build
  or a Folia build for another Minecraft version is not Folia 26.3 evidence.
- Spigot: [26.3 BuildTools metadata](https://hub.spigotmc.org/versions/26.3.json)
  and [BuildTools instructions](https://www.spigotmc.org/wiki/buildtools/).

Paper 26.1+ requires Java 25 according to the
[Paper runtime requirements](https://docs.papermc.io/paper/getting-started/).
Use the runtime required by the chosen server. Keep the plugin's Java 21 compilation
and older-server coverage separate from the server's runtime requirement.

If a platform has no published 26.3 build, record it as unavailable and omit that
platform's 26.3 support claim. It need not prevent publishing support for other
platforms that have passed these checks.

## Safe test setup

1. Provision a dedicated test instance or an isolated copy of the community server.
   Back up configuration and data before changing anything. Use disposable players
   and an empty test world for destructive checks; do not apply enforcement test
   settings to the community instance without the operator provisioning it for that
   purpose.
2. Install the exact packaged candidate JAR that will be published. Do not rebuild it
   between platform checks. Save its SHA-256 digest and retain the artifact.
3. Record the full server version/build, download channel, server JAR digest, plugin
   commit/version, Java vendor/version, OS and complete plugin list. Record the
   EssentialsX version when testing that integration.
4. Save the effective `config.yml`, test player UUIDs, command transcript and server
   log for each phase. Capture screenshots of rendered player output and disconnect
   screens. Scrub real player names, addresses and unrelated chat before publishing.
5. Start with the shipped configuration and enforcement disabled. Confirm startup
   reports the expected scheduler and AFK detector, and logs no linkage failures or
   region-thread violations. Use a real client matching the server version.

## Checks and expected results

Use [CONFIG.md](CONFIG.md) and [ADMIN_GUIDE.md](ADMIN_GUIDE.md) for the complete
configuration and command semantics. Tick a result only when it has been observed
on the recorded platform/build; mark unavailable integrations explicitly.

### Defaults, commands and permissions

- [ ] With the unmodified shipped configuration, join, play and use `/spulse time`.
  Counted time increases during active play. Enforcement stays off and never kicks.
- [ ] Verify `/spulse`, `/sessionpulse`, `/spulse time`, `/spulse top` and tab
  completion. Console and player replies render without literal MiniMessage tags.
- [ ] Use non-operator disposable accounts to verify `sessionpulse.use` denial,
  `sessionpulse.admin` denial for another player's time/reset/reload, and successful
  administration with only `sessionpulse.admin` granted (it includes `.use`).
- [ ] Grant `sessionpulse.exempt` to one account. Its reminders and enforcement are
  skipped while a comparable non-exempt account receives them. Capture both results.
- [ ] Test reset for an online and an offline disposable account. Verify the intended
  player's counted window resets, another player's record does not change, and
  results remain correct after restart.

### All notification channels

In the isolated test configuration, set milestones at minutes 1 and 2 with distinct
messages. Include chat, action bar, title, subtitle and `BLOCK_NOTE_BLOCK_CHIME`.
Keep enforcement off for this phase and keep the player active. Save the exact
configuration with the evidence.

- [ ] At each milestone, all configured channels arrive once in the intended order.
  Title/subtitle and action bar appear in their proper UI areas and the sound is
  audible. Observe the client rather than relying solely on console logs.
- [ ] Include closed MiniMessage colour/hex-colour and decoration tags, a gradient,
  and player/time placeholders. Verify formatting, substituted values and no
  visible raw tags in chat, action bar or title. Capture rendered screenshots.
- [ ] Rejoin before the offline reset window expires. Previously delivered
  milestones are not repeated; elapsed counted time is retained.
- [ ] Enable overtime with `after-minutes: 1` and `every-minutes: 1`. Verify repeats
  at the expected counted-window intervals. At a shared minute, the milestone is
  delivered before overtime. Disable overtime and reload; repeats stop.

### AFK tracking and reload

- [ ] Set `tracking.afk.mode: BUILT_IN` and `idle-seconds: 30`. Stand still without
  input beyond the threshold; time pauses. Move across a block boundary, click,
  chat or run a command in separate trials; each resumes counting. Observe timing
  with `/spulse time` only after the idle interval, since a command is itself input.
- [ ] Set `tracking.afk.mode: "OFF"` and reload. Idle time now counts. Restore
  `BUILT_IN` and verify that the detector switch takes effect without restart.
- [ ] With a compatible EssentialsX installed, test `AUTO` and `ESSENTIALS`, manual
  `/afk`, and EssentialsX automatic AFK with `essentials.afk.auto` granted. Verify
  pause/resume and `AUTO`'s built-in fallback for accounts without automatic AFK.
  Repeat `AUTO` without EssentialsX. Record unavailable integrations separately;
  do not mark them passed.
- [ ] Reload valid changes to prefix, milestones, AFK and flush settings. Time
  continues increasing and new output settings take effect. Verify the documented
  warnings/fallbacks for malformed values and skipped malformed milestone entries.
- [ ] On Folia, run two players in separated regions and repeat movement, commands,
  notifications and reload. Confirm both retain correct independent time and the
  log has no wrong-thread exceptions. Only run this for an available Folia version.

### Enforcement and persistence

Run only on the isolated instance with disposable accounts. Set enforcement to
`enabled: true`, `at-minutes: 1`, `cooldown-minutes: 1`, with a clearly identifiable
kick message using closed colour/hex tags and the supported placeholders. Keep AFK
mode `"OFF"` for predictable timing. Save this test configuration separately.

- [ ] A non-exempt account is disconnected after reaching its counted limit. The
  disconnect screen has readable formatted text and substituted values, without
  raw tags. Legacy disconnect rendering preserves colours/decoration; hover and
  click events are not expected on this screen.
- [ ] Immediate reconnect is refused with the cooldown message and a positive
  rounded-up remaining cooldown. Restart the server during that cooldown and
  verify it still refuses reconnect until expiry; then joining succeeds and the
  counted window starts fresh.
- [ ] For a separate enforcement-disabled session, quit and restart with a counted
  window below its offline reset threshold. Time and delivered milestone state
  persist; no duplicate milestone is sent. Check stored YAML and command output.
- [ ] Stop cleanly and check the final flush. Test restart persistence and a reload
  during active play; neither loses the saved window nor cancels tracking.
- [ ] Restore the baseline configuration and verify enforcement is disabled before
  handing the instance back. Retain backups and all phase evidence.

## Evidence record and release gate

Copy this record into the compatibility issue for every claimed platform/version:

| Field | Recorded value |
| --- | --- |
| Platform, Minecraft version, full server build, channel | |
| Official build metadata and server JAR SHA-256 | |
| Java vendor/version and OS | |
| Plugin version, commit and candidate JAR SHA-256 | |
| Plugin list and optional EssentialsX version | |
| Effective configuration and test player UUIDs | |
| Automated run, logs, transcript and screenshot links | |
| Checks passed, failed, skipped or unavailable | |
| Defects and follow-up issue links | |

Run older supported Paper/Spigot/Folia regression checks with the same candidate
JAR, including the oldest supported boundary and the current 1.21.11 coverage.
Repeat relevant gameplay checks after runtime fixes. A different JAR digest means
a different candidate and needs corresponding validation evidence.

Publish 26.3 compatibility only for individually validated platforms. Update store
metadata and documentation from that verified matrix. Do not close the compatibility
issue or mark a gameplay result complete merely because this checklist exists.
