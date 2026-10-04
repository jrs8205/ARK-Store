<p align="center">
  <img src="assets/branding/banner.png" alt="ARK-Store" width="720">
</p>

# ARK-Store

<p align="center">
  <a href="https://github.com/jrs8205/ARK-Store/releases/latest"><img src="https://img.shields.io/github/v/release/jrs8205/ARK-Store?label=stable" alt="Latest stable version"></a>
  <a href="https://github.com/jrs8205/ARK-Store/releases"><img src="https://img.shields.io/github/v/release/jrs8205/ARK-Store?include_prereleases&label=beta" alt="Latest beta version"></a>
  <a href="https://github.com/jrs8205/ARK-Store/releases"><img src="https://img.shields.io/github/downloads/jrs8205/ARK-Store/total?label=downloads" alt="Total downloads"></a>
  <a href="https://github.com/jrs8205/ARK-Store/stargazers"><img src="https://img.shields.io/github/stars/jrs8205/ARK-Store?label=stars" alt="Stars"></a>
  <a href="LICENSE"><img src="https://img.shields.io/github/license/jrs8205/ARK-Store?label=license" alt="License"></a>
</p>

ARK-Store is an Android app store for open-source apps published on GitHub. A developer
adds one topic to the repository and the app is in the store; new versions follow every
release. No account, no submission form, no review queue, no fees.

## Publish your app

1. Make the repository public.
2. Give it an open-source license that GitHub recognises, for example a `LICENSE` file from
   GitHub's license picker.
3. Create a release and attach the APK.
4. Add the topic `arkstore` to the repository (the gear next to *About*).

The app appears for every user at the next index run, usually within a few hours. To take it
out of the store, remove the topic. Private or unlicensed repositories are never listed.

### Codeberg and GitLab

