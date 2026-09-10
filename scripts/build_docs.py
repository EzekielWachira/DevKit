#!/usr/bin/env python3
"""
Assembles the DevKit documentation site from the repository's own Markdown.

The READMEs stay canonical. GitHub renders them, contributors edit them, and
this script turns them into a navigable site — rather than a `docs/` tree that
duplicates them and drifts.

Two problems have to be solved to do that honestly:

**Size.** `chartkit/README.md` is over seven thousand lines. As one page it is
slow to load and impossible to navigate. Each `##` section becomes its own page.

**Links.** Those files are full of in-page anchors — `[Scales](#scales)` — which
break the moment a file becomes many pages. Every heading at every level is
recorded, and every link is rewritten to the page that now holds it. A link
that cannot be resolved fails the build rather than shipping as a 404.

Usage:  python3 scripts/build_docs.py [--out site-src]
"""

from __future__ import annotations

import argparse
import os
import re
import shutil
import sys
from dataclasses import dataclass, field
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


@dataclass
class Book:
    """One source file, rendered as a section of the site."""

    id: str
    title: str
    source: str
    #: Short line under the title in the navigation and on the landing page.
    tagline: str
    #: Section title this book starts at, when a file holds more than one book.
    starts_at: str | None = None
    #: Section title the *next* book starts at, i.e. where this one ends.
    ends_before: str | None = None
    #: Rendered as a single page rather than split. For short files.
    single_page: bool = False


BOOKS = [
    Book(
        id="ecosystem",
        title="The ecosystem",
        source="README.md",
        tagline="Modules, requirements, installing, and release safety",
        ends_before="TextFieldState and one fill target",
    ),
    Book(
        id="fillkit",
        title="FillKit",
        source="README.md",
        tagline="Fill Compose forms with coherent synthetic data, and reproduce any form state",
        starts_at="TextFieldState and one fill target",
    ),
    Book(
        id="chartkit",
        title="ChartKit",
        source="chartkit/README.md",
        tagline="Compose-native charts over your own data classes",
    ),
    Book(
        id="netkit",
        title="NetKit",
        source="netkit/README.md",
        tagline="Simulate offline, latency, timeouts and HTTP failures",
    ),
    Book(
        id="core",
        title="Core",
        source="core/README.md",
        tagline="The shared foundation. Deliberately tiny",
        single_page=True,
    ),
    Book(
        id="publishing",
        title="Publishing",
        source="docs/PUBLISHING.md",
        tagline="Artifacts, versioning, the BOM, and how a release is cut",
    ),
]

#: Source files that are *not* books but are linked to from them.
LINK_ALIASES = {
    "README.md": "ecosystem/index.md",
    "chartkit/README.md": "chartkit/index.md",
    "netkit/README.md": "netkit/index.md",
    "core/README.md": "core/index.md",
    "fillkit/README.md": "fillkit/index.md",
    "docs/PUBLISHING.md": "publishing/index.md",
    "LICENSE.md": "https://github.com/EzekielWachira/DevKit/blob/main/LICENSE.md",
}

#: Where a repository file that is not part of this site is read instead.
REPO_BLOB = "https://github.com/EzekielWachira/DevKit/blob/main"

#: Pictures of the charts, rendered on a device by `DocsAssetCaptureTest` and
#: collected by `scripts/capture-docs-assets.sh`. A file named after a page is
#: shown on that page; nothing lists the pairings, so adding a chart to the
#: capture test is all it takes to illustrate its page.
ASSETS = ROOT / "docs-assets"

FENCE = re.compile(r"^(\s*)(```+|~~~+)")
HEADING = re.compile(r"^(#{1,6})\s+(.*?)\s*#*$")
LINK = re.compile(r"\[([^\]]*)\]\(([^)]+)\)")


