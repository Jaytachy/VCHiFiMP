/*
 * Thin wrapper over libsamplerate (BSD-2-Clause) for the Android build.
 * Only used when the track's rate is not natively supported by the DAC.
 */
#include <stdlib.h>
#include <string.h>
#include <samplerate.h>

typedef struct {
    SRC_STATE *state;
    int channels;
    double ratio;
} rs_t;

rs_t *rs_new(int channels, double ratio)
{
    int err = 0;
    SRC_STATE *st = src_new(SRC_SINC_MEDIUM_QUALITY, channels, &err);
    if (!st) return NULL;
    rs_t *r = calloc(1, sizeof(*r));
    if (!r) { src_delete(st); return NULL; }
    r->state = st;
    r->channels = channels;
    r->ratio = ratio;
    return r;
}

void rs_delete(rs_t *r)
{
    if (!r) return;
    src_delete(r->state);
    free(r);
}

/* Returns frames produced, or -1 on error. */
int rs_process(rs_t *r, const float *in, int inFrames, float *out, int outMax)
{
    if (!r) return -1;
    SRC_DATA d;
    memset(&d, 0, sizeof(d));
    d.data_in = in;
    d.input_frames = inFrames;
    d.data_out = out;
    d.output_frames = outMax;
    d.src_ratio = r->ratio;
    d.end_of_input = 0;
    if (src_process(r->state, &d) != 0) return -1;
    return d.output_frames_gen;
}

int rs_flush(rs_t *r, float *out, int outMax)
{
    if (!r) return -1;
    SRC_DATA d;
    memset(&d, 0, sizeof(d));
    d.data_in = NULL;
    d.input_frames = 0;
    d.data_out = out;
    d.output_frames = outMax;
    d.src_ratio = r->ratio;
    d.end_of_input = 1;
    if (src_process(r->state, &d) != 0) return -1;
    return d.output_frames_gen;
}
