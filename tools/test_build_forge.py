"""Tests for the builder of the Codeberg lists. Run with: python3 -m unittest discover tools"""

import hashlib
import http.client
import re
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


MIT_GRANT = """Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.
"""
MIT_DISCLAIMER = """
THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
"""
MIT = "MIT License\n\nCopyright (c) 2026 Owner\n\n" + MIT_GRANT + MIT_DISCLAIMER
ISC = """Copyright (c) 2026 Owner

Permission to use, copy, modify, and/or distribute this software for any
purpose with or without fee is hereby granted, provided that the above
copyright notice and this permission notice appear in all copies.

THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES WITH
REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF MERCHANTABILITY
AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY SPECIAL, DIRECT,
INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES WHATSOEVER RESULTING FROM
LOSS OF USE, DATA OR PROFITS, WHETHER IN AN ACTION OF CONTRACT, NEGLIGENCE OR
OTHER TORTIOUS ACTION, ARISING OUT OF OR IN CONNECTION WITH THE USE OR
PERFORMANCE OF THIS SOFTWARE.
"""
BSD_CONDITIONS = """Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this
   list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright notice,
   this list of conditions and the following disclaimer in the documentation
   and/or other materials provided with the distribution.
"""
BSD_NAME = """
3. Neither the name of Example Project nor the names of its contributors may be
   used to endorse or promote products derived from this software without
   specific prior written permission.
"""
BSD_DISCLAIMER = """
THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
"""


