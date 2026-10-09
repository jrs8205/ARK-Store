# Changelog

## 1.8.0

- Several updates at once go in order. Three files are downloaded at a time and the rest
  wait in a queue, shown as such; the store's own update is installed last, once every other
  install is done with, since installing it ends the process. The next install prompt opens
  only once the system has settled the previous install, not as soon as its prompt closes:
  on Android 11 and earlier, where every install is confirmed, a prompt opened over an
  install still running showed as a dark screen that took no touch until it was done. An
  install whose session the system dropped without a word can be tried again
- A download, waiting or running, is cancelled by tapping its ring. An unasked update's
  download stops when the Wi-Fi is left, the charger unplugged or the switch turned off,
  instead of running on regardless
- Skip this version or do not update this app, from the details of an update: the app moves
  to the installed ones with a note, is not counted or announced, and is not installed
  unasked; the next version is offered again after a skip. Both go with the export
- The screenshots and descriptions of apps published on Codeberg and GitLab, and of the
  sources added on the device, are read too; a source added on the device costs one request
  per push for them
- Everything from the 1.8.0 betas below: screenshots and longer descriptions, automatic
  updates, the GitHub token, export and import, no source to begin with, the short
  description in the device's language, the store in 17 languages, and the new releases of
  the installed apps found at once

## 1.8.0-beta.5

- A new release of an app installed on the device shows up at once again, as it did in
  1.6.0: the accounts of the installed apps are asked directly about the repositories
  pushed to since the index was built, a request per account as a rule and one more for
  each release found, while a fresh install with none of them still asks GitHub for
  nothing. The index itself is rebuilt on a
  schedule that GitHub kept loosely, hours apart at times
- The details tell when an installed app was last updated, so that an update made
  unasked can be seen to have gone through
- An install attempt is told apart from an earlier one of the same app throughout: the
  background check gives up only the attempt it began, a late result or cancellation of
  an earlier attempt leaves the current one alone, and a download that fails after its
  cancel counts as cancelled

## 1.8.0-beta.4

- The store speaks 17 languages: German, Spanish, French, Italian, Portuguese, Russian,
  Ukrainian, Polish, Dutch, Turkish, Chinese, Japanese, Korean, Arabic and Hindi join English
  and Finnish, in the app itself and in the store's own listing. The index reads the
  descriptions, short descriptions and screenshots developers publish in those languages, and
  the F-Droid and IzzyOnDroid catalogues contribute their translated summaries
- The short description is chosen in the device's language, else in English; one in another
  language no longer takes the place of the English description

## 1.8.0-beta.3

- The short description a developer writes for the store, `short_description.txt` in the
  repository's `fastlane/metadata/android/<locale>/` folder, takes the place of the
  repository's description on the card and in the details, in the device's language when
  the developer provides it. The F-Droid and IzzyOnDroid catalogues contribute their
  translated summaries the same way

## 1.8.0-beta.2

- No source to begin with: the store's own apps come from the index like everyone else's, so
  a fresh install asks GitHub for nothing and never runs into its limit of 60 requests an
  hour per network. The GitHub token is explained as what it is, something only those who
  add many sources need. An install that already has a source keeps it; remove the store's
  own account under Sources if you no longer want it read from GitHub

## 1.8.0-beta.1

- The details show the screenshots and the longer description a developer publishes in the
  repository's `fastlane/metadata/android/` folder, the one F-Droid reads; a screenshot opens
  to fill the screen. The F-Droid and IzzyOnDroid catalogues contribute their screenshots too
- Automatic updates: on Android 12 and later, a switch in the settings lets the background
  check install the updates of the apps the store itself installed without asking, only on
  Wi-Fi or only while charging if wanted. Other apps, and an update Android wants confirmed,
  still get a notification
- A GitHub token in the settings raises GitHub's limit of requests from 60 an hour for the
  whole network to 5,000. It is checked with GitHub before it is kept, goes to GitHub's API
  alone, and stays out of backups, device transfers and exports
- Export and import of the sources, the bookmarks and the settings as a file, through the
  system's file picker. Importing adds the sources and bookmarks to those on the device and
  applies the settings

## 1.7.0-beta.3

- A chosen theme takes effect at once; in 1.7.0-beta.2 it showed only after the store was
  opened again

## 1.7.0-beta.2

- A choice of themes in the settings, under Appearance: five palettes and, on Android 12
  and later, the colours of the wallpaper. A theme colours the background, the cards and
  the menus too, and every theme keeps the same contrast
- A pure black dark theme for OLED screens

## 1.7.0-beta.1

- The details tell which Android an app needs whenever it is known, also when the store
  itself needs no less, so that every app reads the same way

## 1.6.0

