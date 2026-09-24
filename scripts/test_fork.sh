#!/usr/bin/env bash
set -euo pipefail

scriptDir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
cd "$scriptDir/.."
upstreamRef=${1:-upstream/main}
buildJavaHome=${JAVA_HOME:-"$HOME/.local/graalvm/liberica-nik-25"}

PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts -p 'test_check_upstream_version.py'
python3 scripts/check_upstream_version.py --upstream-ref "$upstreamRef"

env JAVA_HOME="$buildJavaHome" \
    GRAALVM_HOME="${GRAALVM_HOME:-$buildJavaHome}" \
    PATH="$buildJavaHome/bin:$PATH" \
    ./gradlew :composeApp:jvmTest \
    --tests 'dev.etorix.panoscrobbler.fork.*' \
    --tests 'dev.etorix.panoscrobbler.MediaListenerPromotionTest' \
    --tests 'dev.etorix.panoscrobbler.ThemePrefsTest' \
    --tests 'dev.etorix.panoscrobbler.ScrobbleDataTest'
