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
import functools
import hashlib
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
# An app's icon is read from the APK as well and kept as a file beside the index.
MAX_ICON = 512 * 1024
# The dots per inch a row of the list wants its icon in: 144 pixels for 48 dp. Of the files an
# app has, the densest up to this is taken, failing that the least dense beyond it.
ICON_DENSITY = 480
IMAGE_SUFFIXES = (".png", ".webp", ".jpg", ".jpeg")
# The attributes that name an application's icon and the drawable of a layer of one.
ATTRIBUTE_ICON = 0x01010002
# android:minSdkVersion of the uses-sdk element.
ATTRIBUTE_MIN_SDK = 0x0101020C
ATTRIBUTE_DRAWABLE = 0x01010199
# The APK signing block sits just before the zip directory and ends with these words. Its
# pairs with these ids hold the signers of signature schemes v3.1, v3 and v2, in the order
# a recent device looks at them; the first names the present key of an app whose key has
# been rotated.
SIGNING_BLOCK_MAGIC = b"APK Sig Block 42"
MAX_SIGNING_BLOCK = 1024 * 1024
SCHEME_V2 = 0x7109871A
SIGNATURE_SCHEMES = (0x1B93AD61, 0xF05368C0, SCHEME_V2)
PROOF_OF_ROTATION = 0x3BA06F8C
# The version of read_signer, written beside each signer: a file whose signer an older
# reader named is read again, since the reader may have named a key it should not have.
# Raise it whenever read_signer changes what it names.
SIGNER_READER = 2
# The version of the reader of the lowest Android version, written beside each minSdk: a
# file an older reader read is read again. The first reader missed a uses-sdk element that
# came after the application element and wrote 1 for it. Raise it whenever lowest_sdk or
# the way parse_manifest finds the element changes what is written.
SDK_READER = 1
USER_AGENT = "ARK-Store-index"
# An app's long description and phone screenshots are read from where the F-Droid tools take
# them, fastlane/metadata/android/<locale>/, for each of these languages.
METADATA_FOLDER = "fastlane/metadata/android/"
LANGUAGES = ("en", "fi")
DEFAULT_REGIONS = {"en": "US", "fi": "FI"}
MAX_SCREENSHOTS = 8
RAW_ADDRESS = "https://raw.githubusercontent.com"

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
# A repository whose first page of releases holds nothing but prereleases costs a second
# request. The token of a workflow run is allowed 1000 requests an hour, which the published
# apps and a run started soon after this one need a share of, so a run stops examining once
# it has made this many requests; what is left over is examined by the next run.
AUTO_MAX_REQUESTS = 600
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


api_requests = 0


def api(path):
    global api_requests  # pylint: disable=global-statement
    api_requests += 1
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
        # A server that takes no notice of the range sends the whole file, which is not read.
        data = response.read(length + 1) if response.status == 206 else b""
    if len(data) != length:
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


def read_manifest(url, size, labels=None, icons=None):
    """Returns (package, versionCode, versionName, label, signer, minSdk) from the APK at
    url, fetching only its zip directory, the compressed AndroidManifest.xml, its signing
    block and, when the app's name is kept there, its resource table. label is None when the
    APK does not tell it; signer is the SHA-256 of the certificate the APK is signed with, in
    hex, or None when that cannot be told (see read_signer); minSdk is the lowest Android API
    level the app runs on, or the codename of the preview of Android the manifest names
    instead of a number.

    labels maps a package to the label already read from another APK of the same release.
    The files of one release differ in the CPU architecture they are built for, not in what
    the app is called, so the resource table is fetched for the first of them only.

    With icons, an IconStore, the app's icon is read from the APK too and kept there: ask
    the store for it afterwards. Like the label, it is read from the first file of a package
    in a release only; the releases of a package can have different icons.
    """
    try:
        entries = read_directory(url, size)
        if MANIFEST_FILE not in entries:
            raise ManifestError("manifest not found")
        manifest = read_entry(url, size, entries[MANIFEST_FILE], MAX_MANIFEST)
        package, version_code, version_name, label, icon, min_sdk = parse_manifest(manifest)
    except (struct.error, IndexError, ValueError, zlib.error, UnicodeDecodeError) as error:
        raise ManifestError(str(error)) from error
    table = None
    if isinstance(label, int):
        if labels is not None and package in labels:
            label = labels[package]
        else:
            table = read_table(url, size, entries.get(RESOURCES_FILE))
            label = table_string(table, label, url) if table else None
    if icons is not None and (labels is None or package not in labels):
        if table is None and icon:
            table = read_table(url, size, entries.get(RESOURCES_FILE))
        icons.read(url, size, entries, table, icon, package)
    try:
        signer = read_signer(url, size, entries.start)
    except (ManifestError, struct.error, IndexError) as error:
        print("  no signer from %s: %s" % (url.rsplit("/", 1)[-1], error), file=sys.stderr)
        signer = None
    return package, version_code, version_name, tidy_label(label), signer, min_sdk


