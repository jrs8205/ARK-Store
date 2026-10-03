"""Tests for the index builder. Run with: python3 -m unittest discover tools"""

import json
import os
import struct
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
            "packageName": "org.example", "versionCode": 4, "versionName": "1.4", "label": "Example",
        }}
        with mock.patch.object(build_index, "api", return_value=release("new.apk")), \
                mock.patch.object(build_index, "read_manifest") as read_manifest:
            app = build_index.build_app(REPO, previous)
        read_manifest.assert_not_called()
        apk = app["apks"][0]
        self.assertEqual(apk["name"], "new.apk")
        self.assertEqual(apk["url"], "https://example.invalid/new.apk")
        self.assertEqual((apk["packageName"], apk["versionCode"]), ("org.example", 4))
        self.assertEqual(apk["label"], "Example")

    def test_apk_known_without_a_label_is_read_again(self):
        previous = {7: {
            "id": 7, "name": "app.apk", "url": "https://example.invalid/app.apk", "size": 100,
            "packageName": "org.example", "versionCode": 4, "versionName": "1.4",
        }}
        with mock.patch.object(build_index, "api", return_value=release()), \
                mock.patch.object(build_index, "read_manifest",
                                  return_value=("org.example", 4, "1.4", "Example")) as read_manifest:
            app = build_index.build_app(REPO, previous)
        read_manifest.assert_called_once()
        self.assertEqual(app["apks"][0]["label"], "Example")

    def test_apk_known_to_have_no_label_is_not_read_again(self):
        previous = {7: {
            "id": 7, "name": "app.apk", "url": "https://example.invalid/app.apk", "size": 100,
            "packageName": "org.example", "versionCode": 4, "versionName": "1.4", "label": None,
        }}
        with mock.patch.object(build_index, "api", return_value=release()), \
                mock.patch.object(build_index, "read_manifest") as read_manifest:
            app = build_index.build_app(REPO, previous)
        read_manifest.assert_not_called()
        self.assertIsNone(app["apks"][0]["label"])

    def test_apk_known_without_an_icon_is_read_again_when_icons_are_kept(self):
        previous = {7: {
            "id": 7, "name": "app.apk", "url": "https://example.invalid/app.apk", "size": 100,
            "packageName": "org.example", "versionCode": 4, "versionName": "1.4", "label": "Example",
        }}
        icons = build_index.IconStore("unused", "https://x/icons")

        def read_manifest(url, size, labels=None, icons=None):
            icons.found["org.example"] = "https://x/icons/org.example-abc.png"
            return "org.example", 4, "1.4", "Example"

        with mock.patch.object(build_index, "api", return_value=release()), \
                mock.patch.object(build_index, "read_manifest", side_effect=read_manifest) as reader:
            app = build_index.build_app(REPO, previous, icons=icons)
        reader.assert_called_once()
        self.assertEqual(app["apks"][0]["icon"], "https://x/icons/org.example-abc.png")
        # Without a store the entry is as it was.
        with mock.patch.object(build_index, "api", return_value=release()), \
                mock.patch.object(build_index, "read_manifest") as reader:
            app = build_index.build_app(REPO, previous)
        reader.assert_not_called()
        self.assertNotIn("icon", app["apks"][0])

    def test_apk_known_to_have_no_icon_is_not_read_again(self):
        previous = {7: {
            "id": 7, "name": "app.apk", "url": "https://example.invalid/app.apk", "size": 100,
            "packageName": "org.example", "versionCode": 4, "versionName": "1.4", "label": "Example",
            "icon": None,
        }}
        icons = build_index.IconStore("unused", "https://x/icons")
        with mock.patch.object(build_index, "api", return_value=release()), \
                mock.patch.object(build_index, "read_manifest") as reader:
            app = build_index.build_app(REPO, previous, icons=icons)
        reader.assert_not_called()
        self.assertIsNone(app["apks"][0]["icon"])

    def test_downloads_of_prereleases_are_also_counted_apart(self):
        releases = [
            {"tag_name": "v2-beta.1", "prerelease": True, "assets": [asset("b.apk", 2)]},
            dict(release()[0]),
        ]
        with mock.patch.object(build_index, "api", return_value=releases), \
                mock.patch.object(build_index, "read_manifest",
                                  return_value=("org.example", 1, "1", None)):
            app = build_index.build_app(REPO, {})
        self.assertEqual((app["downloads"], app["betaDownloads"]), (6, 1))

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


