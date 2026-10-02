#!/usr/bin/env python3
"""Builds the lists of apps offered by other open-source app catalogues.

F-Droid and IzzyOnDroid publish their catalogues in the same format: a small entry file that
names the full index and gives its checksum, and the index itself, tens of megabytes of JSON
describing every app and version. For each catalogue this script boils the index down to one
small file holding what the store shows and needs for installing, which the app downloads
only when the user has turned that catalogue on.

These lists are an extra beside the store's own index, which is built first and published
in the same run. Nothing that goes wrong here may cost that its update, so a catalogue that
cannot be read keeps the list it had.

Only the standard library is used.
"""

import argparse
import hashlib
import json
import os
import shutil
import sys
import time
import urllib.request

from build_index import guess_category, write_json

USER_AGENT = "ARK-Store-index"
MAX_ENTRY = 1024 * 1024
MAX_INDEX = 256 * 1024 * 1024
MAX_SUMMARY = 200
MAX_NOTES = 500
# Apps built for one CPU architecture at a time have a version for each; this many of an
# app's newest versions are looked at to find them all.
MAX_VERSIONS = 12

CATALOGUES = {
    "fdroid": {
        "address": "https://f-droid.org/repo",
        "page": "https://f-droid.org/packages/%s/",
    },
    "izzy": {
        "address": "https://apt.izzysoft.de/fdroid/repo",
        "page": "https://apt.izzysoft.de/fdroid/index/apk/%s",
    },
}

# The store's category for each category of the catalogues. One that is not listed here is
# guessed from its words, like the category of an automatically found app.
CATEGORIES = {
    "games": "Action Game, Board Game, Card Game, Casual Game, Dice, Educational Game, Emulator, "
             "Game Helper, Games, Party Game, Platformer Game, Puzzle Game, Role-Playing Game, "
             "Shooter Game, Sport Game, Strategy Game, Visual Novel, Word Game",
    "finance": "Finance Manager, Market & Price, Money, Wallet",
    "health": "Diet, Health Manager, Medication, Meditation, Mental Health, Sports & Health, Workout",
    "travel": "Location Tracker & Sharer, Navigation, Public Transport",
    "news": "News, Weather",
    "communication": "Contact, Email, Forum, Messaging, Phone & SMS, Social Network, "
                     "Voice & Video Chat",
    "personalization": "Icon Pack, Keyboard & IME, Launcher, Theming, Wallpaper",
    "media": "Ambient Sound, Audiobook, Camera, Cast, Draw, Ebook Reader, Gallery, Graphics, "
             "Local Media Player, Lyrics, Multimedia, Music Practice Tool, Online Media Player, "
             "Podcast, Radio, Reading, Recorder, Remote Controller",
    "system": "App Manager, App Store & Updater, Battery, DNS & Hosts, File Manager, Firewall, "
              "Notification, System, Volume, Xposed",
    "education": "Science & Education, Translation & Dictionary",
    "productivity": "Alarm Clock, Bookmark, Calendar & Agenda, Clock, Habit Tracker, Inventory, "
                    "Note, OCR, Office, Recipe Manager, Schedule, Shopping List, Stopwatch, Task, "
                    "Text Editor, Time, Time Tracker, Timer, Writing",
    "tools": "Automation, Browser, Calculator, Cloud Storage & File Sync, Code & Forge, "
             "Connectivity, Development, Download, File Encryption & Vault, File Transfer, "
             "Flashlight, Internet, Network Analyzer, Pass Wallet, Password & 2FA, Push, "
             "Remote Access, Security, Speech Recognizer, Text Encryption, Text to Speech, "
             "Unit Convertor, VPN & Proxy",
}
CATEGORY_OF = {
    name.strip(): category for category, names in CATEGORIES.items() for name in names.split(",")
}
# The broad categories say least, so a narrower one of the same app is preferred to them.
BROAD = {"Connectivity", "Development", "Internet", "Multimedia", "Reading", "Security", "System", "Writing"}


class CatalogueError(Exception):
    """The catalogue could not be had or does not look the way it should."""


def fetch(url, limit):
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=120) as response:
        data = response.read(limit + 1)
    if len(data) > limit:
        raise CatalogueError("%s is too large" % url)
    return data


