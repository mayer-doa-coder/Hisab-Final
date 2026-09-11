#!/usr/bin/env bash
# Fails if a string key exists in one language but not the other.
# Bangla (values/) is the default; English (values-en/) is the alternate.
# Keys marked translatable="false" are expected only in the default file.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BN="$ROOT/android/app/src/main/res/values/strings.xml"
EN="$ROOT/android/app/src/main/res/values-en/strings.xml"

for f in "$BN" "$EN"; do
  if [ ! -f "$f" ]; then
    echo "ERROR: missing strings file: $f"
    exit 1
  fi
done

# Translatable keys from the Bangla default file.
bn_keys=$(grep -oE '<string name="[^"]+"[^>]*>' "$BN" \
  | grep -v 'translatable="false"' \
  | grep -oE 'name="[^"]+"' | cut -d'"' -f2 | sort)

en_keys=$(grep -oE '<string name="[^"]+"' "$EN" \
  | cut -d'"' -f2 | sort)

missing_in_en=$(comm -23 <(echo "$bn_keys") <(echo "$en_keys"))
missing_in_bn=$(comm -13 <(echo "$bn_keys") <(echo "$en_keys"))

status=0

if [ -n "$missing_in_en" ]; then
  echo "Missing English translation (present in Bangla, absent in values-en/):"
  echo "$missing_in_en" | sed 's/^/  - /'
  status=1
fi

if [ -n "$missing_in_bn" ]; then
  echo "Missing Bangla string (present in English, absent in values/):"
  echo "$missing_in_bn" | sed 's/^/  - /'
  status=1
fi

if [ "$status" -eq 0 ]; then
  echo "Localization check passed: Bangla and English string keys match."
fi

exit "$status"
