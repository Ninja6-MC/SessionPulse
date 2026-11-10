#!/usr/bin/env bash
#
# Cuts a release tag, pushes it and dispatches the publisher for it. The push starts
# .github/workflows/release-candidate.yml, which builds and smoke-tests the candidate and
# publishes nothing. Once it passes, this script prints the registry owner-inventory
# checklist, asks for each registry's absence attestation and starts
# .github/workflows/release.yml on the tag, which waits on the protected `release`
# environment before publishing to GitHub Releases, Modrinth and Hangar.
#
# Usage: scripts/release.sh <version> [--dry-run]
#        scripts/release.sh <version> --resume <destinations> --candidate-run <run-id> [--dry-run]
#
#   scripts/release.sh 1.0.0
#   scripts/release.sh 0.2.0-beta.2 --dry-run
#   scripts/release.sh 0.2.0-beta.2 --resume hangar --candidate-run <candidate-run-id>
#
# Pass the version without the leading "v"; the tag gets it. --resume skips tagging and
# dispatches the publisher again for an existing tag, for the comma-separated destinations
# given (github, modrinth, hangar) and the candidate run that built the tag.
#
# Why this exists rather than `git tag && git push`: a tag is the only irreversible step
# in the release process. Once approved it publishes to two registries, and unpublishing
# from them is far more work than deleting a tag. The checks below are the accidents that
# are easy to have and expensive to undo - tagging from a linked worktree that is parked
# on an old commit, tagging a stale main, tagging with uncommitted work in the tree,
# tagging a commit CI has not passed, tagging an unsuffixed or -rc version below 1.0.0,
# or tagging a stable version whose changelog section does not exist.
#
# The tag grammar and the changelog lookup are copies of the ones in
# release-candidate.yml, and scripts/tests/test_release_script.py fails if they drift
# apart. CI applies them only after the tag is public; this applies them before.
#
# Sourcing this file defines the functions without running anything, which is how the
# tests exercise the version and changelog rules.

set -euo pipefail

REPO="Ninja6-MC/SessionPulse"
ALL_DESTINATIONS="github,modrinth,hangar"

RED=$'\033[31m'; GREEN=$'\033[32m'; YELLOW=$'\033[33m'; RESET=$'\033[0m'

fail() { echo "${RED}FAIL${RESET} $*" >&2; exit 1; }
ok()   { echo "${GREEN}ok${RESET}   $*"; }
warn() { echo "${YELLOW}warn${RESET} $*"; }

blank() { [ -z "${1//[[:space:]]/}" ]; }

# ---------------------------------------------------------------------------
# The tag grammar release-candidate.yml enforces, plus the two rules from section 1 of
# RELEASE_PROCESS.md that the workflow cannot: below 1.0.0 every release is a
# pre-release, and no release candidate is cut before 1.0.0.
# ---------------------------------------------------------------------------
check_version() {
  local version="$1" tag="v$1" major
  if [[ ! "$tag" =~ ^v[0-9]+\.[0-9]+\.[0-9]+(-(alpha|beta|rc)\.[0-9]+)?$ ]]; then
    fail "'$version' is not a valid version.
     Expected MAJOR.MINOR.PATCH with an optional -alpha.N, -beta.N or -rc.N suffix.
     For example: 1.0.0, 0.2.0-beta.2"
  fi
  major="${version%%.*}"
  if [ "$((10#$major))" -lt 1 ]; then
    case "$version" in
      *-alpha.*|*-beta.*) ;;
      *-rc.*)
        fail "'$version' is a release candidate below 1.0.0.
     Use -alpha.N or -beta.N until 1.0.0; the first release candidate is 1.0.0-rc.1." ;;
      *)
        fail "'$version' is an unsuffixed version below 1.0.0.
     The release workflows publish every unsuffixed tag as a stable release. Use -alpha.N or -beta.N." ;;
    esac
  fi
}

