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

ARK-Store is an Android app store for open-source apps that live on GitHub. Developers
publish their apps to it themselves: add one topic to the repository on GitHub and the app
appears in the store for everyone, ready to install and to keep up to date.

There is nothing to sign up for and nobody to ask. No developer account, no submission form,
no review queue, no fees and no metadata files to maintain.

## Publish your app

1. **Make the repository public.**
2. **Give it an open-source license** that GitHub recognises, for example a `LICENSE` file
   created with GitHub's license picker.
3. **Create a release and attach the APK**, the way you probably already do.
4. **Add the topic `arkstore`** to the repository (the gear next to *About* on the
   repository's front page).

That is all. The app shows up in ARK-Store for every user the next time the list of
published apps is rebuilt, usually within a few hours, and new versions follow by themselves
whenever you publish a release. To take the app out of the store, remove the topic.

The first two points are requirements: a repository that is private or has no recognised
license is never listed, with or without the topic.

### Publishing from Codeberg or GitLab

The same four steps work for a repository on [Codeberg](https://codeberg.org) or a project
on [GitLab](https://gitlab.com): make it public, give it a license, attach the APK to a
release and add the topic `arkstore`. Apps published this way are shown by ARK-Store 1.4 and
later, not by older versions. A few things differ:

- **Codeberg** does not say which license a repository has, so the store recognises it from
  the `LICENSE` or `COPYING` file in the repository's root. The file has to hold the full,
  unchanged text of one of the common licenses (GPL, AGPL, LGPL, Apache, MIT, BSD, MPL, ISC,
  EUPL, Unlicense, CC0), with nothing before it but a title and copyright lines. After a
  license that ends with "END OF TERMS AND CONDITIONS" the file may go on, for instance with
  a notice of its own; after the others only the licenses of parts the app includes may
  follow. A file that only names a license, changes its terms or adds conditions of its own
  is not recognised, and the repository is left out.
- **GitLab** attaches files to a release as links. The APK must be a link that points
  straight at a file kept on gitlab.com itself: a package or an upload of a project, a file
  of a repository or a pipeline's artifact. A link to another site, or one that is passed
  on to another address, is not offered. GitLab has no prereleases, so beta versions cannot
  be published from there, and it does not count downloads.

### How quickly it shows up

- **A newly tagged app**: usually within a few hours. The list of published apps is rebuilt
  by a workflow that is scheduled to run once an hour, but GitHub starts scheduled workflows
  when it has room for them, and in practice the runs have been three to five hours apart.
  The app appears the next time a user opens the store after a run.
- **A new release of a listed app**: likewise at the next run. Users who do not open the
  store are told about the update by the background check, which runs about every four
  hours.
- **Stars and download counts**: updated with the same rebuild.
- **A repository added by hand under Sources**: immediately, and its new releases show up on
  the next refresh.

The store refreshes when it is opened, at most once every five minutes; pulling down or the
refresh button checks right away. If an app is still missing after half a day, check that
the repository is public, has a license GitHub recognises, and that its latest full release
has an `.apk` file attached.

### Adding a repository by hand

The topic is the way to reach everyone, but it is not the only way in. In the app, under
**Sources**, anyone can type a GitHub user name or paste a link to a profile or to a single
repository. All apps of that account, or that one app, are then listed on that device, with
the same two requirements. This is handy for trying your own app in the store before you add
the topic, or for following a developer who has not added it.

### Optional extras

None of these are required, but they make your app look better in the store.

- **Category.** Add a second topic and the app is filed under it: `arkstore-communication`,
  `arkstore-productivity`, `arkstore-tools`, `arkstore-personalization`, `arkstore-media`,
  `arkstore-games`, `arkstore-travel`, `arkstore-news`, `arkstore-system`, `arkstore-health`,
  `arkstore-finance` or `arkstore-education`. Without one, a few common topics such as
  `launcher`, `keyboard`, `dialer` or `weather` are recognised, and everything else goes
  under Other.
- **Description.** The repository's description is shown on the app's card.
- **Release notes.** The text of the release is shown in the app's details.
- **Several APKs.** If you ship one APK per CPU architecture, keep the architecture
  (`arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86` or `universal`) in the file name and each
  device gets the right one.

### Good to know

- Sign every release with the same key and raise the `versionCode` each time. Android
  refuses to update an app whose signing key has changed.
- The newest full release is the version offered. A prerelease that is newer than it is
  offered only to users who have turned on **Show beta versions** in the settings, and is
  marked Beta. Newer means the same package name with a higher `versionCode`: a prerelease
  built from an older branch, or one with a package name of its own, is not offered.
  Drafts are ignored.
- Forks and archived repositories are skipped.

## For users

- **One list for everything**: updates available, installed apps and apps you could install.
- **One tap** to install or update, and **Update all** when several updates are waiting.
  Downloads carry on in the background if you leave the app.
- **Search** with a clear button, **categories** to narrow the list down, and **sorting** by
  name, downloads, stars or release date.
- **Numbers from GitHub**: stars and download counts for every app, the number of apps in the
  store, and how many times ARK-Store itself has been downloaded.
- **Always current**: the list is refreshed whenever the app is opened, and a background
  check every four hours notifies about new versions.
- **Safe updates**: the app warns before installing when the installed app is signed with a
  different key.
- **Beta versions**: optionally get a developer's prereleases as updates.
- **More apps when you want them**: switches in the settings add apps found by searching
  GitHub, Codeberg and GitLab, and the catalogues of IzzyOnDroid and F-Droid. An app offered
  by several of them is listed once.
- **No account**: nothing to sign in to. The app talks to GitHub and, for the files of
  their apps, to Codeberg, GitLab, IzzyOnDroid and F-Droid.
- **Accessible**: screen reader labels, text that scales, text contrast of at least 7:1
  (WCAG AAA), and haptic feedback that follows the system setting.
- **English and Finnish.**

## Automatically found apps

Besides the apps developers publish with the topic, the index workflow searches GitHub for
other open-source Android apps. They are shown only to users who turn on **Found on GitHub**
in the settings, and each carries a label saying so: its developer did not publish it to
the store. Nobody has checked these apps.

The search looks for repositories that carry the topic `android` and are written in Kotlin,
Java or Dart, and for repositories that carry the topic `android-app`. To keep the list
fresh, a repository qualifies only when it

- is public, is not a fork or archived, and has a recognised open-source license, like
  every app in the store,
- has been pushed to within the last 30 days,
- has at least 20 stars, and
- has a full release, at most 180 days old, with an APK attached.

These limits are constants at the top of `tools/build_index.py`. A developer who would
rather not be listed this way can remove the topic the search found the repository by; one
who wants to be listed for everyone adds the `arkstore` topic.

What the list does not promise:

- **It is not complete.** GitHub's search returns at most a thousand repositories for each
  of the four searches, the most starred first, so a qualifying app with fewer stars can be
  left out. An Android app without those topics, or written in another language without the
  `android-app` topic, is not found at all.
- **Not everything on it is an app in its own right.** The rules cannot tell an app from a
  library that attaches a sample or demo APK to its releases, so some of those are listed
  too.
- **It follows releases more slowly.** A repository is looked at again when it has been
  pushed to, and otherwise becomes due for another look after a day. Each run examines a
  limited number of repositories, those that have changed first, so a new version whose
  APK was attached without a push shows up a day later at the earliest, and later still
  while many repositories are changing.

Nobody has chosen a category for these apps, so the index guesses one from the words of the
repository's topics and description. The guess can be wrong, and an app it cannot place goes
under Other.

The list is kept in a file of its own, `auto.json` next to the index, which the app downloads
only while the setting is on. Beta versions of these apps are not offered.

### Found on Codeberg

A second switch, **Found on Codeberg**, does the same for Codeberg, a code hosting service
like GitHub. Codeberg is far smaller, so less is asked: the repository carries the topic
`android` or `android-app`, has at least 5 stars, has been active within the last 30 days,
has a license the store recognises from its license file, and has a full release, at most
180 days old, with an APK attached. These apps are listed in `codeberg.json` next to the
index. A repository there that only mirrors another one is skipped.

A third switch, **Found on GitLab**, does the same for gitlab.com with the same limits. Few
projects there attach an APK to their releases, so that list is short: a handful of apps.
They are listed in `gitlab.json`.

## Other catalogues

Two established catalogues of open-source Android apps can be turned on in the settings, each
with a switch of its own:

- **IzzyOnDroid** passes on the APKs developers release themselves, after scanning them.
- **F-Droid** builds apps from their source and signs most of them with its own key.

The index workflow reads each catalogue's own index, checks it against the checksum the
catalogue publishes, and boils it down to one file per catalogue (`izzy.json`, `fdroid.json`
next to the index), which the app downloads only while that switch is on. The APKs are
downloaded from the catalogue itself, and the app checks every file against the checksum the
catalogue gives for it before installing. Of an app's versions the newest one the device
can run is offered, and for an app that is already installed the newest one signed with the
same key. Apps of a catalogue carry its name as a label, and their details show what the
catalogue warns about in that version, such as tracking or non-free network services. Stars
and download counts are not shown for them, since the catalogues do not tell.

### The same app from several places

Many apps are in more than one of these places. The store lists a package once:

1. from its developer, when they have published it with the `arkstore` topic,
2. else from the developer's releases as found by searching GitHub, Codeberg or GitLab; when
   more than one has it, as with a project and its mirror, the one with the newer version,
3. else from IzzyOnDroid,
4. else from F-Droid.

That is also the order in which new versions tend to arrive. An installed app is the
exception: Android updates an app only with a file signed with the same key, so an installed
app stays with the place whose files are signed the way it is. An app installed from F-Droid
keeps being updated from F-Droid, even when its developer also releases it on GitHub. The
details of an app name the other places that offer it.

## Why only public and licensed apps

Those two requirements are the store's only gatekeeping, and they are checked automatically
on every refresh. A public repository means anyone can read the source the app was built
from. An open-source license means the code may actually be used, studied and shared.
Repositories that are private, or public but unlicensed, are never listed, and a repository
that loses its license disappears from the store.

ARK-Store does not review, build or host apps. It shows what developers publish on GitHub,
and what the catalogues that are turned on offer, and installs those files. The catalogues
list only open-source apps as well. Every app shows where it comes from and who made it, and
its source is one tap away; install apps from developers you trust.

## How it works

1. A workflow in this repository (`.github/workflows/index.yml`) asks GitHub for every
   repository carrying the `arkstore` topic. It is scheduled once an hour; GitHub decides
   when a scheduled run actually starts, which has been every three to five hours. Forks,
   archived repositories and repositories without a recognised license are dropped.
2. For each remaining repository it reads the releases and takes the newest full release with
   an `.apk` file attached.
3. The package name and version are read from each APK's own manifest, and the app's name
   from its resource table. Only the zip directory and those two files are fetched, not the
   whole APK. An app is listed under that name; if it cannot be read, the repository's name
   is shown instead.
4. The result is written to one file, `index.json` on the `index` branch.
5. The app downloads that file and compares it with what is installed on the device.

Because the app reads a single file instead of asking GitHub about every repository, the
number of apps in the store does not affect how fast it loads, and GitHub's request limit
does not come into it. The limit of this design is GitHub's search, which returns at most a
thousand repositories for the topic.

Accounts and repositories added by hand under Sources are not in that file. The app asks
GitHub about them directly, within the 60 anonymous requests an hour that GitHub allows per
network. The same direct lookup is the fallback for published apps if the index is missing
or more than a day old.

## Building

```sh
./gradlew assembleDebug
```

The debug build installs as `org.jarsi.arkstore.debug` next to a release build.

The store topic, the index location, the account that is a source on every new installation
and the store's own repository are the `STORE_TOPIC`, `INDEX_URL`, `GITHUB_OWNER` and
`STORE_REPO` build config fields in `app/build.gradle.kts`; `AUTO_INDEX_URL` is where the
automatically found apps are listed. The index is built with
`python3 tools/build_index.py --output index.json --auto-output auto.json`, and the lists of
the other catalogues, whose addresses are `IZZY_INDEX_URL` and `FDROID_INDEX_URL`, with
`python3 tools/build_repos.py --output-dir .`. The lists of apps on Codeberg and
GitLab (`FORGE_INDEX_URL` for those published with the topic, `CODEBERG_INDEX_URL` and
`GITLAB_INDEX_URL` for those found by searching) are built with `python3 tools/build_forge.py --output-dir .`.

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
