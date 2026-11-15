/* SPDX-License-Identifier: GPL-3.0-or-later */
// usbplay.c - direct isochronous USB audio playback for UAC1 devices
//
// Plays a WAV/FLAC file (via libsndfile) straight to the DAC through usbfs,
// bypassing the kernel mixer, ALSA and PipeWire entirely.
//
// Usage:
//   usbplay --vidpid 3654:4a55 --bits 24 --rate 96000 --volume 0.5 file.wav
//   usbplay --vidpid 3654:4a55 --tone --seconds 10
//
// Options:
//   --device PATH    explicit /dev/bus/usb/BBB/DDD
//   --vidpid VID:PID find the device by id
//   --rate HZ        device sample rate (44100/48000/96000)
//   --bits 16|24     wire format, picks the alt setting
//   --ppm PPM        feed rate trim for free-running devices (packet sizing)
//   --volume X       0.0 .. 1.0 software volume (float domain)
//   --seconds N      stop after N seconds
//   --start S        start offset in the file, seconds
//   --tone           1 kHz test tone instead of a file
//   --dump           print descriptors and exit

#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <string.h>
#include <fcntl.h>
#include <unistd.h>
#include <errno.h>
#include <signal.h>
#include <time.h>
#include <math.h>
#include <sys/ioctl.h>
#include <linux/usbdevice_fs.h>
#include <linux/usb/ch9.h>

#include <sndfile.h>
#include <samplerate.h>

#define PKTS_PER_URB 8
#define NURB         16
#define MAX_ALTS     32
#define MAX_RATES    16

#define TONE_HZ 1000.0

#ifndef USBDEVFS_URB_TYPE_ISO
#define USBDEVFS_URB_TYPE_ISO 1
#endif

static volatile sig_atomic_t g_stop = 0;
static void on_sigint(int s) { (void)s; g_stop = 1; }

struct alt {
    int iface, altset;
    int channels, subframe, bitres;
    int nrates;
    unsigned int rates[MAX_RATES];
    int ep, maxpkt, epattr;
};

/* ---------------------------------------------------------------- utils */

static uint32_t rng_state = 0x12345678u;
static inline double frand(void)
{
    rng_state ^= rng_state << 13;
    rng_state ^= rng_state >> 17;
    rng_state ^= rng_state << 5;
    return (rng_state & 0x00ffffff) / 16777216.0;  /* [0,1) */
}

static double now_s(void)
{
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return t.tv_sec + t.tv_nsec / 1e9;
}

/* --------------------------------------------------------- descriptor */

static int ctrl_xfer(int fd, uint8_t reqtype, uint8_t req, uint16_t val,
                     uint16_t idx, uint16_t len, void *data)
{
    struct usbdevfs_ctrltransfer c;
    memset(&c, 0, sizeof(c));
    c.bRequestType = reqtype;
    c.bRequest = req;
    c.wValue = val;
    c.wIndex = idx;
    c.wLength = len;
    c.timeout = 2000;
    c.data = data;
    return ioctl(fd, USBDEVFS_CONTROL, &c);
}

static int read_config(int fd, unsigned char *buf, int bufsz)
{
    int r = ctrl_xfer(fd, USB_DIR_IN | USB_TYPE_STANDARD | USB_RECIP_DEVICE,
                      USB_REQ_GET_DESCRIPTOR, USB_DT_CONFIG << 8, 0, 9, buf);
    if (r < 0) { perror("GET_DESCRIPTOR(9)"); return -1; }
    int total = buf[2] | (buf[3] << 8);
    if (total > bufsz) total = bufsz;
    r = ctrl_xfer(fd, USB_DIR_IN | USB_TYPE_STANDARD | USB_RECIP_DEVICE,
                  USB_REQ_GET_DESCRIPTOR, USB_DT_CONFIG << 8, 0, total, buf);
    if (r < 0) { perror("GET_DESCRIPTOR(full)"); return -1; }
    return total;
}