# ---------------------------------------------------------------------------
# The changelog lookup from release-candidate.yml's "Collect release notes" step. extract()
# must stay identical to the workflow's; the test compares the two.
#
# find_notes sets NOTES and NOTES_SECTION. A stable version needs its own section;
# a pre-release tries its own, then the base version's, then [Unreleased], and may
# end up with none (the workflow then writes a placeholder line).
# ---------------------------------------------------------------------------
extract() {
  awk -v header="## [$1]" '
    index($0, header) == 1 { found = 1; next }
    found && /^## / { exit }
    found && /^\[[^]]+\]: / { exit }
    found { print }
  ' CHANGELOG.md
}

find_notes() {
  local version="$1" section
  NOTES=""
  NOTES_SECTION=""
  if [ "$version" = "${version%%-*}" ]; then
    NOTES="$(extract "$version")"
    blank "$NOTES" || NOTES_SECTION="$version"
    return 0
  fi
  for section in "$version" "${version%%-*}" Unreleased; do
    NOTES="$(extract "$section")"
    if ! blank "$NOTES"; then
      NOTES_SECTION="$section"
      return 0
    fi
  done
  NOTES=""
}

check_notes() {
  local version="$1"
  find_notes "$version"
  if [ -n "$NOTES_SECTION" ]; then
    ok "changelog notes from '## [$NOTES_SECTION]'"
  elif [ "$version" = "${version%%-*}" ]; then
    fail "CHANGELOG.md has no '## [$version]' section.
     A stable release must document what changed. Move the entries under that
     heading, merge to main, and try again."
  else
    warn "no '## [$version]', '## [${version%%-*}]' or '## [Unreleased]' section;
       the release notes will read: No changelog section was written for this pre-release."
  fi
}

# ---------------------------------------------------------------------------
# CI on the commit being tagged. RELEASE_PROCESS.md makes a green CI run the gate:
# the release job runs only test and shadowJar. Without a usable gh this can only
# warn, so check the commit's runs by hand before confirming.
# ---------------------------------------------------------------------------
check_ci() {
  local sha="$1" result status conclusion url
  if ! command -v gh >/dev/null 2>&1; then
    warn "gh is not installed; confirm CI passed on ${sha:0:7} before tagging:
       https://github.com/$REPO/actions/workflows/ci.yml?query=branch%3Amain"
    return 0
  fi
  if ! result="$(gh run list -R "$REPO" --workflow ci.yml --branch main --event push \
      --commit "$sha" --limit 1 --json status,conclusion,url \
      --jq '.[0] // {} | "\(.status)|\(.conclusion)|\(.url)"' 2>/dev/null)"; then
    warn "gh could not list CI runs (not signed in, or offline); confirm CI passed on ${sha:0:7} before tagging:
       https://github.com/$REPO/actions/workflows/ci.yml?query=branch%3Amain"
    return 0
  fi
  IFS='|' read -r status conclusion url <<<"$result"
  if [ "$status" = "null" ]; then
    fail "no Continuous Integration run on main for ${sha:0:7}. Wait for it, or start one."
  fi
  if [ "$status" != "completed" ]; then
    fail "CI on ${sha:0:7} is still $status. Wait for it to finish:
     $url"
  fi
  if [ "$conclusion" != "success" ]; then
    fail "CI on ${sha:0:7} concluded '$conclusion', not success:
     $url"
  fi
  ok "CI passed on ${sha:0:7}"
}

# ---------------------------------------------------------------------------
# Publisher dispatch. An absence attestation belongs to one dispatch of release.yml
# and is never stored, so it is asked for here, each time, with no default.
# ---------------------------------------------------------------------------
check_destinations() {
  local value="$1" name seen=","
  if [[ ! "$value" =~ ^(github|modrinth|hangar)(,(github|modrinth|hangar))*$ ]]; then
    fail "'$value' is not a destination list.
     Expected a comma-separated list of github, modrinth and hangar, such as: modrinth,hangar"
  fi
  IFS=',' read -r -a names <<<"$value"
  for name in "${names[@]}"; do
    case "$seen" in *",$name,"*) fail "'$name' is listed twice in '$value'." ;; esac
    seen="$seen$name,"
  done
}

selected() { case ",$1," in *",$2,"*) return 0 ;; esac; return 1; }

