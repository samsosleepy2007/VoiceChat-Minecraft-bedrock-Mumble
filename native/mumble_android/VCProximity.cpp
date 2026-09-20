#include "VCProximity.h"

#include <QtCore/QDateTime>
#include <QtCore/QHash>
#include <QtCore/QReadWriteLock>
#include <QtCore/QReadLocker>
#include <QtCore/QWriteLocker>

#include <algorithm>
#include <atomic>

namespace VCProximity {
namespace {
struct PlayerState {
    QString dimension;
    double x = 0.0;
    double y = 0.0;
    double z = 0.0;
    float rangeBlocks = 30.0F;
    bool micEnabled = true;
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
                  bool micEnabled) {
    const QString key = keyFor(mumbleName);
    if (key.isEmpty()) return;

    PlayerState state;
    state.dimension = dimension.trimmed().toCaseFolded();
    state.x = x;
    state.y = y;
    state.z = z;
    state.rangeBlocks = std::max(0.0F, rangeBlocks);
    state.micEnabled = micEnabled;
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

bool shouldRoute(const QString &speakerName, const QString &listenerName) {
    if (!isEnabled()) return true;

    const QString speakerKey = keyFor(speakerName);
    const QString listenerKey = keyFor(listenerName);
    if (speakerKey.isEmpty() || listenerKey.isEmpty()) return false;

    const qint64 now = QDateTime::currentMSecsSinceEpoch();
    QReadLocker locker(&g_lock);
    const auto speakerIt = g_players.constFind(speakerKey);
    const auto listenerIt = g_players.constFind(listenerKey);
    if (speakerIt == g_players.constEnd() || listenerIt == g_players.constEnd()) return false;

    const PlayerState &speaker = speakerIt.value();
    const PlayerState &listener = listenerIt.value();
    if (!isFresh(speaker, now) || !isFresh(listener, now)) return false;
    if (!speaker.micEnabled) return false;
    if (speakerKey == listenerKey) return true;
    if (speaker.dimension.isEmpty() || speaker.dimension != listener.dimension) return false;

    const double dx = speaker.x - listener.x;
    const double dy = speaker.y - listener.y;
    const double dz = speaker.z - listener.z;
    const double distanceSquared = dx * dx + dy * dy + dz * dz;
    const double range = static_cast<double>(speaker.rangeBlocks);
    return distanceSquared <= range * range;
}

} // namespace VCProximity