- The title bar, the search and the filters slide out of view as the list scrolls down and
  come back as soon as it scrolls up, and settle to one edge or the other when a gesture
  leaves them half-way; a switch in the settings, under Appearance, keeps them above the
  list. In a window too low for all of them they slide through the room there is, so that
  every row can be reached, and a control the keyboard focuses brings its row into view
- Bookmarks: an app can be bookmarked from its details, a bookmarked app carries a mark in
  the list, and a Bookmarks chip among the categories shows the bookmarked apps alone. The
  bookmarks stay on the device and follow the app's package, whatever place offers it
- Release notes are shown with their headings, bullets, fenced code, bold and code, instead
  of as the Markdown they are written in, and are read in time linear in their length
- The details tell which Android an app needs, as "Requires: Android 8.0 or later", when it
  is newer than the store itself needs, and a file the device cannot run is not offered. An
  installed app whose newest version needs a newer Android stays in the list, with a note
  and no update button; one not installed is left out. A release built for a preview of
  Android is offered to that preview only
- The index reads the lowest Android version from every APK, and releases read straight
  from GitHub have theirs read from the manifest too. The manifest readers read uses-sdk
  only as a child of the manifest element, decode only the strings they look for, and
  refuse a string pool that decodes to more than it holds
- The updates a refresh has just found are seen at once: a list at its top stays at its top
  when a section comes first, while a list being scrolled, or scrolled further down, is left
  alone
- The README explains what is and is not checked when an app is installed

## 1.6.0-beta.5

- The title bar, the search and the filters slide out of view as the list scrolls down and
  come back as soon as it scrolls up, so that the list has the screen; a switch in the
  settings, under Appearance, keeps them above the list instead
- Bookmarks: an app can be bookmarked from its details, a bookmarked app carries a mark in
  the list, and a Bookmarks chip among the categories shows the bookmarked apps alone. The
  bookmarks stay on the device and follow the app's package, whatever place offers it
- Release notes are shown with their headings, bullets, bold and code, instead of as the
  Markdown they are written in

## 1.6.0-beta.4

- An installed app whose newest version needs a newer Android than the device has stays in
  the list, with a note and no update button; one not installed is left out. A place still
  offering a version the device can run is preferred over one that does not
- The Android version is told in the details only when it is newer than the store itself
  needs; a release built for a preview of Android, named by its codename, is offered to
  that preview only
- Releases read straight from GitHub, from sources and for the store's own row, have their
  lowest Android read from the APK's manifest too
- The manifest readers read uses-sdk only as a child of the manifest element, decode only
  the strings they look for, and refuse a string pool that decodes to more than it holds
- The index keeps the codename of a preview Android an APK needs

## 1.6.0-beta.3

- The details tell which Android an app needs, as "Requires: Android 8.0 or later", once the
  index has read it from the APK, and a file the device cannot run is no longer offered
- The index reads the lowest Android version from every APK, wherever the uses-sdk element
  stands in the manifest
- The README says that a file is refused by its keys only when its keys can be read

## 1.6.0-beta.2

- A list being scrolled at the moment the updates section or an error comes first is left
  alone; a list at its top is still brought to the newcomer
- The README's section on what an install rests on now says what the store can and cannot
  tell: the service hosting the release, the index and HTTPS are trusted; the comparison of
  keys is a first sieve, with Android deciding; and an update without asking is a possibility
  on Android 12 and later under Android's conditions, not a rule

## 1.6.0-beta.1

- The updates a refresh has just found are seen at once. A list at its top stays at its top
  when a section comes first, the updates found or an error, instead of keeping its first
  item in place and leaving the new section hidden above it until the list is scrolled up.
  A list scrolled further down stays where it is
- The README explains what is and is not checked when an app is installed

## 1.5.0

The work of the 1.5.0 beta versions, now for everyone:

- Every app is shown with its own icon: the index reads it from the APK, as an image or, for
  an icon drawn as a vector, as the paths the app then draws itself, and keeps it beside the
  lists; IzzyOnDroid and F-Droid give theirs. An app whose icon cannot be read is shown by
  its initial, not by a symbol or an emoji its name happens to begin with
- Material surfaces, on by default on Android 13 and later: the screen and everything on it,
  the search field, the chips, the buttons, the badges, the cards and the sheets, are drawn
  as brushed metal lit from above. The graphics processor computes the light, the grain and
  the bevelled edge for every pixel, so the edge follows each shape. The setting under
  Appearance turns them off for flat colours or to save power. The metal is tried once off
  screen before it is drawn, and a device whose graphics driver cannot draw it keeps the flat
  colours and says so in the settings; the background is drawn once into an image, the
  shaders are shared and the lighting is computed in half precision. Measured with the
  release build on a Pixel 8a and a Galaxy Z Flip 4, the metal costs nothing measurable in
  scrolling, and the text keeps a contrast of at least 7:1 on it, light and dark