def slugify(text: str) -> str:
    """
    GitHub's heading anchor, which is also what the site is configured to emit.

    Lowercase, drop everything that is not alphanumeric, space or hyphen, then
    spaces to hyphens. Matching GitHub exactly is the point: it is what every
    existing `#anchor` in these files was written against.
    """
    text = re.sub(r"<[^>]+>", "", text)
    text = re.sub(r"`([^`]*)`", r"\1", text)
    text = re.sub(r"\[([^\]]*)\]\([^)]*\)", r"\1", text)
    text = text.strip().lower()
    text = re.sub(r"[^\w\s-]", "", text, flags=re.UNICODE)
    return re.sub(r"[\s_]+", "-", text).strip("-")


@dataclass
class Section:
    title: str
    anchor: str
    lines: list[str] = field(default_factory=list)
    #: `source anchor -> anchor on the page this section becomes`.
    #:
    #: The two differ whenever a title repeats. GitHub numbers duplicates
    #: across the whole file — three "Performance and limitations" headings
    #: become `…`, `…-1`, `…-2` — but once each `##` section is its own page,
    #: the copy on that page is the only one and is numbered from scratch. A
    #: link written against the file's numbering has to be translated, or it
    #: lands on a heading that no longer carries that name.
    anchors: dict[str, str] = field(default_factory=dict)


def outside_fences(lines: list[str]):
    """Yields `(index, line, in_fence)` so link rewriting can skip code."""
    fence: str | None = None
    for index, line in enumerate(lines):
        match = FENCE.match(line)
        if fence is None:
            if match:
                fence = match.group(2)[0] * 3
                yield index, line, True
                continue
        else:
            if match and match.group(2).startswith(fence):
                fence = None
            yield index, line, True
            continue
        yield index, line, fence is not None


class Numberer:
    """GitHub's duplicate-heading suffixes: `slug`, `slug-1`, `slug-2`."""

    def __init__(self) -> None:
        self._seen: dict[str, int] = {}

    def anchor(self, title: str) -> str:
        base = slugify(title)
        count = self._seen.get(base, 0)
        self._seen[base] = count + 1
        return base if count == 0 else f"{base}-{count}"


def split_sections(
    text: str, single_page: bool = False
) -> tuple[list[str], dict[str, str], list[Section]]:
    """
    The preamble before the first `##`, its anchor map, then one Section per `##`.

    Two numberings are computed at once: `source` follows the whole file, the
    way GitHub renders it and the way every existing link was written; `page`
    restarts wherever the content will be split, because that is what the site
    will actually emit.
    """
    lines = text.splitlines()
    preamble: list[str] = []
    preamble_anchors: dict[str, str] = {}
    sections: list[Section] = []
    current: Section | None = None

    source = Numberer()
    page = Numberer()

    for _, line, in_fence in outside_fences(lines):
        heading = None if in_fence else HEADING.match(line)
        if heading:
            level, title = len(heading.group(1)), heading.group(2)
            if level == 2:
                if not single_page:
                    page = Numberer()
                current = Section(title=title, anchor="")
                current.anchor = source.anchor(title)
                current.anchors[current.anchor] = page.anchor(title)
                sections.append(current)
                continue
            target = current.anchors if current is not None else preamble_anchors
            target[source.anchor(title)] = page.anchor(title)
            if current is None:
                preamble.append(line)
                continue
        (current.lines if current else preamble).append(line)

    return preamble, preamble_anchors, sections


def parse_contents(preamble: list[str]) -> list[tuple[str, list[str]]]:
    """
    The `## Contents` list, as `(group title, [anchors])`.

    These files already group their own sections for a human reader — "Cartesian
    charts", "Statistical", "Presentation" — so the navigation is built from
    that rather than from a second list this script would have to keep in step.
    """
    groups: list[tuple[str, list[str]]] = []
    for line in preamble:
        if not line.startswith("- "):
            continue
        body = line[2:]
        label = ""
        if ":" in body:
            head, rest = body.split(":", 1)
            if "](" not in head:
                label, body = head.strip(), rest
        anchors = [
            target[1:]
            for _, target in LINK.findall(body)
            if target.startswith("#")
        ]
        if anchors:
            groups.append((label, anchors))
    return groups


