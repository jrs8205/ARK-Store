#!/usr/bin/env python3
"""Builds the store index: one JSON file describing every app published to ARK-Store.

A repository is published by carrying the store topic. It is listed when it is public, has an
open-source license that GitHub recognises and its newest full release has an APK attached.
The app downloads the resulting file instead of asking the GitHub API about every repository,
which keeps it clear of the API's request limit however many apps there are.

Only the standard library is used. Set GITHUB_TOKEN to raise the API request limit.
"""

import argparse
import calendar
import json
import os
import re
import struct
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import zlib

API = "https://api.github.com"
MANIFEST_FILE = b"AndroidManifest.xml"
RESOURCES_FILE = b"resources.arsc"
TOPIC = "arkstore"
PAGE_SIZE = 100
# GitHub's search returns at most 1000 results for a query.
MAX_SEARCH_PAGES = 10
MAX_NOTES = 4000
MAX_DIRECTORY = 8 * 1024 * 1024
MAX_MANIFEST = 4 * 1024 * 1024
# An app's name is looked up in the APK's resource table, which is fetched whole.
MAX_RESOURCES = 32 * 1024 * 1024
MAX_LABEL = 60
USER_AGENT = "ARK-Store-index"

# Apps nobody published to the store, found by searching GitHub. They are offered only to
# users who ask for them. To keep that list fresh and reasonably trustworthy, a repository
# must have been pushed to within AUTO_PUSHED_DAYS, have at least AUTO_MIN_STARS stars, and
# its newest full release must be at most AUTO_RELEASE_DAYS old.
AUTO_QUERIES = (
    "topic:android language:Kotlin",
    "topic:android language:Java",
    "topic:android language:Dart",
    "topic:android-app",
)
AUTO_PUSHED_DAYS = 30
AUTO_RELEASE_DAYS = 180
AUTO_MIN_STARS = 20
AUTO_SEARCH_PAGES = 10
# Each repository costs one API request, and a workflow run has a limited number of them, so
# only this many are examined per run. Repositories that have not changed since they were
# last examined cost nothing, so the list fills up over a few runs and then stays current.
AUTO_MAX_LOOKUPS = 500
AUTO_MAX_NOTES = 800
# A repository that has not been pushed to is still examined again now and then: a release's
# APKs are often attached a while after the push that tagged it, and files can be replaced
# later without any push. One examined within AUTO_SETTLE_HOURS of its last push is looked at
# on every run, any other once in AUTO_RECHECK_HOURS.
AUTO_SETTLE_HOURS = 2
AUTO_RECHECK_HOURS = 24

# The app files an app under the category named by a topic "arkstore-<category>". Nobody has
# chosen one for an automatically found app, so its category is guessed from the words of its
# topics and description and added as such a topic, which every version of the app reads.
# When two categories do equally well the one listed first wins, so the widest come last.
CATEGORY_WORDS = {
    "games": "game games gaming emulator emulation minecraft chess puzzle sudoku",
    "finance": "finance budget expense expenses wallet crypto bitcoin banking money",
    "health": "fitness health workout nutrition sleep meditation",
    "education": "education flashcards anki dictionary",
    "travel": "maps gps openstreetmap osm transit travel hiking",
    "news": "weather news rss",
    "communication": "chat messaging messenger sms mms email mail dialer telegram matrix irc "
                     "xmpp mastodon fediverse social discord whatsapp contacts lemmy reddit "
                     "twitter misskey bluesky forum voip sip",
    "personalization": "launcher keyboard ime wallpaper wallpapers widget widgets",
    "media": "music audio video player podcast podcasts camera gallery photo photos youtube "
             "spotify exoplayer media3 mpv jellyfin emby plex kodi subsonic navidrome streaming "
             "lyrics anime iptv radio tv ffmpeg movie movies bilibili twitch soundcloud mp3 "
             "media recorder manga comic comics novel novels epub ebook ebooks bangumi",
    "system": "battery root magisk xposed lsposed shizuku kernelsu firewall adblocker adblock "
              "dns backup permissions filemanager dhizuku kernel rom grapheneos lineageos "
              "debloat debloater",
    "productivity": "notes note todo tasks task calendar productivity habit pdf office markdown "
                    "timer pomodoro clipboard automation scanner ocr reminder reminders kanban "
                    "bookmark bookmarks translation translator translate",
    "tools": "utility utilities tools calculator qr barcode flashlight converter sync webdav "
             "terminal ssh adb termux installer downloader password passwords totp 2fa "
             "authenticator encryption vpn proxy wireguard v2ray xray shadowsocks trojan vless "
             "vmess hysteria2 tor browser torrent bluetooth nfc ftp sftp tunnel clash",
}
CATEGORY_PHRASES = {
    "file manager": "system",
    "app manager": "system",
    "package manager": "system",
    "system monitor": "system",
    "terminal emulator": "tools",
    "home assistant": "tools",
    "smart home": "tools",
    "remote control": "media",
    "icon pack": "personalization",
    "public transport": "travel",
    "language learning": "education",
}
CATEGORY_OF_WORD = {
    word: category for category, words in CATEGORY_WORDS.items() for word in words.split()
}