def read_signer(url, size, directory_start):
    """The SHA-256 of the certificate the APK is signed with, in hex, or None when it cannot
    be told: the APK is signed with the version 1 scheme only, by several keys at once, or
    with a rotated key, when it carries different keys for different versions of Android or
    a proof of rotation, which lets it update an app signed with a key before it. Read from
    the signing block, which sits just before the zip directory that starts at
    directory_start. A network failure propagates."""
    if directory_start < 32:
        return None
    tail = read_range(url, directory_start - 24, 24)
    block_size, = struct.unpack_from("<Q", tail, 0)
    if tail[8:24] != SIGNING_BLOCK_MAGIC:
        return None
    if block_size > MAX_SIGNING_BLOCK or block_size + 8 > directory_start or block_size < 24:
        raise ManifestError("odd signing block")
    block = read_range(url, directory_start - block_size - 8, block_size + 8)
    certificates = {}
    position = 8
    end = block_size + 8 - 24
    while position + 12 <= end:
        length, pair_id = struct.unpack_from("<QI", block, position)
        if length < 4 or position + 8 + length > end:
            raise ManifestError("odd signing block")
        if pair_id in SIGNATURE_SCHEMES:
            found, rotated = signer_certificates(block[position + 12:position + 8 + length],
                                                 pair_id != SCHEME_V2)
            if rotated:
                return None
            certificates[pair_id] = found
        position += 8 + length
    # Each scheme is read by some versions of Android, so a file whose schemes disagree is
    # signed with different keys on different devices: nothing to compare against before
    # the download. A scheme with several signers is no single key either.
    signers = set()
    for scheme in SIGNATURE_SCHEMES:
        found = certificates.get(scheme)
        if found is not None:
            if len(found) != 1:
                return None
            signers.add(hashlib.sha256(found[0]).hexdigest())
    return signers.pop() if len(signers) == 1 else None


def signer_certificates(value, versioned):
    """(the first certificate of each signer in the value of a signature scheme's pair,
    whether a signer carries a proof of rotation). The proof is an additional attribute of
    the signed data, which in versions 3 and 3.1 (versioned) comes after the range of
    Android versions the signer is for."""
    found = []
    rotated = False
    signers, _ = prefixed(value, 0)
    position = 0
    while position + 4 <= len(signers):
        signer, position = prefixed(signers, position)
        signed_data, _ = prefixed(signer, 0)
        _, after = prefixed(signed_data, 0)
        certificates, after = prefixed(signed_data, after)
        if len(certificates) >= 4:
            found.append(prefixed(certificates, 0)[0])
        if versioned:
            after += 8
        if after + 4 <= len(signed_data):
            attributes, _ = prefixed(signed_data, after)
            at = 0
            while at + 4 <= len(attributes):
                attribute, at = prefixed(attributes, at)
                if len(attribute) >= 4 and struct.unpack_from("<I", attribute, 0)[0] == PROOF_OF_ROTATION:
                    rotated = True
    return found, rotated


def prefixed(data, position):
    """(bytes, next position) of the length-prefixed field at position in data."""
    length, = struct.unpack_from("<I", data, position)
    start = position + 4
    if start + length > len(data):
        raise ManifestError("signing block cut short")
    return data[start:start + length], start + length


def read_table(url, size, entry):
    """The APK's resource table, or None. A table that is missing, too large or malformed
    costs the app its name and icon, not its place in the store."""
    if entry is None:
        return None
    try:
        return read_entry(url, size, entry, MAX_RESOURCES)
    except (ManifestError, struct.error, IndexError, ValueError, zlib.error) as error:
        print("  no resource table from %s: %s" % (url.rsplit("/", 1)[-1], error), file=sys.stderr)
        return None


def table_string(table, resource_id, url):
    """The string the resource table gives for resource_id, or None for a malformed table."""
    try:
        return resource_string(table, resource_id)
    except (ManifestError, struct.error, IndexError, ValueError) as error:
        print("  no name from %s: %s" % (url.rsplit("/", 1)[-1], error), file=sys.stderr)
        return None


def tidy_label(label):
    label = " ".join((label or "").split())
    return label[:MAX_LABEL] or None


class ZipDirectory(dict):
    """The entries of a zip directory by name, and where the directory starts in the file."""
    start = 0


def read_directory(url, size, wanted=None):
    """The zip directory's entries by name: those for the file names in wanted, or all."""
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

    entries = ZipDirectory()
    entries.start = directory_offset
    position = 0
    while position + 46 <= len(directory) and directory[position:position + 4] == b"PK\x01\x02":
        method, = struct.unpack_from("<H", directory, position + 10)
        compressed, uncompressed = struct.unpack_from("<II", directory, position + 20)
        name_length, extra_length, comment_length = struct.unpack_from("<HHH", directory, position + 28)
        local_offset, = struct.unpack_from("<I", directory, position + 42)
        name = directory[position + 46:position + 46 + name_length]
        if (wanted is None or name in wanted) and name not in entries:
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
    # Where the directory says the file is must lie inside the APK. Asking the server for
    # anything else would fail in a way that looks like a network problem.
    if local_offset + 30 + name_length + compressed > size:
        raise ManifestError("file outside the archive")
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


