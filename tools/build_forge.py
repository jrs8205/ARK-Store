#!/usr/bin/env python3
"""Builds the lists of apps released on Codeberg and on GitLab.

Releases there work much like GitHub's, so a repository is read the same way: its newest
release with an APK attached is the app. One file holds the apps their developers have
published to the store with the store topic, which every user sees. Each place also gets a
file of the apps found by searching it, which the app downloads only when the user has asked
for them.

Codeberg runs Forgejo, which does not say which license a repository has, so it is
recognised from the license file. GitLab tells the license but not the size of a release
file, which is asked from the file itself. A repository whose license cannot be recognised
is not listed, as on GitHub.

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
GITLAB_HOST = "https://gitlab.com"
GITLAB = "gitlab"
GITLAB_PAGE_SIZE = 100
GITLAB_RELEASES = 20
USER_AGENT = "ARK-Store-index"
PAGE_SIZE = 50
MAX_PAGES = 20
MAX_LICENSE = 256 * 1024
# The searches that find apps nobody published to the store. These places are far smaller
# than GitHub and their repositories have fewer stars, so less is asked of them here.
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


# The licenses GitLab recognises, by its own names for them, as SPDX names.
GITLAB_LICENSES = {
    "agpl-3.0": "AGPL-3.0", "apache-2.0": "Apache-2.0", "bsd-2-clause": "BSD-2-Clause",
    "bsd-3-clause": "BSD-3-Clause", "cc0-1.0": "CC0-1.0", "epl-2.0": "EPL-2.0",
    "eupl-1.2": "EUPL-1.2", "gpl-2.0": "GPL-2.0", "gpl-3.0": "GPL-3.0", "isc": "ISC",
    "lgpl-2.1": "LGPL-2.1", "lgpl-3.0": "LGPL-3.0", "mit": "MIT", "mpl-2.0": "MPL-2.0",
    "unlicense": "Unlicense",
}


def gitlab_license(key):
    """The SPDX name of a license as GitLab names it, or None when it is not one the store
    lists. "or later" and "only" variants count as the license itself."""
    name = (key or "").lower()
    for ending in ("+", "-or-later", "-only"):
        if name.endswith(ending):
            name = name[:-len(ending)]
    return GITLAB_LICENSES.get(name)


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


class Codeberg:
    """How Codeberg's repositories are read."""
    source = SOURCE

    @staticmethod
    def search(query, since):
        return search(query)

    @staticmethod
    def usable(repo):
        return usable(repo)

    @staticmethod
    def name(repo):
        return repo["full_name"]

    @staticmethod
    def stars(repo):
        return repo.get("stars_count", 0)

    @staticmethod
    def pushed_at(repo):
        return to_utc(repo.get("updated_at"))

    @staticmethod
    def describe(repo, previous):
        """The repository the way GitHub describes one, or None when it has no license the
        store recognises."""
        license_name = detect_license(repo["full_name"])
        return as_repo(repo, license_name) if license_name else None

    @staticmethod
    def list_releases(repo):
        return list_releases(repo)


