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
    def build(self, releases, manifests=None, previous_apks=None):
        """manifests maps an APK's file name to (package, versionCode); the default makes
        b.apk an upgrade of a.apk."""
        manifests = manifests or {"a.apk": ("org.example", 1), "b.apk": ("org.example", 2)}

        def read_manifest(url, size):
            package, code = manifests[url.rsplit("/", 1)[1]]
            return package, code, str(code)

        with mock.patch.object(build_index, "api", return_value=releases), \
                mock.patch.object(build_index, "read_manifest", side_effect=read_manifest) as reader:
            app = build_index.build_app(REPO, previous_apks or {})
        self.read_count = reader.call_count
        return app

    PRERELEASE_FIRST = [
        {"tag_name": "v2-beta.1", "prerelease": True, "assets": [asset("b.apk", 2)]},
        {"tag_name": "v1", "assets": [asset("a.apk", 1)]},
    ]

    def test_newer_prerelease_is_offered_as_beta(self):
        app = self.build(self.PRERELEASE_FIRST)
        self.assertEqual(app["tag"], "v1")
        self.assertEqual(app["beta"]["tag"], "v2-beta.1")
        self.assertNotIn("betaOnly", app)
        self.assertNotIn("skippedPrerelease", app)

    def test_prerelease_from_an_older_branch_is_not_offered(self):
        app = self.build(self.PRERELEASE_FIRST,
                         {"a.apk": ("org.example", 200), "b.apk": ("org.example", 191)})
        self.assertEqual(app["tag"], "v1")
        self.assertNotIn("beta", app)

    def test_prerelease_with_the_same_version_code_is_not_offered(self):
        app = self.build(self.PRERELEASE_FIRST,
                         {"a.apk": ("org.example", 8), "b.apk": ("org.example", 8)})
        self.assertNotIn("beta", app)

    def test_prerelease_of_another_package_is_not_offered(self):
        app = self.build(self.PRERELEASE_FIRST,
                         {"a.apk": ("org.example", 1), "b.apk": ("org.example.beta", 9)})
        self.assertNotIn("beta", app)

    SPLIT_BY_ARCHITECTURE = [
        {"tag_name": "v2-rc.1", "prerelease": True,
         "assets": [asset("rc-arm64-v8a.apk", 3), asset("rc-x86_64.apk", 4)]},
        {"tag_name": "v2", "assets": [asset("arm64-v8a.apk", 1), asset("x86_64.apk", 2)]},
    ]

    def test_version_codes_are_compared_within_an_architecture(self):
        codes = {"arm64-v8a.apk": ("org.example", 2001), "x86_64.apk": ("org.example", 2004),
                 "rc-arm64-v8a.apk": ("org.example", 2001), "rc-x86_64.apk": ("org.example", 2004)}
        self.assertNotIn("beta", self.build(self.SPLIT_BY_ARCHITECTURE, codes))
        codes.update({"rc-arm64-v8a.apk": ("org.example", 2101), "rc-x86_64.apk": ("org.example", 2104)})
        self.assertEqual(self.build(self.SPLIT_BY_ARCHITECTURE, codes)["beta"]["tag"], "v2-rc.1")

    def test_prerelease_covering_fewer_architectures_is_offered(self):
        # The x86_64 file of the full release has a higher code than the prerelease, yet for
        # an arm64 device the prerelease is an upgrade.
        releases = [
            {"tag_name": "v2-beta.1", "prerelease": True, "assets": [asset("rc-arm64-v8a.apk", 3)]},
            {"tag_name": "v1", "assets": [asset("arm64-v8a.apk", 1), asset("x86_64.apk", 2)]},
        ]
        codes = {"arm64-v8a.apk": ("org.example", 2000010), "x86_64.apk": ("org.example", 4000010),
                 "rc-arm64-v8a.apk": ("org.example", 2000011)}
        self.assertEqual(self.build(releases, codes)["beta"]["tag"], "v2-beta.1")

    def test_universal_files_are_compared_with_every_architecture(self):
        releases = [
            {"tag_name": "v2-beta.1", "prerelease": True, "assets": [asset("b-universal.apk", 3)]},
            {"tag_name": "v1", "assets": [asset("arm64-v8a.apk", 1), asset("x86_64.apk", 2)]},
        ]
        codes = {"arm64-v8a.apk": ("org.example", 2001), "x86_64.apk": ("org.example", 2004),
                 "b-universal.apk": ("org.example", 2002)}
        self.assertIn("beta", self.build(releases, codes))
        codes["b-universal.apk"] = ("org.example", 2001)
        self.assertNotIn("beta", self.build(releases, codes))

    def test_prerelease_for_an_architecture_the_full_release_lacks_is_offered(self):
        releases = [
            {"tag_name": "v2-beta.1", "prerelease": True, "assets": [asset("rc-arm64-v8a.apk", 3)]},
            {"tag_name": "v1", "assets": [asset("x86_64.apk", 2)]},
        ]
        codes = {"x86_64.apk": ("org.example", 9), "rc-arm64-v8a.apk": ("org.example", 3)}
        self.assertIn("beta", self.build(releases, codes))

    def test_architectures_are_read_like_the_app_reads_them(self):
        self.assertEqual(build_index.architectures("app-arm64-v8a-release.apk"), {"arm64-v8a"})
        self.assertEqual(build_index.architectures("app-x86_64.apk"), {"x86_64"})
        self.assertEqual(build_index.architectures("app-x86.apk"), {"x86"})
        self.assertEqual(build_index.architectures("app-armeabi-v7a.apk"), {"armeabi-v7a"})
        self.assertEqual(build_index.architectures("App-Universal.apk"), set())
        self.assertEqual(build_index.architectures("ARK-launcher-0.8.2.apk"), set())

    def test_skipped_prerelease_is_not_examined_again(self):
        manifests = {"a.apk": ("org.example", 200), "b.apk": ("org.example", 191)}
        first = self.build(self.PRERELEASE_FIRST, manifests)
        self.assertEqual(first["skippedPrerelease"]["tag"], "v2-beta.1")
        self.assertEqual(self.read_count, 2)
        second = self.build(self.PRERELEASE_FIRST, manifests, build_index.known_apks(first))
        self.assertEqual(self.read_count, 0)
        self.assertEqual(second, first)

    def test_prerelease_older_than_the_full_release_is_ignored(self):
        app = self.build([
            {"tag_name": "v2", "assets": [asset("a.apk", 1)]},
            {"tag_name": "v2-beta.1", "prerelease": True, "assets": [asset("b.apk", 2)]},
        ])
        self.assertEqual(app["tag"], "v2")
        self.assertNotIn("beta", app)

    def test_prerelease_is_left_alone_when_not_wanted(self):
        with mock.patch.object(build_index, "api", return_value=self.PRERELEASE_FIRST), \
                mock.patch.object(build_index, "read_manifest",
                                  return_value=("org.example", 1, "1")) as reader:
            app = build_index.build_app(REPO, {}, with_prerelease=False)
        self.assertEqual(app["tag"], "v1")
        self.assertNotIn("beta", app)
        self.assertNotIn("skippedPrerelease", app)
        self.assertEqual(reader.call_count, 1)

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
        self.assertEqual(state, {"other/app": self.examined(self.NOW, listed=True)})

    def test_old_release_is_left_out_but_remembered(self):
        apps, state, _ = self.run_auto([self.candidate()], [self.entry(published="2025-01-01T00:00:00Z")])
        self.assertEqual(apps, [])
        self.assertEqual(state, {"other/app": self.examined(self.NOW, listed=False)})

    # The candidate was pushed to about 38 hours before NOW.
    PUSHED = "2026-09-20T00:00:00Z"
    HOUR = 3600

    def examined(self, at, pushed=PUSHED, listed=False):
        return {"pushedAt": pushed, "examinedAt": at, "listed": listed}

    def test_unchanged_repository_is_not_asked_about_again(self):
        previous = {"other/app": self.entry()}
        known = self.examined(self.NOW - self.HOUR, listed=True)
        apps, state, build_app = self.run_auto(
            [self.candidate()], [], previous=previous, state={"other/app": known})
        build_app.assert_not_called()
        self.assertEqual(apps[0]["stars"], 50)
        self.assertEqual(state, {"other/app": known})

    def test_unchanged_repository_is_examined_again_after_a_day(self):
        known = self.examined(self.NOW - (build_index.AUTO_RECHECK_HOURS + 1) * self.HOUR)
        apps, state, build_app = self.run_auto(
            [self.candidate()], [self.entry()], state={"other/app": known})
        self.assertEqual(build_app.call_count, 1)
        self.assertEqual([app["fullName"] for app in apps], ["other/app"])
        self.assertEqual(state, {"other/app": self.examined(self.NOW, listed=True)})

    def test_repository_examined_right_after_a_push_is_examined_again(self):
        # The release's APK may not have been attached yet when it was first looked at.
        pushed = "2026-09-21T13:00:00Z"
        known = self.examined(self.NOW - self.HOUR, pushed)
        _, _, build_app = self.run_auto(
            [self.candidate(pushed=pushed)], [self.entry()], state={"other/app": known})
        self.assertEqual(build_app.call_count, 1)

    def test_state_without_examination_time_is_examined_again(self):
        _, state, build_app = self.run_auto(
            [self.candidate()], [self.entry()], state={"other/app": self.PUSHED})
        self.assertEqual(build_app.call_count, 1)
        self.assertEqual(state, {"other/app": self.examined(self.NOW, listed=True)})

    def test_listed_app_missing_from_the_previous_list_is_examined_again(self):
        # State and the list did not come from the same run.
        known = self.examined(self.NOW - self.HOUR, listed=True)
        apps, _, build_app = self.run_auto(
            [self.candidate()], [self.entry()], previous={}, state={"other/app": known})
        self.assertEqual(build_app.call_count, 1)
        self.assertEqual([app["fullName"] for app in apps], ["other/app"])

    def test_prereleases_are_not_looked_at(self):
        _, _, build_app = self.run_auto([self.candidate()], [self.entry()])
        self.assertFalse(build_app.call_args.kwargs["with_prerelease"])

    def test_changed_repositories_are_examined_before_those_merely_due(self):
        limit = build_index.AUTO_MAX_LOOKUPS
        due = [self.candidate("due/app%d" % i) for i in range(5)]
        changed = [self.candidate("new/app%d" % i) for i in range(limit)]
        state = {repo["full_name"]: self.examined(0) for repo in due}
        _, new_state, build_app = self.run_auto(
            due + changed, [None] * limit, state=state)
        self.assertEqual(build_app.call_count, limit)
        examined = {call.args[0]["full_name"] for call in build_app.call_args_list}
        self.assertEqual(examined, {repo["full_name"] for repo in changed})
        # Those passed over keep their state, so they are looked at on a later run.
        self.assertEqual(new_state["due/app0"], self.examined(0))

    def test_failed_lookup_keeps_the_previous_entry_and_state(self):
        previous = {"other/app": self.entry()}
        known = self.examined(0)
        apps, state, _ = self.run_auto(
            [self.candidate()], [OSError("down")], previous=previous, state={"other/app": known})
        self.assertEqual([app["fullName"] for app in apps], ["other/app"])
        self.assertEqual(state, {"other/app": known})

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
    KNOWN = {"fullName": "owner/app", "tag": "v1", "apks": [{"id": 7}]}
    FOUND = {"fullName": "other/app", "tag": "v3", "publishedAt": "2026-09-01T00:00:00Z",
             "releaseNotes": "", "apks": [{"id": 9}], "stars": 1, "topics": []}

    def candidate(self):
        return dict(REPO, full_name="other/app", html_url="https://github.com/other/app",
                    topics=["android"], stargazers_count=50)

    @staticmethod
    def api(path):
        if path.startswith("/repos/other/app/"):
            raise urllib.error.URLError("down")
        return release(asset_id=8)

    def run_main(self, previous, candidates=(), previous_auto=None):
        """Runs main while GitHub cannot be reached, so that only what the previous files
        hold can be listed. Returns the index and the list of automatically found apps."""
        with tempfile.TemporaryDirectory() as directory:
            paths = {name: os.path.join(directory, name)
                     for name in ("previous.json", "previous-auto.json", "index.json", "auto.json")}
            with open(paths["previous.json"], "w", encoding="utf-8") as file:
                json.dump(previous, file)
            if previous_auto is not None:
                with open(paths["previous-auto.json"], "w", encoding="utf-8") as file:
                    json.dump(previous_auto, file)
            argv = ["build_index.py", "--previous", paths["previous.json"],
                    "--previous-auto", paths["previous-auto.json"],
                    "--output", paths["index.json"], "--auto-output", paths["auto.json"]]
            with mock.patch.object(build_index, "discover", return_value=[REPO]), \
                    mock.patch.object(build_index, "discover_candidates",
                                      return_value=list(candidates)), \
                    mock.patch.object(build_index, "api", side_effect=self.api), \
                    mock.patch.object(build_index, "read_manifest",
                                      side_effect=OSError("range request not honoured")), \
                    mock.patch.object(build_index.time, "time", return_value=1790000000), \
                    mock.patch("sys.argv", argv):
                build_index.main()
            with open(paths["index.json"], encoding="utf-8") as file:
                index = json.load(file)
            with open(paths["auto.json"], encoding="utf-8") as file:
                auto = json.load(file)
        return index, auto

    def test_network_failure_keeps_the_previously_listed_app(self):
        index, _ = self.run_main({"apps": [self.KNOWN]})
        self.assertEqual(index["apps"], [self.KNOWN])

    def test_automatically_found_apps_go_to_a_file_of_their_own(self):
        index, auto = self.run_main(
            {"apps": [self.KNOWN]}, [self.candidate()], previous_auto={"autoApps": [self.FOUND]})
        self.assertNotIn("autoApps", index)
        self.assertEqual([app["fullName"] for app in auto["autoApps"]], ["other/app"])
        self.assertEqual(auto["generatedAt"], index["generatedAt"])

    def test_previous_list_inside_an_old_index_is_still_used(self):
        _, auto = self.run_main(
            {"apps": [self.KNOWN], "autoApps": [self.FOUND]}, [self.candidate()])
        self.assertEqual([app["fullName"] for app in auto["autoApps"]], ["other/app"])


if __name__ == "__main__":
    unittest.main()