def illustration(book_id: str, slug: str, title: str) -> list[str]:
    """
    The picture of this chart, if one was captured, light and dark.

    Two files rather than one because the site follows the reader's system
    setting, and a chart drawn for a light page is unreadable on a dark one —
    the axis labels are the wrong colour, not merely dim. Material shows and
    hides them with `#only-light` / `#only-dark`.

    Written *after* the body is link-rewritten, so these paths are left exactly
    as they are: they point into the copied asset tree, not at a documentation
    page, and putting them through the rewriter would only give it something it
    has no business resolving.
    """
    if not ASSETS.is_dir():
        return []
    lines: list[str] = []
    for suffix, only in (("", "only-light"), ("-dark", "only-dark")):
        for extension in ("svg", "png"):
            candidate = ASSETS / book_id / f"{slug}{suffix}.{extension}"
            if candidate.exists():
                lines.append(
                    f"![{title}](../assets/{book_id}/{slug}{suffix}.{extension}#{only})"
                    "{ .chart-shot }"
                )
                break
    # Muted and looping, because a recording of a gesture is a demonstration
    # rather than a film: nobody wants to press play, and nobody wants sound.
    # `playsinline` keeps iOS from taking it fullscreen.
    #
    # `../../`, not `../`. MkDocs rewrites paths in Markdown links relative to
    # the *source* file, but passes raw HTML through untouched — and the
    # rendered page lives one directory deeper than its source, at
    # `chartkit/<slug>/index.html`. A path that is right in Markdown is off by
    # one level here, and silently: the browser reports a media error rather
    # than a missing file.
    #
    # One per scheme, and `preload="none"` on both: the hidden one is never
    # fetched, so a reader downloads the variant they can actually see rather
    # than both. Autoplay starts the visible one anyway.
    for suffix, variant in (("", "light"), ("-dark", "dark")):
        clip = ASSETS / "clips" / f"{slug}{suffix}.mp4"
        if clip.exists():
            lines += [
                f'<video class="chart-clip chart-clip--{variant}"'
                f' src="../../assets/clips/{slug}{suffix}.mp4"',
                '       autoplay loop muted playsinline preload="none"',
                f'       aria-label="A recording of {title.lower()} being used"></video>',
            ]
    return lines + [""] if lines else []


@dataclass
class Page:
    book: Book
    path: str
    title: str


