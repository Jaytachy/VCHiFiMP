/*
 * uae_dsp.c - 12-slot biquad rack (10 PEQ + HPF + LPF), RBJ cookbook.
 *
 * Control side (JNI threads, under ctrl_lock) compiles band parameters into
 * snapshots. The URB pump copies the active snapshot through a per-slot
 * seqlock; it never takes a lock and never allocates. DF-II transposed state
 * is owned by the pump. Bypass is a single atomic check in fill_urb: no DSP
 * code runs on the sample path while disabled.
 */
#include "uae_dsp.h"

#include <math.h>
#include <string.h>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

static double clampd(double v, double lo, double hi)
{
    if (v < lo) return lo;
    if (v > hi) return hi;
    return v;
}

static double db_to_lin(double db)
{
    return pow(10.0, db / 20.0);
}

/* RBJ cookbook, normalized so a0 == 1. Returns 0 when unrealizable. */
int uae_dsp_compute_band(const struct uae_dsp_band *b, double rate,
                         struct uae_dsp_biquad *out)
{
    if (!b || !out || !b->enabled || rate < 8000.0) return 0;
    if (b->type < UAE_DSP_PEAK || b->type > UAE_DSP_LPF) return 0;

    double fc = b->fc;
    if (!isfinite(fc) || fc <= 0.0 || fc >= rate * 0.45) return 0;

    double q = b->q;
    if (!isfinite(q) || q <= 0.0) return 0;
    q = clampd(q, 0.05, 20.0);

    double gdb = clampd(isfinite(b->gain_db) ? b->gain_db : 0.0, -24.0, 24.0);
    double A = pow(10.0, gdb / 40.0);
    double w0 = 2.0 * M_PI * fc / rate;
    double cw = cos(w0);
    double sw = sin(w0);
    double alpha = sw / (2.0 * q);
    double b0, b1, b2, a0, a1, a2;

    switch (b->type) {
    case UAE_DSP_PEAK:
        b0 = 1.0 + alpha * A;
        b1 = -2.0 * cw;
        b2 = 1.0 - alpha * A;
        a0 = 1.0 + alpha / A;
        a1 = -2.0 * cw;
        a2 = 1.0 - alpha / A;
        break;
    case UAE_DSP_LOWSHELF: {
        double sA = sqrt(A);
        b0 = A * ((A + 1.0) - (A - 1.0) * cw + 2.0 * sA * alpha);
        b1 = 2.0 * A * ((A - 1.0) - (A + 1.0) * cw);
        b2 = A * ((A + 1.0) - (A - 1.0) * cw - 2.0 * sA * alpha);
        a0 = (A + 1.0) + (A - 1.0) * cw + 2.0 * sA * alpha;
        a1 = -2.0 * ((A - 1.0) + (A + 1.0) * cw);
        a2 = (A + 1.0) + (A - 1.0) * cw - 2.0 * sA * alpha;
        break;
    }
    case UAE_DSP_HIGHSHELF: {
        double sA = sqrt(A);
        b0 = A * ((A + 1.0) + (A - 1.0) * cw + 2.0 * sA * alpha);
        b1 = -2.0 * A * ((A - 1.0) + (A + 1.0) * cw);
        b2 = A * ((A + 1.0) + (A - 1.0) * cw - 2.0 * sA * alpha);
        a0 = (A + 1.0) - (A - 1.0) * cw + 2.0 * sA * alpha;
        a1 = 2.0 * ((A - 1.0) - (A + 1.0) * cw);
        a2 = (A + 1.0) - (A - 1.0) * cw - 2.0 * sA * alpha;
        break;
    }
    case UAE_DSP_HPF:
        b0 = (1.0 + cw) * 0.5;
        b1 = -(1.0 + cw);
        b2 = (1.0 + cw) * 0.5;
        a0 = 1.0 + alpha;
        a1 = -2.0 * cw;
        a2 = 1.0 - alpha;
        break;
    default: /* UAE_DSP_LPF */
        b0 = (1.0 - cw) * 0.5;
        b1 = 1.0 - cw;
        b2 = (1.0 - cw) * 0.5;
        a0 = 1.0 + alpha;
        a1 = -2.0 * cw;
        a2 = 1.0 - alpha;
        break;
    }

