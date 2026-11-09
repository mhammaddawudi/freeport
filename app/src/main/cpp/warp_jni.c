/*
 * JNI shim over Warp's C ABI (warp/src/ffi.rs).
 *
 * Every core function returns a heap-allocated, NUL-terminated JSON string
 * that must be released with warp_string_free. This shim copies each reply
 * into a Java string and frees the original, so no native buffer ever
 * crosses into Kotlin.
 */
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>
#include <pthread.h>
#include <android/log.h>

extern char *warp_version(void);
extern void warp_string_free(char *raw);
extern char *warp_job_poll(uint64_t id);
extern char *warp_job_cancel(uint64_t id);
extern char *warp_job_free(uint64_t id);
extern char *warp_identity_open(const char *payload);
extern char *warp_identity_free(uint64_t id);
extern char *warp_scan_start(uint64_t identity, const char *payload);
extern char *warp_tunnel_start(uint64_t identity, const char *payload);
extern char *warp_tunnel_attach_tun(uint64_t job, int32_t fd);
extern char *warp_core_start(const char *arguments);

static const char *NULL_REPLY = "{\"ok\":false,\"error\":\"the core returned nothing\"}";

/*
 * The core only ever returns JSON built by serde_json, which escapes
 * everything outside printable ASCII, so NewStringUTF's modified-UTF-8 input
 * requirement is always met.
 */
static jstring take_reply(JNIEnv *env, char *reply) {
    if (reply == NULL) {
        return (*env)->NewStringUTF(env, NULL_REPLY);
    }
    jstring result = (*env)->NewStringUTF(env, reply);
    warp_string_free(reply);
    return result;
}

#define JNI_FN(name) Java_io_github_mhammaddawudi_freeport_core_NativeWarpCore_##name

/*
 * The core logs through env_logger, which writes to stderr. On Android
 * nothing reads stderr, so everything it says about scanning and handshakes
 * was being thrown away. This pipes stderr into CoreLog.fromCore, putting the
 * core's own account of a connection on the Logs screen next to the app's.
 */
static JavaVM *g_vm;
static jclass g_log_class;
static jmethodID g_log_method;
static int g_stderr_pipe[2];
static pthread_t g_log_thread;
static int g_capturing;

static void *pump_core_log(void *unused) {
    JNIEnv *env;
    if ((*g_vm)->AttachCurrentThread(g_vm, &env, NULL) != JNI_OK) {
        return NULL;
    }
    char buffer[1024];
    size_t used = 0;
    ssize_t got;
    while ((got = read(g_stderr_pipe[0], buffer + used, sizeof(buffer) - used - 1)) > 0) {
        used += (size_t) got;
        buffer[used] = '\0';
        char *start = buffer;
        char *newline;
        while ((newline = strchr(start, '\n')) != NULL) {
            *newline = '\0';
            if (*start != '\0') {
                __android_log_write(ANDROID_LOG_INFO, "WarpCore", start);
                jstring line = (*env)->NewStringUTF(env, start);
                if (line != NULL) {
                    (*env)->CallStaticVoidMethod(env, g_log_class, g_log_method, line);
                    (*env)->DeleteLocalRef(env, line);
                }
            }
            start = newline + 1;
        }
        used = strlen(start);
        memmove(buffer, start, used);
    }
    (*g_vm)->DetachCurrentThread(g_vm);
    return NULL;
}

JNIEXPORT jstring JNICALL JNI_FN(nativeCaptureLogs)(JNIEnv *env, jobject self) {
    if (g_capturing) {
        return (*env)->NewStringUTF(env, "{\"ok\":true,\"capturing\":true}");
    }
    (*env)->GetJavaVM(env, &g_vm);
    jclass local = (*env)->FindClass(env, "io/github/mhammaddawudi/freeport/core/CoreLog");
    if (local == NULL) {
        return (*env)->NewStringUTF(env, "{\"ok\":false,\"error\":\"CoreLog is missing\"}");
    }
    g_log_class = (*env)->NewGlobalRef(env, local);
    g_log_method = (*env)->GetStaticMethodID(env, g_log_class, "fromCore", "(Ljava/lang/String;)V");
    if (g_log_method == NULL) {
        return (*env)->NewStringUTF(env, "{\"ok\":false,\"error\":\"CoreLog.fromCore is missing\"}");
    }
    if (pipe(g_stderr_pipe) != 0) {
        return (*env)->NewStringUTF(env, "{\"ok\":false,\"error\":\"could not open a pipe\"}");
    }
    setvbuf(stderr, NULL, _IOLBF, 0);
    dup2(g_stderr_pipe[1], STDERR_FILENO);
    if (pthread_create(&g_log_thread, NULL, pump_core_log, NULL) != 0) {
        return (*env)->NewStringUTF(env, "{\"ok\":false,\"error\":\"could not start the log reader\"}");
    }
    pthread_detach(g_log_thread);
    g_capturing = 1;
    return (*env)->NewStringUTF(env, "{\"ok\":true,\"capturing\":true}");
}

