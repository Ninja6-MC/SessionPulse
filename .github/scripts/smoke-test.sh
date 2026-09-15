#!/usr/bin/env bash
#
# Boots a throwaway Paper, Folia or Spigot server with the built plugin installed, waits
# for startup to complete, then shuts it down and asserts the plugin loaded, enabled and
# disabled cleanly - and that the shaded dependencies were really relocated. With BOT_JAR
# set it also plays a scripted session in between: a real protocol client joins, receives
# a milestone, is kicked by enforcement, is refused at login during the cooldown, and is
# let back in once the cooldown lapses.
#
# Usage: [BOT_JAR=<path>] [SPIGOT_JAR=<path>] smoke-test.sh <paper|folia|spigot> <mc-version> <path-to-plugin-jar>
#
# Paper and Folia are downloaded from PaperMC's fill API. Spigot publishes no server jar,
# so for spigot SPIGOT_JAR must name one BuildTools already built; this script never runs
# BuildTools itself. CI builds and caches it, and BUILDTOOLS_BUILD only labels the run.
#
# Without BOT_JAR: no gameplay. Nothing joins, nothing is generated, no command is run.
# That is the boot test the scheduler issue introduced - does a jar carrying a relocated
# FoliaLib and a relocated Adventure come up on more than one platform - and its behaviour
# is unchanged for the legs that do not set BOT_JAR.
#
# With BOT_JAR: the jar is build/test-fixtures/SessionPulseProbeBot.jar, and it speaks
# exactly one protocol version, 1.21.11, so any other version fails loudly rather than
# producing a confusing handshake error. SessionPulse logs nothing when a milestone fires,
# when it kicks, or when it refuses a login, so the proof for all three is what the bot
# RECEIVED, read out of $WORKDIR/bot-<n>.log. The server log only supplies timing - the
# `joined the game` and `lost connection` lines - and the negative checks.
#
# Modelled on SpiralGenesis's smoke-test.sh, which is the only precedent in the org.
# Its limbo fixture and allocation sampling are dropped; its protocol bot is not.
#
# Exits non-zero if the server fails to start, the plugin fails to enable or disable, the
# server rejects the plugin, storage does not load or data.yml is not written at shutdown,
# the notifier's console render or its legacy render is missing from the log, or the log
# carries a linkage error, a failed save or a stack trace naming our package - and, with
# BOT_JAR, if any step of the scripted session did not produce exactly the line it should.
# The full server log is left at $WORKDIR/server.log, and each bot run's output at
# $WORKDIR/bot-<n>.log, for the caller to upload as an artifact.

set -euo pipefail

PLATFORM="${1:?usage: smoke-test.sh <paper|folia|spigot> <mc-version> <plugin-jar>}"
MC_VERSION="${2:?missing minecraft version}"
PLUGIN_JAR="${3:?missing plugin jar path}"

case "$PLATFORM" in
    paper | folia | spigot) ;;
    *)
        echo "::error::Platform must be paper, folia or spigot, not '$PLATFORM'."
        exit 1
        ;;
esac

BOOT_TIMEOUT="${BOOT_TIMEOUT:-300}"
STOP_TIMEOUT="${STOP_TIMEOUT:-90}"
WORKDIR="${WORKDIR:-$PWD/run-$PLATFORM}"

if [[ ! -f "$PLUGIN_JAR" ]]; then
    echo "::error::Plugin jar not found: $PLUGIN_JAR"
    exit 1
fi

# Resolve before the cd below, or a relative path stops pointing at the jar. CI passes
# this straight out of `find`, which is relative.
PLUGIN_JAR="$(realpath "$PLUGIN_JAR")"

SPIGOT_JAR="${SPIGOT_JAR:-}"
if [[ "$PLATFORM" == "spigot" ]]; then
    if [[ -z "$SPIGOT_JAR" || ! -f "$SPIGOT_JAR" ]]; then
        echo "::error::Platform spigot needs SPIGOT_JAR naming a BuildTools-built server jar; got '$SPIGOT_JAR'."
        exit 1
    fi
    SPIGOT_JAR="$(realpath "$SPIGOT_JAR")"
fi

