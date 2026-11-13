# VCHiFiMP

A hi-fi music player that drives a USB DAC **directly**, skipping the operating
system's audio stack.

On Android, every sound you play normally goes through AudioFlinger, the vendor
HAL and a mixer that resamples everything to one rate and applies volume in
software modes you don't control. On Linux the same thing happens with
PipeWire or PulseAudio. That is fine for notifications, but it is a bad way to
listen to music: unwanted resampling, extra latency, and volume that is not
yours to set.

VCHiFiMP takes the other road. It opens the USB device itself, selects the
UAC1 alt setting for the exact format it wants, and streams PCM as
isochronous USB transfers, one packet per millisecond. No ALSA, no PipeWire,
no AudioFlinger, no mixer. Volume is applied by us, in floating point, with
TPDF dither when the signal is packed down to 16- or 24-bit.

The result is the DAC receiving exactly the samples we decoded, at exactly the
rate it asked for.

## What it does today

- Reads WAV/FLAC/etc. and decodes to PCM (`libsndfile` on Linux,
  MediaCodec on Android).
- Parses the device's USB audio descriptors itself and picks the matching
  interface/alt setting (S16 or S24_3LE, 44.1/48/96 kHz).
- Streams with a 12-URB pipeline of 8 one-millisecond isochronous packets.
- Supports fractional packet sizes, so 44.1 kHz works as alternating
  44/45-frame packets instead of being resampled.
- Software volume + TPDF dither, applied in float before packing.
- Resamples content the DAC cannot take natively.

## Layout

```
linux/     reference player and measurement tools - the fast debug loop
android/   Kotlin + Compose app with the same C engine behind JNI
tests/     test material
```

## Measured, not assumed

Two real DACs were characterised through raw usbfs:

| device | finding |
|---|---|
| Jieli `3654:4a55` | adaptive endpoint, 44.1/48/96 kHz, S16 + S24_3LE. Measured ±2 ppm at 96k/24, no wow. Good. |
| AB13X `0020:0b21` | 48k/16 only, free-runs ~+1400 ppm and re-corrects every 5 s. Sounds broken on piano. Avoid. |

`libusbdac` also used a real AB13X to validate its UAC1 parser (their fixture
is 8000/48000 Hz, EP 0x01, maxpkt 192, bInterval 4 — a different firmware to
ours). They report no audio-quality observations; bit-perfect delivery cannot
fix this chip, because its wobble happens after the samples arrive, inside its
own clocking.

## Status

Working on Linux (measured, see below). Android builds and runs, and does not
need root — see the claim sequence.

## Verified on hardware

Samsung Galaxy A54 5G (SM-A546E/DS), Android 17 / One UI 9, kernel 5.15.197,
SELinux enforcing, **unrooted, bootloader locked, Knox intact**.

- `snd-usb-audio` binds all three audio interfaces of the Jieli as soon as it
  is plugged in (confirmed in sysfs).
- `AudioManager.getSupportedMixerAttributes()` reports `MIXER_BEHAVIOR_DEFAULT`
  only — Samsung does **not** expose bit-perfect mixing, so that shortcut is
  unavailable. Raw usbfs is the only path.
- The disconnect-sweep sequence below works with ordinary USB permission: after
  opening the device and running it, `/sys/bus/usb/drivers/snd-usb-audio/`
  contains no interface links at all, and the engine streams 48 kHz / 24-bit
  isochronous URBs with no errors.

## Android: taking the interface from the kernel

A stock Android kernel keeps `snd-usb-audio` bound to the DAC as soon as it is
plugged in, because that is how the framework's USB audio support works. usbfs
cannot claim an interface a kernel driver owns.

The obvious fix, `claimInterface(force=true)`, does not work. Android's native
implementation issues `USBDEVFS_DISCONNECT` and then immediately claims the
interface for the caller. The kernel's `SETCONFIGURATION` handler refuses to
switch configuration while *anyone* holds *any* interface — including the
caller — so the switch always fails.

What works instead, with no privileges beyond normal USB permission:

1. for every interface in the current configuration, issue
   `USBDEVFS_IOCTL` + `USBDEVFS_DISCONNECT` (claim nothing)
2. `USBDEVFS_SETCONFIGURATION` — now succeeds, and resets all alt settings
3. claim the AudioControl interface and the streaming interface at alt 0
4. `USBDEVFS_SETINTERFACE` to the chosen alt setting
5. `SET_CUR` sampling frequency on the endpoint

`USBDEVFS_DISCONNECT` via `USBDEVFS_IOCTL` needs no `CAP_SYS_ADMIN`, unlike
`USBDEVFS_DISCONNECT_CLAIM`. Android's SELinux policy grants apps
`usb_device:chr_file { read write getattr ioctl }` with no `allowxperm`
narrowing, so this is permitted for ordinary apps.

Credit where due: this sequence is documented in
[ostrichbat2k/libusbdac](https://github.com/ostrichbat2k/libusbdac) (GPL-3.0)
and [unom-io/usbfs-iso](https://github.com/unom-io/usbfs-iso) (Apache-2.0).
The implementation here is independent; no code was copied.

Note: the sweep evicts *every* interface, including HID (inline remotes), and
those are never re-bound by the app. Release the device when done.

## Build

Linux reference player:

```bash
cd linux
gcc -O2 -D_GNU_SOURCE -o usbplay usbplay.c \
    $(pkg-config --cflags --libs sndfile samplerate) -lm
./usbplay --vidpid 3654:4a55 --rate 96000 --bits 24 --volume 0.5 track.flac
```

Android: open `android/` in Android Studio and build. Requires NDK and CMake.

## Known issues

- **Mixer mode is silent.** Root cause confirmed on device: after a raw session
  the app re-issues `SETCONFIGURATION` to hand the device back, but that
  sometimes fails with `EBADF` because the usbfs fd is invalidated by the
  reconfiguration. `snd-usb-audio` then stays unbound and there is no ALSA card
  for `AudioTrack` to route to. Symptom: mixer mode opens its sink, position
  stays at 0:00, no sound. Workaround: replug the DAC. Proper fix: detect the
  unbound state and refuse mixer playback with a clear message, or rebind
  another way.
- Pause resumes from the position at the moment of the tap, which can be a few
  hundred ms behind the audio actually heard (buffer in flight).
- Cached library keeps URIs for files deleted outside the app; playing one
  fails silently.

## Next

- Library/Now Playing UI overhaul (album art, better hierarchy).
- Glitch-free rate switching between tracks of different rates.
- Vendor `libsamplerate` for 88.2/192 kHz sources.
- Release signing.
