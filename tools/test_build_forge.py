"""Tests for the builder of the Codeberg lists. Run with: python3 -m unittest discover tools"""

import unittest
import urllib.error
from unittest import mock

import build_forge
import build_index

NOW = 1790000000
REPO = {
    "full_name": "owner/app",
    "html_url": "https://codeberg.org/owner/app",
    "description": "A chat app",
    "stars_count": 7,
    "topics": ["android"],
    "updated_at": "2026-09-20T12:00:00+02:00",
}
RELEASES = [{
    "tag_name": "v1",
    "name": "One",
    "body": "n" * 5000,
    "html_url": "https://codeberg.org/owner/app/releases/tag/v1",
    "published_at": "2026-09-01T10:00:00+02:00",
    "draft": False,
    "prerelease": False,
    "assets": [{"id": 4, "name": "app.apk", "size": 100, "download_count": 9,
                "browser_download_url": "https://codeberg.org/owner/app/releases/download/v1/app.apk"}],
}]


class TimeTest(unittest.TestCase):
    def test_time_zone_is_taken_out(self):
        self.assertEqual(build_forge.to_utc("2026-09-15T12:15:55+02:00"), "2026-09-15T10:15:55Z")
        self.assertEqual(build_forge.to_utc("2026-01-01T00:30:00-05:30"), "2026-01-01T06:00:00Z")
        self.assertEqual(build_forge.to_utc("2026-09-15T12:15:55Z"), "2026-09-15T12:15:55Z")

    def test_odd_time_is_empty(self):
        self.assertEqual(build_forge.to_utc(None), "")
        self.assertEqual(build_forge.to_utc("yesterday"), "")


class LicenseTest(unittest.TestCase):
    def test_license_is_recognised_from_its_text(self):
        cases = {
            "AGPL-3.0": "GNU AFFERO GENERAL PUBLIC LICENSE\n   Version 3, 19 November 2007",
            "GPL-3.0": "GNU GENERAL PUBLIC LICENSE\n Version 3, 29 June 2007",
            "GPL-2.0": "GNU GENERAL PUBLIC LICENSE\n Version 2, June 1991",
            "LGPL-3.0": "GNU LESSER GENERAL PUBLIC LICENSE\n Version 3, 29 June 2007",
            "Apache-2.0": "Apache License\n Version 2.0, January 2004",
            "MIT": "MIT License\n\nPermission is hereby granted, free of charge, to any person\n"
                   "... THE SOFTWARE IS PROVIDED \"AS IS\", WITHOUT WARRANTY",
            "BSD-3-Clause": "Redistribution and use in source and binary forms ... Neither the name of",
            "BSD-2-Clause": "Redistribution and use in source and binary forms, with or without",
        }
        for name, text in cases.items():
            self.assertEqual(build_forge.identify_license(text), name)

    def test_unknown_text_is_no_license(self):
        self.assertIsNone(build_forge.identify_license("All rights reserved."))
        self.assertIsNone(build_forge.identify_license(""))

    def test_license_file_is_found_in_the_root(self):
        def forge(path, raw=False, limit=None):
            if path.endswith("/contents"):
                return [{"type": "file", "name": "COPYING"}, {"type": "file", "name": "LICENSE.md"},
                        {"type": "dir", "name": "LICENSE"}, {"type": "file", "name": "LICENSE.artwork"}]
            self.assertTrue(path.endswith("/raw/LICENSE.md"))
            return b"GNU GENERAL PUBLIC LICENSE\nVersion 3"

        with mock.patch.object(build_forge, "forge", side_effect=forge):
            self.assertEqual(build_forge.detect_license("owner/app"), "GPL-3.0")

    def test_repository_without_a_license_file_has_none(self):
        with mock.patch.object(build_forge, "forge", return_value=[{"type": "file", "name": "README.md"}]):
            self.assertIsNone(build_forge.detect_license("owner/app"))


