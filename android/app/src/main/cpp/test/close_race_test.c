/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * Host-side lifecycle tests for the real producer gate used by
 * usb_audio_engine.c. USB ioctls are not linked here; uae_gate.c is the same
 * source the Android engine compiles.
 *
 *   gcc -O2 -pthread -std=c11 -I cpp cpp/uae_gate.c cpp/test/close_race_test.c
 */
#define _GNU_SOURCE
#include <pthread.h>
#include <signal.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#include "uae_gate.h"

#define WRITERS       8
#define CLOSE_ITERS   20000
#define SWITCH_ITERS  20000

static int g_fail;

static void timeout_handler(int sig)
{
    (void)sig;
    fprintf(stderr, "FAIL  watchdog timeout\n");
    _exit(2);
}

static void result(int ok, const char *name, const char *detail)
{
    printf("%s  %-46s %s\n", ok ? "PASS" : "FAIL", name, detail ? detail : "");
    fflush(stdout);
    if (!ok) g_fail++;
}

static uint32_t rnd(uint32_t *s)
{
    *s ^= *s << 13;
    *s ^= *s >> 17;
    *s ^= *s << 5;
    return *s;
}

static void jitter(uint32_t *s)
{
    if ((rnd(s) & 7u) == 0u) {
        struct timespec ts = { 0, (long)(rnd(s) % 50000u) };
        nanosleep(&ts, NULL);
    }
}

static int count_writers(struct uae_gate *g)
{
    pthread_mutex_lock(&g->lock);
    int n = g->writers;
    pthread_mutex_unlock(&g->lock);
    return n;
}

/* ---------------------------------------------------------------- case 1 */

struct close_ctx {
    struct uae_gate *g;
    pthread_barrier_t *start;
    pthread_barrier_t *done;
    int iters;
    atomic_long accepted;
    atomic_long rejected;
};

struct close_arg {
    struct close_ctx *c;
    unsigned seed;
};

static void *close_writer(void *p)
{
    struct close_arg *arg = p;
    struct close_ctx *c = arg->c;
    uint32_t s = arg->seed | 1u;

    for (int i = 0; i < c->iters; i++) {
        pthread_barrier_wait(c->start);

        uint32_t epoch = 0;
        int rc = uae_gate_enter(c->g, &epoch);
        if (rc == 0) {
            jitter(&s);
            pthread_mutex_lock(&c->g->lock);
            int cr = uae_gate_check_locked(c->g, epoch);
            pthread_mutex_unlock(&c->g->lock);
            if (cr == 0) atomic_fetch_add(&c->accepted, 1);
            else atomic_fetch_add(&c->rejected, 1);
            uae_gate_leave(c->g);
        } else {
            atomic_fetch_add(&c->rejected, 1);
        }

        pthread_barrier_wait(c->done);
    }
    return NULL;
}

static void test_close_race(void)
{
    pthread_barrier_t start, done;
    pthread_t th[WRITERS];
    struct close_arg args[WRITERS];
    struct close_ctx ctx;
    int ok = 1;
    char detail[160];

    pthread_barrier_init(&start, NULL, WRITERS + 1);
    pthread_barrier_init(&done, NULL, WRITERS + 1);
    memset(&ctx, 0, sizeof(ctx));
    ctx.start = &start;
    ctx.done = &done;
    ctx.iters = CLOSE_ITERS;

    for (int i = 0; i < WRITERS; i++) {
        args[i].c = &ctx;
        args[i].seed = 0x9e3779b9u * (unsigned)(i + 1);
        pthread_create(&th[i], NULL, close_writer, &args[i]);
    }

    for (int iter = 0; iter < CLOSE_ITERS; iter++) {
        struct uae_gate g;
        uae_gate_init(&g);
        g.running = 1;
        ctx.g = &g;

        pthread_barrier_wait(&start);
        uint32_t s = 0x1234567u ^ (uint32_t)iter;
        jitter(&s);

        uae_gate_op_lock(&g);
        uae_gate_close(&g);
        uae_gate_op_unlock(&g);

        pthread_barrier_wait(&done);

        if (!g.closed || !g.aborting || g.running || count_writers(&g) != 0 ||
            g.writers != 0) {
            ok = 0;
        }
        uae_gate_destroy(&g);
    }

    for (int i = 0; i < WRITERS; i++) pthread_join(th[i], NULL);
    pthread_barrier_destroy(&start);
    pthread_barrier_destroy(&done);

    snprintf(detail, sizeof(detail), "%d iters, %ld accepted, %ld rejected",
             CLOSE_ITERS, atomic_load(&ctx.accepted), atomic_load(&ctx.rejected));
    result(ok, "writers-vs-close", detail);
}

