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


class MainTest(unittest.TestCase):
    def test_network_failure_keeps_the_previously_listed_app(self):
        known = {"fullName": "owner/app", "tag": "v1", "apks": [{"id": 7}]}
        with tempfile.TemporaryDirectory() as directory:
            previous = os.path.join(directory, "previous.json")
            output = os.path.join(directory, "index.json")
            with open(previous, "w", encoding="utf-8") as file:
                json.dump({"apps": [known]}, file)
            with mock.patch.object(build_index, "discover", return_value=[REPO]), \
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
