# Gameplay compatibility checks

The CI boot matrix preserves the Paper/Folia 1.20.4 startup checks and the
Paper/Folia/Spigot 1.21.11 gameplay checks. Paper 26.3 build 147 and Spigot 26.3
also run gameplay checks on Java 25. Folia 26.3 has no published build and is
not covered. Weekly latest-server discovery remains a startup check only.

Each gameplay run checks received milestone chat, action bar, title, subtitle
and sound; a formatted enforcement kick; immediate cooldown refusal; admission
after expiry; no repeated milestone after rejoin; and persisted cooldown data.
Missing delivery, raw MiniMessage tags, linkage failures and scheduler exceptions
fail the run. AFK is disabled in this fixture so idle protocol clients accumulate
real counted time. AFK behavior, player commands, reload and full server-restart
persistence still require the community-server validation checklist; unit tests
cover their logic but do not replace that gameplay evidence.

## Reproduce on disposable Linux servers

Build the plugin and the appropriate test-only client with Java 21 available:

```sh
./gradlew shadowJar botClientJar botClient26Jar --no-daemon
```

Run a 26.3 server and client on Java 25:

```sh
BOT_JAR=build/test-fixtures/SessionPulseProbeBot26.jar SERVER_BUILD=147 \
  .github/scripts/smoke-test.sh paper 26.3 build/libs/SessionPulse-0.1.0-SNAPSHOT.jar
```

For Spigot, build a server using official BuildTools with `--rev 26.3`, then
pass its path as `SPIGOT_JAR`. For older gameplay legs use
`SessionPulseProbeBot.jar` and version `1.21.11`. The script validates the fixture's
manifest protocol version before downloading anything. Both clients stay outside
the plugin JAR and use separate protocol-library classpaths.

## Evidence and release promotion

A passing run writes `run-<platform>/evidence.json`, retained with server and bot
logs. It records Minecraft version, resolved build/channel, actual Java runtime,
server SHA-256, fixture SHA-256 and tested plugin SHA-256. Paper's 26.3 build is
pinned; BuildTools resolves the named Spigot revision and its resulting bytes are
identified by the recorded digest.

Release smoke jobs download and verify the retained candidate before testing.
Promotion requires five distinct passing gameplay receipts: Paper, Folia and
Spigot 1.21.11 plus Paper and Spigot 26.3. Every receipt binds server evidence to
the candidate identity and digest. Missing, duplicate, boot-only, mislabeled or
wrong-runtime evidence fails closed. Publishing continues to promote those tested
bytes. A successful CI run alone does not close the community validation gap.
