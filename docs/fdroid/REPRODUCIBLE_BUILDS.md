# Reproducible builds — the CRLF/LF story

Standalone doc on why reproducibility matters for F-Droid, how it works, and the specific cross-platform pitfall that cost us multiple failed pipeline runs before we figured it out.

## Why F-Droid cares

By default, when F-Droid ships an app it builds the source in its own container and **signs with F-Droid's key** before shipping to users. That has two annoying effects for the developer:

1. **Users can't switch install sources.** An app signed by F-Droid has a different signature from the same app signed by you (GitHub Releases, Obtainium, direct APK sideload). Android refuses to install any of those over the other — the user has to *uninstall first*, losing their app data.
2. **Users can't verify it's really your build.** They're trusting F-Droid's bot to build honestly. Most of the time that's fine, but for security-sensitive apps it matters.

The fix is **reproducible builds**. If F-Droid's own container build produces byte-identical output to the APK you published and signed yourself, F-Droid will just use your APK directly instead of theirs. Same signature. Users can switch freely. Everyone's happy.

## How the comparison works

When the recipe has `Binaries:` + `AllowedAPKSigningKeys:`, F-Droid's `fdroid build` job does:

1. Clone the repo at the pinned `commit:`.
2. Run `./gradlew assembleRelease` in the container to produce `app-release-unsigned.apk`.
3. `curl` the URL expansion of `Binaries:` to download your signed APK.
4. Verify the downloaded APK's signing cert matches `AllowedAPKSigningKeys`.
5. Strip the signature block (`META-INF/*SF`, `META-INF/*DSA`, `META-INF/MANIFEST.MF`, etc.) off both APKs.
6. Compare the remaining byte streams. If they match → F-Droid marks this version as reproducible and serves your APK. If not → build rejected, recipe fails.

Step 6 is the unforgiving one. Any single differing byte in any embedded file rejects the whole comparison.

## Our specific gotcha: CRLF vs LF in AutoEQ profiles

This bit us three pipelines in a row (`871c94f0`, `ba713e8c`, `e565c67a`). Here's the full story.

### What actually differed

The app ships thousands of AutoEQ profile files at `app/src/main/assets/autoeq/profiles/**/*.txt`. Each looks like:

```
Preamp: -6.7 dB
Filter 1: ON LSC Fc 105 Hz Gain -2.3 dB Q 0.70
Filter 2: ON PK Fc 183 Hz Gain -3.8 dB Q 0.69
...
```

On our Windows-built v0.0.2-beta APK, every one of those lines ended with `\r\n` (CRLF). On F-Droid's Linux-built APK, every line ended with `\n` (LF alone). Same text, different bytes.

### Why it happened

Windows git with `core.autocrlf=true` (the default for Git-for-Windows) does an insidious thing:

- **On commit:** converts your CRLF working-tree files to LF before storing in the object database.
- **On checkout:** converts LF stored files back to CRLF in the working tree.

So the repo itself has LF — that's what F-Droid's Linux container saw. But the *working tree* on Windows (what your Gradle build reads) has CRLF. The same git commit produced different Gradle inputs on different platforms.

### Why `.gitattributes` fixes it

`.gitattributes` lets you override `core.autocrlf` per file pattern. We declared:

```
*               text=auto eol=lf
*.txt           text eol=lf
*.csv           text eol=lf
…              (others for good measure)
```

The `eol=lf` on a `text` rule means: *"This file is text. Regardless of platform or `core.autocrlf` setting, keep it LF in the working tree."*

### Why simply adding `.gitattributes` wasn't enough

Git doesn't retroactively reformat your working tree when you add `.gitattributes`. The rule applies to new checkouts. So even after committing `.gitattributes`, my Windows working tree still had CRLF in all the AutoEQ files.

The fix is to force git to re-materialize the working tree from the index with the new rules applied:

```bash
git rm --cached -r .           # empty the index (doesn't touch working tree yet)
git reset --hard HEAD           # restore index + working tree from HEAD, NOW applying .gitattributes
```

After that, the working tree has LF, the next Gradle build produces an APK with LF-embedded AutoEQ profiles, and F-Droid's Linux build produces byte-identical output.

## Checking for future CRLF regressions

If someone (you, a collaborator, a tool) edits files outside git-aware tooling on Windows, CRLF can sneak back in. Check with:

```bash
git ls-files --eol | grep w/crlf
```

If anything's listed there, it means the working tree copy has CRLF but git would normalize it to LF. Renormalize:

```bash
git rm --cached -r .
git reset --hard HEAD
```

Then rebuild and verify:

```bash
./gradlew clean assembleRelease …
# Compare APK contents
"C:/Users/icedc/AppData/Local/Android/Sdk/build-tools/35.0.0/apksigner.bat" verify --print-certs \
  app/build/outputs/apk/release/app-release.apk | grep SHA-256
```

The SHA-256 of the **signing cert** stays the same (same key). What you care about is whether F-Droid's next pipeline completes the reproducibility comparison successfully.

## What doesn't count as a reproducibility issue

- **APK signature mismatch** — normal and expected. F-Droid strips the signature before comparison.
- **Timestamps in `META-INF/*`** — stripped along with signatures.
- **Different Gradle caches** — F-Droid builds cleanly each time. Your local cache doesn't propagate into the APK.

## What *does* count

- Line endings (CRLF vs LF) in any text file ✅ (ours was this)
- Baseline profile randomness (`ArtProfile` task output) — already disabled in `build.gradle.kts`
- JVM version differences affecting `.class` file byte layout — unlikely but possible
- Resource ID allocation order if R8 behaves differently between runs — haven't hit this
- Embedded build timestamps (`BuildConfig.BUILD_TIME` style) — we don't generate any
- Any file whose content depends on the absolute path of the build directory — none known in our app