static int parse_config(const unsigned char *buf, int total, struct alt *alts, int maxalts)
{
    int n = 0, cur = -1;

    for (int i = 0; i + 1 < total; ) {
        int len  = buf[i];
        int type = buf[i + 1];
        if (len < 2 || i + len > total) break;

        if (type == USB_DT_INTERFACE) {
            int iface = buf[i + 2], alt = buf[i + 3];
            int icls  = buf[i + 5], isub = buf[i + 6];

            cur = -1;
            if (icls == 0x01 && isub == 0x02 && n < maxalts) {
                struct alt *a = &alts[n];
                memset(a, 0, sizeof(*a));
                a->iface = iface;
                a->altset = alt;
                a->ep = 0;
                cur = n++;
            }
        } else if (type == USB_DT_CS_INTERFACE && cur >= 0) {
            int sub = buf[i + 2];
            if (sub == 0x02 && len >= 8) {             /* FORMAT_TYPE */
                struct alt *a = &alts[cur];
                int fmt = buf[i + 3];
                if (fmt != 1) { cur = -1; }            /* only PCM */
                else {
                    a->channels = buf[i + 4];
                    a->subframe = buf[i + 5];
                    a->bitres   = buf[i + 6];
                    int nf      = buf[i + 7];
                    a->nrates   = 0;
                    for (int k = 0; k < nf && k < MAX_RATES; k++) {
                        int o = i + 8 + 3 * k;
                        if (o + 2 >= i + len) break;
                        a->rates[a->nrates++] = buf[o] | (buf[o+1] << 8) | (buf[o+2] << 16);
                    }
                }
            }
        } else if (type == USB_DT_ENDPOINT && cur >= 0) {
            struct alt *a = &alts[cur];
            a->ep      = buf[i + 2];
            a->epattr  = buf[i + 3];
            a->maxpkt  = (buf[i + 4] | ((buf[i + 5] & 0x07) << 8))
                       * (1 + ((buf[i + 5] >> 3) & 0x03));
        }
        i += len;
    }
    return n;
}

static void print_descriptors(const struct alt *alts, int n)
{
    for (int i = 0; i < n; i++) {
        const struct alt *a = &alts[i];
        const char *syn[] = {"none", "async", "adaptive", "sync"};
        printf("  iface %d alt %d: %d ch, %d-bit (%d bytes), EP 0x%02x %s maxpkt=%d\n",
               a->iface, a->altset, a->channels, a->bitres, a->subframe,
               a->ep, a->ep ? syn[(a->epattr >> 2) & 3] : "-", a->maxpkt);
        if (a->ep && a->nrates) {
            printf("     rates:");
            for (int k = 0; k < a->nrates; k++) printf(" %u", a->rates[k]);
            printf("\n");
        }
    }
}

static int alt_supports(const struct alt *a, unsigned rate)
{
    for (int k = 0; k < a->nrates; k++)
        if (a->rates[k] == rate) return 1;
    return 0;
}

/* ------------------------------------------------------------ device */

static int find_device(const char *vidpid, char *out, size_t outsz)
{
    unsigned vid = 0, pid = 0;
    if (sscanf(vidpid, "%x:%x", &vid, &pid) != 2) return -1;

    FILE *f = popen("ls -d /sys/bus/usb/devices/*", "r");
    if (!f) return -1;
    char path[512];
    while (fgets(path, sizeof(path), f)) {
        char *nl = strchr(path, '\n');
        if (nl) *nl = 0;
        char fn[600];
        snprintf(fn, sizeof(fn), "%s/idVendor", path);
        FILE *v = fopen(fn, "r");
        if (!v) continue;
        unsigned dv = 0, dp = 0;
        if (fscanf(v, "%x", &dv) != 1) { fclose(v); continue; }
        fclose(v);
        snprintf(fn, sizeof(fn), "%s/idProduct", path);
        v = fopen(fn, "r");
        if (!v) continue;
        if (fscanf(v, "%x", &dp) != 1) { fclose(v); continue; }
        fclose(v);
        if (dv != vid || dp != pid) continue;

        unsigned bus = 0, dev = 0;
        snprintf(fn, sizeof(fn), "%s/busnum", path);
        v = fopen(fn, "r");
        if (!v) continue;
        fscanf(v, "%u", &bus);
        fclose(v);
        snprintf(fn, sizeof(fn), "%s/devnum", path);
        v = fopen(fn, "r");
        if (!v) continue;
        fscanf(v, "%u", &dev);
        fclose(v);

        snprintf(out, outsz, "/dev/bus/usb/%03u/%03u", bus, dev);
        pclose(f);
        return 0;
    }
    pclose(f);
    return -1;
}