/* The core reads its WARP_* switches from the environment. */
JNIEXPORT jstring JNICALL JNI_FN(nativeSetEnv)(JNIEnv *env, jobject self, jstring name, jstring value) {
    const char *n = (*env)->GetStringUTFChars(env, name, NULL);
    const char *v = (*env)->GetStringUTFChars(env, value, NULL);
    int rc = (n != NULL && v != NULL) ? setenv(n, v, 1) : -1;
    if (n != NULL) (*env)->ReleaseStringUTFChars(env, name, n);
    if (v != NULL) (*env)->ReleaseStringUTFChars(env, value, v);
    return (*env)->NewStringUTF(env, rc == 0 ? "{\"ok\":true}" : "{\"ok\":false}");
}

static jstring call_with_text(JNIEnv *env, jstring text, char *(*fn)(const char *)) {
    const char *raw = (*env)->GetStringUTFChars(env, text, NULL);
    if (raw == NULL) {
        return (*env)->NewStringUTF(env, "{\"ok\":false,\"error\":\"out of memory\"}");
    }
    char *reply = fn(raw);
    (*env)->ReleaseStringUTFChars(env, text, raw);
    return take_reply(env, reply);
}

static jstring call_with_id_and_text(
    JNIEnv *env, jlong id, jstring text, char *(*fn)(uint64_t, const char *)) {
    const char *raw = (*env)->GetStringUTFChars(env, text, NULL);
    if (raw == NULL) {
        return (*env)->NewStringUTF(env, "{\"ok\":false,\"error\":\"out of memory\"}");
    }
    char *reply = fn((uint64_t) id, raw);
    (*env)->ReleaseStringUTFChars(env, text, raw);
    return take_reply(env, reply);
}

JNIEXPORT jstring JNICALL JNI_FN(nativeVersion)(JNIEnv *env, jobject self) {
    return take_reply(env, warp_version());
}

JNIEXPORT jstring JNICALL JNI_FN(nativeIdentityOpen)(JNIEnv *env, jobject self, jstring payload) {
    return call_with_text(env, payload, warp_identity_open);
}

JNIEXPORT jstring JNICALL JNI_FN(nativeIdentityFree)(JNIEnv *env, jobject self, jlong id) {
    return take_reply(env, warp_identity_free((uint64_t) id));
}

JNIEXPORT jstring JNICALL JNI_FN(nativeScanStart)(
    JNIEnv *env, jobject self, jlong identity, jstring payload) {
    return call_with_id_and_text(env, identity, payload, warp_scan_start);
}

JNIEXPORT jstring JNICALL JNI_FN(nativeTunnelStart)(
    JNIEnv *env, jobject self, jlong identity, jstring payload) {
    return call_with_id_and_text(env, identity, payload, warp_tunnel_start);
}

JNIEXPORT jstring JNICALL JNI_FN(nativeAttachTun)(JNIEnv *env, jobject self, jlong job, jint fd) {
    return take_reply(env, warp_tunnel_attach_tun((uint64_t) job, (int32_t) fd));
}

JNIEXPORT jstring JNICALL JNI_FN(nativeJobPoll)(JNIEnv *env, jobject self, jlong id) {
    return take_reply(env, warp_job_poll((uint64_t) id));
}

JNIEXPORT jstring JNICALL JNI_FN(nativeJobCancel)(JNIEnv *env, jobject self, jlong id) {
    return take_reply(env, warp_job_cancel((uint64_t) id));
}

JNIEXPORT jstring JNICALL JNI_FN(nativeJobFree)(JNIEnv *env, jobject self, jlong id) {
    return take_reply(env, warp_job_free((uint64_t) id));
}

JNIEXPORT jstring JNICALL JNI_FN(nativeCoreStart)(JNIEnv *env, jobject self, jstring arguments) {
    return call_with_text(env, arguments, warp_core_start);
}