# How file names tell which CPU architecture an APK is built for. This mirrors ApkPicker in
# the app, which chooses the file for a device. Longer markers come first, so that "x86_64"
# is not mistaken for "x86" nor "arm64" for "arm".
ARCHITECTURE_MARKERS = (
    ("arm64-v8a", "arm64-v8a"),
    ("arm64", "arm64-v8a"),
    ("aarch64", "arm64-v8a"),
    ("armeabi-v7a", "armeabi-v7a"),
    ("armeabi", "armeabi-v7a"),
    ("armv7", "armeabi-v7a"),
    ("arm32", "armeabi-v7a"),
    ("x86_64", "x86_64"),
    ("x86-64", "x86_64"),
    ("x64", "x86_64"),
    ("amd64", "x86_64"),
    ("x86", "x86"),
    ("i686", "x86"),
)


def api(path):
    request = urllib.request.Request(
        API + path,
        headers={
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
            "User-Agent": USER_AGENT,
        },
    )
    token = os.environ.get("GITHUB_TOKEN")
    if token:
        request.add_header("Authorization", "Bearer " + token)
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.load(response)


def read_range(url, start, length):
    request = urllib.request.Request(
        url,
        headers={
            "Range": "bytes=%d-%d" % (start, start + length - 1),
            "Accept-Encoding": "identity",
            "User-Agent": USER_AGENT,
        },
    )
    with urllib.request.urlopen(request, timeout=30) as response:
        data = response.read()
    if response.status != 206 or len(data) != length:
        # A transport problem, not a property of the file: the caller must not conclude
        # anything about the APK from it.
        raise OSError("range request not honoured")
    return data


class ManifestError(Exception):
    """The APK itself is malformed. Network failures are reported as OSError instead."""


def inflate(data, limit):
    """Inflates raw deflate data, refusing to produce more than limit bytes. The size a zip
    header declares is only a claim, so the output is capped while it is being produced."""
    inflater = zlib.decompressobj(-15)
    result = inflater.decompress(data, limit + 1)
    if len(result) > limit or inflater.unconsumed_tail:
        raise ManifestError("file too large")
    return result


def read_manifest(url, size, labels=None):
    """Returns (package, versionCode, versionName, label) from the APK at url, fetching only
    its zip directory, the compressed AndroidManifest.xml and, when the app's name is kept
    there, its resource table. label is None when the APK does not tell it.

    labels maps a package to the label already read from another APK of the same release.
    The files of one release differ in the CPU architecture they are built for, not in what
    the app is called, so the resource table is fetched for the first of them only.
    """
    try:
        entries = read_directory(url, size, (MANIFEST_FILE, RESOURCES_FILE))
        if MANIFEST_FILE not in entries:
            raise ManifestError("manifest not found")
        manifest = read_entry(url, size, entries[MANIFEST_FILE], MAX_MANIFEST)
        package, version_code, version_name, label = parse_manifest(manifest)
    except (struct.error, IndexError, ValueError, zlib.error, UnicodeDecodeError) as error:
        raise ManifestError(str(error)) from error
    if isinstance(label, int):
        if labels is not None and package in labels:
            label = labels[package]
        else:
            label = read_label(url, size, entries.get(RESOURCES_FILE), label)
    return package, version_code, version_name, tidy_label(label)


def read_label(url, size, entry, resource_id):
    """The string the APK's resource table gives for resource_id, or None. A table that is
    missing, too large or malformed costs the app its name, not its place in the store."""
    if entry is None:
        return None
    try:
        return resource_string(read_entry(url, size, entry, MAX_RESOURCES), resource_id)
    except (ManifestError, struct.error, IndexError, ValueError, zlib.error) as error:
        print("  no name from %s: %s" % (url.rsplit("/", 1)[-1], error), file=sys.stderr)
        return None


def tidy_label(label):
    label = " ".join((label or "").split())
    return label[:MAX_LABEL] or None


