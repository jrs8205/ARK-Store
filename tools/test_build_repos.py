"""Tests for the builder of the other catalogues' lists. Run with: python3 -m unittest discover tools"""

import hashlib
import http.client
import json
import os
import tempfile
import unittest
import urllib.error
from unittest import mock

import build_repos

SHA = "ab" * 32


def version(code, nativecode=None, channels=None, name=None, anti=None, min_sdk=23, signer="cd" * 32):
    return {
        "added": 1790000000000,
        "file": {"name": "/org.example_%d.apk" % code, "sha256": SHA, "size": 1000 + code},
        "manifest": {
            "versionCode": code,
            "versionName": name or "1.%d" % code,
            "nativecode": nativecode or [],
            "usesSdk": {"minSdkVersion": min_sdk},
            "signer": {"sha256": [signer]},
        },
        "releaseChannels": channels or [],
        "antiFeatures": anti or {},
        "whatsNew": {"en-US": "Fixes"},
    }


def package(versions, **metadata):
    base = {
        "name": {"en-US": "Example"},
        "summary": {"fi": "Esimerkki", "en-US": "An  example\napp"},
        "license": "GPL-3.0-only",
        "categories": ["Internet", "Email"],
        "sourceCode": "https://github.com/owner/app",
        "authorName": "Owner",
    }
    base.update(metadata)
    return {"metadata": base, "versions": {str(v["manifest"]["versionCode"]): v for v in versions}}


def index(packages):
    return {
        "repo": {"antiFeatures": {"Tracking": {"name": {"en-US": "Tracking"}},
                                  "NonFreeNet": {"name": {"en-US": "Non-Free Network Services"}}}},
        "packages": packages,
    }


def build(entry, source="fdroid"):
    catalogue = build_repos.build_catalogue(source, index({"org.example": entry}), 5, 1790000000)
    return catalogue["apps"][0] if catalogue["apps"] else None


class BuildAppTest(unittest.TestCase):
    def test_app_is_described_the_way_the_store_reads_it(self):
        app = build(package([version(7)]))
        self.assertEqual(app["fullName"], "fdroid:org.example")
        self.assertEqual(app["source"], "fdroid")
        self.assertEqual(app["description"], "An example app")
        self.assertEqual(app["author"], "Owner")
        self.assertEqual(app["license"], "GPL-3.0-only")
        self.assertEqual(app["repoUrl"], "https://github.com/owner/app")
        self.assertEqual(app["releaseUrl"], "https://f-droid.org/packages/org.example/")
        self.assertEqual(app["tag"], "1.7")
        self.assertEqual(app["releaseNotes"], "Fixes")
        apk = app["apks"][0]
        self.assertEqual(apk["url"], "https://f-droid.org/repo/org.example_7.apk")
        self.assertEqual(apk["name"], "org.example_7.apk")
        self.assertEqual((apk["packageName"], apk["versionCode"], apk["label"]), ("org.example", 7, "Example"))
        self.assertEqual((apk["sha256"], apk["signer"], apk["minSdk"]), (SHA, "cd" * 32, 23))
        self.assertEqual(apk["id"], int(SHA[:13], 16))

    def test_narrow_category_is_preferred_to_a_broad_one(self):
        self.assertEqual(build(package([version(1)]))["topics"], ["arkstore-communication"])
        broad = build(package([version(1)], categories=["Internet"]))
        self.assertEqual(broad["topics"], ["arkstore-tools"])

    def test_unknown_category_is_guessed_or_left_out(self):
        guessed = build(package([version(1)], categories=["Chess Clubs"]))
        self.assertEqual(guessed["topics"], ["arkstore-games"])
        nothing = build(package([version(1)], categories=["Odd"], summary={"en-US": "Thing"}))
        self.assertEqual(nothing["topics"], [])

    def test_newest_version_of_each_architecture_is_offered(self):
        app = build(package([
            version(10, ["arm64-v8a"]), version(9, ["armeabi-v7a"]),
            version(6, ["arm64-v8a"]), version(5, ["armeabi-v7a"]),
        ]))
        self.assertEqual([(a["versionCode"], a["abis"]) for a in app["apks"]],
                         [(10, ["arm64-v8a"]), (9, ["armeabi-v7a"])])

    def test_older_version_for_an_older_android_is_offered_too(self):
        app = build(package([version(9, min_sdk=35), version(8), version(7), version(6, min_sdk=21)]))
        self.assertEqual([(a["versionCode"], a["minSdk"]) for a in app["apks"]], [(9, 35), (8, 23), (6, 21)])

    def test_version_signed_with_another_key_is_offered_too(self):
        app = build(package([version(9, signer="aa" * 32), version(8), version(7)]))
        self.assertEqual([(a["versionCode"], a["signer"]) for a in app["apks"]],
                         [(9, "aa" * 32), (8, "cd" * 32)])

    def test_beta_channel_is_left_out(self):
        app = build(package([version(8, channels=["Beta"]), version(7)]))
        self.assertEqual([a["versionCode"] for a in app["apks"]], [7])
        self.assertIsNone(build(package([version(8, channels=["Beta"])])))

    def test_anti_features_of_the_newest_version_are_named(self):
        app = build(package([version(2, anti={"NonFreeNet": {}, "Tracking": {}}), version(1)]))
        self.assertEqual(app["antiFeatures"], ["Non-Free Network Services", "Tracking"])

    def test_each_file_carries_the_warnings_of_its_own_version(self):
        app = build(package([version(20, ["arm64-v8a"]), version(19, ["armeabi-v7a"], anti={"Tracking": {}})]))
        self.assertEqual([a["antiFeatures"] for a in app["apks"]], [[], ["Tracking"]])
        self.assertEqual(app["antiFeatures"], [])

    def test_oddly_described_file_is_left_out(self):
        for change in ({"size": {"bytes": 1}}, {"size": "1000"}, {"size": -1}, {"sha256": "ab"}, {"name": "x.apk"}):
            odd = version(10, ["arm64-v8a"])
            odd["file"].update(change)
            app = build(package([odd, version(9, ["armeabi-v7a"])]))
            self.assertEqual([a["versionCode"] for a in app["apks"]], [9])
        odd = version(10)
        odd["manifest"]["usesSdk"] = {"minSdkVersion": "23"}
        self.assertIsNone(build(package([odd])))
        odd = version(10)
        odd["manifest"]["versionCode"] = "10"
        self.assertIsNone(build(package([odd, version(9)])))

    def test_app_without_a_name_or_license_is_left_out(self):
        self.assertIsNone(build(package([version(1)], name={})))
        self.assertIsNone(build(package([version(1)], license=None)))

    def test_several_signers_are_not_guessed_between(self):
        odd = version(1)
        odd["manifest"]["signer"] = {"sha256": ["aa" * 32, "bb" * 32]}
        self.assertIsNone(build(package([odd]))["apks"][0]["signer"])

    def test_malformed_package_does_not_cost_the_others(self):
        packages = {"org.broken": {"metadata": {"name": {"en-US": "Broken"}, "license": "MIT"},
                                   "versions": {"1": {"manifest": {}}}},
                    "org.example": package([version(1)])}
        catalogue = build_repos.build_catalogue("izzy", index(packages), 5, 1790000000)
        self.assertEqual([app["fullName"] for app in catalogue["apps"]], ["izzy:org.example"])


