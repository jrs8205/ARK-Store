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

The places have only so much time between them, and no request begins once a place's time
is up. What a run does not get to keeps its entry and waits for the next run, which begins
with what has waited longest. A repository that gave nothing to list, or that could not be
read, is remembered in the file of its place under "tried", beside the apps, so that the
runs after do not spend their time on it again.

Only the standard library is used.
"""

import argparse
import calendar
import contextlib
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
GITLAB_TREE_PAGE_SIZE = 100
TREE_PAGE_SIZE = 1000
# A list of files too long for this many pages is read as far as they go.
MAX_TREE_PAGES = 5
USER_AGENT = "ARK-Store-index"
PAGE_SIZE = 50
MAX_PAGES = 20
MAX_LICENSE = 256 * 1024
# The searches that find apps nobody published to the store. These places are far smaller
# than GitHub and their repositories have fewer stars, so less is asked of them here.
FOUND_QUERIES = ("android", "android-app")
FOUND_MIN_STARS = 5
# The time the lists may take between them. The step of the workflow that runs this is stopped
# at twelve minutes with nothing written, and the store's own index, built before this, is
# published only after it.
BUDGET_SECONDS = 6 * 60
# The part of a place's time its searches may take. The rest is for examining what they gave:
# a place that answers slowly is then searched as far as that goes, and what the search did
# not get to stands as it was.
SEARCH_SHARE = 0.5
# How long a request may wait for the server, and how much of a place's time has to be left
# for a request to begin at all.
REQUEST_SECONDS = 30
LEAST_SECONDS = 5
# How many hours a repository that could not be read waits for its next try, by how many
# tries in a row have failed: the next run tries once more, and after that the wait doubles
# up to a day. One that times out run after run would otherwise take its half minute of
# every run.
RETRY_HOURS = (0, 1, 2, 4, 8, 16, 24)
# The answers by which a place refuses the builder itself, not the one repository asked
# about: the token is not accepted, or too much has been asked. The place is then left alone
# for the rest of the run.
REFUSALS = (401, 429)
# How long what is remembered of a repository is kept when the searches no longer get to it.
TRIED_KEPT_DAYS = 7
clock = time.monotonic


class OutOfTime(Exception):
    """The time of the place being read is up. Whatever was being asked says nothing about
    the repository, which waits for another run."""


# The reading of the clock at which the place being read has to stop, or None when nothing
# limits it. See limited.
time_up = None


def time_left():
    """How many seconds a request that begins now may wait for the server: REQUEST_SECONDS,
    or what is left of the place's time when that is less. With less than LEAST_SECONDS left
    no request begins, and OutOfTime is raised."""
    if time_up is None:
        return REQUEST_SECONDS
    left = time_up - clock()
    if left < LEAST_SECONDS:
        raise OutOfTime()
    return min(REQUEST_SECONDS, left)


@contextlib.contextmanager
def limited(until):
    """While this lasts, every request made for a place is bound by time_left, with the clock
    reading until as the end of its time: the requests of this builder, and those the index
    builder makes when it reads an APK or a short description for it. None is no limit.

    What is bound is when a request may begin and how long it waits for the server each time
    it waits. A server that goes on sending an answer slowly, piece by piece, is not cut off;
    against that there is the limit of the workflow's step, and the files are written before
    any place gets a second turn."""
    global time_up  # pylint: disable=global-statement
    before = time_up, build_index.request_seconds
    time_up, build_index.request_seconds = until, time_left
    try:
        yield
    finally:
        time_up, build_index.request_seconds = before

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


def with_token(request, variable, header, prefix=""):
    """request with the token of the environment variable, when one is given, in the header
    that place reads it from. Both places answer the runners of GitHub, whose addresses many
    share, with "too many requests" much of the time; a request with a token is counted for
    the token's owner. The header does not go on with a redirect: an answer may send the
    request on to another host, which is not to have the token. Only the requests to a
    place's own API, at addresses made here, carry it: never one to an address a release
    link gave, which anyone can write and which the token would read on its owner's behalf."""
    token = os.environ.get(variable)
    if token:
        request.add_unredirected_header(header, prefix + token)
    return request


