# Cutting a new Equalizer314 release

Copy-paste-friendly playbook for every future `0.0.N-beta` release, given the state established by the initial F-Droid submission.

## Prerequisites (one-time, already done)

- [x] `.gitattributes` exists at repo root enforcing LF for text files
- [x] `app/build.gradle.kts` has `signingConfigs.release` reading `RELEASE_STORE_*` project properties
- [x] `app/build.gradle.kts` disables baseline `ArtProfile` tasks
- [x] Fastlane folder at `fastlane/metadata/android/en-US/`
- [x] fdroiddata MR `!36655` open, branch `bearincrypto1/fdroiddata:Equalizer314`
- [x] GitLab PAT available for API operations
- [x] `gh` CLI authenticated for GitHub releases

## Release variables

Fill these in before starting. Using `0.0.4-beta` as the example — bump for each new release.

```
NEW_VERSION_NAME=0.0.4-beta
NEW_VERSION_CODE=4
KEYSTORE=/c/Users/icedc/Downloads/ANDROID\ MASTER\ FOLDER/ANDROID\ STUFF\ AND\ APPS/my-key.jks
KEY_ALIAS=key0
KEY_PASSWORD=56712345
STORE_PASSWORD=56712345
```

## Steps

### 1. Bump version in `app/build.gradle.kts`

```kotlin
versionCode = 4
versionName = "0.0.4-beta"
```

### 2. Write the changelog for F-Droid

Create `fastlane/metadata/android/en-US/changelogs/4.txt`. **≤ 500 characters.** Plain text, no markdown. Describe user-visible changes. Example:

```
Simple EQ ghost outline fix + miscellaneous UI polish.

- Fixed stale DP band overlay on the main EQ graph after switching off Simple EQ.
- Undo / Redo buttons now reposition correctly around the Edit button after returning from Simple EQ.
```

### 3. Commit, tag, push

```bash
cd "C:/Users/icedc/AndroidStudioProjects/DeviceAudioEQ 03092026"
git add app/build.gradle.kts fastlane/metadata/android/en-US/changelogs/4.txt
git commit -m "release: bump to 0.0.4-beta (versionCode 4)"
git tag Equalizer314-v0.0.4-beta
git push
git push origin Equalizer314-v0.0.4-beta
```

Record the resulting commit SHA — you need it for the recipe update.

```bash
git rev-parse HEAD
```

### 4. Build signed release APK

```bash
./gradlew clean assembleRelease \
  "-PRELEASE_STORE_FILE=C:/Users/icedc/Downloads/ANDROID MASTER FOLDER/ANDROID STUFF AND APPS/my-key.jks" \
  "-PRELEASE_STORE_PASSWORD=56712345" \
  "-PRELEASE_KEY_ALIAS=key0" \
  "-PRELEASE_KEY_PASSWORD=56712345"
```

Verify the APK was signed with the known key:

```bash
"C:/Users/icedc/AppData/Local/Android/Sdk/build-tools/35.0.0/apksigner.bat" verify --print-certs \
  app/build/outputs/apk/release/app-release.apk | grep SHA-256
```

**Expected fingerprint:** `7a8368d18ad64294f9aadf4b736adcd15cb0cb88c6b9dc2e0bd5f1e461b83e52`

If that doesn't match, stop — something is wrong with the keystore and the F-Droid recipe will reject the new APK.

### 5. Rename and publish GitHub release

```bash
cp app/build/outputs/apk/release/app-release.apk \
   app/build/outputs/apk/release/Equalizer314-v0.0.4-beta.apk

gh release create "Equalizer314-v0.0.4-beta" \
  --title "Equalizer314-v0.0.4-beta" \
  --notes "<short release notes>" \
  app/build/outputs/apk/release/Equalizer314-v0.0.4-beta.apk
```

The `Binaries:` URL pattern `…/releases/download/Equalizer314-v%v/Equalizer314-v%v.apk` resolves to this artifact via `%v = 0.0.4-beta`. F-Droid will curl it during the reproducibility check.

### 6. Update the fdroiddata MR

Option A — **auto-update**. Because the recipe has `AutoUpdateMode: Version` + `UpdateCheckMode: Tags`, F-Droid's checkupdate bot will see the new tag `Equalizer314-v0.0.4-beta` and automatically open a follow-up MR to append a new `Builds:` entry. You don't need to do anything else.

Option B — **manual update** (if you don't want to wait, or you need to change anything else in the recipe at the same time):

```bash
cd "C:/Users/icedc/AndroidStudioProjects/fdroid-reference/fdroiddata-fresh"
git checkout Equalizer314
git pull origin Equalizer314  # get any remote changes

# Edit metadata/com.bearinmind.equalizer314.yml:
# 1. Add a new entry to Builds: — above or below the existing one
# 2. Bump CurrentVersion and CurrentVersionCode
# 3. Leave AllowedAPKSigningKeys alone (key unchanged)

git add metadata/com.bearinmind.equalizer314.yml
git commit -m "Equalizer314: bump to 0.0.4-beta"

GL_TOKEN='<your-gitlab-pat>'
git push "https://oauth2:${GL_TOKEN}@gitlab.com/bearincrypto1/fdroiddata.git" Equalizer314
```

### 7. Also update the in-repo recipe draft

Keep `docs/fdroid/com.bearinmind.equalizer314.yml` in sync with what's live on the fdroiddata MR. Commit the same recipe content there, commit + push to the Equalizer314 repo's `main`.

## Common gotchas

- **Forgot to build after bumping `versionCode`?** The tag will point at a commit whose `app/build.gradle.kts` still has the old versionCode. F-Droid will see a versionCode mismatch and reject the build. Always bump *then* commit *then* tag.
- **Changed the signing key?** You must update `AllowedAPKSigningKeys` in the recipe. If the SHA-256 doesn't match the APK's actual signer, F-Droid rejects the downloaded binary outright.
- **Release not yet pushed but recipe points to new version?** F-Droid `curl`s the `Binaries:` URL and 404s. Wait for `gh release create` to finish before updating the recipe.
- **CRLF sneaking back in?** If you or a tool bypasses `.gitattributes` (e.g. editing files outside git, copy-pasting from Windows notepad), the working tree may end up with CRLF again. Periodically run `git ls-files --eol | grep w/crlf` to check. If anything shows up, renormalize:

  ```bash
  git rm --cached -r . && git reset --hard HEAD
  ```
