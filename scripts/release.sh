#!/usr/bin/env bash
#
# Cuts a release tag and pushes it. The push starts .github/workflows/release.yml, which
# builds and smoke-tests the candidate and then waits on the protected `release`
# environment before publishing to GitHub Releases, Modrinth and Hangar.
#
# Usage: scripts/release.sh <version> [--dry-run]
#
#   scripts/release.sh 1.0.0
#   scripts/release.sh 0.2.0-beta.2
#   scripts/release.sh 0.2.0-beta.2 --dry-run
#
# Pass the version without the leading "v"; the tag gets it.
#
# Why this exists rather than `git tag && git push`: a tag is the only irreversible step
# in the release process. Once approved it publishes to two registries, and unpublishing
# from them is far more work than deleting a tag. The checks below are the accidents that
# are easy to have and expensive to undo - tagging from a linked worktree that is parked
# on an old commit, tagging a stale main, tagging with uncommitted work in the tree,
# tagging a commit CI has not passed, tagging an unsuffixed or -rc version below 1.0.0,
# or tagging a stable version whose changelog section does not exist.
#
# The tag grammar and the changelog lookup are copies of the ones in release.yml, and
# scripts/tests/test_release_script.py fails if they drift apart. CI applies them only
# after the tag is public; this applies them before.
#
# Sourcing this file defines the functions without running anything, which is how the
# tests exercise the version and changelog rules.

set -euo pipefail

REPO="Ninja6-MC/SessionPulse"

RED=$'\033[31m'; GREEN=$'\033[32m'; YELLOW=$'\033[33m'; RESET=$'\033[0m'

fail() { echo "${RED}FAIL${RESET} $*" >&2; exit 1; }
ok()   { echo "${GREEN}ok${RESET}   $*"; }
warn() { echo "${YELLOW}warn${RESET} $*"; }

blank() { [ -z "${1//[[:space:]]/}" ]; }

# ---------------------------------------------------------------------------
# The tag grammar release.yml enforces, plus the two rules from section 1 of
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
     release.yml publishes every unsuffixed tag as a stable release. Use -alpha.N or -beta.N." ;;
    esac
  fi
}

# ---------------------------------------------------------------------------
# The changelog lookup from release.yml's "Collect release notes" step. extract()
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

main() {
  local version="" dry_run=0 arg
  for arg in "$@"; do
    case "$arg" in
      --dry-run) dry_run=1 ;;
      -*) echo "Unknown option: $arg" >&2; exit 2 ;;
      *) [ -z "$version" ] || { echo "Only one version may be given." >&2; exit 2; }
         version="${arg#v}" ;;
    esac
  done

  if [ -z "$version" ]; then
    echo "Usage: scripts/release.sh <version> [--dry-run]" >&2
    echo "       scripts/release.sh 0.2.0-beta.2 --dry-run" >&2
    exit 2
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
  if git rev-parse -q --verify "refs/tags/$tag" >/dev/null; then
    fail "tag '$tag' already exists locally. Delete it first if it was never pushed: git tag -d $tag"
  fi
  local rc=0
  git ls-remote --exit-code --tags origin "refs/tags/$tag" >/dev/null 2>&1 || rc=$?
  case "$rc" in
    0) fail "tag '$tag' already exists on origin. That version has been released." ;;
    2) ;;
    *) fail "could not query origin for '$tag' (git ls-remote exit $rc)." ;;
  esac
  ok "tag '$tag' is unused locally and on origin"

  # 7. CI green on this exact commit.
  check_ci "$local_sha"

  # 8. The changelog gate release.yml applies, applied here first.
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

  echo "Pushing this tag starts Publish Release. It builds and smoke-tests the"
  echo "candidate, then waits for approval on the protected release environment."
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

  echo
  echo "Follow the run, inspect the verified candidate summary and complete the"
  echo "registry inventory audit before approving the release environment:"
  echo "  https://github.com/$REPO/actions/workflows/release.yml"
  echo "See RELEASE_PROCESS.md, step 3, for verification and retry rules."
}

if [ "${BASH_SOURCE[0]}" = "$0" ]; then
  main "$@"
fi
