/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * Host-side mixer/headphone EQ tests. Not part of the Android build.
 *
 *   gcc -O2 -pthread -std=c11 -I cpp cpp/uae_mixer_dsp.c cpp/uae_dsp.c \
 *       cpp/test/mixer_dsp_test.c -lm
 *
 * Or through the host-test CMake project next to this file.
 */
#include <math.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "uae_mixer_dsp.h"

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

#define TEST_RATES 3

static const double g_rates[TEST_RATES] = { 44100.0, 48000.0, 96000.0 };
static int g_fail;

static void result(int ok, const char *name, const char *detail)
{
    printf("%s  %-46s %s\n", ok ? "PASS" : "FAIL", name, detail ? detail : "");
    if (!ok) g_fail++;
}

static void make_band(struct uae_dsp_band *b, int type, double fc, double q,
                      double gain_db)
{
    memset(b, 0, sizeof(*b));
    b->type = type;
    b->fc = fc;
    b->q = q;
    b->gain_db = gain_db;
    b->enabled = 1;
}

static void fill_tone(float *buf, int frames, int ch, double rate, double freq,
                      double amp)
{
    for (int i = 0; i < frames; i++) {
        float v = (float)(amp * sin(2.0 * M_PI * freq * (double)i / rate));
        for (int c = 0; c < ch; c++) buf[i * ch + c] = v;
    }
}

static void fill_dc(float *buf, int frames, int ch, float v)
{
    for (int i = 0; i < frames * ch; i++) buf[i] = v;
}

/* Run the 480-frame wet ramp to completion on silence. */
static void ramp_silence(struct uae_mixer_dsp *m)
{
    float zeros[1024 * 2];
    memset(zeros, 0, sizeof(zeros));
    uae_mixer_dsp_process(m, zeros, 1024, 2);
}

static int arm(struct uae_mixer_dsp *m, const struct uae_dsp_band *bands,
               int nbands, double preamp_db, int auto_headroom)
{
    if (uae_mixer_dsp_set_bands(m, bands, nbands, preamp_db, auto_headroom) != 0)
        return 0;
    uae_mixer_dsp_set_enabled(m, 1);
    ramp_silence(m);
    return 1;
}

/* ------------------------------------------------------------- unity ---- */

static void test_unity(double rate, int ch)
{
    struct uae_mixer_dsp *m = uae_mixer_dsp_create(rate);
    struct uae_dsp_band band;
    char name[96], detail[128];
    const int n = 4096;

    if (!m) { result(0, "unity create", "OOM"); return; }
    make_band(&band, UAE_DSP_PEAK, 1000.0, 0.707, 0.0);
    if (!arm(m, &band, 1, 0.0, 0)) {
        result(0, "unity setup", "no snapshot");
        uae_mixer_dsp_destroy(m);
        return;
    }

    float *buf = (float *)malloc(sizeof(float) * (size_t)n * (size_t)ch);
    if (!buf) { result(0, "unity alloc", "OOM"); uae_mixer_dsp_destroy(m); return; }
    fill_tone(buf, n, ch, rate, 997.0, 0.5);

    int active = uae_mixer_dsp_process(m, buf, n, ch);
    double maxerr = 0.0;
    for (int i = 0; i < n; i++) {
        double v = 0.5 * sin(2.0 * M_PI * 997.0 * (double)i / rate);
        for (int c = 0; c < ch; c++)
            maxerr = fmax(maxerr, fabs((double)buf[i * ch + c] - v));
    }

    snprintf(name, sizeof(name), "unity %s @ %.0f Hz",
             ch == 1 ? "mono" : "stereo", rate);
    snprintf(detail, sizeof(detail), "active=%d maxerr=%.3g", active, maxerr);
    result(active == 1 && maxerr <= 1e-6, name, detail);
    free(buf);
    uae_mixer_dsp_destroy(m);
}

static void test_disabled(void)
{
    struct uae_mixer_dsp *m = uae_mixer_dsp_create(48000.0);
    struct uae_dsp_band band;
    const int n = 2048;
    float buf[n * 2], copy[n * 2];

    if (!m) { result(0, "disabled create", "OOM"); return; }
    make_band(&band, UAE_DSP_PEAK, 1000.0, 0.707, 9.0);
    uae_mixer_dsp_set_bands(m, &band, 1, -6.0, 0);
    fill_tone(buf, n, 2, 48000.0, 440.0, 0.5);
    memcpy(copy, buf, sizeof(buf));

    int active = uae_mixer_dsp_process(m, buf, n, 2);
    result(active == 0 && memcmp(buf, copy, sizeof(buf)) == 0,
           "disabled leaves buffer untouched", "no DSP code on the path");
    uae_mixer_dsp_destroy(m);
}

/* ------------------------------------------------------------- reset ---- */

