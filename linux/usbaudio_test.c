// usbaudio_test.c - direct isochronous USB audio output test for UAC1 devices
//
// Usage:
//   ./usbaudio_test --dump
//   ./usbaudio_test --seconds 20 --ppm 0
//   ./usbaudio_test --seconds 20 --ppm 1400
//
// Sends a 1 kHz S16_LE stereo tone to endpoint 0x03 of interface 2, altset 1,
// one isochronous packet per USB frame (1 ms). --ppm rates the packet size to
// match a device clock that is off from nominal (variable 48/49 frames).
//
// Needs write access to /dev/bus/usb/BBB/DDD and the ability to detach
// snd-usb-audio (CAP_SYS_ADMIN or a udev rule).

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

#define IFACE      2
#define ALTSET     1
#define EP_OUT     0x03
#define NURB       16
#define MAX_PKT    1024
#define TONE_HZ    1000.0
#define FS         48000.0

static volatile sig_atomic_t g_stop = 0;
static void on_sigint(int s) { (void)s; g_stop = 1; }

static const char *default_dev(void) { return "/dev/bus/usb/003/006"; }

static int dump_descriptors(int fd)
{
    unsigned char buf[512];
    struct usbdevfs_ctrltransfer ctrl = {
        .bRequestType = USB_DIR_IN | USB_TYPE_STANDARD | USB_RECIP_DEVICE,
        .bRequest     = USB_REQ_GET_DESCRIPTOR,
        .wValue       = USB_DT_CONFIG << 8,
        .wIndex       = 0,
        .wLength      = 9,
        .timeout      = 2000,
        .data         = buf,
    };
    int r = ioctl(fd, USBDEVFS_CONTROL, &ctrl);
    if (r < 0) { perror("GET_DESCRIPTOR(9)"); return -1; }
    int total = buf[2] | (buf[3] << 8);
    if (total > (int)sizeof(buf)) total = sizeof(buf);
    ctrl.wLength = total;
    r = ioctl(fd, USBDEVFS_CONTROL, &ctrl);
    if (r < 0) { perror("GET_DESCRIPTOR(full)"); return -1; }

    printf("RAW:");
    for (int k = 0; k < total; k++) {
        if (k % 16 == 0) printf("\n%04x:", k);
        printf(" %02x", buf[k]);
    }
    printf("\n");

    int i = 0;
    while (i < total) {
        int len = buf[i];
        int type = buf[i + 1];
        if (len < 2) break;
        if (type == USB_DT_CONFIG) {
            printf("CONFIG  total=%d interfaces=%d\n", buf[i+2] | (buf[i+3]<<8), buf[i+4]);
        } else if (type == USB_DT_INTERFACE) {
            printf("  IFACE %d alt %d class=0x%02x sub=0x%02x proto=0x%02x eps=%d\n",
                   buf[i+2], buf[i+3], buf[i+5], buf[i+6], buf[i+7], buf[i+4]);
        } else if (type == USB_DT_ENDPOINT) {
            int mps = (buf[i+4] | ((buf[i+5] & 0x07) << 8))
                    * (1 + ((buf[i+5] >> 3) & 0x03));
            int attr = buf[i+3];
            const char *syn[] = {"none", "async", "adaptive", "sync"};
            printf("    EP 0x%02x attr=0x%02x type=%d sync=%s maxpkt=%d interval=%d\n",
                   buf[i+2], attr, attr & 0x03, syn[(attr >> 2) & 3], mps, buf[i+6]);
        } else if (type == USB_DT_CS_INTERFACE) {
            int sub = buf[i+2];
            if (sub == 0x01 && len >= 7) {
                printf("      AS_GENERAL termlink=%d delay=%d fmt=0x%04x\n",
                       buf[i+3], buf[i+4], buf[i+5] | (buf[i+6] << 8));
            } else if (sub == 0x02 && len >= 8) {
                int nch = buf[i+4], ssz = buf[i+5], bits = buf[i+6], nf = buf[i+7];
                printf("      FORMAT type=%d ch=%d subframe=%d bits=%d rates=",
                       buf[i+3], nch, ssz, bits);
                for (int k = 0; k < nf && i+8+3*k+2 < total; k++) {
                    int f = buf[i+8+3*k] | (buf[i+9+3*k] << 8) | (buf[i+10+3*k] << 16);
                    printf("%d ", f);
                }
                printf("\n");
            } else {
                printf("      CS_IFACE subtype=0x%02x len=%d\n", sub, len);
            }
        } else if (type == 0x25) {
            printf("    CS_ENDPOINT subtype=0x%02x len=%d\n", buf[i+2], len);
        } else {
            printf("    DESC type=0x%02x len=%d\n", type, len);
        }
        i += len;
    }
    return 0;
}

struct urb_ctx {
    struct usbdevfs_urb urb;
    struct usbdevfs_iso_packet_desc pkt;
    unsigned char *buf;
};

static double g_phase = 0.0;

static void fill_tone(unsigned char *buf, int frames)
{
    int16_t *p = (int16_t *)buf;
    const double amp = 0.25 * 32767.0;
    const double step = 2.0 * M_PI * TONE_HZ / FS;
    for (int f = 0; f < frames; f++) {
        int16_t v = (int16_t)lrint(amp * sin(g_phase));
        p[2*f] = v;
        p[2*f+1] = v;
        g_phase += step;
        if (g_phase > 2.0 * M_PI) g_phase -= 2.0 * M_PI;
    }
}

