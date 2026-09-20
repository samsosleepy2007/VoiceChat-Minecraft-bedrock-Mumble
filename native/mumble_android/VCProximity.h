#pragma once

#include <QtCore/QString>
#include <QtCore/QtGlobal>

namespace VCProximity {

void setEnabled(bool enabled);
bool isEnabled();
void setStaleTimeoutMs(qint64 timeoutMs);
void updatePlayer(const QString &mumbleName,
                  const QString &dimension,
                  double x,
                  double y,
                  double z,
                  float rangeBlocks,
                  bool micEnabled);
void removePlayer(const QString &mumbleName);
void clearPlayers();
int playerCount();

// Returns true when Mumble should retain the receiver in the normal-speech
// routing path. Proximity is intentionally disabled by default so stock Mumble
// behaviour remains available for protocol bring-up before Endstone is linked.
bool shouldRoute(const QString &speakerName, const QString &listenerName);

} // namespace VCProximity
