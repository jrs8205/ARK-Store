"""Tests for the builder of the Codeberg lists. Run with: python3 -m unittest discover tools"""

import contextlib
import hashlib
import http.client
import io
import json
import os
import re
import sys
import tempfile
import unittest
import urllib.error
import urllib.request
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


class TokenTest(unittest.TestCase):
    """Codeberg and GitLab answer the runners of GitHub, which many share, with "too many
    requests" more often than not. A token of one's own is counted on its own."""

    def asked(self, environment):
        seen = []

        def urlopen(request, timeout=None):
            seen.append(request)
            return io.BytesIO(b"[]")

        ranged = mock.MagicMock()
        ranged.__enter__.return_value = mock.Mock(status=206, headers={"Content-Range": "bytes 0-0/9", "ETag": "x"})

        def ranged_urlopen(request, timeout=None):
            seen.append(request)
            return ranged

        with mock.patch.dict(os.environ, environment):
            with mock.patch.object(urllib.request, "urlopen", side_effect=urlopen):
                build_forge.forge("/repos/search")
                build_forge.gitlab("/projects")
            with mock.patch.object(urllib.request, "urlopen", side_effect=ranged_urlopen):
                build_forge.remote_file("https://gitlab.com/group/app/-/package_files/5/download")
        return seen

    def test_tokens_go_with_the_requests_to_the_place_they_are_for(self):
        codeberg, gitlab, file = self.asked({"CODEBERG_TOKEN": "c-secret", "GITLAB_TOKEN": "g-secret"})
        self.assertEqual(codeberg.get_header("Authorization"), "token c-secret")
        self.assertEqual(gitlab.get_header("Private-token"), "g-secret")
        # A file is asked for at an address a release link gave, which anyone can write: it
        # gets no token, which would read on its owner's behalf whatever the link points at.
        self.assertIsNone(file.get_header("Private-token"))
        self.assertIsNone(codeberg.get_header("Private-token"))
        self.assertIsNone(gitlab.get_header("Authorization"))
        # And nowhere else: not to wherever an answer may send the request on.
        for request in (codeberg, gitlab, file):
            self.assertNotIn("Authorization", request.headers)
            self.assertNotIn("Private-token", request.headers)

    def test_no_token_is_sent_when_none_is_given(self):
        for request in self.asked({"CODEBERG_TOKEN": "", "GITLAB_TOKEN": ""}):
            self.assertIsNone(request.get_header("Authorization"))
            self.assertIsNone(request.get_header("Private-token"))


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
            "label": "Example", "signer": None, "signerReader": 2, "minSdk": 21, "minSdkCodename": None,
            "sdkReader": 1,
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
        with mock.patch.object(build_forge, "examine", side_effect=error), \
                contextlib.redirect_stderr(io.StringIO()):
            listing = build_forge.Listing(build_forge.Codeberg, found, [REPO],
                                          {"codeberg:owner/app": previous}, NOW)
            listing.work()
            return listing.entries()

    def test_failed_lookup_keeps_the_previous_entry(self):
        self.assertEqual(self.build_list(self.RECENT, found=True), [self.RECENT])

    def test_unforeseen_error_keeps_the_previous_entry_too(self):
        for error in (http.client.IncompleteRead(b""), AttributeError("odd answer")):
            self.assertEqual(self.build_list(self.RECENT, found=True, error=error), [self.RECENT])

    def test_failed_lookup_does_not_keep_a_found_app_past_its_time(self):
        self.assertEqual(self.build_list(self.OLD, found=True), [])
        self.assertEqual(self.build_list(self.OLD, found=False), [self.OLD])

    def test_place_that_cannot_be_searched_keeps_its_lists(self):
        taken = dict(self.OLD, fullName="codeberg:owner/taken")
        published = {"codeberg:owner/taken": taken, "gitlab:group/app": {"fullName": "gitlab:group/app"}}
        found = {"codeberg:owner/app": self.RECENT,
                 "codeberg:owner/taken": dict(self.RECENT, fullName="codeberg:owner/taken"),
                 "codeberg:owner/old": self.OLD}
        with mock.patch.object(build_forge, "search", side_effect=http.client.IncompleteRead(b"")), \
                contextlib.redirect_stderr(io.StringIO()):
            turn = build_forge.Turn(build_forge.Codeberg, published, found, {}, NOW)
            turn.work()
        # What its developer has published in the meantime is no longer among the found.
        self.assertEqual(turn.lists(), ([taken], [self.RECENT]))

    def test_odd_answers_of_a_search_cost_only_themselves(self):
        odd = ["text", None, {"no": "name"}, dict(REPO, full_name="odd/stars", stars_count=None)]
        with mock.patch.object(build_forge, "search", return_value=[REPO] + odd), \
                mock.patch.object(build_forge, "examine", return_value=self.RECENT) as examine:
            turn = build_forge.Turn(build_forge.Codeberg, {}, {}, {}, NOW)
            turn.work()
        self.assertEqual((turn.lists(), examine.call_count), (([], [self.RECENT]), 1))

    def test_list_that_cannot_be_made_keeps_what_it_had(self):
        with mock.patch.object(build_forge, "search", return_value=[dict(REPO, topics=5)]), \
                mock.patch.object(build_forge, "examine", side_effect=AssertionError("asked")) as examine, \
                contextlib.redirect_stderr(io.StringIO()):
            turn = build_forge.Turn(build_forge.Codeberg, {}, {"codeberg:owner/app": self.RECENT}, {}, NOW)
            turn.work()
        self.assertEqual((turn.lists(), examine.call_count), (([], [self.RECENT]), 0))

    def test_odd_entry_of_the_last_run_costs_only_an_examination(self):
        odd = dict(self.RECENT, pushedAt="2026-09-20T10:00:00Z", examinedAt="lately")
        examined = dict(self.RECENT, tag="new")
        with mock.patch.object(build_forge, "examine", return_value=examined), \
                contextlib.redirect_stderr(io.StringIO()):
            listing = build_forge.Listing(build_forge.Codeberg, True, [REPO], {"codeberg:owner/app": odd}, NOW)
            listing.work()
        self.assertEqual(listing.entries(), [examined])

    def test_unusable_repositories_are_told_apart(self):
        self.assertTrue(build_forge.usable(REPO))
        for flag in ("private", "fork", "archived", "mirror", "empty"):
            self.assertFalse(build_forge.usable(dict(REPO, **{flag: True})))


if __name__ == "__main__":
    unittest.main()


TREE = ["README.md", "fastlane/metadata/android/en-US/short_description.txt",
        "fastlane/metadata/android/en-US/full_description.txt",
        "fastlane/metadata/android/en-US/images/phoneScreenshots/1.png",
        "fastlane/metadata/android/fi/short_description.txt"]


