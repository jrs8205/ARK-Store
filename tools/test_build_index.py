"""Tests for the index builder. Run with: python3 -m unittest discover tools"""

import json
import os
import tempfile
import unittest
import urllib.error
import zlib
from unittest import mock

import build_index


def deflate(data):
    compressor = zlib.compressobj(9, zlib.DEFLATED, -15)
    return compressor.compress(data) + compressor.flush()


REPO = {
    "full_name": "owner/app",
    "html_url": "https://github.com/owner/app",
    "license": {"spdx_id": "MIT"},
    "pushed_at": "2026-01-01T00:00:00Z",
    "stargazers_count": 3,
    "topics": ["arkstore"],
}


def release(asset_name="app.apk", asset_id=7):
    return [{
        "tag_name": "v1",
        "assets": [{
            "id": asset_id,
            "name": asset_name,
            "browser_download_url": "https://example.invalid/" + asset_name,
            "size": 100,
            "download_count": 5,
        }],
    }]


class InflateTest(unittest.TestCase):
    def test_returns_data_within_the_limit(self):
        self.assertEqual(build_index.inflate(deflate(b"abc" * 100), 1000), b"abc" * 100)

    def test_stops_a_small_input_that_expands_past_the_limit(self):
        bomb = deflate(b"\0" * (8 * 1024 * 1024))
        self.assertLess(len(bomb), 16 * 1024)
        with self.assertRaises(build_index.ManifestError):
            build_index.inflate(bomb, build_index.MAX_MANIFEST)


class BuildAppTest(unittest.TestCase):
    def test_renamed_asset_keeps_manifest_but_takes_the_new_address(self):
        previous = {7: {
            "id": 7, "name": "old.apk", "url": "https://example.invalid/old.apk", "size": 100,
            "packageName": "org.example", "versionCode": 4, "versionName": "1.4",
        }}
        with mock.patch.object(build_index, "api", return_value=release("new.apk")), \
                mock.patch.object(build_index, "read_manifest") as read_manifest:
            app = build_index.build_app(REPO, previous)
        read_manifest.assert_not_called()
        apk = app["apks"][0]
        self.assertEqual(apk["name"], "new.apk")
        self.assertEqual(apk["url"], "https://example.invalid/new.apk")
        self.assertEqual((apk["packageName"], apk["versionCode"]), ("org.example", 4))

    def test_malformed_apk_is_left_out(self):
        with mock.patch.object(build_index, "api", return_value=release()), \
                mock.patch.object(build_index, "read_manifest",
                                  side_effect=build_index.ManifestError("bad")):
            self.assertIsNone(build_index.build_app(REPO, {}))

    def test_network_failure_is_not_mistaken_for_a_missing_apk(self):
        with mock.patch.object(build_index, "api", return_value=release()), \
                mock.patch.object(build_index, "read_manifest",
                                  side_effect=urllib.error.URLError("down")):
            with self.assertRaises(urllib.error.URLError):
                build_index.build_app(REPO, {})


def asset(name, asset_id):
    return {"id": asset_id, "name": name, "browser_download_url": "https://example.invalid/" + name,
            "size": 100, "download_count": 1}


class PrereleaseTest(unittest.TestCase):
    def build(self, releases):
        with mock.patch.object(build_index, "api", return_value=releases), \
                mock.patch.object(build_index, "read_manifest", return_value=("org.example", 1, "1")):
            return build_index.build_app(REPO, {})

    def test_newer_prerelease_is_offered_as_beta(self):
        app = self.build([
            {"tag_name": "v2-beta.1", "prerelease": True, "assets": [asset("b.apk", 2)]},
            {"tag_name": "v1", "assets": [asset("a.apk", 1)]},
        ])
        self.assertEqual(app["tag"], "v1")
        self.assertEqual(app["beta"]["tag"], "v2-beta.1")
        self.assertNotIn("betaOnly", app)

    def test_prerelease_older_than_the_full_release_is_ignored(self):
        app = self.build([
            {"tag_name": "v2", "assets": [asset("a.apk", 1)]},
            {"tag_name": "v2-beta.1", "prerelease": True, "assets": [asset("b.apk", 2)]},
        ])
        self.assertEqual(app["tag"], "v2")
        self.assertNotIn("beta", app)

    def test_repository_with_only_a_prerelease_is_beta_only(self):
        app = self.build([
            {"tag_name": "v1-beta.1", "prerelease": True, "assets": [asset("b.apk", 2)]},
        ])
        self.assertEqual(app["tag"], "v1-beta.1")
        self.assertTrue(app["betaOnly"])