def localized(value):
    """The English text of a field given in several languages, or whichever comes first."""
    if not isinstance(value, dict) or not value:
        return ""
    for language in ("en-US", "en", "en-GB"):
        if value.get(language):
            return value[language]
    return next((text for text in value.values() if text), "")


def tidy(text, limit):
    return " ".join(str(text or "").split())[:limit]


def category_of(names, summary):
    """The store's category for an app with the catalogue categories names."""
    ordered = [name for name in names if name not in BROAD] + [name for name in names if name in BROAD]
    for name in ordered:
        if name in CATEGORY_OF:
            return CATEGORY_OF[name]
    return guess_category([name.lower().replace(" ", "-") for name in names], summary)


def whole(value):
    """value, which has to be a whole number that is not negative."""
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        raise ValueError("not a whole number: %r" % (value,))
    return value


def min_sdk(manifest):
    return whole((manifest.get("usesSdk") or {}).get("minSdkVersion", 1))


def signers(manifest):
    return tuple((manifest.get("signer") or {}).get("sha256") or [])


def offered_versions(versions):
    """The versions of an app worth offering, newest first. Versions of a beta channel are
    left out.

    Which file a device can take depends on its CPU architecture, on its Android version
    and, once the app is installed, on the key it was signed with. So for each set of
    architectures and each signing key the newest version is offered, and besides it every
    older one that runs on an older Android than the newer ones do."""
    stable = [v for v in versions.values() if not v.get("releaseChannels")]
    stable.sort(key=lambda v: -whole(v["manifest"]["versionCode"]))
    lowest = {}
    chosen = []
    for version in stable[:MAX_VERSIONS]:
        manifest = version["manifest"]
        kind = (tuple(sorted(manifest.get("nativecode") or [])), signers(manifest))
        runs_from = min_sdk(manifest)
        if kind not in lowest or runs_from < lowest[kind]:
            lowest[kind] = runs_from
            chosen.append(version)
    return chosen


def build_app(source, address, page, package, entry, anti_feature_names):
    """The store's entry for one package of a catalogue, or None when it has nothing to
    install or says too little about itself."""
    metadata = entry.get("metadata") or {}
    versions = offered_versions(entry.get("versions") or {})
    name = tidy(localized(metadata.get("name")), 60)
    license_name = metadata.get("license")
    if not versions or not name or not license_name:
        return None
    apks = []
    for version in versions:
        try:
            apks.append(build_apk(address, package, name, version, anti_feature_names))
        except (KeyError, TypeError, ValueError, AttributeError) as error:
            # The app reads every file of an entry, so one that is described oddly is left
            # out rather than passed on.
            print("%s: skipping a file of %s: %r" % (source, package, error), file=sys.stderr)
    if not apks:
        return None
    newest = versions[0]
    summary = tidy(localized(metadata.get("summary")), MAX_SUMMARY)
    category = category_of(metadata.get("categories") or [], summary)
    return {
        "fullName": "%s:%s" % (source, package),
        "source": source,
        "description": summary,
        "author": tidy(metadata.get("authorName"), 60),
        "topics": ["arkstore-" + category] if category else [],
        "license": license_name,
        "repoUrl": metadata.get("sourceCode") or metadata.get("webSite") or page % package,
        "tag": newest["manifest"].get("versionName") or str(newest["manifest"]["versionCode"]),
        "releaseNotes": localized(newest.get("whatsNew"))[:MAX_NOTES],
        "releaseUrl": page % package,
        "publishedAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(newest.get("added", 0) / 1000)),
        # What the newest version is warned about, for versions of the store that do not look
        # at the file they picked.
        "antiFeatures": apks[0]["antiFeatures"],
        "apks": apks,
    }


