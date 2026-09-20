# F-Droid submission guide

## One-time setup

### 1. Fill in AllowedAPKSigningKeys

Get the SHA-256 fingerprint of the release signing cert, strip the colons, and paste it into `fdroid/app.mmmap.yml`:

```sh
keytool -list -v -keystore mmmap-release.jks -alias mmmap \
    | grep "SHA-256"
# example output: SHA-256: AA:BB:CC:DD:EE:...
# paste as:       aabbccddee...  (lowercase, no colons)
```

Edit the placeholder in `fdroid/app.mmmap.yml`:
```yaml
AllowedAPKSigningKeys: aabbccddee...
```

---

### 2. Add screenshots, icon, and feature graphic

Install the app on a device or emulator (`just run`), then capture the following screenshots and export them as PNG:

| Shot | What to show |
|---|---|
| 1 | Map with restaurant pins visible |
| 2 | Filter sheet open (award/cuisine/price chips) |
| 3 | Restaurant detail sheet (name, award, cuisine, facilities) |
| 4 | Near Me list |
| 5 | Visited list or Settings screen |

Save them here:
```
fastlane/metadata/android/en-US/images/
  phoneScreenshots/
    1.png
    2.png
    3.png
    4.png
    5.png
  icon.png           (512×512 — export from mipmap-xxxhdpi or the app icon SVG)
  featureGraphic.png (1024×500 — a banner; docs/banner.svg is a starting point)
```

Capture via adb if you prefer:
```sh
adb shell screencap -p /sdcard/shot.png && adb pull /sdcard/shot.png 1.png
```

---

## Submitting to fdroiddata

### 3. Fork fdroiddata

```sh
# on GitLab: fork https://gitlab.com/fdroid/fdroiddata
# then clone your fork locally
git clone git@gitlab.com:YOUR_GITLAB_USERNAME/fdroiddata.git
cd fdroiddata
git remote add upstream https://gitlab.com/fdroid/fdroiddata.git
git fetch upstream
git checkout -b add-app.mmmap upstream/master
```

### 4. Install fdroidserver (for local validation)

```sh
pip install fdroidserver
# or on Debian/Ubuntu:
sudo apt install fdroidserver
```

### 5. Copy and validate the metadata

```sh
cp /path/to/Mmmap/fdroid/app.mmmap.yml metadata/app.mmmap.yml

# auto-format to fdroiddata style (fixes whitespace, field order, etc.)
fdroid rewritemeta app.mmmap

# lint for errors
fdroid lint app.mmmap
# expected: no errors
```

### 6. (Optional) Test the build locally

Run from inside the fdroiddata clone — uses your host JDK 17 + Android SDK:

```sh
# all four ABI blocks
fdroid build --no-tarball --verbose app.mmmap

# or just one, by versionCode
fdroid build --no-tarball --verbose app.mmmap:105001
```

This clones Mmmap at the commit pinned in each `Builds:` block into
`build/app.mmmap/` and runs one `./gradlew assembleRelease` per block, e.g.
`-PversionName=1.5 -PversionCode=105001 -PabiFilter=armeabi-v7a`, validating
each output APK. Fix any build failures before submitting.

> Note: `--on-server` is for use inside F-Droid's build farm only
> (it tries to lock the host root account). Don't pass it for local
> verification on macOS or Linux desktops.

### 7. Open the Merge Request

```sh
git add metadata/app.mmmap.yml
git commit -m "Add app.mmmap (Mmmap — MICHELIN Guide map)"
git push origin add-app.mmmap
```

Then open a MR on GitLab against `fdroid/fdroiddata:master`.

Suggested MR description:

```
## Mmmap — MICHELIN Guide restaurant map

**App ID**: app.mmmap
**License**: MIT
**Source**: https://github.com/Rozkipz/Mmmap

Plots every MICHELIN Guide restaurant on an offline-first MapLibre map.
No GMS, no Firebase, no Mapbox. Fully self-hostable data via GitHub CSV sync.

### Anti-features
- `NonFreeNet`: dataset refresh from raw.githubusercontent.com and map tiles from tiles.openfreemap.org.

### Reproducibility
Build instructions are in BUILDING.md. The release APK is signed by upstream;
AllowedAPKSigningKeys is set so F-Droid can verify the byte-match and serve
the developer-signed APK.

### Build verification
Tested with: JDK 17, AGP 9.2.1, Android SDK platform-36, NDK r28c.
One build block per ABI (armeabi-v7a, arm64-v8a, x86, x86_64); each reproduces
byte-for-byte against the correspondingly-named APK on the GitHub Release.
```

