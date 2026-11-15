/*
 * usb_audio_engine.c - raw usbfs isochronous audio output (UAC1).
 *
 * The Android app hands over the usbfs fd from UsbDeviceConnection.
 * Everything here is plain usbfs: descriptor parse, interface claim,
 * alt setting selection, sample-rate control, and an isochronous URB pump.
 * No ALSA, no AudioFlinger, no mixer.
 *
 * PCM arrives as interleaved float32; software volume and TPDF dither are
 * applied in the float domain right before S16/S24 packing.
 */
#include <errno.h>
#include <fcntl.h>
#include <math.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include <sys/ioctl.h>
#include <linux/usbdevice_fs.h>
#include <linux/usb/ch9.h>

#include "usb_audio_engine.h"
#include "uae_dsp.h"
#include "uae_pack.h"

#ifdef __ANDROID__
#include <android/log.h>
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "VCProbe", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "VCProbe", __VA_ARGS__)
#else
#define LOGI(...) do {} while (0)
#define LOGE(...) do {} while (0)
#endif

#ifndef USBDEVFS_DISCONNECT_CLAIM
#define USBDEVFS_DISCONNECT_CLAIM _IOWR('U', 0x17, struct usbdevfs_disconnect_claim)
#define USBDEVFS_DISCONNECT_CLAIM_IF_DRIVER 0x01
struct usbdevfs_disconnect_claim {
    unsigned int interface;
    unsigned int flags;
    char driver[256];
};
#endif
#ifndef USBDEVFS_URB_TYPE_ISO
#define USBDEVFS_URB_TYPE_ISO 1
#endif
#ifndef USBDEVFS_REAPURBNDELAY
#define USBDEVFS_REAPURBNDELAY _IOW('U', 13, void *)
#endif
#ifndef USBDEVFS_DISCARDURB
#define USBDEVFS_DISCARDURB _IO('U', 11)
#endif

#define NURB          12
#define PKTS_PER_URB  8
#define MAX_ALTS      24
#define MAX_RATES     8
#define MAX_FRAMES    1024              /* per 1 ms packet, generous */
#define RING_FRAMES   (1u << 17)        /* ~1.4 s at 96 kHz stereo */

struct uae_alt {
    int iface, altset;
    int channels, subframe, bitres;
    int nrates;
    unsigned int rates[MAX_RATES];
    int ep, maxpkt, epattr;
};

struct urb_ctx {
    struct usbdevfs_urb urb;
    struct usbdevfs_iso_packet_desc pkt[PKTS_PER_URB];
    unsigned char *buf;
    int submitted;
};

struct uae {
    int fd, iface, alt, ep, maxpkt, bpf, channels, rate;

    pthread_mutex_t lock;
    pthread_cond_t can_read, can_write;

    float *ring;
    size_t head, tail, filled;

    float volume;
    int dither;             /* user master allow for TPDF dither */
    volatile int resampled; /* producer is using libsamplerate */
    double ppm;
    uint64_t rendered;      /* frames packed since open */
    volatile uint64_t frames_done;  /* frames in URBs the kernel has completed */
    float gain;             /* fade in/out state */
    int ending;             /* source ran dry: fade out and stay silent */
    volatile int eos;       /* producer says: no more samples coming */
    unsigned cfgval;
    int ctrl_iface;
    int ifaces[16];
    int nifaces;
    struct uae_alt alts[MAX_ALTS];
    int nalts;

    /* UAC Feature Unit (hardware volume) */
    int fu_id, ac_iface;
    int hw_vol_supported;
    int hw_vol_min, hw_vol_max, hw_vol_res, hw_vol_cur;

    /* telemetry */
    volatile unsigned long urbs_reaped;
    volatile unsigned long urb_errors;
    volatile unsigned long underruns;
    volatile int primed;             /* buffer has filled at least once */
    uint64_t frames_packed;
    uint64_t last_reap_ns;
    uint64_t interval_min_ns, interval_max_ns, interval_sum_ns;
    unsigned long interval_count;
    char summary[768];

    volatile int running;
    volatile int failed;
    int started;
    pthread_t thread;
    char errmsg[160];

    /* 12-slot biquad rack; calloc-free init happens in uae_open. */
    struct uae_dsp dsp;
};

static uint64_t clock_gettime_ns(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000000000ull + (uint64_t)ts.tv_nsec;
}

static uint32_t rng_state = 0x2545f491u;

static void set_err(struct uae *e, const char *fmt, ...)
{
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(e->errmsg, sizeof(e->errmsg), fmt, ap);
    va_end(ap);
}

const char *uae_error(struct uae *e)
{
    return e ? e->errmsg : "no engine";
}

/* ----------------------------------------------------------- descriptors */

static int ctrl(int fd, uint8_t reqtype, uint8_t req, uint16_t val,
                uint16_t idx, uint16_t len, void *data)
{
    struct usbdevfs_ctrltransfer c;
    memset(&c, 0, sizeof(c));
    c.bRequestType = reqtype;
    c.bRequest = req;
    c.wValue = val;
    c.wIndex = idx;
    c.wLength = len;
    c.timeout = 1500;
    c.data = data;
    return ioctl(fd, USBDEVFS_CONTROL, &c);
}

