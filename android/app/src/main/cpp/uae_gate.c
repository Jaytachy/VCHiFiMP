/* SPDX-License-Identifier: GPL-3.0-or-later */
#include "uae_gate.h"

void uae_gate_init(struct uae_gate *g)
{
    pthread_mutex_init(&g->lock, NULL);
    pthread_cond_init(&g->can_read, NULL);
    pthread_cond_init(&g->can_write, NULL);
    pthread_cond_init(&g->writers_done, NULL);
    pthread_mutex_init(&g->op_lock, NULL);
    g->closed = 0;
    g->aborting = 0;
    g->running = 0;
    g->writers = 0;
    g->epoch = 0;
}

void uae_gate_destroy(struct uae_gate *g)
{
    pthread_mutex_destroy(&g->lock);
    pthread_cond_destroy(&g->can_read);
    pthread_cond_destroy(&g->can_write);
    pthread_cond_destroy(&g->writers_done);
    pthread_mutex_destroy(&g->op_lock);
}

void uae_gate_op_lock(struct uae_gate *g)
{
    pthread_mutex_lock(&g->op_lock);
}

void uae_gate_op_unlock(struct uae_gate *g)
{
    pthread_mutex_unlock(&g->op_lock);
}

void uae_gate_abort(struct uae_gate *g)
{
    pthread_mutex_lock(&g->lock);
    g->aborting = 1;
    g->running = 0;
    pthread_cond_broadcast(&g->can_read);
    pthread_cond_broadcast(&g->can_write);
    pthread_mutex_unlock(&g->lock);
}

void uae_gate_resume(struct uae_gate *g)
{
    pthread_mutex_lock(&g->lock);
    if (!g->closed) g->aborting = 0;
    pthread_mutex_unlock(&g->lock);
}

void uae_gate_close(struct uae_gate *g)
{
    pthread_mutex_lock(&g->lock);
    g->closed = 1;
    g->aborting = 1;
    g->running = 0;
    pthread_cond_broadcast(&g->can_read);
    pthread_cond_broadcast(&g->can_write);
    while (g->writers > 0)
        pthread_cond_wait(&g->writers_done, &g->lock);
    pthread_mutex_unlock(&g->lock);
}

int uae_gate_enter(struct uae_gate *g, uint32_t *epoch)
{
    pthread_mutex_lock(&g->lock);
    if (g->closed || g->aborting) {
        pthread_mutex_unlock(&g->lock);
        return UAE_ERR_CLOSING;
    }
    g->writers++;
    if (epoch) *epoch = g->epoch;
    pthread_mutex_unlock(&g->lock);
    return 0;
}

void uae_gate_leave(struct uae_gate *g)
{
    pthread_mutex_lock(&g->lock);
    if (--g->writers == 0)
        pthread_cond_broadcast(&g->writers_done);
    pthread_mutex_unlock(&g->lock);
}

int uae_gate_check_locked(struct uae_gate *g, uint32_t epoch)
{
    if (epoch != g->epoch) return UAE_ERR_RESET;
    if (g->closed || g->aborting) return UAE_ERR_CLOSING;
    if (!g->running) return -1;
    return 0;
}

int uae_gate_begin_switch(struct uae_gate *g)
{
    pthread_mutex_lock(&g->lock);
    if (g->closed || g->aborting) {
        pthread_mutex_unlock(&g->lock);
        return UAE_ERR_CLOSING;
    }
    g->aborting = 1;
    g->running = 0;
    pthread_cond_broadcast(&g->can_read);
    pthread_cond_broadcast(&g->can_write);
    while (g->writers > 0)
        pthread_cond_wait(&g->writers_done, &g->lock);
    pthread_mutex_unlock(&g->lock);
    return 0;
}

int uae_gate_end_switch(struct uae_gate *g)
{
    pthread_mutex_lock(&g->lock);
    if (g->closed) {
        pthread_mutex_unlock(&g->lock);
        return UAE_ERR_CLOSING;
    }
    g->aborting = 0;
    g->running = 1;
    pthread_mutex_unlock(&g->lock);
    return 0;
}
