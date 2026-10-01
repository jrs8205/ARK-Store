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

ARK-Store is an Android app store for open-source apps that live on GitHub. Anyone can put
their own apps in it: add a GitHub account in the app and every public, licensed Android app
of that account is listed, ready to install and to keep up to date.

There is nothing to sign up for and nobody to ask. No developer account, no submission form,
no review queue, no fees and no metadata files to maintain. If your app has a public
repository, an open-source license and a release with an APK, it is already compatible.

## Add your apps

Your repository needs exactly two things:

1. **It is public.**
2. **It has an open-source license** that GitHub recognises, for example a `LICENSE` file
   created with GitHub's license picker.

Then publish the way you probably already do: create a GitHub release and attach the APK.

That is all. In ARK-Store, open **Sources**, type your GitHub user name (or paste a link to
your profile or to a single repository) and tap **Add**. Every repository of yours that meets
the two requirements and has a release with an APK appears in the list straight away. New
apps and new versions show up by themselves from then on.

To let other people install your apps, tell them your GitHub user name. Sources are kept on
each device, so everyone chooses whose apps they want to see.

### Optional extras

None of these are required, but they make your app look better in the store.

- **Category.** Add one topic to the repository and the app is filed under it:
  `arkstore-communication`, `arkstore-productivity`, `arkstore-tools`,
  `arkstore-personalization`, `arkstore-media`, `arkstore-games`, `arkstore-travel`,
  `arkstore-news`, `arkstore-system`, `arkstore-health`, `arkstore-finance` or
  `arkstore-education`. Without one, a few common topics such as `launcher`, `keyboard`,
  `dialer` or `weather` are recognised, and everything else goes under Other.
- **Description.** The repository's description is shown on the app's card.
- **Release notes.** The text of the release is shown in the app's details.
- **Several APKs.** If you ship one APK per CPU architecture, keep the architecture
  (`arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86` or `universal`) in the file name and each
  device gets the right one.

### Good to know

- Sign every release with the same key and raise the `versionCode` each time. Android
  refuses to update an app whose signing key has changed.
- Prereleases and drafts are ignored; the newest full release is the version offered.
- Forks and archived repositories are skipped.

## For users

- **One list for everything**: updates available, installed apps and apps you could install.
- **One tap** to install or update, and **Update all** when several updates are waiting.
- **Search** with a clear button, **categories** to narrow the list down, and **sorting** by
  name, downloads, stars or release date.
- **Numbers from GitHub**: stars and download counts for every app, the number of apps in the
  store, and how many times ARK-Store itself has been downloaded.
- **Always current**: the list is refreshed whenever the app is opened, and a background
  check twice a day notifies about new versions.
- **Safe updates**: the app warns before installing when the installed app is signed with a
  different key.
- **No account**: the app talks only to GitHub, without signing in.
- **Accessible**: screen reader labels, text that scales, contrast that meets WCAG AA, and
  haptic feedback that follows the system setting.
- **English and Finnish.**

## Why only public and licensed apps

Those two requirements are the store's only gatekeeping, and they are checked automatically
on every refresh. A public repository means anyone can read the source the app was built
from. An open-source license means the code may actually be used, studied and shared.
Repositories that are private, or public but unlicensed, are never listed, and a repository
that loses its license disappears from the store.

ARK-Store does not review, build or host apps. It shows what developers publish on GitHub
and installs the files they attached to their releases. Add sources you trust.

## How it works

1. For every source, the app asks GitHub for the account's public repositories (or for the
   single repository). Forks, archived repositories and repositories without a recognised
   license are dropped.
2. For each remaining repository it reads the releases and takes the newest full release with
   an `.apk` file attached.
3. The package name and version are read from the APK's own manifest. Only the zip directory
   and the manifest are fetched, a few kilobytes instead of the whole file.
4. The result is compared with what is installed on the device.

GitHub allows 60 anonymous requests an hour per network. The app spaces its requests to stay
below that: the repository list is fetched at most every five minutes, releases of listed
apps every 20 minutes or when the repository has been pushed to, and repositories without an
APK every six hours. If the limit is reached anyway, the app keeps showing what it has and
says so.

## Building

```sh
./gradlew assembleDebug
```

The debug build installs as `org.jarsi.arkstore.debug` next to a release build.

The source that is present on first start is the `GITHUB_OWNER` build config field in
`app/build.gradle.kts`.

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