static int parse_config(const unsigned char *buf, int total,
                        struct uae_alt *alts, int maxalts)
{
    int n = 0, cur = -1;
    for (int i = 0; i + 1 < total; ) {
        int len = buf[i], type = buf[i + 1];
        if (len < 2 || i + len > total) break;

        if (type == USB_DT_INTERFACE) {
            cur = -1;
            if (buf[i + 5] == 0x01 && buf[i + 6] == 0x02 && n < maxalts) {
                struct uae_alt *a = &alts[n];
                memset(a, 0, sizeof(*a));
                a->iface  = buf[i + 2];
                a->altset = buf[i + 3];
                cur = n++;
            }
        } else if (type == USB_DT_CS_INTERFACE && cur >= 0) {
            if (buf[i + 2] == 0x02 && len >= 8) {
                struct uae_alt *a = &alts[cur];
                if (buf[i + 3] != 1) {
                    cur = -1;                     /* only PCM */
                } else {
                    a->channels = buf[i + 4];
                    a->subframe = buf[i + 5];
                    a->bitres   = buf[i + 6];
                    int nf      = buf[i + 7];
                    a->nrates   = 0;
                    for (int k = 0; k < nf && k < MAX_RATES; k++) {
                        int o = i + 8 + 3 * k;
                        if (o + 2 >= i + len) break;
                        a->rates[a->nrates++] =
                            buf[o] | (buf[o + 1] << 8) | (buf[o + 2] << 16);
                    }
                }
            }
        } else if (type == USB_DT_ENDPOINT && cur >= 0) {
            struct uae_alt *a = &alts[cur];
            a->ep     = buf[i + 2];
            a->epattr = buf[i + 3];
            a->maxpkt = (buf[i + 4] | ((buf[i + 5] & 0x07) << 8))
                      * (1 + ((buf[i + 5] >> 3) & 0x03));
        }
        i += len;
    }
    return n;
}

/* Detach the kernel audio driver from every audio-class interface. HID and
 * vendor interfaces are left alone; they have no business being evicted. */
static int sweep_audio(int fd, const unsigned char *cfg, int total)
{
    int n = 0;
    for (int i = 0; i + 1 < total; ) {
        int len = cfg[i], type = cfg[i + 1];
        if (len < 2 || i + len > total) break;
        if (type == USB_DT_INTERFACE && cfg[i + 5] == 0x01) {
            struct usbdevfs_ioctl ctl;
            memset(&ctl, 0, sizeof(ctl));
            ctl.ifno = cfg[i + 2];
            ctl.ioctl_code = USBDEVFS_DISCONNECT;
            ctl.data = NULL;
            if (ioctl(fd, USBDEVFS_IOCTL, &ctl) == 0) n++;
        }
        i += len;
    }
    return n;
}

/* UAC1 AudioControl: find Feature Units and their master-channel volume bit.
 * A device may expose several (one per terminal); take the first that offers
 * volume rather than assuming the first one does. */
static void parse_feature_unit(const unsigned char *cfg, int total, struct uae *e)
{
    int ac = -1;
    for (int i = 0; i + 1 < total; ) {
        int len = cfg[i], type = cfg[i + 1];
        if (len < 2 || i + len > total) break;
        if (type == USB_DT_INTERFACE && cfg[i + 5] == 0x01 && cfg[i + 6] == 0x01) {
            ac = cfg[i + 2];
            break;
        }
        i += len;
    }
    if (ac < 0) return;
    e->ac_iface = ac;

    int in_ac = 0;
    for (int i = 0; i + 1 < total; ) {
        int len = cfg[i], type = cfg[i + 1];
        if (len < 2 || i + len > total) break;

        if (type == USB_DT_INTERFACE) {
            int cls = cfg[i + 5], sub = cfg[i + 6];
            in_ac = (cls == 0x01 && sub == 0x01);
        } else if (in_ac && type == USB_DT_CS_INTERFACE &&
                   cfg[i + 2] == 0x06 && len >= 7) {
            int unit = cfg[i + 3];
            int src  = cfg[i + 4];
            int csize = cfg[i + 5];
            int master = cfg[i + 6];
            if (csize > 1 && len >= 8) master |= cfg[i + 7] << 8;
            LOGI("uae: feature unit %d (src %d) controls=0x%04x size=%d",
                 unit, src, master, csize);
            if (!e->hw_vol_supported && (master & 0x02)) {
                e->fu_id = unit;
                e->hw_vol_supported = 1;
            }
        }
        i += len;
    }
}

static int uac_vol_get(struct uae *e, uint8_t req, int *out)
{
    unsigned char b[2] = {0, 0};
    int r = ctrl(e->fd, 0xA1, req, 0x0200, (uint16_t)((e->fu_id << 8) | e->ac_iface), 2, b);
    if (r < 2) return -1;
    *out = (int16_t)(b[0] | (b[1] << 8));
    return 0;
}

