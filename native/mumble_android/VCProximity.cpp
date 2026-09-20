#include "VCProximity.h"

#include <QtCore/QDateTime>
#include <QtCore/QHash>
#include <QtCore/QReadWriteLock>
#include <QtCore/QReadLocker>
#include <QtCore/QWriteLocker>

#include <algorithm>
#include <atomic>
#include <cmath>

namespace VCProximity {
namespace {
struct PlayerState {
    QString dimension;
    double x = 0.0;
    double y = 0.0;
    double z = 0.0;
    float rangeBlocks = 30.0F;
    bool voiceEnabled = true;
    qint64 updatedAtMs = 0;
};

QReadWriteLock g_lock;
QHash<QString, PlayerState> g_players;
std::atomic_bool g_enabled{ false };
std::atomic<qint64> g_staleTimeoutMs{ 15000 };

QString keyFor(const QString &name) {
    return name.trimmed().toCaseFolded();
}

bool isFresh(const PlayerState &state, qint64 nowMs) {
    const qint64 timeout = g_staleTimeoutMs.load(std::memory_order_relaxed);
    return timeout <= 0 || (nowMs - state.updatedAtMs) <= timeout;
}

float smoothMix(float from, float to, double t) {
    const double clamped = std::clamp(t, 0.0, 1.0);
    const double smooth = clamped * clamped * (3.0 - 2.0 * clamped);
    return static_cast<float>(static_cast<double>(from)
                              + (static_cast<double>(to) - static_cast<double>(from)) * smooth);
}

float attenuationForNormalizedDistance(double normalizedDistance) {
    if (normalizedDistance <= 0.20) {
        return 1.0F;
    }
    if (normalizedDistance <= 0.60) {
        return smoothMix(1.0F, 0.55F, (normalizedDistance - 0.20) / 0.40);
    }
    if (normalizedDistance <= 0.90) {
        return smoothMix(0.55F, 0.15F, (normalizedDistance - 0.60) / 0.30);
    }
    if (normalizedDistance < 1.0) {
        return smoothMix(0.15F, 0.03F, (normalizedDistance - 0.90) / 0.10);
    }
    return 0.0F;
}
} // namespace

void setEnabled(bool enabled) {
    g_enabled.store(enabled, std::memory_order_release);
}

bool isEnabled() {
    return g_enabled.load(std::memory_order_acquire);
}

void setStaleTimeoutMs(qint64 timeoutMs) {
    g_staleTimeoutMs.store(std::max<qint64>(0, timeoutMs), std::memory_order_release);
}

void updatePlayer(const QString &mumbleName,
                  const QString &dimension,
                  double x,
                  double y,
                  double z,
                  float rangeBlocks,
                  bool voiceEnabled) {
    const QString key = keyFor(mumbleName);
    if (key.isEmpty()) return;

    PlayerState state;
    state.dimension = dimension.trimmed().toCaseFolded();
    state.x = x;
    state.y = y;
    state.z = z;
    state.rangeBlocks = std::max(0.0F, rangeBlocks);
    state.voiceEnabled = voiceEnabled;
    state.updatedAtMs = QDateTime::currentMSecsSinceEpoch();

    QWriteLocker locker(&g_lock);
    g_players.insert(key, state);
}

void removePlayer(const QString &mumbleName) {
    QWriteLocker locker(&g_lock);
    g_players.remove(keyFor(mumbleName));
}

void clearPlayers() {
    QWriteLocker locker(&g_lock);
    g_players.clear();
}

int playerCount() {
    QReadLocker locker(&g_lock);
    return g_players.size();
}

float attenuationFactor(const QString &speakerName, const QString &listenerName) {
    if (!isEnabled()) return 1.0F;

    const QString speakerKey = keyFor(speakerName);
    const QString listenerKey = keyFor(listenerName);
    if (speakerKey.isEmpty() || listenerKey.isEmpty()) return 0.0F;

    const qint64 now = QDateTime::currentMSecsSinceEpoch();
    QReadLocker locker(&g_lock);
    const auto speakerIt = g_players.constFind(speakerKey);
    const auto listenerIt = g_players.constFind(listenerKey);
    if (speakerIt == g_players.constEnd() || listenerIt == g_players.constEnd()) return 0.0F;

    const PlayerState &speaker = speakerIt.value();
    const PlayerState &listener = listenerIt.value();
    if (!isFresh(speaker, now) || !isFresh(listener, now)) return 0.0F;
    if (!speaker.voiceEnabled) return 0.0F;
    if (speakerKey == listenerKey) return 1.0F;
    if (speaker.dimension.isEmpty() || speaker.dimension != listener.dimension) return 0.0F;

    const double range = static_cast<double>(speaker.rangeBlocks);
    if (range <= 0.0) return 0.0F;

    const double dx = speaker.x - listener.x;
    const double dy = speaker.y - listener.y;
    const double dz = speaker.z - listener.z;
    const double distanceSquared = dx * dx + dy * dy + dz * dz;
    if (distanceSquared >= range * range) return 0.0F;

    const double normalizedDistance = std::sqrt(distanceSquared) / range;
    return attenuationForNormalizedDistance(normalizedDistance);
}

bool shouldRoute(const QString &speakerName, const QString &listenerName) {
    return attenuationFactor(speakerName, listenerName) > 0.0F;
}

} // namespace VCProximity
