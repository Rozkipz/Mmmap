package := "app.mmmap"
gradle  := "./gradlew"
java17  := env_var_or_default("JAVA_HOME", env_var("HOME") + "/.local/jdk/jdk-17.0.19+10")

export JAVA_HOME := java17

# List all available commands
default:
    @just --list

# ─── Dev ──────────────────────────────────────────────────────────────────────

# Build a debug APK  →  app/build/outputs/apk/debug/
build:
    {{gradle}} assembleDebug

# Install APK on a connected device or emulator (release by default; pass 'debug' for a debug build)
install variant='release':
    {{gradle}} install{{capitalize(variant)}}

# Shorthand for `just install debug`
installdebug: (install "debug")

# Install and launch the app (requires adb + connected device)
# The debug build carries applicationIdSuffix ".debug", so the launch component
# differs from the release one — resolve it from the variant.
run variant='release': (install variant)
    #!/usr/bin/env bash
    set -euo pipefail
    PKG="{{package}}$([ "{{variant}}" = "debug" ] && echo ".debug" || true)"
    adb shell am start -n "$PKG/{{package}}.MainActivity"

# Delete all build outputs
clean:
    {{gradle}} clean

# ─── Quality ──────────────────────────────────────────────────────────────────

# Run unit tests
test:
    {{gradle}} testDebugUnitTest

# Run unit tests with JaCoCo coverage  →  app/build/reports/coverage/test/debug/index.html
coverage:
    {{gradle}} testDebugUnitTest createDebugUnitTestCoverageReport

# Run instrumented tests on a connected device or emulator
test-instrumented:
    {{gradle}} connectedDebugAndroidTest

# Run Android lint checks
lint:
    {{gradle}} lintDebug

# Auto-format Kotlin source with ktlint
format:
    {{gradle}} ktlintFormat

# Run lint + unit tests — do this before committing
check: lint test

# ─── Release ──────────────────────────────────────────────────────────────────

# Build the signed release APKs — one per ABI plus an unsplit universal APK.
# e.g. `just assemble-release 1.2 10200`  or plain `just assemble-release`
# When version is given without code, derives code = MAJOR*10000 + MINOR*100.
#
# Each ABI gets its own gradle invocation, exactly as F-Droid's four build blocks do,
# so the published artifact is byte-identical to what their builder produces.
# Outputs land in dist/ as Mmmap-<versionCode>.apk, matching the `Binaries:` URL pattern.
assemble-release version='' code='':
    #!/usr/bin/env bash
    set -euo pipefail
    VERSION="{{version}}"
    CODE="{{code}}"
    ARGS=()
    if [[ -n "$VERSION" ]]; then
        if ! [[ "$VERSION" =~ ^([0-9]+)\.([0-9]+)$ ]]; then
            echo "error: version must be MAJOR.MINOR (got '$VERSION')" >&2
            exit 1
        fi
        ARGS+=("-PversionName=$VERSION")
        if [[ -z "$CODE" ]]; then
            CODE=$(( BASH_REMATCH[1] * 10000 + BASH_REMATCH[2] * 100 ))
        fi
    fi
    if [[ -n "$CODE" ]]; then
        ARGS+=("-PversionCode=$CODE")
    fi
    rm -rf dist && mkdir -p dist
    OUT=app/build/outputs/apk/release
    # versionCode and filename come from AGP's own output-metadata.json, so this works
    # whether or not keystore.properties is present (unsigned builds are named differently).
    stage() {
        python3 -c 'import json,shutil,sys; o,s=sys.argv[1],sys.argv[2]; e=json.load(open(o+"/output-metadata.json"))["elements"][0]; d="dist/Mmmap-%d%s.apk"%(e["versionCode"],s); shutil.copy(o+"/"+e["outputFile"],d); print("  ->",d,"from",e["outputFile"])' "$OUT" "$1"
    }
    # macOS ships bash 3.2, where a bare "${ARGS[@]}" on an empty array trips `set -u`.
    # ${ARGS[@]+"${ARGS[@]}"} is the portable form: expands to nothing when ARGS is empty,
    # and preserves per-element quoting otherwise.
    for ABI in armeabi-v7a arm64-v8a x86 x86_64; do
        {{gradle}} assembleRelease ${ARGS[@]+"${ARGS[@]}"} -PabiFilter="$ABI"
        stage ""
    done
    # Unsplit build for people sideloading from GitHub who don't know their ABI.
    {{gradle}} assembleRelease ${ARGS[@]+"${ARGS[@]}"}
    stage "-universal"
    ls -la dist/