static int uac_vol_set(struct uae *e, int raw)
{
    unsigned char b[2] = { (unsigned char)(raw & 0xff), (unsigned char)((raw >> 8) & 0xff) };
    int r = ctrl(e->fd, 0x21, 0x01, 0x0200, (uint16_t)((e->fu_id << 8) | e->ac_iface), 2, b);
    return r < 0 ? -1 : 0;
}

int uae_hw_volume_info(struct uae *e, int out[4])
{
    if (!e || !e->hw_vol_supported) return 0;
    out[0] = e->hw_vol_min; out[1] = e->hw_vol_max;
    out[2] = e->hw_vol_res; out[3] = e->hw_vol_cur;
    return 1;
}

int uae_hw_volume_set(struct uae *e, int raw)
{
    if (!e || !e->hw_vol_supported) return -1;
    if (raw < e->hw_vol_min) raw = e->hw_vol_min;
    if (raw > e->hw_vol_max) raw = e->hw_vol_max;
    if (uac_vol_set(e, raw) < 0) return -1;
    e->hw_vol_cur = raw;
    return 0;
}

static int alt_has_rate(const struct uae_alt *a, unsigned rate)
{
    for (int k = 0; k < a->nrates; k++)
        if (a->rates[k] == rate) return 1;
    return 0;
}

/* ------------------------------------------------------------ ring buffer */

static size_t ring_write(struct uae *e, const float *src, size_t frames)
{
    size_t space = RING_FRAMES - e->filled;
    size_t n = frames < space ? frames : space;
    size_t ch = (size_t)e->channels;
    for (size_t i = 0; i < n * ch; i++) {
        size_t slot = (e->head * ch + i) % (RING_FRAMES * ch);
        e->ring[slot] = src[i];
    }
    e->head = (e->head + n) % RING_FRAMES;
    e->filled += n;
    if (!e->primed && e->filled > RING_FRAMES / 2) e->primed = 1;
    return n;
}

static size_t ring_read(struct uae *e, float *dst, size_t frames)
{
    size_t n = frames < e->filled ? frames : e->filled;
    size_t ch = (size_t)e->channels;
    for (size_t i = 0; i < n * ch; i++) {
        size_t slot = (e->tail * ch + i) % (RING_FRAMES * ch);
        dst[i] = e->ring[slot];
    }
    e->tail = (e->tail + n) % RING_FRAMES;
    e->filled -= n;
    return n;
}

/* --------------------------------------------------------- packing */

static void pack_frames(struct uae *e, unsigned char *dst, const float *src,
                        int n, int dither)
{
    const int ss = e->bpf / e->channels;   /* subframe bytes */
    const float fade_len = (float)(e->rate / 50);   /* 20 ms */
    const float step = 1.0f / fade_len;
    for (int f = 0; f < n; f++) {
        /* 20 ms fade in on open and fade out when the source ends: an abrupt
         * jump to/from full scale is a click, and USB DACs do not hide it. */
        if (!e->ending) {
            float g = (float)e->rendered / fade_len;
            e->gain = g < 1.0f ? g : 1.0f;
        } else {
            e->gain -= step;
            if (e->gain < 0.0f) e->gain = 0.0f;
        }
        const float gain = e->gain;
        e->rendered++;
        for (int c = 0; c < e->channels; c++) {
            float v = src[f * e->channels + c] * e->volume * gain;
            unsigned char *p = dst + (f * e->channels + c) * ss;
            uae_pack_sample(v, p, ss, dither, &rng_state);
        }
    }
}

/* Fill one URB with PKTS_PER_URB milliseconds of audio. */
static void fill_urb(struct uae *e, struct urb_ctx *c, double *acc, int *prev,
                     int max_frames, double fpm, int block)
{
    float tmp[MAX_FRAMES * 2];
    int off = 0;

    /* One coefficient snapshot per URB. While disabled this is a single
     * atomic load; no DSP code runs on the sample path. */
    struct uae_dsp_snapshot dsp_snap;
    int dsp_enabled =
        atomic_load_explicit(&e->dsp.enabled, memory_order_relaxed) != 0;
    int dsp_on = e->channels == 2 && dsp_enabled;
    if (dsp_on && !uae_dsp_read_snapshot(&e->dsp, &dsp_snap))
        dsp_on = 0;

    /* One decision per URB: dither is only useful when a previous stage
     * actually changed the sample (volume, DSP or resampling). */
    int dither_urb = uae_dither_needed(
        e->dither, e->volume, dsp_enabled, e->resampled);

    for (int k = 0; k < PKTS_PER_URB; k++) {
        *acc += fpm;
        int cur = (int)floor(*acc);
        int n = cur - *prev;
        *prev = cur;
        if (n < 1) n = 1;
        if (n > max_frames) n = max_frames;

        size_t got;
        if (block) {
            pthread_mutex_lock(&e->lock);
            while (e->running && e->filled < (size_t)n) {
                struct timespec ts;
                clock_gettime(CLOCK_MONOTONIC, &ts);
                ts.tv_nsec += 20 * 1000000L;
                if (ts.tv_nsec >= 1000000000L) {
                    ts.tv_sec++;
                    ts.tv_nsec -= 1000000000L;
                }
                if (pthread_cond_timedwait(&e->can_read, &e->lock, &ts) != 0)
                    break;   /* underrun: pad with silence */
            }
            got = ring_read(e, tmp, (size_t)n);
            pthread_cond_signal(&e->can_write);
            pthread_mutex_unlock(&e->lock);
        } else {
            pthread_mutex_lock(&e->lock);
            got = ring_read(e, tmp, (size_t)n);
            pthread_mutex_unlock(&e->lock);
        }

        e->frames_packed += (uint64_t)n;
        if (got < (size_t)n) {
            /* Only count a real starvation: at startup the ring is empty by
             * definition, and after EOS silence is expected. */
            if (!e->eos && e->primed) e->underruns++;
            memset(tmp + got * e->channels, 0,
                   sizeof(float) * (size_t)(n - got) * e->channels);
            /* Only fade out once the producer has declared end-of-stream.
             * Inferring it from an empty ring faded playback to silence at
             * startup, because the pump primes URBs before any data exists. */
            if (e->eos) e->ending = 1;
        }

        if (dsp_on)
            uae_dsp_process(&e->dsp, &dsp_snap, tmp, n);
        pack_frames(e, c->buf + off, tmp, n, dither_urb);
        c->pkt[k].length = (unsigned int)(n * e->bpf);
        off += n * e->bpf;
    }
    c->urb.buffer_length = off;
    c->urb.status = 0;
    c->urb.actual_length = 0;
}