class NotBeforeTest(unittest.TestCase):
    def test_old_release_is_given_up_on_before_reading_its_apks(self):
        releases = [{"tag_name": "v1", "published_at": "2020-01-01T00:00:00Z", "assets": [asset("a.apk", 1)]}]
        with mock.patch.object(build_index, "api", return_value=releases), \
                mock.patch.object(build_index, "read_manifest") as read_manifest:
            self.assertIsNone(build_index.build_app(REPO, {}, not_before=1700000000))
        read_manifest.assert_not_called()

    def test_recent_release_is_examined(self):
        releases = [{"tag_name": "v1", "published_at": "2026-09-01T00:00:00Z", "assets": [asset("a.apk", 1)]}]
        with mock.patch.object(build_index, "api", return_value=releases), \
                mock.patch.object(build_index, "read_manifest", return_value=("org.example", 1, "1")):
            self.assertEqual(build_index.build_app(REPO, {}, not_before=1700000000)["tag"], "v1")


class AutoAppsTest(unittest.TestCase):
    NOW = 1790000000

    def candidate(self, name="other/app", pushed="2026-09-20T00:00:00Z", topics=("android",)):
        return dict(REPO, full_name=name, html_url="https://github.com/" + name,
                    pushed_at=pushed, topics=list(topics), stargazers_count=50)

    def entry(self, name="other/app", published="2026-09-01T00:00:00Z"):
        return {"fullName": name, "tag": "v1", "publishedAt": published, "releaseNotes": "n" * 5000,
                "apks": [{"id": 1}], "stars": 1, "topics": []}

    def run_auto(self, candidates, built, previous=None, state=None, published=()):
        with mock.patch.object(build_index, "discover_candidates", return_value=candidates), \
                mock.patch.object(build_index, "build_app", side_effect=built) as build_app:
            apps, new_state = build_index.build_auto_apps(
                set(published), previous or {}, state or {}, self.NOW)
        return apps, new_state, build_app

    def test_recent_release_is_listed_with_short_notes(self):
        apps, state, _ = self.run_auto([self.candidate()], [self.entry()])
        self.assertEqual([app["fullName"] for app in apps], ["other/app"])
        self.assertEqual(len(apps[0]["releaseNotes"]), build_index.AUTO_MAX_NOTES)
        self.assertEqual(state, {"other/app": "2026-09-20T00:00:00Z"})

    def test_old_release_is_left_out_but_remembered(self):
        apps, state, _ = self.run_auto([self.candidate()], [self.entry(published="2025-01-01T00:00:00Z")])
        self.assertEqual(apps, [])
        self.assertIn("other/app", state)

    def test_unchanged_repository_is_not_asked_about_again(self):
        previous = {"other/app": self.entry()}
        apps, _, build_app = self.run_auto(
            [self.candidate()], [], previous=previous,
            state={"other/app": "2026-09-20T00:00:00Z"})
        build_app.assert_not_called()
        self.assertEqual(apps[0]["stars"], 50)

    def test_published_and_tagged_repositories_are_skipped(self):
        candidates = [self.candidate("a/published"), self.candidate("b/tagged", topics=("arkstore",))]
        apps, _, build_app = self.run_auto(candidates, [], published=["a/published"])
        build_app.assert_not_called()
        self.assertEqual(apps, [])

    def test_unlicensed_repository_is_skipped(self):
        candidate = dict(self.candidate(), license=None)
        apps, _, build_app = self.run_auto([candidate], [])
        build_app.assert_not_called()

    def test_lookups_are_capped_per_run(self):
        candidates = [self.candidate("o/app%d" % i) for i in range(build_index.AUTO_MAX_LOOKUPS + 5)]
        built = [self.entry("o/app%d" % i) for i in range(build_index.AUTO_MAX_LOOKUPS)]
        apps, state, build_app = self.run_auto(candidates, built)
        self.assertEqual(build_app.call_count, build_index.AUTO_MAX_LOOKUPS)
        self.assertEqual(len(state), build_index.AUTO_MAX_LOOKUPS)


class MainTest(unittest.TestCase):
    def test_network_failure_keeps_the_previously_listed_app(self):
        known = {"fullName": "owner/app", "tag": "v1", "apks": [{"id": 7}]}
        with tempfile.TemporaryDirectory() as directory:
            previous = os.path.join(directory, "previous.json")
            output = os.path.join(directory, "index.json")
            with open(previous, "w", encoding="utf-8") as file:
                json.dump({"apps": [known]}, file)
            with mock.patch.object(build_index, "discover", return_value=[REPO]), \
                    mock.patch.object(build_index, "discover_candidates", return_value=[]), \
                    mock.patch.object(build_index, "api", return_value=release(asset_id=8)), \
                    mock.patch.object(build_index, "read_manifest",
                                      side_effect=OSError("range request not honoured")), \
                    mock.patch("sys.argv", ["build_index.py", "--previous", previous,
                                            "--output", output]):
                build_index.main()
            with open(output, encoding="utf-8") as file:
                self.assertEqual(json.load(file)["apps"], [known])


if __name__ == "__main__":
    unittest.main()