def elements(document, ends=False):
    """The start tags of a binary XML document, in order, each as (name, attributes). An
    attribute is (resource, name, type, data, text): the id of the attribute's resource, 0
    when it has none, its name, its typed value and the text of a value that is a string.
    With ends, end tags come too, as (name, None)."""
    strings = None
    resource_ids = ()
    position = 8
    while position + 8 <= len(document):
        kind, header_size, chunk_size = struct.unpack_from("<HHI", document, position)
        if chunk_size < 8 or header_size < 8 or position + chunk_size > len(document):
            break
        if kind == 0x0001:
            strings = StringPool(document, position, header_size, chunk_size)
        elif kind == 0x0180:
            count = (chunk_size - header_size) // 4
            resource_ids = struct.unpack_from("<%dI" % count, document, position + header_size)
        elif kind == 0x0103 and ends:
            if strings is None:
                raise ManifestError("string pool missing")
            name_index, = struct.unpack_from("<i", document, position + header_size + 4)
            yield strings.get(name_index), None
        elif kind == 0x0102:
            if strings is None:
                raise ManifestError("string pool missing")
            body = position + header_size
            name_index, = struct.unpack_from("<i", document, body + 4)
            attribute_start, attribute_size, attribute_count = struct.unpack_from("<HHH", document, body + 8)
            if attribute_size < 20:
                raise ManifestError("bad attribute size")
            attributes = []
            for index in range(attribute_count):
                attribute = body + attribute_start + index * attribute_size
                _, name, raw = struct.unpack_from("<iii", document, attribute)
                data_type = document[attribute + 15]
                value, = struct.unpack_from("<I", document, attribute + 16)
                if data_type == 0x03:
                    text = strings.get(value)
                elif raw >= 0:
                    text = strings.get(raw)
                else:
                    text = None
                resource = resource_ids[name] if 0 <= name < len(resource_ids) else 0
                attributes.append((resource, strings.get(name), data_type, value, text))
            yield strings.get(name_index), attributes
        position += chunk_size


def parse_manifest(data):
    """Returns (package, versionCode, versionName, label, icon, minSdk) from a binary
    AndroidManifest.xml. label is the application's name as text, the id of the resource that
    holds it, or None; icon the id of the resource that holds the application's icon, or
    None; minSdk the lowest Android API level the app runs on, 1 when the manifest names none,
    as Android takes it, or the codename of the preview of Android it names instead of a
    number, as text."""
    manifest = None
    application = None
    min_sdk = 1
    # How many elements are open: 1 inside the root element.
    depth = 0
    # The children of the manifest element come in the order they were written, so the
    # whole document is read: uses-sdk can follow application.
    for element, attributes in elements(data, ends=True):
        if attributes is None:
            depth -= 1
            continue
        depth += 1
        if manifest is None and element != "manifest":
            raise ManifestError("unexpected root element")
        if element == "uses-sdk":
            # Only as a child of the manifest element: Android skips one nested deeper,
            # inside the application element say.
            if depth == 2:
                min_sdk = lowest_sdk(attributes)
            continue
        if manifest is not None and (element != "application" or application is not None):
            continue
        package = None
        version_code = 0
        version_code_major = 0
        version_name = None
        label = None
        icon = None
        for resource, name, data_type, value, text in attributes:
            if resource == 0x0101021B:
                version_code = value
            elif resource == 0x01010576:
                version_code_major = value
            elif resource == 0x0101021C:
                version_name = text
            elif resource == 0x01010001:
                # A reference to a string resource, or the name itself.
                label = value if data_type in (0x01, 0x07) else text
            elif resource == ATTRIBUTE_ICON:
                icon = value if data_type in (0x01, 0x07) and value else None
            elif name == "package":
                package = text
        if manifest is not None:
            application = (label, icon)
            continue
        if not package:
            raise ManifestError("package name missing")
        manifest = (package, (version_code_major << 32) | version_code, version_name)
    if manifest is None:
        raise ManifestError("manifest element not found")
    return manifest + (application or (None, None)) + (min_sdk,)


def lowest_sdk(attributes):
    """The Android API level a uses-sdk element's attributes name as the lowest the app runs
    on: 1 when they name none, or the codename, as text, when they name a preview of Android
    by its codename rather than a number. Only that preview runs such a file."""
    for resource, _, data_type, value, text in attributes:
        if resource == ATTRIBUTE_MIN_SDK:
            # A number written in decimal or in hexadecimal, or a string, the codename.
            if data_type in (0x10, 0x11):
                return value
            return text if data_type == 0x03 else None
    return 1


def adaptive_layers(document):
    """The resources the layers of an adaptive icon refer to, by layer ("background",
    "foreground"), from the binary XML that describes the icon. Empty for a drawable of any
    other kind, such as a vector."""
    layers = {}
    root = None
    for element, attributes in elements(document):
        if root is None:
            root = element
            if root != "adaptive-icon":
                return {}
        elif element in ("background", "foreground"):
            for resource, _, data_type, value, _ in attributes:
                if resource == ATTRIBUTE_DRAWABLE and data_type in (0x01, 0x07) and value:
                    layers[element] = value
    return layers


def table_parts(table):
    """(strings, packages) of a resource table (resources.arsc): its string pool and the
    position, header size and size of each package chunk."""
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
    return strings, packages


def resource_string(table, resource_id):
    """The string a resource table (resources.arsc) holds for resource_id, or None.

    A resource has a value for each configuration it is defined in. The one without any
    qualifier is what a device falls back to, so that is taken; failing it, English, and
    failing that too, whichever comes first.
    """
    strings, packages = table_parts(table)
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


def resource_values(table, packages, resource_id):
    """(configuration, (type, data)) for every configuration resource_id has a value in, the
    configuration being its bytes after the size: country and language at 4, density at 10."""
    package_id = resource_id >> 24
    type_id = (resource_id >> 16) & 0xFF
    entry = resource_id & 0xFFFF
    found = []
    for package, package_header, package_size in packages:
        if struct.unpack_from("<I", table, package + 8)[0] != package_id:
            continue
        position = package + package_header
        end = package + package_size
        while position + 8 <= end:
            kind, header_size, chunk_size = struct.unpack_from("<HHI", table, position)
            if chunk_size < 8 or header_size < 8 or position + chunk_size > end:
                break
            if kind == 0x0201 and header_size >= 24 and table[position + 8] == type_id:
                value = entry_value(table, position, header_size, chunk_size, entry)
                if value is not None:
                    config_size, = struct.unpack_from("<I", table, position + 20)
                    found.append((table[position + 24:position + min(20 + config_size, header_size)], value))
            position += chunk_size
    return found