class MetadataTest(unittest.TestCase):
    """The fastlane metadata of a repository elsewhere, read the way build_index reads it on
    GitHub, from the place's own list of files and raw addresses."""

    def test_codeberg_lists_the_files_of_the_branch_and_addresses_them_raw(self):
        tree = {"tree": [{"path": path, "type": "blob"} for path in TREE] + [{"path": "fastlane", "type": "tree"}],
                "truncated": False}
        texts = {}
        def raw_text(address):
            texts.setdefault(address, 0)
            texts[address] += 1
            return "Chat for everyone" if "/en-US/" in address else "Juttelua kaikille"
        repo = dict(build_forge.as_repo(dict(REPO, default_branch="main"), "MIT"))
        with mock.patch.object(build_forge, "forge", return_value=tree) as forge, \
                mock.patch.object(build_index, "raw_text", side_effect=raw_text):
            metadata = build_forge.Codeberg.metadata(repo, "20260920100000")
        forge.assert_called_once_with("/repos/owner/app/git/trees/main?recursive=true&per_page=%d&page=1"
                                      % build_forge.TREE_PAGE_SIZE)
        raw = "https://codeberg.org/owner/app/raw/branch/main/fastlane/metadata/android/"
        self.assertEqual(metadata, {
            "en": {"summary": "Chat for everyone", "description": raw + "en-US/full_description.txt?at=20260920100000",
                   "screenshots": [raw + "en-US/images/phoneScreenshots/1.png?at=20260920100000"]},
            "fi": {"summary": "Juttelua kaikille", "screenshots": []},
        })
        self.assertEqual(set(texts), {raw + "en-US/short_description.txt?at=20260920100000",
                                      raw + "fi/short_description.txt?at=20260920100000"})

    def test_codeberg_reads_a_long_list_page_by_page_but_only_so_far(self):
        pages = [{"tree": [{"path": "a%d" % i, "type": "blob"}], "truncated": True} for i in range(10)]
        pages[2]["tree"].append({"path": TREE[2], "type": "blob"})
        with mock.patch.object(build_forge, "forge", side_effect=pages) as forge:
            metadata = build_forge.Codeberg.metadata(build_forge.as_repo(dict(REPO, default_branch="main"), "MIT"), None)
        self.assertEqual(forge.call_count, build_forge.MAX_TREE_PAGES)
        self.assertEqual(list(metadata), ["en"])
        self.assertEqual(metadata["en"]["description"],
                         "https://codeberg.org/owner/app/raw/branch/main/fastlane/metadata/android/en-US/full_description.txt")

    def test_codeberg_repository_without_a_tree_has_no_metadata_but_other_errors_propagate(self):
        repo = build_forge.as_repo(dict(REPO, default_branch="main"), "MIT")
        missing = urllib.error.HTTPError("https://codeberg.org/", 404, "Not Found", {}, None)
        with mock.patch.object(build_forge, "forge", side_effect=missing):
            self.assertEqual(build_forge.Codeberg.metadata(repo, None), {})
        failing = urllib.error.HTTPError("https://codeberg.org/", 502, "Bad Gateway", {}, None)
        with mock.patch.object(build_forge, "forge", side_effect=failing):
            with self.assertRaises(urllib.error.HTTPError):
                build_forge.Codeberg.metadata(repo, None)

    def test_gitlab_lists_the_metadata_folder_page_by_page_and_addresses_the_files_raw(self):
        listed = [{"path": path, "type": "blob"} for path in TREE if path.startswith("fastlane/")]
        pages = {1: listed[:2], 2: listed[2:]}
        asked = []
        def gitlab(path):
            asked.append(path)
            page = int(path.rsplit("page=", 1)[1])
            return pages.get(page, [])
        with mock.patch.object(build_forge, "gitlab", side_effect=gitlab), \
                mock.patch.object(build_forge, "GITLAB_TREE_PAGE_SIZE", 2), \
                mock.patch.object(build_index, "raw_text", return_value="Chat for everyone"):
            metadata = build_forge.GitLab.metadata({"project_id": 12, "forge_name": "group/sub/app",
                                                    "default_branch": "main"}, "1")
        self.assertEqual(asked, [
            "/projects/12/repository/tree?recursive=true&path=fastlane%2Fmetadata%2Fandroid&per_page=2&page=1",
            "/projects/12/repository/tree?recursive=true&path=fastlane%2Fmetadata%2Fandroid&per_page=2&page=2",
            # A full page may have more behind it; only an empty one tells the end.
            "/projects/12/repository/tree?recursive=true&path=fastlane%2Fmetadata%2Fandroid&per_page=2&page=3",
        ])
        raw = "https://gitlab.com/group/sub/app/-/raw/main/fastlane/metadata/android/"
        self.assertEqual(metadata["en"], {
            "summary": "Chat for everyone", "description": raw + "en-US/full_description.txt?at=1",
            "screenshots": [raw + "en-US/images/phoneScreenshots/1.png?at=1"]})

    def test_gitlab_project_without_the_folder_has_no_metadata(self):
        missing = urllib.error.HTTPError("https://gitlab.com/", 404, "Not Found", {}, None)
        with mock.patch.object(build_forge, "gitlab", side_effect=missing):
            self.assertEqual(build_forge.GitLab.metadata({"project_id": 12, "forge_name": "group/sub/app",
                                                          "default_branch": "main"}, None), {})

    def test_examined_gitlab_project_has_its_metadata_read_by_its_path(self):
        # The files are addressed by the project's path, which its description has to carry
        # to where the metadata is read: without it every project with a default branch
        # failed to be examined at all.
        listed = [{"path": path, "type": "blob"} for path in TREE if path.startswith("fastlane/")]

        def gitlab(path):
            if "/repository/tree" in path:
                return listed
            if "/releases" in path:
                return [gitlab_release([link(7, "app.apk", "https://gitlab.com/group/sub/app/-/package_files/5/download")])]
            return dict(PROJECT, license={"key": "mit"}, default_branch="main")

        with mock.patch.object(build_forge, "gitlab", side_effect=gitlab), \
                mock.patch.object(build_forge, "remote_file", return_value=(4321, "one")), \
                mock.patch.object(build_index, "raw_text", return_value="Chat for everyone"), \
                mock.patch.object(build_index, "read_manifest",
                                  return_value=("org.example", 3, "1.0", "Example", None, 21)):
            listing = build_forge.Listing(build_forge.GitLab, True, [PROJECT], {}, NOW)
            listing.work()
            apps = listing.entries()
        self.assertEqual([app["fullName"] for app in apps], ["gitlab:group/sub/app"])
        raw = "https://gitlab.com/group/sub/app/-/raw/main/fastlane/metadata/android/"
        self.assertEqual(apps[0]["metadata"]["en"]["description"],
                         raw + "en-US/full_description.txt?at=20260920100000")

    def test_places_tell_the_default_branch(self):
        self.assertEqual(build_forge.as_repo(dict(REPO, default_branch="develop"), "MIT")["default_branch"], "develop")
        with mock.patch.object(build_forge, "gitlab", return_value=dict(PROJECT, default_branch="trunk",
                                                                         license={"key": "mit"})):
            self.assertEqual(build_forge.GitLab.describe(PROJECT)["default_branch"], "trunk")