BOT_JAR="${BOT_JAR:-}"
if [[ -n "$BOT_JAR" ]]; then
    if [[ ! -f "$BOT_JAR" ]]; then
        echo "::error::BOT_JAR is set but no file exists at: $BOT_JAR"
        exit 1
    fi
    # MCProtocolLib is pinned to 1.21.11 in build.gradle.kts. Against any other server the
    # handshake fails with a version mismatch that reads like a plugin fault, so refuse
    # before downloading anything.
    if [[ "$MC_VERSION" != "1.21.11" ]]; then
        echo "::error::BOT_JAR speaks protocol 1.21.11 only; refusing to run it against $MC_VERSION."
        exit 1
    fi
    BOT_JAR="$(realpath "$BOT_JAR")"
fi

mkdir -p "$WORKDIR/plugins"
cd "$WORKDIR"

# ---------------------------------------------------------------------------
# Resolve and verify the server jar
# ---------------------------------------------------------------------------
if [[ "$PLATFORM" != "spigot" ]]; then
    API="https://fill.papermc.io/v3/projects/$PLATFORM/versions/$MC_VERSION/builds/latest"
    echo "Resolving $PLATFORM $MC_VERSION from $API"

    BUILD_JSON="$(curl -fsS --retry 3 --retry-delay 5 -m 60 "$API")"

    # Parsed with shell builtins rather than jq so this script also runs on a developer
    # machine, where jq is often absent - it is absent on the maintainer's. Everything
    # before "server:default" is dropped, which discards the Mojang-mapped download that
    # would otherwise match these patterns first.
    SEGMENT="${BUILD_JSON#*\"server:default\":}"
    JAR_URL="$(printf '%s' "$SEGMENT" | grep -oE 'https://[^"]+' | head -1)"
    JAR_SHA="$(printf '%s' "$SEGMENT" | grep -oE '"sha256":"[a-f0-9]{64}"' | head -1 \
        | grep -oE '[a-f0-9]{64}')"
    BUILD_ID="$(printf '%s' "$BUILD_JSON" | grep -oE '"id":[0-9]+' | head -1 | grep -oE '[0-9]+')"
    CHANNEL="$(printf '%s' "$BUILD_JSON" | grep -oE '"channel":"[A-Z]+"' | head -1 \
        | sed 's/.*:"//; s/"//')"

    if [[ -z "$JAR_URL" || "$JAR_URL" == "null" ]]; then
        echo "::error::Could not resolve a download URL for $PLATFORM $MC_VERSION"
        exit 1
    fi

    echo "Using $PLATFORM build $BUILD_ID ($CHANNEL)"
    curl -fsSL --retry 3 --retry-delay 5 -o server.jar "$JAR_URL"

    # The jar is downloaded and then executed, so verify it against the checksum the API
    # published rather than trusting the transfer.
    echo "$JAR_SHA  server.jar" | sha256sum -c -
else
    # No checksum: BuildTools compiled this jar on the same runner from Spigot's own
    # repositories, so there is no published digest to compare it with.
    BUILD_ID="buildtools-${BUILDTOOLS_BUILD:-unknown}"
    CHANNEL="local"
    echo "Using $PLATFORM $MC_VERSION from $SPIGOT_JAR ($BUILD_ID, $CHANNEL)"
    cp "$SPIGOT_JAR" server.jar
fi

# ---------------------------------------------------------------------------
# Minimal server configuration
# ---------------------------------------------------------------------------
echo "eula=true" > eula.txt

# A flat world, unlike SpiralGenesis's: nothing here touches terrain, and generating a
# normal world would cost CI minutes to produce something no assertion reads. Matches
# scripts/dev-server.sh. max-players=1 on the boot-only legs, because nobody joins.
#
# With BOT_JAR it is 5, and 1 is not enough even for one bot: Folia 1.21.11 refused the
# very FIRST join with multiplayer.disconnect.server_full at max-players=1, with nobody
# else online - its login-phase capacity check counts differently from Paper's, which let
# the same bot in. Every rejoin below still waits for the server's own `lost connection`
# line rather than a sleep, so the gameplay never relies on the spare slots.
MAX_PLAYERS=1
[[ -z "$BOT_JAR" ]] || MAX_PLAYERS=5
cat > server.properties <<PROPS
online-mode=false
server-ip=127.0.0.1
server-port=25565
level-type=minecraft\\:flat
view-distance=4
simulation-distance=4
spawn-protection=0
max-players=$MAX_PLAYERS
motd=SessionPulse CI boot test
enable-command-block=false
PROPS

