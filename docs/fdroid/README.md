# F-Droid submission materials

This folder contains the draft recipe and notes for submitting Equalizer314 to F-Droid.

## `com.bearinmind.equalizer314.yml`

The fdroiddata recipe. To submit:

1. Fork https://gitlab.com/fdroid/fdroiddata.
2. Clone your fork. Set `core.autocrlf false` on Windows to avoid line-ending churn.
3. Create a feature branch: `git checkout -b add-equalizer314`.
4. Copy `com.bearinmind.equalizer314.yml` to `metadata/com.bearinmind.equalizer314.yml` in the fdroiddata fork.
5. Confirm LF line endings (`file metadata/com.bearinmind.equalizer314.yml` should report ASCII, no CR/LF mix).
6. Optional: run `fdroid lint com.bearinmind.equalizer314` and `fdroid build --test com.bearinmind.equalizer314` locally if you have `fdroidserver` set up.
7. Commit + push, open an MR against `master` using the "App Inclusion" MR template.

## Fields explained

- **`commit:`** is the full SHA `dd27a9f59e600b7e881619a6d69cd34693c2021e`, which is what the `Equalizer314-v0.0.2-beta` tag points to. Reviewers reject tag names here.
- **`subdir: app`** — the gradle module lives in `app/`, not the repo root.
- **`UpdateCheckMode: Tags`** — F-Droid scans the GitHub tags for new releases.
- **`AutoUpdateMode: Version`** — when a new tag appears, F-Droid auto-generates a new `Builds:` entry.

## Next versions

When cutting `0.0.3-beta`, append another `Builds:` entry:

```yaml
Builds:
  - versionName: 0.0.2-beta
    versionCode: 2
    commit: dd27a9f59e600b7e881619a6d69cd34693c2021e
    subdir: app
    gradle:
      - yes
  - versionName: 0.0.3-beta
    versionCode: 3
    commit: <NEW_SHA>
    subdir: app
    gradle:
      - yes
```

Bump `CurrentVersion:` and `CurrentVersionCode:` at the bottom, then open a follow-up MR to fdroiddata (or let `AutoUpdateMode: Version` do it).