/* ---------------------------------------------------------------- case 2 */

struct switch_ctx {
    struct uae_gate *g;
    atomic_int stop;
    atomic_long accepted;
    atomic_long old_epoch_frames;
};

struct switch_arg {
    struct switch_ctx *c;
    unsigned seed;
};

static void *switch_writer(void *p)
{
    struct switch_arg *arg = p;
    struct switch_ctx *c = arg->c;
    uint32_t s = arg->seed | 1u;

    while (!atomic_load_explicit(&c->stop, memory_order_relaxed)) {
        uint32_t epoch = 0;
        int rc = uae_gate_enter(c->g, &epoch);
        if (rc == 0) {
            jitter(&s);
            pthread_mutex_lock(&c->g->lock);
            int cr = uae_gate_check_locked(c->g, epoch);
            if (cr == 0) {
                if (epoch != c->g->epoch)
                    atomic_fetch_add(&c->old_epoch_frames, 1);
                atomic_fetch_add(&c->accepted, 1);
            }
            pthread_mutex_unlock(&c->g->lock);
            uae_gate_leave(c->g);
        }
    }
    return NULL;
}

static void switch_once(struct uae_gate *g, uint32_t *ring_epoch)
{
    uae_gate_op_lock(g);
    int rc = uae_gate_begin_switch(g);
    if (rc == 0) {
        pthread_mutex_lock(&g->lock);
        *ring_epoch = UINT32_MAX;      /* ring cleared: no frame of any epoch */
        g->epoch++;
        pthread_cond_broadcast(&g->can_write);
        pthread_mutex_unlock(&g->lock);
        (void)uae_gate_end_switch(g);
    }
    uae_gate_op_unlock(g);
}

static void test_switch_race(void)
{
    struct uae_gate g;
    struct switch_ctx ctx;
    struct switch_arg args[WRITERS];
    pthread_t th[WRITERS];
    uint32_t ring_epoch = UINT32_MAX;
    int ok = 1;
    char detail[200];

    memset(&ctx, 0, sizeof(ctx));
    uae_gate_init(&g);
    g.running = 1;
    ctx.g = &g;
    for (int i = 0; i < WRITERS; i++) {
        args[i].c = &ctx;
        args[i].seed = 0x85ebca6bu * (unsigned)(i + 1);
        pthread_create(&th[i], NULL, switch_writer, &args[i]);
    }

    uint32_t s = 0xabcdef01u;
    for (int i = 0; i < SWITCH_ITERS; i++) {
        switch_once(&g, &ring_epoch);
        jitter(&s);
    }

    atomic_store(&ctx.stop, 1);
    for (int i = 0; i < WRITERS; i++) pthread_join(th[i], NULL);

    /* One final clear: no writer can leave a pre-switch frame behind. */
    switch_once(&g, &ring_epoch);
    if (!g.closed && g.aborting) ok = 0;
    if (ring_epoch != UINT32_MAX) ok = 0;
    if (atomic_load(&ctx.old_epoch_frames) != 0) ok = 0;

    snprintf(detail, sizeof(detail), "%d switches, %ld accepted, %ld old-epoch frames",
             SWITCH_ITERS, atomic_load(&ctx.accepted),
             atomic_load(&ctx.old_epoch_frames));
    result(ok, "writers-vs-switch-format", detail);
    uae_gate_destroy(&g);
}

/* ------------------------------------------------------- lifecycle cases */

static void test_abort_then_close(void)
{
    struct uae_gate g;
    uae_gate_init(&g);
    g.running = 1;

    uae_gate_op_lock(&g);
    uae_gate_abort(&g);
    uae_gate_op_unlock(&g);
    int a = g.aborting && !g.running;

    uae_gate_op_lock(&g);
    uae_gate_close(&g);
    uae_gate_op_unlock(&g);
    result(a && g.closed && g.aborting && !g.running &&
           count_writers(&g) == 0, "abort-then-close", "closed after drain");
    uae_gate_destroy(&g);
}

static void test_double_abort(void)
{
    struct uae_gate g;
    uae_gate_init(&g);
    g.running = 1;
    uae_gate_op_lock(&g);
    uae_gate_abort(&g);
    uae_gate_abort(&g);
    uae_gate_op_unlock(&g);
    result(g.aborting && g.closed == 0 && !g.running,
           "double-abort", "idempotent");
    uae_gate_destroy(&g);
}