cp "$PLUGIN_JAR" plugins/
echo "Installed plugin: $(basename "$PLUGIN_JAR")"

# The smoke config, written before the first boot so saveDefaultConfig() leaves it alone.
# Every number is a validation minimum or chosen against one, and the reasoning is the
# timeline the gameplay block asserts:
#
#   - milestone at minute 1 carrying all five facets, so one fire exercises chat, action
#     bar, title, subtitle and sound through the relocated player facets. Unique marker
#     text, so a grep cannot match anything the server says on its own. prefix is empty,
#     so the chat line is exactly the marker.
#   - enforcement at minute 3, cooldown 1. Enforcement kicks on the first 20-tick check
#     at or past at-minutes, with no grace, and <cooldown> renders as whole minutes
#     rounded up - so the kick and the login refusal both read exactly "SP-SMOKE-KICK 1".
#     Coloured, so the message really leaves the plugin as legacy section-sign codes and
#     the server has to parse them back into a styled component; an uncoloured message
#     would pass even if nothing parsed it. Spigot's login refusal is the one exception,
#     see LOGIN_REFUSAL below.
#   - afk.mode OFF, quoted: the bot stands still, and AUTO would pause its counted window
#     and push every deadline out. Unquoted, YAML reads OFF as boolean false.
#   - window-reset-hours 1 is the minimum and far longer than the run, so the window never
#     resets underneath the kick timing.
if [[ -n "$BOT_JAR" ]]; then
    mkdir -p plugins/SessionPulse
    cat > plugins/SessionPulse/config.yml <<'YAML'
tracking:
  window-reset-hours: 1
  afk:
    mode: "OFF"
  flush-interval-minutes: 1
reminders:
  prefix: ""
  milestones:
    - minute: 1
      message: "<aqua>SP-SMOKE-MARK</aqua>"
      action-bar: "<aqua>SP-SMOKE-MARK</aqua>"
      title: "<aqua>SP-SMOKE-MARK</aqua>"
      subtitle: "<gray>SP-SMOKE-SUB</gray>"
      sound: BLOCK_NOTE_BLOCK_CHIME
  overtime:
    enabled: false
enforcement:
  enabled: true
  at-minutes: 3
  cooldown-minutes: 1
  kick-message: "<red>SP-SMOKE-KICK <cooldown></red>"
YAML
    echo "Wrote smoke config."
fi

# ---------------------------------------------------------------------------
# Boot, wait for readiness, shut down
# ---------------------------------------------------------------------------
rm -f stdin.pipe
mkfifo stdin.pipe

# NOTE: this script is Linux-only in practice. Reading stdin from a FIFO crashes the JVM
# on Windows - jansi's native DLL faults with an access violation during library loading,
# before the server starts. Use scripts/dev-server.sh for local testing on Windows.
#
# On a Windows machine with WSL it does run, which is worth knowing before assuming a
# change here can only be verified by pushing. Two things matter: install a Linux JDK
# inside the distribution rather than reaching for the Windows one, and keep WORKDIR on
# the distribution's own filesystem, because mkfifo does not work under /mnt.
#
# Spigot always gets -DIReallyKnowWhatIAmDoingISwear. A Spigot build that believes itself
# outdated sleeps 20 seconds at startup, and the cached jar ages daily until the next
# BuildTools build replaces it, so without the flag the boot would slow down as the cache
# got older.
SERVER_JVM_ARGS=(-Xms1G -Xmx2G)
[[ "$PLATFORM" != "spigot" ]] || SERVER_JVM_ARGS+=(-DIReallyKnowWhatIAmDoingISwear)
java "${SERVER_JVM_ARGS[@]}" -jar server.jar --nogui < stdin.pipe > server.log 2>&1 &
SERVER_PID=$!

# Holding the write end open keeps the server's stdin from seeing EOF immediately.
exec 3> stdin.pipe

BOT_PID=""

cleanup() {
    exec 3>&- 2>/dev/null || true
    if [[ -n "$BOT_PID" ]] && kill -0 "$BOT_PID" 2>/dev/null; then
        kill -9 "$BOT_PID" 2>/dev/null || true
    fi
    if kill -0 "$SERVER_PID" 2>/dev/null; then
        echo "Force-killing server process $SERVER_PID"
        kill -9 "$SERVER_PID" 2>/dev/null || true
    fi
}
trap cleanup EXIT

