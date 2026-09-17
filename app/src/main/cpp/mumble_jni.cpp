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
}

// In core builds QtService/androiddeployqt owns loading Qt and entering Mumble's
// main(). startNative exists only to keep the Java API identical to smoke builds.
extern "C" JNIEXPORT jint JNICALL
Java_com_voicecraft_vcmumbleserver_NativeServer_startNative(
        JNIEnv *, jclass, jstring, jint, jstring) {
    return QCoreApplication::instance() != nullptr ? 0 : 1;
}

extern "C" JNIEXPORT void JNICALL
Java_com_voicecraft_vcmumbleserver_NativeServer_stopNative(JNIEnv *, jclass) {
    vc_mumble_server_request_stop();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_voicecraft_vcmumbleserver_NativeServer_isRunningNative(JNIEnv *, jclass) {
    return QCoreApplication::instance() != nullptr ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_voicecraft_vcmumbleserver_NativeServer_lastErrorNative(JNIEnv *env, jclass) {
    // Startup failures are currently surfaced by Qt/Mumble logcat. A structured
    // error bridge is deliberately deferred until stock-client bring-up passes.
    return env->NewStringUTF("");
}

extern "C" JNIEXPORT jint JNICALL
Java_com_voicecraft_vcmumbleserver_NativeServer_connectedClientsNative(JNIEnv *, jclass) {
    // TODO after protocol bring-up: expose authenticated Mumble session count.
    return 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_voicecraft_vcmumbleserver_NativeServer_setProximityEnabledNative(
        JNIEnv *, jclass, jboolean enabled) {
    VCProximity::setEnabled(enabled == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL
Java_com_voicecraft_vcmumbleserver_NativeServer_setProximityStaleTimeoutMs(
        JNIEnv *, jclass, jlong timeoutMs) {
    VCProximity::setStaleTimeoutMs(static_cast<qint64>(timeoutMs));
}

extern "C" JNIEXPORT void JNICALL
Java_com_voicecraft_vcmumbleserver_NativeServer_updatePlayerState(
        JNIEnv *env, jclass, jstring mumbleNameValue, jstring dimensionValue,
        jdouble x, jdouble y, jdouble z, jfloat rangeBlocks) {
    const std::string mumbleName = fromJString(env, mumbleNameValue);
    const std::string dimension = fromJString(env, dimensionValue);
    VCProximity::updatePlayer(
            QString::fromUtf8(mumbleName.c_str()),
            QString::fromUtf8(dimension.c_str()),
            static_cast<double>(x),
            static_cast<double>(y),
            static_cast<double>(z),
            static_cast<float>(rangeBlocks));
}

extern "C" JNIEXPORT void JNICALL
Java_com_voicecraft_vcmumbleserver_NativeServer_removePlayerState(
        JNIEnv *env, jclass, jstring mumbleNameValue) {
    const std::string mumbleName = fromJString(env, mumbleNameValue);
    VCProximity::removePlayer(QString::fromUtf8(mumbleName.c_str()));
}

extern "C" JNIEXPORT void JNICALL
Java_com_voicecraft_vcmumbleserver_NativeServer_clearPlayerStates(JNIEnv *, jclass) {
    VCProximity::clearPlayers();
}

extern "C" JNIEXPORT jint JNICALL
Java_com_voicecraft_vcmumbleserver_NativeServer_proximityPlayerCountNative(JNIEnv *, jclass) {
    return VCProximity::playerCount();
}

JNIEXPORT jint JNI_OnLoad(JavaVM *, void *) {
    return JNI_VERSION_1_6;
}
