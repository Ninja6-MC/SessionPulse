#!/usr/bin/env bash
#
# Non-writing credential and readiness checks for every destination selected in this
# dispatch. Every publish job in .github/workflows/release.yml runs this before its own
# write, so a prerequisite that can be checked stops all destinations, not just one.
# Destination version state is not read here: another job may be uploading it.
#
# Inputs come from the environment: SELECT_GITHUB, SELECT_MODRINTH, SELECT_HANGAR
# (true/false), TAG, SHA, CANDIDATE_RUN, CANDIDATE_ATTEMPT, VERSION, HANGAR_CHANNEL,
# MODRINTH_PROJECT, HANGAR_PROJECT, MODRINTH_TOKEN, HANGAR_API_TOKEN and MIGRATED.

set -euo pipefail

check() {
  python3 scripts/release-destination.py "$1" --credentials-only --directory candidate \
    --evidence evidence.json --tag "$TAG" --sha "$SHA" --run-id "$CANDIDATE_RUN" \
    --attempt "$CANDIDATE_ATTEMPT" "${@:2}"
}

test "$MIGRATED" = true || { echo '::error::Release token migration is incomplete; see #79'; exit 1; }

if [ "$SELECT_GITHUB" = true ]; then
  check github
fi

if [ "$SELECT_MODRINTH" = true ]; then
  test -n "$MODRINTH_TOKEN" || { echo '::error::Missing release environment MODRINTH_TOKEN'; exit 1; }
  check modrinth --project "$MODRINTH_PROJECT"
fi

if [ "$SELECT_HANGAR" = true ]; then
  test -n "$HANGAR_API_TOKEN" || { echo '::error::Missing release environment HANGAR_API_TOKEN'; exit 1; }
  check hangar --project "$HANGAR_PROJECT"
  chmod +x ./gradlew
  ./gradlew publishPluginPublicationToHangar --dry-run -PpluginVersion="$VERSION" \
    -PhangarChannel="$HANGAR_CHANNEL" -PhangarProject="$HANGAR_PROJECT" \
    -PreleaseJar="candidate/SessionPulse-$VERSION.jar" --no-daemon > task-graph.txt
  cat task-graph.txt
  python3 scripts/check-hangar-task-graph.py task-graph.txt
fi