def read_directory(url, size, wanted):
    """The zip directory's entries for the file names in wanted, by name."""
    tail_length = min(size, 22 + 65535)
    tail_start = size - tail_length
    tail = read_range(url, tail_start, tail_length)
    eocd = tail.rfind(b"PK\x05\x06")
    if eocd < 0:
        raise ManifestError("zip end record not found")
    directory_size, directory_offset = struct.unpack_from("<II", tail, eocd + 12)
    if directory_size > MAX_DIRECTORY or directory_offset + directory_size > size:
        raise ManifestError("unsupported zip layout")
    if directory_offset >= tail_start:
        begin = directory_offset - tail_start
        directory = tail[begin:begin + directory_size]
    else:
        directory = read_range(url, directory_offset, directory_size)

    entries = {}
    position = 0
    while position + 46 <= len(directory) and directory[position:position + 4] == b"PK\x01\x02":
        method, = struct.unpack_from("<H", directory, position + 10)
        compressed, uncompressed = struct.unpack_from("<II", directory, position + 20)
        name_length, extra_length, comment_length = struct.unpack_from("<HHH", directory, position + 28)
        local_offset, = struct.unpack_from("<I", directory, position + 42)
        name = directory[position + 46:position + 46 + name_length]
        if name in wanted and name not in entries:
            entries[name] = (method, compressed, uncompressed, local_offset, name_length)
        position += 46 + name_length + extra_length + comment_length
    return entries


def read_entry(url, size, entry, limit):
    """The contents of one file of the zip at url, refusing more than limit bytes."""
    method, compressed, uncompressed, local_offset, name_length = entry
    if compressed > limit or uncompressed > limit:
        raise ManifestError("file too large")
    # The local header repeats the name and has an extra field of its own, whose length is
    # not known yet. Android aligns uncompressed files with it, so it is short; the rest
    # is fetched separately in the rare case that the guess falls short.
    guess = 30 + name_length + 4096
    wanted = min(size - local_offset, guess + compressed)
    local = read_range(url, local_offset, wanted)
    if local[:4] != b"PK\x03\x04":
        raise ManifestError("bad local header")
    local_name, local_extra = struct.unpack_from("<HH", local, 26)
    start = 30 + local_name + local_extra
    missing = start + compressed - len(local)
    if missing > 0:
        if local_offset + len(local) + missing > size:
            raise ManifestError("truncated entry")
        local += read_range(url, local_offset + len(local), missing)
    data = local[start:start + compressed]
    if method == 0:
        return data
    if method == 8:
        return inflate(data, limit)
    raise ManifestError("unsupported compression")


def parse_manifest(data):
    """Returns (package, versionCode, versionName, label) from a binary AndroidManifest.xml.
    label is the application's name as text, the id of the resource that holds it, or None."""
    strings = None
    resource_ids = []
    manifest = None
    position = 8
    while position + 8 <= len(data):
        kind, header_size, chunk_size = struct.unpack_from("<HHI", data, position)
        if chunk_size < 8 or header_size < 8 or position + chunk_size > len(data):
            break
        if kind == 0x0001:
            strings = StringPool(data, position, header_size, chunk_size)
        elif kind == 0x0180:
            count = (chunk_size - header_size) // 4
            resource_ids = struct.unpack_from("<%dI" % count, data, position + header_size)
        elif kind == 0x0102:
            if strings is None:
                raise ManifestError("string pool missing")
            body = position + header_size
            name_index, = struct.unpack_from("<i", data, body + 4)
            element = strings.get(name_index)
            if manifest is None and element != "manifest":
                raise ManifestError("unexpected root element")
            if manifest is not None and element != "application":
                position += chunk_size
                continue
            attribute_start, attribute_size, attribute_count = struct.unpack_from("<HHH", data, body + 8)
            if attribute_size < 20:
                raise ManifestError("bad attribute size")
            package = None
            version_code = 0
            version_code_major = 0
            version_name = None
            label = None
            for index in range(attribute_count):
                attribute = body + attribute_start + index * attribute_size
                _, name, raw = struct.unpack_from("<iii", data, attribute)
                data_type = data[attribute + 15]
                value, = struct.unpack_from("<I", data, attribute + 16)
                if data_type == 0x03:
                    text = strings.get(value)
                elif raw >= 0:
                    text = strings.get(raw)
                else:
                    text = None
                resource = resource_ids[name] if 0 <= name < len(resource_ids) else 0
                if resource == 0x0101021B:
                    version_code = value
                elif resource == 0x01010576:
                    version_code_major = value
                elif resource == 0x0101021C:
                    version_name = text
                elif resource == 0x01010001:
                    # A reference to a string resource, or the name itself.
                    label = value if data_type in (0x01, 0x07) else text
                elif strings.get(name) == "package":
                    package = text
            if manifest is not None:
                return manifest + (label,)
            if not package:
                raise ManifestError("package name missing")
            manifest = (package, (version_code_major << 32) | version_code, version_name)
        position += chunk_size
    if manifest is None:
        raise ManifestError("manifest element not found")
    return manifest + (None,)


