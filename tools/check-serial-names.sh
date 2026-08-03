#!/usr/bin/env bash
#
# Fails if two messages in the display protocol claim the same @SerialName.
#
# Worth a script because of how this fails. The discriminator value is what the
# JavaScript receiver switches on, so a duplicate is a genuine ambiguity -- but
# kotlinx.serialization does not report it at the declaration. It throws while
# *building* the serializer for the whole sealed hierarchy, so every test that
# touches any message fails at once with an IllegalStateException pointing at
# whichever line happened to call encode() first. Nothing points at the
# duplicate.
#
# This happened for real: a Ping tool was added while `ping` was already the
# WebSocket keepalive.
set -euo pipefail

cd "$(dirname "$0")/.."
FILE=app/src/main/java/com/rpgmaps/tabletop/display/protocol/DisplayMessage.kt

if [ ! -f "$FILE" ]; then
  echo "cannot find $FILE" >&2
  exit 1
fi

# FogShape's members carry @SerialName too, but they live in their own enum
# namespace and cannot collide with a message tag, so only take the ones
# directly attached to a @Serializable class or object in the hierarchy.
duplicates=$(
  grep -B1 -E '^(data class|data object|class|object) ' "$FILE" \
    | grep -oE '@SerialName\("[^"]+"\)' \
    | sed -E 's/@SerialName\("(.*)"\)/\1/' \
    | sort | uniq -d
)

if [ -n "$duplicates" ]; then
  echo "Duplicate @SerialName values in the display protocol:" >&2
  while IFS= read -r tag; do
    echo "  \"$tag\" is claimed by:" >&2
    grep -n -A1 "@SerialName(\"$tag\")" "$FILE" \
      | grep -E '(data class|data object|class|object) ' \
      | sed 's/^/    /' >&2
  done <<< "$duplicates"
  echo >&2
  echo "Two subclasses cannot share a discriminator: the receiver switches on it," >&2
  echo "and kotlinx.serialization refuses to build the hierarchy's serializer." >&2
  exit 1
fi

echo "No duplicate @SerialName values in the display protocol."
