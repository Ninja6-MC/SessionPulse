#!/usr/bin/env python3
#
# Generates docs/store-description.md from README.md.
#
# Usage:
#   python scripts/store-description.py            # write the file
#   python scripts/store-description.py --check    # fail if the file is out of date
#
# Why this exists: the Modrinth and Hangar project pages live only on those sites. Nothing
# in the release pipeline writes the Modrinth body - mc-publish uploads versions and a
# per-version changelog and has no description input - so a hand-pasted copy of the
# README has no diff, no history and no review, and goes stale the first time a feature
# or config key changes. SpiralGenesis's Modrinth body did exactly that, and was rejected
# on 2026-09-05 over a markdown rule nobody could review, because the text existed in
# exactly one place.
#
# So the body is derived from the README instead, committed, and checked in CI. The README
# stays the single source of truth; this script removes the parts of it that only make
# sense on github.com and enforces the rule that got that submission rejected. One output
# serves both stores.
#
# What it does NOT do: upload anything. Publishing the result is a manual paste into the
# Modrinth description editor and the Hangar resource page. On Modrinth, an edit alone
# does not re-enter the moderation queue, so a rejected project also needs "Resubmit for
# review". See RELEASE_PROCESS.md.

import argparse
import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
README = os.path.join(ROOT, "README.md")
OUTPUT = os.path.join(ROOT, "docs", "store-description.md")

# The one place the repository is named. Both URL bases below are built from it.
REPOSITORY = "Ninja6-MC/SessionPulse"

BLOB = "https://github.com/" + REPOSITORY + "/blob/main/"

# An image has to resolve to the file itself. A blob URL serves GitHub's HTML viewer, so
# routing image targets through BLOB would produce a broken image rather than a 404, which
# is harder to notice.
RAW = "https://raw.githubusercontent.com/" + REPOSITORY + "/main/"

# Anything already addressable as-is from a store page. The ONLY definition of absolute
# in this file: the transforms decide what to rewrite by it and the checks decide what to
# reject by it, so a target the transform leaves alone cannot then be rejected, and one it
# rewrites cannot slip past. Divergent copies of this test once meant a mailto link failed
# the build while a protocol-relative one was rewritten to blob/main///host and passed.
# Add a scheme here, not at a call site.
ABSOLUTE = re.compile(r"^(https?:|mailto:|#|data:|//)")

# src/href/srcset in raw HTML, in all three quoting styles. The leading boundary keeps
# data-src and similar from being reported under the wrong attribute name.
ATTRIBUTE = re.compile(
    r"(?:^|[\s<])(src|href|srcset)\s*=\s*(?:\"([^\"]*)\"|'([^']*)'|([^\s>]+))"
)


# A reference-style link definition, `[label]: target`, optionally with the target in
# angle brackets. The transform only rewrites the form CommonMark always treats as a
# definition - at most three spaces of indentation. The check matches any indentation, so
# a definition the transform declines to touch (inside a list item, say) is rejected
# rather than passed through with its relative target intact.
REFERENCE = re.compile(r"^( {0,3}\[[^\]]+\]:[ \t]*<?)([^\s>]+)")
REFERENCE_ANYWHERE = re.compile(r"^\s*\[[^\]]+\]:[ \t]*<?([^\s>]+)")

# A definition gives no hint whether a link or an image uses it, so the target's
# extension decides which base it is rewritten through.
IMAGE_EXTENSION = re.compile(r"\.(png|jpe?g|gif|svg|webp)([?#]|$)", re.IGNORECASE)


class ReadmeError(ValueError):
    """The README cannot be transformed at all, as opposed to producing a bad result.

    A distinct type so main() does not also swallow UnicodeDecodeError, which subclasses
    ValueError and would otherwise be reported as a malformed-README error with nothing
    but a codec message to go on.
    """


HEADER = (
    "<!-- Generated from README.md by scripts/store-description.py. Do not edit.\n"
    "     Paste everything below this comment into the Modrinth description editor\n"
    "     and the Hangar resource page. -->\n"
)

# Prose characters the README uses that have no business in a store description we want
# to keep diffable and paste-safe. Fenced blocks are deliberately left alone.
ASCII_FOLD = [
    (chr(0x2014), "-"),  # em dash (the README always spaces it)
    (chr(0x2013), "-"),  # en dash
    (chr(0x00b7), "-"),  # middle dot, used as a link separator
    (chr(0x00d7), "x"),  # multiplication sign
    (chr(0x2705), "Yes"),  # check mark
    (chr(0x274c), "No"),  # cross mark
    (chr(0x2b07), ""),  # down arrow, download link
]


def strip_title(lines):
    """Drop the README's markdown H1.

    Both stores render the project name above the description themselves. Only the H1
    that opens the file goes; an H1 anywhere else survives and is rejected by
    check_no_stray_hashes as a malformed heading, because it would be a second title.
    """
    for index, line in enumerate(lines):
        if line.strip() == "":
            continue
        if re.match(r"^# \S", line):
            return lines[:index] + lines[index + 1:]
        return lines
    return lines


