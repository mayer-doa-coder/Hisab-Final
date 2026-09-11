#!/usr/bin/env bash
# Keeps Bangla and English in step, with Bangla treated as the primary language.
#
# Bangla lives in values/strings.xml — the *default* resource set, so it is what
# shows for any language the app hasn't been translated into. English is the
# alternate, in values-en/strings.xml.
#
# Fails the build on:
#   1. a values-bn/ folder existing (Bangla belongs in the default values/)
#   2. a key in Bangla with no English translation
#   3. a key in English with no Bangla original (means someone wrote English first)
#   4. a Bangla string that is present but empty
#
# Keys marked translatable="false" are expected only in the default file.
# See DECISIONS.md D009/D014 and docs/LOCALIZATION.md.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RES="$ROOT/android/app/src/main/res"
BN="$RES/values/strings.xml"
EN="$RES/values-en/strings.xml"

status=0

for f in "$BN" "$EN"; do
  if [ ! -f "$f" ]; then
    echo "ERROR: missing strings file: $f"
    exit 1
  fi
done

# 1. Bangla must be the default resource set, not a locale-qualified one.
# A values-bn/ folder would split Bangla in two and stop it being the fallback
# for every other language.
if [ -d "$RES/values-bn" ]; then
  echo "ERROR: found $RES/values-bn/"
  echo "  Bangla is the default language and belongs in values/, not values-bn/."
  echo "  Move those strings into values/strings.xml and delete the folder."
  status=1
fi

# Drop XML comments first, so a commented-out <string> is never counted.
strip_comments() {
  awk '
    { line = $0
      while (1) {
        if (inc) { i = index(line, "-->"); if (!i) { line = ""; break }
                   line = substr(line, i + 3); inc = 0; continue }
        i = index(line, "<!--"); if (!i) break
        rest = substr(line, i + 4); line = substr(line, 1, i - 1)
        j = index(rest, "-->")
        if (j) { line = line substr(rest, j + 3); continue }
        inc = 1; break
      }
      print line
    }' "$1"
}

bn_body="$(strip_comments "$BN")"
en_body="$(strip_comments "$EN")"

# Translatable keys only — translatable="false" ones live in the default file alone.
bn_keys=$(echo "$bn_body" | grep -oE '<string name="[^"]+"[^>]*>' \
  | grep -v 'translatable="false"' \
  | grep -oE 'name="[^"]+"' | cut -d'"' -f2 | sort)

bn_untranslatable=$(echo "$bn_body" | grep -oE '<string name="[^"]+"[^>]*translatable="false"' \
  | grep -oE 'name="[^"]+"' | cut -d'"' -f2 | sort)

en_keys=$(echo "$en_body" | grep -oE '<string name="[^"]+"' \
  | cut -d'"' -f2 | sort)

# 2. Bangla string with no English translation.
missing_in_en=$(comm -23 <(echo "$bn_keys") <(echo "$en_keys") || true)
if [ -n "$missing_in_en" ]; then
  echo "ERROR: these keys exist in Bangla but have no English translation:"
  echo "$missing_in_en" | sed 's/^/  - /'
  echo "  Add them to values-en/strings.xml."
  status=1
fi

# 3. English string with no Bangla original — Bangla is primary, so this is backwards.
missing_in_bn=$(comm -13 <(echo "$bn_keys") <(echo "$en_keys") || true)
if [ -n "$missing_in_bn" ]; then
  # A translatable="false" key showing up in English is its own mistake.
  untranslatable_in_en=$(comm -12 <(echo "$bn_untranslatable") <(echo "$missing_in_bn") || true)
  real_missing=$(comm -23 <(echo "$missing_in_bn") <(echo "$bn_untranslatable") || true)

  if [ -n "$real_missing" ]; then
    echo "ERROR: these keys exist in English but not in Bangla:"
    echo "$real_missing" | sed 's/^/  - /'
    echo "  Bangla is the primary language — write it in values/strings.xml first."
    status=1
  fi

  if [ -n "$untranslatable_in_en" ]; then
    echo "ERROR: these keys are marked translatable=\"false\" but appear in values-en/:"
    echo "$untranslatable_in_en" | sed 's/^/  - /'
    echo "  Remove them from values-en/strings.xml."
    status=1
  fi
fi

# 4. A Bangla key that exists but has no actual text in it.
empty_bn=$(echo "$bn_body" \
  | grep -oE '<string name="[^"]+"[^>]*>[[:space:]]*</string>|<string name="[^"]+"[^>]*/>' \
  | grep -oE 'name="[^"]+"' | cut -d'"' -f2 | sort || true)
if [ -n "$empty_bn" ]; then
  echo "ERROR: these Bangla strings are empty:"
  echo "$empty_bn" | sed 's/^/  - /'
  echo "  Bangla is what users see by default — it cannot be blank."
  status=1
fi

if [ "$status" -eq 0 ]; then
  bn_count=$(echo "$bn_keys" | grep -c . || true)
  echo "Localization check passed: $bn_count translatable keys, Bangla and English in step."
fi

exit "$status"
