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

Core:

- Reads WAV/FLAC/etc. and decodes to PCM (`libsndfile` on Linux,
  MediaCodec on Android).
- Parses the device's USB audio descriptors itself and picks the matching
  interface/alt setting (S16 or S24_3LE, 44.1/48/96 kHz). Output depth defaults
  to **Auto**, which follows each track's source: 16-bit files take alt 1,
  24-bit files take alt 2, with an in-place `uae_switch_format` when the depth
  changes between tracks.
- Streams with a 12-URB pipeline of 8 one-millisecond isochronous packets.
- Supports fractional packet sizes, so 44.1 kHz works as alternating
  44/45-frame packets instead of being resampled.
- Software volume + TPDF dither (switchable), applied in float before packing.
- Resamples only content the DAC cannot take natively, with
  `SRC_SINC_MEDIUM/BEST/FASTEST` selectable.
- True gapless continuation between same-format tracks: one decode thread, one
  ring, metadata flips on the audible sample boundary.
- UAC Feature Unit hardware volume, with float gain locked at unity when active.

Android app (`v1.0.0`):

- SAF library scan with JSON cache, tags, Songs/Albums/Artists, search, sort,
  favourites, queue sheet, shuffle and 3-state repeat.
- Album and artist detail screens, track action sheet (play next, enqueue,
  favourite, go to album).
- Floating mini-player and full-screen Now Playing with dynamic album-art
  colours, swipe-to-dismiss and a play/pause cover scale.
- Foreground `PlaybackService` with MediaSession, lock screen and media keys.
- Output tab with live pipeline telemetry (URBs, underruns, jitter, ring fill),
  FFT spectrum, level meter and DAC descriptor tree.
- Settings hub: output depth, resampler quality, dither, gapless, DAC
  re-claim on replug, sleep timer, theme, dynamic colours, short-track filter.
- USB detach keeps the playing track and pauses on the audible frame; the DAC
  is re-claimed automatically when it comes back.

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

`v1.0.1` — Android app and Linux reference player both working; no root
required (see the claim sequence). Release builds are minified with R8 and
shrink resources (5.6 MB APK versus 20 MB debug).

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

1. for every interface whose `bInterfaceClass == 0x01` (audio), issue
   `USBDEVFS_IOCTL` + `USBDEVFS_DISCONNECT` (claim nothing). Non-audio
   interfaces — HID remotes, vendor functions — are left alone.
2. `USBDEVFS_SETCONFIGURATION` — now succeeds, and resets all alt settings
   (`EBUSY` is tolerated: the switch is best-effort once the audio interfaces
   are free)
3. claim the AudioControl interface and the streaming interface at alt 0
4. `USBDEVFS_SETINTERFACE` to the chosen alt setting
5. `SET_CUR` sampling frequency on the endpoint

Teardown never calls `SETCONFIGURATION` again: `usb_set_configuration()`
re-registers every interface and invalidates the usbfs fd (`EBADF`), and the
kernel refuses it while any interface is claimed. Instead the engine releases
the interfaces it holds, resets the streaming alt to 0, then issues
`USBDEVFS_IOCTL` + `USBDEVFS_CONNECT` for **every** interface in the
configuration, so `snd-usb-audio`, the HID remote and the capture function all
rebind without a replug.

`USBDEVFS_DISCONNECT` via `USBDEVFS_IOCTL` needs no `CAP_SYS_ADMIN`, unlike
`USBDEVFS_DISCONNECT_CLAIM`. Android's SELinux policy grants apps
`usb_device:chr_file { read write getattr ioctl }` with no `allowxperm`
narrowing, so this is permitted for ordinary apps.

Credit where due: this sequence is documented in
[ostrichbat2k/libusbdac](https://github.com/ostrichbat2k/libusbdac) (GPL-3.0)
and [unom-io/usbfs-iso](https://github.com/unom-io/usbfs-iso) (Apache-2.0).
The implementation here is independent; no code was copied.

The sweep is audio-class-only, and the `CONNECT` teardown re-requests driver
matching for every interface, so nothing stays orphaned after a session.

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

- **Release signing.** `android/app/build.gradle.kts` reads
  `android/keystore.properties` (gitignored) and falls back to the debug key
  when it is absent, so the current v1.0.0 APK is debug-signed. Add the
  properties file and rebuild to produce a proper release signature.
- **Mixer mode depends on the kernel driver.** Phone/speaker playback always
  works, but routing the mixer path through the DAC needs `snd-usb-audio`
  bound; if it is not, audio goes to the speaker until the DAC is replugged.
  The exclusive/bit-perfect path is unaffected.
- **Auto depth depends on metadata.** When neither the library scan nor
  `MediaExtractor` reports a bit depth, the track falls back to 24-bit.
- **24-bit → 16-bit alt switching in place** is implemented (gapless breaks
  into `uae_switch_format`), verified in logs; a full on-device A/B of the
  audible switch is still pending a DAC session.

## Next

- Per-track alt-setting selection (16-bit only when the source is 16-bit).
- Proper release keystore in CI, signed `v1.x` artifacts.
- Scroll/performance profiling with Perfetto.
- Widget and Android Auto surface.
