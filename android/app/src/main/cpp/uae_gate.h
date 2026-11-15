/* SPDX-License-Identifier: GPL-3.0-or-later */
#ifndef UAE_GATE_H
#define UAE_GATE_H

#include <pthread.h>
#include <stdint.h>

/* Distinct from USB I/O errors: the producer must stop or retry. */
#define UAE_ERR_CLOSING (-1000)
#define UAE_ERR_RESET   (-1001)

/*
 * Producer/lifecycle gate.
 *
 * lock protects the ring buffer and all gate state. op_lock serializes the
 * lifecycle operations (abort, resume, close, switch) but is never taken by a
 * writer, so abort can always wake a writer that is blocked in the ring wait.
 */
struct uae_gate {
    pthread_mutex_t lock;
    pthread_cond_t can_read, can_write, writers_done;

    pthread_mutex_t op_lock;

    int closed;             /* permanent; set only by close */
    int aborting;           /* transient; set by abort, cleared by resume */
    volatile int running;   /* pump is alive */
    int writers;            /* uae_write calls inside the engine */
    uint32_t epoch;         /* bumped whenever the ring is cleared */
};

void uae_gate_init(struct uae_gate *g);
void uae_gate_destroy(struct uae_gate *g);

void uae_gate_op_lock(struct uae_gate *g);
void uae_gate_op_unlock(struct uae_gate *g);

/* Abort/resume/close assume the caller holds op_lock. */
void uae_gate_abort(struct uae_gate *g);
void uae_gate_resume(struct uae_gate *g);
void uae_gate_close(struct uae_gate *g);

/*
 * Writer admission. Returns UAE_ERR_CLOSING or 0 and stores the current epoch.
 * leave() must be called exactly once after a successful enter().
 */
int  uae_gate_enter(struct uae_gate *g, uint32_t *epoch);
void uae_gate_leave(struct uae_gate *g);

/*
 * Caller holds g->lock. Returns 0 when it is safe to touch the ring, otherwise
 * UAE_ERR_RESET (epoch changed), UAE_ERR_CLOSING (closed/aborting), or -1
 * (pump not running).
 */
int  uae_gate_check_locked(struct uae_gate *g, uint32_t epoch);

/*
 * Switch gate. begin_switch and end_switch assume op_lock is held. begin_switch
 * gates new writers and drains in-flight ones. end_switch re-admits writers
 * only when the engine is not permanently closed.
 */
int  uae_gate_begin_switch(struct uae_gate *g);
int  uae_gate_end_switch(struct uae_gate *g);

#endif
