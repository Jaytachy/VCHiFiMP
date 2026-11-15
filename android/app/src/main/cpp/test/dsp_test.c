/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * Host-side DSP rack tests. Not part of the Android build.
 *
 *   gcc -O2 -pthread -std=c11 -I cpp cpp/uae_dsp.c cpp/test/dsp_test.c -lm
 */
#include <math.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "uae_dsp.h"

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

#define IMPULSE_N 16384
#define TEST_RATE_44 44100.0
#define TEST_RATE_96 96000.0

static int g_fail;

static void result(int ok, const char *name, const char *detail)
{
    printf("%s  %-42s %s\n", ok ? "PASS" : "FAIL", name, detail ? detail : "");
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

static int arm(struct uae_dsp *d, double rate, const struct uae_dsp_band *bands,
               int nbands, double preamp_db, int auto_headroom,
               struct uae_dsp_snapshot *snap)
{
    uae_dsp_init(d, rate);
    uae_dsp_set_bands(d, bands, nbands, preamp_db, auto_headroom);
    uae_dsp_set_enabled(d, 1);
    if (!uae_dsp_read_snapshot(d, snap)) return 0;

    /* Run the 480-frame wet ramp to completion on silence. */
    float zeros[1024 * 2];
    memset(zeros, 0, sizeof(zeros));
    uae_dsp_process(d, snap, zeros, 1024);
    return 1;
}

static double dft_bin_db(const double *x, int n, double rate, double freq)
{
    double w = 2.0 * M_PI * freq / rate;
    double re = 0.0, im = 0.0;
    for (int i = 0; i < n; i++) {
        re += x[i] * cos(w * i);
        im -= x[i] * sin(w * i);
    }
    double mag = hypot(re, im);
    return mag > 0.0 ? 20.0 * log10(mag) : -1000.0;
}

static void test_impulse_magnitude(void)
{
    const double rates[2] = { TEST_RATE_44, TEST_RATE_96 };
    const int bins[3] = { IMPULSE_N / 128, IMPULSE_N / 16, IMPULSE_N / 4 };
    struct uae_dsp_band band;
    make_band(&band, UAE_DSP_PEAK, 1000.0, 0.707, 6.0);

    for (int r = 0; r < 2; r++) {
        struct uae_dsp d;
        struct uae_dsp_snapshot snap;
        if (!arm(&d, rates[r], &band, 1, 0.0, 0, &snap)) {
            result(0, "impulse setup", "no snapshot");
            uae_dsp_destroy(&d);
            continue;
        }

        float buf[IMPULSE_N * 2];
        memset(buf, 0, sizeof(buf));
        buf[0] = 0.1f;
        buf[1] = 0.1f;
        uae_dsp_process(&d, &snap, buf, IMPULSE_N);

        double ir[IMPULSE_N];
        for (int i = 0; i < IMPULSE_N; i++) ir[i] = buf[i * 2] / 0.1;

        char name[96];
        for (int k = 0; k < 3; k++) {
            double freq = (double)bins[k] * rates[r] / IMPULSE_N;
            double got = dft_bin_db(ir, IMPULSE_N, rates[r], freq);
            double want = uae_dsp_biquad_mag_db(&snap.bq[0], rates[r], freq);
            double delta = fabs(got - want);
            snprintf(name, sizeof(name), "impulse mag %.0f Hz @ %.0f Hz rate",
                     freq, rates[r]);
            char detail[128];
            snprintf(detail, sizeof(detail),
                     "got=%.4f dB analytic=%.4f dB delta=%.4f",
                     got, want, delta);
            result(delta <= 0.01, name, detail);
        }
        uae_dsp_destroy(&d);
    }
}

static void test_preamp(void)
{
    struct uae_dsp d;
    struct uae_dsp_snapshot snap;
    if (!arm(&d, 48000.0, NULL, 0, -6.0, 0, &snap)) {
        result(0, "preamp setup", "no snapshot");
        return;
    }

    float buf[1024 * 2];
    for (int i = 0; i < 1024 * 2; i++) buf[i] = 1.0f;
    uae_dsp_process(&d, &snap, buf, 1024);

    double want = pow(10.0, -6.0 / 20.0);
    double worst = 0.0;
    for (int i = 0; i < 1024 * 2; i++)
        worst = fmax(worst, fabs((double)buf[i] - want));

    char detail[128];
    snprintf(detail, sizeof(detail), "out=%.9f expected=%.9f maxerr=%.3g",
             (double)buf[0], want, worst);
    result(worst < 1e-6, "preamp -6 dB exact 0.501187", detail);
    uae_dsp_destroy(&d);
}

static void test_hpf(void)
{
    struct uae_dsp d;
    struct uae_dsp_snapshot snap;
    struct uae_dsp_band band;
    make_band(&band, UAE_DSP_HPF, 20.0, 0.707, 0.0);
    if (!arm(&d, 48000.0, &band, 1, 0.0, 0, &snap)) {
        result(0, "HPF setup", "no snapshot");
        return;
    }

    const int n = 48000 * 5;
    float *buf = (float *)malloc(sizeof(float) * 2 * (size_t)n);
    if (!buf) { result(0, "HPF alloc", "OOM"); uae_dsp_destroy(&d); return; }
    const double amp = 0.5;
    for (int i = 0; i < n; i++) {
        float v = (float)(amp * sin(2.0 * M_PI * 5.0 * i / 48000.0));
        buf[i * 2] = v;
        buf[i * 2 + 1] = v;
    }
    uae_dsp_process(&d, &snap, buf, n);

    int start = n - 48000;
    double sum = 0.0;
    for (int i = start; i < n; i++) {
        double v = 0.5 * ((double)buf[i * 2] + (double)buf[i * 2 + 1]);
        sum += v * v;
    }
    double out_rms = sqrt(sum / 48000.0);
    double in_rms = amp / sqrt(2.0);
    double atten = 20.0 * log10(out_rms / in_rms);

    char detail[128];
    snprintf(detail, sizeof(detail), "5 Hz RMS %.6f -> %.6f = %.2f dB",
             in_rms, out_rms, atten);
    result(atten < -20.0, "HPF 20 Hz at 48k attenuates 5 Hz >20 dB", detail);
    free(buf);
    uae_dsp_destroy(&d);
}

static void test_auto_headroom(void)
{
    struct uae_dsp d;
    struct uae_dsp_snapshot snap;
    struct uae_dsp_band band;
    make_band(&band, UAE_DSP_PEAK, 1000.0, 0.707, 8.0);
    if (!arm(&d, 44100.0, &band, 1, 0.0, 1, &snap)) {
        result(0, "headroom setup", "no snapshot");
        return;
    }

    double grid_min = 20.0;
    double grid_max = fmin(44100.0 * 0.45, 20000.0);
    double pre_db = 20.0 * log10(snap.preamp);
    double max_db = -1000.0;
    for (int k = 0; k < 256; k++) {
        double f = grid_min * pow(grid_max / grid_min, (double)k / 255.0);
        double db = pre_db + uae_dsp_biquad_mag_db(&snap.bq[0], 44100.0, f);
        if (db > max_db) max_db = db;
    }

    char detail[128];
    snprintf(detail, sizeof(detail), "preamp=%.3f dB max chain=%.4f dB",
             pre_db, max_db);
    result(max_db <= 0.01, "auto headroom +8 dB boost stays <= 0 dB", detail);
    uae_dsp_destroy(&d);
}

/* ------------------------------------------------------------- seqlock */

struct stress {
    struct uae_dsp *d;
    atomic_int stop;
    atomic_ulong reads;
    atomic_ulong fails;
};

static uint64_t snapshot_sum(const struct uae_dsp_snapshot *s)
{
    uint64_t sum = (uint64_t)s->preamp;
    for (int i = 0; i < UAE_DSP_MAX_BANDS; i++) {
        const struct uae_dsp_biquad *q = &s->bq[i];
        sum += (uint64_t)q->b0 + (uint64_t)q->b1 + (uint64_t)q->b2 +
               (uint64_t)q->a1 + (uint64_t)q->a2;
    }
    return sum;
}

static void *stress_reader(void *arg)
{
    struct stress *st = arg;
    while (!atomic_load_explicit(&st->stop, memory_order_relaxed)) {
        struct uae_dsp_snapshot s;
        if (!uae_dsp_read_snapshot(st->d, &s)) {
            atomic_fetch_add(&st->fails, 1);
            continue;
        }
        if (s.nbands != UAE_DSP_MAX_BANDS || snapshot_sum(&s) != s.check)
            atomic_fetch_add(&st->fails, 1);
        atomic_fetch_add(&st->reads, 1);
    }
    return NULL;
}

static void test_seqlock(void)
{
    struct uae_dsp d;
    uae_dsp_init(&d, 48000.0);

    struct stress st;
    memset(&st, 0, sizeof(st));
    st.d = &d;
    atomic_init(&st.stop, 0);
    atomic_init(&st.reads, 0);
    atomic_init(&st.fails, 0);

    pthread_t th;
    pthread_create(&th, NULL, stress_reader, &st);
    for (uint64_t i = 0; i < 1000000u; i++)
        uae_dsp_test_publish(&d, i + 1u);
    atomic_store_explicit(&st.stop, 1, memory_order_relaxed);
    pthread_join(th, NULL);

    char detail[160];
    snprintf(detail, sizeof(detail), "reads=%lu torn/failed=%lu",
             (unsigned long)atomic_load(&st.reads),
             (unsigned long)atomic_load(&st.fails));
    result(atomic_load(&st.reads) > 0 && atomic_load(&st.fails) == 0,
           "seqlock 1M publishes, no torn snapshot", detail);
    uae_dsp_destroy(&d);
}

int main(void)
{
    printf("uae_dsp host tests\n");
    test_impulse_magnitude();
    test_preamp();
    test_hpf();
    test_auto_headroom();
    test_seqlock();
    printf("%s (%d failure%s)\n", g_fail ? "FAILED" : "ALL PASS",
           g_fail, g_fail == 1 ? "" : "s");
    return g_fail ? 1 : 0;
}
