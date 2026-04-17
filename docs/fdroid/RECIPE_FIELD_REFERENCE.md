# F-Droid recipe field reference (Equalizer314-specific)

Field-by-field breakdown of what lives in `metadata/com.bearinmind.equalizer314.yml` on fdroiddata. Canonical source: the [F-Droid Build Metadata Reference](https://f-droid.org/docs/Build_Metadata_Reference/). This doc summarizes the fields we actually use and the specific values that are correct for this app.

## Current recipe

```yaml
Categories:
  - Multimedia
License: GPL-3.0-only
AuthorName: bearinmind
SourceCode: https://github.com/bearinmindcat/Equalizer314
IssueTracker: https://github.com/bearinmindcat/Equalizer314/issues

AutoName: Equalizer314

RepoType: git
Repo: https://github.com/bearinmindcat/Equalizer314.git
Binaries:
  https://github.com/bearinmindcat/Equalizer314/releases/download/Equalizer314-v%v/Equalizer314-v%v.apk

Builds:
  - versionName: 0.0.3-beta
    versionCode: 3
    commit: 43caa38b1fb6307c05a5d983a3aafb58387d77cf
    subdir: app
    gradle:
      - yes

AllowedAPKSigningKeys: 7a8368d18ad64294f9aadf4b736adcd15cb0cb88c6b9dc2e0bd5f1e461b83e52

AutoUpdateMode: Version
UpdateCheckMode: Tags
CurrentVersion: 0.0.3-beta
CurrentVersionCode: 3
```

## Field-by-field

### `Categories`

List. At least one entry from F-Droid's fixed category taxonomy. We use `Multimedia`; `Audio` is **not** a valid F-Droid category as of submission time.

### `License`

SPDX identifier. `GPL-3.0-only` matches our `LICENSE` file.

### `AuthorName`

String. Displayed on the F-Droid listing page.

### `SourceCode` / `IssueTracker`

URLs. Both point at your GitHub repo. The `IssueTracker` field is what makes the "Required" checkbox *"There is an issue tracker and contact info of the author"* on the MR template pass.

### `AutoName`

String. What F-Droid calls the app in its listing. Keeping it identical to the `applicationId`'s suffix prevents display inconsistencies.

### `RepoType` + `Repo`

`git` + the HTTPS clone URL. F-Droid clones this in its build container for every build.

### `Binaries` — the reproducibility link

URL template pointing at the signed APK you published to GitHub Releases. Uses `%v` → `versionName` and `%c` → `versionCode`.

**Formatting quirk (learned the hard way):** F-Droid's `fdroid rewritemeta` linter wraps any long line. It wants the URL on a wrapped, indented next line, AND it wants a literal trailing space after `Binaries:` before the newline:

```
Binaries: <TRAILING SPACE HERE>
  https://github.com/bearinmindcat/Equalizer314/releases/download/Equalizer314-v%v/Equalizer314-v%v.apk
```

If rewritemeta rejects the recipe with a diff showing this exact structure, you've lost the trailing space (editors strip it by default). Re-add with `sed -i 's/^Binaries:$/Binaries: /' metadata/com.bearinmind.equalizer314.yml`.

### `Builds` — the list of builds F-Droid will produce

List of dicts. Each entry describes **one** `versionCode` and how to build it.

```yaml
- versionName: 0.0.3-beta   # String — matches %v in Binaries
  versionCode: 3            # Integer — matches %c in Binaries; must match what app/build.gradle.kts has at `commit`
  commit: 43caa38b…         # Full SHA of the commit to build from. NEVER a tag name; reviewers reject tag names.
  subdir: app               # Gradle module directory (your app module lives at <repo>/app/)
  gradle:
    - yes                   # Special value: "just run ./gradlew assembleRelease"
```

For future versions, append new entries below existing ones. Don't delete old entries — they're the build history.

### `AllowedAPKSigningKeys` — the reproducibility checksum

Single string. Lowercase hex of the SHA-256 fingerprint of the APK's signing certificate. No colons, no spaces, no uppercase.

**Extraction:**

```bash
apksigner verify --print-certs Equalizer314-v0.0.3-beta.apk | grep SHA-256
# Signer #1 certificate SHA-256 digest: 7a8368d18ad64294f9aadf4b736adcd15cb0cb88c6b9dc2e0bd5f1e461b83e52
```

Since this is tied to the keystore (not the release), it stays constant across all versions you sign with the same `my-key.jks` + `key0` alias combination.

**Placement convention:** top-level, after `Builds:`. Docs allow it anywhere at top level, but every accepted recipe in fdroiddata puts it here.

### `AutoUpdateMode` + `UpdateCheckMode`

`Version` + `Tags`. Together they mean: *"F-Droid, scan our GitHub tags for new versions, and when you find one, automatically open a fdroiddata MR adding a new Builds: entry."*

### `CurrentVersion` + `CurrentVersionCode`

Redundant with the latest `Builds:` entry, but F-Droid's catalog index uses these for display purposes. Always match them to the newest build entry.

## Fields we don't use (and why)

- **`AuthorEmail`, `AuthorWebSite`** — optional. Skipped because we have no dedicated contact email and the author's profile is already on GitHub.
- **`Changelog`** — optional URL to a changelog file. Not needed because we ship per-versionCode changelog files in `fastlane/metadata/android/en-US/changelogs/`.
- **`Description`** — optional inline description. Skipped because our Fastlane `full_description.txt` is the canonical source — F-Droid prefers that if both are present.
- **`AntiFeatures`** — not needed. The app has no trackers, no ads, no non-free dependencies, no network access, no known anti-features.
- **`Donate` / `Liberapay` / `Bitcoin`** — no donation channels set up.
- **`MaintainerNotes`** — we have no notes for future maintainers yet. Will add if there's a non-obvious build quirk.

## Validation commands

Before pushing to the MR, verify locally:

```bash
# (Requires fdroidserver installed: pip install fdroidserver)
cd /path/to/fdroiddata-fresh
fdroid lint com.bearinmind.equalizer314
fdroid rewritemeta com.bearinmind.equalizer314  # must produce zero diff
```

If `fdroid rewritemeta` wants to make changes, apply them before pushing — the CI job fails identically to your local run.