def resource_string(table, resource_id):
    """The string a resource table (resources.arsc) holds for resource_id, or None.

    A resource has a value for each configuration it is defined in. The one without any
    qualifier is what a device falls back to, so that is taken; failing it, English, and
    failing that too, whichever comes first.
    """
    kind, header_size, _ = struct.unpack_from("<HHI", table, 0)
    if kind != 0x0002:
        raise ManifestError("not a resource table")
    strings = None
    packages = []
    position = header_size
    while position + 8 <= len(table):
        kind, chunk_header, chunk_size = struct.unpack_from("<HHI", table, position)
        if chunk_size < 8 or chunk_header < 8 or position + chunk_size > len(table):
            break
        if kind == 0x0001 and strings is None:
            strings = StringPool(table, position, chunk_header, chunk_size)
        elif kind == 0x0200:
            packages.append((position, chunk_header, chunk_size))
        position += chunk_size
    if strings is None:
        raise ManifestError("string pool missing")
    # A resource may point at another one; a few steps are plenty for any honest table.
    for _ in range(8):
        value = resource_value(table, packages, resource_id)
        if value is None:
            return None
        data_type, data = value
        if data_type == 0x03:
            return strings.get(data)
        if data_type not in (0x01, 0x07) or not data:
            return None
        resource_id = data
    return None


def resource_value(table, packages, resource_id):
    """(type, data) of the value resource_id has in the best configuration, or None."""
    package_id = resource_id >> 24
    type_id = (resource_id >> 16) & 0xFF
    entry = resource_id & 0xFFFF
    best = None
    for package, package_header, package_size in packages:
        if struct.unpack_from("<I", table, package + 8)[0] != package_id:
            continue
        position = package + package_header
        end = package + package_size
        while position + 8 <= end:
            kind, header_size, chunk_size = struct.unpack_from("<HHI", table, position)
            if chunk_size < 8 or header_size < 8 or position + chunk_size > end:
                break
            if kind == 0x0201 and table[position + 8] == type_id:
                value = entry_value(table, position, header_size, chunk_size, entry)
                if value is not None:
                    config_size, = struct.unpack_from("<I", table, position + 20)
                    config = table[position + 24:position + 20 + config_size]
                    if not any(config):
                        return value
                    rank = 1 if config[4:6] == b"en" else 2
                    if best is None or rank < best[0]:
                        best = (rank, value)
            position += chunk_size
    return best[1] if best else None


def entry_value(table, chunk, header_size, chunk_size, entry):
    """(type, data) of one entry of a type chunk, or None when the chunk has no simple value
    for it."""
    flags = table[chunk + 9]
    entry_count, entries_start = struct.unpack_from("<II", table, chunk + 12)
    offsets = chunk + header_size
    offset = None
    if flags & 0x01:
        # Sparse: pairs of entry index and offset, for the entries that exist.
        for index in range(entry_count):
            found, quarter = struct.unpack_from("<HH", table, offsets + index * 4)
            if found == entry:
                offset = quarter * 4
                break
    elif entry < entry_count:
        if flags & 0x02:
            quarter, = struct.unpack_from("<H", table, offsets + entry * 2)
            offset = None if quarter == 0xFFFF else quarter * 4
        else:
            offset, = struct.unpack_from("<I", table, offsets + entry * 4)
            offset = None if offset == 0xFFFFFFFF else offset
    if offset is None:
        return None
    at = chunk + entries_start + offset
    if at + 8 > chunk + chunk_size:
        return None
    size, entry_flags = struct.unpack_from("<HH", table, at)
    if entry_flags & 0x0008:
        # Compact: the type sits in the flags and the data where the key would be.
        return entry_flags >> 8, struct.unpack_from("<I", table, at + 4)[0]
    if entry_flags & 0x0001 or at + size + 8 > chunk + chunk_size:
        return None
    return table[at + size + 3], struct.unpack_from("<I", table, at + size + 4)[0]