class ExamineMetadataTest(unittest.TestCase):
    def test_examined_entry_carries_the_metadata_of_its_place(self):
        metadata = {"en": {"screenshots": ["https://codeberg.org/x/1.png"]}}
        def forge(path, raw=False, limit=None):
            return RELEASES
        with mock.patch.object(build_forge, "forge", side_effect=forge), \
                mock.patch.object(build_forge, "detect_license", return_value="MIT"), \
                mock.patch.object(build_forge.Codeberg, "metadata", return_value=metadata) as read, \
                mock.patch.object(build_index, "read_manifest", return_value=("org.example", 3, "1.0", "Example")):
            app = build_forge.examine(dict(REPO, default_branch="main"), None, NOW, False)
        read.assert_called_once()
        self.assertEqual(read.call_args.args[0]["full_name"], "codeberg:owner/app")
        self.assertEqual(read.call_args.args[1], "20260920100000")
        self.assertEqual(app["metadata"], metadata)
        self.assertEqual(app["metadataAt"], "2026-09-20T10:00:00Z")
        self.assertEqual(app["metadataReader"], build_index.METADATA_READER)

    def test_metadata_known_for_the_push_is_not_read_again(self):
        metadata = {"en": {"screenshots": ["https://codeberg.org/x/1.png"]}}
        previous = {"pushedAt": "2026-09-01T00:00:00Z", "examinedAt": 0, "metadata": metadata,
                    "metadataAt": "2026-09-20T10:00:00Z", "metadataReader": build_index.METADATA_READER}
        def forge(path, raw=False, limit=None):
            return RELEASES
        with mock.patch.object(build_forge, "forge", side_effect=forge), \
                mock.patch.object(build_forge, "detect_license", return_value="MIT"), \
                mock.patch.object(build_forge.Codeberg, "metadata") as read, \
                mock.patch.object(build_index, "read_manifest", return_value=("org.example", 3, "1.0", "Example")):
            app = build_forge.examine(dict(REPO, default_branch="main"), previous, NOW, False)
        read.assert_not_called()
        self.assertEqual(app["metadata"], metadata)


class TimeTakenTest(unittest.TestCase):
    """The lists have so much time between them. A place that would take longer is examined as
    far as its time goes, and the rest keep the entries they had."""
    RECENT = BuildListTest.RECENT
    OLD = BuildListTest.OLD
    OTHER = dict(REPO, full_name="owner/other")

    def test_what_is_left_when_the_time_is_up_keeps_its_entry_without_being_asked_about(self):
        with mock.patch.object(build_forge, "examine", side_effect=AssertionError("asked")) as examine, \
                mock.patch.object(build_forge, "clock", return_value=500.0):
            # As after an error, a found app stays only as long as its release is recent.
            for entry, found, kept in ((self.RECENT, True, [self.RECENT]), (self.OLD, True, []),
                                       (self.OLD, False, [self.OLD])):
                listing = build_forge.Listing(build_forge.Codeberg, found, [REPO], {"codeberg:owner/app": entry}, NOW)
                listing.work(500.0)
                self.assertEqual((listing.entries(), listing.left(), listing.records()), (kept, 1, {}))
        examine.assert_not_called()

    def test_repositories_are_examined_until_the_time_is_up(self):
        examined = {"fullName": "codeberg:owner/app", "tag": "new"}
        kept = dict(self.RECENT, fullName="codeberg:owner/other")
        previous = {"codeberg:owner/app": self.RECENT, "codeberg:owner/other": kept}
        with mock.patch.object(build_forge, "examine", return_value=examined) as examine, \
                mock.patch.object(build_forge, "clock", side_effect=[10.0, 20.0]):
            listing = build_forge.Listing(build_forge.Codeberg, True, [REPO, self.OTHER], previous, NOW)
            listing.work(15.0)
        self.assertEqual(examine.call_count, 1)
        self.assertEqual(listing.entries(), [examined, kept])

    def test_without_a_limit_every_repository_is_examined(self):
        examined = {"fullName": "codeberg:owner/app"}
        with mock.patch.object(build_forge, "examine", return_value=examined) as examine, \
                mock.patch.object(build_forge, "clock", side_effect=AssertionError("no clock is read")):
            build_forge.Listing(build_forge.Codeberg, True, [REPO, self.OTHER], {}, NOW).work()
        self.assertEqual(examine.call_count, 2)

    def test_limit_goes_with_both_lists_of_a_place(self):
        with mock.patch.object(build_forge, "search", return_value=[]), \
                mock.patch.object(build_forge.Listing, "work") as work:
            build_forge.Turn(build_forge.Codeberg, {}, {}, {}, NOW).work(7.0)
        self.assertEqual([call.args for call in work.call_args_list], [(7.0,), (7.0,)])

    def test_each_place_has_its_share_and_what_an_earlier_one_left_over(self):
        self.assertEqual(build_forge.shares(100.0, 2, budget=360), [280.0, 460.0])
        self.assertEqual(build_forge.shares(0.0, 1, budget=360), [360.0])
        # The step of the workflow that runs this is stopped at twelve minutes, with nothing written.
        self.assertLessEqual(build_forge.BUDGET_SECONDS, 8 * 60)

    def test_place_tells_how_much_it_left_for_want_of_time(self):
        with mock.patch.object(build_forge, "search", return_value=[REPO, self.OTHER]), \
                mock.patch.object(build_forge, "examine", side_effect=AssertionError("asked")), \
                mock.patch.object(build_forge, "clock", return_value=500.0), \
                contextlib.redirect_stderr(io.StringIO()) as said, \
                contextlib.redirect_stdout(io.StringIO()) as told:
            turn = build_forge.Turn(build_forge.Codeberg, {}, {}, {}, NOW, until=500.0)
            turn.work(500.0)
            turn.tell()
        self.assertTrue(turn.unfinished())
        self.assertIn("codeberg: out of time, 2 of 2 left as they were", said.getvalue())
        self.assertIn("codeberg: 0 published, 0 found", told.getvalue())


class Clock:
    """A clock that reads what the test makes of it."""

    def __init__(self, at=0.0):
        self.at = at

    def __call__(self):
        return self.at

    def take(self, seconds):
        self.at += seconds


def codeberg(name, stars=7, **more):
    return dict(REPO, full_name=name, html_url="https://codeberg.org/" + name, stars_count=stars, **more)


def record(at=NOW, failures=0, pushed="2026-09-20T10:00:00Z", cut=0):
    made = {"pushedAt": pushed, "at": at, "failures": failures}
    if cut:
        made["cut"] = cut
    return made


def transport(clock, seconds, answer):
    """A urlopen that answers after so many seconds and, like a real one, gives up when it is
    given less time than that."""
    def urlopen(request, timeout=None):
        if timeout is not None and timeout < seconds:
            clock.take(timeout)
            raise TimeoutError("The read operation timed out")
        clock.take(seconds)
        return answer(request)
    return urlopen


