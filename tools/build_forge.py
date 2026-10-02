#!/usr/bin/env python3
"""Builds the lists of apps released on Codeberg.

Codeberg runs Forgejo, whose releases work much like GitHub's, so a repository there is read
the same way: its newest release with an APK attached is the app. Two files are written. One
holds the apps their developers have published to the store with the store topic, which every
user sees. The other holds apps found by searching Codeberg, which the app downloads only
when the user has asked for them.

Forgejo does not say which license a repository has, so it is recognised from the license
file. A repository whose license cannot be recognised is not listed, as on GitHub.

Only the standard library is used.
"""

import argparse
import calendar
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

import build_index
from build_index import write_json

HOST = "https://codeberg.org"
SOURCE = "codeberg"
USER_AGENT = "ARK-Store-index"
PAGE_SIZE = 50
MAX_PAGES = 20
MAX_LICENSE = 256 * 1024
# The searches that find apps nobody published to the store. Codeberg is far smaller than
# GitHub and its repositories have fewer stars, so less is asked of them here.
FOUND_QUERIES = ("android", "android-app")
FOUND_MIN_STARS = 5

LICENSE_FILE = re.compile(r"^(licen[cs]e|copying|unlicense)(\.(md|txt|rst))?$", re.IGNORECASE)
# What tells a license apart, looked for in the first part of its text in this order: the
# licenses that quote another one's name come before the one they quote.
LICENSE_MARKS = (
    ("AGPL-3.0", ("gnu affero general public license", "version 3")),
    ("LGPL-3.0", ("gnu lesser general public license", "version 3")),
    ("LGPL-2.1", ("gnu lesser general public license", "version 2.1")),
    ("GPL-3.0", ("gnu general public license", "version 3")),
    ("GPL-2.0", ("gnu general public license", "version 2")),
    ("Apache-2.0", ("apache license", "version 2.0")),
    ("MPL-2.0", ("mozilla public license", "2.0")),
    ("EUPL-1.2", ("european union public licence", "1.2")),
    ("MIT", ("permission is hereby granted, free of charge", "the software is provided \"as is\"")),
    ("ISC", ("permission to use, copy, modify, and/or distribute this software",)),
    ("BSD-3-Clause", ("redistribution and use in source and binary forms", "neither the name")),
    ("BSD-2-Clause", ("redistribution and use in source and binary forms",)),
    ("Unlicense", ("this is free and unencumbered software released into the public domain",)),
    ("CC0-1.0", ("cc0 1.0 universal",)),
)


def forge(path, raw=False, limit=None):
    request = urllib.request.Request(HOST + "/api/v1" + path, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=30) as response:
        if raw:
            return response.read(limit)
        return json.load(response)


def to_utc(text):
    """A Forgejo timestamp, which carries a time zone, the way GitHub writes one; "" when it
    is missing or odd."""
    match = re.match(r"^(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d)(?:\.\d+)?(Z|[+-]\d\d:\d\d)$", text or "")
    if not match:
        return ""
    seconds = calendar.timegm(time.strptime(match.group(1), "%Y-%m-%dT%H:%M:%S"))
    zone = match.group(2)
    if zone != "Z":
        offset = int(zone[1:3]) * 3600 + int(zone[4:6]) * 60
        seconds -= offset if zone[0] == "+" else -offset
    return time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(seconds))


def identify_license(text):
    """The SPDX name of the license whose text this is, or None."""
    start = " ".join(text[:4000].lower().split())
    for name, marks in LICENSE_MARKS:
        if all(mark in start for mark in marks):
            return name
    return None


def detect_license(name):
    """The license of the repository name, read from the license file in its root."""
    files = [
        entry["name"] for entry in forge("/repos/%s/contents" % name)
        if entry.get("type") == "file" and LICENSE_FILE.match(entry.get("name") or "")
    ]
    if not files:
        return None
    # "LICENSE" before "COPYING", and the plain name before one with an ending.
    files.sort(key=lambda file: (not file.lower().startswith("licen"), len(file)))
    data = forge("/repos/%s/raw/%s" % (name, urllib.parse.quote(files[0])), raw=True, limit=MAX_LICENSE)
    return identify_license(data.decode("utf-8", "replace"))


def search(query):
    """The repositories that have query among their topics, most starred first."""
    found = []
    for page in range(1, MAX_PAGES + 1):
        result = forge("/repos/search?q=%s&topic=true&sort=stars&order=desc&limit=%d&page=%d"
                       % (urllib.parse.quote(query), PAGE_SIZE, page))
        items = result.get("data") or []
        found.extend(items)
        if len(items) < PAGE_SIZE:
            break
    return found


def usable(repo):
    return not (repo.get("private") or repo.get("fork") or repo.get("archived")
                or repo.get("mirror") or repo.get("empty"))


def as_repo(repo, license_name):
    """A Forgejo repository described the way GitHub describes one."""
    return {
        "full_name": "%s:%s" % (SOURCE, repo["full_name"]),
        "forge_name": repo["full_name"],
        "html_url": repo["html_url"],
        "description": repo.get("description") or "",
        "stargazers_count": repo.get("stars_count", 0),
        "topics": repo.get("topics") or [],
        "license": {"spdx_id": license_name},
        "pushed_at": to_utc(repo.get("updated_at")),
    }