static void *pump(void *arg)
{
    struct uae *e = arg;
    const int max_frames = e->maxpkt / e->bpf;
    const double fpm = (double)e->rate * (1.0 + e->ppm / 1e6) / 1000.0;
    double acc = 0.0;
    int prev = 0;

    struct urb_ctx ctx[NURB];
    memset(ctx, 0, sizeof(ctx));

    for (int i = 0; i < NURB; i++) {
        ctx[i].buf = malloc((size_t)PKTS_PER_URB * e->maxpkt);
        if (!ctx[i].buf) {
            set_err(e, "out of memory");
            e->failed = 1;
            e->running = 0;
            goto cleanup;
        }
        ctx[i].urb.type = USBDEVFS_URB_TYPE_ISO;
        ctx[i].urb.endpoint = e->ep;
        ctx[i].urb.flags = USBDEVFS_URB_ISO_ASAP;
        ctx[i].urb.number_of_packets = PKTS_PER_URB;
        ctx[i].urb.buffer = ctx[i].buf;
        ctx[i].urb.usercontext = &ctx[i];
    }

    for (int i = 0; i < NURB; i++) {
        fill_urb(e, &ctx[i], &acc, &prev, max_frames, fpm, 0);
        if (ioctl(e->fd, USBDEVFS_SUBMITURB, &ctx[i].urb) < 0) {
            LOGE("uae: SUBMITURB prime %d failed: %s (len=%u)",
                 i, strerror(errno), ctx[i].urb.buffer_length);
            set_err(e, "SUBMITURB: %s", strerror(errno));
            e->failed = 1;
            e->running = 0;
            goto cleanup;
        }
        ctx[i].submitted = 1;
    }

    int reaped = 0;
    while (e->running) {
        struct usbdevfs_urb *u = NULL;
        if (ioctl(e->fd, USBDEVFS_REAPURBNDELAY, &u) < 0) {
            if (errno == EAGAIN) { usleep(300); continue; }
            if (errno == EINTR) continue;
            set_err(e, "REAPURB: %s", strerror(errno));
            break;
        }
        struct urb_ctx *c = (struct urb_ctx *)u->usercontext;
        c->submitted = 0;
        // A completed URB means the host controller has handed those frames to
        // the device: this is the only honest "played" counter we have.
        e->frames_done += u->buffer_length / (unsigned)e->bpf;
        e->urbs_reaped++;
        if (u->status) e->urb_errors++;
        {
            uint64_t now = (uint64_t)clock_gettime_ns();
            if (e->last_reap_ns) {
                uint64_t dt = now - e->last_reap_ns;
                if (e->interval_count == 0 || dt < e->interval_min_ns) e->interval_min_ns = dt;
                if (dt > e->interval_max_ns) e->interval_max_ns = dt;
                e->interval_sum_ns += dt;
                e->interval_count++;
            }
            e->last_reap_ns = now;
        }
        if ((++reaped % 500) == 0)
            LOGI("uae: reaped=%d status=%d len=%u", reaped, u->status, u->actual_length);
        if (u->status && reaped < 600)
            LOGE("uae: urb status=%d packets0=%u/%u", u->status,
                 c->pkt[0].actual_length, c->pkt[0].length);

        fill_urb(e, c, &acc, &prev, max_frames, fpm, 1);

        if (ioctl(e->fd, USBDEVFS_SUBMITURB, &c->urb) < 0) {
            set_err(e, "SUBMITURB(resubmit): %s", strerror(errno));
            break;
        }
        c->submitted = 1;
    }

cleanup:
    for (int i = 0; i < NURB; i++) {
        if (ctx[i].submitted)
            ioctl(e->fd, USBDEVFS_DISCARDURB, &ctx[i].urb);
    }
    /* reap whatever was in flight so the kernel stops touching our buffers */
    for (int spins = 0; spins < 200; spins++) {
        struct usbdevfs_urb *u = NULL;
        if (ioctl(e->fd, USBDEVFS_REAPURBNDELAY, &u) < 0) {
            if (errno == EAGAIN) { usleep(500); continue; }
            break;
        }
    }
    for (int i = 0; i < NURB; i++) free(ctx[i].buf);
    return NULL;
}

