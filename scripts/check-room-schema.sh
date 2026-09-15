#!/usr/bin/env bash
# Step 31: the phone's database schema must never change without a migration.
#
# A missing migration is not a build error — it is a crash on a shopkeeper's
# phone, on data that may not be synced yet. So CI checks three things after
# the Android build has exported the schema:
#
#   1. the version declared in @Database matches the newest exported schema;
#   2. every step between versions has a Migration, registered on the builder;
#   3. the exported schema files match the committed ones.
#
# Run from the repository root: bash scripts/check-room-schema.sh
set -euo pipefail

SCHEMA_DIR="android/app/schemas/com.hisab.app.data.HisabDatabase"
MIGRATIONS_FILE="android/app/src/main/java/com/hisab/app/data/Migrations.kt"
DATABASE_FILE="android/app/src/main/java/com/hisab/app/data/HisabDatabase.kt"

fail() {
    echo "Room schema check FAILED: $*" >&2
    exit 1
}

[ -d "$SCHEMA_DIR" ] || fail "no exported schemas in $SCHEMA_DIR (build the app first: ./gradlew assembleDebug)"
[ -f "$MIGRATIONS_FILE" ] || fail "missing $MIGRATIONS_FILE"
[ -f "$DATABASE_FILE" ] || fail "missing $DATABASE_FILE"

versions=$(find "$SCHEMA_DIR" -maxdepth 1 -name '*.json' -exec basename {} .json \; | sort -n)
[ -n "$versions" ] || fail "no exported schema files in $SCHEMA_DIR"
newest=$(echo "$versions" | tail -1)

# 1. The declared version and the newest exported schema must agree.
declared=$(grep -oE 'version[[:space:]]*=[[:space:]]*[0-9]+' "$DATABASE_FILE" | grep -oE '[0-9]+' | head -1)
[ -n "$declared" ] || fail "could not read the database version from $DATABASE_FILE"
if [ "$declared" != "$newest" ]; then
    fail "@Database says version $declared but the newest exported schema is $newest — build the app so the schema is exported, then commit it"
fi

# 2. Every version step needs a migration, and it must be registered.
for version in $versions; do
    [ "$version" -eq 1 ] && continue
    previous=$((version - 1))
    name="MIGRATION_${previous}_${version}"

    grep -q "$name" "$MIGRATIONS_FILE" ||
        fail "schema version $version exists but $name is missing from $MIGRATIONS_FILE"
    grep -q "$name" "$DATABASE_FILE" ||
        fail "$name exists but is not passed to addMigrations(...) in $DATABASE_FILE"
done

# 3. The exported schema must match what is committed. This is the check that
#    catches an entity changed without a new version: the build rewrites the
#    schema file for the current version, and the difference shows up here.
if git rev-parse --git-dir >/dev/null 2>&1; then
    changed=$(git status --porcelain -- "$SCHEMA_DIR")
    if [ -n "$changed" ]; then
        echo "$changed" >&2
        fail "the exported schema differs from the committed one — either an entity changed without a new version and migration, or the new schema was not committed"
    fi
fi

echo "Room schema check passed: versions $(echo "$versions" | tr '\n' ' ')- each with a registered migration, matching the committed files."