# Defined before the boot rather than with the assertions, because the gameplay block
# below reports through it too.
fail() { echo "::error::$1"; FAILED=1; }
FAILED=0

echo "Waiting up to ${BOOT_TIMEOUT}s for startup..."
booted=0
for ((i = 0; i < BOOT_TIMEOUT; i++)); do
    if grep -q 'Done (' server.log 2>/dev/null; then
        booted=1
        echo "Server reported startup complete after ${i}s."
        break
    fi
    # Fail fast if the JVM died rather than burning the whole timeout.
    if ! kill -0 "$SERVER_PID" 2>/dev/null; then
        echo "::error::Server process exited before startup completed."
        break
    fi
    sleep 1
done

# ---------------------------------------------------------------------------
# Gameplay (BOT_JAR only)
# ---------------------------------------------------------------------------
# Every helper returns a status and never exits, so each step is written
# `<wait> || { fail "..."; return; }`: one missing line fails the run with a message naming
# that line, and the steps after it - which all assume it happened - are skipped rather
# than piling up noise. The server is still stopped and every assertion below still runs.

BOT_NAME="SmokeBot"
BOT_RUNS=0

# Whole-line, fixed-string. A substring match would accept "SP-SMOKE-KICK 10" as the kick
# or a line still carrying markup around the marker.
bot_has() { LC_ALL=C grep -qxF "$2" "$1" 2>/dev/null; }

# The section sign as the two UTF-8 bytes the bot prints, spelled as escapes so no editor
# or locale can change what the file holds. Matched with LC_ALL=C, as bytes.
SECTION=$'\xc2\xa7'

# The cooldown's login refusal, as the bot must log it. Upstream CraftBukkit wraps a
# pre-login refusal string in Component.literal() instead of parsing it, so on Spigot
# alone the legacy codes arrive as literal text - which the vanilla client still renders
# as colour, so a player sees red. Paper and Folia parse the string into a styled
# component, which the bot flattens to the bare text. The game-phase kick goes through
# kickPlayer(String), which all three parse, so it has one expected line everywhere.
LOGIN_REFUSAL="BOT disconnect phase=login reason=SP-SMOKE-KICK 1"
[[ "$PLATFORM" != "spigot" ]] || LOGIN_REFUSAL="BOT disconnect phase=login reason=${SECTION}cSP-SMOKE-KICK 1"

# Occurrences of a fixed string in the server log, as a comparison. grep -c prints 0 and
# exits 1 on no match, hence the `|| true`.
log_count_at_least() {
    local n
    n="$(grep -cF "$1" server.log 2>/dev/null || true)"
    (( ${n:-0} >= $2 ))
}

# Polls a condition once a second. Gives up early if the server died, so a crash reports
# as a crash's consequence instead of burning every remaining timeout in turn.
wait_until() {
    local timeout="$1"; shift
    local end=$((SECONDS + timeout))
    while ((SECONDS < end)); do
        "$@" && return 0
        kill -0 "$SERVER_PID" 2>/dev/null || return 1
        sleep 1
    done
    "$@"
}

bot_exited() { ! kill -0 "$BOT_PID" 2>/dev/null; }

# Starts the next bot run for up to $1 seconds; its output goes to bot-<run>.log.
start_bot() {
    BOT_RUNS=$((BOT_RUNS + 1))
    echo "Starting bot run $BOT_RUNS for up to ${1}s"
    # stdout forced to UTF-8, so a section sign the bot received is written as the bytes
    # the checks look for whatever the runner's locale.
    java -Dstdout.encoding=UTF-8 -jar "$BOT_JAR" 127.0.0.1 25565 "$BOT_NAME" "$1" > "bot-$BOT_RUNS.log" 2>&1 &
    BOT_PID=$!
}

# Waits up to $1 seconds for the bot process to finish, and reaps it.
finish_bot() {
    wait_until "$1" bot_exited || return 1
    wait "$BOT_PID" 2>/dev/null || true
}

