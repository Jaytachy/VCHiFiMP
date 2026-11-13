#!/bin/sh
# Usage:
#   sudo /tmp/opencode/usbsetup.sh release 3654:4a55   # take playback iface from snd-usb-audio
#   sudo /tmp/opencode/usbsetup.sh restore 3654:4a55   # give it back
#
# Only touches the selected USB device. Never card 0 / HDA / PipeWire graph.

MODE=${1:-release}
VIDPID=${2:-3654:4a55}
VID=${VIDPID%:*}
PID=${VIDPID#*:}

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

[ -n "$SYSFS" ] || { echo "device $VIDPID not found"; exit 1; }
echo "device: $DEV ($SYSFS)"

# streaming interfaces belonging to the device
IFS_LIST=""
for i in /sys/bus/usb/devices/"$SYSFS":1.*; do
    [ -d "$i" ] || continue
    IFS_LIST="$IFS_LIST $(basename "$i")"
done
echo "interfaces:$IFS_LIST"

if [ "$MODE" = "restore" ]; then
    echo -n "$SYSFS" > /sys/bus/usb/drivers_probe 2>/dev/null
    echo "re-probed snd-usb-audio"
    sleep 0.5
    cat /proc/asound/cards
    exit 0
fi

# unbind whichever interfaces snd-usb-audio actually holds
for i in /sys/bus/usb/devices/"$SYSFS":1.*; do
    [ -d "$i" ] || continue
    DRV=$(basename "$(readlink "$i/driver" 2>/dev/null)" 2>/dev/null)
    [ "$DRV" = "snd-usb-audio" ] || continue
    NAME=$(basename "$i")
    echo "unbinding $NAME from snd-usb-audio"
    echo -n "$NAME" > /sys/bus/usb/drivers/snd-usb-audio/unbind 2>/dev/null \
        || echo "  unbind failed (already unbound?)"
done

chmod 666 "$DEV" && echo "device node is now writable by your user"

echo "--- cards now ---"
cat /proc/asound/cards
echo "READY: $DEV"
