#!/bin/sh
# Usage:
#   sudo /tmp/opencode/run_dongle_test.sh dump 3654:4a55
#   sudo /tmp/opencode/run_dongle_test.sh test 3654:4a55
# Only touches the selected USB device.

MODE=${1:-test}
VIDPID=${2:-0020:0b21}
VID=${VIDPID%:*}
PID=${VIDPID#*:}
BIN=/tmp/opencode/usbaudio_test

DEV=""
SYSFS=""
for d in /sys/bus/usb/devices/*; do
    [ -f "$d/idVendor" ] || continue
    [ "$(cat "$d/idVendor")" = "$VID" ] || continue
    [ "$(cat "$d/idProduct")" = "$PID" ] || continue
    BUS=$(printf '%03d' "$(cat "$d/busnum")")
    DEVN=$(printf '%03d' "$(cat "$d/devnum")")
    DEV="/dev/bus/usb/$BUS/$DEVN"
    SYSFS=$(basename "$d")
    break
done

echo "device: $DEV ($SYSFS) $VIDPID"
[ -n "$DEV" ] && [ -e "$DEV" ] || { echo "device not found"; exit 1; }

[ -n "$SYSFS" ] && echo -n "$SYSFS" > /sys/bus/usb/drivers_probe 2>/dev/null
sleep 0.3

echo "=== descriptors ==="
"$BIN" --dump --device "$DEV"

[ "$MODE" = "dump" ] && exit 0

echo
echo "=== baseline 48 frames/frame, 12 s ==="
"$BIN" --device "$DEV" --seconds 12 --ppm 0

sleep 1
echo
echo "=== +1400 ppm, 12 s ==="
"$BIN" --device "$DEV" --seconds 12 --ppm 1400

[ -n "$SYSFS" ] && echo -n "$SYSFS" > /sys/bus/usb/drivers_probe 2>/dev/null
