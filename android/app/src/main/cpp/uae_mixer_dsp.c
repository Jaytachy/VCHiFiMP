/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * uae_mixer_dsp.c - mixer/headphone EQ rack.
 *
 * The Android mixer path writes interleaved stereo float PCM to AudioTrack;
 * mono sources are expanded by the sink, but the wrapper supports both channel
 * counts so the host test can exercise each. uae_dsp.c itself is reused
 * unchanged and remains stereo-only, so mono blocks are processed as
 * duplicated stereo in fixed-size chunks.
 */
#include "uae_mixer_dsp.h"

#include <stdlib.h>

#define MIX_CHUNK 1024

struct uae_mixer_dsp *uae_mixer_dsp_create(double rate)
{
    struct uae_mixer_dsp *m = calloc(1, sizeof(*m));
    if (!m) return NULL;

    if (uae_dsp_init(&m->dsp, rate) != 0) {
        free(m);
        return NULL;
    }
    m->rate = rate;
    m->scratch = malloc(sizeof(float) * 2u * MIX_CHUNK);
    if (!m->scratch) {
        uae_dsp_destroy(&m->dsp);
        free(m);
        return NULL;
    }
    return m;
}

void uae_mixer_dsp_destroy(struct uae_mixer_dsp *m)
{
    if (!m) return;
    uae_dsp_destroy(&m->dsp);
    free(m->scratch);
    free(m);
}

int uae_mixer_dsp_set_bands(struct uae_mixer_dsp *m,
                            const struct uae_dsp_band *bands, int nbands,
                            double preamp_db, int auto_headroom)
{
    if (!m) return -1;
    return uae_dsp_set_bands(&m->dsp, bands, nbands, preamp_db, auto_headroom);
}

void uae_mixer_dsp_set_enabled(struct uae_mixer_dsp *m, int on)
{
    if (!m) return;
    uae_dsp_set_enabled(&m->dsp, on);
}

void uae_mixer_dsp_request_reset(struct uae_mixer_dsp *m)
{
    if (!m) return;
    uae_dsp_request_reset(&m->dsp);
}

void uae_mixer_dsp_set_rate(struct uae_mixer_dsp *m, double rate)
{
    if (!m || !(rate >= 8000.0)) return;
    m->rate = rate;
    uae_dsp_prepare_rate(&m->dsp, rate);
}

int uae_mixer_dsp_process(struct uae_mixer_dsp *m, float *buf, int frames,
                          int channels)
{
    if (!m || !buf || frames <= 0 || (channels != 1 && channels != 2))
        return 0;

    /* Bypass: one atomic load, then leave the block alone. */
    if (!atomic_load_explicit(&m->dsp.enabled, memory_order_relaxed))
        return 0;

    struct uae_dsp_snapshot snap;
    if (!uae_dsp_read_snapshot(&m->dsp, &snap))
        return 0;

    if (channels == 2) {
        uae_dsp_process(&m->dsp, &snap, buf, frames);
    } else {
        int done = 0;
        while (done < frames) {
            int n = frames - done;
            if (n > MIX_CHUNK) n = MIX_CHUNK;

            for (int i = 0; i < n; i++) {
                float v = buf[done + i];
                m->scratch[i * 2] = v;
                m->scratch[i * 2 + 1] = v;
            }
            uae_dsp_process(&m->dsp, &snap, m->scratch, n);
            for (int i = 0; i < n; i++)
                buf[done + i] = m->scratch[i * 2];
            done += n;
        }
    }

    /* enabled drops when the fade-out reaches zero; the caller then stops
     * invoking process() and the bypass is literal again. */
    return atomic_load_explicit(&m->dsp.enabled, memory_order_relaxed) ? 1 : 0;
}