def build_apk(address, package, label, version, anti_feature_names):
    """The store's description of one version's file. Raises an error for a file that is not
    described the way the store needs it."""
    manifest = version["manifest"]
    file = version["file"]
    name = file["name"]
    checksum = file["sha256"]
    signed_by = signers(manifest)
    abis = sorted(manifest.get("nativecode") or [])
    version_name = manifest.get("versionName")
    if not isinstance(name, str) or not name.startswith("/"):
        raise ValueError("odd file name")
    if not isinstance(checksum, str) or len(checksum) != 64:
        raise ValueError("odd checksum")
    if not all(isinstance(text, str) for text in signed_by + tuple(abis)):
        raise ValueError("odd signer or architecture")
    if version_name is not None and not isinstance(version_name, str):
        raise ValueError("odd version name")
    return {
        # A number that stands for this very file, the way an asset id does on GitHub.
        "id": int(checksum[:13], 16),
        "name": name[1:],
        "url": address + name,
        "size": whole(file["size"]),
        "packageName": package,
        "versionCode": whole(manifest["versionCode"]),
        "versionName": version_name,
        "label": label,
        "sha256": checksum,
        "signer": signed_by[0] if len(signed_by) == 1 else None,
        "abis": abis,
        "minSdk": min_sdk(manifest),
        # What this version is warned about. Versions differ in it, and a device is offered
        # the one that suits it, which need not be the newest.
        "antiFeatures": sorted(
            anti_feature_names.get(key, key) for key in (version.get("antiFeatures") or {})
        ),
    }


def build_catalogue(source, index, timestamp, now):
    """The file the app downloads for one catalogue, from its full index."""
    address = CATALOGUES[source]["address"]
    page = CATALOGUES[source]["page"]
    described = (index.get("repo") or {}).get("antiFeatures") or {}
    anti_feature_names = {
        key: localized((value or {}).get("name")) or key for key, value in described.items()
    }
    apps = []
    for package, entry in (index.get("packages") or {}).items():
        try:
            app = build_app(source, address, page, package, entry, anti_feature_names)
        except (KeyError, TypeError, ValueError, AttributeError) as error:
            print("%s: skipping %s: %r" % (source, package, error), file=sys.stderr)
            continue
        if app:
            apps.append(app)
    apps.sort(key=lambda app: app["fullName"].lower())
    return {"version": 1, "source": source, "generatedAt": int(now * 1000), "timestamp": timestamp, "apps": apps}


def read_timestamp(path):
    """The catalogue timestamp a file written by an earlier run was built from, or None."""
    try:
        with open(path, encoding="utf-8") as file:
            loaded = json.load(file)
        return loaded["timestamp"] if loaded.get("version") == 1 and loaded.get("apps") else None
    except (OSError, ValueError, KeyError, TypeError, AttributeError):
        return None


def update(source, previous, output, now):
    """Writes output for one catalogue. The index is downloaded only when the catalogue has
    changed since previous was built; a catalogue that cannot be read keeps its previous
    file, and one that has never been read gets an empty list."""
    address = CATALOGUES[source]["address"]
    try:
        entry = json.loads(fetch(address + "/entry.json", MAX_ENTRY))
        timestamp = entry["timestamp"]
        if previous and read_timestamp(previous) == timestamp:
            shutil.copyfile(previous, output)
            print("%s: unchanged" % source)
            return
        described = entry["index"]
        data = fetch(address + described["name"], MAX_INDEX)
        if hashlib.sha256(data).hexdigest() != described["sha256"]:
            raise CatalogueError("index does not match its checksum")
        catalogue = build_catalogue(source, json.loads(data), timestamp, now)
        if not catalogue["apps"]:
            raise CatalogueError("index lists no apps")
        write_json(output, catalogue, ensure_ascii=False)
        print("%s: %d apps" % (source, len(catalogue["apps"])))
    except Exception as error:  # pylint: disable=broad-except
        print("%s: %r" % (source, error), file=sys.stderr)
        if previous and os.path.exists(previous):
            shutil.copyfile(previous, output)
        else:
            write_json(output, {"version": 1, "source": source, "generatedAt": int(now * 1000),
                                "timestamp": 0, "apps": []})


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--previous-dir", help="where the files written by the previous run are")
    parser.add_argument("--output-dir", required=True)
    arguments = parser.parse_args()
    os.makedirs(arguments.output_dir, exist_ok=True)
    now = time.time()
    for source in CATALOGUES:
        name = source + ".json"
        previous = os.path.join(arguments.previous_dir, name) if arguments.previous_dir else None
        update(source, previous, os.path.join(arguments.output_dir, name), now)


if __name__ == "__main__":
    main()