def gitlab(path):
    request = urllib.request.Request(GITLAB_HOST + "/api/v4" + path, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.load(response)


def remote_size(url):
    """The size of the file at url, asked with a request for its first byte."""
    request = urllib.request.Request(url, headers={
        "Range": "bytes=0-0", "Accept-Encoding": "identity", "User-Agent": USER_AGENT,
    })
    with urllib.request.urlopen(request, timeout=30) as response:
        response.read()
        total = (response.headers.get("Content-Range") or "").rpartition("/")[2]
    if response.status != 206 or not total.isdigit():
        raise OSError("size not told")
    return int(total)


def apk_link(link):
    """(name, address) of a release link that is an APK kept on GitLab itself, or None. A
    link can point anywhere; a file on another site is that site's to offer."""
    url = link.get("direct_asset_url") or link.get("url") or ""
    parsed = urllib.parse.urlsplit(url)
    if parsed.scheme != "https" or parsed.netloc != urllib.parse.urlsplit(GITLAB_HOST).netloc:
        return None
    name = link.get("name") or ""
    if not name.lower().endswith(".apk"):
        name = urllib.parse.unquote(parsed.path.rsplit("/", 1)[-1])
    return (name, url) if name.lower().endswith(".apk") else None


class GitLab:
    """How GitLab's projects are read."""
    source = GITLAB

    @staticmethod
    def search(query, since):
        found = []
        activity = "&last_activity_after=" + urllib.parse.quote(since) if since else ""
        for page in range(1, MAX_PAGES + 1):
            items = gitlab("/projects?topic=%s&order_by=star_count&sort=desc&archived=false"
                           "&visibility=public&per_page=%d&page=%d%s"
                           % (urllib.parse.quote(query), GITLAB_PAGE_SIZE, page, activity))
            found.extend(items)
            if len(items) < GITLAB_PAGE_SIZE:
                break
        return found

    @staticmethod
    def usable(repo):
        return not (repo.get("archived") or repo.get("forked_from_project") or repo.get("mirror")
                    or repo.get("empty_repo") or repo.get("visibility", "public") != "public")

    @staticmethod
    def name(repo):
        return repo["path_with_namespace"]

    @staticmethod
    def stars(repo):
        return repo.get("star_count", 0)

    @staticmethod
    def pushed_at(repo):
        return to_utc(repo.get("last_activity_at"))

    @staticmethod
    def describe(repo, previous):
        detail = gitlab("/projects/%d?license=true" % repo["id"])
        license_name = gitlab_license((detail.get("license") or {}).get("key"))
        if license_name is None or detail.get("forked_from_project"):
            return None
        return {
            "full_name": "%s:%s" % (GITLAB, repo["path_with_namespace"]),
            "project_id": repo["id"],
            # The size of a file already listed is not asked again.
            "known_sizes": {apk["id"]: apk["size"] for apk in build_index.known_apks(previous).values()},
            "html_url": repo["web_url"],
            "description": repo.get("description") or "",
            "stargazers_count": repo.get("star_count", 0),
            "topics": repo.get("topics") or [],
            "license": {"spdx_id": license_name},
            "pushed_at": to_utc(repo.get("last_activity_at")),
        }

    @staticmethod
    def list_releases(repo):
        """The releases of a project, newest first, the way GitHub describes them. GitLab
        has no prereleases; a release dated in the future is treated as a draft. Only the
        newest release with APKs has their sizes asked, which is all that gets listed."""
        releases = gitlab("/projects/%d/releases?per_page=%d" % (repo["project_id"], GITLAB_RELEASES))
        result = []
        sized = False
        for release in releases:
            assets = []
            links = [] if sized else (release.get("assets") or {}).get("links") or []
            for link in links:
                apk = apk_link(link)
                if apk is None:
                    continue
                size = repo["known_sizes"].get(link["id"]) or remote_size(apk[1])
                assets.append({"id": link["id"], "name": apk[0], "browser_download_url": apk[1],
                               "size": size, "download_count": 0})
            draft = bool(release.get("upcoming_release"))
            sized = sized or (bool(assets) and not draft)
            result.append({
                "tag_name": release["tag_name"],
                "name": release.get("name"),
                "body": release.get("description"),
                "html_url": (release.get("_links") or {}).get("self"),
                "published_at": to_utc(release.get("released_at")),
                "draft": draft,
                "prerelease": False,
                "assets": assets,
            })
        return result


PLACES = (Codeberg, GitLab)


def examine(repo, previous, today, found, place=Codeberg):
    """The entry of one repository, or None when it has nothing to list. previous is its
    entry from the last run: one that is still fresh is kept without asking again, apart from
    the stars and topics, which the search has just told."""
    pushed_at = place.pushed_at(repo)
    if previous and previous.get("pushedAt") == pushed_at \
            and today - previous.get("examinedAt", 0) < build_index.AUTO_RECHECK_HOURS * 3600:
        app = dict(previous, stars=place.stars(repo), topics=repo.get("topics") or [])
    else:
        described = place.describe(repo, previous)
        if described is None:
            return None
        app = build_index.build_app(
            described, build_index.known_apks(previous),
            not_before=today - build_index.AUTO_RELEASE_DAYS * 86400 if found else None,
            with_prerelease=not found, list_releases=place.list_releases,
        )
        if app is None:
            return None
        app["source"] = place.source
        app["examinedAt"] = int(today)
        if found:
            app["releaseNotes"] = app["releaseNotes"][:build_index.AUTO_MAX_NOTES]
    if found:
        if not build_index.recent_enough(app, today):
            return None
        app = dict(app, topics=build_index.with_category(repo.get("topics") or [], repo.get("description")))
    return app


def build_list(repos, previous, today, found, place=Codeberg):
    apps = []
    for repo in repos:
        key = "%s:%s" % (place.source, place.name(repo))
        before = previous.get(key)
        try:
            app = examine(repo, before, today, found, place)
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


def published_apps(place, previous, today):
    """(names, apps): the repositories of one place that carry the store topic, by name, and
    their entries. When the place cannot be searched, its previous entries stand."""
    prefix = place.source + ":"
    try:
        repos = [
            repo for repo in place.search(build_index.TOPIC, None)
            if place.usable(repo) and build_index.TOPIC in (repo.get("topics") or [])
        ]
        return {place.name(repo) for repo in repos}, build_list(repos, previous, today, False, place)
    except (urllib.error.URLError, OSError, ValueError, KeyError, TypeError) as error:
        print("%s: published apps unavailable: %s" % (place.source, error), file=sys.stderr)
        return set(), [app for name, app in previous.items() if name.startswith(prefix)]


def found_apps(place, taken, previous, today):
    """The apps found by searching one place, leaving out the repositories in taken, which
    their developers have published. When the place cannot be searched, the previous list
    stands as far as its releases are still recent."""
    try:
        since = today - build_index.AUTO_PUSHED_DAYS * 86400
        candidates = {}
        for query in FOUND_QUERIES:
            for repo in place.search(query, time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(since))):
                candidates.setdefault(place.name(repo), repo)
        repos = [
            repo for name, repo in candidates.items()
            if place.usable(repo)
            and name not in taken
            and build_index.TOPIC not in (repo.get("topics") or [])
            and place.stars(repo) >= FOUND_MIN_STARS
            and (build_index.parse_time(place.pushed_at(repo)) or 0) >= since
        ]
        repos.sort(key=lambda repo: -place.stars(repo))
        return build_list(repos, previous, today, True, place)
    except (urllib.error.URLError, OSError, ValueError, KeyError, TypeError) as error:
        print("%s: search unavailable: %s" % (place.source, error), file=sys.stderr)
        found = [app for app in previous.values() if build_index.recent_enough(app, today)]
        return sorted(found, key=lambda app: app["fullName"].lower())


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--previous-dir", help="where the files written by the previous run are")
    parser.add_argument("--output-dir", required=True)
    arguments = parser.parse_args()
    before = arguments.previous_dir or ""
    previous_published = load(os.path.join(before, "forge.json"), ("apps", "betaApps"))
    today = time.time()
    generated_at = int(today * 1000)

    published = []
    for place in PLACES:
        taken, apps = published_apps(place, previous_published, today)
        published += apps
        name = place.source + ".json"
        found = found_apps(place, taken, load(os.path.join(before, name), ("apps",)), today)
        write_json(os.path.join(arguments.output_dir, name), {
            "version": 1,
            "source": place.source,
            "generatedAt": generated_at,
            "apps": found,
        }, ensure_ascii=False)
        print("%s: %d published, %d found" % (place.source, len(apps), len(found)))

    published.sort(key=lambda app: app["fullName"].lower())
    write_json(os.path.join(arguments.output_dir, "forge.json"), {
        "version": 1,
        "generatedAt": generated_at,
        "apps": [app for app in published if not app.get("betaOnly")],
        "betaApps": [app for app in published if app.get("betaOnly")],
    }, ensure_ascii=False)


if __name__ == "__main__":
    main()