The same four steps work on [Codeberg](https://codeberg.org) and [GitLab](https://gitlab.com)
with ARK-Store 1.4 and later.

- Codeberg does not report licenses, so the store reads `LICENSE` or `COPYING` in the
  repository root. The file must hold the full, unchanged text of a common license (GPL,
  AGPL, LGPL, Apache, MIT, BSD, MPL, ISC, EUPL, Unlicense, CC0), preceded only by a title and
  copyright lines. After a license that ends in "END OF TERMS AND CONDITIONS" the file may
  go on; after the others only the licenses of included parts may follow. A file that only
  names a license or changes its terms is not recognised.
- GitLab attaches release files as links. The APK link must point straight at a file kept on
  gitlab.com: a package, an upload, a repository file or a pipeline artifact. Links to other
  sites or redirected links are not offered. GitLab has no prereleases and does not count
  downloads.

### How quickly it shows up

- A new app or a new release: at the next index run. The run is scheduled hourly, but GitHub
  starts scheduled workflows when it has room, in practice every three to five hours. Users
  who do not open the store are told about updates by a background check about every four
  hours.
- Stars and download counts: with the same run.
- A repository added by hand under Sources: immediately.

The store refreshes when opened, at most once every five minutes; pulling down or the
refresh button checks right away. If an app is still missing after half a day, check that
the repository is public, has a recognised license and that its latest full release has an
`.apk` file attached.

### Adding a repository by hand

Under **Sources** in the app, anyone can type a GitHub user name or paste a link to a profile
or a repository. That account's apps, or that one app, are then listed on that device, under
the same two requirements. Useful for trying your own app before adding the topic, or for
following a developer who has not added it.

### Optional extras

- **Category**: a second topic, one of `arkstore-communication`, `arkstore-productivity`,
  `arkstore-tools`, `arkstore-personalization`, `arkstore-media`, `arkstore-games`,
  `arkstore-travel`, `arkstore-news`, `arkstore-system`, `arkstore-health`,
  `arkstore-finance` or `arkstore-education`. Without one, a few common topics such as
  `launcher`, `keyboard`, `dialer` and `weather` are recognised; the rest goes under Other.
- **Description**: the repository's description is shown on the card, the release notes in
  the details.
- **Several APKs**: keep the architecture (`arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86` or
  `universal`) in the file name and each device gets the right one.

### Good to know

- Sign every release with the same key and raise `versionCode` each time. Android refuses an
  update signed with another key.
- The newest full release is offered. A newer prerelease (same package name, higher
  `versionCode`) is offered only to users with **Show beta versions** on, marked Beta. Drafts
  are ignored.
- Forks and archived repositories are skipped.

## For users

- One list: updates, installed apps and apps to install. One tap to install or update,
  **Update all** when several updates wait. Downloads continue in the background.
- Search, categories, places (GitHub, Codeberg, GitLab, IzzyOnDroid, F-Droid) and sorting by
  name, downloads, stars or release date.
- Every app with its own icon, read from the APK or given by its catalogue.
- The details tell the latest version, when it was published, the size of the file and the
  Android version the app needs.
- Stars and download counts from GitHub.
- Refresh on open, a background check every four hours and a notification for new versions.
- A warning before installing over an app signed with a different key.
- Beta versions on request.
- More apps with switches in the settings: apps found by searching GitHub, Codeberg and
  GitLab, and the IzzyOnDroid and F-Droid catalogues. An app offered by several places is
  listed once.
- No account. The app talks to GitHub and, for files, to Codeberg, GitLab, IzzyOnDroid and
  F-Droid.
- Accessible: screen reader labels, text that scales, contrast of at least 7:1 (WCAG AAA),
  haptics that follow the system setting.
- English and Finnish.

## Automatically found apps

Besides the apps developers publish with the topic, the index searches GitHub for other
open-source Android apps. They are shown only with **Found on GitHub** on, and their card
says the developer did not publish them here. Nobody has checked these apps.

The search covers repositories with the topic `android` written in Kotlin, Java or Dart, and
repositories with the topic `android-app`. A repository qualifies when it

- is public, not a fork or archived, and has a recognised open-source license,
- has been pushed to within the last 30 days,
- has at least 20 stars, and
- has a full release, at most 180 days old, with an APK attached.

The limits are constants at the top of `tools/build_index.py`. To stay off this list, remove
the topic the search found the repository by; to be listed for everyone, add `arkstore`.

What the list does not promise:

- It is not complete. GitHub returns at most a thousand repositories per search, the most
  starred first.
- Some entries are libraries that attach a sample APK; the rules cannot tell them apart.
- It follows releases more slowly. A repository is looked at again after a push, otherwise
  after a day, and each run examines a limited number.
- Categories are guessed from the topics and the description. An app that cannot be placed
  goes under Other.

The list is `auto.json` next to the index, downloaded only while the switch is on. Beta
versions of these apps are not offered.

**Found on Codeberg** does the same for Codeberg with lower limits: topic `android` or
`android-app`, at least 5 stars, active within 30 days, a license recognised from the license
file, and a full release at most 180 days old with an APK (`codeberg.json`). Mirrors are
skipped. **Found on GitLab** does the same for gitlab.com with the same limits; few projects
there attach APKs, so the list is short (`gitlab.json`).

## Other catalogues

Two catalogues can be turned on in the settings, each with a switch of its own:

- **IzzyOnDroid** passes on the APKs developers release themselves, after scanning them.
- **F-Droid** builds apps from source and signs most of them with its own key.

The index reads each catalogue's own index, checks it against the catalogue's checksum and
writes `izzy.json` and `fdroid.json`. The APKs are downloaded from the catalogue and checked
against its SHA-256 checksum before installing. The newest version the device can run is
offered, and for an installed app the newest one signed with the same key. Their cards carry
the catalogue's name, and their details show the catalogue's warnings, such as tracking or
non-free network services. Stars and download counts are not shown for them.

### The same app from several places

A package is listed once, in this order:

1. from its developer, published with the `arkstore` topic,
2. else from the developer's releases found by searching GitHub, Codeberg or GitLab; with a
   project and its mirror, the one with the newer version,
3. else from IzzyOnDroid,
4. else from F-Droid.

An installed app stays with the place whose files are signed the way it is: an app installed
from F-Droid keeps updating from F-Droid even when its developer also releases on GitHub. The
details name the other places.

## Why only public and licensed apps

Those two requirements are the store's only gatekeeping, checked on every refresh. A public
repository means the source can be read; an open-source license means the code may be used,
studied and shared. A repository that loses its license disappears from the store.

ARK-Store does not review, build or host apps. Every app shows where it comes from and who
made it, and its source is one tap away.

## What you trust when you install

What the store checks:

- The APK of a published or found app is downloaded over HTTPS from the release on GitHub,
  Codeberg or GitLab, following that service's redirects. ARK-Store has no servers of its own
  in between.
- The APK of an IzzyOnDroid or F-Droid app is downloaded from the catalogue or its mirrors
  and checked against the catalogue's SHA-256 checksum.
- The file's package name must be the one offered.
- The file's signing keys are compared with every key the installed app has had. A file with
  none of them is refused before it reaches Android. Android makes the final decision on
  whether the keys match.
- When the index could read a file's signing key, the details say beforehand whether an
  installed app is signed the same way. An app with another key under the same package name,
  or another app altogether, is not offered an update.
- Installs go through Android's package installer, which asks before installing. On Android
  12 and later it may update an app the store itself installed without asking again, under
  Android's own conditions.

What the store does not check:

- Nobody reviews the code, builds it from source or scans it.
- On a first install there is no installed key to compare with, and the file's key is not
  compared with the one the index recorded. The guarantee is that the file is what the
  release served.
- The store cannot tell a developer's file from another one validly signed for the same
  package name. You trust the hosting service, the index that this repository's workflow
  builds (`tools/build_index.py`, published on the `index` branch) and HTTPS.
- F-Droid builds from source and IzzyOnDroid scans; apps found by searching are only known to
  be public, licensed and released, and their label says so.

Install apps from developers you trust.

## How it works

1. A workflow in this repository (`.github/workflows/index.yml`) asks GitHub for every
   repository with the `arkstore` topic. Forks, archived repositories and repositories
   without a recognised license are dropped.
2. For each repository it takes the newest full release with an `.apk` file attached.
3. The package name, the version and the lowest Android version are read from the APK's
   manifest, the name and the icon from its resources; only the zip directory, those entries
   and the icon are fetched. Icons are kept in the `icons` folder of the `index` branch,
   vector icons as their paths.
4. The result is `index.json` on the `index` branch.
5. The app downloads that file and compares it with what is installed.

The app reads one file instead of asking GitHub about every repository, so the number of
apps does not affect loading and GitHub's request limit does not come into it. The limit is
GitHub's search, which returns at most a thousand repositories for the topic.

Sources added by hand are not in that file: the app asks GitHub about them directly, within
the 60 anonymous requests an hour GitHub allows per network. The same lookup is the fallback
when the index is missing or more than a day old.

## Building

```sh
./gradlew assembleDebug
```

The debug build installs as `org.jarsi.arkstore.debug` next to a release build.

The store topic, the index locations, the account that is a source on every new installation
and the store's own repository are build config fields in `app/build.gradle.kts`
(`STORE_TOPIC`, `INDEX_URL`, `AUTO_INDEX_URL`, `FORGE_INDEX_URL`, `CODEBERG_INDEX_URL`,
`GITLAB_INDEX_URL`, `IZZY_INDEX_URL`, `FDROID_INDEX_URL`, `GITHUB_OWNER`, `STORE_REPO`). The
lists are built with `python3 tools/build_index.py --output index.json --auto-output auto.json`,
`python3 tools/build_repos.py --output-dir .` and `python3 tools/build_forge.py --output-dir .`.

Release signing is read from a `keystore.properties` file in the project root, which is never
committed:

```properties
storeFile=release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

Without that file the release build is left unsigned.

## License

ARK-Store is licensed under the [GNU General Public License v3](LICENSE).