class LicenseTest(unittest.TestCase):
    def test_license_is_known_by_its_terms(self):
        cases = {
            "MIT": MIT,
            "ISC": ISC,
            "BSD-3-Clause": "Copyright (c) 2026, Owner\n\n" + BSD_CONDITIONS + BSD_NAME + BSD_DISCLAIMER,
            "BSD-2-Clause": "Copyright (c) 2026, Owner\n\n" + BSD_CONDITIONS + BSD_DISCLAIMER,
        }
        for name, text in cases.items():
            self.assertEqual(build_forge.identify_license(text), name)

    def test_layout_and_markup_make_no_difference(self):
        marked_up = "# MIT License\n\n" + " ".join(MIT_GRANT.split()) + "\n\n> " + MIT_DISCLAIMER.replace("\n", "\n> ")
        self.assertEqual(build_forge.identify_license(marked_up), "MIT")

    def test_mention_of_a_license_is_not_one(self):
        self.assertIsNone(build_forge.identify_license(
            "Use is restricted to non-commercial purposes. "
            "It is NOT licensed under the Apache License Version 2.0"))
        self.assertIsNone(build_forge.identify_license("GNU GENERAL PUBLIC LICENSE\n Version 3, 29 June 2007"))

    def test_changed_terms_are_no_license(self):
        changed = MIT_GRANT + "\nThe Software shall be used for Good, not Evil.\n" + MIT_DISCLAIMER
        self.assertIsNone(build_forge.identify_license(changed))
        self.assertIsNone(build_forge.identify_license(MIT.replace("sublicense, and/or sell", "sublicense")))

    def test_conditions_around_the_terms_are_no_license(self):
        self.assertIsNone(build_forge.identify_license(
            "\"Commons Clause\" License Condition v1.0\n\nThe Software is provided to you under the "
            "License, as defined below, subject to the following condition.\n\n" + MIT))
        self.assertIsNone(build_forge.identify_license("Our own terms come first. " * 60 + MIT))
        self.assertIsNone(build_forge.identify_license("Commercial redistribution is forbidden.\n\n" + MIT))
        for added in ("The software may not be sold.", "For non-commercial use only.",
                      "The Software shall be used for Good, not Evil.", "Redistribution is forbidden.",
                      "Use is limited to educational institutions.",
                      # A line that tells whose copyright the work is may not add a condition either.
                      "Copyright (c) 2026 Example. For non-commercial use only."):
            self.assertIsNone(build_forge.identify_license(MIT + "\n" + added + "\n"))
            self.assertIsNone(build_forge.identify_license(added + "\n\n" + MIT))

    def test_copyright_holder_may_have_any_name(self):
        # The lower-case form of this letter is two characters long: the terms are still found
        # whole whatever way the letters around them are written.
        owner = "İsmail"
        self.assertEqual(build_forge.identify_license(MIT.replace("Owner", owner)), "MIT")
        self.assertEqual(
            build_forge.identify_license(MIT.replace("Owner", owner) + "\nCopyright (c) 2026 " + owner + "\n"), "MIT")

    def test_only_further_licenses_may_follow_terms_that_are_not_closed(self):
        self.assertEqual(build_forge.identify_license(MIT + "\n----\n\nISC License\n\n" + ISC), "MIT")
        told = MIT + "\n----\nThis app includes a library under the following license:\n\n" + ISC
        self.assertIsNone(build_forge.identify_license(told))

    def test_names_filled_in_do_not_change_the_terms(self):
        isc = ISC.replace("THE AUTHOR", "EXAMPLE CORP")
        self.assertNotEqual(isc, ISC)
        self.assertEqual(build_forge.identify_license(isc), "ISC")
        disclaimer = BSD_DISCLAIMER.replace("THE COPYRIGHT HOLDERS AND CONTRIBUTORS", "EXAMPLE CORP") \
            .replace("THE COPYRIGHT HOLDER OR CONTRIBUTORS", "EXAMPLE CORP")
        self.assertNotIn("COPYRIGHT HOLDER", disclaimer)
        self.assertEqual(build_forge.identify_license(BSD_CONDITIONS + disclaimer), "BSD-2-Clause")
        self.assertEqual(build_forge.identify_license(BSD_CONDITIONS + BSD_NAME + disclaimer), "BSD-3-Clause")
        mit = MIT.replace("THE\nAUTHORS OR COPYRIGHT HOLDERS", "EXAMPLE CORP")
        self.assertNotEqual(mit, MIT)
        self.assertEqual(build_forge.identify_license(mit), "MIT")

    def test_name_cannot_carry_a_condition(self):
        limited = BSD_DISCLAIMER.replace("THE COPYRIGHT HOLDERS AND CONTRIBUTORS",
                                         "EXAMPLE CORP FOR NON-COMMERCIAL USE ONLY")
        self.assertIsNone(build_forge.identify_license(BSD_CONDITIONS + limited))

    def test_file_may_go_on_after_closed_terms(self):
        terms = "Everyone may do as they like.\n\nEND OF TERMS AND CONDITIONS\n"
        letters = re.sub(r"[^a-z]+", "", terms.lower())
        known = (("everyonemaydoastheylike", "endoftermsandconditions",
                  {hashlib.sha256(letters.encode()).hexdigest()[:32]: "Example-1.0"}),)
        with mock.patch.object(build_forge, "LICENSE_TEXTS", known + build_forge.LICENSE_TEXTS):
            title = "# The General Public License, version 1\n\nCopyright (c) 2026 Owner\n\n"
            notice = "\nHow to apply these terms\n\nThis program is free software: share it.\n"
            self.assertEqual(build_forge.identify_license(title + terms + notice), "Example-1.0")
            self.assertEqual(build_forge.identify_license(title + terms + notice + MIT), "Example-1.0")
            self.assertIsNone(build_forge.identify_license(title + terms + "Non-commercial use only.\n"))
            self.assertIsNone(build_forge.identify_license("Not for resale.\n" + terms))

    def test_unknown_text_is_no_license(self):
        self.assertIsNone(build_forge.identify_license("All rights reserved."))
        self.assertIsNone(build_forge.identify_license(""))

    def test_license_file_is_found_in_the_root(self):
        def forge(path, raw=False, limit=None):
            if path.endswith("/contents"):
                return [{"type": "file", "name": "COPYING"}, {"type": "file", "name": "LICENSE.md"},
                        {"type": "dir", "name": "LICENSE"}, {"type": "file", "name": "LICENSE.artwork"}]
            self.assertTrue(path.endswith("/raw/LICENSE.md"))
            return MIT.encode()

        with mock.patch.object(build_forge, "forge", side_effect=forge):
            self.assertEqual(build_forge.detect_license("owner/app"), "MIT")

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

    def test_icons_are_kept_where_the_caller_says(self):
        icons = build_index.IconStore("unused", "https://x/icons")

        def read_manifest(url, size, labels=None, icons=None):
            icons.found["org.example"] = "https://x/icons/org.example-abc.png"
            return "org.example", 3, "1.0", "Example"

        with mock.patch.object(build_forge, "forge", return_value=RELEASES), \
                mock.patch.object(build_forge, "detect_license", return_value="GPL-3.0"), \
                mock.patch.object(build_index, "read_manifest", side_effect=read_manifest):
            app = build_forge.examine(REPO, None, NOW, True, icons=icons)
        self.assertEqual(app["apks"][0]["icon"], "https://x/icons/org.example-abc.png")

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


