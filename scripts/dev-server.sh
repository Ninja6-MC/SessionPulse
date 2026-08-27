#!/usr/bin/env bash
#
# Boots a local Paper server with the freshly built plugin installed, for manual testing
# of session tracking, reminders and the /spulse command.
#
# Usage: scripts/dev-server.sh [mc-version]
#
# Paper only. Folia arrives with the scheduler abstraction, and the smoke matrix is CI's
# job, not this script's.
#
# The world is flat and unseeded, unlike SpiralGenesis's dev server, which needs real
# generated terrain because it allocates spawns. SessionPulse counts seconds and sends
# messages, so a flat world boots faster and exercises everything the plugin does.
#
# Why not `./gradlew runServer`: SessionPulse deliberately does not take the
# xyz.jpenilla.run-paper plugin. run-paper 2.x resolves servers through PaperMC's v2 API,
# which now returns 403, and every 3.x release requires Gradle 9 while this project is on
# 8.10.2. Adding a plugin known not to work would be copying a wart rather than a
# pattern. This resolves through the v3 "fill" API instead.

set -euo pipefail

MC_VERSION="${1:-1.20.4}"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUNDIR="$REPO_ROOT/run"
SERVER_JAR="$RUNDIR/paper-$MC_VERSION.jar"

cd "$REPO_ROOT"

# ---------------------------------------------------------------------------
# Build the plugin
# ---------------------------------------------------------------------------
echo "==> Building plugin"
./gradlew shadowJar -q

# The exclusions are load-bearing: withSourcesJar() and withJavadocJar() are both on and
# tasks.jar is classified `thin`, so build/libs holds four jars and only one of them is
# the shaded plugin.
PLUGIN_JAR="$(find build/libs -maxdepth 1 -name '*.jar' \
    ! -name '*-sources.jar' ! -name '*-javadoc.jar' ! -name '*-thin.jar' | head -1)"

if [[ -z "$PLUGIN_JAR" ]]; then
    echo "error: no plugin jar found in build/libs" >&2
    exit 1
fi
echo "    $PLUGIN_JAR"

mkdir -p "$RUNDIR/plugins"

# ---------------------------------------------------------------------------
# Resolve and download the server jar (cached between runs)
# ---------------------------------------------------------------------------
if [[ ! -f "$SERVER_JAR" ]]; then
    echo "==> Resolving Paper $MC_VERSION"
    API="https://fill.papermc.io/v3/projects/paper/versions/$MC_VERSION/builds/latest"
    BUILD_JSON="$(curl -fsS --retry 3 --retry-delay 5 -m 60 "$API")"

    # jq is present in CI but not in a stock Git Bash, so slice the payload by hand.
    # Everything before "server:default" is dropped, which discards the Mojang-mapped
    # download that would otherwise match these patterns first.
    SEGMENT="${BUILD_JSON#*\"server:default\":}"
    JAR_URL="$(printf '%s' "$SEGMENT" | grep -oE 'https://[^"]+' | head -1)"
    JAR_SHA="$(printf '%s' "$SEGMENT" | grep -oE '"sha256":"[a-f0-9]{64}"' | head -1 \
        | grep -oE '[a-f0-9]{64}')"

    if [[ -z "$JAR_URL" || -z "$JAR_SHA" ]]; then
        echo "error: could not resolve a download for Paper $MC_VERSION" >&2
        exit 1
    fi

    echo "    $JAR_URL"
    curl -fsSL --retry 3 --retry-delay 5 -o "$SERVER_JAR.tmp" "$JAR_URL"

    # The jar is downloaded and then executed, so verify it rather than trust the transfer.
    echo "$JAR_SHA  $SERVER_JAR.tmp" | sha256sum -c - >/dev/null
    mv "$SERVER_JAR.tmp" "$SERVER_JAR"
    echo "    verified"
else
    echo "==> Using cached $(basename "$SERVER_JAR")"
fi

# ---------------------------------------------------------------------------
# Server configuration
# ---------------------------------------------------------------------------
echo "eula=true" > "$RUNDIR/eula.txt"

# Written once so hand-edits survive; delete the file to regenerate it.
if [[ ! -f "$RUNDIR/server.properties" ]]; then
    cat > "$RUNDIR/server.properties" <<PROPS
# Offline mode so arbitrary usernames can join for multi-player tracking tests.
# Local development only.
online-mode=false
level-type=minecraft\:flat
view-distance=6
simulation-distance=6
spawn-protection=0
max-players=10
motd=SessionPulse dev server
PROPS
fi

cp -f "$PLUGIN_JAR" "$RUNDIR/plugins/"

# ---------------------------------------------------------------------------
# Run
# ---------------------------------------------------------------------------
cat <<'BANNER'

==> Starting. The plugin has no behaviour yet - it enables, it disables, and that is all.
    What this boot proves is that plugin.yml is loadable, which no Gradle task checks.

      grep "SessionPulse enabled" run/logs/latest.log

    In game, /spulse prints its usage string: the command is declared with no executor
    until the command issue lands. That is expected, not a broken build.

    Type `stop` to shut down.

BANNER

cd "$RUNDIR"
exec java -Xms1G -Xmx2G -jar "$SERVER_JAR" --nogui
