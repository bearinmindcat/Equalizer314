# F-Droid Reproducible Builds — what worked, what didn't, and the checklist for a new app

Everything learned shipping Equalizer314 to F-Droid for the first time. Read this before submitting any Android app to fdroiddata, especially one that needs reproducible builds (`Binaries:` + `AllowedAPKSigningKeys:`).

The canonical outcome: **MR [!36655](https://gitlab.com/fdroid/fdroiddata/-/merge_requests/36655) passing all 9 CI jobs on v0.0.4-beta** after 8 pipeline runs.

## TL;DR

Reproducibility on F-Droid requires that F-Droid's Linux container build produces a byte-identical APK to the one you published yourself. Four things can make that fail, and you have to beat all four:

1. **AGP baseline profile** randomizes bytes → disable it.
2. **Line endings** (CRLF on Windows, LF on Linux) → enforce LF with `.gitattributes`.
3. **AGP `dependenciesInfo` signing block** is flagged by F-Droid's scanner → disable it.
4. **Windows `core.autocrlf=true`** can smuggle CRLF back in → override with `.gitattributes` per file pattern.

Plus miscellaneous formatting pickiness from `fdroid rewritemeta`.

---

## The final passing configuration

### `app/build.gradle.kts` — the bits that matter for F-Droid

```kotlin
android {
    // ... your normal config ...

    signingConfigs {
        create("release") {
            val storeFilePath = project.findProperty("RELEASE_STORE_FILE") as String?
            if (storeFilePath != null) {
                storeFile = file(storeFilePath)
                storePassword = project.findProperty("RELEASE_STORE_PASSWORD") as String?
                keyAlias = project.findProperty("RELEASE_KEY_ALIAS") as String?
                keyPassword = project.findProperty("RELEASE_KEY_PASSWORD") as String?
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // On AGP 8.3+, ALSO set `vcsInfo { include = false }` here.
            // On AGP 8.2.0 and earlier, this block does not exist — leaving it out is correct.
        }
    }

    // Strip the "Dependency metadata" signing block AGP 8.1+ adds by default.
    // F-Droid's `check apk` job rejects APKs containing it.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

// Disable non-deterministic baseline profile generation (breaks reproducibility).
tasks.whenTaskAdded {
    if (name.contains("ArtProfile")) {
        enabled = false
    }
}
```

### `.gitattributes` at repo root

```
# Enforce LF line endings for text files so builds are reproducible
# across Windows / Linux / macOS.
*               text=auto eol=lf
*.txt           text eol=lf
*.csv           text eol=lf
*.json          text eol=lf
*.xml           text eol=lf
*.yml           text eol=lf
*.yaml          text eol=lf
*.kt            text eol=lf
*.java          text eol=lf
*.gradle        text eol=lf
*.gradle.kts    text eol=lf
*.pro           text eol=lf
*.properties    text eol=lf
*.md            text eol=lf

# Explicit binary
*.png           binary
*.jpg           binary
*.jpeg          binary
*.webp          binary
*.apk           binary
*.jks           binary
*.keystore      binary
```

### The fdroiddata recipe (`metadata/<applicationId>.yml`)

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
  - versionName: 0.0.4-beta
    versionCode: 4
    commit: dfd1510a8f2115c156c76b2e0d7df6ba93da745e
    subdir: app
    gradle:
      - yes

AllowedAPKSigningKeys: 7a8368d18ad64294f9aadf4b736adcd15cb0cb88c6b9dc2e0bd5f1e461b83e52

AutoUpdateMode: Version
UpdateCheckMode: Tags
CurrentVersion: 0.0.4-beta
CurrentVersionCode: 4
```

Gotchas embedded in that file:
- **`Binaries:` has a literal trailing space after the colon** (before the newline). `rewritemeta` refuses to accept it without that space.
- **`Binaries:` URL is on an indented next line.** rewritemeta wraps long values automatically; not wrapping fails lint.
- **`commit:` is a full 40-char SHA**, not a tag name. Reviewers reject tag names.
- **`AllowedAPKSigningKeys` is placed after `Builds:`**, not before. Every accepted recipe in fdroiddata uses this placement; docs allow either but convention matters.
- **`AllowedAPKSigningKeys` value is lowercase hex, no colons.** The format must be `7a8368d1…` (64 chars), not `7A:83:68:D1…`.

---

## What worked

| Thing | Why it worked |
|---|---|
| **AGP 8.2.0 baseline** | Compatible with F-Droid's Linux build container. No issues. |
| **Kotlin 1.9.20** | Compatible. No issues. |
| **Signing via project properties** (`-PRELEASE_STORE_FILE=…`) | No secrets in repo. F-Droid ignores local signing and re-signs, but properties-based config doesn't break their build. |
| **Fastlane folder at `fastlane/metadata/android/en-US/`** | F-Droid auto-detected title, descriptions, changelogs, icon, 9 phone screenshots. Zero duplication in the YAML. |
| **Tagged releases with `UpdateCheckMode: Tags` + `AutoUpdateMode: Version`** | F-Droid's checkupdates bot scans tags automatically. Future versions don't require a manual MR to fdroiddata. |
| **Extracting SHA-256 from the APK via `apksigner verify --print-certs`** | Single clean command, output format already matches what F-Droid wants. |
| **`.gitattributes` with `text=auto eol=lf` plus explicit rules** | Overrode Windows `core.autocrlf=true` and made both Windows and Linux working trees contain LF. |
| **`git rm --cached -r . && git reset --hard HEAD`** | Forced Windows working tree to re-materialize from the index with `.gitattributes` applied. Without this step, the working tree keeps its old CRLF content and the next Gradle build still embeds CRLF. |
| **`dependenciesInfo { includeInApk = false; includeInBundle = false }`** | Strips the AGP-embedded signing block that F-Droid's `check apk` job rejects. |
| **Task disable for `ArtProfile`** (`tasks.whenTaskAdded { … }`) | Baseline profile generation is non-deterministic across build environments. Disabling makes the APK byte-stable. |
| **Using the official "App Inclusion" MR template verbatim** | All checkboxes present, phrasing matches other accepted MRs. Reviewers expect this form. |
| **Branch named `Equalizer314`** | CONTRIBUTING.md: *"Naming it like the app name or, much better, the app id."* |
| **GitLab API to open MR via Personal Access Token** | The `POST /projects/<fork_id>/merge_requests` endpoint with `target_project_id: <upstream_id>` opens cross-project MRs without the web UI. Don't use the upstream project ID as the URL project — create on the fork. |

---

## What didn't work

Chronological list of dead ends and why.

### 1. `vcsInfo { include = false }` inside `buildTypes.release { }`

**Failed because:** the `vcsInfo` DSL is only available in AGP 8.3+. The project is on AGP 8.2.0.

**Error:** `e: … Unresolved reference: vcsInfo`

**Fix:** delete the block. AGP 8.2.0 doesn't embed VCS info by default, so there's nothing to disable. Add the block back when/if you bump to AGP 8.3+.

### 2. Submitting without `Binaries:` and `AllowedAPKSigningKeys:`

**Failed because:** the reviewer explicitly asked for reproducible builds: *"Please add Binaries and AllowedAPKSigningKeys for reproducible build."*

**Lesson:** if your app has a public GitHub Releases page, enable reproducibility from day one. Otherwise F-Droid signs with its own key and users can't switch install sources without uninstalling first.

### 3. `Binaries:` URL on one long line

**Failed because:** `fdroid rewritemeta` auto-wraps long YAML values and fails the job if the submitted recipe doesn't match its canonical form.

**Error trace showed the diff:**
```diff
-Binaries: https://github.com/…very long URL…
+Binaries: <TRAILING_SPACE>
+  https://github.com/…very long URL…
```

**Fix:** manually wrap the URL onto an indented next line with a trailing space after the key.

### 4. `Binaries:` wrapped but without trailing space

**Failed because:** `rewritemeta` is byte-strict. It wants `Binaries: \n  URL` (with a space between `:` and `\n`), not `Binaries:\n  URL`.

**Most editors strip trailing whitespace automatically.** Had to re-add with:
```bash
sed -i 's/^Binaries:$/Binaries: /' metadata/com.bearinmind.equalizer314.yml
```
Verify with hexdump that `B i n a r i e s :  \n` is the byte sequence.

### 5. Windows `core.autocrlf=true` + no `.gitattributes`

**Failed because:** Windows working tree had CRLF in the ~9000 AutoEQ profile text files. Linux working tree had LF. Same git commit → different APK byte content. F-Droid's reproducibility comparison produced a **316,811-line diff**, all just `\r\n` vs `\n`.

**Fix:** `.gitattributes` with `eol=lf` rules. Then renormalize the working tree.

### 6. Adding `.gitattributes` but not renormalizing the working tree

**Failed because:** `.gitattributes` rules apply to new checkouts, not retroactively. Your Windows working tree still had CRLF even after committing `.gitattributes`.

**Fix:**
```bash
git rm --cached -r .           # empty the index
git reset --hard HEAD          # re-materialize from HEAD with new .gitattributes applied
```

After this, working tree on Windows had LF matching the Linux container, and v0.0.3-beta's APK finally reproduced.

### 7. AGP's "Dependency metadata" signing block

**Failed because:** AGP 8.1+ embeds library-dependency metadata as a second signing block inside every release APK. F-Droid's `check apk` job scans for this and fails the build.

**Error trace:** `CRITICAL: Found 1 problems … Found extra signing block 'Dependency metadata'`

**Fix:** add `dependenciesInfo { includeInApk = false; includeInBundle = false }` to `android { }` in `build.gradle.kts`. Requires a new signed release with the fix baked in.

This didn't show up on earlier pipelines because `check apk` **depends on `fdroid build` succeeding** — for pipelines #3–#6 (which failed on reproducibility), `check apk` was skipped. It only ran once v0.0.3-beta passed reproducibility. Classic "more errors hidden behind the first error" situation.

### 8. Trying to auto-apply the `~"New App"` label via API

**Failed because:** setting labels on the upstream `fdroid/fdroiddata` project requires Reporter-level access. Contributors don't have that. API returned success with `labels: []`.

**Not a blocker** — maintainers apply the label during triage. The `/label ~"New App"` quick-action line in the MR description is harmless.

### 9. Creating a second MR from the same source branch

**Failed because:** GitLab refuses: *"Another open merge request already exists for this source branch: !36655"*.

**Lesson:** push updates to the existing branch to update the existing MR. Don't open a new MR.

### 10. `git add --renormalize .` without committing or re-checking out

**Failed because:** `--renormalize` updates the **index** only, not the working tree. If the index already stored LF (which it did in our case), `--renormalize` was a no-op and the Windows working tree kept its CRLF content.

**Not harmful, just unhelpful.** The real fix is step 6.

---

## Checklist for a new Android app submission

Do these in order. Each step is cheap, and skipping any of them means you'll hit the corresponding failure described above.

### Before writing any code

- [ ] Pick an SPDX license (e.g. `GPL-3.0-only`). Commit a `LICENSE` file at repo root.
- [ ] Register on GitLab and set up a Personal Access Token with `api`, `read_repository`, `write_repository` scopes.
- [ ] Decide on tag format (`v1.0.0`, `AppName-v1.0.0`, etc.). Be consistent.

### First commit / initial setup

- [ ] Create `.gitattributes` at repo root with LF rules (see above). Commit before anything else.
- [ ] On Windows: run `git config --global core.autocrlf false` for this machine, to avoid accidental reintroduction of CRLF outside `.gitattributes` coverage.
- [ ] Create `app/build.gradle.kts` with `signingConfigs.release` reading project properties, `dependenciesInfo { includeInApk = false; includeInBundle = false }`, and the `tasks.whenTaskAdded` block disabling ArtProfile tasks.
- [ ] On AGP 8.3+, also add `vcsInfo { include = false }` inside `buildTypes.release`.

### Before cutting a release

- [ ] Bump `versionCode` and `versionName`. Commit.
- [ ] Create `fastlane/metadata/android/en-US/` with:
  - [ ] `title.txt` — ≤50 chars
  - [ ] `short_description.txt` — ≤80 chars (enforced, not soft limit)
  - [ ] `full_description.txt` — ≤4000 chars, plain text (no markdown, no embedded URLs)
  - [ ] `changelogs/<versionCode>.txt` — ≤500 chars per file
  - [ ] `images/icon.png` — ideally 512×512
  - [ ] `images/phoneScreenshots/{1,2,…}.{png,jpg}` — up to 8
- [ ] Tag the commit: `git tag <AppName>-v<version>`.
- [ ] Build and sign: `./gradlew assembleRelease -PRELEASE_STORE_FILE=… -PRELEASE_STORE_PASSWORD=… -PRELEASE_KEY_ALIAS=… -PRELEASE_KEY_PASSWORD=…`.
- [ ] Verify signing key didn't change: `apksigner verify --print-certs <apk> | grep SHA-256`.
- [ ] Rename APK to `<AppName>-v<version>.apk` and publish to GitHub Releases with the exact filename pattern `<AppName>-v<version>.apk`.

### Before opening the MR

- [ ] Fork `https://gitlab.com/fdroid/fdroiddata`.
- [ ] Clone your fork. **Run `git config --global core.autocrlf false`** first (or `core.autocrlf input` on Windows) so YAML files stay LF.
- [ ] Checkout `master`, pull latest, create branch `<AppName>` or `add-<appname>`.
- [ ] Write `metadata/<applicationId>.yml` following the structure above.
  - [ ] Categories from F-Droid's fixed list — verify against `templates/general.yml`.
  - [ ] License as SPDX identifier.
  - [ ] `commit:` is full 40-char SHA.
  - [ ] `Binaries:` URL on wrapped, indented next line; trailing space after key.
  - [ ] `AllowedAPKSigningKeys:` after `Builds:`, lowercase hex, no colons.
  - [ ] `AutoUpdateMode: Version`, `UpdateCheckMode: Tags`.
- [ ] Verify: `grep -c $'\r' metadata/<applicationId>.yml` should return `0`.
- [ ] If you have `fdroidserver` installed locally: `fdroid lint <applicationId>` and `fdroid rewritemeta <applicationId>` (both must produce zero diff).
- [ ] Commit with a message like `New App: <AppName> <version>`.
- [ ] Push to your fork.

### Opening the MR

- [ ] Open MR via GitLab web UI or via API (POST to your fork's project, with `target_project_id: 36528`).
- [ ] Use the official "App Inclusion" template — don't abbreviate.
- [ ] Tick all 5 Required checkboxes.
- [ ] Tick the 2 Strongly Recommended ones (Fastlane folder, tagged releases + autoupdate).
- [ ] Leave the 3 Suggested unchecked unless you've actually done them (external repo submodules, reproducible builds already set up, multiple APKs for native code).
- [ ] Include `/label ~"New App"` at the bottom of the description body.

### After opening the MR

- [ ] Watch the pipeline. First run takes 10–30 min.
- [ ] If `fdroid rewritemeta` fails: read the diff in the trace, apply the exact whitespace/wrapping it shows as `+`, push.
- [ ] If `fdroid build` fails with a diff: that's reproducibility. Check line endings first (`git ls-files --eol | grep w/crlf`), then dependency blocks, then baseline profile.
- [ ] If `check apk` fails with "Dependency metadata": add `dependenciesInfo { includeInApk = false }` to `build.gradle.kts`, bump versionCode, cut new release, update recipe.
- [ ] When all 9 jobs pass, wait for a maintainer to review. They'll apply the `~"New App"` label and do a code review.

---

## Reference commands

### Extract signing cert SHA-256 from an APK

```bash
apksigner verify --print-certs Equalizer314-v0.0.4-beta.apk | grep SHA-256
# Signer #1 certificate SHA-256 digest: 7a8368d1…
```

Or via keytool:
```bash
keytool -printcert -jarfile Equalizer314-v0.0.4-beta.apk \
  | sed -n 's/[[:space:]]*SHA256: //p' | tr -d ':' | tr '[:upper:]' '[:lower:]'
```

### Build signed release (Equalizer314 example)

```bash
./gradlew clean assembleRelease \
  "-PRELEASE_STORE_FILE=C:/path/to/my-key.jks" \
  "-PRELEASE_STORE_PASSWORD=…" \
  "-PRELEASE_KEY_ALIAS=key0" \
  "-PRELEASE_KEY_PASSWORD=…"
```

### Renormalize working tree after adding `.gitattributes`

```bash
git add .gitattributes
git commit -m "Add .gitattributes enforcing LF"
git rm --cached -r .
git reset --hard HEAD
# Verify:
git ls-files --eol | grep w/crlf   # should return no matches
```

### Check for any remaining CRLF offenders in a source tree

```bash
git ls-files --eol | grep w/crlf
```

### Push an fdroiddata branch to your fork via HTTPS + PAT

```bash
GL_TOKEN='glpat-…'
git push "https://oauth2:${GL_TOKEN}@gitlab.com/<username>/fdroiddata.git" <branch>
```

### Open a cross-project MR via API

```bash
GL_TOKEN='glpat-…'
curl -sS -X POST "https://gitlab.com/api/v4/projects/<FORK_ID>/merge_requests" \
  -H "PRIVATE-TOKEN: ${GL_TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{
    "source_branch": "<branch>",
    "target_branch": "master",
    "title": "New App: <applicationId>",
    "description": "...",
    "target_project_id": 36528,
    "remove_source_branch": false
  }'
```

### Update an existing MR description via API

```bash
GL_TOKEN='glpat-…'
curl -sS -X PUT "https://gitlab.com/api/v4/projects/36528/merge_requests/<MR_IID>" \
  -H "PRIVATE-TOKEN: ${GL_TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{"description": "..."}'
```

### Check pipeline status

```bash
GL_TOKEN='glpat-…'
curl -sS "https://gitlab.com/api/v4/projects/36528/merge_requests/<MR_IID>/pipelines" \
  -H "PRIVATE-TOKEN: ${GL_TOKEN}"
```

---

## Final state (at time of writing)

- MR **!36655** — all 9 CI jobs passing on v0.0.4-beta (pipeline 2461427050).
- Source: commit `dfd1510a8f2115c156c76b2e0d7df6ba93da745e`, tag `Equalizer314-v0.0.4-beta`.
- Pending: maintainer review, label application, merge.

The detailed chronological log is at `docs/fdroid/SUBMISSION_JOURNAL.md`. The per-version release playbook is at `docs/fdroid/RELEASE_PROCESS.md`. The per-field recipe reference is at `docs/fdroid/RECIPE_FIELD_REFERENCE.md`.
