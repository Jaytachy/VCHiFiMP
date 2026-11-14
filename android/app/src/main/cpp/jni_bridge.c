/*
 * jni_bridge.c - thin JNI layer over the usbfs engine.
 *
 * Kotlin pushes decoded PCM as a direct FloatBuffer (zero copy).
 */
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "usb_audio_engine.h"
#include "uae_dsp.h"

static void throw_illegal(JNIEnv *env, const char *msg)
{
    jclass cls = (*env)->FindClass(env, "java/lang/IllegalStateException");
    if (cls) (*env)->ThrowNew(env, cls, msg);
}

JNIEXPORT jlong JNICALL
Java_dev_jay_usbdirect_UsbAudioEngine_nativeOpen(JNIEnv *env, jobject thiz,
                                                 jint fd, jint rate, jint bits)
{
    struct uae *e = uae_open(fd, rate, bits);
    if (!e) {
        throw_illegal(env, "engine alloc failed");
        return 0;
    }
    if (!uae_ok(e)) {
        const char *msg = uae_error(e);
        uae_close(e);
        throw_illegal(env, msg ? msg : "engine open failed");
        return 0;
    }
    return (jlong)(intptr_t)e;
}

JNIEXPORT jboolean JNICALL
Java_dev_jay_usbdirect_UsbAudioEngine_nativeOk(JNIEnv *env, jobject thiz, jlong h)
{
    return uae_ok((struct uae *)(intptr_t)h) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_dev_jay_usbdirect_UsbAudioEngine_nativeError(JNIEnv *env, jobject thiz, jlong h)
{
    const char *s = uae_error((struct uae *)(intptr_t)h);
    return (*env)->NewStringUTF(env, s ? s : "");
}

JNIEXPORT jint JNICALL
Java_dev_jay_usbdirect_UsbAudioEngine_nativeWrite(JNIEnv *env, jobject thiz,
                                                  jlong h, jobject buf,
                                                  jint frames)
{
    struct uae *e = (struct uae *)(intptr_t)h;
    float *p = (*env)->GetDirectBufferAddress(env, buf);
    if (!p) { throw_illegal(env, "need a direct FloatBuffer"); return -1; }
    return uae_write(e, p, frames);
}

JNIEXPORT void JNICALL
Java_dev_jay_usbdirect_UsbAudioEngine_nativeSetVolume(JNIEnv *env, jobject thiz,
                                                      jlong h, jfloat v)
{
    uae_set_volume((struct uae *)(intptr_t)h, v);
}

JNIEXPORT jlongArray JNICALL
Java_dev_jay_usbdirect_UsbAudioEngine_nativeTelemetry(JNIEnv *env, jobject thiz, jlong h)
{
    long long buf[24];
    int n = uae_get_telemetry((struct uae *)(intptr_t)h, buf, 24);
    jlong tmp[24];
    for (int i = 0; i < n; i++) tmp[i] = (jlong)buf[i];
    jlongArray arr = (*env)->NewLongArray(env, n);
    if (arr && n > 0) (*env)->SetLongArrayRegion(env, arr, 0, n, tmp);
    return arr;
}

JNIEXPORT jstring JNICALL
Java_dev_jay_usbdirect_UsbAudioEngine_nativeSummary(JNIEnv *env, jobject thiz, jlong h)
{
    const char *s = uae_summary((struct uae *)(intptr_t)h);
    return (*env)->NewStringUTF(env, s ? s : "");
}

JNIEXPORT jint JNICALL
Java_dev_jay_usbdirect_UsbAudioEngine_nativeHwVolInfo(JNIEnv *env, jobject thiz,
                                                      jlong h, jintArray out)
{
    int info[4];
    if (!uae_hw_volume_info((struct uae *)(intptr_t)h, info)) return 0;
    if (out) (*env)->SetIntArrayRegion(env, out, 0, 4, (const jint *)info);
    return 1;
}

JNIEXPORT jboolean JNICALL
Java_dev_jay_usbdirect_UsbAudioEngine_nativeHwVolSet(JNIEnv *env, jobject thiz,
                                                     jlong h, jint raw)
{
    return uae_hw_volume_set((struct uae *)(intptr_t)h, (int)raw) == 0
           ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_dev_jay_usbdirect_UsbAudioEngine_nativeSwitchFormat(JNIEnv *env, jobject thiz,
                                                         jlong h, jint rate, jint bits)
{
    return (jint)uae_switch_format((struct uae *)(intptr_t)h, (int)rate, (int)bits);
}

JNIEXPORT jlong JNICALL
Java_dev_jay_usbdirect_UsbAudioEngine_nativeBufferedFrames(JNIEnv *env, jobject thiz, jlong h)
{
    return (jlong)uae_buffered_frames((struct uae *)(intptr_t)h);
}

JNIEXPORT jlong JNICALL
Java_dev_jay_usbdirect_UsbAudioEngine_nativeFramesPlayed(JNIEnv *env, jobject thiz, jlong h)
{
    return (jlong)uae_frames_played((struct uae *)(intptr_t)h);
}

JNIEXPORT void JNICALL
Java_dev_jay_usbdirect_UsbAudioEngine_nativeSetDither(JNIEnv *env, jobject thiz,
                                                      jlong h, jboolean on)
{
    uae_set_dither((struct uae *)(intptr_t)h, on ? 1 : 0);
}

JNIEXPORT void JNICALL
Java_dev_jay_usbdirect_UsbAudioEngine_nativeSetPpm(JNIEnv *env, jobject thiz,
                                                   jlong h, jfloat ppm)
{
    uae_set_ppm((struct uae *)(intptr_t)h, ppm);
}

JNIEXPORT void JNICALL
Java_dev_jay_usbdirect_UsbAudioEngine_nativeDspEnable(JNIEnv *env, jobject thiz,
                                                      jlong h, jboolean on)
{
    if (!h) return;
    uae_set_dsp_enabled((struct uae *)(intptr_t)h, on ? 1 : 0);
}

JNIEXPORT void JNICALL
Java_dev_jay_usbdirect_UsbAudioEngine_nativeDspSetBands(JNIEnv *env, jobject thiz,
                                                        jlong h, jdoubleArray params,
                                                        jint nbands, jdouble preamp_db,
                                                        jboolean auto_headroom)
{
    if (!h || !params) return;
    int n = nbands;
    if (n < 0) n = 0;
    if (n > UAE_DSP_MAX_BANDS) n = UAE_DSP_MAX_BANDS;
    if ((*env)->GetArrayLength(env, params) < n * 5) return;

    double raw[UAE_DSP_MAX_BANDS * 5];
    (*env)->GetDoubleArrayRegion(env, params, 0, n * 5, raw);
    if ((*env)->ExceptionCheck(env)) return;

    struct uae_dsp_band bands[UAE_DSP_MAX_BANDS];
    memset(bands, 0, sizeof(bands));
    for (int i = 0; i < n; i++) {
        const double *p = &raw[i * 5];
        bands[i].type = (int)p[0];
        bands[i].fc = p[1];
        bands[i].q = p[2];
        bands[i].gain_db = p[3];
        bands[i].enabled = p[4] != 0.0;
    }
    uae_set_dsp_bands((struct uae *)(intptr_t)h, bands, n,
                      (double)preamp_db, auto_headroom ? 1 : 0);
}

JNIEXPORT void JNICALL
Java_dev_jay_usbdirect_UsbAudioEngine_nativeDspReset(JNIEnv *env, jobject thiz,
                                                     jlong h)
{
    if (!h) return;
    uae_reset_dsp((struct uae *)(intptr_t)h);
}

JNIEXPORT void JNICALL
Java_dev_jay_usbdirect_UsbAudioEngine_nativeEos(JNIEnv *env, jobject thiz, jlong h)
{
    if (h) uae_set_eos((struct uae *)(intptr_t)h);
}

JNIEXPORT void JNICALL
Java_dev_jay_usbdirect_UsbAudioEngine_nativeClose(JNIEnv *env, jobject thiz, jlong h)
{
    if (h) uae_close((struct uae *)(intptr_t)h);
}