def resource_value(table, packages, resource_id):
    """(type, data) of the value resource_id has in the best configuration, or None."""
    best = None
    for config, value in resource_values(table, packages, resource_id):
        if not any(config):
            return value
        rank = 1 if config[4:6] == b"en" else 2
        if best is None or rank < best[0]:
            best = (rank, value)
    return best[1] if best else None


def config_density(config):
    """The screen density a configuration is for, in dots per inch; 0 when it names none."""
    return struct.unpack_from("<H", config, 10)[0] if len(config) >= 12 else 0


def density_rank(density):
    """Sorts the densities a file comes in the way a list row wants them: the densest up to
    ICON_DENSITY first, then the least dense beyond it, and last those that name none."""
    if density in (0, 0xFFFE, 0xFFFF):
        return (2, 0)
    if density <= ICON_DENSITY:
        return (0, -density)
    return (1, density)


class Resources:
    """A resource table, read for the files and colours an app's icon is made of. read, given
    the path of a file inside the APK, returns its contents: a colour can be kept in a file
    of its own."""

    def __init__(self, table, read=None):
        self.table = table
        self.read = read
        self.strings, self.packages = table_parts(table)

    def values(self, resource_id):
        return resource_values(self.table, self.packages, resource_id)

    def file(self, resource_id, suffixes=IMAGE_SUFFIXES):
        """The path, inside the APK, of the file resource_id stands for, taken from the
        configuration that suits a list row best (see density_rank) among those whose file
        has one of suffixes; None when there is none. A reference is followed."""
        for _ in range(8):
            files = []
            reference = None
            for config, (data_type, data) in self.values(resource_id):
                if data_type == 0x03:
                    path = self.strings.get(data) or ""
                    if path.lower().endswith(suffixes):
                        files.append((density_rank(config_density(config)), path))
                elif data_type in (0x01, 0x07) and data and reference is None:
                    reference = data
            if files:
                return min(files)[1]
            if reference is None:
                return None
            resource_id = reference
        return None

    def color(self, resource_id, depth=0):
        """The colour resource_id stands for, as "#aarrggbb", or None. A reference is
        followed, and a colour described in a file of its own, a gradient or a selector, is
        read from there in its first colour."""
        for _ in range(8):
            value = resource_value(self.table, self.packages, resource_id)
            if value is None:
                return None
            data_type, data = value
            color = color_text(data_type, data)
            if color is not None:
                return color
            if data_type == 0x03:
                path = self.strings.get(data) or ""
                if path.endswith(".xml") and self.read is not None and depth < 4:
                    return document_color(self.read(path), self, depth + 1)
                return None
            if data_type not in (0x01, 0x07) or not data:
                return None
            resource_id = data
        return None


def document_color(document, resources, depth=0):
    """The first colour a colour described in XML comes in: of a gradient, its start colour
    or the colour of its first item; of a selector, the colour of its first item."""
    root = None
    for element, attributes in elements(document):
        values = {name: (data_type, data, text) for _, name, data_type, data, text in attributes}
        if root is None:
            root = element
            if root == "gradient":
                color = attribute_color(values, "startColor", resources, depth)
                if color:
                    return color
            elif root != "selector":
                return None
        elif element == "item":
            color = attribute_color(values, "color", resources, depth)
            if color:
                return color
    return None


def color_text(data_type, data):
    """A colour value as "#aarrggbb", or None for a value that is no colour. The four types
    tell how the colour was written; the data is the full colour whichever way."""
    if data_type in (0x1C, 0x1E):
        return "#%08x" % data
    if data_type in (0x1D, 0x1F):
        return "#ff%06x" % (data & 0xFFFFFF)
    return None


def attribute_number(values, name, resources, default=None):
    """The attribute name of an element as a number, or default. values maps the element's
    attribute names to (type, data, text). A dimension is taken by its number, whatever its
    unit; a reference is followed into the resource table."""
    if name not in values:
        return default
    data_type, data, text = values[name]
    for _ in range(8):
        if data_type == 0x04:
            return round(struct.unpack("<f", struct.pack("<I", data))[0], 4)
        if 0x10 <= data_type <= 0x1F and data_type not in (0x1C, 0x1D, 0x1E, 0x1F):
            return struct.unpack("<i", struct.pack("<I", data))[0]
        if data_type == 0x05:
            mantissa = data >> 8
            if mantissa & 0x800000:
                mantissa -= 1 << 24
            return round(mantissa * (1.0, 1 / 128, 1 / 32768, 1 / 8388608)[(data >> 4) & 3], 4)
        if data_type == 0x03:
            try:
                return float(text)
            except (TypeError, ValueError):
                return default
        if data_type not in (0x01, 0x07) or not data or resources is None:
            return default
        found = resource_value(resources.table, resources.packages, data)
        if found is None:
            return default
        data_type, data = found
        text = resources.strings.get(data) if data_type == 0x03 else None
    return default


def attribute_text(values, name, resources):
    """The attribute name of an element as text, or None; a reference is followed."""
    if name not in values:
        return None
    data_type, data, text = values[name]
    if data_type == 0x03:
        return text
    if data_type in (0x01, 0x07) and data and resources is not None:
        return resource_string(resources.table, data)
    return None


