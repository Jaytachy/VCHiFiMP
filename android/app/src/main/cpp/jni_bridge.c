/*
 * jni_bridge.c - thin JNI layer over the usbfs engine.
 *
 * Kotlin pushes decoded PCM as a direct FloatBuffer (zero copy).
 */
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>

#include "usb_audio_engine.h"

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

JNIEXPORT void JNICALL
Java_dev_jay_usbdirect_UsbAudioEngine_nativeSetPpm(JNIEnv *env, jobject thiz,
                                                   jlong h, jfloat ppm)
{
    uae_set_ppm((struct uae *)(intptr_t)h, ppm);
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
