"""Tests for the builder of the other catalogues' lists. Run with: python3 -m unittest discover tools"""

import hashlib
import http.client
import json
import os
import tempfile
import unittest
import urllib.error
from unittest import mock

import build_index
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
    def test_label_is_localized_metadata_not_the_apk_default_name(self):
        for source in ("fdroid", "izzy"):
            with self.subTest(source=source):
                app = build(package([version(7)], name={"fi": "Laskin", "en-US": "Calculator"}), source)
                self.assertEqual(app["source"], source)
                self.assertEqual(app["apks"][0]["label"], "Calculator")

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

    def test_icon_is_the_catalogue_file_for_the_language_read(self):
        described = {"fi": {"name": "/org.example/fi/icon.png", "sha256": SHA, "size": 1},
                     "en-US": {"name": "/org.example/en-US/icon.png", "sha256": SHA, "size": 1}}
        app = build(package([version(1)], icon=described))
        self.assertEqual(app["icon"], "https://f-droid.org/repo/org.example/en-US/icon.png")
        self.assertIsNone(build(package([version(1)]))["icon"])
        self.assertIsNone(build(package([version(1)], icon={"en-US": {"name": "icon.png"}}))["icon"])
        self.assertIsNone(build(package([version(1)], icon={"en-US": "odd"}))["icon"])

    def test_phone_screenshots_are_given_for_each_language(self):
        def files(locale, *names):
            return [{"name": "/org.example/%s/phoneScreenshots/%s" % (locale, name), "sha256": SHA, "size": 1}
                    for name in names]

        screenshots = {
            "phone": {"en-GB": files("en-GB", "1.png"), "en-US": files("en-US", "10.png", "my shot.png", "2.png"),
                      "fi": files("fi", "1.jpg", "2.gif"), "de-DE": files("de-DE", "1.png")},
            "tenInch": {"fi-FI": [{"name": "/org.example/fi-FI/tenInchScreenshots/1.png"}]},
        }
        for source, address in (("fdroid", "https://f-droid.org/repo"),
                                ("izzy", "https://apt.izzysoft.de/fdroid/repo")):
            with self.subTest(source=source):
                app = build(package([version(1)], summary={"en-US": "An example app"}, screenshots=screenshots), source)
                shots = address + "/org.example/%s/phoneScreenshots/"
                # The file's digest marks the address, so that a replaced file is fetched anew.
                stamp = "?v=" + SHA[:12]
                self.assertEqual(app["metadata"], {
                    "en": {"screenshots": [shots % "en-US" + "2.png" + stamp, shots % "en-US" + "10.png" + stamp,
                                           shots % "en-US" + "my%20shot.png" + stamp]},
                    "fi": {"screenshots": [shots % "fi" + "1.jpg" + stamp]},
                })

    def test_screenshots_are_capped(self):
        names = [{"name": "/org.example/en-US/phoneScreenshots/%d.png" % number} for number in range(12, 0, -1)]
        app = build(package([version(1)], screenshots={"phone": {"en-US": names}}))
        self.assertEqual([address.rsplit("/", 1)[1] for address in app["metadata"]["en"]["screenshots"]],
                         ["%d.png" % number for number in range(1, build_index.MAX_SCREENSHOTS + 1)])

    def test_app_without_screenshots_or_translated_summary_has_empty_metadata(self):
        english = {"en-US": "An example app"}
        self.assertEqual(build(package([version(1)], summary=english))["metadata"], {})
        for odd in ("odd", {"phone": []}, {"phone": {"en-US": "odd"}}, {"phone": {"en-US": [{"name": "1.png"}]}},
                    {"phone": {"en-US": ["odd", {"name": 5}]}}):
            with self.subTest(screenshots=odd):
                self.assertEqual(build(package([version(1)], summary=english, screenshots=odd))["metadata"], {})

    def test_summary_is_given_in_each_language_it_differs_from_the_description_in(self):
        app = build(package([version(1)]))
        self.assertEqual(app["description"], "An example app")
        # The English summary is the description already, so it is not repeated.
        self.assertEqual(app["metadata"], {"fi": {"summary": "Esimerkki"}})

    def test_summary_is_one_line_within_the_limit_and_comes_from_the_usual_region_first(self):
        summary = {"fi-FI": " Suomen\n\n alue ", "fi": "Kieli", "en-US": "English", "en-GB": "British"}
        app = build(package([version(1)], summary=summary))
        self.assertEqual(app["metadata"], {"fi": {"summary": "Suomen alue"}})
        long = {"en-US": "x", "fi": "y" * 300}
        self.assertEqual(build(package([version(1)], summary=long))["metadata"]["fi"]["summary"],
                         "y" * build_repos.MAX_SUMMARY)

    def test_summary_and_screenshots_are_chosen_from_their_own_locales(self):
        shots = [{"name": "/org.example/fi-FI/phoneScreenshots/1.png", "sha256": SHA, "size": 1}]
        app = build(package([version(1)], summary={"fi": "Esimerkki", "en-US": "English"},
                            screenshots={"phone": {"fi-FI": shots}}))
        self.assertEqual(app["metadata"], {"fi": {
            "summary": "Esimerkki",
            "screenshots": ["https://f-droid.org/repo/org.example/fi-FI/phoneScreenshots/1.png?v=" + SHA[:12]],
        }})

    def test_odd_summaries_are_left_out(self):
        for odd in ({"en-US": "English", "fi": 5}, {"en-US": "English", "fi": ""}, {"en-US": "English", "fi": None}):
            with self.subTest(summary=odd):
                self.assertEqual(build(package([version(1)], summary=odd))["metadata"], {})

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

    PREVIOUS = {"version": 1, "source": "fdroid", "timestamp": 5,
                "apps": [{"fullName": "fdroid:old", "icon": None, "metadata": {}}]}

    def test_changed_catalogue_is_rebuilt(self):
        written, _ = self.run_update(self.responses(timestamp=6), self.PREVIOUS)
        self.assertEqual(written["timestamp"], 6)
        self.assertEqual([app["fullName"] for app in written["apps"]], ["fdroid:org.example"])

    def test_list_written_before_icons_is_rebuilt_once(self):
        before_icons = dict(self.PREVIOUS, apps=[{"fullName": "fdroid:old"}])
        written, _ = self.run_update(self.responses(timestamp=5), before_icons)
        self.assertEqual([app["fullName"] for app in written["apps"]], ["fdroid:org.example"])
        self.assertIn("icon", written["apps"][0])

    def test_list_written_before_screenshots_is_rebuilt_once(self):
        before_screenshots = dict(self.PREVIOUS, apps=[{"fullName": "fdroid:old", "icon": None}])
        written, _ = self.run_update(self.responses(timestamp=5), before_screenshots)
        self.assertEqual([app["fullName"] for app in written["apps"]], ["fdroid:org.example"])
        self.assertIn("metadata", written["apps"][0])
        _, asked = self.run_update(self.responses(timestamp=5), written)
        self.assertEqual(len(asked), 1)

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