def chunk(kind, header, body=b""):
    """A chunk of Android's binary resource formats: type, header size, total size."""
    return struct.pack("<HHI", kind, 8 + len(header), 8 + len(header) + len(body)) + header + body


def string_pool(strings):
    """A UTF-16 string pool."""
    offsets = b""
    data = b""
    for text in strings:
        offsets += struct.pack("<I", len(data))
        data += struct.pack("<H", len(text)) + text.encode("utf-16-le") + b"\0\0"
    header = struct.pack("<IIIII", len(strings), 0, 0, 28 + len(offsets), 0)
    return chunk(0x0001, header, offsets + data)


def simple_entry(data_type, data):
    return struct.pack("<HHI", 8, 0, 0) + struct.pack("<HBBI", 8, 0, data_type, data)


def type_chunk(type_id, entries, language=b"\0\0", flags=0, density=0):
    """A type chunk holding entries, a list of encoded entries or None, for one language and
    screen density."""
    config = struct.pack("<I", 16) + b"\0" * 4 + language + b"\0" * 4 + struct.pack("<H", density)
    offsets = b""
    body = b""
    for index, entry in enumerate(entries):
        if flags & 0x01:
            if entry is not None:
                offsets += struct.pack("<HH", index, len(body) // 4)
        elif flags & 0x02:
            offsets += struct.pack("<H", 0xFFFF if entry is None else len(body) // 4)
        else:
            offsets += struct.pack("<I", 0xFFFFFFFF if entry is None else len(body))
        body += entry or b""
    count = sum(e is not None for e in entries) if flags & 0x01 else len(entries)
    offsets += b"\0" * (-len(offsets) % 4)
    header = struct.pack("<BBHII", type_id, flags, 0, count, 20 + len(config) + len(offsets))
    return chunk(0x0201, header + config, offsets + body)


def resource_table(strings, *types):
    package = chunk(0x0200, struct.pack("<I", 0x7F) + b"\0" * 276, b"".join(types))
    return chunk(0x0002, struct.pack("<I", 1), string_pool(strings) + package)


def manifest_element(name, attributes):
    """A start tag; attributes are (name index, type, data) with the name's index doubling as
    its place in the resource map."""
    body = struct.pack("<iiHHHHHH", -1, name, 20, 20, len(attributes), 0, 0, 0)
    for attribute, data_type, data in attributes:
        raw = data if data_type == 0x03 else -1
        body += struct.pack("<iiiHBBI", -1, attribute, raw, 8, 0, data_type, data)
    return chunk(0x0102, struct.pack("<Ii", 1, -1), body)


def manifest(label, icon=None):
    """A binary manifest of org.example, version 3, whose application has the given label
    attribute as (type, data), or none, and refers to the icon resource icon, if any."""
    strings = ["versionCode", "label", "package", "manifest", "application", "org.example", "Plain", "icon"]
    resource_map = chunk(0x0180, b"", struct.pack("<8I", 0x0101021B, 0x01010001, 0, 0, 0, 0, 0, 0x01010002))
    elements = manifest_element(3, [(0, 0x10, 3), (2, 0x03, 5)])
    attributes = [(1,) + label] if label else []
    if icon:
        attributes.append((7, 0x01, icon))
    elements += manifest_element(4, attributes)
    return chunk(0x0003, b"", string_pool(strings) + resource_map + elements)


def adaptive_icon(background, foreground):
    """The binary XML of an adaptive icon whose layers refer to the given resources; a layer
    given as None is left out."""
    strings = ["drawable", "adaptive-icon", "background", "foreground"]
    resource_map = chunk(0x0180, b"", struct.pack("<I", 0x01010199))
    elements = manifest_element(1, [])
    if background:
        elements += manifest_element(2, [(0, 0x01, background)])
    if foreground:
        elements += manifest_element(3, [(0, 0x01, foreground)])
    return chunk(0x0003, b"", string_pool(strings) + resource_map + elements)


def end_element(name):
    """An end tag."""
    return chunk(0x0103, struct.pack("<Ii", 1, -1), struct.pack("<ii", -1, name))


def as_float(number):
    """A float the way binary XML stores one."""
    return struct.unpack("<I", struct.pack("<f", number))[0]


VECTOR_STRINGS = ["vector", "viewportWidth", "viewportHeight", "group", "rotation", "pivotX", "scaleX",
                  "path", "pathData", "fillColor", "strokeColor", "strokeWidth", "fillType", "clip-path",
                  "M0 0h10v10z", "M5 5h2v2z", "attr", "name", "android:fillColor", "gradient", "item", "color",
                  "startColor", "translateY", "M1 1", "shape", "solid", "selector"]


def document(*elements):
    """A binary XML document of the given elements, which refer to VECTOR_STRINGS by index for
    element and attribute names alike."""
    return chunk(0x0003, b"", string_pool(VECTOR_STRINGS) + b"".join(elements))


def vector_document(*elements, viewport=(108.0, 108.0)):
    """The binary XML of a vector drawable with the given elements inside."""
    body = manifest_element(0, [(1, 0x04, as_float(viewport[0])), (2, 0x04, as_float(viewport[1]))])
    return document(body + b"".join(elements) + end_element(0))


class VectorTest(unittest.TestCase):
    PATH = manifest_element(7, [(8, 0x03, 14), (9, 0x1C, 0xFF112233)]) + end_element(7)

    def test_paths_and_groups_are_described_the_way_the_app_draws_them(self):
        group = manifest_element(3, [(4, 0x04, as_float(45)), (5, 0x04, as_float(54)), (6, 0x10, 2)])
        clip = manifest_element(13, [(8, 0x03, 15)]) + end_element(13)
        stroked = manifest_element(7, [(8, 0x03, 15), (10, 0x1D, 0x445566), (11, 0x05, (3 << 8) | 1),
                                       (12, 0x10, 1)]) + end_element(7)
        document = vector_document(group + clip + self.PATH + end_element(3) + stroked)
        self.assertEqual(build_index.vector_description(document, None), {
            "width": 108.0, "height": 108.0,
            "root": {"nodes": [
                {"rotation": 45.0, "pivotX": 54.0, "scaleX": 2, "clip": "M5 5h2v2z",
                 "nodes": [{"path": "M0 0h10v10z", "fill": "#ff112233"}]},
                {"path": "M5 5h2v2z", "stroke": "#ff445566", "strokeWidth": 3.0, "fillType": 1},
            ]},
        })

    def test_gradient_is_drawn_in_its_first_colour(self):
        gradient = manifest_element(7, [(8, 0x03, 14)])
        gradient += manifest_element(16, [(17, 0x03, 18)]) + manifest_element(19, [(22, 0x1C, 0xFF000001)])
        gradient += manifest_element(20, [(21, 0x1C, 0xFFABCDEF)]) + end_element(20)
        gradient += end_element(19) + end_element(16) + end_element(7)
        document = vector_document(gradient)
        self.assertEqual(build_index.vector_description(document, None)["root"]["nodes"],
                         [{"path": "M0 0h10v10z", "fill": "#ff000001"}])

    def test_colours_and_path_data_kept_in_resources_are_looked_up(self):
        table = resource_table(["M1 1"], type_chunk(2, [simple_entry(0x03, 0)]),
                               type_chunk(4, [simple_entry(0x1E, 0x8ABC)]))
        resources = build_index.Resources(table)
        path = manifest_element(7, [(8, 0x01, 0x7F020000), (9, 0x01, 0x7F040000), (23, 0x01, 0x7F040000)])
        document = vector_document(path + end_element(7))
        self.assertEqual(build_index.vector_description(document, resources)["root"]["nodes"],
                         [{"path": "M1 1", "fill": "#88aabbcc"}])

    def test_shape_is_drawn_as_a_square_of_its_colour(self):
        solid = document(manifest_element(25, []) + manifest_element(26, [(21, 0x1C, 0xFF123456)])
                         + end_element(26) + end_element(25))
        self.assertEqual(build_index.vector_description(solid, None),
                         {"width": 1, "height": 1, "root": {"nodes": [{"path": "M0 0h1v1h-1z", "fill": "#ff123456"}]}})
        gradient = document(manifest_element(25, []) + manifest_element(19, [(22, 0x1D, 0x002A6B)])
                            + end_element(19) + end_element(25))
        self.assertEqual(build_index.vector_description(gradient, None)["root"]["nodes"][0]["fill"], "#ff002a6b")
        self.assertIsNone(build_index.vector_description(document(manifest_element(25, []) + end_element(25)), None))

    def test_colour_described_in_a_file_is_read_in_its_first_colour(self):
        gradient = document(manifest_element(19, [(22, 0x1C, 0xFF0000FF)]) + end_element(19))
        selector = document(manifest_element(27, []) + manifest_element(20, [(21, 0x1C, 0xFF00FF00)])
                            + end_element(20) + end_element(27))
        files = {"res/color/gradient.xml": gradient, "res/color/selector.xml": selector, "res/color/odd.xml": b""}
        table = resource_table(list(files), type_chunk(4, [simple_entry(0x03, 0), simple_entry(0x03, 1),
                                                           simple_entry(0x03, 2)]))
        resources = build_index.Resources(table, files.get)
        self.assertEqual(resources.color(0x7F040000), "#ff0000ff")
        self.assertEqual(resources.color(0x7F040001), "#ff00ff00")
        self.assertIsNone(resources.color(0x7F040002))
        self.assertIsNone(build_index.Resources(table).color(0x7F040000))

    def test_other_drawables_and_empty_canvases_are_none(self):
        self.assertIsNone(build_index.vector_description(adaptive_icon(None, 0x7F050000), None))
        self.assertIsNone(build_index.vector_description(vector_document(viewport=(0.0, 24.0)), None))


class ManifestTest(unittest.TestCase):
    def test_label_written_out_is_returned_as_text(self):
        self.assertEqual(build_index.parse_manifest(manifest((0x03, 6))), ("org.example", 3, None, "Plain", None))

    def test_label_kept_in_a_resource_is_returned_as_its_id(self):
        self.assertEqual(build_index.parse_manifest(manifest((0x01, 0x7F020001)))[3], 0x7F020001)

    def test_application_without_a_label_has_none(self):
        self.assertEqual(build_index.parse_manifest(manifest(None)), ("org.example", 3, None, None, None))

    def test_icon_is_returned_as_the_id_of_its_resource(self):
        self.assertEqual(build_index.parse_manifest(manifest(None, icon=0x7F030000))[4], 0x7F030000)

    def test_adaptive_icon_names_the_resources_of_its_layers(self):
        self.assertEqual(
            build_index.adaptive_layers(adaptive_icon(0x7F040000, 0x7F050000)),
            {"background": 0x7F040000, "foreground": 0x7F050000},
        )
        self.assertEqual(build_index.adaptive_layers(adaptive_icon(None, 0x7F050000)), {"foreground": 0x7F050000})
        # Any other drawable described in XML, such as a vector, is no adaptive icon.
        self.assertEqual(build_index.adaptive_layers(manifest(None)), {})


class ResourceStringTest(unittest.TestCase):
    STRINGS = ["Default", "English", "Suomi"]

    def test_value_without_qualifiers_is_preferred(self):
        table = resource_table(
            self.STRINGS,
            type_chunk(2, [None, simple_entry(0x03, 2)], language=b"fi"),
            type_chunk(2, [None, simple_entry(0x03, 0)]),
            type_chunk(2, [None, simple_entry(0x03, 1)], language=b"en"),
        )
        self.assertEqual(build_index.resource_string(table, 0x7F020001), "Default")

    def test_english_is_taken_when_there_is_no_default(self):
        table = resource_table(
            self.STRINGS,
            type_chunk(2, [simple_entry(0x03, 2)], language=b"fi"),
            type_chunk(2, [simple_entry(0x03, 1)], language=b"en"),
        )
        self.assertEqual(build_index.resource_string(table, 0x7F020000), "English")

    def test_reference_is_followed(self):
        table = resource_table(
            self.STRINGS,
            type_chunk(2, [simple_entry(0x01, 0x7F030000)]),
            type_chunk(3, [simple_entry(0x03, 1)]),
        )
        self.assertEqual(build_index.resource_string(table, 0x7F020000), "English")

    def test_reference_to_itself_ends(self):
        table = resource_table(self.STRINGS, type_chunk(2, [simple_entry(0x01, 0x7F020000)]))
        self.assertIsNone(build_index.resource_string(table, 0x7F020000))

    def test_sparse_and_short_offsets_are_read(self):
        entries = [None, None, simple_entry(0x03, 2)]
        for flags in (0x01, 0x02):
            table = resource_table(self.STRINGS, type_chunk(2, entries, flags=flags))
            self.assertEqual(build_index.resource_string(table, 0x7F020002), "Suomi")
            self.assertIsNone(build_index.resource_string(table, 0x7F020001))

    def test_compact_entry_is_read(self):
        compact = struct.pack("<HHI", 0, 0x0008 | (0x03 << 8), 1)
        table = resource_table(self.STRINGS, type_chunk(2, [compact]))
        self.assertEqual(build_index.resource_string(table, 0x7F020000), "English")

    def test_entry_count_beyond_the_chunk_is_refused(self):
        for flags in (0x00, 0x01, 0x02):
            table = bytearray(resource_table(self.STRINGS, type_chunk(2, [simple_entry(0x03, 0)], flags=flags)))
            # The count is the second field of the only type chunk's header.
            at = table.index(struct.pack("<HH", 0x0201, 36)) + 12
            struct.pack_into("<I", table, at, 1 << 30)
            with self.assertRaises(build_index.ManifestError):
                build_index.resource_string(bytes(table), 0x7F020000)

    def test_missing_resource_is_none(self):
        table = resource_table(self.STRINGS, type_chunk(2, [simple_entry(0x03, 0)]))
        self.assertIsNone(build_index.resource_string(table, 0x7F020005))
        self.assertIsNone(build_index.resource_string(table, 0x7F040000))
        self.assertIsNone(build_index.resource_string(table, 0x01020000))


def zip_file(files):
    """A zip of stored files, as bytes."""
    data = b""
    directory = b""
    for name, content in files.items():
        offset = len(data)
        common = struct.pack("<HHHHHIII", 20, 0, 0, 0, 0, 0, len(content), len(content))
        data += b"PK\x03\x04" + common + struct.pack("<HH", len(name), 0) + name + content
        directory += b"PK\x01\x02" + struct.pack("<H", 20) + common
        directory += struct.pack("<HHHHHII", len(name), 0, 0, 0, 0, 0, offset) + name
    end = b"PK\x05\x06" + struct.pack("<HHHHIIH", 0, 0, len(files), len(files), len(directory), len(data), 0)
    return data + directory + end


class ReadManifestTest(unittest.TestCase):
    TABLE = resource_table(["Example App"], type_chunk(2, [simple_entry(0x03, 0)]))

    def read(self, files, labels=None):
        apk = zip_file(files)
        self.ranges = []

        def read_range(url, start, length):
            self.ranges.append((start, length))
            return apk[start:start + length]

        with mock.patch.object(build_index, "read_range", side_effect=read_range):
            return build_index.read_manifest("https://example.invalid/a.apk", len(apk), labels)

    def test_label_is_looked_up_in_the_resource_table(self):
        files = {b"AndroidManifest.xml": manifest((0x01, 0x7F020000)), b"resources.arsc": self.TABLE}
        self.assertEqual(self.read(files), ("org.example", 3, None, "Example App"))

    def test_label_read_from_another_apk_of_the_release_is_reused(self):
        files = {b"AndroidManifest.xml": manifest((0x01, 0x7F020000)), b"resources.arsc": self.TABLE}
        self.assertEqual(self.read(files, {"org.example": "Known"})[3], "Known")
        self.assertEqual(len(self.ranges), 2)

    def test_unreadable_resource_table_costs_only_the_label(self):
        files = {b"AndroidManifest.xml": manifest((0x01, 0x7F020000)), b"resources.arsc": b"junk" * 8}
        self.assertEqual(self.read(files), ("org.example", 3, None, None))
        files.pop(b"resources.arsc")
        self.assertEqual(self.read(files), ("org.example", 3, None, None))

    def test_resource_table_placed_outside_the_archive_costs_only_the_label(self):
        files = {b"AndroidManifest.xml": manifest((0x01, 0x7F020000)), b"resources.arsc": self.TABLE}
        apk = bytearray(zip_file(files))
        # The offset of the local header is the last field before the name in the directory.
        at = apk.rindex(b"resources.arsc") - 4
        struct.pack_into("<I", apk, at, len(apk) + 1)

        def read_range(url, start, length):
            self.assertGreater(length, 0)
            self.assertLessEqual(start + length, len(apk))
            return bytes(apk[start:start + length])

        with mock.patch.object(build_index, "read_range", side_effect=read_range):
            result = build_index.read_manifest("https://example.invalid/a.apk", len(apk))
        self.assertEqual(result, ("org.example", 3, None, None))

    def test_label_is_tidied(self):
        self.assertEqual(build_index.tidy_label("  Two\n words "), "Two words")
        self.assertEqual(len(build_index.tidy_label("x" * 100)), build_index.MAX_LABEL)
        self.assertIsNone(build_index.tidy_label("  "))


PNG = b"\x89PNG\r\n\x1a\n"
WEBP = b"RIFF\x10\0\0\0WEBP"


class IconTest(unittest.TestCase):
    """The icon is read from the APK along with the manifest and kept as a file."""
    ADDRESS = "https://example.invalid/index/icons"
    # Resource 0x7F030000 is the icon, offered in three densities; 0x7F040000 a background
    # colour, 0x7F050000 a foreground image and 0x7F060000 a vector drawable.
    STRINGS = ["Example App", "res/mipmap-mdpi/ic.png", "res/mipmap-xxhdpi/ic.webp",
               "res/mipmap-xxxhdpi/ic.png", "res/mipmap-anydpi-v26/ic.xml", "res/drawable/fg.png",
               "res/drawable/vector.xml"]
    FILES = {
        b"res/mipmap-mdpi/ic.png": PNG + b"mdpi",
        b"res/mipmap-xxhdpi/ic.webp": WEBP + b"xxhdpi",
        b"res/mipmap-xxxhdpi/ic.png": PNG + b"xxxhdpi",
        b"res/drawable/fg.png": PNG + b"foreground",
        b"res/drawable/vector.xml": b"<vector/>",
    }

    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.icons = build_index.IconStore(self.directory.name, self.ADDRESS + "/")

    def read(self, table, files=None, icon=0x7F030000, apk_files=None):
        files = {b"AndroidManifest.xml": manifest((0x01, 0x7F020000), icon=icon), b"resources.arsc": table}
        files.update(self.FILES if apk_files is None else apk_files)
        apk = zip_file(files)
        self.ranges = []

        def read_range(url, start, length):
            self.ranges.append((start, length))
            return apk[start:start + length]

        with mock.patch.object(build_index, "read_range", side_effect=read_range):
            return build_index.read_manifest("https://example.invalid/a.apk", len(apk), icons=self.icons)

    def table(self, *types):
        label = type_chunk(2, [simple_entry(0x03, 0)])
        return resource_table(self.STRINGS, label, *types)

    def published(self, name):
        files = os.listdir(self.directory.name)
        self.assertEqual(len(files), 1)
        self.assertTrue(files[0].startswith(name), files[0])
        return self.ADDRESS + "/" + files[0]

    def test_densest_file_up_to_the_wanted_density_is_kept(self):
        table = self.table(
            type_chunk(3, [simple_entry(0x03, 1)], density=160),
            type_chunk(3, [simple_entry(0x03, 3)], density=640),
            type_chunk(3, [simple_entry(0x03, 2)], density=480),
        )
        self.assertEqual(self.read(table)[3], "Example App")
        address = self.published("org.example-")
        self.assertTrue(address.endswith(".webp"))
        self.assertEqual(self.icons.get("org.example"), address)
        with open(os.path.join(self.directory.name, address.rsplit("/", 1)[1]), "rb") as file:
            self.assertEqual(file.read(), WEBP + b"xxhdpi")

    def test_least_dense_file_beyond_the_wanted_density_is_the_fallback(self):
        table = self.table(
            type_chunk(3, [simple_entry(0x03, 3)], density=640),
            type_chunk(3, [simple_entry(0x03, 4)], density=0xFFFE),
        )
        self.read(table)
        self.assertTrue(self.published("org.example-").endswith(".png"))

    def test_file_name_changes_with_the_contents(self):
        table = self.table(type_chunk(3, [simple_entry(0x03, 1)], density=160))
        first = self.read(table) and self.icons.get("org.example")
        self.icons = build_index.IconStore(self.directory.name, self.ADDRESS)
        self.read(table, apk_files={**self.FILES, b"res/mipmap-mdpi/ic.png": PNG + b"other"})
        self.assertNotEqual(first, self.icons.get("org.example"))
        self.assertEqual(len(os.listdir(self.directory.name)), 2)

    def test_layers_of_an_adaptive_icon_are_kept_for_the_app_to_draw(self):
        table = self.table(
            type_chunk(3, [simple_entry(0x03, 4)], density=0xFFFE),
            type_chunk(4, [simple_entry(0x1C, 0xFF112233)]),
            type_chunk(5, [simple_entry(0x03, 5)]),
        )
        self.read(table, apk_files={
            **self.FILES, b"res/mipmap-anydpi-v26/ic.xml": adaptive_icon(0x7F040000, 0x7F050000)})
        self.assertEqual(
            self.icons.get("org.example"),
            {"background": "#ff112233", "foreground": self.published("org.example-foreground-")},
        )

    def test_layer_drawn_as_a_vector_is_kept_for_the_app_to_draw(self):
        table = self.table(
            type_chunk(3, [simple_entry(0x03, 4)], density=0xFFFE),
            type_chunk(5, [simple_entry(0x03, 6)]),
        )
        vector = vector_document(VectorTest.PATH)
        self.read(table, apk_files={
            **self.FILES, b"res/mipmap-anydpi-v26/ic.xml": adaptive_icon(None, 0x7F050000),
            b"res/drawable/vector.xml": vector})
        address = self.published("org.example-foreground-")
        self.assertTrue(address.endswith(".json"))
        self.assertEqual(self.icons.get("org.example"), {"foreground": address})
        with open(os.path.join(self.directory.name, address.rsplit("/", 1)[1]), encoding="utf-8") as file:
            self.assertEqual(json.load(file), build_index.vector_description(vector, None))

    def test_icon_that_is_a_vector_itself_is_kept_the_same_way(self):
        table = self.table(type_chunk(3, [simple_entry(0x03, 6)]))
        self.read(table, apk_files={**self.FILES, b"res/drawable/vector.xml": vector_document(VectorTest.PATH)})
        self.assertEqual(self.icons.get("org.example"), self.published("org.example-"))
        self.assertTrue(self.icons.get("org.example").endswith(".json"))

    def test_layer_that_is_neither_image_nor_vector_leaves_the_app_without_an_icon(self):
        table = self.table(
            type_chunk(3, [simple_entry(0x03, 4)], density=0xFFFE),
            type_chunk(5, [simple_entry(0x03, 6)]),
        )
        self.read(table, apk_files={
            **self.FILES, b"res/mipmap-anydpi-v26/ic.xml": adaptive_icon(None, 0x7F050000),
            b"res/drawable/vector.xml": adaptive_icon(None, None)})
        self.assertIsNone(self.icons.get("org.example"))
        self.assertIn("org.example", self.icons)
        self.assertEqual(os.listdir(self.directory.name), [])

    def test_file_that_is_not_the_image_its_name_says_is_refused(self):
        table = self.table(type_chunk(3, [simple_entry(0x03, 1)], density=160))
        self.read(table, apk_files={b"res/mipmap-mdpi/ic.png": b"<html>not found</html>"})
        self.assertIsNone(self.icons.get("org.example"))
        self.assertEqual(os.listdir(self.directory.name), [])

    def test_package_is_read_once_per_run(self):
        table = self.table(type_chunk(3, [simple_entry(0x03, 1)], density=160))
        self.read(table)
        requests = len(self.ranges)
        self.read(table)
        self.assertLess(len(self.ranges), requests)
        self.assertEqual(len(os.listdir(self.directory.name)), 1)

    def test_app_without_an_icon_or_a_resource_table_has_none(self):
        self.read(self.table(), icon=None)
        self.assertIsNone(self.icons.get("org.example"))
        self.icons = build_index.IconStore(self.directory.name, self.ADDRESS)
        files = {b"AndroidManifest.xml": manifest((0x03, 6), icon=0x7F030000)}
        apk = zip_file(files)
        with mock.patch.object(build_index, "read_range", side_effect=lambda url, start, length: apk[start:start + length]):
            build_index.read_manifest("https://example.invalid/a.apk", len(apk), icons=self.icons)
        self.assertIsNone(self.icons.get("org.example"))

    def test_package_name_that_is_no_file_name_is_refused(self):
        with self.assertRaises(build_index.ManifestError):
            self.icons.keep("../evil", "", "png", PNG)
        self.assertEqual(os.listdir(self.directory.name), [])


class PruneIconsTest(unittest.TestCase):
    def test_files_no_list_refers_to_are_removed(self):
        with tempfile.TemporaryDirectory() as directory:
            icons = os.path.join(directory, "icons")
            os.makedirs(icons)
            for name in ("kept.png", "layer.png", "gone.png"):
                with open(os.path.join(icons, name), "wb") as file:
                    file.write(PNG)
            with open(os.path.join(directory, "index.json"), "w", encoding="utf-8") as file:
                json.dump({"apps": [{"apks": [{"icon": "https://x/icons/kept.png"}], "beta": {"apks": [{"icon": None}]}}]}, file)
            with open(os.path.join(directory, "auto.json"), "w", encoding="utf-8") as file:
                json.dump({"autoApps": [{"apks": [{"icon": {"background": "#000000", "foreground": "https://x/icons/layer.png"}}]}]}, file)
            with open(os.path.join(directory, "fdroid.json"), "w", encoding="utf-8") as file:
                json.dump({"apps": [{"icon": "https://f-droid.org/repo/org.example/en-US/icon.png"}]}, file)
            self.assertEqual(build_index.prune_icons(directory), 1)
            self.assertEqual(sorted(os.listdir(icons)), ["kept.png", "layer.png"])

    def test_unreadable_list_keeps_every_file(self):
        with tempfile.TemporaryDirectory() as directory:
            icons = os.path.join(directory, "icons")
            os.makedirs(icons)
            with open(os.path.join(icons, "a.png"), "wb") as file:
                file.write(PNG)
            with open(os.path.join(directory, "index.json"), "w", encoding="utf-8") as file:
                file.write("{not json")
            self.assertEqual(build_index.prune_icons(directory), 0)
            self.assertEqual(os.listdir(icons), ["a.png"])


def asset(name, asset_id):
    return {"id": asset_id, "name": name, "browser_download_url": "https://example.invalid/" + name,
            "size": 100, "download_count": 1}


class PrereleaseTest(unittest.TestCase):
    def build(self, releases, manifests=None, previous_apks=None):
        """manifests maps an APK's file name to (package, versionCode); the default makes
        b.apk an upgrade of a.apk."""
        manifests = manifests or {"a.apk": ("org.example", 1), "b.apk": ("org.example", 2)}

        def read_manifest(url, size, labels=None, icons=None):
            package, code = manifests[url.rsplit("/", 1)[1]]
            return package, code, str(code), None

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
                                  return_value=("org.example", 1, "1", None)) as reader:
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
                mock.patch.object(build_index, "read_manifest", return_value=("org.example", 1, "1", None)):
            self.assertEqual(build_index.build_app(REPO, {}, not_before=1700000000)["tag"], "v1")


class CategoryTest(unittest.TestCase):
    def test_topic_names_the_category(self):
        self.assertEqual(build_index.guess_category(["android", "music-player"], ""), "media")

    def test_description_decides_when_the_topics_say_nothing(self):
        self.assertEqual(build_index.guess_category(["android"], "A simple VPN client."), "tools")

    def test_topic_counts_for_more_than_a_word_of_the_description(self):
        self.assertEqual(build_index.guess_category(["launcher"], "Plays music"), "personalization")

    def test_phrase_counts_for_more_than_its_words(self):
        self.assertEqual(build_index.guess_category(["music"], "A terminal emulator"), "tools")

    def test_phrase_is_matched_at_word_boundaries(self):
        self.assertIsNone(build_index.guess_category([], "A profile manager"))

    def test_only_whole_words_count(self):
        self.assertIsNone(build_index.guess_category(["android"], "Gamepad remapper"))

    def test_nothing_recognised_is_no_category(self):
        self.assertIsNone(build_index.guess_category(["android", "kotlin"], None))

    def test_category_is_added_as_a_store_topic(self):
        self.assertEqual(
            build_index.with_category(["android", "music-player"], ""),
            ["android", "music-player", "arkstore-media"])

    def test_category_the_developer_chose_is_left_alone(self):
        topics = ["arkstore-games", "music-player"]
        self.assertEqual(build_index.with_category(topics, ""), topics)

    def test_topics_stay_as_they_are_without_a_category(self):
        self.assertEqual(build_index.with_category(["android"], "An app"), ["android"])


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

    def test_examined_app_is_filed_under_a_category(self):
        candidate = dict(self.candidate(topics=("android",)), description="A podcast player")
        apps, _, _ = self.run_auto([candidate], [self.entry()])
        self.assertEqual(apps[0]["topics"], ["android", "arkstore-media"])

    def test_unchanged_app_is_filed_under_its_category_once(self):
        previous = {"other/app": dict(self.entry(), topics=["keyboard", "arkstore-personalization"])}
        known = self.examined(self.NOW - self.HOUR, listed=True)
        apps, _, _ = self.run_auto(
            [self.candidate(topics=("keyboard",))], [], previous=previous, state={"other/app": known})
        self.assertEqual(apps[0]["topics"], ["keyboard", "arkstore-personalization"])

    def test_lookups_are_capped_per_run(self):
        candidates = [self.candidate("o/app%d" % i) for i in range(build_index.AUTO_MAX_LOOKUPS + 5)]
        built = [self.entry("o/app%d" % i) for i in range(build_index.AUTO_MAX_LOOKUPS)]
        apps, state, build_app = self.run_auto(candidates, built)
        self.assertEqual(build_app.call_count, build_index.AUTO_MAX_LOOKUPS)
        self.assertEqual(len(state), build_index.AUTO_MAX_LOOKUPS)

    def test_examining_stops_when_the_requests_are_used_up(self):
        candidates = [self.candidate("o/app%d" % i) for i in range(5)]
        cost = build_index.AUTO_MAX_REQUESTS // 2

        def build_app(repo, *args, **kwargs):
            build_index.api_requests += cost
            return self.entry(repo["full_name"])

        apps, state, mocked = self.run_auto(candidates, build_app)
        self.assertEqual(mocked.call_count, 2)
        self.assertEqual(sorted(state), ["o/app0", "o/app1"])


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
