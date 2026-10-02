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

These lists are an extra beside the store's own index, which is built first and published
in the same run. Nothing that goes wrong here may cost that its update, so whatever cannot
be read keeps the entry it had.

Only the standard library is used.
"""

import argparse
import calendar
import hashlib
import json
import os
import re
import sys
import time
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
# How a license is known: by its terms, not by its name, which any text can mention. Each
# row gives the words the terms begin and end with and, for every wording in circulation,
# the SHA-256 of what lies between, cut to 32 digits. Texts are compared with everything
# but their letters taken out, so layout, numbering and markup make no difference and a
# single changed word does.
LICENSE_TEXTS = (
    ("everyoneispermittedtocopyanddistributeverbatimcopies",
     "endoftermsandconditions", {
         "76023b3fc2840303845d28ef1641a111": "AGPL-3.0",
         "e83efed7564938ec1591616392dc5f38": "GPL-3.0",
         "3e3fef102b1eebb29ea43347bb3ca1cf": "GPL-2.0",
         "8ff23fdf9e00e8790733c71d09255d4e": "GPL-2.0",
         "3d8643281fa1fabe2a138eb6ef79d592": "GPL-2.0",
         "659e09f4db02f6e8f0b7b4d2cc76d767": "LGPL-2.1",
     }),
    ("everyoneispermittedtocopyanddistributeverbatimcopies",
     "tochoosethatversionforthelibrary", {
         "48be380478a9b22cd8ce668e24f63cd9": "LGPL-3.0",
     }),
    ("termsandconditionsforusereproductionanddistribution",
     "endoftermsandconditions", {
         "64742245c13bd2d2e39e40db46aaaac5": "Apache-2.0",
     }),
    ("mozillapubliclicenseversiondefinitions",
     "asdefinedbythemozillapubliclicensev", {
         "a8a9bc31fa1fb45b4589fa6446c90c27": "MPL-2.0",
     }),
    ("europeanunionpubliclicencev",
     "requiretheproductionofaneweuplversion", {
         "48fd181a2a1f6f8fa09df9b6d3f22bab": "EUPL-1.2",
     }),
    ("permissionisherebygrantedfreeofchargetoanypersonobtainingacopy",
     "orotherdealingsinthesoftware", {
         "970a18fea597db0b3acc4d40c788a5f3": "MIT",
     }),
    ("permissiontousecopymodifyandordistributethissoftware",
     "useorperformanceofthissoftware", {
         "0cff2796014693e8691e94eccd6f9b92": "ISC",
     }),
    ("redistributionanduseinsourceandbinaryforms",
     "ifadvisedofthepossibilityofsuchdamage", {
         "a41fa61f21eaa27d785e413c26be9cac": "BSD-3-Clause",
         "b5338cb0db9bedf1134d3e5fb6552ce9": "BSD-2-Clause",
     }),
    ("thisisfreeandunencumberedsoftwarereleasedintothepublicdomain",
     "formoreinformationpleaserefertohttpsunlicenseorg", {
         "1506f05d8a52455479d253ae494efbb3": "Unlicense",
     }),
    ("thisisfreeandunencumberedsoftwarereleasedintothepublicdomain",
     "formoreinformationpleaserefertohttpunlicenseorg", {
         "f7e5acc9ebdbd10d9fe369a7fbd698de": "Unlicense",
     }),
    ("creativecommonslegalcode",
     "withrespecttothisccoruseofthework", {
         "6d4f8225d25cd68d7a806427f72ad55b": "CC0-1.0",
     }),
    ("statementofpurpose",
     "withrespecttothisccoruseofthework", {
         "102363731e9b0bc4a0330bc55cf09e43": "CC0-1.0",
     }),
)
# Where the terms of a license name somebody: who provides the software, who is not liable
# and whose name may not be used to promote things. The terms are the same whoever is named,
# so the names are taken out before the terms are compared.
LICENSE_NAMED = re.compile(
    r"(?<=neitherthenameof)[a-z]{1,120}?(?=northenamesofitscontributors)"
    r"|(?<=softwareisprovidedby)[a-z]{1,120}?(?=asisandanyexpress)"
    r"|(?<=providedasisand)[a-z]{1,120}?(?=disclaimsallwarranties)"
    r"|(?<=innoeventshall)[a-z]{1,120}?(?=beliablefor)"
)
# The words that close the terms of the longer licenses. What a file says after them is not
# part of the terms: that is where a license tells how to apply it, and where projects put
# their own notices.
LICENSE_CLOSED = "endoftermsandconditions"
# How many letters may come before the terms.
LICENSE_LEAD = 1000
# The words a title of a license is made of. Before the terms, and after terms that are not
# closed as above, a file may hold only titles and lines that say whose copyright it is.
# Anything else there may be a condition of its own, and the file then is no license the
# store knows.
LICENSE_TITLE = frozenset("""
    the mit isc bsd apache gnu affero lesser library general public license licence licenses
    version clause new revised simplified modified or mozilla european union eupl creative
    commons legal code cc universal unlicense zero v x expat style spdx identifier january
    february june november http https www org fsf software free foundation inc and authors
    contributors all rights reserved franklin street fifth floor temple place suite mass ave
    boston cambridge ma usa