def build() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", default="site-src")
    args = parser.parse_args()

    out = ROOT / args.out
    if out.exists():
        shutil.rmtree(out)
    out.mkdir(parents=True)

    # Pass one: read every book, split it, and record where each anchor lands.
    #
    # `homes` maps a link as it is written in the source — `(book, anchor)` —
    # to the page that now holds it and the anchor that page actually emits.
    homes: dict[tuple[str, str], tuple[str, str]] = {}
    book_pages: dict[str, list[tuple[Section, str]]] = {}
    book_preamble: dict[str, list[str]] = {}
    book_sections: dict[str, list[Section]] = {}

    book_contents: dict[str, list[str]] = {}

    for book in BOOKS:
        text = (ROOT / book.source).read_text(encoding="utf-8")
        preamble, preamble_anchors, sections = split_sections(text, book.single_page)

        # Taken from the *whole* file, before the book slicing below. A file
        # that holds two books — the root README is the ecosystem guide and the
        # FillKit manual — has one Contents list covering both, and each book
        # simply takes the entries that turn out to be its own.
        contents = next((x for x in sections if x.title == "Contents"), None)
        book_contents[book.id] = contents.lines if contents else []

        if book.starts_at:
            start_at = next(
                (i for i, x in enumerate(sections) if x.title == book.starts_at), None
            )
            if start_at is None:
                print(f"error: {book.id}: no section titled {book.starts_at!r}", file=sys.stderr)
                return 1
            preamble, preamble_anchors = [], {}
            sections = sections[start_at:]
        if book.ends_before:
            end_at = next(
                (i for i, x in enumerate(sections) if x.title == book.ends_before), None
            )
            if end_at is None:
                print(f"error: {book.id}: no section titled {book.ends_before!r}", file=sys.stderr)
                return 1
            sections = sections[:end_at]

        # The source H1 is replaced by the page's own title.
        while preamble and not preamble[0].strip():
            preamble.pop(0)
        if preamble and preamble[0].startswith("# "):
            preamble.pop(0)

        book_preamble[book.id] = preamble
        book_sections[book.id] = sections
        index_page = f"{book.id}/index.md"
        for source_anchor, page_anchor in preamble_anchors.items():
            homes.setdefault((book.id, source_anchor), (index_page, page_anchor))

        pages: list[tuple[Section, str]] = []
        used: set[str] = set()
        for section in sections:
            if book.single_page or section.title == "Contents":
                target = index_page
            else:
                slug = slugify(section.title) or "section"
                candidate, n = slug, 2
                while candidate in used:
                    candidate, n = f"{slug}-{n}", n + 1
                used.add(candidate)
                target = f"{book.id}/{candidate}.md"
                pages.append((section, target))
            for source_anchor, page_anchor in section.anchors.items():
                homes.setdefault((book.id, source_anchor), (target, page_anchor))
        book_pages[book.id] = pages

    unresolved: list[str] = []

    def resolve(book: Book, anchor: str) -> tuple[str, str] | None:
        home = homes.get((book.id, anchor))
        if home is not None:
            return home
        # A link into another book cut from the same file — the ecosystem
        # README links into the FillKit manual and back.
        for other in BOOKS:
            if other.source == book.source and other.id != book.id:
                home = homes.get((other.id, anchor))
                if home is not None:
                    return home
        return None

    def rewrite(lines: list[str], book: Book, page_path: str) -> list[str]:
        """Points every link at wherever its target ended up."""
        here = Path(page_path).parent

        def fix(match: re.Match) -> str:
            label, target = match.group(1), match.group(2)
            title = ""
            if " " in target and target.rstrip().endswith(('"', "'")):
                target, title = target.split(" ", 1)
                title = " " + title
            if re.match(r"^(https?:|mailto:|#!)", target):
                return match.group(0)

            if target.startswith("#"):
                home = resolve(book, target[1:])
                if home is None:
                    unresolved.append(f"{page_path}: {target}")
                    return match.group(0)
                page, anchor = home
                rel = os.path.relpath(page, here)
                return f"[{label}]({rel}#{anchor}{title})"

            base, _, anchor = target.partition("#")
            key = os.path.normpath(os.path.join(str(Path(book.source).parent), base))
            alias = LINK_ALIASES.get(key) or LINK_ALIASES.get(base)
            if alias is None:
                # Anything else relative is a file in the repository that is not
                # part of this site — a Kotlin source file, NOTICE, a script.
                # Those become links to the repository, because a docs site that
                # silently swallowed them would give the reader a 404 where the
                # README gave them the code.
                if key.startswith("..") or not (ROOT / key).exists():
                    unresolved.append(f"{page_path}: {target} (no such file)")
                    return match.group(0)
                url = f"{REPO_BLOB}/{key}"
                return f"[{label}]({url}#{anchor}{title})" if anchor else f"[{label}]({url}{title})"
            if alias.startswith("http"):
                return f"[{label}]({alias}{title})"
            if anchor:
                owner = next((b for b in BOOKS if alias.startswith(b.id + "/")), None)
                home = resolve(owner, anchor) if owner else None
                if home is not None:
                    page, page_anchor = home
                    rel = os.path.relpath(page, here)
                    return f"[{label}]({rel}#{page_anchor}{title})"
                unresolved.append(f"{page_path}: {target}")
                return match.group(0)
            rel = os.path.relpath(alias, here)
            return f"[{label}]({rel}{title})"

        result = []
        for _, line, in_fence in outside_fences(lines):
            result.append(line if in_fence else LINK.sub(fix, line))
        return result

    # Pass two: write the pages.
    written = 0
    for book in BOOKS:
        (out / book.id).mkdir(parents=True, exist_ok=True)
        index_page = f"{book.id}/index.md"

        body = [f"# {book.title}", "", book.tagline + ".", ""]
        body += rewrite(book_preamble[book.id], book, index_page)
        if book.single_page:
            for section in book_sections[book.id]:
                body += ["", f"## {section.title}", ""]
                body += rewrite(section.lines, book, index_page)
        (out / index_page).write_text("\n".join(body).rstrip() + "\n", encoding="utf-8")
        written += 1

        for section, target in book_pages[book.id]:
            slug = Path(target).stem
            page = [f"# {section.title}", ""]
            page += illustration(book.id, slug, section.title)
            page += rewrite(section.lines, book, target)
            (out / target).write_text("\n".join(page).rstrip() + "\n", encoding="utf-8")
            written += 1

    if unresolved:
        print(
            f"error: {len(unresolved)} link(s) point at a heading that does not exist:",
            file=sys.stderr,
        )
        for item in sorted(set(unresolved))[:25]:
            print(f"  {item}", file=sys.stderr)
        return 1

    # Pass three: the navigation, grouped the way each file groups itself.
    summary = ["# Navigation", "", "* [Home](index.md)"]
    for book in BOOKS:
        summary.append(f"* [{book.title}]({book.id}/index.md)")
        if book.single_page:
            continue
        by_anchor = {s.anchor: (s, t) for s, t in book_pages[book.id]}
        placed: set[str] = set()
        groups = parse_contents(book_contents[book.id])
        for label, anchors in groups:
            items = [by_anchor[a] for a in anchors if a in by_anchor and a not in placed]
            if not items:
                continue
            indent = "    "
            if label:
                summary.append(f"{indent}* {label}")
                indent += "    "
            for section, target in items:
                placed.add(section.anchor)
                summary.append(f"{indent}* [{section.title}]({target})")
        rest = [(s, t) for s, t in book_pages[book.id] if s.anchor not in placed]
        if rest:
            indent = "    "
            if groups:
                summary.append("    * More")
                indent = "        "
            for section, target in rest:
                summary.append(f"{indent}* [{section.title}]({target})")
    (out / "SUMMARY.md").write_text("\n".join(summary) + "\n", encoding="utf-8")

    if ASSETS.is_dir():
        shutil.copytree(ASSETS, out / "assets", dirs_exist_ok=True)

    # A stylesheet that is copied but never referenced is worse than one that is
    # missing: the site builds, every link resolves, every check passes, and the
    # pages are simply unstyled. That shipped once — `extra_css` was added to
    # `mkdocs.yml` and then lost to a `git reset` because only the stylesheet
    # itself had been staged. Nothing downstream noticed.
    # A picture named after no page is a picture nobody sees. Two were captured
    # for `###` subsections — `world-map`, `choropleth-map` — which are not
    # pages, so they were copied into the site, served, and shown nowhere. The
    # capture ran, the build passed, and the pages they were meant for stayed
    # blank.
    known = {p.stem for book in BOOKS for p in (out / book.id).glob("*.md")}
    orphans = sorted(
        {p.stem.removesuffix("-dark") for p in (ASSETS / "chartkit").glob("*")}
        - known
    ) if (ASSETS / "chartkit").is_dir() else []
    if orphans:
        print(
            "error: captured pictures with no page to appear on: "
            + ", ".join(orphans),
            file=sys.stderr,
        )
        return 1

    config = (ROOT / "mkdocs.yml").read_text(encoding="utf-8")
    for sheet in sorted((ROOT / "docs-src").glob("stylesheets/*.css")):
        reference = f"stylesheets/{sheet.name}"
        if reference not in config:
            print(
                f"error: docs-src/{reference} exists but mkdocs.yml does not list it "
                f"under `extra_css`, so the site would build unstyled",
                file=sys.stderr,
            )
            return 1

    for extra in sorted((ROOT / "docs-src").iterdir()):
        if extra.is_file():
            shutil.copyfile(extra, out / extra.name)
        else:
            shutil.copytree(extra, out / extra.name, dirs_exist_ok=True)

    print(f"built {written + 1} pages into {out.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(build())
