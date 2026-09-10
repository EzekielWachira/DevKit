#!/usr/bin/env python3
"""
Checks every internal link and anchor in the built documentation site.

`mkdocs build --strict` already fails on a link to a page that does not exist.
It does not check **anchors**, and anchors are exactly what
`scripts/build_docs.py` rewrites: the READMEs are full of in-page links like
`[Scales](#scales)` which have to be repointed at whichever page now holds that
heading, and a repeated heading is numbered differently once its section becomes
a page of its own.

So the interesting failure — a link that lands on a real page at a heading that
is not there — is invisible to the strict build. This closes that gap.

Usage:  python3 scripts/check_docs_links.py [--site site]
"""

from __future__ import annotations

import argparse
import os
import re
import sys
from pathlib import Path

ANCHOR_ID = re.compile(r'id="([^"]+)"')
HREF = re.compile(r'href="([^"]+)"')
ARTICLE = re.compile(r"<article[^>]*>(.*?)</article>", re.S)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--site", default="site")
    args = parser.parse_args()

    site = Path(args.site)
    if not site.is_dir():
        print(f"error: no built site at {site} — run mkdocs build first", file=sys.stderr)
        return 1

    pages: dict[str, tuple[set[str], str]] = {}
    for path in site.rglob("index.html"):
        html = path.read_text(encoding="utf-8", errors="ignore")
        key = path.parent.relative_to(site).as_posix() or "."
        pages[key] = (set(ANCHOR_ID.findall(html)), html)

    if not pages:
        print(f"error: {site} contains no pages", file=sys.stderr)
        return 1

    broken: list[str] = []
    checked = 0

    for key, (_, html) in pages.items():
        # Only the article body. The theme's own chrome — navigation, the table
        # of contents, the footer — links to pages this check has no business
        # second-guessing, and includes anchors the theme generates itself.
        body = ARTICLE.search(html)
        scope = body.group(1) if body else html

        for href in HREF.findall(scope):
            if href.startswith(("http://", "https://", "mailto:", "data:")):
                continue
            target, _, anchor = href.partition("#")
            checked += 1

            page = key if target in ("", "./") else os.path.normpath(
                os.path.join(key, target)
            ).strip("/")
            if page not in pages:
                broken.append(f'{key}: {href} → no such page "{page}"')
                continue
            if anchor and anchor not in pages[page][0]:
                broken.append(f'{key}: {href} → no anchor "#{anchor}" on {page}')

    print(f"checked {checked} internal links across {len(pages)} pages")
    if broken:
        print(f"error: {len(broken)} broken link(s):", file=sys.stderr)
        for item in sorted(set(broken))[:40]:
            print(f"  {item}", file=sys.stderr)
        return 1
    print("all internal links and anchors resolve")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