int main(int argc, char **argv)
{
    const char *dev = default_dev();
    int do_dump = 0;
    int seconds = 20;
    double ppm = 0.0;

    for (int i = 1; i < argc; i++) {
        if (!strcmp(argv[i], "--dump")) do_dump = 1;
        else if (!strcmp(argv[i], "--device") && i+1 < argc) dev = argv[++i];
        else if (!strcmp(argv[i], "--seconds") && i+1 < argc) seconds = atoi(argv[++i]);
        else if (!strcmp(argv[i], "--ppm") && i+1 < argc) ppm = atof(argv[++i]);
        else { fprintf(stderr, "unknown arg %s\n", argv[i]); return 2; }
    }

    int fd = open(dev, O_RDWR);
    if (fd < 0) { perror(dev); return 1; }

    if (do_dump) {
        int r = dump_descriptors(fd);
        close(fd);
        return r ? 1 : 0;
    }

    struct usbdevfs_disconnect_claim dc;
    memset(&dc, 0, sizeof(dc));
    dc.interface = IFACE;
    dc.flags = USBDEVFS_DISCONNECT_CLAIM_IF_DRIVER;
    strncpy(dc.driver, "snd-usb-audio", sizeof(dc.driver) - 1);
    if (ioctl(fd, USBDEVFS_DISCONNECT_CLAIM, &dc) < 0)
        fprintf(stderr, "warning: disconnect_claim: %s\n", strerror(errno));

    unsigned int iface = IFACE;
    if (ioctl(fd, USBDEVFS_CLAIMINTERFACE, &iface) < 0) {
        perror("CLAIMINTERFACE"); close(fd); return 1;
    }

    struct usbdevfs_setinterface si = { .interface = IFACE, .altsetting = ALTSET };
    if (ioctl(fd, USBDEVFS_SETINTERFACE, &si) < 0) {
        perror("SETINTERFACE"); close(fd); return 1;
    }
    printf("claimed iface %d altset %d, ppm=%+.0f\n", IFACE, ALTSET, ppm);

    struct urb_ctx ctx[NURB];
    memset(ctx, 0, sizeof(ctx));
    for (int i = 0; i < NURB; i++) {
        if (posix_memalign((void **)&ctx[i].buf, 4096, MAX_PKT)) {
            perror("alloc"); return 1;
        }
        ctx[i].urb.type = USBDEVFS_URB_TYPE_ISO;
        ctx[i].urb.endpoint = EP_OUT;
        ctx[i].urb.flags = USBDEVFS_URB_ISO_ASAP;
        ctx[i].urb.number_of_packets = 1;
        ctx[i].urb.buffer = ctx[i].buf;
        ctx[i].urb.usercontext = &ctx[i];
    }

    double target = 48.0 * (1.0 + ppm / 1e6);
    double acc = 0.0;
    int prev = 0;
    long frames_sent = 0;

    signal(SIGINT, on_sigint);
    signal(SIGTERM, on_sigint);

    // Prime the pipe.
    frames_sent = 0;
    for (int i = 0; i < NURB; i++) {
        acc += target;
        int cur = (int)floor(acc);
        int n = cur - prev;
        prev = cur;
        if (n < 1) n = 1;
        if (n > 255) n = 255;
        fill_tone(ctx[i].buf, n);
        ctx[i].urb.buffer_length = n * 4;
        ctx[i].pkt.length = n * 4;
        if (ioctl(fd, USBDEVFS_SUBMITURB, &ctx[i].urb) < 0) {
            perror("SUBMITURB"); return 1;
        }
        frames_sent += n;
    }

    struct timespec t0, tn;
    clock_gettime(CLOCK_MONOTONIC, &t0);
    tn = t0;
    unsigned long errors = 0, packets = 0;

    while (!g_stop) {
        struct usbdevfs_urb *u = NULL;
        if (ioctl(fd, USBDEVFS_REAPURB, &u) < 0) {
            if (errno == EINTR) continue;
            perror("REAPURB"); break;
        }
        struct urb_ctx *c = (struct urb_ctx *)u->usercontext;
        packets++;
        if (u->status) errors++;

        clock_gettime(CLOCK_MONOTONIC, &tn);
        double secs = (tn.tv_sec - t0.tv_sec) + (tn.tv_nsec - t0.tv_nsec) / 1e9;
        if (secs >= seconds) break;

        acc += target;
        int cur = (int)floor(acc);
        int n = cur - prev;
        prev = cur;
        if (n < 1) n = 1;
        if (n > 255) n = 255;
        fill_tone(c->buf, n);
        c->urb.buffer_length = n * 4;
        c->pkt.length = n * 4;
        c->urb.status = 0;
        c->urb.actual_length = 0;
        if (ioctl(fd, USBDEVFS_SUBMITURB, &c->urb) < 0) {
            perror("SUBMITURB(resubmit)"); break;
        }
        frames_sent += n;
    }

    double elapsed = (tn.tv_sec - t0.tv_sec) + (tn.tv_nsec - t0.tv_nsec) / 1e9;
    printf("done: %.2f s, %lu packets, %ld frames, %.1f errors, actual avg %.3f frames/ms\n",
           elapsed, packets, frames_sent, (double)errors,
           elapsed > 0 ? frames_sent / (elapsed * 1000.0) : 0.0);

    close(fd);
    return 0;
}