class TriedTest(unittest.TestCase):
    """A repository that gave nothing to list, or that could not be read, is remembered in the
    file of its place. The runs after do not spend their time on it again, and so get to the
    repositories behind it."""
    RECENT = BuildListTest.RECENT

    def listing(self, repos, examine, tried=None, previous=None, today=NOW, found=True, until=None, **more):
        with mock.patch.object(build_forge, "examine", side_effect=examine) as asked, \
                contextlib.redirect_stderr(io.StringIO()):
            listing = build_forge.Listing(build_forge.Codeberg, found, repos, previous or {}, today,
                                          tried=tried, **more)
            listing.work(until)
        self.asked = [call.args[0]["full_name"] for call in asked.call_args_list]
        return listing

    def test_what_gave_nothing_is_not_asked_about_again_while_it_stands(self):
        first = self.listing([REPO], [None])
        self.assertEqual(first.records(), {"owner/app": record()})
        again = self.listing([REPO], [None], tried=first.records(), today=NOW + 3600)
        self.assertEqual((self.asked, again.entries(), again.left()), ([], [], 0))
        self.assertEqual(again.records(), first.records())

    def test_a_push_or_a_day_gone_by_has_it_looked_at_again(self):
        pushed = dict(REPO, updated_at="2026-09-21T12:00:00+02:00")
        self.listing([pushed], [None], tried={"owner/app": record()}, today=NOW + 3600)
        self.assertEqual(self.asked, ["owner/app"])
        day = build_index.AUTO_RECHECK_HOURS * 3600
        self.listing([REPO], [None], tried={"owner/app": record()}, today=NOW + day - 1)
        self.assertEqual(self.asked, [])
        again = self.listing([REPO], [None], tried={"owner/app": record()}, today=NOW + day)
        self.assertEqual(self.asked, ["owner/app"])
        self.assertEqual(again.records(), {"owner/app": record(at=NOW + day)})

    def test_what_could_not_be_read_waits_longer_after_each_failure(self):
        previous = {"codeberg:owner/app": self.RECENT}
        slow = TimeoutError("slow")
        first = self.listing([REPO], slow, previous=previous)
        self.assertEqual(first.records(), {"owner/app": record(failures=1)})
        # The entry it had stands meanwhile, and the first failure is tried again by the next run.
        self.assertEqual(first.entries(), [self.RECENT])
        second = self.listing([REPO], slow, tried=first.records(), previous=previous, today=NOW + 1800)
        self.assertEqual(self.asked, ["owner/app"])
        self.assertEqual(second.records(), {"owner/app": record(at=NOW + 1800, failures=2)})
        # The second waits an hour.
        resting = self.listing([REPO], slow, tried=second.records(), previous=previous, today=NOW + 1800 + 3599)
        self.assertEqual((self.asked, resting.entries(), resting.left()), ([], [self.RECENT], 0))
        self.assertEqual(resting.records(), second.records())
        self.listing([REPO], slow, tried=second.records(), previous=previous, today=NOW + 1800 + 3600)
        self.assertEqual(self.asked, ["owner/app"])
        # And none waits longer than a day, however often it has failed.
        often = {"owner/app": record(failures=40)}
        self.listing([REPO], slow, tried=often, previous=previous, today=NOW + 24 * 3600 - 1)
        self.assertEqual(self.asked, [])
        self.listing([REPO], slow, tried=often, previous=previous, today=NOW + 24 * 3600)
        self.assertEqual(self.asked, ["owner/app"])
        self.assertEqual([build_forge.retry_seconds(count) // 3600 for count in range(1, 9)],
                         [0, 1, 2, 4, 8, 16, 24, 24])

    def test_what_is_listed_again_is_forgotten(self):
        listing = self.listing([REPO], [self.RECENT], tried={"owner/app": record(at=NOW - 90000, failures=3)})
        self.assertEqual((listing.entries(), listing.records()), ([self.RECENT], {}))

    def test_the_longest_waiting_go_first(self):
        repos = [codeberg("a/tried", 90), codeberg("b/new", 50), codeberg("c/listed", 10), codeberg("d/new", 60)]
        tried = {"a/tried": record(at=NOW - 90000)}
        previous = {"codeberg:c/listed": dict(self.RECENT, fullName="codeberg:c/listed", examinedAt=NOW - 200000)}
        self.listing(repos, [None] * 4, tried=tried, previous=previous)
        # Those never looked at by their stars, then the others by how long ago they were.
        self.assertEqual(self.asked, ["d/new", "b/new", "c/listed", "a/tried"])

    def test_time_running_out_is_no_failure(self):
        repos = [codeberg("a/one", 9), codeberg("b/two", 8), codeberg("c/three", 7)]
        clock = Clock()

        def examine(*asked):
            clock.take(100)

        with mock.patch.object(build_forge, "clock", clock):
            listing = self.listing(repos, examine, until=150.0)
        self.assertEqual((self.asked, listing.left()), (["a/one", "b/two"], 1))
        # Of what the run did not get to nothing is remembered.
        self.assertEqual(sorted(listing.records()), ["a/one", "b/two"])
        # An examination the time ran out on is remembered as cut short: no failure, and no
        # finding either, so the entry stands. So is one that failed as the time ran out.
        previous = {"codeberg:owner/app": self.RECENT}
        listing = self.listing([REPO], build_forge.OutOfTime(), previous=previous)
        self.assertEqual((listing.entries(), listing.left()), ([self.RECENT], 1))
        self.assertEqual(listing.records(), {"owner/app": record(cut=1)})

        def cut_short(*asked):
            clock.take(28)
            raise TimeoutError("slow")

        clock.at = 100.0
        with mock.patch.object(build_forge, "clock", clock):
            listing = self.listing([REPO], cut_short, previous=previous, until=130.0)
        self.assertEqual((listing.entries(), listing.left()), ([self.RECENT], 1))
        self.assertEqual(listing.records(), {"owner/app": record(cut=1)})

    def test_what_was_cut_short_goes_behind_the_others_and_waits_longer_each_time(self):
        heavy = codeberg("heavy/app", 50)
        stands = dict(self.RECENT, fullName="codeberg:heavy/app")
        previous = {"codeberg:heavy/app": stands}
        first = self.listing([heavy, REPO], build_forge.OutOfTime(), previous=previous)
        self.assertEqual(self.asked, ["heavy/app"])
        self.assertEqual(first.records(), {"heavy/app": record(cut=1)})
        # More time in the same run goes to the others first, and only then to it again.
        with mock.patch.object(build_forge, "examine", side_effect=[self.RECENT, build_forge.OutOfTime()]) as asked:
            first.work()
        self.assertEqual([call.args[0]["full_name"] for call in asked.call_args_list], ["owner/app", "heavy/app"])
        # However often the time runs out on it in one run, that counts once.
        self.assertEqual(first.records(), {"heavy/app": record(cut=1)})
        self.assertEqual(first.entries(), [stands, self.RECENT])
        # The next run begins with what has never been looked at.
        fresh = codeberg("new/app", 5)
        second = self.listing([heavy, fresh], [None, build_forge.OutOfTime()], tried=first.records(),
                              previous=previous, today=NOW + 1800)
        self.assertEqual(self.asked, ["new/app", "heavy/app"])
        self.assertEqual(second.records()["heavy/app"], record(at=NOW + 1800, cut=2))
        # Cut short twice in a row, it waits an hour, with the entry it had.
        resting = self.listing([heavy], [None], tried=second.records(), previous=previous, today=NOW + 1800 + 3599)
        self.assertEqual((self.asked, resting.entries(), resting.left()), ([], [stands], 0))
        self.listing([heavy], [stands], tried=second.records(), previous=previous, today=NOW + 1800 + 3600)
        self.assertEqual(self.asked, ["heavy/app"])

    def three_files(self, read_manifest, tried=None):
        """A listing of a repository whose release has three files, examined for real."""
        files = [dict(RELEASES[0]["assets"][0], id=number, name="app-%d.apk" % number) for number in (1, 2, 3)]
        with mock.patch.object(build_forge, "forge", return_value=[dict(RELEASES[0], assets=files)]), \
                mock.patch.object(build_forge, "detect_license", return_value="MIT"), \
                mock.patch.object(build_index, "read_manifest", side_effect=read_manifest) as read, \
                contextlib.redirect_stderr(io.StringIO()):
            listing = build_forge.Listing(build_forge.Codeberg, True, [REPO], {}, NOW, tried=tried)
            listing.work()
        return listing, read.call_count

    def test_what_an_examination_had_read_is_not_read_again(self):
        manifest = ("org.example", 3, "1.0", "Example")
        for trouble in (build_forge.OutOfTime(), TimeoutError("slow")):
            first, read = self.three_files([manifest, manifest, trouble])
            self.assertEqual((first.entries(), read), ([], 3))
            remembered = json.loads(json.dumps(first.records()))
            self.assertEqual([apk["id"] for apk in remembered["owner/app"]["apks"]], [1, 2])
            second, read = self.three_files([manifest], tried=remembered)
            self.assertEqual(read, 1)
            self.assertEqual([apk["id"] for apk in second.entries()[0]["apks"]], [1, 2, 3])
            self.assertEqual(second.records(), {})

    def test_place_that_refuses_the_builder_is_left_alone_for_the_run(self):
        previous = {"codeberg:owner/app": self.RECENT}
        listing = self.listing([REPO, TimeTakenTest.OTHER], build_forge.Refused(429, "Too Many Requests"),
                               previous=previous)
        self.assertEqual(self.asked, ["owner/app"])
        self.assertEqual((listing.entries(), listing.left()), ([self.RECENT], 2))
        # No failure of the repository. It only goes behind the others, so that it is not the
        # one asked about first in every run, and it waits no longer for that.
        self.assertEqual(listing.records(), {"owner/app": record(cut=1)})
        again = self.listing([REPO], build_forge.Refused(429, "Too Many Requests"), tried=listing.records(),
                             previous=previous, today=NOW + 1800)
        self.assertEqual((self.asked, again.records()), (["owner/app"], {"owner/app": record(at=NOW + 1800, cut=1)}))
        self.assertTrue(listing.stopped)
        listing.work()
        self.assertEqual(listing.left(), 2)

    def test_what_one_list_gave_up_on_the_other_still_looks_at(self):
        topic = dict(REPO, topics=["android", build_index.TOPIC])
        with mock.patch.object(build_forge, "search", return_value=[topic]), \
                mock.patch.object(build_forge, "examine", return_value=self.RECENT) as examine:
            turn = build_forge.Turn(build_forge.Codeberg, {}, {}, {"found": {"owner/app": record()}}, NOW + 60)
            turn.work()
        self.assertEqual(examine.call_count, 1)
        self.assertEqual(turn.lists(), ([self.RECENT], []))

    def test_what_the_search_no_longer_gives_is_forgotten_only_when_the_search_was_whole(self):
        tried = {"owner/app": record(), "gone/away": record()}
        whole = self.listing([REPO], [None], tried=tried, today=NOW + 60)
        self.assertEqual(sorted(whole.records()), ["owner/app"])
        part = self.listing([REPO], [None], tried=tried, today=NOW + 60, seen={"owner/app"}, whole=False)
        self.assertEqual(sorted(part.records()), ["gone/away", "owner/app"])

    def test_odd_or_old_records_are_not_read(self):
        old = NOW - build_forge.TRIED_KEPT_DAYS * 86400 - 1
        read = {"id": 4, "name": "a.apk", "packageName": "org.example", "versionCode": 3, "versionName": "1.0",
                "label": "Example", "signer": None, "minSdk": 21}
        with tempfile.TemporaryDirectory() as folder:
            path = os.path.join(folder, "codeberg.json")
            self.assertEqual(build_forge.load_tried(path, NOW), {"published": {}, "found": {}})
            for written in ("[]", "{", '{"tried": []}', '{"tried": {"found": [], "published": "x"}}'):
                with open(path, "w", encoding="utf-8") as file:
                    file.write(written)
                self.assertEqual(build_forge.load_tried(path, NOW), {"published": {}, "found": {}})
            build_index.write_json(path, {"apps": [], "tried": {"published": {"p/ok": record(failures=2)}, "found": {
                "a/ok": record(), "b/text": "x", "c/no-time": {"failures": 0}, "d/odd-time": record(at="soon"),
                "e/odd-count": record(failures=-1), "f/old": record(at=old), "g/true": record(failures=True),
                "h/odd-push": record(pushed=7), "i/ahead": record(at=NOW + 60), "j/cut": record(cut=2),
                "k/odd-cut": dict(record(), cut="x"), "l/no-cut": dict(record(), cut=-1),
                "m/files": dict(record(failures=1), apks=[read]),
                "n/odd-files": dict(record(), apks=[4]), "o/no-id": dict(record(), apks=[dict(read, id="4")]),
                "q/half-file": dict(record(), apks=[{"id": 4, "name": "a.apk"}]),
            }}})
            self.assertEqual(build_forge.load_tried(path, NOW), {"published": {"p/ok": record(failures=2)}, "found": {
                "a/ok": record(), "j/cut": record(cut=2), "m/files": dict(record(failures=1), apks=[read]),
            }})

    def heavy_run(self, answer, tried, today):
        """A run of a place whose seven most starred repositories each take 28 seconds to give
        answer, with one that has an app behind them."""
        clock = Clock()
        repos = [codeberg("heavy/%d" % number, 100 - number) for number in range(7)] + [codeberg("owner/app", 5)]

        def detect_license(name):
            if not name.startswith("heavy/"):
                return "MIT"
            wait = build_forge.time_left()
            if wait < 28:
                clock.take(wait)
                raise TimeoutError("The read operation timed out")
            clock.take(28)
            return answer()

        with mock.patch.object(build_forge, "clock", clock), \
                mock.patch.object(build_forge, "search", return_value=repos), \
                mock.patch.object(build_forge, "detect_license", side_effect=detect_license), \
                mock.patch.object(build_forge, "forge", return_value=RELEASES), \
                mock.patch.object(build_index, "read_manifest", return_value=("org.example", 3, "1.0", "Example")), \
                contextlib.redirect_stderr(io.StringIO()):
            listing = build_forge.found_listing(build_forge.Codeberg, set(), {}, tried, today)
            listing.work(180.0)
        return listing

    def test_app_behind_repositories_that_give_nothing_is_reached_by_the_next_run(self):
        first = self.heavy_run(lambda: None, {}, NOW)
        self.assertEqual((first.entries(), len(first.records())), ([], 7))
        # Six gave nothing; the seventh had twelve seconds and was cut short.
        self.assertEqual(first.records()["heavy/6"], record(cut=1))
        second = self.heavy_run(lambda: None, first.records(), NOW + 1800)
        self.assertEqual([app["fullName"] for app in second.entries()], ["codeberg:owner/app"])

    def test_app_behind_repositories_that_time_out_is_reached_by_the_next_run(self):
        def slow():
            raise TimeoutError("slow")

        first = self.heavy_run(slow, {}, NOW)
        self.assertEqual(first.entries(), [])
        second = self.heavy_run(slow, first.records(), NOW + 1800)
        self.assertEqual([app["fullName"] for app in second.entries()], ["codeberg:owner/app"])


class DeadlineTest(unittest.TestCase):
    """The time of a place binds its requests too: none begins once it is up, and one that has
    begun waits for the server no longer than what is left."""

    def test_request_waits_as_long_as_there_is_time_left(self):
        clock = Clock(100.0)
        with mock.patch.object(build_forge, "clock", clock):
            self.assertEqual(build_forge.time_left(), 30)
            with build_forge.limited(200.0):
                self.assertEqual(build_forge.time_left(), 30)
                clock.at = 188.0
                self.assertEqual(build_forge.time_left(), 12.0)
                clock.at = 196.0
                with self.assertRaises(build_forge.OutOfTime):
                    build_forge.time_left()
                with build_forge.limited(None):
                    self.assertEqual(build_forge.time_left(), 30)
                with self.assertRaises(build_forge.OutOfTime):
                    build_forge.time_left()
            self.assertEqual(build_forge.time_left(), 30)

    def waits(self, at):
        """How long each kind of request of a place waits when the clock reads at and the
        place's time is up at 100."""
        seen = []

        def urlopen(request, timeout=None):
            seen.append(timeout)
            raise urllib.error.URLError("no answer")

        asks = (lambda: build_forge.forge("/repos/search"), lambda: build_forge.gitlab("/projects"),
                lambda: build_forge.remote_file("https://gitlab.com/group/app/-/package_files/5/download"),
                lambda: build_index.read_range("https://codeberg.org/app.apk", 0, 4),
                lambda: build_index.raw_text("https://codeberg.org/owner/app/raw/branch/main/a.txt"))
        with mock.patch.object(build_forge, "clock", Clock(at)), \
                mock.patch.object(urllib.request, "urlopen", side_effect=urlopen), \
                build_forge.limited(100.0):
            for ask in asks:
                with self.assertRaises(urllib.error.URLError):
                    ask()
        return seen

    def test_every_request_of_a_place_is_bound_by_its_time(self):
        self.assertEqual(self.waits(0.0), [30] * 5)
        self.assertEqual(self.waits(80.0), [20.0] * 5)
        with self.assertRaises(build_forge.OutOfTime):
            self.waits(97.0)
        # Outside a place's turn the index builder's requests wait as they always did.
        self.assertEqual(build_index.request_seconds(), 30)

    def pages(self, search, page, until):
        clock = Clock()
        with mock.patch.object(build_forge, "clock", clock), \
                mock.patch.object(urllib.request, "urlopen",
                                  side_effect=transport(clock, 28, lambda request: io.BytesIO(page))), \
                build_forge.limited(until):
            return search()

    def test_search_stops_between_its_pages_and_says_it_is_not_whole(self):
        full = json.dumps({"data": [codeberg("owner/app%d" % number) for number in range(build_forge.PAGE_SIZE)]})
        found = self.pages(lambda: build_forge.search("android"), full.encode(), 60.0)
        self.assertEqual((len(found), found.whole), (2 * build_forge.PAGE_SIZE, False))
        last = json.dumps({"data": [REPO]}).encode()
        found = self.pages(lambda: build_forge.search("android"), last, 60.0)
        self.assertEqual((len(found), found.whole), (1, True))
        projects = json.dumps([PROJECT] * build_forge.GITLAB_PAGE_SIZE).encode()
        found = self.pages(lambda: build_forge.GitLab.search("android", None), projects, 60.0)
        self.assertEqual((len(found), found.whole), (2 * build_forge.GITLAB_PAGE_SIZE, False))
        found = self.pages(lambda: build_forge.GitLab.search("android", None), b"[]", None)
        self.assertEqual((len(found), found.whole), (0, True))
        # A page asked for with little time left may not come in that time. The pages before
        # it are kept all the same.
        found = self.pages(lambda: build_forge.search("android"), full.encode(), 62.0)
        self.assertEqual((len(found), found.whole), (2 * build_forge.PAGE_SIZE, False))
        found = self.pages(lambda: build_forge.GitLab.search("android", None), projects, 62.0)
        self.assertEqual((len(found), found.whole), (2 * build_forge.GITLAB_PAGE_SIZE, False))
        # A search whose first page does not come has failed.
        with self.assertRaises(TimeoutError):
            self.pages(lambda: build_forge.search("android"), full.encode(), 20.0)

    def test_repositories_of_the_pages_read_before_one_timed_out_are_examined(self):
        clock = Clock()

        def page(request):
            number = int(re.search(r"[?&]page=(\d+)", request.full_url).group(1))
            count = build_forge.PAGE_SIZE if number < 4 else 1
            return io.BytesIO(json.dumps({"data": [
                codeberg("owner/page%d-%d" % (number, place)) for place in range(count)]}).encode())

        slow = transport(clock, 28, page)

        def urlopen(request, timeout=None):
            if "q=android&" not in request.full_url:
                return io.BytesIO(b'{"data":[]}')
            return slow(request, timeout)

        def examine(repo, *rest):
            return {"fullName": "codeberg:" + repo["full_name"], "publishedAt": "2026-09-01T08:00:00Z"}

        unseen = dict(BuildListTest.RECENT, fullName="codeberg:owner/unseen")
        with mock.patch.object(build_forge, "clock", clock), \
                mock.patch.object(urllib.request, "urlopen", side_effect=urlopen), \
                mock.patch.object(build_forge, "examine", side_effect=examine), \
                contextlib.redirect_stderr(io.StringIO()):
            turn = build_forge.Turn(build_forge.Codeberg, {}, {"codeberg:owner/unseen": unseen}, {}, NOW, until=180.0)
            turn.work(180.0)
        found = turn.lists()[1]
        # Three pages came in 28 seconds each; the fourth had six seconds and did not.
        self.assertEqual(clock.at, 90.0)
        self.assertEqual(len(found), 3 * build_forge.PAGE_SIZE + 1)
        self.assertIn(unseen, found)

    def test_what_a_search_cut_short_did_not_get_to_stands_as_it_was(self):
        part = build_forge.Found([REPO])
        part.whole = False
        examined = dict(BuildListTest.RECENT, tag="new")
        unseen = dict(BuildListTest.RECENT, fullName="codeberg:owner/unseen")
        old = dict(BuildListTest.OLD, fullName="codeberg:owner/old")
        published = {"codeberg:owner/published": {"fullName": "codeberg:owner/published"}}
        found = {"codeberg:owner/app": BuildListTest.RECENT, "codeberg:owner/unseen": unseen,
                 "codeberg:owner/old": old}
        tried = {"found": {"gone/away": record()}}
        for searched, lists, remembered in (
                (part, ([published["codeberg:owner/published"]], [examined, unseen]), ["gone/away"]),
                ([REPO], ([], [examined]), [])):
            with mock.patch.object(build_forge, "search", return_value=searched), \
                    mock.patch.object(build_forge, "examine", return_value=examined), \
                    contextlib.redirect_stderr(io.StringIO()):
                turn = build_forge.Turn(build_forge.Codeberg, published, found, tried, NOW + 60)
                turn.work()
            self.assertEqual(turn.lists(), lists)
            self.assertEqual(sorted(turn.tried()["found"]), remembered)

    def test_searches_have_only_a_part_of_the_time_of_their_place(self):
        limits = []

        def search(query):
            limits.append(build_forge.time_up)
            return []

        with mock.patch.object(build_forge, "search", side_effect=search), \
                mock.patch.object(build_forge, "clock", Clock(100.0)):
            build_forge.Turn(build_forge.Codeberg, {}, {}, {}, NOW, until=280.0)
        self.assertEqual(limits, [100.0 + 180.0 * build_forge.SEARCH_SHARE] * 3)
        self.assertIsNone(build_forge.time_up)


class RefusalTest(unittest.TestCase):
    """A place refusing the builder itself, because its token is not accepted or too much has
    been asked, is told apart from a file that cannot be had: only the place's own API says
    the first."""

    def answer(self, code, ask):
        error = urllib.error.HTTPError("https://example.org/x", code, "No", {}, None)
        with mock.patch.object(urllib.request, "urlopen", side_effect=error):
            ask()

    def test_only_an_answer_of_the_places_own_api_is_a_refusal(self):
        for ask in (lambda: build_forge.forge("/repos/search"), lambda: build_forge.gitlab("/projects")):
            for code in (401, 429):
                with self.assertRaises(build_forge.Refused):
                    self.answer(code, ask)
            for code in (403, 404, 500):
                with self.assertRaises(urllib.error.HTTPError):
                    self.answer(code, ask)
        for ask in (lambda: build_forge.remote_file("https://gitlab.com/group/app/-/package_files/5/download"),
                    lambda: build_index.read_range("https://codeberg.org/app.apk", 0, 4),
                    lambda: build_index.raw_text("https://codeberg.org/owner/app/raw/branch/main/a.txt")):
            for code in (401, 429):
                with self.assertRaises(urllib.error.HTTPError):
                    self.answer(code, ask)

    def test_file_that_asks_for_a_login_is_its_repositorys_failure_alone(self):
        locked = "https://gitlab.com/api/v4/projects/999/packages/generic/app/1/app.apk"
        bad = dict(PROJECT, id=1, path_with_namespace="group/bad", web_url="https://gitlab.com/group/bad",
                   star_count=9)
        good = dict(PROJECT, id=2, path_with_namespace="group/good", web_url="https://gitlab.com/group/good")
        links = {1: link(7, "app.apk", locked), 2: link(8, "app.apk", GitLabTest.APK)}
        sized = mock.MagicMock()
        sized.__enter__.return_value = mock.Mock(status=206, headers={"Content-Range": "bytes 0-0/9", "ETag": "x"})

        def gitlab(path):
            project = int(re.match(r"/projects/(\d+)", path).group(1))
            if "/releases" in path:
                return [gitlab_release([links[project]])]
            return dict(bad if project == 1 else good, license={"key": "mit"})

        def urlopen(request, timeout=None):
            if request.full_url == locked:
                raise urllib.error.HTTPError(locked, 401, "Unauthorized", {}, None)
            return sized

        with mock.patch.object(build_forge, "gitlab", side_effect=gitlab), \
                mock.patch.object(urllib.request, "urlopen", side_effect=urlopen), \
                mock.patch.object(build_index, "read_manifest",
                                  return_value=("org.example", 3, "1.0", "Example", None, 21)), \
                contextlib.redirect_stderr(io.StringIO()):
            listing = build_forge.Listing(build_forge.GitLab, True, [bad, good], {}, NOW)
            listing.work()
        self.assertEqual([app["fullName"] for app in listing.entries()], ["gitlab:group/good"])
        self.assertEqual(listing.records(), {"group/bad": record(failures=1, pushed="2026-09-20T10:00:00Z")})
        self.assertFalse(listing.stopped)


class MoveTest(unittest.TestCase):
    """An app on its way from one list to the other stays where it was until the move has been
    made: a repository given the store topic leaves the found only for the published, and the
    other way round."""
    KNOWN = BuildListTest.RECENT
    TOPIC = dict(REPO, topics=["android", build_index.TOPIC])

    def turn(self, published, found, topic_repos, other_repos, examine, until=None, at=0.0):
        def search(query):
            return topic_repos if query == build_index.TOPIC else other_repos

        self.clock = Clock(at)
        for patch in (mock.patch.object(build_forge, "search", side_effect=search),
                      mock.patch.object(build_forge, "examine", side_effect=examine),
                      mock.patch.object(build_forge, "clock", self.clock),
                      contextlib.redirect_stderr(io.StringIO())):
            patch.__enter__()
            self.addCleanup(patch.__exit__, None, None, None)
        turn = build_forge.Turn(build_forge.Codeberg, published, found, {}, NOW, until=until)
        turn.work(until)
        return turn

    def test_app_given_the_store_topic_stays_among_the_found_until_it_is_examined(self):
        slow = codeberg("owner/slow", 9, topics=[build_index.TOPIC])

        def examine(repo, *rest):
            if repo["full_name"] == "owner/slow":
                self.clock.take(196)
            return {"fullName": "codeberg:" + repo["full_name"], "publishedAt": "2026-09-01T08:00:00Z"}

        turn = self.turn({}, {"codeberg:owner/app": self.KNOWN}, [slow, self.TOPIC], [slow, self.TOPIC], examine,
                         until=180.0)
        published, found = turn.lists()
        self.assertEqual([app["fullName"] for app in published], ["codeberg:owner/slow"])
        self.assertEqual(found, [self.KNOWN])
        self.assertTrue(turn.unfinished())
        # With time for it, the move is made.
        turn.work()
        published, found = turn.lists()
        self.assertEqual([app["fullName"] for app in published], ["codeberg:owner/app", "codeberg:owner/slow"])
        self.assertEqual(found, [])

    def test_app_that_could_not_be_read_as_published_stays_among_the_found_too(self):
        turn = self.turn({}, {"codeberg:owner/app": self.KNOWN}, [self.TOPIC], [self.TOPIC],
                         urllib.error.URLError("down"))
        self.assertEqual(turn.lists(), ([], [self.KNOWN]))

    def test_app_with_nothing_to_publish_leaves_the_found_as_before(self):
        turn = self.turn({}, {"codeberg:owner/app": self.KNOWN}, [self.TOPIC], [self.TOPIC], [None])
        self.assertEqual(turn.lists(), ([], []))

    def test_app_that_lost_the_store_topic_stays_published_until_it_is_examined(self):
        found = dict(self.KNOWN, tag="found")
        turn = self.turn({"codeberg:owner/app": self.KNOWN}, {}, [], [REPO], [found], until=500.0, at=500.0)
        self.assertEqual(turn.lists(), ([self.KNOWN], []))
        turn.work()
        self.assertEqual(turn.lists(), ([], [found]))

    def test_app_that_lost_the_store_topic_does_not_stay_published_over_a_failure(self):
        # Its developer has withdrawn it: as before, it is listed again once it can be read.
        turn = self.turn({"codeberg:owner/app": self.KNOWN}, {}, [], [REPO], urllib.error.URLError("down"))
        self.assertEqual(turn.lists(), ([], []))

    def test_app_whose_repository_is_no_longer_found_leaves_the_published(self):
        turn = self.turn({"codeberg:owner/app": self.KNOWN}, {}, [], [], AssertionError("asked"))
        self.assertEqual(turn.lists(), ([], []))


class RunTest(unittest.TestCase):
    """The run as a whole: every place has its turn, what one leaves of its time goes to the
    others, and the files are written with what is remembered."""

    def run_main(self, previous, output):
        arguments = ["build_forge.py", "--previous-dir", previous, "--output-dir", output]
        with mock.patch.object(sys, "argv", arguments), \
                contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            build_forge.main()
        files = {}
        for name in sorted(os.listdir(output)):
            with open(os.path.join(output, name), encoding="utf-8") as file:
                files[name] = json.load(file)
        return files

    def test_each_place_is_told_its_time_and_gets_what_another_left(self):
        turns = []

        class Turn:
            def __init__(self, place, published, found, tried, today, icons=None, until=None):
                self.source = place.source
                self.asked = [("searched", until)]
                turns.append(self)

            def work(self, until=None):
                self.asked.append(("examined", until))

            def unfinished(self):
                return self.source == "codeberg"

            def lists(self):
                return [], []

            def tried(self):
                return {"published": {}, "found": {}}

            def tell(self):
                pass

        with tempfile.TemporaryDirectory() as folder, \
                mock.patch.object(build_forge, "Turn", Turn), \
                mock.patch.object(build_forge, "clock", return_value=1000.0):
            files = self.run_main(folder, os.path.join(folder, "out"))
        self.assertEqual(sorted(files), ["codeberg.json", "forge.json", "gitlab.json"])
        first, second = build_forge.shares(1000.0, 2)
        self.assertEqual([(turn.source, turn.asked) for turn in turns], [
            ("codeberg", [("searched", first), ("examined", first), ("examined", second)]),
            ("gitlab", [("searched", second), ("examined", second)]),
        ])

    def test_what_is_remembered_is_written_and_read_by_the_next_run(self):
        with tempfile.TemporaryDirectory() as folder, \
                mock.patch("time.time", return_value=float(NOW)), \
                mock.patch.object(build_forge, "search", return_value=[REPO]), \
                mock.patch.object(build_forge.GitLab, "search", return_value=[]), \
                mock.patch.object(build_forge, "examine", return_value=None) as examine:
            first = self.run_main(os.path.join(folder, "none"), os.path.join(folder, "one"))
            self.assertEqual(examine.call_count, 1)
            second = self.run_main(os.path.join(folder, "one"), os.path.join(folder, "two"))
            self.assertEqual(examine.call_count, 1)
        for files in (first, second):
            self.assertEqual(sorted(files), ["codeberg.json", "forge.json", "gitlab.json"])
            self.assertEqual(files["codeberg.json"]["tried"], {"published": {}, "found": {"owner/app": record()}})
            self.assertEqual(files["gitlab.json"]["tried"], {"published": {}, "found": {}})
            # The app reads the lists of apps; what is remembered is beside them, not among them.
            self.assertEqual([files[name]["apps"] for name in sorted(files)], [[], [], []])
            self.assertNotIn("tried", files["forge.json"])

    def test_slow_search_of_one_place_leaves_the_other_its_time(self):
        clock = Clock()
        forks = [codeberg("fork/%d" % number, fork=True) for number in range(build_forge.PAGE_SIZE)]
        page = json.dumps({"data": forks}).encode()
        began = []

        slow = transport(clock, 28, lambda request: io.BytesIO(page))

        def urlopen(request, timeout=None):
            self.assertIn("https://codeberg.org/api/v1/repos/search?", request.full_url)
            if "q=%s&" % build_index.TOPIC in request.full_url:
                return io.BytesIO(b'{"data":[]}')
            return slow(request, timeout)

        def gitlab(path):
            began.append(clock.at)
            if path.startswith("/projects?"):
                return [PROJECT] if "topic=android&" in path else []
            if "/releases" in path:
                return [gitlab_release([link(7, "app.apk", GitLabTest.APK)])]
            return dict(PROJECT, license={"key": "mit"})

        with tempfile.TemporaryDirectory() as folder, \
                mock.patch("time.time", return_value=float(NOW)), \
                mock.patch.object(build_forge, "clock", clock), \
                mock.patch.object(urllib.request, "urlopen", side_effect=urlopen), \
                mock.patch.object(build_forge, "gitlab", side_effect=gitlab), \
                mock.patch.object(build_forge, "remote_file", return_value=(4321, "one")), \
                mock.patch.object(build_index, "read_manifest",
                                  return_value=("org.example", 3, "1.0", "Example", None, 21)):
            files = self.run_main(folder, os.path.join(folder, "out"))
        self.assertEqual([app["fullName"] for app in files["gitlab.json"]["apps"]], ["gitlab:group/sub/app"])
        self.assertEqual(files["codeberg.json"]["apps"], [])
        # Codeberg's searches were stopped within its own time, long before the time of both was up.
        self.assertLess(began[0], build_forge.BUDGET_SECONDS / 2)
        self.assertLess(clock.at, build_forge.BUDGET_SECONDS)

    def test_repository_too_slow_for_one_turn_does_not_keep_the_others_waiting(self):
        head = codeberg("owner/head", 50)
        address = "https://codeberg.org/owner/head/releases/download/v1/head-%d.apk"
        files = [{"id": number, "name": "head-%d.apk" % number, "size": 100, "download_count": 0,
                  "browser_download_url": address % number} for number in range(1, 8)]
        releases = {"owner/head": [dict(RELEASES[0], assets=files)], "owner/app": RELEASES}
        clock = Clock()
        read = []

        def forge(path, raw=False, limit=None):
            return releases[path.split("/repos/")[1].split("/releases")[0]]

        def read_manifest(url, size, labels=None, icons=None):
            if "/owner/head/" not in url:
                return "org.example", 3, "1.0", "Example"
            read.append(int(url.rsplit("-", 1)[1].split(".")[0]))
            wait = build_index.request_seconds()
            if wait < 28:
                clock.take(wait)
                raise TimeoutError("The read operation timed out")
            clock.take(28)
            return "org.head", 3, "1.0", "Head"

        runs = []
        with tempfile.TemporaryDirectory() as folder, \
                mock.patch.object(build_forge, "clock", clock), \
                mock.patch.object(build_forge, "search", return_value=[head, REPO]), \
                mock.patch.object(build_forge.GitLab, "search", return_value=[]), \
                mock.patch.object(build_forge, "detect_license", return_value="MIT"), \
                mock.patch.object(build_forge, "forge", side_effect=forge), \
                mock.patch.object(build_index, "read_manifest", side_effect=read_manifest):
            for run in range(2):
                clock.at = 0.0
                del read[:]
                with mock.patch("time.time", return_value=float(NOW + 1800 * run)):
                    written = self.run_main(os.path.join(folder, str(run)), os.path.join(folder, str(run + 1)))
                runs.append(([app["fullName"] for app in written["codeberg.json"]["apps"]], list(read), clock.at,
                             written["codeberg.json"]["tried"]["found"]))
        # Six files are read in the first turn and the seventh is cut short. The time GitLab
        # left goes to the repository behind first, and then to the seventh file alone.
        both = ["codeberg:owner/app", "codeberg:owner/head"]
        self.assertEqual(runs[0], (both, [1, 2, 3, 4, 5, 6, 7, 7], 208.0, {}))
        self.assertEqual(runs[1], (both, [], 0.0, {}))