def attribute_color(values, name, resources, depth=0):
    """The attribute name of an element as "#aarrggbb", or None; a reference is followed."""
    if name not in values:
        return None
    data_type, data, _ = values[name]
    color = color_text(data_type, data)
    if color is None and data_type in (0x01, 0x07) and data and resources is not None:
        color = resources.color(data, depth)
    return color


# The attributes of a vector drawable's groups and paths that the app draws, by the names the
# app knows them by.
VECTOR_GROUP = ("rotation", "pivotX", "pivotY", "scaleX", "scaleY", "translateX", "translateY")
VECTOR_PATH = (("strokeWidth", "strokeWidth"), ("fillAlpha", "fillAlpha"), ("strokeAlpha", "strokeAlpha"),
               ("fillType", "fillType"), ("strokeLineCap", "cap"), ("strokeLineJoin", "join"))


def vector_description(document, resources):
    """How a vector drawable is drawn, from its binary XML: the size of its canvas, and its
    groups and paths as a tree of nodes the way the app draws them. None when the XML is no
    vector drawable. What the app does not draw is simplified: a gradient is drawn in its
    first colour, and a clip path clips the whole group it is in.

    A shape drawable, as the background of an adaptive icon often is, is drawn as a square
    of its colour, a gradient again in its first colour."""
    root = None
    group = None
    above = []
    path = None
    target = "fill"
    for element, attributes in elements(document, ends=True):
        if attributes is None:
            if element == "group" and above:
                group = above.pop()
            elif element == "path":
                path = None
            continue
        values = {name: (data_type, data, text) for _, name, data_type, data, text in attributes}
        if root is None:
            if element == "shape":
                path = {"path": "M0 0h1v1h-1z"}
                root = {"width": 1, "height": 1, "root": {"nodes": [path]}}
                continue
            if element != "vector":
                return None
            group = {"nodes": []}
            root = {
                "width": attribute_number(values, "viewportWidth", resources, 0),
                "height": attribute_number(values, "viewportHeight", resources, 0),
                "root": group,
            }
            if not root["width"] or not root["height"]:
                return None
        elif group is None:
            # Inside a shape: its colour, solid or the start of a gradient.
            color = attribute_color(values, "color" if element == "solid" else "startColor", resources)
            if color and element in ("solid", "gradient"):
                path.setdefault("fill", color)
        elif element == "group":
            node = {"nodes": []}
            for name in VECTOR_GROUP:
                value = attribute_number(values, name, resources)
                if value is not None:
                    node[name] = value
            group["nodes"].append(node)
            above.append(group)
            group = node
        elif element == "clip-path":
            data = attribute_text(values, "pathData", resources)
            if data:
                group["clip"] = data
        elif element == "path":
            data = attribute_text(values, "pathData", resources)
            if not data:
                continue
            path = {"path": data}
            for name, key in (("fillColor", "fill"), ("strokeColor", "stroke")):
                color = attribute_color(values, name, resources)
                if color:
                    path[key] = color
            for name, key in VECTOR_PATH:
                value = attribute_number(values, name, resources)
                if value is not None:
                    path[key] = value
            group["nodes"].append(path)
        elif element == "attr":
            target = "stroke" if (attribute_text(values, "name", None) or "").endswith("strokeColor") else "fill"
        elif element in ("gradient", "item") and path is not None:
            color = attribute_color(values, "startColor" if element == "gradient" else "color", resources)
            if color:
                path.setdefault(target, color)
    if root is not None and group is None and "fill" not in path:
        # A shape of no colour.
        return None
    return root


def entry_value(table, chunk, header_size, chunk_size, entry):
    """(type, data) of one entry of a type chunk, or None when the chunk has no simple value
    for it."""
    flags = table[chunk + 9]
    entry_count, entries_start = struct.unpack_from("<II", table, chunk + 12)
    offsets = chunk + header_size
    # The offsets must fit between the header and the entries of this very chunk. A count
    # that claims more would have every chunk read on into the ones after it, which takes a
    # crafted table of a few kilobytes a very long time.
    width = 2 if flags & 0x02 and not flags & 0x01 else 4
    if not header_size <= entries_start <= chunk_size or entry_count > (entries_start - header_size) // width:
        raise ManifestError("resource type out of range")
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
        # Each string decoded once, by where it lies: a hostile document can refer to a
        # string of tens of thousands of characters tens of thousands of times, through as
        # many indexes, and decoding it at every reference would cost billions of
        # characters. Keyed by position, the cache holds the pool's own content, and no
        # more: a pool whose strings, decoded, outgrow it has offsets into the middle of one
        # another's data, which aapt2 never writes, and the file is refused rather than
        # decoded on. A UTF-16 pool decodes to half its bytes in characters, a UTF-8 one to
        # at most as many.
        self.decoded = {}
        self.characters_left = chunk_size

    def get(self, index):
        if index < 0 or index >= self.count:
            return None
        offset, = struct.unpack_from("<i", self.data, self.chunk + self.header_size + index * 4)
        at = self.chunk + self.strings_start + offset
        if offset < 0 or at >= self.limit:
            return None
        if at in self.decoded:
            return self.decoded[at]
        text = self.decode(at)
        if text is not None:
            self.characters_left -= len(text)
            if self.characters_left < 0:
                raise ManifestError("string pool decodes to more than it holds")
        self.decoded[at] = text
        return text

    def decode(self, at):
        """The string that begins at position at, or None when it runs past the pool."""
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


