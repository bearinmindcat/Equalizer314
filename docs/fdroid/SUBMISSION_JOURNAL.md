# F-Droid submission journal — Equalizer314

Chronological record of the initial submission process. Kept so that future-you (and anyone else who picks this up) doesn't have to rediscover why each change was made.

## The MR

**Merge Request:** https://gitlab.com/fdroid/fdroiddata/-/merge_requests/36655
**Target project:** `fdroid/fdroiddata` (ID 36528)
**Source branch:** `bearincrypto1/fdroiddata:Equalizer314` (fork ID 77887510)
**Title:** `New App: com.bearinmind.equalizer314`
**Opened:** 2026-04-17

## Initial state

The submission was built on top of these already-present artifacts in the Equalizer314 repo:

- **Fastlane metadata** at `fastlane/metadata/android/en-US/` (title, short/full description, changelogs, icon, 9 phone screenshots) — satisfies the "Strongly Recommended" checkbox about upstream metadata.
- **Git tag `Equalizer314-v0.0.1-beta`** at commit `056bebb…` (the first signed release) — satisfies the "tagged releases" recommendation.
- **ArtProfile task disabled** in `app/build.gradle.kts` — a prerequisite for reproducibility since baseline profile generation is non-deterministic.

At MR-open time, the recipe targeted `0.0.2-beta` (commit `dd27a9f…`) — a later release that had the signing config plumbed via `-PRELEASE_STORE_FILE` project properties and the fastlane folder populated.

## Pipeline history

The fdroiddata CI runs a fixed set of jobs on every pushed commit. Their outcomes, in order:

| # | Commit | Status | What failed and why |
|---|---|---|---|
| 1 | `10504ce8` | ✅ all green | Initial YAML without `Binaries` or `AllowedAPKSigningKeys`. Everything passed because the reproducibility comparison only runs when those fields are set. |
| 2 | `10504ce8` | ✅ all green | Second run, triggered by the MR description edit to the official template. Still no reproducibility fields. |
| 3 | `871c94f0` | ❌ `fdroid rewritemeta` + `fdroid build` | Added `Binaries` + `AllowedAPKSigningKeys` for the first time. **(a)** rewritemeta wanted the long `Binaries:` URL wrapped onto an indented next line. **(b)** The reproducibility check compared F-Droid's Linux-built APK against our published v0.0.2-beta APK — and found 316k+ lines of differences. Root cause: Windows `core.autocrlf=true` converted the thousands of AutoEQ profile text files in `app/src/main/assets/autoeq/profiles/*.txt` from LF (what git stores) to CRLF (what the Windows working tree held, and what our APK embedded). F-Droid's Linux container produced LF. |
| 4 | `ba713e8c` | ❌ same two | Moved `AllowedAPKSigningKeys` to its conventional location (after `Builds:`, matching the placement seen in every accepted GitHub-releases recipe). Didn't fix either underlying failure. |
| 5 | `7e234447` | ❌ rewritemeta (build still running) | Wrapped `Binaries:` URL onto the next indented line. rewritemeta **still** failed because it also wanted a **trailing space** after `Binaries:` (YAML folded-scalar syntax quirk). |
| 6 | `e565c67a` | ❌ reproducibility only | Added the trailing space. `fdroid rewritemeta` finally passed. `fdroid build` still failed because the CRLF/LF mismatch is a real source-level issue that no YAML tweak can patch around. |
| 7 | `0ee151a0` | ❌ `check apk` only (build + reproducibility ✅) | Switched recipe to point at **v0.0.3-beta** (commit `43caa38b…`) — `.gitattributes` fix worked, `fdroid build` reproducibility passed for the first time. But `check apk` flagged the APK contains an extra "Dependency metadata" signing block that AGP 8.1+ embeds by default. |
| 8 | `d5acf01e` | pending | Switched recipe to point at **v0.0.4-beta** (commit `dfd1510a…`). Added `dependenciesInfo { includeInApk = false; includeInBundle = false }` to `app/build.gradle.kts` to strip the offending signing block. |

## Why reproducibility is byte-level picky

`fdroid build` produces an unsigned APK from the source commit, downloads your `Binaries:` URL, strips the signature block off both APKs, then does a **byte-for-byte** comparison of the remaining contents. A single differing byte across any embedded file fails the comparison and rejects the build for reproducibility purposes.

The CRLF vs LF issue looks harmless — it's semantically identical text — but the APK is a ZIP archive and the byte content of each entry is compared literally. A `.txt` file with CRLF vs LF has different bytes, different size, different CRC32, and fails.

## The fix that stuck

1. **`.gitattributes` in repo root** — declares `*.txt text eol=lf` (plus other text file types and explicit binary rules). This tells git to keep LF in the working tree on *all* platforms, regardless of `core.autocrlf`.
2. **`git rm --cached -r . && git reset --hard HEAD`** — forced the existing Windows working tree to re-materialize from the index with the new `.gitattributes` rules applied. All 9092 tracked files re-written to the working tree with LF.
3. **Rebuild** — the v0.0.3-beta APK now embeds LF content in all assets. F-Droid's Linux build produces identical bytes.
4. **Recipe bump** — the MR's recipe now points at commit `43caa38b…` (v0.0.3-beta) instead of `dd27a9f…` (v0.0.2-beta). The signing key is unchanged, so `AllowedAPKSigningKeys` stays `7a8368d1…`.

## Lessons learned for next time

- **`rewritemeta` is byte-strict.** It will reject recipes that are semantically identical but differ in insignificant whitespace (trailing spaces after `Binaries:`, indentation of wrapped values). Don't argue — just match its output.
- **Set `core.autocrlf=false` globally** on your Windows dev environment for any cross-platform project. `.gitattributes` is the authoritative fix, but `autocrlf=true` is a footgun for assets.
- **Reproducibility requires the published APK to exist before the recipe is submitted.** If you push reproducibility fields in the recipe but the GitHub release doesn't have the exact `Equalizer314-v%v.apk` artifact at the `Binaries:` URL, F-Droid's `curl` fetch fails and the build is rejected.
- **The signing cert SHA-256 is tied to the *key*, not the release.** It stays constant across versions as long as you use the same keystore + alias. Only commit hash and version numbers change between releases.
- **AGP 8.1+ embeds a "Dependency metadata" signing block** by default. F-Droid's `check apk` job rejects it. Always set `android.dependenciesInfo { includeInApk = false; includeInBundle = false }` for F-Droid builds.
