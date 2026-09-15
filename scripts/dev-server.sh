#!/usr/bin/env bash
#
# Boots a local Paper, Folia or Spigot server with the freshly built plugin installed, for
# manual testing of session tracking, reminders and the /spulse command.
#
# Usage: scripts/dev-server.sh [paper|folia|spigot] [mc-version]
#
# Both arguments are optional and the platform defaults to paper. A first argument that
# starts with a digit is read as the version, so the old one-argument form
# `scripts/dev-server.sh 1.21.11` still boots Paper. The smoke matrix is CI's job, not
# this script's; scripts/dev-server.ps1 is the same thing for Windows PowerShell.
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
#
# Spigot publishes no server jar, so for spigot the first run builds one with BuildTools:
# it needs git on PATH, takes 10-25 minutes and about 2GB under run/buildtools, and the
# jar it leaves at run/spigot-<mc-version>.jar is reused after that. Delete the jar to
# rebuild it.

set -euo pipefail

PLATFORM="paper"
if [[ $# -gt 0 && ! "$1" =~ ^[0-9] ]]; then
    PLATFORM="$1"
    shift
fi
case "$PLATFORM" in
    paper | folia | spigot) ;;
    *)
        echo "error: platform must be paper, folia or spigot, not '$PLATFORM'" >&2
        exit 2
        ;;
esac
MC_VERSION="${1:-1.20.4}"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUNDIR="$REPO_ROOT/run"
# Per platform in the name, so switching between platforms keeps each one cached.
SERVER_JAR="$RUNDIR/$PLATFORM-$MC_VERSION.jar"

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
if [[ ! -f "$SERVER_JAR" && "$PLATFORM" == "spigot" ]]; then
    # BuildTools clones Spigot's repositories with the system git on Linux and fails a
    # long way in without it, so check first.
    if ! command -v git >/dev/null 2>&1; then
        echo "error: building spigot needs git on PATH" >&2
        exit 1
    fi
    echo "==> Building spigot $MC_VERSION with BuildTools (10-25 minutes on the first run)"
    BT_JSON="$(curl -fsS --retry 3 --retry-delay 5 -m 60 \
        'https://hub.spigotmc.org/jenkins/job/BuildTools/lastSuccessfulBuild/api/json?tree=number')"
    BT_BUILD="$(printf '%s' "$BT_JSON" | grep -oE '"number":[0-9]+' | grep -oE '[0-9]+' || true)"
    if [[ -z "$BT_BUILD" ]]; then
        echo "error: could not resolve the latest BuildTools build" >&2
        exit 1
    fi
    BT_DIR="$RUNDIR/buildtools"
    BT_JAR="$BT_DIR/BuildTools-$BT_BUILD.jar"
    mkdir -p "$BT_DIR"
    if [[ ! -f "$BT_JAR" ]]; then
        curl -fsSL --retry 3 --retry-delay 5 -o "$BT_JAR.tmp" \
            "https://hub.spigotmc.org/jenkins/job/BuildTools/$BT_BUILD/artifact/target/BuildTools.jar"
        mv "$BT_JAR.tmp" "$BT_JAR"
    fi
    echo "    BuildTools build $BT_BUILD"
    (cd "$BT_DIR" && java -jar "$BT_JAR" --rev "$MC_VERSION" --compile SPIGOT \
        --output-dir "$RUNDIR" --final-name "spigot-$MC_VERSION.jar")
    if [[ ! -f "$SERVER_JAR" ]]; then
        echo "error: BuildTools finished without producing $SERVER_JAR" >&2
        exit 1
    fi
elif [[ ! -f "$SERVER_JAR" ]]; then
    echo "==> Resolving $PLATFORM $MC_VERSION"
    API="https://fill.papermc.io/v3/projects/$PLATFORM/versions/$MC_VERSION/builds/latest"
    BUILD_JSON="$(curl -fsS --retry 3 --retry-delay 5 -m 60 "$API")"

    # jq is present in CI but not in a stock Git Bash, so slice the payload by hand.
    # Everything before "server:default" is dropped, which discards the Mojang-mapped
    # download that would otherwise match these patterns first.
    SEGMENT="${BUILD_JSON#*\"server:default\":}"
    JAR_URL="$(printf '%s' "$SEGMENT" | grep -oE 'https://[^"]+' | head -1)"
    JAR_SHA="$(printf '%s' "$SEGMENT" | grep -oE '"sha256":"[a-f0-9]{64}"' | head -1 \
        | grep -oE '[a-f0-9]{64}')"

    if [[ -z "$JAR_URL" || -z "$JAR_SHA" ]]; then
        echo "error: could not resolve a download for $PLATFORM $MC_VERSION" >&2
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
# A BuildTools jar here is never refreshed, so CraftBukkit soon calls it outdated and
# sleeps 20 seconds on every boot. The flag skips that, as smoke-test.sh does.
JVM_FLAGS=()
if [[ "$PLATFORM" == spigot ]]; then
    JVM_FLAGS+=(-DIReallyKnowWhatIAmDoingISwear)
fi
exec java -Xms1G -Xmx2G "${JVM_FLAGS[@]}" -jar "$SERVER_JAR" --nogui