# Create a signed git tag and push it  →  e.g. `just tag 1.2`
tag version:
    #!/usr/bin/env bash
    set -euo pipefail
    if ! [[ "{{version}}" =~ ^[0-9]+\.[0-9]+$ ]]; then
        echo "error: version must be MAJOR.MINOR (got '{{version}}')" >&2
        exit 1
    fi
    git tag -s v{{version}} -m "Release v{{version}}"
    git push origin v{{version}}

# Write the release notes to one fastlane changelog per ABI versionCode.
# F-Droid resolves changelogs by each APK's own versionCode, so all four need the file.
# e.g. `just changelog "Adds per-architecture APKs."`
changelog text:
    #!/usr/bin/env bash
    set -euo pipefail
    BASE=$(grep -E '^\s+versionCode = [0-9]+$' app/build.gradle.kts | sed -E 's/.* ([0-9]+)$/\1/')
    DIR=fastlane/metadata/android/en-US/changelogs
    for N in 1 2 3 4; do
        printf '%s\n' "{{text}}" > "$DIR/$(( BASE * 10 + N )).txt"
    done
    ls -la "$DIR"

# Create a GitHub Release and attach every APK in dist/
# Pass target SHA to pin the tag to a specific commit (e.g. $GITHUB_SHA in CI)
# Uses fastlane changelog if present, otherwise builds bullet-point notes from commits since last tag
gh-release version target='':
    #!/usr/bin/env bash
    set -euo pipefail
    if ! compgen -G "dist/*.apk" > /dev/null; then
        echo "error: no APKs in dist/ — run 'just assemble-release' first" >&2
        exit 1
    fi
    BASE=$(grep -E '^\s+versionCode = [0-9]+$' app/build.gradle.kts | sed -E 's/.* ([0-9]+)$/\1/')
    NOTES_FILE="fastlane/metadata/android/en-US/changelogs/$(( BASE * 10 + 2 )).txt"
    TARGET_FLAG={{ if target != '' { '"--target ' + target + '"' } else { '""' } }}
    if [[ -f "$NOTES_FILE" ]]; then
      gh release create "v{{version}}" $TARGET_FLAG --title "v{{version}}" --notes-file "$NOTES_FILE" dist/*.apk
    else
      PREV=$(git describe --tags --abbrev=0 HEAD^ 2>/dev/null || git rev-list --max-parents=0 HEAD)
      CHANGELOG=$(git log "${PREV}..HEAD" --pretty=format:"- %s")
      gh release create "v{{version}}" $TARGET_FLAG --title "v{{version}}" --notes "$CHANGELOG" dist/*.apk
    fi

# Bump build.gradle.kts to match the given MAJOR.MINOR version (no commit)
# F-Droid's checkupdates regex parses the literal versionCode/versionName lines
# in defaultConfig — gradle.properties is invisible to it. Keep both literals
# in build.gradle.kts in sync with git tags.
bump-version version:
    #!/usr/bin/env bash
    set -euo pipefail
    if ! [[ "{{version}}" =~ ^([0-9]+)\.([0-9]+)$ ]]; then
        echo "error: version must be MAJOR.MINOR (got '{{version}}')" >&2
        exit 1
    fi
    CODE=$(( BASH_REMATCH[1] * 10000 + BASH_REMATCH[2] * 100 ))
    sed -i.bak -E "s/^([[:space:]]*)versionCode = [0-9]+$/\1versionCode = $CODE/" app/build.gradle.kts
    sed -i.bak -E "s/^([[:space:]]*)versionName = \"[0-9.]+\"$/\1versionName = \"{{version}}\"/" app/build.gradle.kts
    rm -f app/build.gradle.kts.bak
    echo "bumped: versionName={{version}} versionCode=$CODE"
    # Keep the F-Droid recipe in lockstep. Drift between build.gradle.kts and the
    # metadata is what makes fdroiddata's checkupdates/lint jobs fail after a release.
    just _sync-fdroid-versions "{{version}}" "$CODE"
    echo "per-ABI versionCodes: $((CODE * 10 + 1)) $((CODE * 10 + 2)) $((CODE * 10 + 3)) $((CODE * 10 + 4))"

# Rewrite versionName/versionCode/CurrentVersion in fdroid/app.mmmap.yml (internal).
# Touches only those lines so the file stays byte-identical to `fdroid rewritemeta` output.
_sync-fdroid-versions version code:
    #!/usr/bin/env bash
    set -euo pipefail
    YML=fdroid/app.mmmap.yml
    awk -v v="{{version}}" -v b="{{code}}" 'BEGIN { q = sprintf("%c", 39) }
        /^  - versionName: /      { print "  - versionName: " q v q; next }
        /^    versionCode: /      { n++; print "    versionCode: " b * 10 + n; next }
        /^CurrentVersion: /       { print "CurrentVersion: " q v q; next }
        /^CurrentVersionCode: /   { print "CurrentVersionCode: " b * 10 + 4; next }
                                  { print }' "$YML" > "$YML.tmp"
    mv "$YML.tmp" "$YML"
    echo "synced $YML to {{version}} ($(( {{code}} * 10 + 1 ))-$(( {{code}} * 10 + 4 )))"

# Point every F-Droid build block at a specific commit (defaults to the tag for the
# current versionName). F-Droid requires a full SHA here, not a tag name.
fdroid-stamp-commit sha='':
    #!/usr/bin/env bash
    set -euo pipefail
    SHA="{{sha}}"
    if [[ -z "$SHA" ]]; then
        VERSION=$(grep -E '^\s+versionName = "[0-9.]+"$' app/build.gradle.kts | sed -E 's/.*"([0-9.]+)".*/\1/')
        SHA=$(git rev-parse "v${VERSION}^{commit}")
        echo "using tag v${VERSION} -> $SHA"
    fi
    sed -i.bak -E "s/^    commit: .*$/    commit: ${SHA}/" fdroid/app.mmmap.yml
    rm -f fdroid/app.mmmap.yml.bak
    grep -n "commit:" fdroid/app.mmmap.yml