class StringPool:
    def __init__(self, data, chunk, header_size, chunk_size):
        self.data = data
        self.chunk = chunk
        self.header_size = header_size
        self.limit = chunk + chunk_size
        self.count, = struct.unpack_from("<i", data, chunk + 8)
        flags, self.strings_start = struct.unpack_from("<Ii", data, chunk + 16)
        self.utf8 = bool(flags & 0x100)
        if self.count < 0 or self.count > (chunk_size - header_size) // 4:
            raise ManifestError("string pool out of range")

    def get(self, index):
        if index < 0 or index >= self.count:
            return None
        offset, = struct.unpack_from("<i", self.data, self.chunk + self.header_size + index * 4)
        at = self.chunk + self.strings_start + offset
        if offset < 0 or at >= self.limit:
            return None
        data = self.data
        if self.utf8:
            at += 2 if data[at] & 0x80 else 1
            length = data[at]
            at += 1
            if length & 0x80:
                length = ((length & 0x7F) << 8) | data[at]
                at += 1
            if at + length > self.limit:
                return None
            return data[at:at + length].decode("utf-8", "replace")
        length, = struct.unpack_from("<H", data, at)
        at += 2
        if length & 0x8000:
            low, = struct.unpack_from("<H", data, at)
            length = ((length & 0x7FFF) << 16) | low
            at += 2
        if at + length * 2 > self.limit:
            return None
        return data[at:at + length * 2].decode("utf-16-le", "replace")


def discover():
    repos = []
    for page in range(1, MAX_SEARCH_PAGES + 1):
        query = urllib.parse.quote("topic:%s archived:false" % TOPIC)
        result = api("/search/repositories?q=%s&sort=updated&per_page=%d&page=%d" % (query, PAGE_SIZE, page))
        items = result.get("items", [])
        repos.extend(items)
        if len(items) < PAGE_SIZE:
            break
        # The search API allows fewer requests per minute than the rest.
        time.sleep(2)
    return repos


def discover_candidates(today):
    """Repositories that might be Android apps worth offering, most starred first."""
    since = time.strftime("%Y-%m-%d", time.gmtime(today - AUTO_PUSHED_DAYS * 86400))
    found = {}
    for base in AUTO_QUERIES:
        query = urllib.parse.quote(
            "%s pushed:>=%s stars:>=%d archived:false" % (base, since, AUTO_MIN_STARS)
        )
        for page in range(1, AUTO_SEARCH_PAGES + 1):
            result = api("/search/repositories?q=%s&sort=stars&order=desc&per_page=%d&page=%d"
                         % (query, PAGE_SIZE, page))
            items = result.get("items", [])
            for repo in items:
                found.setdefault(repo["full_name"], repo)
            time.sleep(2)
            if len(items) < PAGE_SIZE:
                break
    return sorted(found.values(), key=lambda repo: -repo.get("stargazers_count", 0))


def recent_enough(app, today):
    """Whether the release an entry describes is at most AUTO_RELEASE_DAYS old."""
    published = published_at({"published_at": app.get("publishedAt")})
    return published is not None and today - published <= AUTO_RELEASE_DAYS * 86400


def examined(state, full_name):
    """What state holds about a repository: the pushed_at it had when it was last examined,
    the time of that examination and whether it was listed as a result. State written before
    the time was recorded holds the pushed_at alone; such a repository counts as examined
    long ago."""
    value = state.get(full_name)
    if isinstance(value, dict):
        return value.get("pushedAt"), value.get("examinedAt", 0), bool(value.get("listed"))
    return value, 0, False


def recheck_due(pushed_at, examined_at, today):
    """Whether a repository that has not been pushed to since it was examined is due for
    another look. See AUTO_SETTLE_HOURS."""
    pushed = parse_time(pushed_at)
    settling = pushed is not None and examined_at - pushed < AUTO_SETTLE_HOURS * 3600
    return settling or today - examined_at >= AUTO_RECHECK_HOURS * 3600


def guess_category(topics, description):
    """The category the words of a repository's topics and description point to, or None.

    A topic says more than a word of the description, and a phrase more than either."""
    topics = [topic.lower() for topic in topics]
    words = re.findall(r"[a-z0-9]+", (description or "").lower())
    text = " %s " % " ".join(words)
    score = dict.fromkeys(CATEGORY_WORDS, 0)
    for topic in topics:
        for part in {topic, *topic.split("-")}:
            if part in CATEGORY_OF_WORD:
                score[CATEGORY_OF_WORD[part]] += 2
    for word in set(words):
        if word in CATEGORY_OF_WORD:
            score[CATEGORY_OF_WORD[word]] += 1
    for phrase, category in CATEGORY_PHRASES.items():
        if " %s " % phrase in text or phrase.replace(" ", "-") in topics:
            score[category] += 3
    best = max(score.values())
    return next(category for category in score if score[category] == best) if best else None


def with_category(topics, description):
    """topics with the guessed category added as a store topic. A category the developer has
    chosen that way is left alone."""
    prefix = TOPIC + "-"
    if any(topic.lower().startswith(prefix) for topic in topics):
        return topics
    category = guess_category(topics, description)
    return topics + [prefix + category] if category else topics