    if (!isfinite(a0) || fabs(a0) < 1e-12) return 0;
    out->b0 = b0 / a0;
    out->b1 = b1 / a0;
    out->b2 = b2 / a0;
    out->a1 = a1 / a0;
    out->a2 = a2 / a0;
    return isfinite(out->b0) && isfinite(out->b1) && isfinite(out->b2) &&
           isfinite(out->a1) && isfinite(out->a2);
}

double uae_dsp_biquad_mag_db(const struct uae_dsp_biquad *q,
                             double rate, double freq)
{
    if (!q || rate <= 0.0) return 0.0;
    double w = 2.0 * M_PI * freq / rate;
    double c1 = cos(w), s1 = -sin(w);
    double c2 = cos(2.0 * w), s2 = -sin(2.0 * w);
    double nr = q->b0 + q->b1 * c1 + q->b2 * c2;
    double ni = q->b1 * s1 + q->b2 * s2;
    double dr = 1.0 + q->a1 * c1 + q->a2 * c2;
    double di = q->a1 * s1 + q->a2 * s2;
    double num = hypot(nr, ni);
    double den = hypot(dr, di);
    if (den < 1e-15) return 0.0;
    return 20.0 * log10(num / den);
}

/* Highest single-band boost on a 256-point log grid; sum-free by design. */
static double max_band_boost_db(const struct uae_dsp_biquad *bq, int n,
                                double rate)
{
    if (n <= 0 || rate <= 0.0) return 0.0;
    double fmin = 20.0;
    double fmax = rate * 0.45;
    if (fmax > 20000.0) fmax = 20000.0;
    if (fmax <= fmin * 1.001) return 0.0;

    double max_boost = 0.0;
    for (int b = 0; b < n; b++) {
        double band_max = -1e9;
        for (int k = 0; k < 256; k++) {
            double f = fmin * pow(fmax / fmin, (double)k / 255.0);
            double db = uae_dsp_biquad_mag_db(&bq[b], rate, f);
            if (db > band_max) band_max = db;
        }
        if (band_max > max_boost) max_boost = band_max;
    }
    return max_boost > 0.0 ? max_boost : 0.0;
}

static void publish_locked(struct uae_dsp *d)
{
    struct uae_dsp_snapshot snap;
    memset(&snap, 0, sizeof(snap));

    int n = 0;
    for (int i = 0; i < d->nbands && n < UAE_DSP_MAX_BANDS; i++) {
        struct uae_dsp_biquad bq;
        if (uae_dsp_compute_band(&d->bands[i], d->rate, &bq))
            snap.bq[n++] = bq;
    }
    snap.nbands = (unsigned)n;

    double boost = max_band_boost_db(snap.bq, n, d->rate);
    double eff_db = d->preamp_db;
    if (d->auto_headroom && boost > 0.0) eff_db -= boost;
    snap.preamp = db_to_lin(eff_db);
    snap.tag = ++d->generation;
    snap.check = 0;

    unsigned cur = atomic_load_explicit(&d->active_slot, memory_order_relaxed);
    unsigned next = (cur + 1u) % UAE_DSP_SLOTS;
    struct uae_dsp_slot *s = &d->slots[next];
    unsigned seq = atomic_load_explicit(&s->seq, memory_order_relaxed);
    if ((seq & 1u) == 0u) seq++;
    atomic_store_explicit(&s->seq, seq, memory_order_relaxed);
    atomic_thread_fence(memory_order_release);
    s->data = snap;
    atomic_store_explicit(&s->seq, seq + 1u, memory_order_release);
    atomic_store_explicit(&d->active_slot, next, memory_order_release);
}

int uae_dsp_init(struct uae_dsp *d, double rate)
{
    if (!d) return -1;
    memset(d, 0, sizeof(*d));
    pthread_mutex_init(&d->ctrl_lock, NULL);
    d->rate = rate;
    d->preamp_db = 0.0;
    d->auto_headroom = 0;
    d->nbands = 0;

    atomic_init(&d->enabled, 0);
    atomic_init(&d->want, 0);
    atomic_init(&d->reset_req, 0);
    atomic_init(&d->active_slot, 0u);
    for (int i = 0; i < UAE_DSP_SLOTS; i++) {
        atomic_init(&d->slots[i].seq, 0u);
        memset(&d->slots[i].data, 0, sizeof(d->slots[i].data));
    }
    d->slots[0].data.preamp = 1.0;
    d->wet = 0.0;
    d->run_active = 0;
    return 0;
}

