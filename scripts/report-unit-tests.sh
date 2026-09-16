#!/usr/bin/env bash
# Prints how many Android unit tests actually ran, and fails if the answer is
# none.
#
# Gradle prints "BUILD SUCCESSFUL" whether it ran 125 tests or zero, so a green
# run on its own proves nothing about coverage. This repository has already
# been caught that way once: CI was green for weeks while running 21 of the
# server's 64 tests (Step 22). The server's test runner prints its own count;
# Gradle's does not, so this reads the JUnit XML it writes and says the number
# out loud, in the CI log, where a sudden drop is visible.
#
# Used by .github/workflows/ci.yml and by scripts/ci-local.sh, so there is one
# definition of "how many tests ran", not two.
#
# Run from the repository root:
#   bash scripts/report-unit-tests.sh android/app/build/test-results/testDebugUnitTest
set -euo pipefail

RESULTS_DIR="${1:-android/app/build/test-results/testDebugUnitTest}"

shopt -s nullglob
files=("$RESULTS_DIR"/*.xml)
if [ ${#files[@]} -eq 0 ]; then
    echo "Unit test report FAILED: no result files in $RESULTS_DIR — did the tests run at all?" >&2
    exit 1
fi

# One attribute of a file's <testsuite> element, or 0 if it is absent.
attribute() {
    local value
    value=$(grep -o '<testsuite [^>]*>' "$1" | head -1 | grep -oE " $2=\"[0-9]+\"" | grep -oE '[0-9]+' || true)
    echo "${value:-0}"
}

tests=0
failures=0
errors=0
skipped=0
for file in "${files[@]}"; do
    tests=$((tests + $(attribute "$file" tests)))
    failures=$((failures + $(attribute "$file" failures)))
    errors=$((errors + $(attribute "$file" errors)))
    skipped=$((skipped + $(attribute "$file" skipped)))
done

echo "Android unit tests: $tests run, $failures failed, $errors errors, $skipped skipped, across ${#files[@]} classes."

if [ "$tests" -eq 0 ]; then
    echo "Unit test report FAILED: result files exist but contain no tests." >&2
    exit 1
fi
if [ $((failures + errors)) -gt 0 ]; then
    echo "Unit test report FAILED: $failures failures and $errors errors." >&2
    exit 1
fi