def build_auto_apps(published, previous_auto, state, today):
    """Builds the list of automatically found apps.

    state records, for each repository, the pushed_at it had when it was last examined and
    when that was. A repository that has not changed and is not due for another look is not
    asked about again: its previous entry, or its absence, still stands. Returns the entries
    and the new state.
    """
    candidates = [
        repo for repo in discover_candidates(today)
        if repo["full_name"] not in published
        and TOPIC not in (repo.get("topics") or [])
        and listable(repo)
    ]

    # The budget goes to repositories that have changed first, most starred first, and then
    # to unchanged ones due for another look, the longest unexamined first.
    changed = []
    due = []
    for repo in candidates:
        full_name = repo["full_name"]
        pushed_at, examined_at, listed = examined(state, full_name)
        if pushed_at != (repo.get("pushed_at") or ""):
            changed.append(full_name)
        elif listed and full_name not in previous_auto:
            # State says the app was listed, yet the previous list does not have it: the two
            # files did not come from the same run. Leaving the repository alone would keep
            # the app out until its next push.
            changed.append(full_name)
        elif recheck_due(pushed_at, examined_at, today):
            due.append((examined_at, full_name))
    examine = set((changed + [name for _, name in sorted(due)])[:AUTO_MAX_LOOKUPS])

    apps = []
    new_state = {}
    lookups = 0
    for repo in candidates:
        full_name = repo["full_name"]
        pushed_at = repo.get("pushed_at") or ""
        previous = previous_auto.get(full_name)
        looked = False
        if full_name not in examine:
            # Unchanged, or out of budget for this run: keep what is known.
            app = previous
            if app:
                # Stars and topics come with the search result, so they are always current.
                app = dict(app, stars=repo.get("stargazers_count", 0), topics=repo.get("topics") or [])
        else:
            lookups += 1
            try:
                # Beta versions of these apps are never offered, so a prerelease is not
                # worth the requests that reading its files takes.
                app = build_app(
                    repo, known_apks(previous), not_before=today - AUTO_RELEASE_DAYS * 86400,
                    with_prerelease=False,
                )
                looked = True
            except (urllib.error.URLError, OSError, ValueError, KeyError) as error:
                print("%s: %s" % (full_name, error), file=sys.stderr)
                app = previous
            if app and looked:
                app["releaseNotes"] = app["releaseNotes"][:AUTO_MAX_NOTES]
        listed = bool(app) and recent_enough(app, today)
        if listed:
            # From the topics as GitHub gives them, so the category is added once however
            # many runs an entry is carried through.
            topics = with_category(repo.get("topics") or [], repo.get("description"))
            apps.append(dict(app, topics=topics))
        if looked:
            new_state[full_name] = {"pushedAt": pushed_at, "examinedAt": int(today), "listed": listed}
        elif full_name in state:
            # What state says stays as it was, so a repository that was passed over or
            # could not be reached is looked at next time.
            new_state[full_name] = state[full_name]
    apps.sort(key=lambda app: app["fullName"].lower())
    print("%d automatically found apps, %d repositories examined" % (len(apps), lookups))
    return apps, new_state


def license_of(repo):
    spdx = (repo.get("license") or {}).get("spdx_id")
    return spdx if spdx and spdx != "NOASSERTION" else None


def listable(repo):
    return (
        not repo.get("private")
        and not repo.get("disabled")
        and not repo.get("fork")
        and not repo.get("archived")
        and license_of(repo) is not None
    )


def apk_assets(release):
    return [a for a in release.get("assets", []) if a["name"].lower().endswith(".apk")]


def release_info(release, previous_apks):
    """Describes one release and its usable APKs, or returns None when it has none."""
    apks = []
    labels = {}
    for asset in apk_assets(release):
        known = previous_apks.get(asset["id"])
        if known and "label" in known:
            # The asset id changes whenever a file is replaced, so a known id means the same
            # contents. Its name and address can still change, so those are never reused.
            # An entry written before names were read has no label, and is read again.
            manifest = (known["packageName"], known["versionCode"], known["versionName"],
                        known["label"])
        else:
            try:
                manifest = read_manifest(asset["browser_download_url"], asset["size"], labels)
            except ManifestError as error:
                # The file is broken; leave it out. A network failure, on the other hand,
                # propagates so that the caller keeps what it knew about the app.
                print("  skipping %s: %s" % (asset["name"], error), file=sys.stderr)
                continue
        apks.append({
            "id": asset["id"],
            "name": asset["name"],
            "url": asset["browser_download_url"],
            "size": asset["size"],
            "packageName": manifest[0],
            "versionCode": manifest[1],
            "versionName": manifest[2],
            "label": manifest[3],
        })
        labels[manifest[0]] = manifest[3]
    if not apks:
        return None
    return {
        "tag": release["tag_name"],
        "releaseName": release.get("name") or "",
        "releaseNotes": (release.get("body") or "")[:MAX_NOTES],
        "releaseUrl": release.get("html_url") or "",
        "publishedAt": release.get("published_at") or "",
        "apks": apks,
    }