print_inventory_checklist() {
  local version="$1" destinations="$2"
  echo
  echo "Owner inventory checklist for $version. An omitted API result is not proof of absence."
  if selected "$destinations" modrinth; then
    echo "  Modrinth: sign in as the project owner and open the project's Versions management list."
    echo "            Check every status, including draft, unlisted, scheduled and archived, and any"
    echo "            deleted-version view. Is $version there in any state?"
  fi
  if selected "$destinations" hangar; then
    echo "  Hangar:   sign in as the project owner and open the Versions and management views."
    echo "            Check hidden and soft-deleted versions and any deleted-version view."
    echo "            Is $version there in any state?"
  fi
  echo "  If a view is unavailable, resolve that before answering; do not infer absence from the"
  echo "  public page. A version that exists in any state is 'present': the publisher then"
  echo "  verifies it and skips it, or stops if its bytes or metadata differ."
  echo
}

# Prints true or false. No default: anything but 'absent' or 'present' asks again, and
# end of input fails rather than choosing for the maintainer.
ask_absence() {
  local destination="$1" version="$2" answer
  while true; do
    read -r -p "Is $destination $version absent from the owner inventory in every state? Type absent or present: " answer \
      || fail "no answer for $destination. Nothing was dispatched."
    case "${answer%$'\r'}" in
      absent) echo true; return 0 ;;
      present) echo false; return 0 ;;
      *) echo "Type absent or present." >&2 ;;
    esac
  done
}

