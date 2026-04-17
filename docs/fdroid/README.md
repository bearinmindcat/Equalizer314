# F-Droid submission materials for Equalizer314

This folder contains everything related to the F-Droid submission.

- **`com.bearinmind.equalizer314.yml`** — the recipe draft, always kept in sync with what's live on `fdroiddata`.
- **`SUBMISSION_JOURNAL.md`** — detailed blow-by-blow of how the initial submission went, every pipeline failure we hit, and exactly how each one was fixed. Read this first if you're coming back to this in six months.
- **`RELEASE_PROCESS.md`** — step-by-step "cut a new version" playbook. Use this for every future `0.0.N-beta` release.
- **`RECIPE_FIELD_REFERENCE.md`** — what every YAML field means, the placement convention used in accepted recipes, and the exact formatting quirks that `fdroid rewritemeta` enforces.
- **`REPRODUCIBLE_BUILDS.md`** — why `Binaries` + `AllowedAPKSigningKeys` matter, the CRLF/LF gotcha that cost us three failed pipelines, and how `.gitattributes` fixes it permanently.

## Current state

- **Active MR:** https://gitlab.com/fdroid/fdroiddata/-/merge_requests/36655
- **Tracked version:** 0.0.3-beta (`versionCode 3`)
- **Source commit pinned:** `43caa38b1fb6307c05a5d983a3aafb58387d77cf`
- **Signing cert SHA-256:** `7a8368d18ad64294f9aadf4b736adcd15cb0cb88c6b9dc2e0bd5f1e461b83e52`

## Recipe field convention

Order matters only for readability (F-Droid parses any order), but follow the accepted-recipe convention seen in apps like `androdns.android.leetdreams.ch.androdns.yml`:

1. `Categories`, `License`, `AuthorName`, `SourceCode`, `IssueTracker`
2. `AutoName`
3. `RepoType`, `Repo`, **`Binaries`** (folded onto next line — `rewritemeta` requires a trailing space after the colon and the URL on an indented line)
4. `Builds:` list
5. **`AllowedAPKSigningKeys`** (after `Builds:`, lowercase hex, no colons)
6. `AutoUpdateMode`, `UpdateCheckMode`, `CurrentVersion`, `CurrentVersionCode`