static void test_double_close(void)
{
    struct uae_gate g;
    uae_gate_init(&g);
    g.running = 1;
    uae_gate_op_lock(&g);
    uae_gate_close(&g);
    uae_gate_close(&g);
    uae_gate_op_unlock(&g);
    result(g.closed && g.aborting && !g.running &&
           count_writers(&g) == 0, "double-close", "gate idempotent");
    uae_gate_destroy(&g);
}

static void test_abort_resume_writers(void)
{
    struct uae_gate g;
    uint32_t epoch = 0;
    uae_gate_init(&g);
    g.running = 1;

    uae_gate_op_lock(&g);
    uae_gate_abort(&g);
    uae_gate_resume(&g);
    /* Seek path: pump restart without releasing the USB claim. */
    int begin = uae_gate_begin_switch(&g);
    pthread_mutex_lock(&g.lock);
    g.epoch++;
    pthread_mutex_unlock(&g.lock);
    int end = uae_gate_end_switch(&g);
    uae_gate_op_unlock(&g);

    int rc = uae_gate_enter(&g, &epoch);
    pthread_mutex_lock(&g.lock);
    int cr = uae_gate_check_locked(&g, epoch);
    pthread_mutex_unlock(&g.lock);
    if (rc == 0) uae_gate_leave(&g);

    result(begin == 0 && end == 0 && rc == 0 && cr == 0 &&
           !g.aborting && g.running, "abort-resume-writers",
           "writers admitted after pump restart");
    uae_gate_destroy(&g);
}

static void test_resume_after_close(void)
{
    struct uae_gate g;
    uint32_t epoch = 0;
    uae_gate_init(&g);
    g.running = 1;
    uae_gate_op_lock(&g);
    uae_gate_close(&g);
    uae_gate_resume(&g);
    uae_gate_op_unlock(&g);
    int rc = uae_gate_enter(&g, &epoch);
    result(g.closed && g.aborting && !g.running && rc == UAE_ERR_CLOSING,
           "resume-after-close", "no-op; writer rejected");
    uae_gate_destroy(&g);
}

struct resume_ctx {
    struct uae_gate *g;
    int iters;
};

static void *resume_loop(void *p)
{
    struct resume_ctx *c = p;
    for (int i = 0; i < c->iters; i++) {
        uae_gate_op_lock(c->g);
        uae_gate_resume(c->g);
        uae_gate_op_unlock(c->g);
    }
    return NULL;
}

static void test_resume_racing_close(void)
{
    struct uae_gate g;
    struct resume_ctx rc = { &g, 100000 };
    pthread_t t;
    uae_gate_init(&g);
    g.running = 1;
    pthread_create(&t, NULL, resume_loop, &rc);

    uae_gate_op_lock(&g);
    uae_gate_close(&g);
    uae_gate_op_unlock(&g);

    pthread_join(t, NULL);
    uint32_t epoch = 0;
    int erc = uae_gate_enter(&g, &epoch);
    result(g.closed && g.aborting && erc == UAE_ERR_CLOSING &&
           count_writers(&g) == 0, "resume-racing-close",
           "close wins; no writer admitted");
    uae_gate_destroy(&g);
}

static void test_epoch_sequence(void)
{
    struct uae_gate g;
    uint32_t old, epoch = 0;
    int rc;
    uae_gate_init(&g);
    g.running = 1;

    old = g.epoch;
    switch_once(&g, &(uint32_t){ UINT32_MAX });

    pthread_mutex_lock(&g.lock);
    rc = uae_gate_check_locked(&g, old);   /* must fail before ring_write */
    pthread_mutex_unlock(&g.lock);

    int enter = uae_gate_enter(&g, &epoch);
    pthread_mutex_lock(&g.lock);
    int current = uae_gate_check_locked(&g, epoch);
    pthread_mutex_unlock(&g.lock);
    if (enter == 0) uae_gate_leave(&g);

    result(rc == UAE_ERR_RESET && g.epoch == old + 1 &&
           enter == 0 && current == 0 && epoch == g.epoch,
           "epoch-sequence", "old epoch blocked; current epoch accepted");
    uae_gate_destroy(&g);
}

int main(void)
{
    signal(SIGALRM, timeout_handler);
    alarm(300);

    printf("== close_race_test: real uae_gate.c, %d writers ==\n", WRITERS);
    test_close_race();
    test_switch_race();
    test_abort_then_close();
    test_double_abort();
    test_double_close();
    test_abort_resume_writers();
    test_resume_after_close();
    test_resume_racing_close();
    test_epoch_sequence();

    printf("== %s ==\n", g_fail ? "FAIL" : "PASS");
    return g_fail ? 1 : 0;
}