/* --------------------------------------------------------------- public API */

struct uae *uae_open(int fd, int rate, int bits)
{
    struct uae *e = calloc(1, sizeof(*e));
    if (!e) return NULL;
    e->fd = fd;
    e->dither = 1;
    e->ctrl_iface = -1;
    e->nifaces = 0;
    pthread_mutex_init(&e->lock, NULL);
    pthread_cond_init(&e->can_read, NULL);
    pthread_cond_init(&e->can_write, NULL);
    /* Initialize early so uae_close() on a partially-open engine is valid. */
    uae_dsp_init(&e->dsp, 0.0);

    unsigned char cfg[1024];
    int r = ctrl(fd, USB_DIR_IN | USB_TYPE_STANDARD | USB_RECIP_DEVICE,
                 USB_REQ_GET_DESCRIPTOR, USB_DT_CONFIG << 8, 0, 9, cfg);
    if (r < 0) { set_err(e, "GET_DESCRIPTOR: %s", strerror(errno)); return e; }
    int total = cfg[2] | (cfg[3] << 8);
    if (total > (int)sizeof(cfg)) total = sizeof(cfg);
    r = ctrl(fd, USB_DIR_IN | USB_TYPE_STANDARD | USB_RECIP_DEVICE,
             USB_REQ_GET_DESCRIPTOR, USB_DT_CONFIG << 8, 0, (uint16_t)total, cfg);
    if (r < 0) { set_err(e, "GET_DESCRIPTOR(full): %s", strerror(errno)); return e; }

    struct uae_alt alts[MAX_ALTS];
    int nalts = parse_config(cfg, total, alts, MAX_ALTS);
    e->nalts = nalts;
    memcpy(e->alts, alts, sizeof(alts));

    struct uae_alt *pick = NULL;
    for (int pass = 0; pass < 2 && !pick; pass++) {
        for (int i = 0; i < nalts; i++) {
            struct uae_alt *a = &alts[i];
            if (!a->ep || (a->ep & 0x80) || !a->channels) continue;
            if (!alt_has_rate(a, (unsigned)rate)) continue;
            if (pass == 0 && a->subframe != ((bits == 24) ? 3 : 2)) continue;
            pick = a;
            break;
        }
    }
    if (!pick) {
        set_err(e, "no UAC1 alt setting for %d Hz / %d-bit", rate, bits);
        return e;
    }

    e->iface   = pick->iface;
    e->alt     = pick->altset;
    e->ep      = pick->ep;
    e->maxpkt  = pick->maxpkt;
    e->channels = pick->channels;
    e->rate    = rate;
    e->bpf     = pick->channels * pick->subframe;
    e->volume  = 1.0f;
    e->ppm     = 0.0;
    e->gain    = 0.0f;
    e->rendered = 0;
    e->frames_done = 0;
    e->ending  = 0;

    /* Android keeps snd-usb-audio bound to the DAC. claimInterface(force=true)
     * evicts it but then claims the interface for us, after which the kernel's
     * SETCONFIGURATION handler refuses to switch because *someone* still holds
     * an interface. So: disconnect every interface ourselves, claim nothing,
     * switch configuration, and claim only afterwards. USBDEVFS_DISCONNECT via
     * USBDEVFS_IOCTL needs no CAP_SYS_ADMIN, unlike USBDEVFS_DISCONNECT_CLAIM. */
    for (int i = 0; i + 1 < total; ) {
        int len = cfg[i], type = cfg[i + 1];
        if (len < 2 || i + len > total) break;
        if (type == USB_DT_INTERFACE && e->nifaces < 16)
            e->ifaces[e->nifaces++] = cfg[i + 2];
        i += len;
    }
    int swept = sweep_audio(fd, cfg, total);
    /* swept == 0 just means nothing is bound right now (a previous session
     * already detached the driver, or the device never bound one). Not fatal:
     * claiming below is the real test. */
    (void)swept;

    LOGI("uae: iface=%d alt=%d ep=0x%02x maxpkt=%d bpf=%d swept=%d",
         e->iface, e->alt, e->ep, e->maxpkt, e->bpf, swept);

    unsigned int cfgval = (unsigned)cfg[5];
    e->cfgval = cfgval;
    if (ioctl(fd, USBDEVFS_SETCONFIGURATION, &cfgval) < 0 && errno != EBUSY) {
        set_err(e, "SETCONFIGURATION: %s", strerror(errno));
        return e;
    }

