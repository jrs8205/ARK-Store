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