def parse_time(text):
    """A GitHub timestamp in seconds since the epoch, or None when it is missing or odd."""
    try:
        return calendar.timegm(time.strptime(text, "%Y-%m-%dT%H:%M:%SZ"))
    except (TypeError, ValueError):
        return None


def published_at(release):
    """The time a release was published, in seconds since the epoch, or None."""
    return parse_time(release.get("published_at"))


def architectures(file_name):
    """The CPU architectures an APK's file name says it is built for; empty when it names
    none or says "universal", which means it runs everywhere."""
    rest = file_name.lower()
    if "universal" in rest:
        return set()
    found = set()
    for marker, abi in ARCHITECTURE_MARKERS:
        if marker in rest:
            found.add(abi)
            rest = rest.replace(marker, " ")
    return found


def is_upgrade(beta_info, stable_info):
    """Whether a prerelease upgrades a full release on some device: the same package with a
    higher version code.

    GitHub lists releases by the date of the tagged commit, so a prerelease at the top of the
    list may come from an older branch, be a nightly build tagged over and over, or be a
    separate app with its own package name.

    Releases with one APK per CPU architecture often give each its own version code, and the
    two releases need not cover the same architectures. So each APK of the prerelease is
    compared with the full-release APKs a device could have had in its place: those built
    for the same architecture and those that run everywhere. The app makes the final choice
    for the device at hand; this only drops a prerelease that upgrades nothing anywhere.
    """
    for beta in beta_info["apks"]:
        same_package = [apk for apk in stable_info["apks"] if apk["packageName"] == beta["packageName"]]
        if not same_package:
            continue
        built_for = architectures(beta["name"])
        alternatives = [
            apk for apk in same_package
            if not built_for or not architectures(apk["name"]) or built_for & architectures(apk["name"])
        ]
        # With no full release for its architecture, the prerelease is all such a device has.
        if not alternatives or any(beta["versionCode"] > apk["versionCode"] for apk in alternatives):
            return True
    return False


def build_app(repo, previous_apks, not_before=None, with_prerelease=True):
    """Returns the index entry of a repository, or None when it has nothing to install.

    With not_before, a repository whose newest full release was published earlier than that
    is given up on at once, before any of its APKs are examined.

    The entry describes the newest full release. When the release at the top of the list is a
    prerelease that upgrades it, that is added under "beta" for users who have asked for beta
    versions. One that does not is noted under "skippedPrerelease", only so that its APKs are
    not examined again on the next run. A repository with nothing but a prerelease gets an
    entry marked "betaOnly", built from it. Without with_prerelease, prereleases are not
    looked at.
    """
    full_name = repo["full_name"]
    page = api("/repos/%s/releases?per_page=%d" % (full_name, PAGE_SIZE))
    releases = [r for r in page if not r.get("draft")]
    stable = next((r for r in releases if not r.get("prerelease")), None)
    if stable is None and len(page) == PAGE_SIZE:
        try:
            stable = api("/repos/%s/releases/latest" % full_name)
        except urllib.error.HTTPError as error:
            if error.code != 404:
                raise
    if not_before is not None:
        if stable is None or (published_at(stable) or 0) < not_before:
            return None
    # Releases come newest first, so a prerelease at the top is usually newer than the full
    # release; is_upgrade makes sure.
    newest = releases[0] if releases else None
    prerelease = newest if newest is not None and newest.get("prerelease") else None
    if not with_prerelease:
        prerelease = None

    stable_info = release_info(stable, previous_apks) if stable else None
    beta_info = release_info(prerelease, previous_apks) if prerelease else None
    if stable_info is None and beta_info is None:
        return None

    app = {
        "fullName": full_name,
        "description": repo.get("description") or "",
        "stars": repo.get("stargazers_count", 0),
        "topics": repo.get("topics") or [],
        "license": license_of(repo),
        "repoUrl": repo["html_url"],
        "pushedAt": repo.get("pushed_at") or "",
        "downloads": sum(a.get("download_count", 0) for r in releases for a in apk_assets(r)),
        # The part of the downloads that prereleases account for, so that the app can tell
        # how often the full releases and the beta versions have each been downloaded.
        "betaDownloads": sum(
            a.get("download_count", 0) for r in releases if r.get("prerelease") for a in apk_assets(r)
        ),
    }
    if stable_info is not None:
        app.update(stable_info)
        if beta_info is not None:
            if is_upgrade(beta_info, stable_info):
                app["beta"] = beta_info
            else:
                app["skippedPrerelease"] = {"tag": beta_info["tag"], "apks": beta_info["apks"]}
    else:
        app.update(beta_info)
        app["betaOnly"] = True
    return app