smoke_gameplay() {
    local log join_at kick_at want rest

    # --- 1. First session: join, then receive the minute-1 milestone -----------------
    start_bot 100
    log="bot-$BOT_RUNS.log"
    wait_until 60 bot_has "$log" "BOT joined" \
        || { fail "Bot run 1 never logged 'BOT joined' within 60s - see $log."; return; }
    wait_until 30 log_count_at_least "$BOT_NAME joined the game" 1 \
        || { fail "Server never logged '$BOT_NAME joined the game' for bot run 1."; return; }
    # The milestone deadline runs from the server's join, not the bot's start: the counted
    # window opens at PlayerJoinEvent. One minute, plus a 20-tick check, plus slack.
    join_at=$SECONDS

    # Everything a milestone sends, each checked separately so the failure names the facet
    # that is missing. A linkage error here is the player-facet shape of an adventure-api /
    # adventure-platform skew, which the console-only boot legs cannot see.
    for want in \
        "BOT chat overlay=false text=SP-SMOKE-MARK" \
        "BOT actionbar text=SP-SMOKE-MARK" \
        "BOT title text=SP-SMOKE-MARK" \
        "BOT subtitle text=SP-SMOKE-SUB" \
        "BOT sound key=minecraft:block.note_block.chime"; do
        wait_until $((join_at + 90 - SECONDS)) bot_has "$log" "$want" || {
            if grep -qE 'AbstractMethodError|NoSuchMethodError|IncompatibleClassChangeError' server.log; then
                fail "Milestone never delivered '$want', and the server log carries a linkage error - the player facets of adventure-platform-bukkit are broken against this adventure-api."
            else
                fail "Milestone never delivered '$want' within 90s of the join - see $log."
            fi
            return
        }
    done
    echo "Milestone delivered: chat, action bar, title, subtitle, sound."

    finish_bot 60 || { fail "Bot run 1 did not exit after its 100s session."; return; }
    wait_until 30 log_count_at_least "$BOT_NAME lost connection" 1 \
        || { fail "Server never logged '$BOT_NAME lost connection' after bot run 1."; return; }

    # --- 2. Second session: no repeat milestone, then the enforcement kick -----------
    # About 100s of the 3-minute budget is already counted, so the kick lands roughly 80s
    # after this join. 120s is the ceiling that still fails a window that reset or stopped
    # counting.
    start_bot 150
    log="bot-$BOT_RUNS.log"
    wait_until 60 bot_has "$log" "BOT joined" \
        || { fail "Bot run 2 never logged 'BOT joined' within 60s - see $log."; return; }
    wait_until 30 log_count_at_least "$BOT_NAME joined the game" 2 \
        || { fail "Server never logged '$BOT_NAME joined the game' for bot run 2."; return; }
    wait_until 120 bot_has "$log" "BOT disconnect phase=game reason=SP-SMOKE-KICK 1" \
        || { fail "Enforcement never kicked with 'SP-SMOKE-KICK 1' within 120s of the rejoin - see $log."; return; }
    kick_at=$SECONDS
    echo "Enforcement kicked the bot."

    # Fired milestones are re-seeded on join, so minute 1 is already behind the window and
    # must not fire a second time.
    ! grep -qF 'SP-SMOKE-MARK' "$log" \
        || fail "The minute-1 milestone fired again on the second session - fired milestones were not re-seeded on join."

    finish_bot 30 || { fail "Bot run 2 did not exit after it was kicked."; return; }
    wait_until 30 log_count_at_least "$BOT_NAME lost connection" 2 \
        || { fail "Server never logged '$BOT_NAME lost connection' after the kick."; return; }

    # --- 3. Straight back in: the cooldown refuses the login --------------------------
    start_bot 30
    log="bot-$BOT_RUNS.log"
    # The bot exits on its own after 30s whatever happens, so failing this means the
    # process hung, not that the login was let through; the next two checks decide that.
    finish_bot 60 || { fail "Bot run 3 hung - the process did not exit within 60s."; return; }
    # Carries the section sign on Spigot only - see where LOGIN_REFUSAL is set.
    bot_has "$log" "$LOGIN_REFUSAL" \
        || { fail "The cooldown did not refuse the login with '$LOGIN_REFUSAL' - see $log."; return; }
    ! bot_has "$log" "BOT joined" \
        || { fail "Bot run 3 joined during the cooldown."; return; }
    echo "Cooldown refused the login."

    # --- 4. Positive control: after the cooldown lapses, the login is let through ------
    # Without this, a server refusing every login for any reason would pass step 3.
    rest=$((kick_at + 65 - SECONDS))
    if ((rest > 0)); then
        echo "Waiting ${rest}s for the cooldown to lapse..."
        sleep "$rest"
    fi
    start_bot 20
    log="bot-$BOT_RUNS.log"
    wait_until 60 bot_has "$log" "BOT joined" \
        || { fail "Bot run 4 was not let back in after the cooldown lapsed - see $log."; return; }
    echo "Login allowed after the cooldown."
    finish_bot 60 || { fail "Bot run 4 hung - the process did not exit within 60s."; return; }
    # Enforcement resets the counted window before it kicks, so the returning player starts
    # from zero and must not be kicked again straight away. A game-phase disconnect here
    # means the reset did not happen and the player is locked out in a kick loop.
    ! grep -qF 'BOT disconnect phase=game' "$log" \
        || fail "Bot run 4 was kicked again after the cooldown - enforcement did not reset the window before the kick."
}