    /* claim the AudioControl interface (class 1, subclass 1) as well */
    for (int i = 0; i + 1 < total; ) {
        int len = cfg[i], type = cfg[i + 1];
        if (len < 2 || i + len > total) break;
        if (type == USB_DT_INTERFACE && cfg[i + 5] == 0x01 && cfg[i + 6] == 0x01) {
            unsigned int ac = cfg[i + 2];
            e->ctrl_iface = (int)ac;
            if (ioctl(fd, USBDEVFS_CLAIMINTERFACE, &ac) < 0)
                set_err(e, "warning: claim control iface %u: %s", ac, strerror(errno));
            break;
        }
        i += len;
    }

    unsigned int iface = (unsigned)e->iface;
    int claimed = 0;
    for (int attempt = 0; attempt < 10; attempt++) {
        if (ioctl(fd, USBDEVFS_CLAIMINTERFACE, &iface) == 0) { claimed = 1; break; }
        if (errno != EBUSY) break;
        /* The kernel may be re-probing snd-usb-audio after our teardown
         * CONNECT. Sweep again and retry rather than failing the track. */
        usleep(20000);
        sweep_audio(fd, cfg, total);
    }
    if (!claimed) {
        LOGE("uae: CLAIMINTERFACE(%u) failed: %s", iface, strerror(errno));
        if (errno == EBUSY)
            set_err(e, "interface %d busy (snd-usb-audio bound; needs root)",
                    e->iface);
        else
            set_err(e, "CLAIMINTERFACE: %s", strerror(errno));
        return e;
    }

    struct usbdevfs_setinterface si;
    si.interface = (unsigned)e->iface;
    si.altsetting = (unsigned)e->alt;
    if (ioctl(fd, USBDEVFS_SETINTERFACE, &si) < 0) {
        set_err(e, "SETINTERFACE: %s", strerror(errno));
        return e;
    }

    unsigned char fr[3] = { (unsigned char)(rate & 0xff),
                            (unsigned char)((rate >> 8) & 0xff),
                            (unsigned char)((rate >> 16) & 0xff) };
    /* Not fatal: some devices ignore SET_CUR and derive the rate from the
       alt setting. Keep going. */
    (void)ctrl(fd, 0x22, 0x01, 0x0100, (uint16_t)e->ep, 3, fr);

    /* Read it back from the device itself -- this is the only claim about the
     * sample rate that does not come from our own bookkeeping. */
    unsigned char back[3] = {0, 0, 0};
    int got = ctrl(fd, 0xA2, 0x81, 0x0100, (uint16_t)e->ep, 3, back);
    if (got >= 3) {
        unsigned dev_rate = back[0] | (back[1] << 8) | (back[2] << 16);
        LOGI("uae: requested Fs=%u, device GET_CUR Fs=%u", rate, dev_rate);
        if (dev_rate != (unsigned)rate)
            LOGI("uae: WARNING device reports a different rate than requested");
    } else {
        LOGI("uae: GET_CUR sampling frequency unsupported (%d)", got);
    }

    parse_feature_unit(cfg, total, e);
    if (e->hw_vol_supported) {
        int mn = 0, mx = 0, rs = 0, cur = 0;
        if (uac_vol_get(e, 0x82, &mn) == 0 && uac_vol_get(e, 0x83, &mx) == 0) {
            if (uac_vol_get(e, 0x84, &rs) != 0) rs = 0;
            if (uac_vol_get(e, 0x81, &cur) != 0) cur = mx;
            e->hw_vol_min = mn; e->hw_vol_max = mx;
            e->hw_vol_res = rs; e->hw_vol_cur = cur;
            LOGI("uae: feature unit %d volume: min=%d max=%d res=%d cur=%d (1/256 dB)",
                 e->fu_id, mn, mx, rs, cur);
        } else {
            e->hw_vol_supported = 0;
            LOGI("uae: feature unit %d exposes no volume range", e->fu_id);
        }
    }

    /* descriptor summary for the Output screen */
    {
        int off = snprintf(e->summary, sizeof(e->summary), "UAC1\n");
        for (int i = 0; i < e->nalts && off < (int)sizeof(e->summary) - 80; i++) {
            struct uae_alt *a = &e->alts[i];
            if (!a->ep) continue;
            const char *syn[] = {"none", "async", "adaptive", "sync"};
            off += snprintf(e->summary + off, sizeof(e->summary) - off,
                "iface %d alt %d: %d ch, %d-bit, EP 0x%02x %s, maxpkt %d\n",
                a->iface, a->altset, a->channels, a->bitres,
                a->ep, syn[(a->epattr >> 2) & 3], a->maxpkt);
        }
        if (e->hw_vol_supported && off < (int)sizeof(e->summary) - 60)
            snprintf(e->summary + off, sizeof(e->summary) - off,
                     "feature unit %d: volume %d..%d (1/256 dB)\n",
                     e->fu_id, e->hw_vol_min, e->hw_vol_max);
    }

    e->ring = malloc(sizeof(float) * RING_FRAMES * (size_t)e->channels);
    if (!e->ring) { set_err(e, "ring alloc failed"); return e; }

    uae_dsp_prepare_rate(&e->dsp, (double)e->rate);

    e->running = 1;
    if (pthread_create(&e->thread, NULL, pump, e) != 0) {
        set_err(e, "pthread_create failed");
        e->running = 0;
        e->started = 0;
        return e;
    }
    e->started = 1;
    return e;
}