def strip_html_blocks(lines):
    """Drop the GitHub-only chrome: centred logo, tagline, badge row, footer org mark.

    The stores render the project icon and summary themselves, and none of the relative
    image sources resolve there. Anything at the top level that opens an HTML block goes,
    along with everything up to its closing tag.
    """
    out = []
    closing = None
    for line in lines:
        if closing is not None:
            if closing in line:
                closing = None
            continue
        stripped = line.strip()
        if stripped.startswith("<p ") or stripped.startswith("<p>"):
            if "</p>" not in stripped:
                closing = "</p>"
            continue
        if stripped.startswith("<h1"):
            continue
        out.append(line)

    # An unclosed block would otherwise consume every remaining line and still exit 0,
    # writing a description containing nothing but the generated-by comment. The tool
    # exists to stop a bad page reaching the store, so it must not fail open.
    if closing is not None:
        raise ReadmeError(
            "README.md has an HTML block that is never closed with %s. "
            "Stripping it would drop the rest of the file." % closing
        )

    return out


def strip_store_links(lines):
    """Drop the Download / Modrinth / Hangar row.

    A store page linking to itself is noise, and the download link duplicates the
    versions list each store shows beside the description.
    """
    out = []
    skipping = False
    for line in lines:
        if line.startswith("**[") and "Download]" in line:
            skipping = True
            continue
        if skipping:
            if line.strip() == "":
                skipping = False
            continue
        out.append(line)
    return out


def fold_ascii(lines):
    """Fold typographic characters to ASCII, outside fenced blocks."""
    out = []
    fenced = False
    for line in lines:
        if line.lstrip().startswith("```"):
            fenced = not fenced
            out.append(line)
            continue
        if not fenced:
            for bad, good in ASCII_FOLD:
                line = line.replace(bad, good)
        out.append(line)
    return out


def collapse_blanks(lines):
    out = []
    for line in lines:
        if line.strip() == "" and out and out[-1].strip() == "":
            continue
        out.append(line)
    while out and out[0].strip() == "":
        out.pop(0)
    while out and out[-1].strip() in ("", "---"):
        out.pop()
    return out


def check_no_stray_hashes(lines):
    """The rule SpiralGenesis's submission was rejected over.

    Modrinth content rules 2.2: headers separate sections, they are not body text. A
    line-initial "#" inside a fenced block is YAML or shell comment syntax, but read as
    raw markdown it is indistinguishable from a header used as body text - which is how a
    config block got SpiralGenesis rejected on 2026-09-05.
    """
    problems = []
    fenced = False
    for number, line in enumerate(lines, 1):
        if line.lstrip().startswith("```"):
            fenced = not fenced
            continue
        if not line.lstrip().startswith("#"):
            continue
        if fenced:
            problems.append("%d: '#' begins a line inside a code fence: %s" % (number, line.strip()))
        elif not re.match(r"^#{2,6} \S", line):
            problems.append("%d: not a well-formed heading: %s" % (number, line.strip()))
    return problems


def check_no_relative_links(text):
    problems = []
    for number, line in enumerate(text.split("\n"), 1):
        for match in re.finditer(r"\]\(([^)]+)\)", line):
            target = match.group(1)
            if not ABSOLUTE.match(target):
                problems.append("%d: relative link: %s" % (number, target))
    return problems


def check_no_relative_reference_definitions(text):
    """Reject reference-style definitions whose target is still relative.

    `[guide]: docs/ADMIN_GUIDE.md` is as much a link as `[guide](docs/ADMIN_GUIDE.md)`,
    but the inline link check cannot see it. Fenced blocks are skipped: there the line is
    code, not a definition.
    """
    problems = []
    fenced = False
    for number, line in enumerate(text.split("\n"), 1):
        if line.lstrip().startswith("```"):
            fenced = not fenced
            continue
        if fenced:
            continue
        match = REFERENCE_ANYWHERE.match(line)
        if match and not ABSOLUTE.match(match.group(1)):
            problems.append("%d: relative reference definition: %s" % (number, match.group(1)))
    return problems


def check_no_relative_html_refs(text):
    """Catch relative references the stripper does not know how to remove.

    strip_html_blocks only recognises the chrome this README actually uses, so a tag it
    has never seen - a <div> wrapper, a bare <img> - survives into the output with its
    src or href intact. A relative one 404s on a store page exactly like a relative
    markdown link does, and the markdown link check cannot see it because it is not
    markdown. So assert on the output rather than trying to enumerate every tag.

    All three quoting styles are matched. Checking only double quotes would close the
    common case and leave the general one open, which is the kind of half-fix that reads
    as covered in review.
    """
    problems = []
    fenced = False
    for number, line in enumerate(text.split("\n"), 1):
        if line.lstrip().startswith("```"):
            fenced = not fenced
            continue
        if fenced:
            continue
        for attribute, double, single, bare in ATTRIBUTE.findall(line):
            value = double or single or bare
            # srcset carries a comma-separated candidate list, each entry a URL
            # followed by an optional descriptor. Every candidate has to resolve, so
            # checking the whole value as one URL would pass on the first entry alone.
            for candidate in value.split(",") if attribute == "srcset" else [value]:
                target = candidate.strip().split(" ")[0]
                if target and not ABSOLUTE.match(target):
                    problems.append(
                        "%d: relative %s in raw HTML: %s" % (number, attribute, target)
                    )
    return problems