- Chips under the categories narrow the list to the apps of one place: GitHub, Codeberg,
  GitLab, IzzyOnDroid or F-Droid, with a choice of all places; the chip of a place that is
  off in the settings turns it on
- The details say where an installed app came from, Google Play, F-Droid, Aurora Store,
  ARK-Store or another app, and whether it is signed with the same key as the version offered
  here, so that ARK-Store can update it. The index names the key each file is signed with,
  read from the APK's signing block, only when every signature scheme in the block agrees,
  and names no key for a file whose key has been rotated, since such a file updates an app
  signed with the key before as well
- The original of a fork that kept its package name is listed on its own, found from the
  repository the catalogues name as its source, and a build of another project is not
  offered as the installed app's update: its key, not known yet, is compared once the file
  has been downloaded. Another app altogether installed under an app's package name, such
  as Google's own under microG Companion's, is shown as what it is, with nothing to open,
  update or remove. That is told only from a key seen to differ and a name the index has
  read from the APK itself, never from a catalogue's translated name or a conflict
  remembered from before, and an installed app counts as signed the way a file is only when
  its whole set of keys is that one key
- A key the index named with an older reader is not trusted, in the index or in the store's
  own copy, until the file has been read again
- A search puts the apps it names first, then those whose repository, developer or package
  it names, and shows the result from the top. The number of apps and the order stay above
  the list with the search and the chips, so that a card scrolling out goes under them; in a
  window too low for that they scroll with the list
- More room between the search field, the chips and the list, and the search field's
  background lines up with its outline

## 1.5.0-beta.4

- The material surfaces are on by default on Android 13 and later: the screen and everything
  on it are brushed metal lit from above. The setting under Appearance turns them off for
  flat colours or to save power. Measured with the release build on a Pixel 8a and a Galaxy
  Z Flip 4, the metal costs nothing measurable in scrolling, and the text keeps a contrast
  of at least 7:1 on it, light and dark

## 1.5.0-beta.3

- The number of apps and the order stay above the list with the search and the chips, so
  that a card scrolling out goes under them; in a window too low for that they scroll with
  the list
- A search puts the apps it names first, then those whose repository, developer or package
  it names, and shows the result from the top
- The original of a fork that kept its package name is listed on its own, found from the
  repository the catalogues name as its source, and a build of another project is not
  offered as the installed app's update: its key, not known yet, is compared once the file
  has been downloaded. Another app altogether installed under an app's package name, such
  as Google's own under microG Companion's, is shown as what it is, with nothing to open,
  update or remove
- The index names no key for a file whose key has been rotated, since such a file updates
  an app signed with the key before as well, and reads the keys of the files it knows once
  again
- Material surfaces: the metal is tried once off screen before it is drawn, and a device
  whose graphics driver cannot draw it keeps the flat colours and says so in the settings;
  the background is drawn once into an image, the shaders are shared and the lighting is
  computed in half precision, so that scrolling is smoother. Still an experiment, off by
  default

## 1.5.0-beta.2

- An experiment behind a setting: with "Material surfaces" on, the screen and everything on
  it, the search field, the chips, the buttons, the badges, the cards and the sheets, are
  drawn as brushed metal lit from above. The graphics processor computes the light, the
  grain and the bevelled edge for every pixel, so the edge follows each shape. Needs Android 13
- The place chips are always shown, in a tone of their own and with a choice of all places;
  the chip of a place that is off in the settings turns it on
- The details say where an installed app came from, Google Play, F-Droid, Aurora Store,
  ARK-Store or another app, and whether it is signed with the same key as the version offered
  here, so that ARK-Store can update it
- The index names the key each file is signed with, read from the APK's signing block, and
  only when every signature scheme in the block agrees
- The lists of IzzyOnDroid and F-Droid are built again once so that their icons appear
- More room between the search field, the chips and the list, and the search field's
  background lines up with its outline

## 1.5.0-beta.1

- Every app is shown with its own icon: the index reads it from the APK, as an image or, for
  an icon drawn as a vector, as the paths the app then draws itself, and keeps it beside the
  lists; IzzyOnDroid and F-Droid give theirs. An app whose icon cannot be read is shown by
  its initial, not by a symbol or an emoji its name happens to begin with
- Chips under the categories narrow the list to the apps of one place: GitHub, Codeberg,
  GitLab, IzzyOnDroid or F-Droid

## 1.4.0

The work of the 1.4.0 beta versions, now for everyone:

- Apps are listed under the name they give themselves instead of the name of their
  repository: the store index reads it from the APK, so that for example `bitwarden/android`
  is shown as Bitwarden. An app whose name is not known yet keeps its repository's name, and
  an app whose name differs from its repository's is shown with the repository as well
- Four more places to get apps from, each with a switch of its own in the settings: apps
  released on Codeberg and GitLab with the `arkstore` topic, apps found by searching those
  two, and the catalogues of IzzyOnDroid and F-Droid. Their apps carry the catalogue's name as
  a label, show what the catalogue warns about, and are checked against the catalogue's
  checksum before they are installed
- The settings say where apps come from: published to ARK-Store (always shown), found on
  GitHub, Codeberg, GitLab, IzzyOnDroid and F-Droid
- An app offered by several places is listed once, from its developer when possible; an
  installed app stays with the place whose files are signed the way it is, and the details
  name the other places
- From IzzyOnDroid and F-Droid the newest version the device can run is offered, also when
  the very newest needs a newer Android, and an installed app is offered the newest version
  signed with its key
- The apps of the other catalogues are in the list as soon as the store starts, read from
  the lists last downloaded, and the installed apps are read in one go, which keeps a list of
  thousands quick
- A beta version shows how often the prereleases have been downloaded and the stable version
  how often the full releases have, instead of both showing the sum
- An app whose signing key has been replaced is no longer taken for one signed otherwise,
  and a file signed with the key the app has now installs whether or not it carries the
  history of the keys before it
- One faulty entry no longer costs a whole list its apps, and a list just downloaded always
  replaces an older copy
- Two downloads running at once never write to the same file, and the download notification
  goes away when the last download is done
- The index: a list that cannot be built no longer holds up the store's own index. On
  Codeberg a license is recognised only from its full, unchanged text, whoever it names and
  whatever the copyright lines around it say. On GitLab only files kept on gitlab.com itself
  are offered, a project named by its path included, and a file replaced behind the same
  link is noticed

## 1.4.0-beta.6

- The apps of the other catalogues are in the list as soon as the store starts, read from the
  lists last downloaded, instead of only once the network has answered
- From IzzyOnDroid and F-Droid the newest version the device can run is offered, also when
  the very newest needs a newer Android, and an installed app is offered the newest version
  signed with its key
- The warnings shown for a catalogue's app are those of the version offered
- An app whose signing key has been replaced is no longer taken for one signed otherwise
- One faulty entry no longer costs a whole list its apps, and a list just downloaded always
  replaces an older copy
- Two downloads running at once never write to the same file, and the download notification
  goes away when the last download is done
- The index: a list that cannot be built no longer holds up the store's own index. On
  Codeberg a license is recognised only from its full, unchanged text. On GitLab only files
  kept on gitlab.com itself are offered, and a file replaced behind the same link is noticed

## 1.4.0-beta.5

- Apps released on GitLab: developers can publish from gitlab.com with the `arkstore` topic,
  and a new switch in the settings, Found on GitLab, lists the few apps found by searching it

## 1.4.0-beta.4

- Apps released on Codeberg: developers can publish from there with the `arkstore` topic,
  just as on GitHub, and a new switch in the settings, Found on Codeberg, lists apps found
  by searching it
- The same app on GitHub and on Codeberg is listed once, from the one with the newer version

## 1.4.0-beta.3

- Two more places to get apps from, each with a switch of its own in the settings: the
  catalogues of IzzyOnDroid and F-Droid. Their apps carry the catalogue's name as a label,
  show what the catalogue warns about, and are checked against the catalogue's checksum
  before they are installed
- The settings now say where apps come from: published to ARK-Store (always shown), found on
  GitHub, IzzyOnDroid and F-Droid
- An app offered by several places is listed once, from its developer when possible; an
  installed app stays with the place whose files are signed the way it is, and the details
  name the other places
- The installed apps are read in one go, which keeps a list of thousands quick

## 1.4.0-beta.2

- An app whose name differs from its repository's is shown with the repository as well as
  the owner, and the details of every app name the repository
- A release newer than the index takes its name only from an earlier version of the same
  package, not from another app of the same repository
- The index builder refuses a resource table whose entry counts do not fit, treats a file
  placed outside the APK as a broken file rather than a network failure, and stops examining
  repositories once a run has made 600 API requests

## 1.4.0-beta.1

- Apps are listed under the name they give themselves instead of the name of their
  repository: the store index reads it from the APK, so that for example `bitwarden/android`
  is shown as Bitwarden. The name is used in the list, the details, sorting, search and
  notifications; an app whose name is not known yet keeps its repository's name
- A beta version shows how often the prereleases have been downloaded and the stable version
  how often the full releases have, instead of both showing the sum

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
