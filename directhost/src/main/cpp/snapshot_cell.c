#include <jni.h>
#include <stdatomic.h>
#include <stdint.h>

/* No ABI-sized C header or fallback atomic implementation crosses the mapping. */
_Static_assert(sizeof(_Atomic uint64_t) == 8, "snapshot cell must occupy eight bytes");
_Static_assert(_Alignof(_Atomic uint64_t) <= 8, "snapshot cell alignment exceeds layout");
_Static_assert(__atomic_always_lock_free(8, 0), "64-bit snapshot is not always lock-free on this ABI");
_Static_assert(sizeof(jlong) == 8, "JNI word must retain all bits");

static jlong bind_cell(JNIEnv *env, jclass type, jobject buffer, jint offset) {
    (void)type;
    void *base = (*env)->GetDirectBufferAddress(env, buffer);
    jlong capacity = (*env)->GetDirectBufferCapacity(env, buffer);
    if (!base || offset < 0 || capacity < 8 || offset > capacity - 8 ||
        ((uintptr_t)base + (uint32_t)offset) % _Alignof(_Atomic uint64_t) != 0) {
        jclass error = (*env)->FindClass(env, "java/lang/IllegalArgumentException");
        if (error) (*env)->ThrowNew(env, error, "Invalid direct snapshot cell layout");
        return 0;
    }
    return (jlong)((uintptr_t)base + (uint32_t)offset);
}

static jlong initialize_cell(JNIEnv *env, jclass type, jobject buffer, jint offset) {
    jlong address = bind_cell(env, type, buffer, offset);
    if (!(*env)->ExceptionCheck(env)) atomic_init((_Atomic uint64_t *)(uintptr_t)address, UINT64_C(0));
    return address;
}

/* Setup validated the address. These functions neither touch JNI nor acquire a lock. */
static jlong acquire_cell(JNIEnv *env, jclass type, jlong address) {
    (void)env; (void)type;
    return (jlong)atomic_load_explicit((_Atomic uint64_t *)(uintptr_t)address, memory_order_acquire);
}
static void release_cell(JNIEnv *env, jclass type, jlong address, jlong word) {
    (void)env; (void)type;
    atomic_store_explicit((_Atomic uint64_t *)(uintptr_t)address, (uint64_t)word, memory_order_release);
}

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)reserved;
    JNIEnv *env = 0;
    if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass type = (*env)->FindClass(env, "com/code2hack/eyeprojector/directhost/activation/snapshot/NativeSnapshotAccess");
    if (!type) return JNI_ERR;
    const JNINativeMethod methods[] = {
        {"initializeCell", "(Ljava/nio/ByteBuffer;I)J", (void *)initialize_cell},
        {"bindCell", "(Ljava/nio/ByteBuffer;I)J", (void *)bind_cell},
        {"acquireCell", "(J)J", (void *)acquire_cell},
        {"releaseCell", "(JJ)V", (void *)release_cell},
    };
    if ((*env)->RegisterNatives(env, type, methods, sizeof(methods) / sizeof(methods[0])) != JNI_OK) return JNI_ERR;
    return JNI_VERSION_1_6;
}
