# Isolated EssentialsX acceptance

This fixture tests the exact deployed `98e7568` snapshot without connecting to an
existing server. It creates a new directory under ignored `build/`, binds Paper to
`127.0.0.1` on a free port, limits its heap to 1 GiB, and stops its own process. It
never edits community server configuration, authentication, permissions or data.

The fixture supports native Minecraft 1.21.11 using the existing Node client and
native 26.3 using the separate Java MCProtocolLib bridge. Each run produces a
platform/version-specific receipt. Set `SPULSE_TEST_PLATFORM` to `paper` (default)
or `spigot` and supply that platform's verified server artifact. Paper results do
not establish Spigot Essentials compatibility. Keep platform-specific boot/gameplay
evidence separately.

Supply Java 21 for Paper 1.21.11 or Java 25 for Paper 26.3, Node, an existing bot
dependency directory, the matching Paper server JAR, and official EssentialsX
2.22.0. Use the requested runtime through executable paths; do not change global
PATH or JAVA_HOME. Native 26.3 requires the separately built bot fixture. The
candidate must have
SHA-256 `84d28cfd8d20e16bd37a34235ab3e564aee79f9476f588ee92a2279998b8525c`.
EssentialsX must have SHA-256
`bda4685105977fca2e209820a9f0ad24275bd103390a03236f38e59bfdac58e6`, published in the
[official release](https://github.com/EssentialsX/Essentials/releases/tag/2.22.0).
The server digest is retained in the result. No server artifact is built or changed.

```powershell
$env:BOT_MODULE_ROOT = 'C:/path/to/node_modules'
$env:SPULSE_TEST_SERVER_JAR = 'C:/path/to/paper-1.21.11.jar'
$env:SPULSE_TEST_SERVER_SHA = 'official-paper-server-sha256'
$env:SPULSE_TEST_PLUGIN_JAR = 'C:/path/to/SessionPulse-98e7568.jar'
$env:SPULSE_TEST_ESSENTIALS_JAR = 'C:/path/to/EssentialsX-2.22.0.jar'
# Paper API and its compile dependencies, for the separate test-only probe plugin.
$env:SPULSE_TEST_COMPILE_CP = 'C:/path/to/paper-api.jar;C:/path/to/other-api.jar'
# Optional executable paths; defaults are java, javac and jar from PATH.
$env:SPULSE_TEST_JAVA = 'C:/path/to/jdk/bin/java.exe'
$env:SPULSE_TEST_JAVAC = 'C:/path/to/jdk/bin/javac.exe'
$env:SPULSE_TEST_JAR_TOOL = 'C:/path/to/jdk/bin/jar.exe'
node scripts/essentials-acceptance.cjs
```

For native 26.3, first run `./gradlew botClient26Jar`, then set these additional
inputs and supply the official Paper 26.3 artifact and a portable Java 25 runtime:

```powershell
$env:SPULSE_TEST_VERSION = '26.3'
$env:SPULSE_TEST_NATIVE_BOT_JAR = (Resolve-Path build/test-fixtures/SessionPulseProbeBot26.jar).Path
node scripts/essentials-acceptance.cjs
```

The bridge binds only to loopback, accepts only the two fixture player commands
and quit from stdin, acknowledges teleports through the protocol-specific helper,
and retains only exact configured reminder text. Its class is never included in
the production plugin JAR. The result retains the bridge digest and Java version.

Allow approximately 9 minutes, including the full 300-second automatic idle trial
and a clean stop/restart. First boot may download Paper runtime dependencies. The
hard limit is 15 minutes. Check available memory before starting; the fixture's
JVM also needs memory outside its heap. Accepting the test server EULA and starting
a disposable local server are operator actions implicit in invoking this harness.

Three disposable players receive explicit permissions from the test-only probe:
`SPXAuto` has `essentials.afk.auto`; `SPXFallback` and `SPXManual` do not. All may use
manual `/afk`. The probe uses the candidate's public tracker to observe exact
counted seconds without sending player activity, and reads the actual Essentials
AFK state and effective permission. It samples only these fixture players.

The JSON result and isolated server log record manual pause/resume, the effective
300-second automatic threshold, AUTO's built-in fallback for the player without
automatic permission, resume on input, reload retaining time and a single tick
schedule, unchanged fired milestones plus a future reminder under the changed
prefix, and clean restart persistence without replay. An ESSENTIALS-only reload uses a
deliberately shorter 30-second built-in threshold, then verifies an unpermitted
player continues counting across 45 idle seconds: ESSENTIALS must not blend in
that fallback. AUTO is restored before the persistence check. Reminder text is exact and
limited to the fixture's two configured messages. A stopped clock or duplicate
ticking fails the elapsed-time bounds.

The separately packaged probe is never included in the production plugin JAR.
`spxfixture` accepts only the local server console. The isolated config disables
enforcement and uses a one-hour reset horizon, one-minute flush and 300-second
idle thresholds. No live authentication plugin or account provisioning is involved.

```powershell
node --test scripts/tests/essentials-acceptance.test.cjs
```

EssentialsX 2.22.0 currently logs `You are running an unsupported server version!`
on 26.3 while remaining enabled. The fixture records that warning explicitly.
A passing receipt demonstrates observed interoperability on its exact tested
server build; it is not an assertion of upstream EssentialsX support for 26.3.

For a short native command companion, set `SPULSE_TEST_SCOPE=commands`. Its
`PASS_COMMANDS_ONLY` outcome covers effective permission, the player's own time
receipt and read-only denial for another player's time. It skips AFK, reload,
reminders and persistence, so link its prior full receipt separately. The full
scope includes those command probes automatically.

Restart persistence here uses clean stop/rejoin within the one-hour reset horizon.
Enforcement is disabled; restart during an enforcement cooldown is not covered.
Keep that acceptance point separate from reconnect/cooldown CI observations.