def file_of_kind(data, suffix):
    """Whether data begins the way a file with the given suffix does: an image, or the
    description of a vector drawable."""
    if suffix == "png":
        return data[:8] == b"\x89PNG\r\n\x1a\n"
    if suffix == "webp":
        return data[:4] == b"RIFF" and data[8:12] == b"WEBP"
    if suffix in ("jpg", "jpeg"):
        return data[:2] == b"\xff\xd8"
    if suffix == "json":
        return data[:1] == b"{"
    return False


class IconStore:
    """The icons read from APKs: files in a directory, published at an address. A file is
    named after its package and its contents, so that the address of an icon changes when
    the icon does and an icon carried from run to run is written once.

    An icon is an image file, or a vector drawable described for the app to draw (see
    vector_description), kept as a JSON file. An adaptive icon is kept as its layers."""

    def __init__(self, directory, address):
        self.directory = directory
        self.address = address.rstrip("/")
        self.found = {}

    def get(self, package):
        """How the icon of package is published, as last read: the address of the icon; for
        an icon drawn in layers, {"background", "foreground"} with the address of each layer,
        the background possibly a colour as "#aarrggbb"; or None when the app has no icon the
        store can keep. An address ending in .json is a vector drawable to draw, see
        vector_description; any other is an image."""
        return self.found.get(package)

    def remember(self, package, icon):
        """Takes the icon of package as read on an earlier run, so that another file of the
        same release gets it without the release's first file being read again."""
        self.found[package] = icon

    def read(self, url, size, entries, table, resource_id, package):
        """Reads the icon that resource_id names in the resource table of the APK at url,
        whose zip directory is entries. An icon that cannot be made out costs the app its icon
        only. A network failure propagates, as it does for the manifest."""
        try:
            self.found[package] = self.extract(url, size, entries, table, resource_id, package)
        except (ManifestError, struct.error, IndexError, KeyError, ValueError, zlib.error,
                UnicodeDecodeError) as error:
            print("  no icon from %s: %s" % (url.rsplit("/", 1)[-1], error), file=sys.stderr)
            self.found[package] = None

    def extract(self, url, size, entries, table, resource_id, package):
        if table is None or not resource_id:
            return None
        resources = Resources(table, lambda path: read_entry(url, size, entries[path.encode()], MAX_ICON))
        path = resources.file(resource_id)
        if path is not None:
            return self.image(package, "", path, url, size, entries)
        described = resources.file(resource_id, (".xml",))
        if described is None:
            return None
        document = read_entry(url, size, entries[described.encode()], MAX_ICON)
        layers = adaptive_layers(document)
        if not layers:
            # An icon described in XML that is not drawn in layers: a vector, or nothing the
            # store can keep.
            return self.vector(package, "", document, resources)
        foreground = self.layer(package, "-foreground", layers.get("foreground"), resources, url, size, entries)
        if foreground is None:
            return None
        icon = {"foreground": foreground}
        background = layers.get("background")
        if background:
            kept = resources.color(background) \
                or self.layer(package, "-background", background, resources, url, size, entries)
            if kept:
                icon["background"] = kept
        return icon

    def layer(self, package, layer, resource_id, resources, url, size, entries):
        """One layer of an adaptive icon, kept as a file: the image resource_id stands for,
        or the vector it is drawn as. None when it is neither."""
        if not resource_id:
            return None
        path = resources.file(resource_id)
        if path is not None:
            return self.image(package, layer, path, url, size, entries)
        described = resources.file(resource_id, (".xml",))
        if described is None:
            return None
        return self.vector(package, layer, read_entry(url, size, entries[described.encode()], MAX_ICON), resources)

    def image(self, package, layer, path, url, size, entries):
        data = read_entry(url, size, entries[path.encode()], MAX_ICON)
        return self.keep(package, layer, path.rsplit(".", 1)[-1].lower(), data)

    def vector(self, package, layer, document, resources):
        drawn = vector_description(document, resources)
        if drawn is None:
            return None
        return self.keep(package, layer, "json", json.dumps(drawn, separators=(",", ":")).encode())

    def keep(self, package, layer, suffix, data):
        """Writes data as a file of the given kind (see file_of_kind) of package's icon and
        returns the address it is published at."""
        if not re.fullmatch(r"[A-Za-z0-9_.]+", package):
            raise ManifestError("package name %r is no file name" % package)
        if not file_of_kind(data, suffix):
            raise ManifestError("the icon is not the %s file its name says" % suffix)
        name = "%s%s-%s.%s" % (package, layer, hashlib.sha256(data).hexdigest()[:12], suffix)
        os.makedirs(self.directory, exist_ok=True)
        target = os.path.join(self.directory, name)
        if not os.path.exists(target):
            with open(target, "wb") as file:
                file.write(data)
        return "%s/%s" % (self.address, name)


def prune_icons(directory, folder="icons"):
    """Removes from the icons folder under directory every file none of the lists in
    directory refers to any more, and returns how many. An unreadable list keeps every file:
    better a few files too many than the icons of a list that is published after all."""
    icons = os.path.join(directory, folder)
    if not os.path.isdir(icons):
        return 0
    referred = set()

    def collect(value):
        if isinstance(value, dict):
            for key, inner in value.items():
                if key != "icon":
                    collect(inner)
                    continue
                for address in (inner.values() if isinstance(inner, dict) else [inner]):
                    if isinstance(address, str):
                        referred.add(address.rsplit("/", 1)[-1])
        elif isinstance(value, list):
            for inner in value:
                collect(inner)

    for name in os.listdir(directory):
        if not name.endswith(".json"):
            continue
        try:
            with open(os.path.join(directory, name), encoding="utf-8") as file:
                collect(json.load(file))
        except ValueError:
            print("%s unreadable, keeping every icon" % name, file=sys.stderr)
            return 0
    removed = 0
    for name in os.listdir(icons):
        if name not in referred:
            os.remove(os.path.join(icons, name))
            removed += 1
    return removed


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