def forge(path, raw=False, limit=None):
    request = urllib.request.Request(HOST + "/api/v1" + path, headers={"User-Agent": USER_AGENT})
    with_token(request, "CODEBERG_TOKEN", "Authorization", "token ")
    with urllib.request.urlopen(request, timeout=time_left()) as response:
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
    # Everything below is read from the lowered text: lowering can make a letter longer, so
    # the indexes of where fit only this text.
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
        gap = lowered[where[position - 1] + 1 if position else 0:where[start]]
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
    # What is left may only tell whose copyright the work is, and a copyright line may not
    # take anything back either.
    if not says_nothing(lowered[where[position - 1] + 1:]):
        return None
    return None if any(limit in letters[position:] for limit in LICENSE_LIMITS) else found[0][2]


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


class Found(list):
    """The repositories a search gave. whole tells whether the search got to its end: when the
    time of its place ran out between its pages it did not, and the repositories it did not
    get to are then not known to be gone."""
    whole = True


def search(query):
    """The repositories that have query among their topics, most starred first."""
    found = Found()
    for page in range(1, MAX_PAGES + 1):
        try:
            result = forge("/repos/search?q=%s&topic=true&sort=stars&order=desc&limit=%d&page=%d"
                           % (urllib.parse.quote(query), PAGE_SIZE, page))
        except OutOfTime:
            found.whole = False
            break
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
        "default_branch": repo.get("default_branch"),
    }


def tree_paths(repo):
    """The paths of the files on the default branch of a Forgejo repository, read page by
    page as far as MAX_TREE_PAGES; none when the branch is not there to list."""
    paths = []
    for page in range(1, MAX_TREE_PAGES + 1):
        try:
            tree = forge("/repos/%s/git/trees/%s?recursive=true&per_page=%d&page=%d"
                         % (repo["forge_name"], urllib.parse.quote(repo["default_branch"], safe=""),
                            TREE_PAGE_SIZE, page))
        except urllib.error.HTTPError as error:
            if error.code != 404:
                raise
            return []
        paths.extend(item.get("path") or "" for item in tree.get("tree") or [] if item.get("type") == "blob")
        if not tree.get("truncated"):
            break
    return paths


def raw_address(repo, branch, path, stamp=None):
    """The address a file on a branch of a Forgejo repository is served at, marked with the
    stamp the way build_index.raw_address marks one."""
    address = "%s/%s/raw/branch/%s/%s" % (HOST, repo, urllib.parse.quote(branch, safe="/"),
                                          urllib.parse.quote(path, safe="/"))
    return address + "?at=" + stamp if stamp else address


def gitlab_tree_paths(repo):
    """The paths of the files in the metadata folder of a GitLab project, read page by page
    as far as MAX_TREE_PAGES; none when the folder is not there to list."""
    paths = []
    folder = urllib.parse.quote(build_index.METADATA_FOLDER.rstrip("/"), safe="")
    for page in range(1, MAX_TREE_PAGES + 1):
        try:
            items = gitlab("/projects/%d/repository/tree?recursive=true&path=%s&per_page=%d&page=%d"
                           % (repo["project_id"], folder, GITLAB_TREE_PAGE_SIZE, page))
        except urllib.error.HTTPError as error:
            if error.code != 404:
                raise
            return []
        paths.extend(item.get("path") or "" for item in items if item.get("type") == "blob")
        if len(items) < GITLAB_TREE_PAGE_SIZE:
            break
    return paths