# Full release pipeline: bump → clean → check → sign APK → commit bump → tag → GitHub Release
release version:
    #!/usr/bin/env bash
    set -euo pipefail
    just bump-version {{version}}
    just clean
    just check
    just assemble-release {{version}}
    git add app/build.gradle.kts fdroid/app.mmmap.yml gradle.properties
    if ! git diff --cached --quiet; then
        git commit -m "chore(release): v{{version}}"
        git push origin HEAD
    fi
    just tag {{version}}
    just gh-release {{version}}

# ─── Phase 2 (F-Droid) ────────────────────────────────────────────────────────

# Download the upstream MICHELIN CSV and regenerate the bundled seed asset
# Needs Python 3.10+ (the script uses `str | None` annotations); macOS ships 3.9.
seed-db:
    #!/usr/bin/env bash
    set -euo pipefail
    PY=""
    for c in python3.14 python3.13 python3.12 python3.11 python3.10 python3; do
        if command -v "$c" >/dev/null 2>&1 && \
           "$c" -c 'import sys; sys.exit(0 if sys.version_info >= (3,10) else 1)'; then
            PY="$c"; break
        fi
    done
    if [[ -z "$PY" ]]; then
        echo "error: need Python 3.10+ on PATH (found $(python3 --version 2>&1))" >&2
        exit 1
    fi
    echo "using $PY ($($PY --version))"
    "$PY" scripts/seed_db.py

# Validate F-Droid metadata YAML (run from inside a fdroiddata clone)
fdroid-lint path='../fdroiddata':
    fdroid lint --allow-disabled-algorithms {{path}}/metadata/{{package}}.yml

# Simulate a reproducible F-Droid build locally (requires fdroidserver + Docker)
fdroid-build-local path='../fdroiddata':
    fdroid build --on-server --no-tarball --verbose {{package}}

# ─── Utilities ────────────────────────────────────────────────────────────────

# Scan dependencies for proprietary libs (GMS/Firebase/Mapbox) — should print OK
deps-audit:
    {{gradle}} :app:dependencies | grep -Ei 'gms|firebase|mapbox' && echo "WARNING: proprietary deps found" || echo "OK: no proprietary deps"

# Generate a release signing keystore and write keystore.properties (run once, back up the .jks)
setup-keystore:
    #!/usr/bin/env bash
    set -euo pipefail
    KEYSTORE_FILE="mmmap-release.jks"
    PROPS_FILE="keystore.properties"

    if [[ -f "$KEYSTORE_FILE" ]]; then
        echo "$KEYSTORE_FILE already exists — delete it manually to regenerate."
        exit 1
    fi

    echo "Creating release keystore at $KEYSTORE_FILE"
    echo "You will be prompted for a keystore password."

    keytool -genkeypair \
        -v \
        -keystore "$KEYSTORE_FILE" \
        -keyalg RSA \
        -keysize 4096 \
        -validity 10000 \
        -alias mmmap \
        -dname "CN=Mmmap, O=Mmmap, C=GB"

    echo
    echo "Enter keystore password (same as above):"
    read -rs STORE_PASS
    echo "Enter key password (leave blank to reuse keystore password):"
    read -rs KEY_PASS
    KEY_PASS="${KEY_PASS:-$STORE_PASS}"

    printf 'storeFile=%s\nstorePassword=%s\nkeyAlias=mmmap\nkeyPassword=%s\n' \
        "$KEYSTORE_FILE" "$STORE_PASS" "$KEY_PASS" > "$PROPS_FILE"

    echo
    echo "Written to $PROPS_FILE"
    echo "IMPORTANT: back up both $KEYSTORE_FILE and $PROPS_FILE — they are gitignored."
