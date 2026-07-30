#!/usr/bin/env bash
#
# Catches Kotlin platform declaration clashes before the compiler does.
#
# Kotlin generates a JVM setter for every non-private `var`, including one
# marked `private set`. A function of the same name and signature in the same
# class is then a duplicate JVM method, and the build fails with:
#
#   Platform declaration clash: The following declarations have the same
#   JVM signature (setFoo(...)V)
#
# This project has walked into it three times -- setView, setBrushSoftness and
# setFogShape -- because "a property plus a function that sets it with a side
# effect" is a natural thing to write. The convention here is to name those
# functions update*/apply*/select* instead.
#
# Usage: tools/check-declaration-clashes.sh
# Exits non-zero if any clash is found.

set -uo pipefail
cd "$(dirname "$0")/.."

status=0

while IFS= read -r file; do
  # Non-private `var` declarations: those are the ones that get a JVM setter.
  # `private var` gets a plain field, so it cannot clash.
  while IFS= read -r prop; do
    [ -z "$prop" ] && continue
    capitalised="$(printf '%s' "${prop:0:1}" | tr '[:lower:]' '[:upper:]')${prop:1}"
    if grep -qE "fun +set${capitalised}\(" "$file"; then
      echo "CLASH  $file"
      echo "       var $prop  generates  set${capitalised}(...)  which collides with fun set${capitalised}()"
      status=1
    fi
  done < <(grep -oE '^[[:space:]]+(@Volatile[[:space:]]+)?var [a-zA-Z_][a-zA-Z0-9_]*' "$file" \
            | sed -E 's/.*var //')
done < <(find app/src -name '*.kt')

if [ "$status" -eq 0 ]; then
  echo "No platform declaration clashes found."
fi
exit "$status"
