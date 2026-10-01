# Changelog

## 1.3.0-beta.3

- Colours reworked so that every text keeps a contrast of at least 7:1 (WCAG AAA) in both the
  light and the dark theme; cards have a visible edge
- The settings show whether update notifications are allowed and lead to the system settings
- The notification check also covers a switched-off channel
- Optional: a switch in the settings lists open-source Android apps found by searching GitHub,
  marked as such; only actively developed repositories with a recent release qualify
- The store index is downloaded again only when it has changed
- Downloads continue in a foreground service, with a progress notification, when the user
  leaves the app

## 1.3.0-beta.2

- Settings have their own page behind a gear icon, and the beta switch lives there
- An installed beta that is newer than the offered stable version is pointed out, with a way
  back to the stable version in the app's details

## 1.3.0-beta.1

- Beta versions: a switch in the settings offers a developer's prerelease when it
  is newer than the full release; such apps carry a Beta badge
- Update notifications still arrive when one source cannot be reached
- A failure while storing APK details no longer interrupts an install
- The index builder caps the size of a decompressed manifest, keeps an app listed through
  network failures, follows renamed release files and records prereleases

## 1.2.1

- The background update check runs every four hours instead of every twelve

## 1.2.0

- Published apps are read from a store index, one file rebuilt every hour, so the store loads
  at once and stays clear of GitHub's request limit however many apps it holds
- Direct lookups remain for sources added by hand and as a fallback

## 1.1.0

- Developers publish an app to the store by adding the topic `arkstore` to its repository;
  such apps appear for every user without anyone adding a source
- Sources remain for adding an account or a single repository by hand
- Release lookups are spaced out as the store grows, to stay within GitHub's request limit

## 1.0.0

- First release of ARK-Store, a store for open-source Android apps published on GitHub
- Lists every public repository whose latest release has an APK attached
- Add other GitHub accounts or repositories as sources
- Shows stars and download counts from GitHub
- Search, categories from repository topics, sorting and an app count
- Lists only public repositories with an open-source license
- Haptic feedback
- Shows installed, updatable and not yet installed apps
- One-tap install and update, with "Update all"
- Background update check with a notification
- Warns when the installed app is signed with a different key
- English and Finnish
