/* SPDX-License-Identifier: GPL-3.0-or-later */
#ifndef UAE_DSP_H
#define UAE_DSP_H

#include <pthread.h>
#include <stdatomic.h>
#include <stdint.h>

#define UAE_DSP_MAX_BANDS 12
#define UAE_DSP_SLOTS     3
#define UAE_DSP_RAMP_FRAMES 480

enum {
    UAE_DSP_PEAK      = 0,
    UAE_DSP_LOWSHELF  = 1,
    UAE_DSP_HIGHSHELF = 2,
    UAE_DSP_HPF       = 3,
    UAE_DSP_LPF       = 4
};

struct uae_dsp_band {
    int type;
    double fc;
    double q;
    double gain_db;
    int enabled;
};

struct uae_dsp_biquad {
    double b0, b1, b2, a1, a2;   /* a0 normalized to 1 */
};

struct uae_dsp_snapshot {
    unsigned nbands;
    double preamp;               /* linear, auto headroom already applied */
    struct uae_dsp_biquad bq[UAE_DSP_MAX_BANDS];
    uint64_t tag;
    uint64_t check;              /* host-test torn-write checksum */
};

struct uae_dsp_slot {
    _Atomic unsigned seq;
    struct uae_dsp_snapshot data;
};

/*
 * Embedded in struct uae. All control-side fields (bands, rate, snapshots)
 * are touched under ctrl_lock by JNI threads only. The pump reads the active
 * snapshot through a per-slot seqlock and owns z1/z2 and the wet ramp.
 */
struct uae_dsp {
    pthread_mutex_t ctrl_lock;

    struct uae_dsp_band bands[UAE_DSP_MAX_BANDS];
    int nbands;
    double preamp_db;
    int auto_headroom;
    double rate;
    uint64_t generation;

    _Atomic int enabled;         /* 1 while the pump must call dsp_process */
    _Atomic int want;            /* user request; 0 during the fade-out */
    _Atomic int reset_req;
    _Atomic unsigned active_slot;
    struct uae_dsp_slot slots[UAE_DSP_SLOTS];

    /* pump thread only */
    double z1[2][UAE_DSP_MAX_BANDS];
    double z2[2][UAE_DSP_MAX_BANDS];
    double wet;
    int run_active;
};

int  uae_dsp_init(struct uae_dsp *d, double rate);
void uae_dsp_destroy(struct uae_dsp *d);

/* Replace the band set and republish coefficients for the current rate. */
int  uae_dsp_set_bands(struct uae_dsp *d, const struct uae_dsp_band *bands,
                       int nbands, double preamp_db, int auto_headroom);

/* New device rate: recompute coefficients and clear the DF-II state. */
void uae_dsp_prepare_rate(struct uae_dsp *d, double rate);

void uae_dsp_set_enabled(struct uae_dsp *d, int on);
void uae_dsp_request_reset(struct uae_dsp *d);

/* Seqlock-protected snapshot copy used by the URB pump. */
int  uae_dsp_read_snapshot(struct uae_dsp *d, struct uae_dsp_snapshot *out);

/* In-place, interleaved stereo float. Called from the pump only. */
void uae_dsp_process(struct uae_dsp *d, const struct uae_dsp_snapshot *snap,
                     float *buf, int frames);

/* Exposed for the host test and for auto-headroom evaluation. */
int    uae_dsp_compute_band(const struct uae_dsp_band *b, double rate,
                            struct uae_dsp_biquad *out);
double uae_dsp_biquad_mag_db(const struct uae_dsp_biquad *bq,
                             double rate, double freq);

/* Host seqlock stress test only; single writer, no control mutex. */
void uae_dsp_test_publish(struct uae_dsp *d, uint64_t tag);

#endif