wait_for_candidate() {
  local tag="$1" sha="$2" id="" tries=0
  echo "Waiting for the Release Candidate run for $tag..." >&2
  while [ -z "$id" ]; do
    id="$(gh run list -R "$REPO" --workflow release-candidate.yml --event push --branch "$tag" \
      --limit 5 --json databaseId,headSha \
      --jq "[.[] | select(.headSha == \"$sha\")][0].databaseId // empty")" || id=""
    if [ -z "$id" ]; then
      tries=$((tries + 1))
      [ "$tries" -lt 36 ] || fail "no Release Candidate run appeared for $tag. Check:
     https://github.com/$REPO/actions/workflows/release-candidate.yml"
      sleep 5
    fi
  done
  gh run watch "$id" -R "$REPO" --exit-status >&2 \
    || fail "Release Candidate run $id did not succeed. Nothing was dispatched; fix it and use --resume."
  echo "$id"
}

dispatch_publisher() {
  local tag="$1" candidate_run="$2" destinations="$3" dry_run="$4" version="${1#v}"
  local modrinth=false hangar=false nonce url="" tries=0

  print_inventory_checklist "$version" "$destinations"
  if selected "$destinations" modrinth; then modrinth="$(ask_absence Modrinth "$version")"; fi
  if selected "$destinations" hangar; then hangar="$(ask_absence Hangar "$version")"; fi

  echo
  echo "  tag            $tag"
  echo "  candidate run  $candidate_run"
  echo "  destinations   $destinations"
  echo "  attested absent: modrinth=$modrinth hangar=$hangar"
  echo

  if [ "$dry_run" -eq 1 ]; then
    echo "${YELLOW}Dry run: the publisher was not dispatched.${RESET}"
    return 0
  fi

  nonce="$(date +%s)-$$-$RANDOM"
  gh workflow run release.yml -R "$REPO" --ref "$tag" \
    -f tag="$tag" -f candidate_run_id="$candidate_run" -f destinations="$destinations" \
    -f modrinth_absence_confirmed="$modrinth" -f hangar_absence_confirmed="$hangar" \
    -f nonce="$nonce"
  ok "dispatched Publish Release for $tag"

  # gh workflow run returns no run ID; the nonce in run-name identifies this dispatch.
  while [ -z "$url" ] && [ "$tries" -lt 24 ]; do
    sleep 5
    tries=$((tries + 1))
    url="$(gh run list -R "$REPO" --workflow release.yml --event workflow_dispatch --limit 20 \
      --json displayTitle,url --jq ".[] | select(.displayTitle | endswith(\" $nonce\")) | .url" \
      2>/dev/null | head -1)" || url=""
  done
  echo
  if [ -n "$url" ]; then
    echo "Publisher run: $url"
  else
    echo "Find the run named 'Publish $tag $nonce':"
    echo "  https://github.com/$REPO/actions/workflows/release.yml"
  fi
  echo "Approve the release environment in the browser once the plan job has passed."
  echo "See RELEASE_PROCESS.md, step 3, for verification and retry rules."
}

resume() {
  local version="$1" destinations="$2" candidate_run="$3" dry_run="$4" tag="v$1" rc=0
  check_version "$version"
  check_destinations "$destinations"
  [[ "$candidate_run" =~ ^[1-9][0-9]*$ ]] || fail "--candidate-run takes the Release Candidate run ID."
  command -v gh >/dev/null 2>&1 || fail "gh is required to dispatch the publisher."
  git ls-remote --exit-code --tags origin "refs/tags/$tag" >/dev/null 2>&1 || rc=$?
  [ "$rc" -eq 0 ] || fail "tag '$tag' is not on origin (git ls-remote exit $rc). Nothing to resume."
  ok "tag '$tag' is on origin"
  dispatch_publisher "$tag" "$candidate_run" "$destinations" "$dry_run"
}

main() {
  local version="" dry_run=0 resume_destinations="" candidate_run=""
  while [ "$#" -gt 0 ]; do
    case "$1" in
      --dry-run) dry_run=1 ;;
      --resume)
        [ "$#" -ge 2 ] || { echo "--resume takes a destination list." >&2; exit 2; }
        resume_destinations="$2"; shift ;;
      --candidate-run)
        [ "$#" -ge 2 ] || { echo "--candidate-run takes a run ID." >&2; exit 2; }
        candidate_run="$2"; shift ;;
      -*) echo "Unknown option: $1" >&2; exit 2 ;;
      *) [ -z "$version" ] || { echo "Only one version may be given." >&2; exit 2; }
         version="${1#v}" ;;
    esac
    shift
  done

  if [ -z "$version" ]; then
    echo "Usage: scripts/release.sh <version> [--dry-run]" >&2
    echo "       scripts/release.sh <version> --resume <destinations> --candidate-run <run-id> [--dry-run]" >&2
    exit 2
  fi
  if [ -n "$resume_destinations" ] || [ -n "$candidate_run" ]; then
    [ -n "$resume_destinations" ] && [ -n "$candidate_run" ] \
      || { echo "--resume and --candidate-run are used together." >&2; exit 2; }
    resume "$version" "$resume_destinations" "$candidate_run" "$dry_run"
    return
  fi

  local tag="v$version"
  cd "$(git rev-parse --show-toplevel)"

  # 1. Version rules. Checked first so a typo costs nothing.
  check_version "$version"
  ok "version '$version' is well formed and allowed"

  # 2. Refuse to run from a linked worktree. Those sit on their own branches at
  #    whatever commit they were created from, which is how a tag ends up pointing
  #    at something several merges behind main.
  local git_dir common_dir
  git_dir="$(cd "$(git rev-parse --git-dir)" && pwd -P)"
  common_dir="$(cd "$(git rev-parse --git-common-dir)" && pwd -P)"
  if [ "$git_dir" != "$common_dir" ]; then
    fail "this is a linked worktree ($(git rev-parse --show-toplevel)).
     Release from the main checkout instead."
  fi
  ok "running in the main checkout"

  # 3. On main, and nowhere else.
  local branch
  branch="$(git rev-parse --abbrev-ref HEAD)"
  [ "$branch" = "main" ] || fail "on branch '$branch', not 'main'. Run: git checkout main"
  ok "on main"

  # 4. Nothing uncommitted. A tag names a commit, so anything still in the working
  #    tree is not in the release no matter what the diff looks like locally.
  if ! git diff --quiet || ! git diff --cached --quiet; then
    echo
    git status --short | sed 's/^/     /'
    echo
    fail "working tree has uncommitted changes."
  fi
  ok "working tree is clean"

  local untracked
  untracked="$(git ls-files --others --exclude-standard | head -5 || true)"
  if [ -n "$untracked" ]; then
    warn "untracked files present (they will not be in the release):"
    echo "$untracked" | sed 's/^/       /'
  fi

  # 5. Exactly in step with origin/main. Behind means the release misses merged
  #    work; ahead means it contains commits nobody has reviewed.
  git fetch origin main --tags --quiet
  local local_sha remote_sha
  local_sha="$(git rev-parse HEAD)"
  remote_sha="$(git rev-parse origin/main)"
  if [ "$local_sha" != "$remote_sha" ]; then
    fail "main is not in step with origin/main ($(git rev-list --count origin/main..HEAD) ahead, $(git rev-list --count HEAD..origin/main) behind).
     Run: git pull origin main"
  fi
  ok "main matches origin/main at ${local_sha:0:7}"

  # 6. The tag must not already exist, locally or on origin. Re-tagging silently is
  #    how a published version ends up pointing at different code than its tag.
  #    Origin is checked first: step 5 fetched its tags, so a released tag is also
  #    local, and the local hint below must only ever name a tag that was never pushed.
  local rc=0
  git ls-remote --exit-code --tags origin "refs/tags/$tag" >/dev/null 2>&1 || rc=$?
  case "$rc" in
    0) fail "tag '$tag' already exists on origin. That version has been released." ;;
    2) ;;
    *) fail "could not query origin for '$tag' (git ls-remote exit $rc)." ;;
  esac
  if git rev-parse -q --verify "refs/tags/$tag" >/dev/null; then
    fail "tag '$tag' already exists locally but not on origin. Delete it first: git tag -d $tag"
  fi
  ok "tag '$tag' is unused locally and on origin"

  # 7. CI green on this exact commit.
  check_ci "$local_sha"

  # 8. The changelog gate release-candidate.yml applies, applied here first.
  check_notes "$version"

  # 9. Show what is about to happen. Everything above this line is reversible;
  #    everything below is not.
  local channel
  case "$version" in
    *-alpha.*) channel="alpha (GitHub pre-release, Modrinth alpha, Hangar Alpha)" ;;
    *-beta.*|*-rc.*) channel="beta (GitHub pre-release, Modrinth beta, Hangar Beta)" ;;
    *) channel="release (GitHub latest, Modrinth release, Hangar Release)" ;;
  esac

  echo
  echo "  tag      $tag"
  echo "  commit   $(git log -1 --format='%h %s')"
  echo "  channel  $channel"
  echo
  echo "  release notes:"
  if [ -n "$NOTES_SECTION" ]; then
    printf '%s\n' "$NOTES" | grep -v '^[[:space:]]*$' | head -8 | sed 's/^/    /' || true
    local total
    total="$(printf '%s\n' "$NOTES" | grep -cv '^[[:space:]]*$')"
    [ "$total" -gt 8 ] && echo "    ... and $((total - 8)) more lines"
  else
    echo "    (none)"
  fi
  echo

  if [ "$dry_run" -eq 1 ]; then
    echo "${YELLOW}Dry run: every check passed. Nothing was tagged or pushed.${RESET}"
    exit 0
  fi

  echo "Pushing this tag starts Release Candidate, which builds and smoke-tests the"
  echo "candidate. This script then waits for it, asks for the registry attestations and"
  echo "dispatches Publish Release, which waits for approval on the release environment."
  echo
  local confirm
  read -r -p "Type the tag to confirm: " confirm
  [ "$confirm" = "$tag" ] || fail "confirmation did not match. Nothing was tagged."

  # 10. Tag, then push that one tag by name. The tag is created locally first so a
  #     failed push leaves something to inspect and delete rather than a
  #     half-published state. GitHub fires no tag push events when more than three
  #     tags are pushed at once, so never push --tags.
  git tag -a "$tag" -m "SessionPulse $tag"
  ok "created $tag at ${local_sha:0:7}"

  git push origin "refs/tags/$tag"
  ok "pushed $tag"

  if ! command -v gh >/dev/null 2>&1; then
    warn "gh is not installed. When Release Candidate passes, dispatch the publisher with:
       scripts/release.sh $version --resume $ALL_DESTINATIONS --candidate-run <run-id>"
    return 0
  fi
  local candidate_run
  candidate_run="$(wait_for_candidate "$tag" "$local_sha")"
  ok "Release Candidate run $candidate_run passed"
  dispatch_publisher "$tag" "$candidate_run" "$ALL_DESTINATIONS" 0
}

if [ "${BASH_SOURCE[0]}" = "$0" ]; then
  main "$@"
fi
