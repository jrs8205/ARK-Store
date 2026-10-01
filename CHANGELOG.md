# Changelog

## 1.3.0

The work of the 1.3.0 beta versions, now for everyone:

- Beta versions: a switch in the settings offers a developer's prerelease when it is the same
  app with a higher version code than the full release; such apps carry a Beta badge, and an
  installed beta shows the way back to the stable version
- Settings have their own page behind a gear icon
- Optional: a switch in the settings lists open-source Android apps found by searching GitHub,
  marked as such; their list is downloaded only while the switch is on
- Downloads continue in the background, with a progress notification, and a download that
  finishes while the store is not on screen opens the install prompt from a notification
- Colours reworked so that every text keeps a contrast of at least 7:1 in both themes
- The settings show whether update notifications are allowed
- Each update is announced once, and a failed check no longer repeats a notification
- An app installed from elsewhere under another signing key is listed as installed instead of
  being offered as an update over and over
- An app from one of the user's own sources stays in the list, and a source follows its
  repository when that is renamed or moved
- The store index is downloaded only when it has changed, and is rebuilt as soon as a release
  of the store is published
- The search field gives up the keyboard when anything else is touched

## 1.3.0-beta.5

- A download that finishes while the store is not on screen tells so with a notification;
  tapping it opens the install prompt
- An app installed from elsewhere under another signing key is no longer offered as an update
  over and over; it is listed as installed, with an explanation and a way to try again
- Each update is announced once; a failed check that is retried does not bring a dismissed
  notification back, and passing GitHub errors are retried
- "A newer beta is installed" is shown only when the installed version really is a beta
- The settings switches answer at once, also while the list is being refreshed
- Automatically found apps are downloaded only while they are shown
- The download notification is updated at most twice a second, and tapping a notification no
  longer opens a second copy of the store
- An install no longer waits for a refresh that is running
- The search field gives up the keyboard when anything else is touched
- The index builder keeps automatically found apps in a file of their own, fetches the previous
  index in one piece and no longer examines prereleases it would not list

## 1.3.0-beta.4

- An app from one of the user's own sources stays in the list when the index lists it as
  automatically found, also after a refresh that could not reach the source
- A source that could not be reached no longer brings back an older release than the one
  already known
- A beta version is offered only when it has the same package name and a higher version code
  than the full release; a prerelease from an older branch or a nightly build is not
- A source follows its repository when that is renamed or moved
- The store index is rebuilt as soon as a release of the store is published
- Automatically found repositories are examined again after a while even when nothing was
  pushed, so a release whose files were attached late is found

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
