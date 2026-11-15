#ifndef UAE_PACK_H
#define UAE_PACK_H

#include <stdint.h>

/*
 * Decide once per URB whether TPDF dither is needed. Samples are only
 * modified when software volume is not unity, the DSP rack is active, or
 * libsamplerate is active. The user toggle remains a master allow.
 */
int uae_dither_needed(int allow, float volume, int dsp_enabled, int resampled,
                      int src_bits, int out_bits);

/* Quantize one float sample into S16 or packed S24_3LE; advances the RNG. */
void uae_pack_sample(float v, unsigned char *p, int subframe_bytes,
                     int dither, uint32_t *rng);

/* Exposed for the host test. */
uint32_t uae_pack_rand(uint32_t *state);

#endif