if [[ "$booted" -eq 1 && -n "$BOT_JAR" ]]; then
    smoke_gameplay
    # An early return can leave a bot connected. Stop it and reap it now, so its log is
    # complete and flushed before it is printed and uploaded, and so it is not still
    # holding a connection while the server shuts down.
    if [[ -n "$BOT_PID" ]] && kill -0 "$BOT_PID" 2>/dev/null; then
        kill "$BOT_PID" 2>/dev/null || true
    fi
    [[ -z "$BOT_PID" ]] || wait "$BOT_PID" 2>/dev/null || true
fi

if [[ "$booted" -eq 1 ]]; then
    echo "Requesting graceful shutdown..."
    echo "stop" >&3 || true
    for ((i = 0; i < STOP_TIMEOUT; i++)); do
        kill -0 "$SERVER_PID" 2>/dev/null || break
        sleep 1
    done
fi

exec 3>&- 2>/dev/null || true
# A server still alive after STOP_TIMEOUT - or one that never booted - would otherwise hang
# this wait until the job timeout. Killed, the missing 'SessionPulse disabled' line below
# reports it as the failure it is.
if kill -0 "$SERVER_PID" 2>/dev/null; then
    echo "::warning::Server still running after the stop request; killing it."
    kill -9 "$SERVER_PID" 2>/dev/null || true
fi
wait "$SERVER_PID" 2>/dev/null || true
trap - EXIT

# ---------------------------------------------------------------------------
# Assertions
# ---------------------------------------------------------------------------
echo "----- last 40 log lines -----"
tail -40 server.log || true
echo "-----------------------------"
if [[ -n "$BOT_JAR" ]]; then
    for f in bot-*.log; do
        [[ -f "$f" ]] || continue
        echo "----- $f -----"
        cat "$f"
    done
    echo "-----------------------------"
fi

# Every assertion below is written `<condition> || fail "..."`, negatives included, and
# never `grep ... && fail "..."`. Under `set -euo pipefail` an `&&` assertion that lands
# last in the script - or last inside an if-body - evaluates to false on a HEALTHY server
# and takes the whole run down with exit 1. That is a green server reported as a failure,
# which is worse than the check not existing. SpiralGenesis's script uses only `|| fail`
# for exactly this reason.

[[ "$booted" -eq 1 ]] || fail "Server never reached 'Done (' within ${BOOT_TIMEOUT}s."

grep -q 'Enabling SessionPulse' server.log || fail "Plugin was never enabled."
grep -q 'SessionPulse enabled' server.log || fail "onEnable did not run to completion."
grep -q 'SessionPulse disabled' server.log || fail "onDisable did not run to completion."

# The relocated Adventure pipeline, proved rather than assumed. onEnable renders a chat
# line, an action bar and a title through Notifier to the console audience, and logs a
# legacy render. Nothing joins, so these are the only Adventure calls a boot makes.
grep -q 'MiniMessage pipeline' server.log \
    || fail "The notifier's chat render never reached the console - the relocated Adventure pipeline did not work."

# The legacy serializer resolves after relocation and actually rendered: the text is
# present and the markup is not. Enforcement's kick and pre-login messages depend on it.
# One grep, never a pipe: a negated pipe under pipefail can pass on the very line it
# exists to catch.
grep -q 'Legacy serializer' server.log \
    || fail "The legacy render was never logged - LegacyComponentSerializer did not resolve."