def gitlab_raw_address(project, branch, path, stamp=None):
    """The address a file on a branch of a GitLab project is served at, see raw_address."""
    address = "%s/%s/-/raw/%s/%s" % (GITLAB_HOST, project, urllib.parse.quote(branch, safe="/"),
                                     urllib.parse.quote(path, safe="/"))
    return address + "?at=" + stamp if stamp else address


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

    @staticmethod
    def metadata(repo, stamp):
        """The fastlane metadata of a repository, see build_index.metadata_from_paths, from
        the list of its files and the short descriptions read from the raw server."""
        return build_index.metadata_from_paths(
            tree_paths(repo), repo["forge_name"], repo["default_branch"], stamp,
            read_text=build_index.raw_text, address=raw_address)


def gitlab(path):
    request = urllib.request.Request(GITLAB_HOST + "/api/v4" + path, headers={"User-Agent": USER_AGENT})
    with_token(request, "GITLAB_TOKEN", "PRIVATE-TOKEN")
    with urllib.request.urlopen(request, timeout=time_left()) as response:
        return json.load(response)


def remote_file(url):
    """(size, mark) of the file at url, asked with a request for its first byte. The mark is
    what the server gives for telling one version of the file from another, or "" when it
    gives nothing. The answer is judged by its headers alone: a server that takes no notice
    of the range sends the whole file, which is not read."""
    request = urllib.request.Request(url, headers={
        "Range": "bytes=0-0", "Accept-Encoding": "identity", "User-Agent": USER_AGENT,
    })
    with urllib.request.urlopen(request, timeout=time_left()) as response:
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
    # The path is read part by part: a slash written as %2F is part of a name, not a boundary
    # between parts. That is how the API takes a project by its path. No name may be empty or
    # stand for the directory itself or the one above, not within a part either.
    parts = [urllib.parse.unquote(part) for part in parsed.path.split("/")]
    if any(name in ("", ".", "..") for part in parts[1:] for name in part.split("/")):
        return None
    # Written one and the same way, whatever way the link wrote it.
    path = "/".join(urllib.parse.quote(part, safe="") for part in parts)
    if not GITLAB_FILES.fullmatch(path):
        return None
    name = link.get("name") or ""
    if not name.lower().endswith(".apk"):
        name = parts[-1]
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
        found = Found()
        activity = "&last_activity_after=" + urllib.parse.quote(since) if since else ""
        for page in range(1, MAX_PAGES + 1):
            try:
                items = gitlab("/projects?topic=%s&order_by=star_count&sort=desc&archived=false"
                               "&visibility=public&per_page=%d&page=%d%s"
                               % (urllib.parse.quote(query), GITLAB_PAGE_SIZE, page, activity))
            except OutOfTime:
                found.whole = False
                break
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
            "forge_name": repo["path_with_namespace"],
            "project_id": repo["id"],
            "html_url": repo["web_url"],
            "description": repo.get("description") or "",
            "stargazers_count": repo.get("star_count", 0),
            "topics": repo.get("topics") or [],
            "license": {"spdx_id": license_name},
            "pushed_at": to_utc(repo.get("last_activity_at")),
            "default_branch": detail.get("default_branch"),
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

    @staticmethod
    def metadata(repo, stamp):
        """The fastlane metadata of a project, see Codeberg.metadata."""
        return build_index.metadata_from_paths(
            gitlab_tree_paths(repo), repo["forge_name"], repo["default_branch"], stamp,
            read_text=build_index.raw_text, address=gitlab_raw_address)


PLACES = (Codeberg, GitLab)


def fresh(previous, pushed_at, today):
    """Whether previous, the entry a repository had after the last run, stands without asking
    again: the repository has not been pushed to since, and it was examined within
    AUTO_RECHECK_HOURS."""
    return bool(previous) and previous.get("pushedAt") == pushed_at \
        and today - previous.get("examinedAt", 0) < build_index.AUTO_RECHECK_HOURS * 3600