class ExamineTest(unittest.TestCase):
    def examine(self, previous=None, found=True, license_name="GPL-3.0", releases=RELEASES, repo=REPO):
        def forge(path, raw=False, limit=None):
            self.asked.append(path)
            return releases

        self.asked = []
        with mock.patch.object(build_forge, "forge", side_effect=forge), \
                mock.patch.object(build_forge, "detect_license", return_value=license_name), \
                mock.patch.object(build_index, "read_manifest",
                                  return_value=("org.example", 3, "1.0", "Example")):
            return build_forge.examine(repo, previous, NOW, found)

    def test_found_app_is_described_like_one_from_github(self):
        app = self.examine()
        self.assertEqual(app["fullName"], "codeberg:owner/app")
        self.assertEqual(app["source"], "codeberg")
        self.assertEqual(app["repoUrl"], "https://codeberg.org/owner/app")
        self.assertEqual((app["license"], app["stars"], app["downloads"]), ("GPL-3.0", 7, 9))
        self.assertEqual(app["publishedAt"], "2026-09-01T08:00:00Z")
        self.assertEqual(app["pushedAt"], "2026-09-20T10:00:00Z")
        self.assertEqual(len(app["releaseNotes"]), build_index.AUTO_MAX_NOTES)
        self.assertIn("arkstore-communication", app["topics"])
        self.assertEqual(app["apks"][0]["label"], "Example")
        self.assertEqual(self.asked, ["/repos/owner/app/releases?limit=50"])

    def test_unlicensed_repository_is_not_listed(self):
        self.assertIsNone(self.examine(license_name=None))
        self.assertEqual(self.asked, [])

    def test_fresh_entry_is_kept_without_asking(self):
        first = self.examine()
        again = self.examine(previous=first, repo=dict(REPO, stars_count=9))
        self.assertEqual(self.asked, [])
        self.assertEqual(again["stars"], 9)
        self.assertEqual(again["tag"], "v1")

    def test_changed_or_old_entry_is_examined_again(self):
        first = self.examine()
        self.examine(previous=first, repo=dict(REPO, updated_at="2026-09-21T12:00:00+02:00"))
        self.assertEqual(len(self.asked), 1)
        self.examine(previous=dict(first, examinedAt=NOW - 25 * 3600))
        self.assertEqual(len(self.asked), 1)

    def test_found_app_with_an_old_release_is_left_out(self):
        old = [dict(RELEASES[0], published_at="2025-01-01T00:00:00+00:00")]
        self.assertIsNone(self.examine(releases=old))
        self.assertIsNotNone(self.examine(releases=old, found=False))

    def test_published_app_keeps_its_full_notes(self):
        self.assertEqual(len(self.examine(found=False)["releaseNotes"]), build_index.MAX_NOTES)


PROJECT = {
    "id": 12,
    "path_with_namespace": "group/sub/app",
    "web_url": "https://gitlab.com/group/sub/app",
    "description": "A chat app",
    "star_count": 8,
    "topics": ["android"],
    "last_activity_at": "2026-09-20T10:00:00.123Z",
    "visibility": "public",
}


def gitlab_release(links, tag="v1", released="2026-09-01T08:00:00.000Z", upcoming=False):
    return {"tag_name": tag, "name": tag, "description": "notes", "released_at": released,
            "upcoming_release": upcoming, "_links": {"self": "https://gitlab.com/group/sub/app/-/releases/" + tag},
            "assets": {"links": links}}


def link(link_id, name, url):
    return {"id": link_id, "name": name, "url": url, "direct_asset_url": url}