def known_apks(app):
    """Every APK an index entry describes, keyed by asset id."""
    if not app:
        return {}
    apks = list(app.get("apks", []))
    for key in ("beta", "skippedPrerelease"):
        apks += (app.get(key) or {}).get("apks", [])
    return {apk["id"]: apk for apk in apks}


def load_previous(path, keys):
    """The entries under keys in a file written by an earlier run, by repository; None when
    the file is missing or unreadable."""
    if not path or not os.path.exists(path):
        return None
    try:
        with open(path, encoding="utf-8") as file:
            loaded = json.load(file)
        return {app["fullName"]: app for key in keys for app in loaded.get(key, [])}
    except (ValueError, KeyError, TypeError, AttributeError):
        print("%s unreadable, starting fresh" % path, file=sys.stderr)
        return None


def write_json(path, value, **options):
    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    with open(path, "w", encoding="utf-8") as file:
        json.dump(value, file, separators=(",", ":"), **options)


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--previous", help="the previous index, to reuse what is known about unchanged APKs")
    parser.add_argument("--previous-auto", help="the previous list of automatically found apps")
    parser.add_argument("--state", help="what earlier runs learnt about automatically found repositories; "
                                        "read if present and rewritten")
    parser.add_argument("--output", required=True)
    parser.add_argument("--auto-output", required=True,
                        help="where the automatically found apps go; they are many and wanted by few, "
                             "so the app downloads them separately and only on request")
    arguments = parser.parse_args()

    previous_apps = load_previous(arguments.previous, ("apps", "betaApps")) or {}
    previous_auto = load_previous(arguments.previous_auto, ("autoApps",))
    if previous_auto is None:
        # An index built before the list had a file of its own carries it itself.
        previous_auto = load_previous(arguments.previous, ("autoApps",)) or {}

    state = {}
    if arguments.state and os.path.exists(arguments.state):
        try:
            with open(arguments.state, encoding="utf-8") as file:
                state = json.load(file)
        except ValueError:
            print("state unreadable, starting fresh", file=sys.stderr)

    apps = []
    failed = 0
    for repo in discover():
        if not listable(repo):
            continue
        full_name = repo["full_name"]
        previous = previous_apps.get(full_name)
        try:
            app = build_app(repo, known_apks(previous))
        except (urllib.error.URLError, OSError, ValueError, KeyError) as error:
            # Keep what was known rather than dropping an app over a passing error.
            print("%s: %s" % (full_name, error), file=sys.stderr)
            failed += 1
            app = previous
        if app:
            apps.append(app)
            print("%s %s (%d APK)%s" % (
                full_name, app["tag"], len(app["apks"]),
                ", beta %s" % app["beta"]["tag"] if app.get("beta") else "",
            ))

    apps.sort(key=lambda app: app["fullName"].lower())

    today = time.time()
    try:
        auto_apps, state = build_auto_apps(
            {app["fullName"] for app in apps}, previous_auto, state, today
        )
    except Exception as error:  # pylint: disable=broad-except
        # The published apps matter most; nothing that goes wrong in this optional part may
        # cost them their update.
        print("automatic discovery failed: %s" % error, file=sys.stderr)
        auto_apps = [app for app in previous_auto.values() if recent_enough(app, today)]

    generated_at = int(time.time() * 1000)
    write_json(arguments.output, {
        "version": 1,
        "generatedAt": generated_at,
        # Apps with a full release. Kept free of beta-only entries so that every version of
        # the store can read this list.
        "apps": [app for app in apps if not app.get("betaOnly")],
        "betaApps": [app for app in apps if app.get("betaOnly")],
    }, ensure_ascii=False)
    # Apps found by searching GitHub rather than published by their developers.
    write_json(arguments.auto_output, {
        "version": 1,
        "generatedAt": generated_at,
        "autoApps": auto_apps,
    }, ensure_ascii=False)
    if arguments.state:
        write_json(arguments.state, state)
    print("%d apps, %d repositories failed" % (len(apps), failed))


if __name__ == "__main__":
    main()