! grep -qE 'Legacy serializer.*</' server.log \
    || fail "The legacy render still carries MiniMessage markup - it was logged unrendered."

# A Paper jar copied into SPIGOT_JAR by mistake would otherwise pass as the Spigot leg.
# CraftBukkit's version line names the Spigot build; Paper's does not.
if [[ "$PLATFORM" == "spigot" ]]; then
    grep -q -- '-Spigot-' server.log \
        || fail "server.log does not identify a Spigot server - SPIGOT_JAR is not a Spigot build."
fi

# Folia refuses a plugin without folia-supported and says exactly this.
! grep -qi 'not marked as supporting Folia' server.log \
    || fail "Server rejected the plugin as not Folia-compatible."

! grep -qi "Could not load 'plugins/" server.log || fail "Plugin jar failed to load."

! grep -qiE 'Error occurred while enabling|Failed to enable' server.log \
    || fail "Plugin threw during enable."

! grep -qi 'Error occurred while disabling' server.log \
    || fail "Plugin threw during disable."

# The configuration, on a real server rather than only in a unit test. These legs are
# advisory - they run on every pull request but are not in main's required-check list - so
# they are a signal, not a gate.
#
# The third line is the valuable one: it catches the SHIPPED config.yml drifting away from
# the code defaults, which the unit tests can only compare file-to-file.
# Only without BOT_JAR: with it, the script wrote config.yml itself, so its presence proves
# nothing about the jar.
if [[ -z "$BOT_JAR" ]]; then
    [[ -f "plugins/SessionPulse/config.yml" ]] \
        || fail "saveDefaultConfig() did not write config.yml - the resource is missing from the jar."
fi

grep -q 'Configuration loaded:' server.log || fail "The configuration never loaded."

# Storage, on a real server. The loaded line is logged whether or not data.yml exists yet,
# so it proves the read at enable ran, not that a file was found.
grep -q 'Storage loaded:' server.log || fail "Storage never loaded."

# Nobody joins, so nothing is dirty - but shutdown writes unconditionally, which is what
# makes the file's presence after the graceful stop proof that the final synchronous save
# ran rather than being skipped or lost to a cancelled async task.
[[ -f "plugins/SessionPulse/data.yml" ]] \
    || fail "data.yml was not written at shutdown - the final synchronous save did not run."

! grep -q 'Failed to save' server.log || fail "Storage failed to write data.yml."

# Matched on the SHAPE of a warning, not on a list of the individual warning texts.
# PluginConfig can emit a dozen different complaints and an alternation of phrases only
# ever caught the two written into it, so a shipped config.yml that grew an unclosed tag
# in `reminders.prefix` used to pass this leg while this very caption said it could not.
#
# Every warning the class emits opens with the config key it is about, so the marker is
# a WARN line from this plugin naming one of the three top-level sections. That invariant
# is enforced, not assumed: PluginConfigTest#everyWarningOpensWithTheKeyItIsAbout drives a
# corpus of malformed files through PluginConfig and fails the build on any warning that
# does not start with one of these three roots, so a new warning cannot escape this leg
# without a red test first.
#
# With BOT_JAR the file under test is the smoke config rather than the shipped one, so the
# message says so. It must still be warning-free: a warning there means a value was
# clamped or defaulted, and every timing the gameplay block asserts was chosen against
# the values as written.
if [[ -z "$BOT_JAR" ]]; then
    ! grep -qE 'WARN.*SessionPulse.*(tracking|reminders|enforcement)\.[a-z]' server.log \
        || fail "The SHIPPED config.yml produced a validation warning, so the file and the code defaults disagree."
else
    ! grep -qE 'WARN.*SessionPulse.*(tracking|reminders|enforcement)\.[a-z]' server.log \
        || fail "The smoke config produced a validation warning, so the gameplay timings no longer hold."
fi

