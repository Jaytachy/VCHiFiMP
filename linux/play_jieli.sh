#!/bin/sh
# User-level playback through raw usbfs. Requires usbsetup.sh release once.
#   ./play_jieli.sh dump
#   ./play_jieli.sh tone 10
#   ./play_jieli.sh piano 25
set -e
cd /tmp/opencode

VIDPID=3654:4a55
DEV=""
for d in /sys/bus/usb/devices/*; do
    [ -f "$d/idVendor" ] || continue
    [ "$(cat "$d/idVendor"):$(cat "$d/idProduct")" = "$VIDPID" ] || continue
    DEV=$(printf '/dev/bus/usb/%03d/%03d' "$(cat "$d/busnum")" "$(cat "$d/devnum")")
    break
done
[ -n "$DEV" ] || { echo "device $VIDPID not found"; exit 1; }
echo "device: $DEV"

MODE=${1:-dump}
SECS=${2:-10}

case "$MODE" in
  dump)
    ./usbplay --device "$DEV" --dump
    ;;
  tone)
    ./usbplay --device "$DEV" --rate 96000 --bits 24 --volume 0.5 \
              --tone --seconds "$SECS"
    ;;
  piano)
    ./usbplay --device "$DEV" --rate 96000 --bits 24 --volume 0.5 \
              --seconds "$SECS" piano96.wav
    ;;
  *)
    echo "usage: $0 dump|tone|piano [seconds]"; exit 2;;
esac
