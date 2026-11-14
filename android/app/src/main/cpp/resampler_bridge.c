#include <jni.h>
#include <stdint.h>

typedef struct rs_t rs_t;
rs_t *rs_new(int channels, double ratio, int quality);
void rs_delete(rs_t *r);
int  rs_process(rs_t *r, const float *in, int inFrames, float *out, int outMax);
int  rs_flush(rs_t *r, float *out, int outMax);

JNIEXPORT jlong JNICALL
Java_dev_jay_usbdirect_NativeResampler_nativeNew(JNIEnv *env, jobject thiz,
                                                 jint channels, jdouble ratio,
                                                 jint quality)
{
    return (jlong)(intptr_t)rs_new((int)channels, (double)ratio, (int)quality);
}

JNIEXPORT jint JNICALL
Java_dev_jay_usbdirect_NativeResampler_nativeProcess(JNIEnv *env, jobject thiz,
                                                     jlong h, jobject in,
                                                     jint inFrames, jobject out,
                                                     jint outMax)
{
    float *pi = (*env)->GetDirectBufferAddress(env, in);
    float *po = (*env)->GetDirectBufferAddress(env, out);
    if (!pi || !po) return -1;
    return rs_process((rs_t *)(intptr_t)h, pi, (int)inFrames, po, (int)outMax);
}

JNIEXPORT jint JNICALL
Java_dev_jay_usbdirect_NativeResampler_nativeFlush(JNIEnv *env, jobject thiz,
                                                   jlong h, jobject out, jint outMax)
{
    float *po = (*env)->GetDirectBufferAddress(env, out);
    if (!po) return -1;
    return rs_flush((rs_t *)(intptr_t)h, po, (int)outMax);
}

JNIEXPORT void JNICALL
Java_dev_jay_usbdirect_NativeResampler_nativeDelete(JNIEnv *env, jobject thiz, jlong h)
{
    if (h) rs_delete((rs_t *)(intptr_t)h);
}