def examine(repo, previous, today, found, place=Codeberg, icons=None):
    """The entry of one repository, or None when it has nothing to list. previous is its
    entry from the last run: one that is still fresh is kept without asking again, apart from
    the stars and topics, which the search has just told. icons, an IconStore, is where the
    icons of the apps are kept."""
    if fresh(previous, place.pushed_at(repo), today):
        app = dict(previous, stars=place.stars(repo), topics=repo.get("topics") or [])
    else:
        described = place.describe(repo)
        if described is None:
            return None
        app = build_index.build_app(
            described, build_index.known_apks(previous),
            not_before=today - build_index.AUTO_RELEASE_DAYS * 86400 if found else None,
            with_prerelease=not found, list_releases=place.list_releases, icons=icons,
            metadata_of=lambda repo: build_index.app_metadata(repo, previous, read=place.metadata),
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


def shares(started, places, budget=BUDGET_SECONDS):
    """When the time of each of so many places is up, on the clock that read started at the
    beginning. The budget is split evenly, and a place that ends early leaves the rest of its
    share to those after it."""
    return [started + budget * number / places for number in range(1, places + 1)]


def retry_seconds(failures):
    """How long a repository waits for its next try after so many tries in a row have failed,
    see RETRY_HOURS."""
    return RETRY_HOURS[min(failures, len(RETRY_HOURS)) - 1] * 3600


def load_tried(path, today):
    """What the file at path, written by an earlier run, remembers of the repositories of its
    place: {"published": ..., "found": ...}, one for each list, by the name of the repository;
    see Listing for what is remembered. Anything not written the way this builder writes it
    is left out, and so is what is older than TRIED_KEPT_DAYS. A missing or unreadable file
    remembers nothing, and everything is then examined as if for the first time."""
    tried = {"published": {}, "found": {}}
    try:
        with open(path, encoding="utf-8") as file:
            loaded = json.load(file)
    except (OSError, ValueError):
        return tried
    written = loaded.get("tried") if isinstance(loaded, dict) else None
    for kind, records in tried.items():
        given = written.get(kind) if isinstance(written, dict) else None
        for name, record in (given.items() if isinstance(given, dict) else ()):
            if not isinstance(record, dict):
                continue
            pushed_at, at, failures = record.get("pushedAt"), record.get("at"), record.get("failures")
            if isinstance(pushed_at, str) and type(at) in (int, float) and type(failures) is int \
                    and failures >= 0 and 0 <= today - at <= TRIED_KEPT_DAYS * 86400:
                records[name] = {"pushedAt": pushed_at, "at": at, "failures": failures}
    return tried


class Listing:
    """One list of one place in the making: the repositories its search gave, and what the
    run knows of each so far.

    previous holds the entries of the last run, by "place:name". tried holds what the last run
    remembered of the repositories that are not in the list, by name: "pushedAt", the push the
    repository had when it was last looked at, "at", when that was, and "failures", how many
    tries in a row have failed, 0 after an examination that found nothing to list. Without
    this an examination that gives no entry would be made anew in every run, and when the
    time of the place is short the runs would never get past the same repositories.

    A repository is settled, with an entry or with None for nothing to list, when its entry
    is fresh (see fresh), when it gave nothing at its last examination, has not been pushed to
    since and that is at most AUTO_RECHECK_HOURS ago, or when it is examined now. One that
    failed keeps the entry it had and waits before it is tried again, see RETRY_HOURS. The
    rest wait to be examined, see work, and keep the entry they had meanwhile. An app found
    by searching is kept that way only as long as its release is recent, as always.

    seen holds the names the search gave, and whole tells whether the search got to its end.
    When it did not, the entries and the memory of the repositories it did not get to stand
    as they were."""

    def __init__(self, place, found, repos, previous, today, tried=None, icons=None, seen=None, whole=True):
        self.place = place
        self.found = found
        self.previous = previous
        self.today = today
        self.icons = icons
        self.whole = whole
        self.total = len(repos)
        # Set when the place refused the builder itself: nothing more is asked in this run.
        self.stopped = False
        self.names = [place.name(repo) for repo in repos]
        self.seen = set(self.names) if seen is None else seen
        self.settled = {}
        self.tried = {}
        self.waiting = []
        tried = tried or {}
        for order, repo in enumerate(repos):
            name = self.names[order]
            before = previous.get(self.key(name))
            known = tried.get(name)
            pushed_at = place.pushed_at(repo)
            if known and known["failures"]:
                if today - known["at"] < retry_seconds(known["failures"]):
                    self.tried[name] = known
                    continue
            elif known and known["pushedAt"] == pushed_at \
                    and today - known["at"] < build_index.AUTO_RECHECK_HOURS * 3600:
                self.tried[name] = known
                self.settled[name] = None
                continue
            elif fresh(before, pushed_at, today):
                # No request is made for a fresh entry.
                self.settled[name] = examine(repo, before, today, found, place, icons)
                continue
            if known:
                self.tried[name] = known
            last = known["at"] if known else (before or {}).get("examinedAt", 0)
            self.waiting.append((last, -place.stars(repo), order, repo))
        # The longest waiting first, and those never looked at before all others, by their
        # stars: every repository has its turn, whatever becomes of those before it.
        self.waiting.sort(key=lambda item: item[:3])
        if not whole:
            for name, known in tried.items():
                if name not in self.seen:
                    self.tried[name] = known

    def key(self, name):
        return "%s:%s" % (self.place.source, name)

    def standing(self, name):
        """The entry a repository had after the last run, as far as it may stand without the
        repository having been examined."""
        app = self.previous.get(self.key(name))
        if self.found and app and not build_index.recent_enough(app, self.today):
            return None
        return app

    def work(self, until=None):
        """Examines the repositories that wait, until none is left or the clock reads until.
        No request begins after that, see limited. What an examination was doing when the
        time ran out is not held against the repository: it waits for another run, as do
        those the run did not get to."""
        if self.stopped:
            return
        with limited(until):
            while self.waiting:
                if until is not None and clock() >= until:
                    break
                repo = self.waiting[0][3]
                name = self.place.name(repo)
                record = {"pushedAt": self.place.pushed_at(repo), "at": int(self.today), "failures": 0}
                try:
                    app = examine(repo, self.previous.get(self.key(name)), self.today, self.found,
                                  self.place, self.icons)
                except OutOfTime:
                    break
                except Exception as error:  # pylint: disable=broad-except
                    if isinstance(error, urllib.error.HTTPError) and error.code in REFUSALS:
                        print("%s: %r, the place is left alone for this run" % (self.key(name), error),
                              file=sys.stderr)
                        self.stopped = True
                        break
                    if until is not None and until - clock() < LEAST_SECONDS:
                        break
                    # Keep what was known rather than dropping an app over a passing error.
                    print("%s: %r" % (self.key(name), error), file=sys.stderr)
                    record["failures"] = self.tried.get(name, {}).get("failures", 0) + 1
                    self.tried[name] = record
                    del self.waiting[0]
                    continue
                del self.waiting[0]
                self.settled[name] = app
                if app is None:
                    self.tried[name] = record
                else:
                    self.tried.pop(name, None)

    def left(self):
        """How many repositories still wait to be examined."""
        return len(self.waiting)

    def unsettled(self):
        """The names of the repositories whose entry this run does not know: those that wait
        and those that failed."""
        return [name for name in self.names if name not in self.settled]

    def put_off(self):
        """The names of the repositories that still wait to be examined."""
        return [self.place.name(item[3]) for item in self.waiting]

    def entries(self):
        """The list as it stands, see the class."""
        apps = [app for app in self.settled.values() if app]
        apps += [app for app in map(self.standing, self.unsettled()) if app]
        if not self.whole:
            prefix = self.key("")
            names = [key[len(prefix):] for key in self.previous if key.startswith(prefix)]
            apps += [app for app in map(self.standing, (name for name in names if name not in self.seen)) if app]
        return sorted(apps, key=lambda app: app["fullName"].lower())

    def records(self):
        """What to remember for the next run, see the class."""
        return dict(self.tried)


def searched(place, query, since, what):
    """What a search of place gave, as a Found. A search that fails gave nothing and is not
    whole: the place could not be searched, and what it did not tell stands as it was."""
    try:
        given = place.search(query, since)
    except Exception as error:  # pylint: disable=broad-except
        print("%s: %s unavailable: %r" % (place.source, what, error), file=sys.stderr)
        given = Found()
        given.whole = False
        return given
    if not getattr(given, "whole", True):
        print("%s: out of time in the %s, what it did not get to stands as it was"
              % (place.source, what), file=sys.stderr)
    return given


def published_listing(place, previous, tried, today, icons=None):
    """The list of the repositories of one place that carry the store topic. previous holds
    the published entries of the last run, of every place; tried, see Listing."""
    prefix = place.source + ":"
    given = searched(place, build_index.TOPIC, None, "published apps")
    repos = [repo for repo in given if place.usable(repo) and build_index.TOPIC in (repo.get("topics") or [])]
    mine = {key: app for key, app in previous.items() if key.startswith(prefix)}
    return Listing(place, False, repos, mine, today, tried, icons,
                   seen={place.name(repo) for repo in given}, whole=getattr(given, "whole", True))


def found_listing(place, taken, previous, tried, today, icons=None):
    """The list of the apps found by searching one place, leaving out the repositories in
    taken, which their developers have published. previous holds the entries of the place's
    own file from the last run; tried, see Listing."""
    since = today - build_index.AUTO_PUSHED_DAYS * 86400
    candidates = {}
    whole = True
    for query in FOUND_QUERIES:
        given = searched(place, query, time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(since)), "search")
        whole = whole and getattr(given, "whole", True)
        for repo in given:
            candidates.setdefault(place.name(repo), repo)
    repos = [
        repo for name, repo in candidates.items()
        if place.usable(repo)
        and name not in taken
        and build_index.TOPIC not in (repo.get("topics") or [])
        and place.stars(repo) >= FOUND_MIN_STARS
        and (build_index.parse_time(place.pushed_at(repo)) or 0) >= since
    ]
    return Listing(place, True, repos, previous, today, tried, icons, seen=set(candidates) | set(taken), whole=whole)