def list_releases(repo):
    """The releases of a repository, newest first, the way GitHub describes them."""
    releases = forge("/repos/%s/releases?limit=%d" % (repo["forge_name"], PAGE_SIZE))
    return [{
        "tag_name": release["tag_name"],
        "name": release.get("name"),
        "body": release.get("body"),
        "html_url": release.get("html_url"),
        "published_at": to_utc(release.get("published_at")),
        "draft": release.get("draft"),
        "prerelease": release.get("prerelease"),
        "assets": [{
            "id": asset["id"],
            "name": asset["name"],
            "browser_download_url": asset["browser_download_url"],
            "size": asset["size"],
            "download_count": asset.get("download_count", 0),
        } for asset in release.get("assets") or []],
    } for release in releases]


def examine(repo, previous, today, found):
    """The entry of one repository, or None when it has nothing to list. previous is its
    entry from the last run: one that is still fresh is kept without asking again, apart from
    the stars and topics, which the search has just told."""
    pushed_at = to_utc(repo.get("updated_at"))
    if previous and previous.get("pushedAt") == pushed_at \
            and today - previous.get("examinedAt", 0) < build_index.AUTO_RECHECK_HOURS * 3600:
        app = dict(previous, stars=repo.get("stars_count", 0), topics=repo.get("topics") or [])
    else:
        name = repo["full_name"]
        license_name = detect_license(name)
        if license_name is None:
            return None
        app = build_index.build_app(
            as_repo(repo, license_name), build_index.known_apks(previous),
            not_before=today - build_index.AUTO_RELEASE_DAYS * 86400 if found else None,
            with_prerelease=not found, list_releases=list_releases,
        )
        if app is None:
            return None
        app["source"] = SOURCE
        app["examinedAt"] = int(today)
        if found:
            app["releaseNotes"] = app["releaseNotes"][:build_index.AUTO_MAX_NOTES]
    if found:
        if not build_index.recent_enough(app, today):
            return None
        app = dict(app, topics=build_index.with_category(repo.get("topics") or [], repo.get("description")))
    return app


def build_list(repos, previous, today, found):
    apps = []
    for repo in repos:
        key = "%s:%s" % (SOURCE, repo["full_name"])
        before = previous.get(key)
        try:
            app = examine(repo, before, today, found)
        except (urllib.error.URLError, OSError, ValueError, KeyError, TypeError) as error:
            # Keep what was known rather than dropping an app over a passing error.
            print("%s: %s" % (key, error), file=sys.stderr)
            app = before
        if app:
            apps.append(app)
    apps.sort(key=lambda app: app["fullName"].lower())
    return apps


def load(path, keys):
    return build_index.load_previous(path, keys) or {}


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--previous-dir", help="where the files written by the previous run are")
    parser.add_argument("--output-dir", required=True)
    arguments = parser.parse_args()
    before = arguments.previous_dir or ""
    published_file = os.path.join(arguments.output_dir, "forge.json")
    found_file = os.path.join(arguments.output_dir, "codeberg.json")
    previous_published = load(os.path.join(before, "forge.json"), ("apps", "betaApps"))
    previous_found = load(os.path.join(before, "codeberg.json"), ("apps",))
    today = time.time()
    generated_at = int(today * 1000)

    try:
        published_repos = [
            repo for repo in search(build_index.TOPIC)
            if usable(repo) and build_index.TOPIC in (repo.get("topics") or [])
        ]
        published = build_list(published_repos, previous_published, today, found=False)
    except (urllib.error.URLError, OSError, ValueError, KeyError, TypeError) as error:
        print("codeberg: published apps unavailable: %s" % error, file=sys.stderr)
        published_repos = []
        published = sorted(previous_published.values(), key=lambda app: app["fullName"].lower())
    write_json(published_file, {
        "version": 1,
        "generatedAt": generated_at,
        "apps": [app for app in published if not app.get("betaOnly")],
        "betaApps": [app for app in published if app.get("betaOnly")],
    }, ensure_ascii=False)

    try:
        since = today - build_index.AUTO_PUSHED_DAYS * 86400
        candidates = {}
        for query in FOUND_QUERIES:
            for repo in search(query):
                candidates.setdefault(repo["full_name"], repo)
        taken = {repo["full_name"] for repo in published_repos}
        repos = [
            repo for repo in candidates.values()
            if usable(repo)
            and repo["full_name"] not in taken
            and build_index.TOPIC not in (repo.get("topics") or [])
            and repo.get("stars_count", 0) >= FOUND_MIN_STARS
            and (build_index.parse_time(to_utc(repo.get("updated_at"))) or 0) >= since
        ]
        repos.sort(key=lambda repo: -repo.get("stars_count", 0))
        found = build_list(repos, previous_found, today, found=True)
    except (urllib.error.URLError, OSError, ValueError, KeyError, TypeError) as error:
        print("codeberg: search unavailable: %s" % error, file=sys.stderr)
        found = [app for app in previous_found.values() if build_index.recent_enough(app, today)]
        found.sort(key=lambda app: app["fullName"].lower())
    write_json(found_file, {
        "version": 1,
        "source": SOURCE,
        "generatedAt": generated_at,
        "apps": found,
    }, ensure_ascii=False)
    print("codeberg: %d published, %d found" % (len(published), len(found)))


if __name__ == "__main__":
    main()
