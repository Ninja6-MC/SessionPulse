#!/usr/bin/env bash
#
# Boots a throwaway Paper or Folia server with the built plugin installed, waits for
# startup to complete, then shuts it down and asserts the plugin loaded, enabled and
# disabled cleanly - and that the shaded dependencies were really relocated.
#
# Usage: boot-test.sh <paper|folia> <mc-version> <path-to-plugin-jar>
#
# No gameplay. Nothing joins, nothing is generated, no command is run. The question this
# answers is narrow and it is the one the scheduler issue exists to answer early: does a
# jar carrying a relocated FoliaLib and a relocated Adventure actually come up on more
# than one platform, or has every issue after it been written against an assumption that
# does not hold.
#
# Modelled on SpiralGenesis's smoke-test.sh, which is the only precedent in the org.
# Everything about a limbo fixture, a protocol bot and allocation sampling is dropped.
#
# Exits non-zero if the server fails to start, the plugin fails to enable or disable, the
# server rejects the plugin, or the log carries a linkage error or a stack trace naming
# our package. The full server log is left at $WORKDIR/server.log for the caller to
# upload as an artifact.

set -euo pipefail

PLATFORM="${1:?usage: boot-test.sh <paper|folia> <mc-version> <plugin-jar>}"
MC_VERSION="${2:?missing minecraft version}"
PLUGIN_JAR="${3:?missing plugin jar path}"

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

mkdir -p "$WORKDIR/plugins"
cd "$WORKDIR"

# ---------------------------------------------------------------------------
# Resolve and verify the server jar
# ---------------------------------------------------------------------------
API="https://fill.papermc.io/v3/projects/$PLATFORM/versions/$MC_VERSION/builds/latest"
echo "Resolving $PLATFORM $MC_VERSION from $API"

BUILD_JSON="$(curl -fsS --retry 3 --retry-delay 5 -m 60 "$API")"

# Parsed with shell builtins rather than jq so this script also runs on a developer
# machine, where jq is often absent - it is absent on the maintainer's. Everything before
# "server:default" is dropped, which discards the Mojang-mapped download that would
# otherwise match these patterns first.
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

# ---------------------------------------------------------------------------
# Minimal server configuration
# ---------------------------------------------------------------------------
echo "eula=true" > eula.txt

# A flat world, unlike SpiralGenesis's: nothing here touches terrain, and generating a
# normal world would cost CI minutes to produce something no assertion reads. Matches
# scripts/dev-server.sh. max-players=1 because nobody joins.
cat > server.properties <<PROPS
online-mode=false
level-type=minecraft\\:flat
view-distance=4
simulation-distance=4
spawn-protection=0
max-players=1
motd=SessionPulse CI boot test
enable-command-block=false
PROPS

cp "$PLUGIN_JAR" plugins/
echo "Installed plugin: $(basename "$PLUGIN_JAR")"

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
java -Xms1G -Xmx2G -jar server.jar --nogui < stdin.pipe > server.log 2>&1 &
SERVER_PID=$!

# Holding the write end open keeps the server's stdin from seeing EOF immediately.
exec 3> stdin.pipe

cleanup() {
    exec 3>&- 2>/dev/null || true
    if kill -0 "$SERVER_PID" 2>/dev/null; then
        echo "Force-killing server process $SERVER_PID"
        kill -9 "$SERVER_PID" 2>/dev/null || true
    fi
}
trap cleanup EXIT

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

if [[ "$booted" -eq 1 ]]; then
    echo "Requesting graceful shutdown..."
    echo "stop" >&3 || true
    for ((i = 0; i < STOP_TIMEOUT; i++)); do
        kill -0 "$SERVER_PID" 2>/dev/null || break
        sleep 1
    done
fi

exec 3>&- 2>/dev/null || true
wait "$SERVER_PID" 2>/dev/null || true
trap - EXIT

# ---------------------------------------------------------------------------
# Assertions
# ---------------------------------------------------------------------------
echo "----- last 40 log lines -----"
tail -40 server.log || true
echo "-----------------------------"

fail() { echo "::error::$1"; FAILED=1; }
FAILED=0

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

# The relocated Adventure pipeline, proved rather than assumed. onEnable deserializes a
# MiniMessage string and sends it to the console audience, which is the only thing in the
# plugin today that walks the ServiceLoader path mergeServiceFiles() exists to keep
# working. Without this line in the log, the jar enabled but the pipeline did not round
# trip - and every other assertion here would still have passed.
grep -q 'MiniMessage pipeline' server.log \
    || fail "The MiniMessage round trip never reached the console - the relocated Adventure pipeline did not work."

# Folia refuses a plugin without folia-supported and says exactly this.
! grep -qi 'not marked as supporting Folia' server.log \
    || fail "Server rejected the plugin as not Folia-compatible."

! grep -qi "Could not load 'plugins/" server.log || fail "Plugin jar failed to load."

! grep -qiE 'Error occurred while enabling|Failed to enable' server.log \
    || fail "Plugin threw during enable."

! grep -qi 'Error occurred while disabling' server.log \
    || fail "Plugin threw during disable."

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
# appear in a healthy log, and each is invisible to the Gradle build.
! grep -qE 'NoClassDefFoundError|ClassNotFoundException|NoSuchMethodError|ServiceConfigurationError' server.log \
    || fail "A linkage error appeared in the log - check the relocation and mergeServiceFiles()."

# The legacy Bukkit scheduler throws this on Folia. Catching it is the whole point of
# routing every task through the Scheduler seam.
! grep -q 'UnsupportedOperationException' server.log \
    || fail "UnsupportedOperationException in log (a legacy scheduler call on Folia)."

# Any stack trace naming our package is a defect wherever it surfaced.
if grep -q 'com\.ninja6\.sessionpulse' server.log && grep -qE '^[[:space:]]+at ' server.log; then
    ! grep -B5 'at com\.ninja6\.sessionpulse' server.log | grep -qE 'Exception|Error' \
        || fail "A stack trace referencing com.ninja6.sessionpulse appeared in the log."
fi

if [[ "$FAILED" -ne 0 ]]; then
    echo "Boot test FAILED for $PLATFORM $MC_VERSION."
    exit 1
fi

echo "Boot test PASSED for $PLATFORM $MC_VERSION (build $BUILD_ID)."