class Turn:
    """The turn of one place: its two lists in the making, the published and the found.

    The searches are made at once and may take SEARCH_SHARE of the time up to until. The
    examinations are made by work, which can be called again with more time: what the
    searches gave is kept, and only what still waits is examined."""

    def __init__(self, place, published, found, tried, today, icons=None, until=None):
        self.place = place
        limit = None
        if until is not None:
            now = clock()
            limit = now + (until - now) * SEARCH_SHARE
        with limited(limit):
            self.published = published_listing(place, published, tried.get("published") or {}, today, icons)
            # A published app the search did not get to is still its developer's to list.
            taken = set(self.published.names)
            taken.update(app["fullName"][len(place.source) + 1:] for app in self.published.entries())
            self.found = found_listing(place, taken, found, tried.get("found") or {}, today, icons)

    def work(self, until=None):
        """Examines what waits in both lists, the published first, until the clock reads
        until; see Listing.work."""
        for listing in (self.published, self.found):
            listing.work(until)
            if listing.stopped:
                self.published.stopped = self.found.stopped = True

    def unfinished(self):
        """Whether more time would let more be examined."""
        return not self.found.stopped and self.published.left() + self.found.left() > 0

    def lists(self):
        """(published, found): the entries of the two lists as they stand.

        A repository given the store topic leaves the found for the published, and one that
        lost the topic goes the other way. An examination put off for want of time would
        leave such an app in neither list, so it stays in the one it was in until the
        examination for the other has been made. An app on its way to the published stays
        among the found also when that examination fails. One whose developer took the topic
        off does not stay published over a failure: it has been withdrawn, and is listed
        again when it can be read."""
        published, found = self.published.entries(), self.found.entries()
        listed = {app["fullName"] for app in published + found}
        for names, there, apps in ((self.published.unsettled(), self.found, found),
                                   (self.found.put_off(), self.published, published)):
            for name in names:
                app = there.standing(name)
                if app and app["fullName"] not in listed:
                    apps.append(app)
                    listed.add(app["fullName"])
        for apps in (published, found):
            apps.sort(key=lambda app: app["fullName"].lower())
        return published, found

    def tried(self):
        """What the place's file is to remember for the next run, see load_tried."""
        return {"published": self.published.records(), "found": self.found.records()}

    def tell(self):
        """Says how the turn went."""
        left = self.published.left() + self.found.left()
        if left:
            why = "refused" if self.found.stopped else "out of time"
            print("%s: %s, %d of %d left as they were"
                  % (self.place.source, why, left, self.published.total + self.found.total), file=sys.stderr)
        published, found = self.lists()
        remembered = len(self.published.records()) + len(self.found.records())
        print("%s: %d published, %d found, %d remembered for giving nothing or failing"
              % (self.place.source, len(published), len(found), remembered))