/* ------------------------------------------------------------ source */

struct source {
    float *data;      /* interleaved, device rate */
    long frames;      /* total sample frames */
    int channels;     /* device channels */
};

static double g_tone_phase = 0.0;

static int gen_tone(struct source *s, unsigned rate, int channels, double ppm, int seconds)
{
    long n = (long)((double)rate * (1.0 + ppm / 1e6) * seconds);
    s->channels = channels;
    s->frames = n;
    s->data = malloc(sizeof(float) * n * channels);
    if (!s->data) return -1;
    double step = 2.0 * M_PI * TONE_HZ / rate;
    for (long f = 0; f < n; f++) {
        float v = 0.2f * (float)sin(g_tone_phase);
        g_tone_phase += step;
        if (g_tone_phase > 2.0 * M_PI) g_tone_phase -= 2.0 * M_PI;
        for (int c = 0; c < channels; c++) s->data[f * channels + c] = v;
    }
    return 0;
}

static int load_file(struct source *s, const char *path, unsigned rate,
                     double ppm, double start_s, double max_s)
{
    SF_INFO info;
    memset(&info, 0, sizeof(info));
    SNDFILE *sf = sf_open(path, SFM_READ, &info);
    if (!sf) {
        fprintf(stderr, "sndfile: %s: %s\n", path, sf_strerror(NULL));
        return -1;
    }
    printf("file: %s  %d Hz  %d ch  %lld frames  fmt=0x%x\n",
           path, info.samplerate, info.channels, (long long)info.frames, info.format);

    long skip = (long)(start_s * info.samplerate);
    if (skip > 0) sf_seek(sf, skip, SEEK_SET);

    long inframes = info.frames > 0 ? info.frames - skip : 0;
    if (max_s > 0) {
        long cap = (long)(max_s * info.samplerate);
        if (inframes <= 0 || inframes > cap) inframes = cap;
    }
    if (inframes <= 0) inframes = (long)(10 * info.samplerate);

    float *in = malloc(sizeof(float) * inframes * info.channels);
    if (!in) { sf_close(sf); return -1; }
    sf_count_t got = sf_readf_float(sf, in, inframes);
    sf_close(sf);
    if (got <= 0) { free(in); fprintf(stderr, "sndfile: nothing decoded\n"); return -1; }

    double ratio = (double)rate * (1.0 + ppm / 1e6) / info.samplerate;
    printf("resample: %d Hz -> %.3f Hz  ratio=%.9f\n", info.samplerate,
           rate * (1.0 + ppm / 1e6), ratio);

    if (fabs(ratio - 1.0) < 1e-9) {
        s->data = in;
        s->frames = got;
        s->channels = info.channels;
        return 0;
    }

    SRC_DATA d;
    memset(&d, 0, sizeof(d));
    long outmax = (long)((double)got * ratio) + 64;
    float *out = malloc(sizeof(float) * outmax * info.channels);
    if (!out) { free(in); return -1; }

    d.data_in = in;
    d.input_frames = got;
    d.data_out = out;
    d.output_frames = outmax;
    d.src_ratio = ratio;
    int err = src_simple(&d, SRC_SINC_MEDIUM_QUALITY, info.channels);
    free(in);
    if (err) { fprintf(stderr, "samplerate: %s\n", src_strerror(err)); free(out); return -1; }

    s->data = out;
    s->frames = d.output_frames_gen;
    s->channels = info.channels;
    return 0;
}

/* --------------------------------------------------------- conversion */