def build_auto_apps(published, previous_auto, state, today, icons=None):
    """Builds the list of automatically found apps.

    state records, for each repository, the pushed_at it had when it was last examined and
    when that was. A repository that has not changed and is not due for another look is not
    asked about again: its previous entry, or its absence, still stands. Returns the entries
    and the new state. icons is passed on to build_app.
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
    requests_before = api_requests
    for repo in candidates:
        full_name = repo["full_name"]
        pushed_at = repo.get("pushed_at") or ""
        previous = previous_auto.get(full_name)
        looked = False
        if full_name not in examine or api_requests - requests_before >= AUTO_MAX_REQUESTS:
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
                    with_prerelease=False, icons=icons,
                    metadata_of=functools.partial(app_metadata, previous=previous),
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


def release_info(release, previous_apks, icons=None):
    """Describes one release and its usable APKs, or returns None when it has none. With
    icons, an IconStore, each file tells how its app's icon is published."""
    apks = []
    labels = {}
    for asset in apk_assets(release):
        known = previous_apks.get(asset["id"])
        if known and "label" in known and known.get("signerReader") == SIGNER_READER \
                and known.get("sdkReader") == SDK_READER and (icons is None or "icon" in known) \
                and (known.get("minSdk") is not None or "minSdkCodename" in known):
            # The asset id changes whenever a file is replaced, so a known id means the same
            # contents. Its name and address can still change, so those are never reused.
            # An entry written before names, signers, icons or the lowest Android version
            # were read lacks them, and is read again, as is one whose signer or lowest
            # Android version an older reader named (see SIGNER_READER and SDK_READER). One
            # written before codenames were kept has None for a codename, and is read again.
            lowest = known["minSdk"] if known.get("minSdkCodename") is None else known["minSdkCodename"]
            manifest = (known["packageName"], known["versionCode"], known["versionName"],
                        known["label"], known["signer"], lowest)
            icon = known.get("icon")
            # A file of the release added since is read for its name and icon only when
            # this one does not tell them, just as if this one had been read now.
            if icons is not None:
                icons.remember(manifest[0], icon)
        else:
            try:
                manifest = read_manifest(asset["browser_download_url"], asset["size"], labels, icons)
            except ManifestError as error:
                # The file is broken; leave it out. A network failure, on the other hand,
                # propagates so that the caller keeps what it knew about the app.
                print("  skipping %s: %s" % (asset["name"], error), file=sys.stderr)
                continue
            icon = icons.get(manifest[0]) if icons is not None else None
        lowest = manifest[5] if len(manifest) > 5 else None
        apk = {
            "id": asset["id"],
            "name": asset["name"],
            "url": asset["browser_download_url"],
            "size": asset["size"],
            "packageName": manifest[0],
            "versionCode": manifest[1],
            "versionName": manifest[2],
            "label": manifest[3],
            # SHA-256 of the certificate the file is signed with, as the catalogues tell it;
            # None when it cannot be told, and which reader told it.
            "signer": manifest[4] if len(manifest) > 4 else None,
            "signerReader": SIGNER_READER,
            # The lowest Android API level the file runs on, as its manifest says it, or the
            # codename of the preview of Android it names instead, which only that preview
            # runs. The app offers a device only files it can run and tells the version in
            # the details.
            "minSdk": lowest if isinstance(lowest, int) else None,
            "minSdkCodename": lowest if isinstance(lowest, str) else None,
            "sdkReader": SDK_READER,
        }
        if icons is not None:
            # See IconStore.get; None for an app whose icon could not be kept.
            apk["icon"] = icon
        apks.append(apk)
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


def preferred_locale(language, locales):
    """The one of locales to read language from, or None: the locale of the language's usual
    region (DEFAULT_REGIONS), failing that the language alone, failing that the first of its
    other regions."""
    for locale in ("%s-%s" % (language, DEFAULT_REGIONS[language]), language):
        if locale in locales:
            return locale
    return min((locale for locale in locales if locale.startswith(language + "-")), default=None)


def natural_order(path):
    """Sorts files by the number their name begins with, so that 2.png comes before 10.png,
    and then by name. Names that begin with no number come last."""
    name = path.rsplit("/", 1)[-1]
    number = re.match(r"\d+", name)
    return (number is None, int(number.group()) if number else 0, name)


def chosen_screenshots(paths):
    """The images among paths that are shown, in the order they are shown."""
    images = [path for path in paths if path.lower().endswith(IMAGE_SUFFIXES)]
    return sorted(images, key=natural_order)[:MAX_SCREENSHOTS]


def stamp_of(pushed_at):
    """The digits of a push's time, to mark an address with, or None for no time."""
    digits = re.sub(r"\D", "", pushed_at or "")
    return digits or None


def raw_address(full_name, branch, path, stamp=None):
    """The address a file on a branch of a repository is served at. The address names the
    branch, so it would stay the same when the file changed and a copy kept by it would never
    be fetched again; with a stamp, the push the file was last seen at, it changes with it."""
    address = "%s/%s/%s/%s" % (RAW_ADDRESS, full_name, urllib.parse.quote(branch, safe="/"),
                               urllib.parse.quote(path, safe="/"))
    return address + "?at=" + stamp if stamp else address