def load(path, keys):
    return build_index.load_previous(path, keys) or {}


def write(output_dir, turns, generated_at):
    """Writes the file of each place and the file of the published apps of them all, with
    what the turns have made of them so far."""
    published = []
    for place, turn in zip(PLACES, turns):
        apps, found = turn.lists()
        published += apps
        write_json(os.path.join(output_dir, place.source + ".json"), {
            "version": 1,
            "source": place.source,
            "generatedAt": generated_at,
            "apps": found,
            # Beside the apps, not among them: the app reads "apps" and nothing else.
            "tried": turn.tried(),
        }, ensure_ascii=False)
    published.sort(key=lambda app: app["fullName"].lower())
    write_json(os.path.join(output_dir, "forge.json"), {
        "version": 1,
        "generatedAt": generated_at,
        "apps": [app for app in published if not app.get("betaOnly")],
        "betaApps": [app for app in published if app.get("betaOnly")],
    }, ensure_ascii=False)


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--previous-dir", help="where the files written by the previous run are")
    parser.add_argument("--output-dir", required=True)
    parser.add_argument("--icons-dir", help="where the icons read from the APKs are kept as files")
    parser.add_argument("--icons-url", help="the address the files of --icons-dir are published at")
    arguments = parser.parse_args()
    before = arguments.previous_dir or ""
    previous_published = load(os.path.join(before, "forge.json"), ("apps", "betaApps"))
    today = time.time()
    generated_at = int(today * 1000)
    icons = None
    if arguments.icons_dir and arguments.icons_url:
        icons = build_index.IconStore(arguments.icons_dir, arguments.icons_url)

    ends = shares(clock(), len(PLACES))
    turns = []
    for place, until in zip(PLACES, ends):
        path = os.path.join(before, place.source + ".json")
        turn = Turn(place, previous_published, load(path, ("apps",)), load_tried(path, today), today, icons,
                    until=until)
        turn.work(until)
        turns.append(turn)
    # Written as soon as every place has had its turn, so that the files are there whatever
    # becomes of the rest.
    write(arguments.output_dir, turns, generated_at)
    # What a place left of its time goes to those that ran out of theirs. The searches are
    # not made again.
    again = [turn for turn in turns if turn.unfinished()]
    for turn in again:
        turn.work(ends[-1])
    if again:
        write(arguments.output_dir, turns, generated_at)
    for turn in turns:
        turn.tell()


if __name__ == "__main__":
    main()