if [[ -n "$BOT_JAR" ]]; then
    # The cooldown the kick set, persisted. YamlDataStorage writes cooldown-expires for the
    # player's entry, and a lapsed cooldown is not pruned - so after the positive control
    # the bot's entry still carries a non-zero value, which is what makes this check
    # stable at shutdown rather than racing the cooldown's expiry.
    grep -qE '^[[:space:]]*cooldown-expires:[[:space:]]*[1-9][0-9]*[[:space:]]*$' plugins/SessionPulse/data.yml 2>/dev/null \
        || fail "data.yml carries no non-zero cooldown-expires - the enforcement cooldown was not persisted."

    # Rendered, never raw: no MiniMessage tag and no section sign in any text the bot
    # received. Only the text events are read; a `closed` line repeats the transport's own
    # reason. The Spigot login refusal is exempt, as one exact line, for the reason given
    # where LOGIN_REFUSAL is set - step 3 already matched it whole.
    #
    # Captured into variables and grepped once each rather than piped, for the same
    # pipefail reason as the legacy-serializer check above.
    BOT_TEXT="$(LC_ALL=C grep -hE '^BOT (chat|actionbar|title|subtitle|disconnect) ' bot-*.log 2>/dev/null || true)"
    if [[ "$PLATFORM" == "spigot" ]]; then
        BOT_TEXT="$(LC_ALL=C grep -vxF "$LOGIN_REFUSAL" <<<"$BOT_TEXT" || true)"
    fi
    ! LC_ALL=C grep -E "(<[a-z/#]|$SECTION)" <<<"$BOT_TEXT" \
        || fail "A bot received text still carrying MiniMessage markup or a section sign - it was sent unrendered."

    # Event and scheduler failures that only a joined player can trigger.
    ! grep -qE 'Could not pass event|Task generated an exception|Exception in region thread' server.log \
        || fail "A listener or scheduled task threw while the bot was online."
fi

# THE relocation assertion, and the reason it is a real proof rather than a tautology:
# FoliaLib emits this itself, at SEVERE, when its own runtime package still begins
# com.tcoded.folialib. It stores that prefix comma-separated and reassembles it at runtime
# specifically so a shading tool's string-constant remapping cannot rewrite the check into
# silence.
#
# It is still a NEGATIVE check, so it would also pass if FoliaLib never loaded at all. It
# is a backstop; the primary proof is the ZipFile inspection in shadowJar's doLast, which
# is positive and runs before the jar ever reaches a server. Both, deliberately.
! grep -q 'FoliaLib is not relocated correctly' server.log \
    || fail "FoliaLib reports it was not relocated - the shadowJar relocation did not apply."

# The shape a broken relocation or a dropped service file actually takes. None of these
# appear in a healthy log, and each is invisible to the Gradle build. AbstractMethodError
# and IncompatibleClassChangeError are the shapes an adventure-api / adventure-platform
# skew takes.
! grep -qE 'NoClassDefFoundError|ClassNotFoundException|NoSuchMethodError|AbstractMethodError|IncompatibleClassChangeError|ServiceConfigurationError' server.log \
    || fail "A linkage error appeared in the log - check the relocation, mergeServiceFiles(), and the adventure-api / adventure-platform versions."

# The legacy Bukkit scheduler throws this on Folia. Catching it is the whole point of
# routing every task through the Scheduler seam.
! grep -q 'UnsupportedOperationException' server.log \
    || fail "UnsupportedOperationException in log (a legacy scheduler call on Folia)."

# Any stack trace naming our package is a defect wherever it surfaced.
if grep -q 'com\.ninja6\.sessionpulse' server.log && grep -qE '^[[:space:]]+at ' server.log; then
    # Captured first, not piped: `grep -q` exits at its first match, the upstream grep can
    # then die of SIGPIPE, and under pipefail that turns a found stack trace into a pass.
    TRACE_CONTEXT="$(grep -B5 'at com\.ninja6\.sessionpulse' server.log || true)"
    ! grep -qE 'Exception|Error' <<<"$TRACE_CONTEXT" \
        || fail "A stack trace referencing com.ninja6.sessionpulse appeared in the log."
fi

if [[ "$FAILED" -ne 0 ]]; then
    echo "Smoke test FAILED for $PLATFORM $MC_VERSION."
    exit 1
fi

if [[ -n "$BOT_JAR" ]]; then
    echo "Smoke test PASSED for $PLATFORM $MC_VERSION (build $BUILD_ID), with gameplay."
else
    echo "Smoke test PASSED for $PLATFORM $MC_VERSION (build $BUILD_ID)."
fi