static void test_reset(void)
{
    struct uae_mixer_dsp *m1 = uae_mixer_dsp_create(48000.0);
    struct uae_mixer_dsp *m2 = uae_mixer_dsp_create(48000.0);
    struct uae_dsp_band band;
    const int dirty_n = 4096;
    const int impulse_n = 8192;

    if (!m1 || !m2) {
        result(0, "reset create", "OOM");
        uae_mixer_dsp_destroy(m1);
        uae_mixer_dsp_destroy(m2);
        return;
    }
    make_band(&band, UAE_DSP_PEAK, 1000.0, 1.0, 9.0);
    if (!arm(m1, &band, 1, 0.0, 0) || !arm(m2, &band, 1, 0.0, 0)) {
        result(0, "reset setup", "no snapshot");
        uae_mixer_dsp_destroy(m1);
        uae_mixer_dsp_destroy(m2);
        return;
    }

    float *dirty = (float *)malloc(sizeof(float) * 2 * dirty_n);
    float *a = (float *)calloc((size_t)impulse_n * 2, sizeof(float));
    float *b = (float *)calloc((size_t)impulse_n * 2, sizeof(float));
    if (!dirty || !a || !b) {
        result(0, "reset alloc", "OOM");
        free(dirty); free(a); free(b);
        uae_mixer_dsp_destroy(m1);
        uae_mixer_dsp_destroy(m2);
        return;
    }

    /* Put m1 into a different state, then reset before the impulse. */
    fill_tone(dirty, dirty_n, 2, 48000.0, 220.0, 0.9);
    uae_mixer_dsp_process(m1, dirty, dirty_n / 2, 2);
    uae_mixer_dsp_process(m1, dirty + dirty_n, dirty_n / 2, 2);
    uae_mixer_dsp_request_reset(m1);

    a[0] = a[1] = 0.25f;
    b[0] = b[1] = 0.25f;
    uae_mixer_dsp_process(m1, a, impulse_n, 2);
    uae_mixer_dsp_process(m2, b, impulse_n, 2);

    double maxerr = 0.0;
    for (int i = 0; i < impulse_n * 2; i++)
        maxerr = fmax(maxerr, fabs((double)a[i] - (double)b[i]));
    char detail[128];
    snprintf(detail, sizeof(detail), "impulse maxdiff=%.3g", maxerr);
    result(maxerr <= 1e-12, "reset equals fresh instance", detail);

    free(dirty); free(a); free(b);
    uae_mixer_dsp_destroy(m1);
    uae_mixer_dsp_destroy(m2);
}

/* -------------------------------------------------------- rate change ---- */

static void test_rate_change(void)
{
    struct uae_mixer_dsp *m = uae_mixer_dsp_create(44100.0);
    struct uae_mixer_dsp *fresh = uae_mixer_dsp_create(96000.0);
    struct uae_dsp_band band;
    const int n = 16384;

    if (!m || !fresh) {
        result(0, "rate create", "OOM");
        uae_mixer_dsp_destroy(m);
        uae_mixer_dsp_destroy(fresh);
        return;
    }
    make_band(&band, UAE_DSP_PEAK, 3000.0, 0.9, 7.0);
    if (!arm(m, &band, 1, -1.0, 1) || !arm(fresh, &band, 1, -1.0, 1)) {
        result(0, "rate setup", "no snapshot");
        uae_mixer_dsp_destroy(m);
        uae_mixer_dsp_destroy(fresh);
        return;
    }

    /* Re-target the live rack; prepare_rate recompiles and clears state. */
    uae_mixer_dsp_set_rate(m, 96000.0);
    ramp_silence(m);

    float *a = (float *)calloc((size_t)n * 2, sizeof(float));
    float *b = (float *)calloc((size_t)n * 2, sizeof(float));
    if (!a || !b) {
        result(0, "rate alloc", "OOM");
        free(a); free(b);
        uae_mixer_dsp_destroy(m);
        uae_mixer_dsp_destroy(fresh);
        return;
    }
    a[0] = a[1] = 0.25f;
    b[0] = b[1] = 0.25f;

    uae_mixer_dsp_process(m, a, n, 2);
    uae_mixer_dsp_process(fresh, b, n, 2);

    double maxerr = 0.0;
    for (int i = 0; i < n * 2; i++)
        maxerr = fmax(maxerr, fabs((double)a[i] - (double)b[i]));
    char detail[128];
    snprintf(detail, sizeof(detail), "44.1k->96k impulse maxdiff=%.3g", maxerr);
    result(maxerr <= 1e-12, "rate change matches fresh instance", detail);

    free(a); free(b);
    uae_mixer_dsp_destroy(m);
    uae_mixer_dsp_destroy(fresh);
}

/* -------------------------------------------------------------- ramp ---- */

