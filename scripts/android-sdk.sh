#!/usr/bin/env bash
# Shared helper: locate the Android SDK (and the cmake/ninja that ship with it).
#
# Source this file from a script; do not execute it directly:
#
#   source "$ROOT_DIR/scripts/android-sdk.sh"
#   ensure_android_sdk "$ROOT_DIR" || exit 1
#
# `ensure_android_sdk` exports ANDROID_HOME (and ANDROID_SDK_ROOT when unset), so
# Gradle/AGP and every child process find the SDK without any manual setup.
#
# Lookup order, first valid hit wins:
#   1. $ANDROID_HOME
#   2. $ANDROID_SDK_ROOT
#   3. sdk.dir in <repo>/local.properties   (what Android Studio writes)
#   4. sdk.dir in <repo>/demo/local.properties
#   5. platform default locations (macOS / Linux / Windows)
#
# A directory counts as an SDK when it contains at least one of
# platform-tools, platforms, ndk, cmdline-tools or build-tools.

# True when $1 looks like an Android SDK installation.
_android_sdk_is_valid() {
    local dir="${1:-}"
    [[ -n "$dir" && -d "$dir" ]] || return 1
    [[ -d "$dir/platform-tools" || -d "$dir/platforms" || -d "$dir/ndk" \
        || -d "$dir/cmdline-tools" || -d "$dir/build-tools" ]]
}

# Echo the sdk.dir value from a properties file, or nothing.
_android_sdk_from_properties() {
    local file="${1:-}" dir
    [[ -f "$file" ]] || return 1
    dir="$(sed -n 's/^[[:space:]]*sdk\.dir[[:space:]]*=[[:space:]]*//p' "$file" | head -1)"
    [[ -n "$dir" ]] || return 1
    dir="${dir%$'\r'}"          # tolerate CRLF line endings
    dir="${dir//\\:/:}"         # unescape the ':' of Java properties files
    dir="${dir//\\\\/\\}"       # ... and the escaped backslashes
    printf '%s\n' "$dir"
}

# Echo the first Android SDK directory found, or nothing (exit status 1).
find_android_sdk() {
    local root_dir="${1:-}" candidate dir

    for candidate in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}"; do
        if _android_sdk_is_valid "$candidate"; then
            printf '%s\n' "$candidate"
            return 0
        fi
        if [[ -n "$candidate" ]]; then
            echo "warning: ignoring ${candidate}: not an Android SDK directory" >&2
        fi
    done

    for candidate in "$root_dir/local.properties" "$root_dir/demo/local.properties"; do
        [[ -n "$root_dir" ]] || break
        dir="$(_android_sdk_from_properties "$candidate")" || true
        if _android_sdk_is_valid "$dir"; then
            printf '%s\n' "$dir"
            return 0
        fi
        if [[ -n "$dir" ]]; then
            echo "warning: ignoring sdk.dir=${dir} from ${candidate}: not an Android SDK directory" >&2
        fi
    done

    for candidate in \
        "$HOME/Library/Android/sdk" \
        "$HOME/Android/Sdk" \
        "$HOME/Android/sdk" \
        "${LOCALAPPDATA:-}/Android/Sdk"; do
        if _android_sdk_is_valid "$candidate"; then
            printf '%s\n' "$candidate"
            return 0
        fi
    done

    # Git Bash on Windows keeps LOCALAPPDATA in Windows form.
    if [[ -n "${LOCALAPPDATA:-}" ]] && command -v cygpath > /dev/null 2>&1; then
        candidate="$(cygpath -u "$LOCALAPPDATA" 2>/dev/null)/Android/Sdk"
        if _android_sdk_is_valid "$candidate"; then
            printf '%s\n' "$candidate"
            return 0
        fi
    fi

    return 1
}

# Export ANDROID_HOME/ANDROID_SDK_ROOT, or explain how to point the build at the SDK.
ensure_android_sdk() {
    local root_dir="${1:-}" sdk
    if sdk="$(find_android_sdk "$root_dir")"; then
        export ANDROID_HOME="$sdk"
        export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$sdk}"
        return 0
    fi
    cat >&2 <<'EOF'
error: Android SDK not found.

       Set ANDROID_HOME (or ANDROID_SDK_ROOT), or create local.properties in the
       repository root containing:

           sdk.dir=/absolute/path/to/Android/sdk

       Android Studio writes that file automatically when you open the project.
       The usual install locations are searched too:
           macOS   ~/Library/Android/sdk
           Linux   ~/Android/Sdk
           Windows %LOCALAPPDATA%\Android\Sdk
EOF
    return 1
}

# Make cmake + ninja available (needed to build the host `hermesc`): keep whatever
# is on PATH, otherwise use the copies bundled with the Android SDK.
ensure_cmake_and_ninja() {
    local sdk="${1:-}"
    if command -v cmake > /dev/null 2>&1 && command -v ninja > /dev/null 2>&1; then
        return 0
    fi
    [[ -n "$sdk" ]] || sdk="$(find_android_sdk "${2:-}" 2>/dev/null)" || true
    local sdk_cmake_bin
    sdk_cmake_bin="$(ls -d "$sdk"/cmake/*/bin 2>/dev/null | sort -V | tail -1)"
    if [[ -n "$sdk_cmake_bin" ]]; then
        export PATH="$sdk_cmake_bin:$PATH"
    fi
    if command -v cmake > /dev/null 2>&1 && command -v ninja > /dev/null 2>&1; then
        return 0
    fi
    cat >&2 <<'EOF'
error: cmake and ninja are required to build the host `hermesc`.

       Install them (macOS: `brew install cmake ninja`), or make sure the copies
       bundled with the Android SDK (<sdk>/cmake/<version>/bin) are present.
EOF
    return 1
}
