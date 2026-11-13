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

## Status

Working on Linux. Android compiles, but see `README` notes on interface
ownership: a stock Android kernel keeps `snd-usb-audio` bound to the DAC, and
usbfs cannot steal an interface a kernel driver owns without root.

## Build

Linux reference player:

```bash
cd linux
gcc -O2 -D_GNU_SOURCE -o usbplay usbplay.c \
    $(pkg-config --cflags --libs sndfile samplerate) -lm
./usbplay --vidpid 3654:4a55 --rate 96000 --bits 24 --volume 0.5 track.flac
```

Android: open `android/` in Android Studio and build. Requires NDK and CMake.