def link(link_id, name, url, direct=None):
    return {"id": link_id, "name": name, "url": url, "direct_asset_url": direct or url}


class GitLabTest(unittest.TestCase):
    APK = "https://gitlab.com/group/sub/app/-/package_files/5/download"
    DIRECT = "https://gitlab.com/group/sub/app/-/releases/v1/downloads/app.apk"

    def examine(self, releases, previous=None, license_key="gpl-3.0+", found=True, forked=False, size=4321,
                mark="one"):
        def gitlab(path):
            self.asked.append(path)
            if "/releases" in path:
                return releases
            detail = dict(PROJECT, license={"key": license_key} if license_key else None)
            if forked:
                detail["forked_from_project"] = {"id": 1}
            return detail

        def remote_file(url):
            self.sized.append(url)
            return size, mark

        self.asked = []
        self.sized = []
        with mock.patch.object(build_forge, "gitlab", side_effect=gitlab), \
                mock.patch.object(build_forge, "remote_file", side_effect=remote_file), \
                mock.patch.object(build_index, "read_manifest",
                                  return_value=("org.example", 3, "1.0", "Example", None, 21)) as read_manifest:
            self.read_manifest = read_manifest
            return build_forge.examine(PROJECT, previous, NOW, found, build_forge.GitLab)

    def test_project_is_described_like_a_repository_on_github(self):
        app = self.examine([gitlab_release([link(7, "app.apk", self.APK)])])
        self.assertEqual(app["fullName"], "gitlab:group/sub/app")
        self.assertEqual((app["source"], app["license"], app["stars"]), ("gitlab", "GPL-3.0", 8))
        self.assertEqual(app["repoUrl"], "https://gitlab.com/group/sub/app")
        self.assertEqual(app["publishedAt"], "2026-09-01T08:00:00Z")
        self.assertEqual(app["pushedAt"], "2026-09-20T10:00:00Z")
        self.assertEqual(app["apks"], [{
            "id": build_forge.file_id(7, self.APK, 4321, "one"), "name": "app.apk", "url": self.APK,
            "size": 4321, "packageName": "org.example", "versionCode": 3, "versionName": "1.0",
            "label": "Example", "signer": None, "signerReader": 2, "minSdk": 21, "sdkReader": 1,
        }])

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

    def test_address_that_only_passes_the_request_on_is_not_a_file_on_gitlab(self):
        upload = "uploads/0123456789abcdef0123456789abcdef/app.apk"
        passed_on = [link(7, "app.apk", "https://example.com/app.apk", direct=self.DIRECT),
                     link(8, "app.apk", self.DIRECT),
                     link(9, "app.apk", "https://gitlab.com/group/sub/app/-/%72eleases/v1/downloads/app.apk"),
                     link(10, "app.apk", "https://gitlab.com/api/v4/projects/12/releases/v1/downloads/app.apk"),
                     # A release link is free to name its own path, and so to look like a file.
                     link(11, "app.apk", "https://gitlab.com/group/sub/app/-/releases/v1/downloads/" + upload),
                     link(12, "app.apk", "https://gitlab.com/api/v4/projects/12/releases/v1/downloads/" + upload),
                     link(13, "app.apk", "https://gitlab.com/group/app/-/raw/main/../../-/releases/v1/downloads/a.apk"),
                     link(14, "app.apk", "https://gitlab.com/group/app/-/raw/main/%2e%2e/x/app.apk"),
                     link(15, "app.apk", "https://gitlab.com/group/app/-/blob/main/app.apk"),
                     # However an address is written, it is read as the one it stands for.
                     link(16, "app.apk", "https://gitlab.com/%61pi/v4/projects/12/releases/v1/downloads/app.apk"),
                     link(17, "app.apk", "https://gitlab.com/group/app/%2d/releases/v1/downloads/" + upload),
                     link(18, "app.apk", "https://gitlab.com/api/v4/projects/group%2F..%2Fapp/packages/generic/a/1/app.apk"),
                     link(19, "app.apk", "https://gitlab.com/api/v4/projects/group%2F/packages/generic/a/1/app.apk")]
        self.assertIsNone(self.examine([gitlab_release(passed_on)]))
        self.assertEqual(self.sized, [])

    def test_files_gitlab_keeps_itself_are_offered(self):
        kept = ["https://gitlab.com/api/v4/projects/12/packages/generic/app/1.0/app.apk",
                # The API takes a project by its path too, with its slashes written as %2F.
                "https://gitlab.com/api/v4/projects/group%2Fsub%2Fapp/packages/generic/app/1.0/app.apk",
                "https://gitlab.com/-/project/12/uploads/0123456789abcdef0123456789abcdef/app.apk",
                "https://gitlab.com/group/sub/app/uploads/0123456789abcdef0123456789abcdef/app.apk",
                "https://gitlab.com/group/releases/-/raw/main/out/app.apk",
                "https://gitlab.com/group/sub/app/-/jobs/123/artifacts/raw/out/app.apk",
                "https://gitlab.com/group/sub/app/-/jobs/artifacts/main/raw/out/app.apk?job=build"]
        app = self.examine([gitlab_release([link(index, "app.apk", url) for index, url in enumerate(kept)])])
        self.assertEqual([apk["url"] for apk in app["apks"]], kept)

    def test_link_is_followed_to_the_file_itself(self):
        app = self.examine([gitlab_release([link(7, "app.apk", self.APK, direct=self.DIRECT)])])
        self.assertEqual(app["apks"][0]["url"], self.APK)

    def test_unchanged_file_is_not_read_again(self):
        releases = [gitlab_release([link(7, "app.apk", self.APK)])]
        first = self.examine(releases)
        self.assertEqual(self.read_manifest.call_count, 1)
        again = self.examine(releases, previous=dict(first, examinedAt=NOW - 25 * 3600))
        self.assertEqual(self.read_manifest.call_count, 0)
        self.assertEqual(again["apks"], first["apks"])

    def test_another_file_behind_the_same_link_is_read_again(self):
        first = self.examine([gitlab_release([link(7, "app.apk", self.APK)])])
        old = dict(first, examinedAt=NOW - 25 * 3600)
        moved = self.examine([gitlab_release([link(7, "app.apk", self.APK + "?v=2")])], previous=old)
        self.assertEqual(self.read_manifest.call_count, 1)
        self.assertEqual(moved["apks"][0]["url"], self.APK + "?v=2")
        self.assertNotEqual(moved["apks"][0]["id"], first["apks"][0]["id"])
        replaced = self.examine([gitlab_release([link(7, "app.apk", self.APK)])], previous=old, size=5000)
        self.assertEqual(self.read_manifest.call_count, 1)
        self.assertEqual(replaced["apks"][0]["size"], 5000)
        # The same address and size can still hold another file; the server's mark tells.
        self.examine([gitlab_release([link(7, "app.apk", self.APK)])], previous=old, mark="two")
        self.assertEqual(self.read_manifest.call_count, 1)

    def test_file_the_server_gives_no_mark_for_is_read_every_time(self):
        releases = [gitlab_release([link(7, "app.apk", self.APK)])]
        first = self.examine(releases, mark="")
        self.examine(releases, previous=dict(first, examinedAt=NOW - 25 * 3600), mark="")
        self.assertEqual(self.read_manifest.call_count, 1)

    def test_only_the_newest_release_with_files_has_them_sized(self):
        releases = [gitlab_release([link(9, "new.apk", self.APK)], tag="v2"),
                    gitlab_release([link(7, "old.apk", self.APK)], tag="v1")]
        app = self.examine(releases)
        self.assertEqual(app["tag"], "v2")
        self.assertEqual(len(self.sized), 1)

    def test_upcoming_release_is_passed_over(self):
        upcoming = "https://gitlab.com/group/sub/app/-/package_files/6/download"
        releases = [gitlab_release([link(9, "new.apk", upcoming)], tag="v2", upcoming=True),
                    gitlab_release([link(7, "old.apk", self.APK)], tag="v1")]
        self.assertEqual(self.examine(releases)["tag"], "v1")
        # Its files may not be there yet, so they are not even asked about.
        self.assertEqual(self.sized, [self.APK])

    def test_size_is_told_by_the_headers_without_reading_the_file(self):
        def response(status, content_range, **headers):
            answer = mock.MagicMock()
            answer.__enter__.return_value = answer
            answer.status = status
            answer.headers = dict(headers, **({"Content-Range": content_range} if content_range else {}))
            answer.read.side_effect = AssertionError("the file must not be read")
            return answer

        with mock.patch("urllib.request.urlopen", return_value=response(206, "bytes 0-0/4321", ETag='"abc"')):
            self.assertEqual(build_forge.remote_file(self.APK), (4321, '"abc"'))
        with mock.patch("urllib.request.urlopen", return_value=response(206, "bytes 0-0/4321")):
            self.assertEqual(build_forge.remote_file(self.APK), (4321, ""))
        for status, content_range in ((200, None), (206, "bytes 0-0/*"), (200, "bytes 0-0/4321")):
            with mock.patch("urllib.request.urlopen", return_value=response(status, content_range)):
                with self.assertRaises(OSError):
                    build_forge.remote_file(self.APK)

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
    RECENT = {"fullName": "codeberg:owner/app", "publishedAt": "2026-09-01T08:00:00Z"}
    OLD = {"fullName": "codeberg:owner/app", "publishedAt": "2025-01-01T00:00:00Z"}

    def build_list(self, previous, found, error=urllib.error.URLError("down")):
        with mock.patch.object(build_forge, "examine", side_effect=error):
            return build_forge.build_list([REPO], {"codeberg:owner/app": previous}, NOW, found=found)

    def test_failed_lookup_keeps_the_previous_entry(self):
        self.assertEqual(self.build_list(self.RECENT, found=True), [self.RECENT])

    def test_unforeseen_error_keeps_the_previous_entry_too(self):
        for error in (http.client.IncompleteRead(b""), AttributeError("odd answer")):
            self.assertEqual(self.build_list(self.RECENT, found=True, error=error), [self.RECENT])

    def test_failed_lookup_does_not_keep_a_found_app_past_its_time(self):
        self.assertEqual(self.build_list(self.OLD, found=True), [])
        self.assertEqual(self.build_list(self.OLD, found=False), [self.OLD])

    def test_place_that_cannot_be_searched_keeps_its_lists(self):
        published = {"codeberg:owner/app": self.OLD, "gitlab:group/app": {"fullName": "gitlab:group/app"}}
        found = {"codeberg:owner/app": self.RECENT, "codeberg:owner/taken": dict(self.RECENT),
                 "codeberg:owner/old": self.OLD}
        with mock.patch.object(build_forge, "search", side_effect=http.client.IncompleteRead(b"")):
            self.assertEqual(build_forge.published_apps(build_forge.Codeberg, published, NOW),
                             (set(), [self.OLD]))
            # What its developer has published in the meantime is no longer among the found.
            self.assertEqual(build_forge.found_apps(build_forge.Codeberg, {"owner/taken"}, found, NOW),
                             [self.RECENT])

    def test_unusable_repositories_are_told_apart(self):
        self.assertTrue(build_forge.usable(REPO))
        for flag in ("private", "fork", "archived", "mirror", "empty"):
            self.assertFalse(build_forge.usable(dict(REPO, **{flag: True})))


if __name__ == "__main__":
    unittest.main()
