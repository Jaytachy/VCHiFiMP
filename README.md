# USB Direct

Music player that talks to a USB DAC through raw `usbfs` isochronous transfers,
bypassing ALSA/PipeWire on Linux and AudioFlinger/the Android mixer on Android.
Volume is applied in software (float), with TPDF dither to S16/S24.

## Layout

```
linux/     reference implementation + measurement tools (the fast debug loop)
android/   Kotlin/Compose app + JNI engine (same C core)
tests/     test material (piano excerpt at 96k/24)
```

## Hardware findings

### Jieli 3654:4a55 (the good one)
- UAC1, full speed, 4 interfaces.
- Playback interface 1:
  - alt 1: S16_LE, 2 ch, 44.1/48/96 kHz, EP 0x03 **ADAPTIVE**, maxpkt 384
  - alt 2: S24_3LE, 2 ch, 44.1/48/96 kHz, EP 0x03 **ADAPTIVE**, maxpkt 576
- Capture interface 2: mono S16 48 kHz, EP 0x83 async.
- HID interface 3.
- Measured clock: **±2 ppm at 96 kHz/24-bit, no wow** (independent HDA mic).
- Adaptive endpoint means the device follows the host rate, so no feedback
  endpoint and no rate-trim needed.

### AB13X 0020:0b21 (known bad)
- UAC1, 48 kHz / 16-bit only, EP 0x03 synchronous, maxpkt 384.
- Free-runs ~+1400 ppm and re-corrects every 5.0 s (dips to ~993-996 Hz).
- Cheap ASRC: piano sounds "pinched", upper partials sag. Do not trust it.

## Linux reference player

```
cd linux
gcc -O2 -D_GNU_SOURCE -o usbplay usbplay.c $(pkg-config --cflags --libs sndfile samplerate) -lm
./usbplay --device /dev/bus/usb/BBB/DDD --dump
./usbplay --device ... --rate 96000 --bits 24 --volume 0.5 file.wav
./usbplay --device ... --rate 44100 --bits 16 --tone --seconds 10
./usbplay --device ... --rate 44100 --bits 16 --out render.wav in96.wav   # digital render
```

`--vidpid VID:PID` finds the device automatically. Root (or a udev rule) is
needed to open the node and to detach `snd-usb-audio`.

Measured results:

| case | result |
|---|---|
| 96k/24, alt 2 | 0 URB errors, 1000.005 Hz |
| 44.1k/16, fractional 44/45-frame packets | 0 errors, no clicks |
| 96k -> 44.1k resample | level transparent, 1000.0000 Hz |
| software volume + TPDF dither | in path |

## Android app

`UsbAudioEngine` (JNI) needs the usbfs fd from
`UsbDeviceConnection.getFileDescriptor()`, then does everything itself.

### Known constraint: interface ownership

On Android the kernel `snd-usb-audio` driver is usually bound to USB audio
devices (that is how the framework's USB audio support works). `usbfs` cannot
claim an interface that a kernel driver owns, and detaching it needs
`CAP_SYS_ADMIN` (`USBDEVFS_DISCONNECT_CLAIM`). Consequences:

- **Rooted device / custom kernel without snd-usb-audio**: the raw path works.
- **Stock, driver bound**: `uae_open` fails with
  `interface N busy (snd-usb-audio bound; needs root)`.

The engine tries `DISCONNECT_CLAIM` first and reports that error if it cannot
proceed. This must be resolved before the app is usable on a stock phone; the
options are root, a kernel with `CONFIG_SND_USB_AUDIO` disabled, or a
different sink strategy.

### Still missing
- Vendored `libsamplerate` (currently linear interpolation in Kotlin).
- Background playback service / notification.
- Library + queue UI.
- UAC2 support.
