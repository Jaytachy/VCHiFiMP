/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * uae_pack.c - exact integer packing with optional TPDF dither.
 *
 * Keeping this free of Android/USB headers lets the host test verify that a
 * unity-volume, DSP-off, native-rate stream writes the original integers
 * byte-exactly.
 */
#include "uae_pack.h"

#include <math.h>
#include <string.h>

uint32_t uae_pack_rand(uint32_t *state)
{
    uint32_t x = *state;
    x ^= x << 13;
    x ^= x >> 17;
    x ^= x << 5;
    *state = x;
    return x;
}

static float pack_frand(uint32_t *state)
{
    return (float)(uae_pack_rand(state) & 0x00ffffffu) / 16777216.0f;
}

int uae_dither_needed(int allow, float volume, int dsp_enabled, int resampled,
                      int src_bits, int out_bits)
{
    if (!allow) return 0;
    return volume != 1.0f || dsp_enabled || resampled ||
           (src_bits > 0 && out_bits > 0 && src_bits > out_bits);
}

void uae_pack_sample(float v, unsigned char *p, int subframe_bytes,
                     int dither, uint32_t *rng)
{
    if (subframe_bytes == 2) {
        double d = dither ? (double)v +
                           ((double)pack_frand(rng) - (double)pack_frand(rng)) / 32768.0
                          : (double)v;
        long x = lrint(d * 32768.0);
        if (x > 32767) x = 32767;
        if (x < -32768) x = -32768;
        int16_t s = (int16_t)x;
        memcpy(p, &s, 2);
    } else {
        double d = dither ? (double)v +
                           ((double)pack_frand(rng) - (double)pack_frand(rng)) / 8388608.0
                          : (double)v;
        long x = lrint(d * 8388608.0);
        if (x > 8388607) x = 8388607;
        if (x < -8388608) x = -8388608;
        p[0] = (unsigned char)(x & 0xff);
        p[1] = (unsigned char)((x >> 8) & 0xff);
        p[2] = (unsigned char)((x >> 16) & 0xff);
    }
}
