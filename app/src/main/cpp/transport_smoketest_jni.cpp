#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <arpa/inet.h>
#include <atomic>
#include <cerrno>
#include <cstring>
#include <mutex>
#include <netinet/in.h>
#include <string>
#include <sys/select.h>
#include <sys/socket.h>
#include <thread>
#include <unistd.h>

namespace {
constexpr const char* TAG = "VCMumbleNative";
std::atomic<bool> running{false};
std::atomic<int> clients{0};
std::thread worker;
std::mutex stateMutex;
std::string lastErrorText;
int tcpFd = -1;
int udpFd = -1;

void setError(const std::string& value) {
    std::lock_guard<std::mutex> lock(stateMutex);
    lastErrorText = value;
    __android_log_print(ANDROID_LOG_ERROR, TAG, "%s", value.c_str());
}

void closeSocket(int& fd) {
    if (fd >= 0) {
        shutdown(fd, SHUT_RDWR);
        close(fd);
        fd = -1;
    }
}

int createBoundSocket(int type, int port) {
    int fd = socket(AF_INET, type, 0);
    if (fd < 0) return -1;
    int yes = 1;
    setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &yes, sizeof(yes));
    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = htonl(INADDR_ANY);
    addr.sin_port = htons(static_cast<uint16_t>(port));
    if (bind(fd, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) < 0) {
        close(fd);
        return -1;
    }
    return fd;
}

void serverLoop() {
    while (running.load()) {
        fd_set readSet;
        FD_ZERO(&readSet);
        int maxFd = -1;
        if (tcpFd >= 0) { FD_SET(tcpFd, &readSet); maxFd = std::max(maxFd, tcpFd); }
        if (udpFd >= 0) { FD_SET(udpFd, &readSet); maxFd = std::max(maxFd, udpFd); }
        timeval timeout{0, 250000};
        int result = select(maxFd + 1, &readSet, nullptr, nullptr, &timeout);
        if (result <= 0) continue;

        if (tcpFd >= 0 && FD_ISSET(tcpFd, &readSet)) {
            sockaddr_in peer{};
            socklen_t peerLen = sizeof(peer);
            int client = accept(tcpFd, reinterpret_cast<sockaddr*>(&peer), &peerLen);
            if (client >= 0) {
                // Phase 1 only validates that Android can own the Mumble TCP/UDP port.
                // Do not claim protocol compatibility until the upstream Mumble core is integrated.
                const char* message = "VC Mumble Server transport test\n";
                send(client, message, std::strlen(message), MSG_NOSIGNAL);
                close(client);
            }
        }

        if (udpFd >= 0 && FD_ISSET(udpFd, &readSet)) {
            char buffer[512];
            sockaddr_in peer{};
            socklen_t peerLen = sizeof(peer);
            recvfrom(udpFd, buffer, sizeof(buffer), 0, reinterpret_cast<sockaddr*>(&peer), &peerLen);
        }
    }
}

void stopInternal() {
    running.store(false);
    closeSocket(tcpFd);
    closeSocket(udpFd);
    if (worker.joinable()) worker.join();
    clients.store(0);
}
}

extern "C" JNIEXPORT jint JNICALL
Java_com_voicecraft_vcmumbleserver_NativeServer_startNative(
        JNIEnv*, jclass, jstring, jint fallbackPort, jstring) {
    const int port = static_cast<int>(fallbackPort);
    if (running.load()) return 0;
    {
        std::lock_guard<std::mutex> lock(stateMutex);
        lastErrorText.clear();
    }

    tcpFd = createBoundSocket(SOCK_STREAM, port);
    if (tcpFd < 0) {
        setError("TCP bind failed on port " + std::to_string(port) + ": " + std::strerror(errno));
        stopInternal();
        return 1;
    }
    if (listen(tcpFd, 16) < 0) {
        setError("TCP listen failed: " + std::string(std::strerror(errno)));
        stopInternal();
        return 2;
    }

    udpFd = createBoundSocket(SOCK_DGRAM, port);
    if (udpFd < 0) {
        setError("UDP bind failed on port " + std::to_string(port) + ": " + std::strerror(errno));
        stopInternal();
        return 3;
    }

    running.store(true);
    worker = std::thread(serverLoop);
    __android_log_print(ANDROID_LOG_INFO, TAG, "Transport smoke-test listening TCP+UDP on %d", port);
    return 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_voicecraft_vcmumbleserver_NativeServer_stopNative(JNIEnv*, jclass) {
    stopInternal();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_voicecraft_vcmumbleserver_NativeServer_isRunningNative(JNIEnv*, jclass) {
    return running.load() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_voicecraft_vcmumbleserver_NativeServer_lastErrorNative(JNIEnv* env, jclass) {
    std::lock_guard<std::mutex> lock(stateMutex);
    return env->NewStringUTF(lastErrorText.c_str());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_voicecraft_vcmumbleserver_NativeServer_connectedClientsNative(JNIEnv*, jclass) {
    return clients.load();
}

extern "C" JNIEXPORT void JNICALL
Java_com_voicecraft_vcmumbleserver_NativeServer_setProximityEnabledNative(JNIEnv*, jclass, jboolean) {}

extern "C" JNIEXPORT void JNICALL
Java_com_voicecraft_vcmumbleserver_NativeServer_setProximityStaleTimeoutMs(JNIEnv*, jclass, jlong) {}

extern "C" JNIEXPORT void JNICALL
Java_com_voicecraft_vcmumbleserver_NativeServer_updatePlayerState(
        JNIEnv*, jclass, jstring, jstring, jdouble, jdouble, jdouble, jfloat, jboolean) {}

extern "C" JNIEXPORT void JNICALL
Java_com_voicecraft_vcmumbleserver_NativeServer_removePlayerState(JNIEnv*, jclass, jstring) {}

extern "C" JNIEXPORT void JNICALL
Java_com_voicecraft_vcmumbleserver_NativeServer_clearPlayerStates(JNIEnv*, jclass) {}

extern "C" JNIEXPORT jint JNICALL
Java_com_voicecraft_vcmumbleserver_NativeServer_proximityPlayerCountNative(JNIEnv*, jclass) {
    return 0;
}

JNIEXPORT jint JNI_OnLoad(JavaVM*, void*) {
    return JNI_VERSION_1_6;
}
