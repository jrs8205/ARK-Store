#!/usr/bin/env python3
"""Builds the store index: one JSON file describing every app published to ARK-Store.

A repository is published by carrying the store topic. It is listed when it is public, has an
open-source license that GitHub recognises and its newest full release has an APK attached.
The app downloads the resulting file instead of asking the GitHub API about every repository,
which keeps it clear of the API's request limit however many apps there are.

Only the standard library is used. Set GITHUB_TOKEN to raise the API request limit.
"""

import argparse
import json
import os
import struct
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import zlib

API = "https://api.github.com"
TOPIC = "arkstore"
PAGE_SIZE = 100
# GitHub's search returns at most 1000 results for a query.
MAX_SEARCH_PAGES = 10
MAX_NOTES = 4000
MAX_DIRECTORY = 8 * 1024 * 1024
MAX_MANIFEST = 4 * 1024 * 1024
USER_AGENT = "ARK-Store-index"


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
        raise ManifestError("manifest too large")
    return result


def read_manifest(url, size):
    """Returns (package, versionCode, versionName) from the APK at url, fetching only its
    zip directory and the compressed AndroidManifest.xml."""
    try:
        return parse_manifest(read_manifest_bytes(url, size))
    except (struct.error, IndexError, ValueError, zlib.error, UnicodeDecodeError) as error:
        raise ManifestError(str(error)) from error


def read_manifest_bytes(url, size):
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

    position = 0
    while position + 46 <= len(directory) and directory[position:position + 4] == b"PK\x01\x02":
        method, = struct.unpack_from("<H", directory, position + 10)
        compressed, uncompressed = struct.unpack_from("<II", directory, position + 20)
        name_length, extra_length, comment_length = struct.unpack_from("<HHH", directory, position + 28)
        local_offset, = struct.unpack_from("<I", directory, position + 42)
        name = directory[position + 46:position + 46 + name_length]
        if name == b"AndroidManifest.xml":
            if compressed > MAX_MANIFEST or uncompressed > MAX_MANIFEST:
                raise ManifestError("manifest too large")
            wanted = min(size - local_offset, 30 + len(name) + 65535 + compressed)
            local = read_range(url, local_offset, wanted)
            if local[:4] != b"PK\x03\x04":
                raise ManifestError("bad local header")
            local_name, local_extra = struct.unpack_from("<HH", local, 26)
            start = 30 + local_name + local_extra
            data = local[start:start + compressed]
            if len(data) != compressed:
                raise ManifestError("truncated entry")
            if method == 0:
                return data
            if method == 8:
                return inflate(data, MAX_MANIFEST)
            raise ManifestError("unsupported compression")
        position += 46 + name_length + extra_length + comment_length
    raise ManifestError("manifest not found")


def parse_manifest(data):
    strings = None
    resource_ids = []
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
            if strings.get(name_index) != "manifest":
                raise ManifestError("unexpected root element")
            attribute_start, attribute_size, attribute_count = struct.unpack_from("<HHH", data, body + 8)
            if attribute_size < 20:
                raise ManifestError("bad attribute size")
            package = None
            version_code = 0
            version_code_major = 0
            version_name = None
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
                elif strings.get(name) == "package":
                    package = text
            if not package:
                raise ManifestError("package name missing")
            return package, (version_code_major << 32) | version_code, version_name
        position += chunk_size
    raise ManifestError("manifest element not found")


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
    for asset in apk_assets(release):
        known = previous_apks.get(asset["id"])
        if known:
            # The asset id changes whenever a file is replaced, so a known id means the same
            # contents. Its name and address can still change, so those are never reused.
            manifest = (known["packageName"], known["versionCode"], known["versionName"])
        else:
            try:
                manifest = read_manifest(asset["browser_download_url"], asset["size"])
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
        })
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


def build_app(repo, previous_apks):
    """Returns the index entry of a repository, or None when it has nothing to install.

    The entry describes the newest full release. When the newest release of all is a
    prerelease, it is added under "beta" for users who have asked for beta versions. A
    repository with nothing but a prerelease gets an entry marked "betaOnly", built from it.
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
    # Releases come newest first, so a prerelease at the top is newer than the full release.
    newest = releases[0] if releases else None
    prerelease = newest if newest is not None and newest.get("prerelease") else None

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
    }
    if stable_info is not None:
        app.update(stable_info)
        if beta_info is not None:
            app["beta"] = beta_info
    else:
        app.update(beta_info)
        app["betaOnly"] = True
    return app


def known_apks(app):
    """Every APK an index entry describes, keyed by asset id."""
    if not app:
        return {}
    apks = list(app.get("apks", [])) + list((app.get("beta") or {}).get("apks", []))
    return {apk["id"]: apk for apk in apks}


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--previous", help="the previous index, to reuse what is known about unchanged APKs")
    parser.add_argument("--output", required=True)
    arguments = parser.parse_args()

    previous_apps = {}
    if arguments.previous and os.path.exists(arguments.previous):
        try:
            with open(arguments.previous, encoding="utf-8") as file:
                loaded = json.load(file)
                previous_apps = {
                    app["fullName"]: app
                    for app in loaded.get("apps", []) + loaded.get("betaApps", [])
                }
        except (ValueError, KeyError, TypeError):
            print("previous index unreadable, starting fresh", file=sys.stderr)

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
    index = {
        "version": 1,
        "generatedAt": int(time.time() * 1000),
        # Apps with a full release. Kept free of beta-only entries so that every version of
        # the store can read this list.
        "apps": [app for app in apps if not app.get("betaOnly")],
        "betaApps": [app for app in apps if app.get("betaOnly")],
    }
    os.makedirs(os.path.dirname(os.path.abspath(arguments.output)), exist_ok=True)
    with open(arguments.output, "w", encoding="utf-8") as file:
        json.dump(index, file, ensure_ascii=False, separators=(",", ":"))
    print("%d apps, %d repositories failed" % (len(apps), failed))


if __name__ == "__main__":
    main()
