/* SPDX-License-Identifier: GPL-3.0-or-later */
#ifndef UAE_MIXER_DSP_H
#define UAE_MIXER_DSP_H

#include "uae_dsp.h"

/*
 * Mixer-owned DSP rack.
 *
 * This is a second uae_dsp instance that belongs to the AudioTrack sink, not
 * to the USB engine. The mixer writer calls process() on the interleaved float
 * block immediately before AudioTrack.write. Control calls may come from the
 * UI thread: uae_dsp publishes coefficients through its seqlock and keeps the
 * enabled/want flags atomic.
 *
 * Bypass is a single atomic check in process(): while the rack is disabled the
 * buffer is not read, written, or copied, so no DSP code runs on the sample
 * path.
 */
struct uae_mixer_dsp {
    struct uae_dsp dsp;
    double rate;
    float *scratch;             /* mono -> stereo expansion, 2 * chunk */
};

struct uae_mixer_dsp *uae_mixer_dsp_create(double rate);
void uae_mixer_dsp_destroy(struct uae_mixer_dsp *m);

/* Same parameter contract as uae_dsp_set_bands(). */
int  uae_mixer_dsp_set_bands(struct uae_mixer_dsp *m,
                             const struct uae_dsp_band *bands, int nbands,
                             double preamp_db, int auto_headroom);

/* Same 480-frame wet/dry ramp as the USB rack. */
void uae_mixer_dsp_set_enabled(struct uae_mixer_dsp *m, int on);

/* Clear DF-II history at the next process() call (seek/track change). */
void uae_mixer_dsp_request_reset(struct uae_mixer_dsp *m);

/*
 * New sink rate: recompute coefficients and clear history. Call this with the
 * mixer writer stopped (sink (re)start), as uae_dsp_prepare_rate() assumes
 * exclusive ownership of the filter state.
 */
void uae_mixer_dsp_set_rate(struct uae_mixer_dsp *m, double rate);

/*
 * In-place process of frames interleaved samples. channels is 1 or 2.
 * Returns 1 while the rack is engaged, including the fade-out ramp, and 0
 * when the buffer was left completely untouched.
 */
int  uae_mixer_dsp_process(struct uae_mixer_dsp *m, float *buf, int frames,
                           int channels);

#endif
