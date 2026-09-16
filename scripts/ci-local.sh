#!/usr/bin/env bash
# Runs, on this machine, every check GitHub CI runs — the same commands, in
# the same order — and prints one summary at the end.
#
# Why this exists: every CI failure this repository has had came from checking
# something narrower than what CI checks. Prettier was run over one folder
# while CI runs it over the whole server; a green run was trusted while it ran
# 21 of 64 tests; a build that did nothing was read as a build with no
# warnings. Running a subset and calling it done is the mistake. This runs the
# whole set, so "passes here" and "passes in CI" mean the same thing.
#
# It does not stop at the first failure. CI runs the Android and server jobs
# independently, so both answers are worth having at once.
#
# The one step it cannot mirror is "Set up Android SDK": that runs on GitHub's
# machine and can break on its own (it did, when Google stopped serving the
# legacy `tools` package). When CI fails there, the cause is upstream, not in
# this code — look at the action's issue tracker before changing anything.
#
# KEEP IN STEP WITH .github/workflows/ci.yml. A step added there and not here
# makes this script lie.
#
# Run from the repository root:  bash scripts/ci-local.sh
# Needs: JDK 17+ as JAVA_HOME, Node 22+, and the local Postgres from D030.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

results=()
failed=0

# Runs one named step from a directory, records pass or fail, and carries on.
step() {
    local name="$1" dir="$2"
    shift 2
    echo
    echo "==================================================================="
    echo "  $name"
    echo "==================================================================="
    if (cd "$ROOT/$dir" && "$@"); then
        results+=("PASS  $name")
    else
        results+=("FAIL  $name")
        failed=1
    fi
}

# --- Before anything: the environment traps already hit on this machine. ---

java_major=$("${JAVA_HOME:+$JAVA_HOME/bin/}java" -version 2>&1 | grep -oE 'version "[0-9]+' | grep -oE '[0-9]+' | head -1 || true)
if [ -z "$java_major" ] || [ "$java_major" -lt 17 ]; then
    echo "ci-local: Gradle needs JDK 17 or newer, but JAVA_HOME gives JDK '${java_major:-none}'." >&2
    echo "          Point JAVA_HOME at a newer JDK — Android Studio ships one in its 'jbr' folder." >&2
    exit 2
fi

# A stopped Postgres does not look like a stopped Postgres: it shows up as
# "migration failed" and dozens of failed server tests, which reads like broken
# code. Checked first so it says what it actually is. Only the default local
# database is checked — a custom DATABASE_URL is left to speak for itself.
if [ -z "${DATABASE_URL:-}" ] && ! (exec 3<>/dev/tcp/127.0.0.1/5433) 2>/dev/null; then
    echo "ci-local: nothing is listening on 127.0.0.1:5433, so every database test would fail." >&2
    echo "          Start the local Postgres first (README, 'Database (Postgres)'):" >&2
    echo '          "$PG/bin/pg_ctl" -D "$PG/../data" -o "-p 5433" -l "$PG/../server.log" start' >&2
    exit 2
fi

# --- Android job, in ci.yml's order. ---

step "Android: Localization completeness" . bash scripts/check-localization.sh
step "Android: Compile" android ./gradlew --console=plain assembleDebug compileDebugAndroidTestKotlin
step "Android: JUnit method signatures" . bash scripts/check-junit-methods.sh android/app/build/intermediates/built_in_kotlinc/debugAndroidTest/compileDebugAndroidTestKotlin/classes
step "Android: Room schema and migrations" . bash scripts/check-room-schema.sh
step "Android: Unit tests" android ./gradlew --console=plain testDebugUnitTest
step "Android: Unit test count" . bash scripts/report-unit-tests.sh android/app/build/test-results/testDebugUnitTest
step "Android: Lint" android ./gradlew --console=plain lintDebug spotlessCheck

# --- Server job, in ci.yml's order. ---

# CI starts from a fresh checkout, so it never has a stale dist/. Locally a
# deleted test's compiled file can linger there and keep running as a ghost —
# that happened once (Steps 16–17). Clearing it makes the count honest.
rm -rf "$ROOT/server/dist"

step "Server: npm ci" server npm ci
step "Server: Compile" server npm run build
step "Server: Database migrations" server npm run migrate
step "Server: Unit tests" server npm test
step "Server: Lint" server npm run lint
step "Server: Format check" server npm run format:check

# --- Summary. ---

echo
echo "==================================================================="
echo "  Summary (mirrors .github/workflows/ci.yml)"
echo "==================================================================="
for line in "${results[@]}"; do
    echo "  $line"
done

if [ "$failed" -ne 0 ]; then
    echo
    echo "Not ready to push: fix every FAIL above first."
    echo "A 'Room schema and migrations' FAIL that only lists an untracked schema"
    echo "JSON file means the new schema has not been committed yet — commit it."
    exit 1
fi

echo
echo "Everything CI checks passes here. After pushing, still confirm the real run:"
echo "  gh run list --limit 1"
exit 0