void uae_dsp_destroy(struct uae_dsp *d)
{
    if (!d) return;
    pthread_mutex_destroy(&d->ctrl_lock);
}

int uae_dsp_set_bands(struct uae_dsp *d, const struct uae_dsp_band *bands,
                      int nbands, double preamp_db, int auto_headroom)
{
    if (!d) return -1;
    if (nbands < 0) nbands = 0;
    if (nbands > UAE_DSP_MAX_BANDS) nbands = UAE_DSP_MAX_BANDS;

    pthread_mutex_lock(&d->ctrl_lock);
    d->nbands = nbands;
    if (nbands > 0 && bands)
        memcpy(d->bands, bands, sizeof(struct uae_dsp_band) * (size_t)nbands);
    d->preamp_db = isfinite(preamp_db)
                 ? clampd(preamp_db, -48.0, 24.0) : 0.0;
    d->auto_headroom = auto_headroom ? 1 : 0;
    publish_locked(d);
    pthread_mutex_unlock(&d->ctrl_lock);
    return 0;
}

void uae_dsp_prepare_rate(struct uae_dsp *d, double rate)
{
    if (!d) return;
    pthread_mutex_lock(&d->ctrl_lock);
    d->rate = rate;
    publish_locked(d);
    pthread_mutex_unlock(&d->ctrl_lock);

    /* The pump is stopped around uae_switch_format, so this is exclusive. */
    memset(d->z1, 0, sizeof(d->z1));
    memset(d->z2, 0, sizeof(d->z2));
    d->wet = 0.0;
    d->run_active = 0;
    atomic_store_explicit(&d->reset_req, 0, memory_order_relaxed);
}

void uae_dsp_set_enabled(struct uae_dsp *d, int on)
{
    if (!d) return;
    if (on) {
        atomic_store_explicit(&d->want, 1, memory_order_release);
        atomic_store_explicit(&d->enabled, 1, memory_order_release);
    } else {
        /* Keep the gate open until the pump has ramped wet down to zero. */
        atomic_store_explicit(&d->want, 0, memory_order_release);
        if (!atomic_load_explicit(&d->enabled, memory_order_relaxed))
            atomic_store_explicit(&d->enabled, 0, memory_order_relaxed);
    }
}

void uae_dsp_request_reset(struct uae_dsp *d)
{
    if (d) atomic_store_explicit(&d->reset_req, 1, memory_order_release);
}

int uae_dsp_read_snapshot(struct uae_dsp *d, struct uae_dsp_snapshot *out)
{
    if (!d || !out) return 0;
    for (int tries = 0; tries < 100000; tries++) {
        unsigned idx = atomic_load_explicit(&d->active_slot, memory_order_acquire);
        if (idx >= UAE_DSP_SLOTS) idx = 0;
        struct uae_dsp_slot *s = &d->slots[idx];
        unsigned s1 = atomic_load_explicit(&s->seq, memory_order_acquire);
        if (s1 & 1u) continue;
        struct uae_dsp_snapshot tmp;
        memcpy(&tmp, &s->data, sizeof(tmp));
        atomic_thread_fence(memory_order_acquire);
        unsigned s2 = atomic_load_explicit(&s->seq, memory_order_relaxed);
        if (s1 == s2) {
            *out = tmp;
            return 1;
        }
    }
    return 0;
}

static void reset_state(struct uae_dsp *d)
{
    memset(d->z1, 0, sizeof(d->z1));
    memset(d->z2, 0, sizeof(d->z2));
}

static inline double biquad_step(const struct uae_dsp_biquad *q, double x,
                                 double *z1, double *z2)
{
    double y = q->b0 * x + *z1;
    *z1 = q->b1 * x - q->a1 * y + *z2;
    *z2 = q->b2 * x - q->a2 * y;
    return y;
}

static inline double soft_clip(double x)
{
    const double t = 0.98;
    double a = fabs(x);
    if (a <= t) return x;
    double knee = t + (1.0 - t) * tanh((a - t) / (1.0 - t));
    return copysign(knee, x);
}

