#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TIMESTAMP_QUPATH_APP_DIR="${TIMESTAMP_QUPATH_APP_DIR:-/Applications/QuPath-0.6.0-arm64.app}"
TIMESTAMP_JAVA_HOME="${TIMESTAMP_JAVA_HOME:-/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home}"
TIMESTAMP_DEV_ROOT="${TIMESTAMP_DEV_ROOT:-/private/tmp/timestamp-qupath-development}"
JAVA_EXECUTABLE="${TIMESTAMP_JAVA_HOME}/bin/java"
QUPATH_APP_LIB="${TIMESTAMP_QUPATH_APP_DIR}/Contents/app"
GUARD_FILE="${TIMESTAMP_DEV_ROOT}/recording-unsaved.guard"
WATCH_MARKER="${TIMESTAMP_DEV_ROOT}/last-build.marker"
DEV_USER_HOME="${TIMESTAMP_DEV_ROOT}/user-home"
DEV_PREFS_ROOT="${TIMESTAMP_DEV_ROOT}/preferences"
QUPATH_CLASSPATH="${PROJECT_DIR}/build/classes/java/main:${PROJECT_DIR}/build/resources/main:${QUPATH_APP_LIB}/*"
QUPATH_PID=""

if [[ ! -x "${JAVA_EXECUTABLE}" ]]; then
    echo "Java was not found at ${JAVA_EXECUTABLE}" >&2
    echo "Set TIMESTAMP_JAVA_HOME to a Java 21 JDK." >&2
    exit 1
fi

if [[ ! -d "${QUPATH_APP_LIB}" ]]; then
    echo "QuPath was not found at ${TIMESTAMP_QUPATH_APP_DIR}" >&2
    echo "Set TIMESTAMP_QUPATH_APP_DIR to the installed QuPath .app path." >&2
    exit 1
fi

mkdir -p "${TIMESTAMP_DEV_ROOT}" "${DEV_USER_HOME}" "${DEV_PREFS_ROOT}"

stop_development_qupath() {
    if [[ -n "${QUPATH_PID}" ]] && kill -0 "${QUPATH_PID}" 2>/dev/null; then
        kill -TERM "${QUPATH_PID}"
        wait "${QUPATH_PID}" 2>/dev/null || true
    fi
    QUPATH_PID=""
}

cleanup() {
    stop_development_qupath
}
trap cleanup EXIT INT TERM

build_project() {
    touch "${WATCH_MARKER}"
    (
        cd "${PROJECT_DIR}"
        env JAVA_HOME="${TIMESTAMP_JAVA_HOME}" ./gradlew test classes
    )
}

start_development_qupath() {
    echo "Starting isolated TimeStamp development instance..."
    (
        cd "${PROJECT_DIR}"
        env TIMESTAMP_DEV_GUARD_FILE="${GUARD_FILE}" \
            "${JAVA_EXECUTABLE}" \
            -Dqupath.prefs.name=io.github.qupath/timestamp-development \
            -Duser.home="${DEV_USER_HOME}" \
            -Djava.util.prefs.userRoot="${DEV_PREFS_ROOT}" \
            -cp "${QUPATH_CLASSPATH}" \
            qupath.QuPath
    ) &
    QUPATH_PID=$!
    echo "Watching for TimeStamp source changes (PID ${QUPATH_PID})."
}

source_has_changed() {
    local changed_file
    changed_file="$(find \
        "${PROJECT_DIR}/src/main" \
        "${PROJECT_DIR}/src/test" \
        "${PROJECT_DIR}/scripts" \
        -type f \
        \( -name '*.java' -o -name '*.groovy' -o -name '*.py' -o -name '*.sh' \) \
        -newer "${WATCH_MARKER}" -print -quit)"
    if [[ -n "${changed_file}" ]]; then
        return 0
    fi
    for build_file in \
        "${PROJECT_DIR}/build.gradle.kts" \
        "${PROJECT_DIR}/settings.gradle.kts" \
        "${PROJECT_DIR}/gradle/libs.versions.toml"; do
        if [[ -f "${build_file}" && "${build_file}" -nt "${WATCH_MARKER}" ]]; then
            return 0
        fi
    done
    return 1
}

echo "TimeStamp development reload mode"
echo "This runs directly from build/classes; it does not reinstall the extension JAR."
echo "Use only for development. Do not open clinical work or keep unsaved QuPath edits in this instance."
echo

build_project
start_development_qupath
waiting_for_safe_restart=false

while true; do
    if ! kill -0 "${QUPATH_PID}" 2>/dev/null; then
        wait "${QUPATH_PID}" 2>/dev/null || true
        QUPATH_PID=""
        echo "The development QuPath instance was closed; stopping the watcher."
        exit 0
    fi

    if source_has_changed; then
        if [[ -f "${GUARD_FILE}" ]]; then
            if [[ "${waiting_for_safe_restart}" == false ]]; then
                echo "Change detected, but TimeStamp has an active or unsaved recording."
                echo "Stop, review, and save or discard it; reload will continue automatically afterward."
                waiting_for_safe_restart=true
            fi
        else
            waiting_for_safe_restart=false
            echo "Change detected. Rebuilding and restarting the development instance..."
            stop_development_qupath
            build_project
            start_development_qupath
        fi
    fi
    sleep 1
done
