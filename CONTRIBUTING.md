# Contributing to ARK-Store

These conventions apply to all changes in this repository.

## Language rules

- **All code is written in English**: identifiers, comments, log messages, commit
  messages, documentation and repository files.
- **The app ships in exactly two languages**:
  - English is the default (`app/src/main/res/values/`, `fastlane/.../en-US/`)
  - Finnish is the second language (`app/src/main/res/values-fi/`, `fastlane/.../fi-FI/`)
- **Finnish may only appear in those translation resources.** Do not add Finnish text
  anywhere else in the repository, and do not add other app languages.

## Housekeeping

- Keep the app small: no accounts, no sign-in and no services other than GitHub.
- Avoid new dependencies when the platform or the existing ones can do the job.
- New user-facing strings must be added to the default (English) resources and translated
  in `values-fi`.