""".split())
# What tells that words outside the terms take back what the terms grant.
LICENSE_LIMITS = ("commonsclause", "noncommercial", "notforcommercial", "notlicensed", "notbesold",
                  "additionalrestriction", "personaluseonly")


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


def license_texts(letters):
    """(start, end, name) of every license whose terms are in letters word for word, in the
    order they come."""
    found = []
    for begins, ends, known in LICENSE_TEXTS:
        start = letters.find(begins)
        while start >= 0:
            end = letters.find(ends, start)
            if end < 0:
                break
            end += len(ends)
            terms = letters[start:end]
            name = known.get(hashlib.sha256(LICENSE_NAMED.sub("", terms).encode()).hexdigest()[:32])
            named = "".join(LICENSE_NAMED.findall(terms))
            if name and not any(limit in named for limit in LICENSE_LIMITS):
                found.append((start, end, name))
            start = letters.find(begins, start + 1)
    return sorted(found)


def says_nothing(piece):
    """Whether a piece of a license file has nothing of its own to say: every line of it is
    a title or tells whose copyright the work is."""
    for line in piece.splitlines():
        line = line.strip(" \t#*=_>|`~-").lower()
        if re.match(r"(copyright\b|\(c\)|\u00a9)", line):
            continue
        if not set(re.findall(r"[a-z]+", line)) <= LICENSE_TITLE:
            return False
    return True


def identify_license(text):
    """The SPDX name of the license whose text this is, or None.

    The terms have to be there in full and unchanged, with nothing before them but a title
    and whose copyright the work is. A file that only mentions a license, changes its terms
    or adds conditions of its own is no license the store knows. After terms that end with
    "END OF TERMS AND CONDITIONS" a file may go on, as GitHub has it too; after any others
    only further licenses may follow, those of what the project includes."""
    lowered = text.lower()
    where = [index for index, character in enumerate(lowered) if "a" <= character <= "z"]
    letters = "".join(lowered[index] for index in where)
    found = license_texts(letters)
    if not found or found[0][0] > LICENSE_LEAD:
        return None
    position = 0
    for start, end, _ in found:
        if start < position:
            continue
        gap = text[where[position - 1] + 1 if position else 0:where[start]]
        if not says_nothing(gap) or any(limit in letters[position:start] for limit in LICENSE_LIMITS):
            return None
        position = end
        if letters.endswith(LICENSE_CLOSED, 0, end):
            # Closed terms: what follows is the file's own, as long as it takes nothing back.
            # The terms of further licenses are not the file's own words.
            for later_start, later_end, _ in found + [(len(letters), len(letters), None)]:
                if later_start < position:
                    continue
                if any(limit in letters[position:later_start] for limit in LICENSE_LIMITS):
                    return None
                position = later_end
            return found[0][2]
    return found[0][2] if says_nothing(text[where[position - 1] + 1:]) else None


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
    def describe(repo):
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


def remote_file(url):
    """(size, mark) of the file at url, asked with a request for its first byte. The mark is
    what the server gives for telling one version of the file from another, or "" when it
    gives nothing. The answer is judged by its headers alone: a server that takes no notice
    of the range sends the whole file, which is not read."""
    request = urllib.request.Request(url, headers={
        "Range": "bytes=0-0", "Accept-Encoding": "identity", "User-Agent": USER_AGENT,
    })
    with urllib.request.urlopen(request, timeout=30) as response:
        total = (response.headers.get("Content-Range") or "").rpartition("/")[2]
        if response.status != 206 or not total.isdigit():
            raise OSError("size not told")
        mark = response.headers.get("ETag") or response.headers.get("Last-Modified") or ""
    return int(total), mark


# The addresses at which GitLab hands out a file it keeps itself: a package of a project, a
# file uploaded to it, a file of a repository and what a pipeline built. From these GitLab
# passes a request on only to where it stores its files. Everything else is left out, among
# it the addresses that stand for a release link and pass the request on to wherever that
# link points.
GITLAB_FILES = re.compile(
    r"/api/v4/projects/[^/]+/packages/generic/[^/]+/[^/]+/.+"
    r"|/-/project/\d+/uploads/[0-9a-f]{32}/[^/]+"
    r"|/(?!api/)(?:(?!-/)[^/]+/)+(?:"
    r"uploads/[0-9a-f]{32}/[^/]+"
    r"|-/raw/.+"
    r"|-/package_files/\d+/download"
    r"|-/jobs/(?:\d+/artifacts/raw|artifacts/[^/]+/raw)/.+"
    r")"
)


def apk_link(link):
    """(name, address) of a release link that is an APK kept on GitLab itself, or None. A
    link can point anywhere; a file on another site is that site's to offer.

    What counts is where the link itself points, not the address GitLab gives for
    downloading it directly, which is on GitLab whatever the link points at. And the link
    has to point at one of the places GitLab keeps files in; see GITLAB_FILES."""
    url = link.get("url") or ""
    parsed = urllib.parse.urlsplit(url)
    if parsed.scheme != "https" or parsed.netloc != urllib.parse.urlsplit(GITLAB_HOST).netloc:
        return None
    path = urllib.parse.unquote(parsed.path)
    if not GITLAB_FILES.fullmatch(path) or any(part in ("", ".", "..") for part in path.split("/")[1:]):
        return None
    name = link.get("name") or ""
    if not name.lower().endswith(".apk"):
        name = path.rsplit("/", 1)[-1]
    return (name, url) if name.lower().endswith(".apk") else None


def file_id(link_id, url, size, mark):
    """A number that stands for the file behind a release link. A link keeps its own id when
    it is pointed at another file, and an address can come to hold another file, so the
    address, the size and the server's mark for the file go into the number too: what was
    read from a file is then used again only while the link still leads to that file."""
    described = "%s\n%s\n%d\n%s" % (link_id, url, size, mark)
    return int(hashlib.sha256(described.encode()).hexdigest()[:13], 16)


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
    def describe(repo):
        detail = gitlab("/projects/%d?license=true" % repo["id"])
        license_name = gitlab_license((detail.get("license") or {}).get("key"))
        if license_name is None or detail.get("forked_from_project"):
            return None
        return {
            "full_name": "%s:%s" % (GITLAB, repo["path_with_namespace"]),
            "project_id": repo["id"],
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
        has no prereleases; a release dated in the future is treated as a draft, and its
        files, which may not be there yet, are not looked at. Only the newest release with
        APKs has their sizes asked, which is all that gets listed."""
        releases = gitlab("/projects/%d/releases?per_page=%d" % (repo["project_id"], GITLAB_RELEASES))
        result = []
        sized = False
        for release in releases:
            assets = []
            draft = bool(release.get("upcoming_release"))
            links = [] if sized or draft else (release.get("assets") or {}).get("links") or []
            for link in links:
                apk = apk_link(link)
                if apk is None:
                    continue
                size, mark = remote_file(apk[1])
                # Without a mark nothing tells whether the file is still the same one, so
                # it is taken for a new one every time and read again.
                mark = mark or "asked %f" % time.time()
                assets.append({"id": file_id(link["id"], apk[1], size, mark), "name": apk[0],
                               "browser_download_url": apk[1], "size": size, "download_count": 0})
            sized = sized or bool(assets)
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
        described = place.describe(repo)
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
        except Exception as error:  # pylint: disable=broad-except
            # Keep what was known rather than dropping an app over a passing error. An app
            # found by searching is kept only as long as its release is recent, as always.
            print("%s: %r" % (key, error), file=sys.stderr)
            app = before
            if found and app and not build_index.recent_enough(app, today):
                app = None
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
    except Exception as error:  # pylint: disable=broad-except
        print("%s: published apps unavailable: %r" % (place.source, error), file=sys.stderr)
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
    except Exception as error:  # pylint: disable=broad-except
        print("%s: search unavailable: %r" % (place.source, error), file=sys.stderr)
        published = {"%s:%s" % (place.source, name) for name in taken}
        found = [
            app for name, app in previous.items()
            if name not in published and build_index.recent_enough(app, today)
        ]
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