---

## Per-release checklist (after initial acceptance)

Each time you cut a new release, do these steps **before** tagging:

```sh
# 1. Refresh the bundled DB
just seed-db

# 2. Bump both literals in app/build.gradle.kts AND the versions in
#    fdroid/app.mmmap.yml (bump-version keeps them in lockstep)
just bump-version X.Y

# 3. Write the fastlane changelog — one file per ABI versionCode, because
#    F-Droid resolves changelogs by each APK's own versionCode
just changelog "What changed in this release."

# 4. Update CHANGELOG.md, then commit steps 1–4 and push

# 5. Release: builds one APK per ABI plus an unsplit universal APK into dist/,
#    tags, and publishes them all to the GitHub Release
just release X.Y

# 6. Point the F-Droid recipe at the tagged commit (a full SHA, not a tag name)
just fdroid-stamp-commit
```

Then verify before touching the MR:

```sh
# mirrors fdroiddata's CI (schema, lint, rewritemeta no-op, checkupdates, scanner)
gh workflow run verify-fdroid.yml -f version=X.Y
```

After the GitHub Release is published, F-Droid detects the new tag via `UpdateCheckMode: Tags`,
reads the base versionCode out of `app/build.gradle.kts`, applies each `VercodeOperation` to
generate the four build blocks, builds each from source, verifies the byte-match against the
correspondingly-named signed APK from `Binaries:`, and publishes automatically — no MR needed
for subsequent releases.

### ABI split

`Builds:` has one block per ABI. Each passes `abiFilter` via `gradleprops`, which makes
`app/build.gradle.kts` emit a single-ABI APK stamped `10 * base + offset`:

| ABI | offset | gradleprops | versionCode at base 10500 |
|---|---|---|---|
| `armeabi-v7a` | 1 | `abiFilter=armeabi-v7a` | 105001 |
| `arm64-v8a` | 2 | `abiFilter=arm64-v8a` | 105002 |
| `x86` | 3 | `abiFilter=x86` | 105003 |
| `x86_64` | 4 | `abiFilter=x86_64` | 105004 |

Three things must stay in sync, or F-Droid fails the build with
`Unexpected versionCode in output`:

- the offsets in `abiVersionCodeOffsets` (`app/build.gradle.kts`)
- the `VercodeOperation` list (`fdroid/app.mmmap.yml`) — its **order must match the order of
  the `Builds:` blocks**, because `checkupdates` deep-copies the last four blocks and zips
  them against that list
- the `Binaries:` filenames, which use `%c` and so must match what `just assemble-release`
  writes into `dist/`

The ordering `armeabi-v7a < arm64-v8a < x86 < x86_64` is mandated by F-Droid so clients
resolve the right variant for their device.

Every build block must also carry `ndk: r28c`. F-Droid provisions an NDK only when the
recipe asks for one; without it AGP silently skips stripping the prebuilt `.so` files that
ship inside AARs, and the build stops reproducing on a single file:

```
lib/<abi>/libdatastore_shared_counter.so
  ours   5916 bytes, stripped
  fdroid 8432 bytes, NOT stripped
```

This has regressed once already — pinned in `7cded41`, then dropped by `03b8190` when
`Builds:` was split into four blocks. It only surfaces ~25 minutes into the reproducible
job, or as a rejected merge request, so `verify-fdroid.yml` asserts `ndk:` pins == build
blocks in the fast metadata job.

Note `fdroid rewritemeta` **strips comments**, and `verify-fdroid.yml` asserts it is a no-op —
so keep `fdroid/app.mmmap.yml` free of comments and in canonical form.

---

## After acceptance

Add the F-Droid badge to `README.md`:

```markdown
[<img src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png" height="75">](https://f-droid.org/packages/app.mmmap)
```
