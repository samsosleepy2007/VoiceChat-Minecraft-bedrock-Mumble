#include <jni.h>

#include "VCProximity.h"

#include <QCoreApplication>
#include <QtGlobal>

#include <string>

extern "C" void vc_mumble_server_request_stop();

namespace {

std::string fromJString(JNIEnv *env, jstring value) {
    if (value == nullptr) return {};
    const char *raw = env->GetStringUTFChars(value, nullptr);
    if (raw == nullptr) return {};
    std::string result(raw);
    env->ReleaseStringUTFChars(value, raw);
    return result;
}

jint startNative(JNIEnv *, jclass, jstring, jint, jstring) {
    return QCoreApplication::instance() != nullptr ? 0 : 1;
}

void stopNative(JNIEnv *, jclass) {
    vc_mumble_server_request_stop();
}

jboolean isRunningNative(JNIEnv *, jclass) {
    return QCoreApplication::instance() != nullptr ? JNI_TRUE : JNI_FALSE;
}

jstring lastErrorNative(JNIEnv *env, jclass) {
    return env->NewStringUTF("");
}

jint connectedClientsNative(JNIEnv *, jclass) {
    return 0;
}

void setProximityEnabledNative(JNIEnv *, jclass, jboolean enabled) {
    VCProximity::setEnabled(enabled == JNI_TRUE);
}

jint proximityPlayerCountNative(JNIEnv *, jclass) {
    return VCProximity::playerCount();
}

void setProximityStaleTimeoutMsNative(JNIEnv *, jclass, jlong timeoutMs) {
    VCProximity::setStaleTimeoutMs(static_cast<qint64>(timeoutMs));
}

void updatePlayerStateNative(
        JNIEnv *env, jclass, jstring mumbleNameValue, jstring dimensionValue,
        jdouble x, jdouble y, jdouble z, jfloat rangeBlocks, jboolean micEnabled) {
    const std::string mumbleName = fromJString(env, mumbleNameValue);
    const std::string dimension = fromJString(env, dimensionValue);
    VCProximity::updatePlayer(
            QString::fromUtf8(mumbleName.c_str()),
            QString::fromUtf8(dimension.c_str()),
            static_cast<double>(x),
            static_cast<double>(y),
            static_cast<double>(z),
            static_cast<float>(rangeBlocks),
            micEnabled == JNI_TRUE);
}

void removePlayerStateNative(JNIEnv *env, jclass, jstring mumbleNameValue) {
    const std::string mumbleName = fromJString(env, mumbleNameValue);
    VCProximity::removePlayer(QString::fromUtf8(mumbleName.c_str()));
}

void clearPlayerStatesNative(JNIEnv *, jclass) {
    VCProximity::clearPlayers();
}

JNINativeMethod kNativeMethods[] = {
        {const_cast<char *>("startNative"),
         const_cast<char *>("(Ljava/lang/String;ILjava/lang/String;)I"),
         reinterpret_cast<void *>(startNative)},
        {const_cast<char *>("stopNative"),
         const_cast<char *>("()V"),
         reinterpret_cast<void *>(stopNative)},
        {const_cast<char *>("isRunningNative"),
         const_cast<char *>("()Z"),
         reinterpret_cast<void *>(isRunningNative)},
        {const_cast<char *>("lastErrorNative"),
         const_cast<char *>("()Ljava/lang/String;"),
         reinterpret_cast<void *>(lastErrorNative)},
        {const_cast<char *>("connectedClientsNative"),
         const_cast<char *>("()I"),
         reinterpret_cast<void *>(connectedClientsNative)},
        {const_cast<char *>("setProximityEnabledNative"),
         const_cast<char *>("(Z)V"),
         reinterpret_cast<void *>(setProximityEnabledNative)},
        {const_cast<char *>("proximityPlayerCountNative"),
         const_cast<char *>("()I"),
         reinterpret_cast<void *>(proximityPlayerCountNative)},
        {const_cast<char *>("setProximityStaleTimeoutMsNative"),
         const_cast<char *>("(J)V"),
         reinterpret_cast<void *>(setProximityStaleTimeoutMsNative)},
        {const_cast<char *>("updatePlayerStateNative"),
         const_cast<char *>("(Ljava/lang/String;Ljava/lang/String;DDDFZ)V"),
         reinterpret_cast<void *>(updatePlayerStateNative)},
        {const_cast<char *>("removePlayerStateNative"),
         const_cast<char *>("(Ljava/lang/String;)V"),
         reinterpret_cast<void *>(removePlayerStateNative)},
        {const_cast<char *>("clearPlayerStatesNative"),
         const_cast<char *>("()V"),
         reinterpret_cast<void *>(clearPlayerStatesNative)},
};

} // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *) {
    JNIEnv *env = nullptr;
    if (vm == nullptr || vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK
            || env == nullptr) {
        return JNI_ERR;
    }

    jclass nativeServerClass =
            env->FindClass("com/voicecraft/vcmumbleserver/NativeServer");
    if (nativeServerClass == nullptr) {
        env->ExceptionClear();
        return JNI_ERR;
    }

    const jint methodCount =
            static_cast<jint>(sizeof(kNativeMethods) / sizeof(kNativeMethods[0]));
    if (env->RegisterNatives(nativeServerClass, kNativeMethods, methodCount) != JNI_OK) {
        env->ExceptionClear();
        env->DeleteLocalRef(nativeServerClass);
        return JNI_ERR;
    }

    env->DeleteLocalRef(nativeServerClass);
    return JNI_VERSION_1_6;
}
