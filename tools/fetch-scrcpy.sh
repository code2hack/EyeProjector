#!/bin/sh
# Fetch the unmodified backend pinned by ShellMain; never replace an existing file.
set -eu
root=$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd -P)
target="$root/third_party/scrcpy-server-v5.0.1"
hash=764eb6f79811d5211fe9df341120882ba9994c7a61b897d7bf3fb662e53bc536
verify() { printf '%s  %s\n' "$hash" "$1" | sha256sum -c -; }
if [ -e "$target" ]; then verify "$target"; exit; fi
temp=$(mktemp "$root/third_party/.scrcpy.XXXXXX")
trap 'rm -f "$temp"' EXIT HUP INT TERM
curl --fail --location --silent --show-error --max-time 120 \
  https://github.com/Genymobile/scrcpy/releases/download/v5.0.1/scrcpy-server-v5.0.1 -o "$temp"
verify "$temp"
# Hard link publishes atomically and fails safely if another fetch won the race.
ln "$temp" "$target"
