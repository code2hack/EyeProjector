#!/bin/sh
# Synthetic media only; identical timing in the Phone host and fixture app.
set -eu
root=$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd -P)
tempdir=$(mktemp -d)
trap 'rm -rf "$tempdir"' EXIT HUP INT TERM
ffmpeg -nostdin -n -hide_banner -loglevel warning -filter_threads 1 \
  -f lavfi -i "color=c=black:s=480x640:r=30:d=12,drawbox=x=0:y=0:w=iw:h=ih:color=white:t=fill:enable='lt(mod(t,1),0.1)',drawtext=text='EyeProjector AV %{n}':fontcolor=0x44ff88:fontsize=24:x=20:y=300" \
  -f lavfi -i "aevalsrc=exprs='0.1*sin(2*PI*1000*t)*lt(mod(t,1),0.1)':s=48000:d=12" \
  -map 0:v:0 -map 1:a:0 -c:v libx264 -threads 2 -preset fast -crf 18 \
  -pix_fmt yuv420p -c:a aac -b:a 96k -ar 48000 -ac 1 -t 12 \
  -movflags +faststart "$tempdir/owned_flash_beep.mp4"
for module in directhost hostprobe; do
  cp "$tempdir/owned_flash_beep.mp4" "$root/$module/src/main/res/raw/owned_flash_beep.mp4"
done
