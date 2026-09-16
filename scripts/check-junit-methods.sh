#!/usr/bin/env bash
# Fails if any JUnit @Test, @Before or @After method returns something.
#
# JUnit 4 only runs methods that return void. Kotlin makes that easy to break
# without noticing: `fun setUp() = runBlocking { ... }` returns whatever its
# last line returns, so a setup that happens to end with `repository.save(x)`
# returns a value, and JUnit refuses the whole class ("Method setUp() should be
# void"). It compiles. Nothing complains. Unit tests on the JVM would catch it,
# but on-phone tests only fail on a phone — which CI does not have.
#
# This has happened twice: a HomeScreenTest method in M0, and SaleFlowTest's
# setup at Step 40, which silently took all ten of its tests out of a device
# run. So this reads the compiled classes, where the real return type is
# written down, and checks every annotated method. It runs in CI right after
# the Android compile, so the mistake is caught on every push instead of on
# the next device run.
#
# Usage (from the repository root, after compiling):
#   bash scripts/check-junit-methods.sh <compiled-classes-dir> [more dirs...]
set -euo pipefail

if [ $# -eq 0 ]; then
    echo "usage: $0 <compiled-classes-dir> [more dirs...]" >&2
    exit 2
fi

JAVAP="${JAVA_HOME:+$JAVA_HOME/bin/}javap"

problems=""
checked=0
for dir in "$@"; do
    if [ ! -d "$dir" ]; then
        echo "JUnit method check FAILED: no compiled classes at $dir — compile first." >&2
        exit 1
    fi

    count=$(find "$dir" -name '*.class' | wc -l | tr -d ' ')
    checked=$((checked + count))

    # Relative paths from inside the directory keep each javap command line
    # short; Windows refuses command lines past ~32,000 characters.
    found=$(
        cd "$dir" &&
            find . -name '*.class' -print0 |
            xargs -0 -n 80 "$JAVAP" -v -p 2>/dev/null |
            awk '
                # A class header: remember which class the methods belong to.
                /^[a-z ]*(class|interface) [A-Za-z0-9_.$]+/ {
                    for (i = 1; i <= NF; i++) {
                        if ($i == "class" || $i == "interface") { cls = $(i + 1); break }
                    }
                    next
                }
                # A method header is indented by exactly two spaces and ends in ");".
                /^  [^ ].*\);$/ {
                    method = $0
                    sub(/^  /, "", method)
                    returns = ""
                    next
                }
                /^    descriptor: / {
                    returns = $2
                    sub(/^.*\)/, "", returns)
                    next
                }
                /^        org\.junit\.(Test|Before|After|BeforeClass|AfterClass)(\(|$)/ {
                    if (returns != "" && returns != "V") {
                        annotation = $1
                        sub(/\(.*/, "", annotation)
                        print "  " cls ": @" annotation " " method
                    }
                }
            '
    )
    if [ -n "$found" ]; then
        problems="${problems}${found}"$'\n'
    fi
done

if [ "$checked" -eq 0 ]; then
    echo "JUnit method check FAILED: found no compiled classes to check." >&2
    exit 1
fi

if [ -n "$problems" ]; then
    echo "JUnit method check FAILED: these must return nothing, or JUnit will not run their class:" >&2
    printf '%s' "$problems" >&2
    echo "Fix: give the function an explicit ': Unit' return type, e.g. 'fun setUp(): Unit = runBlocking { ... }'." >&2
    exit 1
fi

echo "JUnit method check passed: every @Test/@Before/@After method returns void, across $checked compiled classes."
