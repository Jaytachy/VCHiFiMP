#ifndef USB_AUDIO_ENGINE_H

#include <stdint.h>
#define USB_AUDIO_ENGINE_H

struct uae;

/* fd is a usbfs descriptor (UsbDeviceConnection.getFileDescriptor()).
 * rate: 44100 / 48000 / 96000. bits: 16 or 24 (picks the alt setting). */
struct uae *uae_open(int fd, int rate, int bits);
int  uae_ok(struct uae *e);
const char *uae_error(struct uae *e);

/* interleaved float32, device channel count. Blocking, returns frames taken. */
int  uae_write(struct uae *e, const float *src, int frames);

void uae_set_volume(struct uae *e, float v);   /* 0.0 .. 1.0 */
void uae_set_ppm(struct uae *e, float ppm);     /* free-running clock trim */
void uae_set_eos(struct uae *e);                /* producer reached end of stream */
uint64_t uae_frames_played(struct uae *e);  /* frames the device consumed */
uint64_t uae_buffered_frames(struct uae *e); /* decoded but not yet heard */
int  uae_switch_format(struct uae *e, int rate, int bits);  /* 0 = ok */

/* diagnostics */
const char *uae_summary(struct uae *e);
int  uae_get_telemetry(struct uae *e, long long *out, int max);
int  uae_hw_volume_info(struct uae *e, int out[4]);   /* min,max,res,cur in 1/256 dB */
int  uae_hw_volume_set(struct uae *e, int raw);
void uae_close(struct uae *e);

#endif