static void test_enable_ramp(void)
{
    struct uae_mixer_dsp *m = uae_mixer_dsp_create(48000.0);
    struct uae_dsp_band band;

    if (!m) { result(0, "ramp create", "OOM"); return; }
    /* +12 dB low shelf: an abrupt enable would step ~0.6 on DC. */
    make_band(&band, UAE_DSP_LOWSHELF, 100.0, 0.707, 12.0);
    if (!arm(m, &band, 1, 0.0, 0)) {
        result(0, "ramp setup", "no snapshot");
        uae_mixer_dsp_destroy(m);
        return;
    }

    float dc[2048 * 2];
    fill_dc(dc, 2048, 2, 0.2f);
    uae_mixer_dsp_process(m, dc, 2048, 2);

    /* Fade out completely, then re-enable and measure the ramp. */
    uae_mixer_dsp_set_enabled(m, 0);
    int active = uae_mixer_dsp_process(m, dc, 2048, 2);
    active |= uae_mixer_dsp_process(m, dc, 2048, 2);

    float out[480 * 2];
    fill_dc(out, 480, 2, 0.2f);
    uae_mixer_dsp_set_enabled(m, 1);
    uae_mixer_dsp_process(m, out, 480, 2);

    double maxstep = fabs((double)out[0] - 0.2);
    for (int i = 1; i < 480; i++) {
        for (int c = 0; c < 2; c++) {
            double d = fabs((double)out[i * 2 + c] - (double)out[(i - 1) * 2 + c]);
            if (d > maxstep) maxstep = d;
        }
    }

    char detail[160];
    snprintf(detail, sizeof(detail), "fadeout_active=%d max step=%.5f", active,
             maxstep);
    result(active == 0 && maxstep < 0.02, "enable ramp has no step", detail);
    uae_mixer_dsp_destroy(m);
}

/* ------------------------------------------------------- block splitting */

static void test_multiblock(int ch)
{
    struct uae_mixer_dsp *one = uae_mixer_dsp_create(48000.0);
    struct uae_mixer_dsp *many = uae_mixer_dsp_create(48000.0);
    struct uae_dsp_band bands[3];
    const int n = 100000;

    if (!one || !many) {
        result(0, "multiblock create", "OOM");
        uae_mixer_dsp_destroy(one);
        uae_mixer_dsp_destroy(many);
        return;
    }
    make_band(&bands[0], UAE_DSP_LOWSHELF, 120.0, 0.707, 6.0);
    make_band(&bands[1], UAE_DSP_PEAK, 1000.0, 1.2, -4.0);
    make_band(&bands[2], UAE_DSP_HIGHSHELF, 8000.0, 0.707, 3.0);
    if (!arm(one, bands, 3, -1.0, 1) || !arm(many, bands, 3, -1.0, 1)) {
        result(0, "multiblock setup", "no snapshot");
        uae_mixer_dsp_destroy(one);
        uae_mixer_dsp_destroy(many);
        return;
    }

    float *a = (float *)malloc(sizeof(float) * (size_t)n * (size_t)ch);
    float *b = (float *)malloc(sizeof(float) * (size_t)n * (size_t)ch);
    if (!a || !b) {
        result(0, "multiblock alloc", "OOM");
        free(a); free(b);
        uae_mixer_dsp_destroy(one);
        uae_mixer_dsp_destroy(many);
        return;
    }
    for (int i = 0; i < n; i++) {
        double t = (double)i / 48000.0;
        float v = (float)(0.30 * sin(2.0 * M_PI * 220.0 * t) +
                          0.20 * sin(2.0 * M_PI * 997.0 * t) +
                          0.05 * sin(2.0 * M_PI * 7000.0 * t));
        for (int c = 0; c < ch; c++) a[i * ch + c] = v;
    }
    memcpy(b, a, sizeof(float) * (size_t)n * (size_t)ch);

    uae_mixer_dsp_process(one, a, n, ch);

    const int sizes[] = { 333, 1024, 2048, 777, 4801 };
    int pos = 0, k = 0;
    while (pos < n) {
        int take = sizes[k++ % 5];
        if (take > n - pos) take = n - pos;
        uae_mixer_dsp_process(many, b + (size_t)pos * ch, take, ch);
        pos += take;
    }

    int diffs = 0;
    double maxerr = 0.0;
    for (int i = 0; i < n * ch; i++) {
        double d = fabs((double)a[i] - (double)b[i]);
        if (b[i] != a[i]) diffs++;
        if (d > maxerr) maxerr = d;
    }

    char name[96], detail[128];
    snprintf(name, sizeof(name), "100k frames %s one-shot == blocks",
             ch == 1 ? "mono" : "stereo");
    snprintf(detail, sizeof(detail), "diffs=%d maxdiff=%.3g", diffs, maxerr);
    result(diffs == 0, name, detail);

    free(a); free(b);
    uae_mixer_dsp_destroy(one);
    uae_mixer_dsp_destroy(many);
}

int main(void)
{
    printf("uae_mixer_dsp host tests\n");

    for (int r = 0; r < TEST_RATES; r++) {
        test_unity(g_rates[r], 1);
        test_unity(g_rates[r], 2);
    }
    test_disabled();
    test_reset();
    test_rate_change();
    test_enable_ramp();
    test_multiblock(1);
    test_multiblock(2);

    printf("%s (%d failure%s)\n", g_fail ? "FAILED" : "ALL PASS",
           g_fail, g_fail == 1 ? "" : "s");
    return g_fail ? 1 : 0;
}
