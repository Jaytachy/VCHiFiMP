/*
 * Host test for the unity-volume packer and automatic dither gate.
 *
 *   gcc -O2 -std=c11 -I . uae_pack.c test/pack_test.c -lm
 */
#include <stdint.h>
#include <stdio.h>
#include <string.h>

#include "uae_pack.h"

static uint32_t rnd(uint32_t *s)
{
    return uae_pack_rand(s);
}

static int16_t read_s16(const unsigned char *p)
{
    int16_t v;
    memcpy(&v, p, 2);
    return v;
}

static int32_t read_s24(const unsigned char *p)
{
    int32_t v = (int32_t)p[0] | ((int32_t)p[1] << 8) | ((int32_t)p[2] << 16);
    if (v & 0x800000) v |= ~0xFFFFFF;
    return v;
}

int main(void)
{
    const int N = 100000;
    uint32_t seed = 0x12345678u;
    uint32_t rng;
    int16_t bad16 = 0;
    int32_t bad24 = 0;

    /* Unity volume, DSP off, no resampling: byte-exact input integers. */
    for (int i = 0; i < N; i++) {
        int32_t in16 = (int16_t)(rnd(&seed) & 0xFFFFu);
        float v16 = (float)in16 / 32768.0f;
        unsigned char p16[2];
        rng = seed;
        uae_pack_sample(v16, p16, 2, 0, &rng);
        if (read_s16(p16) != in16) bad16++;

        int32_t u = (int32_t)(rnd(&seed) & 0xFFFFFFu);
        int32_t in24 = (u & 0x800000) ? (u | ~0xFFFFFF) : u;
        float v24 = (float)in24 / 8388608.0f;
        unsigned char p24[3];
        rng = seed;
        uae_pack_sample(v24, p24, 3, 0, &rng);
        if (read_s24(p24) != in24) bad24++;
    }

    printf("%s  S16 unity exactness: %d mismatches / %d\n",
           bad16 ? "FAIL" : "PASS", bad16, N);
    printf("%s  S24 unity exactness: %d mismatches / %d\n",
           bad24 ? "FAIL" : "PASS", bad24, N);

    /* Dither still visibly changes samples when the path is modified. */
    int differs = 0;
    uint32_t r1 = 1, r2 = 2;
    for (int i = 0; i < 4096; i++) {
        int16_t in = (int16_t)(rnd(&seed) & 0x7FFF);
        float v = ((float)in / 32768.0f) * 0.5f;
        unsigned char a[2], b[2];
        uae_pack_sample(v, a, 2, 0, &r1);
        uae_pack_sample(v, b, 2, 1, &r2);
        if (memcmp(a, b, 2) != 0) differs++;
    }
    printf("%s  dither path changes samples at volume 0.5: %d/4096\n",
           differs > 0 ? "PASS" : "FAIL", differs);

    /* 24-in -> 16-out at unity must dither, unlike plain truncation. */
    int red_differs = 0;
    uint32_t r3 = 3, r4 = 4;
    for (int i = 0; i < 4096; i++) {
        int32_t u = (int32_t)(rnd(&seed) & 0xFFFFFFu);
        int32_t in24 = (u & 0x800000) ? (u | ~0xFFFFFF) : u;
        float v = (float)in24 / 8388608.0f;
        unsigned char a[2], b[2];
        uae_pack_sample(v, a, 2, 0, &r3);
        uae_pack_sample(v, b, 2, 1, &r4);
        if (memcmp(a, b, 2) != 0) red_differs++;
    }
    printf("%s  24>16 dither changes samples: %d/4096\n",
           red_differs > 0 ? "PASS" : "FAIL", red_differs);

    int g_ok =
        uae_dither_needed(1, 1.0f, 0, 0, 16, 16) == 0 &&
        uae_dither_needed(1, 0.5f, 0, 0, 16, 16) == 1 &&
        uae_dither_needed(1, 1.0f, 1, 0, 16, 16) == 1 &&
        uae_dither_needed(1, 1.0f, 0, 1, 16, 16) == 1 &&
        uae_dither_needed(1, 1.0f, 0, 0, 24, 16) == 1 &&
        uae_dither_needed(1, 1.0f, 0, 0, 16, 24) == 0 &&
        uae_dither_needed(1, 1.0f, 0, 0, 24, 24) == 0 &&
        uae_dither_needed(0, 1.0f, 0, 0, 24, 16) == 0;
    printf("%s  auto-dither gate incl. bit-depth reduction\n",
           g_ok ? "PASS" : "FAIL");

    int fail = bad16 || bad24 || differs == 0 || red_differs == 0 || !g_ok;
    printf("%s\n", fail ? "FAILED" : "ALL PASS");
    return fail ? 1 : 0;
}