class GitLabTest(unittest.TestCase):
    APK = "https://gitlab.com/group/sub/app/-/package_files/5/download"

    def examine(self, releases, previous=None, license_key="gpl-3.0+", found=True, forked=False):
        def gitlab(path):
            self.asked.append(path)
            if "/releases" in path:
                return releases
            detail = dict(PROJECT, license={"key": license_key} if license_key else None)
            if forked:
                detail["forked_from_project"] = {"id": 1}
            return detail

        def remote_size(url):
            self.sized.append(url)
            return 4321

        self.asked = []
        self.sized = []
        with mock.patch.object(build_forge, "gitlab", side_effect=gitlab), \
                mock.patch.object(build_forge, "remote_size", side_effect=remote_size), \
                mock.patch.object(build_index, "read_manifest",
                                  return_value=("org.example", 3, "1.0", "Example")):
            return build_forge.examine(PROJECT, previous, NOW, found, build_forge.GitLab)

    def test_project_is_described_like_a_repository_on_github(self):
        app = self.examine([gitlab_release([link(7, "app.apk", self.APK)])])
        self.assertEqual(app["fullName"], "gitlab:group/sub/app")
        self.assertEqual((app["source"], app["license"], app["stars"]), ("gitlab", "GPL-3.0", 8))
        self.assertEqual(app["repoUrl"], "https://gitlab.com/group/sub/app")
        self.assertEqual(app["publishedAt"], "2026-09-01T08:00:00Z")
        self.assertEqual(app["pushedAt"], "2026-09-20T10:00:00Z")
        self.assertEqual(app["apks"], [{
            "id": 7, "name": "app.apk", "url": self.APK, "size": 4321, "packageName": "org.example",
            "versionCode": 3, "versionName": "1.0", "label": "Example",
        }])
        self.assertNotIn("known_sizes", app)

    def test_file_name_comes_from_the_address_when_the_link_is_named_otherwise(self):
        url = "https://gitlab.com/api/v4/projects/12/packages/generic/app/1/my%20app.apk"
        app = self.examine([gitlab_release([link(7, "Android build", url)])])
        self.assertEqual(app["apks"][0]["name"], "my app.apk")

    def test_file_kept_on_another_site_is_not_offered(self):
        elsewhere = [link(7, "app.apk", "https://example.com/app.apk"),
                     link(8, "app.apk", "http://gitlab.com/group/app.apk"),
                     link(9, "notes.txt", "https://gitlab.com/group/notes.txt")]
        self.assertIsNone(self.examine([gitlab_release(elsewhere)]))
        self.assertEqual(self.sized, [])

    def test_size_of_a_known_file_is_not_asked_again(self):
        releases = [gitlab_release([link(7, "app.apk", self.APK)])]
        first = self.examine(releases)
        self.assertEqual(len(self.sized), 1)
        self.examine(releases, previous=dict(first, examinedAt=NOW - 25 * 3600))
        self.assertEqual(self.sized, [])

    def test_only_the_newest_release_with_files_has_them_sized(self):
        releases = [gitlab_release([link(9, "new.apk", self.APK)], tag="v2"),
                    gitlab_release([link(7, "old.apk", self.APK)], tag="v1")]
        app = self.examine(releases)
        self.assertEqual(app["tag"], "v2")
        self.assertEqual(len(self.sized), 1)

    def test_upcoming_release_is_passed_over(self):
        releases = [gitlab_release([link(9, "new.apk", self.APK)], tag="v2", upcoming=True),
                    gitlab_release([link(7, "old.apk", self.APK)], tag="v1")]
        self.assertEqual(self.examine(releases)["tag"], "v1")

    def test_unrecognised_license_or_fork_is_not_listed(self):
        releases = [gitlab_release([link(7, "app.apk", self.APK)])]
        self.assertIsNone(self.examine(releases, license_key="sspl-1.0"))
        self.assertIsNone(self.examine(releases, license_key=None))
        self.assertIsNone(self.examine(releases, forked=True))
        self.assertEqual(self.examine(releases, license_key="AGPL-3.0-or-later")["license"], "AGPL-3.0")

    def test_unusable_projects_are_told_apart(self):
        self.assertTrue(build_forge.GitLab.usable(PROJECT))
        for change in ({"archived": True}, {"forked_from_project": {"id": 1}}, {"mirror": True},
                       {"empty_repo": True}, {"visibility": "internal"}):
            self.assertFalse(build_forge.GitLab.usable(dict(PROJECT, **change)))


class BuildListTest(unittest.TestCase):
    def test_failed_lookup_keeps_the_previous_entry(self):
        previous = {"codeberg:owner/app": {"fullName": "codeberg:owner/app"}}
        with mock.patch.object(build_forge, "examine", side_effect=urllib.error.URLError("down")):
            apps = build_forge.build_list([REPO], previous, NOW, found=True)
        self.assertEqual(apps, [{"fullName": "codeberg:owner/app"}])

    def test_unusable_repositories_are_told_apart(self):
        self.assertTrue(build_forge.usable(REPO))
        for flag in ("private", "fork", "archived", "mirror", "empty"):
            self.assertFalse(build_forge.usable(dict(REPO, **{flag: True})))


if __name__ == "__main__":
    unittest.main()