int uae_ok(struct uae *e)
{
    return e && e->running && !e->failed && e->errmsg[0] == 0;
}

int uae_write(struct uae *e, const float *src, int frames)
{
    if (!uae_ok(e)) return -1;
    int done = 0;
    while (done < frames && e->running) {
        pthread_mutex_lock(&e->lock);
        while (e->filled >= RING_FRAMES - 4096 && e->running) {
            struct timespec ts;
            clock_gettime(CLOCK_MONOTONIC, &ts);
            ts.tv_nsec += 50 * 1000000L;
            if (ts.tv_nsec >= 1000000000L) {
                ts.tv_sec++;
                ts.tv_nsec -= 1000000000L;
            }
            pthread_cond_timedwait(&e->can_write, &e->lock, &ts);
        }
        size_t n = ring_write(e, src + (size_t)done * e->channels,
                              (size_t)(frames - done));
        pthread_cond_signal(&e->can_read);
        pthread_mutex_unlock(&e->lock);
        if (n == 0) break;
        done += (int)n;
    }
    return done;
}

void uae_set_dither(struct uae *e, int on)
{
    if (e) e->dither = on ? 1 : 0;
}

void uae_set_resampled(struct uae *e, int on)
{
    if (e) e->resampled = on ? 1 : 0;
}

void uae_set_volume(struct uae *e, float v)
{
    if (!e) return;
    if (v < 0.0f) v = 0.0f;
    if (v > 1.0f) v = 1.0f;
    e->volume = v;
}

/**
 * Switch the live session to another rate / bit depth without releasing the
 * interface. Returns 0 on success. The caller must have stopped writing first.
 */
int uae_switch_format(struct uae *e, int rate, int bits)
{
    if (!e || !e->running) return -1;

    struct uae_alt *pick = NULL;
    for (int pass = 0; pass < 2 && !pick; pass++) {
        for (int i = 0; i < e->nalts; i++) {
            struct uae_alt *a = &e->alts[i];
            if (!a->ep || (a->ep & 0x80) || !a->channels) continue;
            if (!alt_has_rate(a, (unsigned)rate)) continue;
            if (pass == 0 && a->subframe != ((bits == 24) ? 3 : 2)) continue;
            pick = a;
            break;
        }
    }
    if (!pick) return -2;

    /* stop the pump; it frees its URBs on the way out */
    e->running = 0;
    pthread_mutex_lock(&e->lock);
    pthread_cond_broadcast(&e->can_read);
    pthread_cond_broadcast(&e->can_write);
    pthread_mutex_unlock(&e->lock);
    if (e->started) {
        pthread_join(e->thread, NULL);
        e->started = 0;
    }

    pthread_mutex_lock(&e->lock);
    e->head = e->tail = 0;
    e->filled = 0;
    e->primed = 0;
    pthread_cond_broadcast(&e->can_write);
    pthread_mutex_unlock(&e->lock);

    e->frames_done = 0;
    e->rendered = 0;
    e->gain = 0.0f;
    e->ending = 0;
    e->eos = 0;
    e->failed = 0;

    /* Clear DF-II history and recompile RBJ coefficients for the new rate.
     * This covers seeks and non-gapless track changes too, because both go
     * through this path. Gapless boundaries deliberately skip it. */
    uae_dsp_prepare_rate(&e->dsp, (double)rate);

    struct usbdevfs_setinterface si;
    si.interface = (unsigned)pick->iface;
    si.altsetting = (unsigned)pick->altset;
    if (ioctl(e->fd, USBDEVFS_SETINTERFACE, &si) < 0)
        return -3;

    unsigned char fr[3] = { (unsigned char)(rate & 0xff),
                            (unsigned char)((rate >> 8) & 0xff),
                            (unsigned char)((rate >> 16) & 0xff) };
    (void)ctrl(e->fd, 0x22, 0x01, 0x0100, (uint16_t)pick->ep, 3, fr);

    e->iface    = pick->iface;
    e->alt      = pick->altset;
    e->ep       = pick->ep;
    e->maxpkt   = pick->maxpkt;
    e->channels = pick->channels;
    e->bpf      = pick->channels * pick->subframe;
    e->rate     = rate;

    LOGI("uae: switched to %d Hz / %d-bit (alt %d, maxpkt %d)",
         rate, bits, e->alt, e->maxpkt);

    e->running = 1;
    if (pthread_create(&e->thread, NULL, pump, e) != 0) {
        e->running = 0;
        return -4;
    }
    e->started = 1;
    return 0;
}

const char *uae_summary(struct uae *e)
{
    return e ? e->summary : "";
}