void uae_dsp_process(struct uae_dsp *d, const struct uae_dsp_snapshot *snap,
                     float *buf, int frames)
{
    if (!d || !snap || !buf || frames <= 0) return;

    if (atomic_exchange_explicit(&d->reset_req, 0, memory_order_acquire))
        reset_state(d);

    int want = atomic_load_explicit(&d->want, memory_order_relaxed);
    if (!d->run_active) {
        if (!want) {
            atomic_store_explicit(&d->enabled, 0, memory_order_relaxed);
            return;
        }
        reset_state(d);
        d->run_active = 1;
        d->wet = 0.0;
    }

    const double step = 1.0 / (double)UAE_DSP_RAMP_FRAMES;
    const int n = (int)snap->nbands;
    const double preamp = snap->preamp;

    if (d->wet >= 1.0 && want) {
        /* steady state: no wet/dry ramp code on the sample path */
        for (int f = 0; f < frames; f++) {
            for (int c = 0; c < 2; c++) {
                double x = (double)buf[f * 2 + c] * preamp;
                for (int b = 0; b < n; b++)
                    x = biquad_step(&snap->bq[b], x, &d->z1[c][b], &d->z2[c][b]);
                buf[f * 2 + c] = (float)soft_clip(x);
            }
        }
        return;
    }

    for (int f = 0; f < frames; f++) {
        for (int c = 0; c < 2; c++) {
            double dry = (double)buf[f * 2 + c];
            double x = dry * preamp;
            for (int b = 0; b < n; b++)
                x = biquad_step(&snap->bq[b], x, &d->z1[c][b], &d->z2[c][b]);
            x = soft_clip(x);
            buf[f * 2 + c] = (float)(dry + (x - dry) * d->wet);
        }

        if (want) {
            if (d->wet < 1.0) {
                d->wet += step;
                if (d->wet > 1.0) d->wet = 1.0;
            }
        } else {
            d->wet -= step;
            if (d->wet <= 0.0) {
                d->wet = 0.0;
                d->run_active = 0;
                atomic_store_explicit(&d->enabled, 0, memory_order_relaxed);
                break;
            }
        }
    }
}

/* ------------------------------------------------------------------ test */

void uae_dsp_test_publish(struct uae_dsp *d, uint64_t tag)
{
    if (!d) return;
    struct uae_dsp_snapshot snap;
    memset(&snap, 0, sizeof(snap));
    snap.nbands = UAE_DSP_MAX_BANDS;
    snap.preamp = (double)(tag & 0xffffu);
    snap.tag = tag;

    uint64_t sum = (uint64_t)(tag & 0xffffu);
    for (int i = 0; i < UAE_DSP_MAX_BANDS; i++) {
        struct uae_dsp_biquad *q = &snap.bq[i];
        q->b0 = (double)((tag + (uint64_t)i) & 0xffffffu);
        q->b1 = (double)((tag + (uint64_t)i + 1u) & 0xffffffu);
        q->b2 = (double)((tag + (uint64_t)i + 2u) & 0xffffffu);
        q->a1 = (double)((tag + (uint64_t)i + 3u) & 0xffffffu);
        q->a2 = (double)((tag + (uint64_t)i + 4u) & 0xffffffu);
        sum += (uint64_t) q->b0 + (uint64_t) q->b1 + (uint64_t) q->b2 +
               (uint64_t) q->a1 + (uint64_t) q->a2;
    }
    snap.check = sum;

    unsigned cur = atomic_load_explicit(&d->active_slot, memory_order_relaxed);
    unsigned next = (cur + 1u) % UAE_DSP_SLOTS;
    struct uae_dsp_slot *s = &d->slots[next];
    unsigned seq = atomic_load_explicit(&s->seq, memory_order_relaxed);
    if ((seq & 1u) == 0u) seq++;
    atomic_store_explicit(&s->seq, seq, memory_order_relaxed);
    atomic_thread_fence(memory_order_release);
    s->data = snap;
    atomic_store_explicit(&s->seq, seq + 1u, memory_order_release);
    atomic_store_explicit(&d->active_slot, next, memory_order_release);
}
