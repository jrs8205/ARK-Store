<p align="center">
  <img src="assets/branding/banner.png" alt="ARK-Store" width="720">
</p>

# ARK-Store

<p align="center">
  <a href="https://github.com/jrs8205/ARK-Store/releases"><img src="https://img.shields.io/github/v/release/jrs8205/ARK-Store?include_prereleases&label=version" alt="Latest version"></a>
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

That is all. The app shows up in ARK-Store for every user within about an hour, and new
versions follow by themselves whenever you publish a release. To take the app out of
the store, remove the topic.

The first two points are requirements: a repository that is private or has no recognised
license is never listed, with or without the topic.

### How quickly it shows up

- **A newly tagged app**: within about an hour. The list of published apps is rebuilt once an
  hour, and the app appears the next time a user opens the store after that.
- **A new release of a listed app**: likewise within about an hour. Users who do not open
  the store are told about the update by the background check, which runs about every four
  hours.
- **Stars and download counts**: updated with the same hourly rebuild.
- **A repository added by hand under Sources**: immediately, and its new releases show up on
  the next refresh.

The store refreshes when it is opened, at most once every five minutes; pulling down or the
refresh button checks right away. If an app is still missing after a couple of hours, check
that the repository is public, has a license GitHub recognises, and that its latest full
release has an `.apk` file attached.

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
- **No account**: the app talks only to GitHub, without signing in.
- **Accessible**: screen reader labels, text that scales, contrast that meets WCAG AA, and
  haptic feedback that follows the system setting.
- **English and Finnish.**

## Automatically found apps

Besides the apps developers publish with the topic, the index workflow searches GitHub for
other open-source Android apps. They are shown only to users who turn on **Show automatically
found apps** in the settings, and each carries a label saying that its developer did not
publish it to the store. To keep the list fresh, a repository qualifies only when it

- is public and has a recognised open-source license, like every app in the store,
- has been pushed to within the last 30 days,
- has at least 20 stars, and
- has a full release, at most 180 days old, with an APK attached.

These limits are constants at the top of `tools/build_index.py`. A developer who would
rather not be listed this way can remove the `android` topics the search looks for; one who
wants to be listed for everyone adds the `arkstore` topic.

The list is kept in a file of its own, `auto.json` next to the index, which the app downloads
only while the setting is on. Beta versions of these apps are not offered.

## Why only public and licensed apps

Those two requirements are the store's only gatekeeping, and they are checked automatically
on every refresh. A public repository means anyone can read the source the app was built
from. An open-source license means the code may actually be used, studied and shared.
Repositories that are private, or public but unlicensed, are never listed, and a repository
that loses its license disappears from the store.

ARK-Store does not review, build or host apps. It shows what developers publish on GitHub
and installs the files they attached to their releases. Every app shows its developer, and
its source is one tap away; install apps from developers you trust.

## How it works

1. Once an hour a workflow in this repository (`.github/workflows/index.yml`) asks GitHub for
   every repository carrying the `arkstore` topic. Forks, archived repositories and
   repositories without a recognised license are dropped.
2. For each remaining repository it reads the releases and takes the newest full release with
   an `.apk` file attached.
3. The package name and version are read from each APK's own manifest. Only the zip directory
   and the manifest are fetched, a few kilobytes instead of the whole file.
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
`python3 tools/build_index.py --output index.json --auto-output auto.json`.

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