/* Fixed-order telemetry snapshot; indices are mirrored in Kotlin. */
int uae_get_telemetry(struct uae *e, long long *out, int max)
{
    if (!e || max < 17) return 0;
    size_t filled;
    pthread_mutex_lock(&e->lock);
    filled = e->filled;
    pthread_mutex_unlock(&e->lock);

    uint64_t latency_frames = filled;
    if (e->frames_packed > e->frames_done)
        latency_frames += e->frames_packed - e->frames_done;

    out[0] = (long long)e->urbs_reaped;
    out[1] = (long long)e->urb_errors;
    out[2] = (long long)e->underruns;
    out[3] = (long long)(filled * 100 / RING_FRAMES);
    out[4] = e->rate;
    out[5] = (e->bpf / (e->channels ? e->channels : 1)) * 8;
    out[6] = e->alt;
    out[7] = e->maxpkt;
    out[8] = (e->alts[0].epattr >> 2) & 3;   /* overwritten below by chosen alt */
    for (int i = 0; i < e->nalts; i++)
        if (e->alts[i].altset == e->alt && e->alts[i].iface == e->iface)
            out[8] = (e->alts[i].epattr >> 2) & 3;
    out[9] = e->rate ? (long long)(latency_frames * 1000 / (uint64_t)e->rate) : 0;
    out[10] = e->interval_count ? (long long)(e->interval_min_ns / 1000) : 0;
    out[11] = e->interval_count ? (long long)(e->interval_max_ns / 1000) : 0;
    out[12] = e->interval_count ? (long long)((e->interval_sum_ns / e->interval_count) / 1000) : 0;
    out[13] = (long long)e->frames_done;
    out[14] = (long long)e->frames_packed;
    out[15] = (long long)filled;
    out[16] = e->rate
        ? (long long)((e->frames_packed - e->frames_done) * 1000 / (uint64_t)e->rate)
        : 0;
    return 17;
}

/* Frames decoded but not yet consumed: ring occupancy + URBs in flight. */
uint64_t uae_buffered_frames(struct uae *e)
{
    if (!e) return 0;
    uint64_t filled;
    pthread_mutex_lock(&e->lock);
    filled = e->filled;
    pthread_mutex_unlock(&e->lock);
    uint64_t flight = e->frames_packed > e->frames_done
                    ? e->frames_packed - e->frames_done : 0;
    return filled + flight;
}

uint64_t uae_frames_played(struct uae *e)
{
    return e ? e->frames_done : 0;
}

void uae_set_eos(struct uae *e)
{
    if (e) e->eos = 1;
}

void uae_set_ppm(struct uae *e, float ppm)
{
    if (e) e->ppm = ppm;
}

/* ------------------------------------------------------------ DSP control */

void uae_set_dsp_enabled(struct uae *e, int on)
{
    if (e) uae_dsp_set_enabled(&e->dsp, on);
}

void uae_set_dsp_bands(struct uae *e, const struct uae_dsp_band *bands,
                       int nbands, double preamp_db, int auto_headroom)
{
    if (e) uae_dsp_set_bands(&e->dsp, bands, nbands, preamp_db, auto_headroom);
}

void uae_reset_dsp(struct uae *e)
{
    if (e) uae_dsp_request_reset(&e->dsp);
}

void uae_close(struct uae *e)
{
    if (!e) return;
    e->running = 0;
    pthread_mutex_lock(&e->lock);
    pthread_cond_broadcast(&e->can_read);
    pthread_cond_broadcast(&e->can_write);
    pthread_mutex_unlock(&e->lock);
    if (e->started) pthread_join(e->thread, NULL);

    struct usbdevfs_setinterface si0;
    si0.interface = (unsigned)e->iface;
    si0.altsetting = 0;
    ioctl(e->fd, USBDEVFS_SETINTERFACE, &si0);

    unsigned int iface = (unsigned)e->iface;
    ioctl(e->fd, USBDEVFS_RELEASEINTERFACE, &iface);
    if (e->ctrl_iface >= 0) {
        unsigned int ac = (unsigned)e->ctrl_iface;
        ioctl(e->fd, USBDEVFS_RELEASEINTERFACE, &ac);
    }

    /* Hand the interfaces back to the kernel with USBDEVFS_CONNECT, which asks
     * normal driver matching to rebind (snd-usb-audio). SETCONFIGURATION was
     * the wrong tool here: usb_set_configuration() re-registers every
     * interface, which invalidated our usbfs fd mid-teardown (EBADF) and is
     * also refused outright while the HID interface is still claimed. */
    /* CONNECT every interface, not just the ones we claimed: the HID remote
     * and the capture interface were evicted by earlier sessions and would
     * otherwise stay dead until the user replugs the DAC. */
    int reconnected = 0;
    for (int i = 0; i < e->nifaces; i++) {
        struct usbdevfs_ioctl ctl;
        memset(&ctl, 0, sizeof(ctl));
        ctl.ifno = (unsigned)e->ifaces[i];
        ctl.ioctl_code = USBDEVFS_CONNECT;
        ctl.data = NULL;
        if (ioctl(e->fd, USBDEVFS_IOCTL, &ctl) == 0) reconnected++;
    }
    LOGI("uae: handed back, rebind requested for %d/%d interfaces",
         reconnected, e->nifaces);

    uae_dsp_destroy(&e->dsp);
    free(e->ring);
    pthread_mutex_destroy(&e->lock);
    pthread_cond_destroy(&e->can_read);
    pthread_cond_destroy(&e->can_write);
    free(e);
}