static inline void put_s16(unsigned char *p, float v)
{
    double d = v + (frand() - frand()) / 32768.0;
    long x = lrint(d * 32767.0);
    if (x >  32767) x =  32767;
    if (x < -32768) x = -32768;
    int16_t s = (int16_t)x;
    memcpy(p, &s, 2);
}

static inline void put_s24(unsigned char *p, float v)
{
    double d = v + (frand() - frand()) / 8388608.0;
    long x = lrint(d * 8388607.0);
    if (x >  8388607) x =  8388607;
    if (x < -8388608) x = -8388608;
    p[0] = (unsigned char)(x & 0xff);
    p[1] = (unsigned char)((x >> 8) & 0xff);
    p[2] = (unsigned char)((x >> 16) & 0xff);
}

/* --------------------------------------------------------------- urbs */

struct urb_ctx {
    struct usbdevfs_urb urb;
    struct usbdevfs_iso_packet_desc pkt[PKTS_PER_URB];
    unsigned char *buf;
    int max_bytes;
};

int main(int argc, char **argv)
{
    const char *devpath = NULL, *vidpid = NULL, *file = NULL, *outfile = NULL;
    unsigned rate = 48000;
    int bits = 16, use_tone = 0, do_dump = 0;
    double ppm = 0.0, volume = 1.0, seconds = 0.0, start_s = 0.0;
    double max_load = 30.0;

    for (int i = 1; i < argc; i++) {
        if      (!strcmp(argv[i], "--device") && i+1 < argc) devpath = argv[++i];
        else if (!strcmp(argv[i], "--vidpid") && i+1 < argc) vidpid = argv[++i];
        else if (!strcmp(argv[i], "--rate")   && i+1 < argc) rate = strtoul(argv[++i], NULL, 0);
        else if (!strcmp(argv[i], "--bits")   && i+1 < argc) bits = atoi(argv[++i]);
        else if (!strcmp(argv[i], "--ppm")    && i+1 < argc) ppm = atof(argv[++i]);
        else if (!strcmp(argv[i], "--volume") && i+1 < argc) volume = atof(argv[++i]);
        else if (!strcmp(argv[i], "--seconds")&& i+1 < argc) seconds = atof(argv[++i]);
        else if (!strcmp(argv[i], "--start")  && i+1 < argc) start_s = atof(argv[++i]);
        else if (!strcmp(argv[i], "--max")    && i+1 < argc) max_load = atof(argv[++i]);
        else if (!strcmp(argv[i], "--out")    && i+1 < argc) outfile = argv[++i];
        else if (!strcmp(argv[i], "--tone"))    use_tone = 1;
        else if (!strcmp(argv[i], "--dump"))    do_dump = 1;
        else if (argv[i][0] != '-') file = argv[i];
        else { fprintf(stderr, "unknown arg %s\n", argv[i]); return 2; }
    }

    char devbuf[64];
    if (!devpath && vidpid) {
        if (find_device(vidpid, devbuf, sizeof(devbuf))) {
            fprintf(stderr, "device %s not found\n", vidpid);
            return 1;
        }
        devpath = devbuf;
    }
    if (!devpath) { fprintf(stderr, "need --device or --vidpid\n"); return 2; }

    int fd = open(devpath, O_RDWR);
    if (fd < 0) { perror(devpath); return 1; }

    unsigned char cfg[1024];
    int total = read_config(fd, cfg, sizeof(cfg));
    if (total < 0) return 1;

    struct alt alts[MAX_ALTS];
    int nalts = parse_config(cfg, total, alts, MAX_ALTS);
    printf("device: %s  (%d streaming alt settings)\n", devpath, nalts);
    print_descriptors(alts, nalts);
    if (do_dump) { close(fd); return 0; }

    /* pick alt: right bit depth, right rate, output endpoint */
    struct alt *pick = NULL;
    for (int pass = 0; pass < 2 && !pick; pass++) {
        for (int i = 0; i < nalts; i++) {
            struct alt *a = &alts[i];
            if (!a->ep || (a->ep & 0x80)) continue;
            if (!a->channels) continue;
            if (!alt_supports(a, rate)) continue;
            if (pass == 0) {
                int want = (bits == 24) ? 3 : 2;
                if (a->subframe != want) continue;
            }
            pick = a;
            break;
        }
    }
    if (!pick) {
        fprintf(stderr, "no alt setting for %u Hz / %d-bit\n", rate, bits);
        close(fd);
        return 1;
    }

    int bpf = pick->channels * pick->subframe;
    printf("using iface %d alt %d  EP 0x%02x  %d ch x %d B = %d B/frame\n",
           pick->iface, pick->altset, pick->ep, pick->channels, pick->subframe, bpf);

    /* take the interface away from snd-usb-audio */
    struct usbdevfs_disconnect_claim dc;
    memset(&dc, 0, sizeof(dc));
    dc.interface = pick->iface;
    dc.flags = USBDEVFS_DISCONNECT_CLAIM_IF_DRIVER;
    strncpy(dc.driver, "snd-usb-audio", sizeof(dc.driver) - 1);
    if (ioctl(fd, USBDEVFS_DISCONNECT_CLAIM, &dc) < 0)
        fprintf(stderr, "warning: disconnect_claim: %s\n", strerror(errno));

    unsigned int iface = pick->iface;
    if (ioctl(fd, USBDEVFS_CLAIMINTERFACE, &iface) < 0) {
        perror("CLAIMINTERFACE"); close(fd); return 1;
    }

    struct usbdevfs_setinterface si = { .interface = pick->iface, .altsetting = pick->altset };
    if (ioctl(fd, USBDEVFS_SETINTERFACE, &si) < 0) {
        perror("SETINTERFACE"); close(fd); return 1;
    }

    /* UAC1 sampling frequency control: class request to the endpoint */
    unsigned char fr[3] = { rate & 0xff, (rate >> 8) & 0xff, (rate >> 16) & 0xff };
    int r = ctrl_xfer(fd, 0x22, 0x01, 0x0100, pick->ep, 3, fr);
    if (r < 0) fprintf(stderr, "warning: set sample rate: %s\n", strerror(errno));
    else       printf("sample rate set to %u Hz\n", rate);

    /* source */
    struct source src;
    memset(&src, 0, sizeof(src));
    if (use_tone || !file) {
        if (gen_tone(&src, rate, pick->channels, ppm, (int)(seconds > 0 ? seconds : 10))) {
            fprintf(stderr, "tone alloc failed\n"); return 1;
        }
    } else {
        double lim = seconds > 0 ? seconds : max_load;
        if (load_file(&src, file, rate, ppm, start_s, lim)) return 1;
        if (src.channels > pick->channels) src.channels = pick->channels;
        if (src.channels < pick->channels) {
            fprintf(stderr, "channel count mismatch (%d src, %d dev)\n",
                    src.channels, pick->channels);
            return 1;
        }
    }

    if (outfile) {
        SF_INFO oi;
        memset(&oi, 0, sizeof(oi));
        oi.samplerate = (int)rate;
        oi.channels = src.channels;
        oi.format = SF_FORMAT_WAV | SF_FORMAT_PCM_24;
        SNDFILE *of = sf_open(outfile, SFM_WRITE, &oi);
        if (!of) { fprintf(stderr, "write %s: %s\n", outfile, sf_strerror(NULL)); return 1; }
        float *tmp = malloc(sizeof(float) * src.frames * src.channels);
        for (long f = 0; f < src.frames * src.channels; f++)
            tmp[f] = src.data[f] * (float)volume;
        sf_writef_float(of, tmp, src.frames);
        sf_close(of);
        free(tmp);
        printf("wrote %s: %d Hz, %d ch, %ld frames\n",
               outfile, (int)rate, src.channels, src.frames);
        return 0;
    }

    long total_out = src.frames;
    double duration = (double)total_out / (rate * (1.0 + ppm / 1e6));
    if (seconds > 0 && duration > seconds) duration = seconds;
    printf("playing %.2f s of audio, volume %.2f, ppm %+.0f\n", duration, volume, ppm);

    /* urbs */
    int max_frames = pick->maxpkt / bpf;
    if (max_frames < 1) max_frames = 1;
    struct urb_ctx ctx[NURB];
    memset(ctx, 0, sizeof(ctx));
    for (int i = 0; i < NURB; i++) {
        ctx[i].max_bytes = PKTS_PER_URB * pick->maxpkt;
        if (posix_memalign((void **)&ctx[i].buf, 4096, ctx[i].max_bytes)) {
            perror("alloc"); return 1;
        }
        ctx[i].urb.type = USBDEVFS_URB_TYPE_ISO;
        ctx[i].urb.endpoint = pick->ep;
        ctx[i].urb.flags = USBDEVFS_URB_ISO_ASAP;
        ctx[i].urb.number_of_packets = PKTS_PER_URB;
        ctx[i].urb.buffer = ctx[i].buf;
        ctx[i].urb.usercontext = &ctx[i];
    }

    const double fpm = (double)rate * (1.0 + ppm / 1e6) / 1000.0;
    double acc = 0.0;
    int prev = 0;
    long pos = 0;                 /* frames consumed from src */

    signal(SIGINT, on_sigint);
    signal(SIGTERM, on_sigint);

    double t0 = now_s();

    /* refill one URB; returns bytes queued */
    #define REFILL(c)                                                          \
    do {                                                                       \
        int _off = 0;                                                          \
        for (int _k = 0; _k < PKTS_PER_URB; _k++) {                            \
            acc += fpm;                                                        \
            int _cur = (int)floor(acc);                                        \
            int _n = _cur - prev;                                              \
            prev = _cur;                                                       \
            if (_n < 1) _n = 1;                                                \
            if (_n > max_frames) _n = max_frames;                              \
            for (int _f = 0; _f < _n; _f++) {                                  \
                for (int _c = 0; _c < pick->channels; _c++) {                  \
                    float _v = 0.0f;                                           \
                    if (pos < src.frames)                                      \
                        _v = src.data[pos * src.channels + _c] * (float)volume;\
                    unsigned char *_p = (c)->buf + _off + (_f * pick->channels + _c) * pick->subframe; \
                    if (pick->subframe == 2) put_s16(_p, _v);                  \
                    else                     put_s24(_p, _v);                  \
                }                                                              \
                pos++;                                                         \
            }                                                                  \
            (c)->pkt[_k].length = _n * bpf;                                    \
            _off += _n * bpf;                                                  \
        }                                                                      \
        (c)->urb.buffer_length = _off;                                         \
        (c)->urb.status = 0;                                                   \
        (c)->urb.actual_length = 0;                                            \
    } while (0)

    for (int i = 0; i < NURB; i++) {
        REFILL(&ctx[i]);
        if (ioctl(fd, USBDEVFS_SUBMITURB, &ctx[i].urb) < 0) {
            perror("SUBMITURB"); return 1;
        }
    }

    unsigned long errors = 0, packets = 0;
    double last = t0;

    while (!g_stop) {
        struct usbdevfs_urb *u = NULL;
        if (ioctl(fd, USBDEVFS_REAPURB, &u) < 0) {
            if (errno == EINTR) continue;
            perror("REAPURB"); break;
        }
        struct urb_ctx *c = (struct urb_ctx *)u->usercontext;
        packets += PKTS_PER_URB;
        if (u->status) errors++;

        double now = now_s();
        if (now - t0 >= duration) break;

        REFILL(c);
        if (ioctl(fd, USBDEVFS_SUBMITURB, &c->urb) < 0) {
            perror("SUBMITURB(resubmit)"); break;
        }
        last = now;
    }

    /* let the queued audio drain */
    usleep(150 * 1000);

    double elapsed = last - t0;
    printf("done: %.2f s, %lu packets, %.1f URB errors, %ld/%ld frames\n",
           elapsed, packets, (double)errors, pos, src.frames);

    close(fd);
    return 0;
}