class UpdateTest(unittest.TestCase):
    def run_update(self, responses, previous=None):
        """Runs update with the catalogue answering responses, a map from the end of an
        address to bytes or an exception. Returns what was written and the addresses asked."""
        asked = []

        def fetch(url, limit):
            asked.append(url)
            answer = next(value for key, value in responses.items() if url.endswith(key))
            if isinstance(answer, Exception):
                raise answer
            return answer

        with tempfile.TemporaryDirectory() as directory:
            before = None
            if previous is not None:
                before = os.path.join(directory, "previous.json")
                with open(before, "w", encoding="utf-8") as file:
                    json.dump(previous, file)
            output = os.path.join(directory, "out.json")
            with mock.patch.object(build_repos, "fetch", side_effect=fetch):
                build_repos.update("fdroid", before, output, 1790000000)
            with open(output, encoding="utf-8") as file:
                return json.load(file), asked

    @staticmethod
    def responses(timestamp=5, checksum=None):
        data = json.dumps(index({"org.example": package([version(3)])})).encode()
        entry = {"timestamp": timestamp,
                 "index": {"name": "/index-v2.json", "sha256": checksum or hashlib.sha256(data).hexdigest()}}
        return {"/entry.json": json.dumps(entry).encode(), "/index-v2.json": data}

    PREVIOUS = {"version": 1, "source": "fdroid", "timestamp": 5, "apps": [{"fullName": "fdroid:old"}]}

    def test_changed_catalogue_is_rebuilt(self):
        written, _ = self.run_update(self.responses(timestamp=6), self.PREVIOUS)
        self.assertEqual(written["timestamp"], 6)
        self.assertEqual([app["fullName"] for app in written["apps"]], ["fdroid:org.example"])

    def test_unchanged_catalogue_is_not_downloaded_again(self):
        written, asked = self.run_update(self.responses(timestamp=5), self.PREVIOUS)
        self.assertEqual(written, self.PREVIOUS)
        self.assertEqual(len(asked), 1)

    def test_index_that_does_not_match_its_checksum_is_refused(self):
        written, _ = self.run_update(self.responses(timestamp=6, checksum="00" * 32), self.PREVIOUS)
        self.assertEqual(written, self.PREVIOUS)

    def test_unreachable_catalogue_keeps_the_previous_list(self):
        written, _ = self.run_update({"/entry.json": urllib.error.URLError("down")}, self.PREVIOUS)
        self.assertEqual(written, self.PREVIOUS)

    def test_unforeseen_error_keeps_the_previous_list_too(self):
        written, _ = self.run_update({"/entry.json": http.client.IncompleteRead(b"")}, self.PREVIOUS)
        self.assertEqual(written, self.PREVIOUS)
        odd = self.responses(timestamp=6, checksum=hashlib.sha256(b"[]").hexdigest())
        odd["/index-v2.json"] = b"[]"
        written, _ = self.run_update(odd, self.PREVIOUS)
        self.assertEqual(written, self.PREVIOUS)

    def test_catalogue_that_describes_itself_oddly_is_still_read(self):
        data = json.dumps({"repo": [], "packages": {"org.example": package([version(3)])}}).encode()
        entry = {"timestamp": 6, "index": {"name": "/index-v2.json", "sha256": hashlib.sha256(data).hexdigest()}}
        written, _ = self.run_update({"/entry.json": json.dumps(entry).encode(), "/index-v2.json": data},
                                     self.PREVIOUS)
        self.assertEqual([app["fullName"] for app in written["apps"]], ["fdroid:org.example"])

    def test_unreachable_catalogue_never_read_gets_an_empty_list(self):
        written, _ = self.run_update({"/entry.json": urllib.error.URLError("down")})
        self.assertEqual((written["apps"], written["timestamp"]), ([], 0))


if __name__ == "__main__":
    unittest.main()
