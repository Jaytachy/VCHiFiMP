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
    double ppm;

    volatile int running;
    volatile int failed;
    int started;
    pthread_t thread;
    char errmsg[160];
};

static uint32_t rng_state = 0x2545f491u;
static inline float frand(void)
{
    rng_state ^= rng_state << 13;
    rng_state ^= rng_state >> 17;
    rng_state ^= rng_state << 5;
    return (float)(rng_state & 0x00ffffffu) / 16777216.0f;
}

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

static void pack_frames(struct uae *e, unsigned char *dst, const float *src, int n)
{
    const int ss = e->bpf / e->channels;   /* subframe bytes */
    for (int f = 0; f < n; f++) {
        for (int c = 0; c < e->channels; c++) {
            float v = src[f * e->channels + c] * e->volume;
            unsigned char *p = dst + (f * e->channels + c) * ss;
            if (ss == 2) {
                double d = v + (frand() - frand()) / 32768.0;
                long x = lrint(d * 32767.0);
                if (x > 32767) x = 32767;
                if (x < -32768) x = -32768;
                int16_t s = (int16_t)x;
                memcpy(p, &s, 2);
            } else {
                double d = v + (frand() - frand()) / 8388608.0;
                long x = lrint(d * 8388607.0);
                if (x > 8388607) x = 8388607;
                if (x < -8388608) x = -8388608;
                p[0] = (unsigned char)(x & 0xff);
                p[1] = (unsigned char)((x >> 8) & 0xff);
                p[2] = (unsigned char)((x >> 16) & 0xff);
            }
        }
    }
}

/* Fill one URB with PKTS_PER_URB milliseconds of audio. */
static void fill_urb(struct uae *e, struct urb_ctx *c, double *acc, int *prev,
                     int max_frames, double fpm, int block)
{
    float tmp[MAX_FRAMES * 2];
    int off = 0;

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

        if (got < (size_t)n)
            memset(tmp + got * e->channels, 0,
                   sizeof(float) * (size_t)(n - got) * e->channels);

        pack_frames(e, c->buf + off, tmp, n);
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
            set_err(e, "SUBMITURB: %s", strerror(errno));
            e->failed = 1;
            e->running = 0;
            goto cleanup;
        }
        ctx[i].submitted = 1;
    }

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
    pthread_mutex_init(&e->lock, NULL);
    pthread_cond_init(&e->can_read, NULL);
    pthread_cond_init(&e->can_write, NULL);

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

    /* Detach the kernel driver if one is bound (needs CAP_SYS_ADMIN). */
    struct usbdevfs_disconnect_claim dc;
    memset(&dc, 0, sizeof(dc));
    dc.interface = (unsigned)e->iface;
    dc.flags = USBDEVFS_DISCONNECT_CLAIM_IF_DRIVER;
    strncpy(dc.driver, "snd-usb-audio", sizeof(dc.driver) - 1);
    if (ioctl(fd, USBDEVFS_DISCONNECT_CLAIM, &dc) < 0 && errno != EPERM) {
        /* EBUSY here means somebody else owns it; keep going and let claim say so */
    }

    unsigned int iface = (unsigned)e->iface;
    if (ioctl(fd, USBDEVFS_CLAIMINTERFACE, &iface) < 0) {
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

    e->ring = malloc(sizeof(float) * RING_FRAMES * (size_t)e->channels);
    if (!e->ring) { set_err(e, "ring alloc failed"); return e; }

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

void uae_set_volume(struct uae *e, float v)
{
    if (!e) return;
    if (v < 0.0f) v = 0.0f;
    if (v > 1.0f) v = 1.0f;
    e->volume = v;
}

void uae_set_ppm(struct uae *e, float ppm)
{
    if (e) e->ppm = ppm;
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

    unsigned int iface = (unsigned)e->iface;
    ioctl(e->fd, USBDEVFS_RELEASEINTERFACE, &iface);

    free(e->ring);
    pthread_mutex_destroy(&e->lock);
    pthread_cond_destroy(&e->can_read);
    pthread_cond_destroy(&e->can_write);
    free(e);
}