def metadata_from_paths(paths, full_name, default_branch, stamp=None):
    """The "metadata" of a repository's entry, given the paths of the files on its default
    branch: for each of LANGUAGES there is something for, the address of the long description,
    when there is one, and the addresses of the phone screenshots. Both come from one folder.
    The addresses carry the stamp, see raw_address."""
    descriptions = {}
    screenshots = {}
    for path in paths:
        if not path.startswith(METADATA_FOLDER):
            continue
        parts = path[len(METADATA_FOLDER):].split("/")
        if parts[1:] == ["full_description.txt"]:
            descriptions[parts[0]] = path
        elif len(parts) == 4 and parts[1:3] == ["images", "phoneScreenshots"]:
            screenshots.setdefault(parts[0], []).append(path)
    shown = {locale: chosen_screenshots(found) for locale, found in screenshots.items()}
    useful = set(descriptions) | {locale for locale, found in shown.items() if found}
    metadata = {}
    for language in LANGUAGES:
        locale = preferred_locale(language, useful)
        if locale is None:
            continue
        found = {}
        if locale in descriptions:
            found["description"] = raw_address(full_name, default_branch, descriptions[locale], stamp)
        found["screenshots"] = [raw_address(full_name, default_branch, path, stamp) for path in shown.get(locale, [])]
        metadata[language] = found
    return metadata


def fastlane_metadata(full_name, default_branch, stamp=None):
    """The "metadata" of a repository's entry (see metadata_from_paths), from the list of the
    files on its default branch, which takes one request. A list too long for GitHub to give
    whole is read as far as it goes."""
    try:
        tree = api("/repos/%s/git/trees/%s?recursive=1" % (full_name, urllib.parse.quote(default_branch, safe="")))
    except urllib.error.HTTPError as error:
        if error.code != 404:
            raise
        return {}
    paths = [item.get("path") or "" for item in tree.get("tree") or [] if item.get("type") == "blob"]
    return metadata_from_paths(paths, full_name, default_branch, stamp)


def app_metadata(repo, previous=None):
    """The "metadata" of a repository's entry, or None when its default branch is not known,
    so that the entry goes without and a later run looks it up. previous is the repository's
    entry from an earlier run, whose metadata stands while the repository is not pushed to,
    and while a lookup fails: the metadata is an extra, and a passing error must not cost the
    entry its release."""
    known = previous.get("metadata") if previous and "metadata" in previous else None
    if previous and known is not None and previous.get("pushedAt") == (repo.get("pushed_at") or ""):
        return known
    if not repo.get("default_branch"):
        return None
    try:
        return fastlane_metadata(repo["full_name"], repo["default_branch"], stamp_of(repo.get("pushed_at")))
    except (urllib.error.URLError, OSError, ValueError) as error:
        print("%s: metadata not read: %s" % (repo["full_name"], error), file=sys.stderr)
        return known


def build_app(repo, previous_apks, not_before=None, with_prerelease=True, list_releases=None, icons=None,
              metadata_of=None):
    """Returns the index entry of a repository, or None when it has nothing to install.

    With not_before, a repository whose newest full release was published earlier than that
    is given up on at once, before any of its APKs are examined. With icons, an IconStore,
    the icons of the apps are read and kept there. With metadata_of, a function given the
    repository such as app_metadata, what it returns is written under "metadata", unless None.

    The entry describes the newest full release. When the release at the top of the list is a
    prerelease that upgrades it, that is added under "beta" for users who have asked for beta
    versions. One that does not is noted under "skippedPrerelease", only so that its APKs are
    not examined again on the next run. A repository with nothing but a prerelease gets an
    entry marked "betaOnly", built from it. Without with_prerelease, prereleases are not
    looked at.

    list_releases gives the releases of a repository that is not on GitHub, newest first and
    described the way GitHub describes them.
    """
    full_name = repo["full_name"]
    if list_releases is not None:
        page = list_releases(repo)
    else:
        page = api("/repos/%s/releases?per_page=%d" % (full_name, PAGE_SIZE))
    releases = [r for r in page if not r.get("draft")]
    stable = next((r for r in releases if not r.get("prerelease")), None)
    if stable is None and list_releases is None and len(page) == PAGE_SIZE:
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

    stable_info = release_info(stable, previous_apks, icons) if stable else None
    beta_info = release_info(prerelease, previous_apks, icons) if prerelease else None
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
    metadata = metadata_of(repo) if metadata_of is not None else None
    if metadata is not None:
        app["metadata"] = metadata
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
    parser.add_argument("--previous", help="the previous index, to reuse what is known about unchanged "
                                           "APKs and repositories")
    parser.add_argument("--previous-auto", help="the previous list of automatically found apps")
    parser.add_argument("--state", help="what earlier runs learnt about automatically found repositories; "
                                        "read if present and rewritten")
    parser.add_argument("--output", required=True)
    parser.add_argument("--auto-output", required=True,
                        help="where the automatically found apps go; they are many and wanted by few, "
                             "so the app downloads them separately and only on request")
    parser.add_argument("--icons-dir", help="where the icons read from the APKs are kept as files; "
                                            "the files of the previous run are expected to be there already")
    parser.add_argument("--icons-url", help="the address the files of --icons-dir are published at")
    arguments = parser.parse_args()
    icons = IconStore(arguments.icons_dir, arguments.icons_url) if arguments.icons_dir and arguments.icons_url else None

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
            app = build_app(repo, known_apks(previous), icons=icons,
                            metadata_of=functools.partial(app_metadata, previous=previous))
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
            {app["fullName"] for app in apps}, previous_auto, state, today, icons
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