def check_body_is_intact(text):
    """A last sanity check that something actually survived the transforms.

    Cheap insurance against a stripper bug quietly producing an empty page. The README
    has several sections, so an output with none of them means a transform went wrong,
    not that the README got shorter.
    """
    if not re.search(r"(?m)^## \S", text):
        return ["generated description contains no section headings at all"]
    return []


def check_ascii(text):
    problems = []
    fenced = False
    for number, line in enumerate(text.split("\n"), 1):
        if line.lstrip().startswith("```"):
            fenced = not fenced
            continue
        if fenced:
            continue
        for character in line:
            if ord(character) > 127:
                problems.append("%d: non-ASCII U+%04X outside a code fence" % (number, ord(character)))
                break
    return problems


def absolutise_image_targets(text):
    """Point relative markdown image targets at raw.githubusercontent.com.

    Runs before absolutise_links, which would otherwise rewrite them through BLOB and
    produce an image tag pointing at an HTML page. The README carries no markdown images
    today; this exists so that adding one does not quietly break the store page.
    """

    def replace(match):
        target = match.group(2)
        if ABSOLUTE.match(target):
            return match.group(0)
        return "!" + match.group(1) + "(" + RAW + target + ")"

    return re.sub(r"!(\[[^\]]*\])\(([^)]+)\)", replace, text)


def absolutise_links(text):
    """Point relative markdown links at github.com.

    On a store page a relative target resolves against the project URL and 404s.
    """

    def replace(match):
        target = match.group(1)
        if ABSOLUTE.match(target):
            return match.group(0)
        return "](" + BLOB + target + ")"

    return re.sub(r"\]\(([^)]+)\)", replace, text)


def absolutise_reference_definitions(text):
    """Point relative reference-style definitions at github.com, outside fenced blocks.

    Image files go through RAW and everything else through BLOB, for the same reason
    absolutise_image_targets runs before absolutise_links.
    """
    out = []
    fenced = False
    for line in text.split("\n"):
        if line.lstrip().startswith("```"):
            fenced = not fenced
        elif not fenced:
            match = REFERENCE.match(line)
            if match and not ABSOLUTE.match(match.group(2)):
                base = RAW if IMAGE_EXTENSION.search(match.group(2)) else BLOB
                line = match.group(1) + base + line[match.start(2):]
        out.append(line)
    return "\n".join(out)


def render():
    with io.open(README, encoding="utf-8") as handle:
        lines = handle.read().replace("\r\n", "\n").split("\n")

    lines = strip_title(lines)
    lines = strip_html_blocks(lines)
    lines = strip_store_links(lines)
    lines = fold_ascii(lines)
    lines = collapse_blanks(lines)

    text = absolutise_links(absolutise_image_targets("\n".join(lines)))
    text = absolutise_reference_definitions(text) + "\n"

    problems = (
        check_no_stray_hashes(text.split("\n"))
        + check_no_relative_links(text)
        + check_no_relative_reference_definitions(text)
        + check_no_relative_html_refs(text)
        + check_ascii(text)
        + check_body_is_intact(text)
    )
    if problems:
        sys.stderr.write("Generated description violates the store content rules:\n")
        for problem in problems:
            sys.stderr.write("  " + problem + "\n")
        sys.exit(1)

    return HEADER + "\n" + text


def main():
    parser = argparse.ArgumentParser(
        description="Generate docs/store-description.md from README.md."
    )
    parser.add_argument(
        "--check",
        action="store_true",
        help="exit non-zero if docs/store-description.md is out of date",
    )
    args = parser.parse_args()

    # A malformed README is a content error, not a crash: report it the same way a
    # rule violation is reported so CI logs read the same for both.
    try:
        generated = render()
    except ReadmeError as error:
        sys.stderr.write("Cannot generate the description from README.md:\n")
        sys.stderr.write("  " + str(error) + "\n")
        sys.exit(1)

    if args.check:
        if not os.path.exists(OUTPUT):
            sys.stderr.write(
                "docs/store-description.md does not exist.\n"
                "Run: python scripts/store-description.py\n"
            )
            sys.exit(1)
        with io.open(OUTPUT, encoding="utf-8") as handle:
            current = handle.read().replace("\r\n", "\n")
        if current != generated:
            sys.stderr.write(
                "docs/store-description.md is out of date with README.md.\n"
                "Run: python scripts/store-description.py\n"
            )
            sys.exit(1)
        print("docs/store-description.md is up to date.")
        return

    with io.open(OUTPUT, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(generated)
    print("Wrote docs/store-description.md (%d characters)." % len(generated))


if __name__ == "__main__":
    main()
