import {
  world,
  system,
  ItemStack,
  ItemLockMode,
  EntityComponentTypes,
  EquipmentSlot,
  PlayerPermissionLevel,
} from "@minecraft/server";
import {
  CustomForm,
  ObservableBoolean,
  ObservableNumber,
  ObservableString,
} from "@minecraft/server-ui";

// Keep the original VoiceCraft item identifiers so existing worlds upgrade
// without losing the Mic item. Server integration is VC Mumble native.
const MIC_OFF = "voicecraft:mic_off";
const MIC_ON = "voicecraft:mic_on";
const LEGACY_HOLD = "voicecraft:mic_hold";
const LEGACY_TOGGLE = "voicecraft:mic_toggle";

const MODE_HOLD = "hold";
const MODE_TOGGLE = "toggle";

const PROP_MODE = "vcmumble:mode";
const PROP_LATCH = "vcmumble:toggle_latched";
const PROP_VOICE_RANGE = "vcmumble:voice_range";
const LEGACY_PROP_MODE = "voicecraft:mode";
const LEGACY_PROP_LATCH = "voicecraft:toggle_latched";
const LEGACY_PROP_VOICE_RANGE = "voicecraft:voice_range";

// Contract implemented by feature/minecraft-mic-addon-v1.
const MIC_ON_TAG = "vcmumble.mic.on";
const MIC_OFF_TAG = "vcmumble.mic.off";
const RANGE_VALUE_PREFIX = "vcmumble.vr.value.";
const RANGE_REQUEST_PREFIX = "vcmumble.vr.request.";
const RANGE_MAX_PREFIX = "vcmumble.vr.max.";
const RANGE_ACK_PREFIX = "vcmumble.vr.ack.";
const RANGE_SYNC_PREFIX = "vcmumble.vr.sync.";
const ATTN_VALUE_PREFIX = "vcmumble.attn.value.";
const ATTN_REQUEST_PREFIX = "vcmumble.attn.request.";
const ATTN_ACK_PREFIX = "vcmumble.attn.ack.";
const ATTN_SYNC_PREFIX = "vcmumble.attn.sync.";

const DEFAULT_VOICE_RANGE = 30;
const DEFAULT_MAX_RANGE = 150;
const VOICE_RANGE_PREVIEW_PARTICLE = "vcmumble:voice_range_preview";
const VOICE_RANGE_PREVIEW_MIN_POINTS = 48;
const VOICE_RANGE_PREVIEW_MAX_POINTS = 96;
const VOICE_RANGE_COMMIT_DEBOUNCE_TICKS = 8;
const VOICE_RANGE_CHANGE_COOLDOWN_TICKS = 20 * 30;
let rangeRequestSequence = 0;
let attenuationRequestSequence = 0;
const states = new Map();
const rangeChangeCooldownUntil = new Map();

function voiceRangeCooldownTicks(player) {
  return Math.max(
    0,
    (rangeChangeCooldownUntil.get(player.id) ?? 0) - system.currentTick
  );
}

function voiceRangeCooldownSeconds(player) {
  return Math.ceil(voiceRangeCooldownTicks(player) / 20);
}

function startVoiceRangeCooldown(player) {
  rangeChangeCooldownUntil.set(
    player.id,
    system.currentTick + VOICE_RANGE_CHANGE_COOLDOWN_TICKS
  );
}

function isMicId(typeId) {
  return (
    typeId === MIC_OFF ||
    typeId === MIC_ON ||
    typeId === LEGACY_HOLD ||
    typeId === LEGACY_TOGGLE
  );
}

function isLegacyId(typeId) {
  return typeId === LEGACY_HOLD || typeId === LEGACY_TOGGLE;
}

function inventory(player) {
  return player.getComponent(EntityComponentTypes.Inventory)?.container;
}

function equippable(player) {
  return player.getComponent(EntityComponentTypes.Equippable);
}

function itemId(stack) {
  return stack?.typeId ?? "";
}

function getMainId(player) {
  try {
    return itemId(equippable(player)?.getEquipment(EquipmentSlot.Mainhand));
  } catch {
    return "";
  }
}

function getOffId(player) {
  try {
    return itemId(equippable(player)?.getEquipment(EquipmentSlot.Offhand));
  } catch {
    return "";
  }
}

function migrateDynamicProperties(player) {
  try {
    if (player.getDynamicProperty(PROP_MODE) === undefined) {
      const oldMode = player.getDynamicProperty(LEGACY_PROP_MODE);
      if (oldMode === MODE_TOGGLE || oldMode === MODE_HOLD) {
        player.setDynamicProperty(PROP_MODE, oldMode);
      }
    }
    if (player.getDynamicProperty(PROP_LATCH) === undefined) {
      const oldLatch = player.getDynamicProperty(LEGACY_PROP_LATCH);
      if (oldLatch === true || oldLatch === false) {
        player.setDynamicProperty(PROP_LATCH, oldLatch);
      }
    }
    if (player.getDynamicProperty(PROP_VOICE_RANGE) === undefined) {
      const oldRange = Number(player.getDynamicProperty(LEGACY_PROP_VOICE_RANGE));
      if (Number.isFinite(oldRange) && oldRange >= 1) {
        player.setDynamicProperty(PROP_VOICE_RANGE, Math.floor(oldRange));
      }
    }
  } catch {}
}

function getMode(player) {
  const value = player.getDynamicProperty(PROP_MODE);
  if (value === MODE_TOGGLE) return MODE_TOGGLE;
  if (value === MODE_HOLD) return MODE_HOLD;

  const legacy = player.getDynamicProperty(LEGACY_PROP_MODE);
  if (legacy === MODE_TOGGLE) {
    player.setDynamicProperty(PROP_MODE, MODE_TOGGLE);
    return MODE_TOGGLE;
  }
  return MODE_HOLD;
}

function setMode(player, mode) {
  player.setDynamicProperty(PROP_MODE, mode === MODE_TOGGLE ? MODE_TOGGLE : MODE_HOLD);
}

function getLatch(player) {
  const value = player.getDynamicProperty(PROP_LATCH);
  if (value === true) return true;
  if (value === false) return false;
  return player.getDynamicProperty(LEGACY_PROP_LATCH) === true;
}

function setLatch(player, value) {
  player.setDynamicProperty(PROP_LATCH, !!value);
}

function makeMic(on) {
  const stack = new ItemStack(on ? MIC_ON : MIC_OFF, 1);
  stack.lockMode = ItemLockMode.inventory;
  stack.keepOnDeath = true;
  return stack;
}

function scanMic(player) {
  const result = [];
  const inv = inventory(player);
  if (inv) {
    for (let i = 0; i < inv.size; i++) {
      const id = itemId(inv.getItem(i));
      if (isMicId(id)) result.push({ where: "inventory", index: i, id });
    }
  }
  try {
    const offId = getOffId(player);
    if (isMicId(offId)) result.push({ where: "offhand", index: -1, id: offId });
  } catch {}
  return result;
}

function enforceSingleMic(player) {
  const inv = inventory(player);
  const offMic = isMicId(getOffId(player));
  let keepIndex = -1;

  if (!offMic && inv) {
    let selected = -1;
    try {
      selected = Number(player.selectedSlotIndex);
    } catch {}

    if (
      Number.isInteger(selected) &&
      selected >= 0 &&
      selected < inv.size &&
      isMicId(itemId(inv.getItem(selected)))
    ) {
      keepIndex = selected;
    }

    if (keepIndex < 0) {
      for (let i = 0; i < inv.size; i++) {
        if (isMicId(itemId(inv.getItem(i)))) {
          keepIndex = i;
          break;
        }
      }
    }
  }

  let removed = 0;
  if (inv) {
    for (let i = 0; i < inv.size; i++) {
      if (!isMicId(itemId(inv.getItem(i)))) continue;
      if (!offMic && i === keepIndex) continue;
      inv.setItem(i, undefined);
      removed++;
    }
  }

  if (removed > 0) {
    console.warn(
      `[VCMumbleItem/BP] MIC_DUPLICATE_REMOVED player=${player.name} removed=${removed}`
    );
  }
}

function hasAnyMic(player) {
  return scanMic(player).length > 0;
}

function ensureMic(player) {
  enforceSingleMic(player);
  if (hasAnyMic(player)) return;

  const inv = inventory(player);
  if (!inv) return;

  const leftover = inv.addItem(makeMic(false));
  if (leftover) {
    player.sendMessage("§c[VC Mumble] Inventory เต็ม — ไม่สามารถมอบ Mic ได้§r");
    return;
  }

  console.warn(`[VCMumbleItem/BP] MIC_GIVEN player=${player.name} state=OFF`);
}

function migrateLegacyItems(player) {
  let legacyMode = null;
  const inv = inventory(player);

  if (inv) {
    for (let i = 0; i < inv.size; i++) {
      const current = inv.getItem(i);
      const id = itemId(current);
      if (!isLegacyId(id)) continue;

      if (id === LEGACY_TOGGLE) legacyMode = MODE_TOGGLE;
      else if (legacyMode === null) legacyMode = MODE_HOLD;

      inv.setItem(i, makeMic(false));
      console.warn(`[VCMumbleItem/BP] MIGRATE player=${player.name} slot=${i} ${id}->${MIC_OFF}`);
    }
  }

  try {
    const eq = equippable(player);
    const off = eq?.getEquipment(EquipmentSlot.Offhand);
    const id = itemId(off);
    if (isLegacyId(id)) {
      legacyMode = id === LEGACY_TOGGLE ? MODE_TOGGLE : MODE_HOLD;
      eq?.setEquipment(EquipmentSlot.Offhand, makeMic(false));
    }
  } catch {}

  if (legacyMode !== null) {
    setMode(player, legacyMode);
    setLatch(player, false);
    states.delete(player.id);
  }
}

function replaceMicStatus(player, on) {
  const target = on ? MIC_ON : MIC_OFF;
  let changed = false;
  const inv = inventory(player);

  if (inv) {
    for (let i = 0; i < inv.size; i++) {
      const current = inv.getItem(i);
      const id = itemId(current);
      if (!isMicId(id) || id === target) continue;
      inv.setItem(i, makeMic(on));
      changed = true;
    }
  }

  try {
    const eq = equippable(player);
    const off = eq?.getEquipment(EquipmentSlot.Offhand);
    const id = itemId(off);
    if (isMicId(id) && id !== target) {
      eq?.setEquipment(EquipmentSlot.Offhand, makeMic(on));
      changed = true;
    }
  } catch (e) {
    console.warn(`[VCMumbleItem/BP] offhand status replace failed player=${player.name}: ${e}`);
  }

  return changed;
}

function reassertMicFlags(player) {
  const inv = inventory(player);
  if (inv) {
    for (let i = 0; i < inv.size; i++) {
      const slot = inv.getSlot(i);
      if (!isMicId(slot.typeId)) continue;
      try {
        slot.lockMode = ItemLockMode.inventory;
        slot.keepOnDeath = true;
      } catch {}
    }
  }

  try {
    const off = equippable(player)?.getEquipmentSlot(EquipmentSlot.Offhand);
    if (off && isMicId(off.typeId)) {
      off.lockMode = ItemLockMode.inventory;
      off.keepOnDeath = true;
    }
  } catch {}
}

function publishMicState(player, on) {
  const wanted = on ? MIC_ON_TAG : MIC_OFF_TAG;
  const unwanted = on ? MIC_OFF_TAG : MIC_ON_TAG;

  try {
    // Keep the two bridge tags strictly mutually exclusive. Some worlds can
    // retain an old OFF tag while the visual Mic item has already switched ON.
    try {
      if (player.hasTag(unwanted)) player.removeTag(unwanted);
    } catch {}
    try {
      if (!player.hasTag(wanted)) player.addTag(wanted);
    } catch {}

    let tags = [];
    try {
      tags = player.getTags();
    } catch {}
    const correct = tags.includes(wanted) && !tags.includes(unwanted);

    // Command fallback repairs tag state if Script API tag mutation did not
    // become visible immediately to Endstone. Only runs when verification fails.
    if (!correct) {
      try { player.runCommand(`tag @s remove ${unwanted}`); } catch {}
      try { player.runCommand(`tag @s add ${wanted}`); } catch {}
    }
  } catch (e) {
    console.warn(`[VCMumbleItem/BP] mic tag sync failed player=${player.name}: ${e}`);
  }
}
function stateFor(player) {
  let state = states.get(player.id);
  if (state) return state;

  const mode = getMode(player);
  const mainMic = isMicId(getMainId(player));
  const offMic = isMicId(getOffId(player));
  let latch = getLatch(player);

  if (mode === MODE_TOGGLE && player.getDynamicProperty(PROP_LATCH) === undefined) {
    const anyOn = scanMic(player).some((entry) => entry.id === MIC_ON);
    if (anyOn && !offMic) latch = true;
  }

  const effective = offMic || (mode === MODE_HOLD ? mainMic : latch);
  state = {
    mode,
    lastMainMic: mainMic,
    toggleLatched: latch,
    effective,
  };
  states.set(player.id, state);
  return state;
}

function evaluate(player) {
  migrateLegacyItems(player);
  enforceSingleMic(player);
  ensureMic(player);

  const state = stateFor(player);
  const mainMic = isMicId(getMainId(player));
  const offMic = isMicId(getOffId(player));
  const hasMic = hasAnyMic(player);
  const mode = getMode(player);

  if (mode !== state.mode) {
    if (mode === MODE_TOGGLE) {
      state.toggleLatched = !!(mainMic && !offMic);
      setLatch(player, state.toggleLatched);
    } else {
      state.toggleLatched = false;
      setLatch(player, false);
    }
    state.mode = mode;
  }

  if (hasMic && mode === MODE_TOGGLE && mainMic && !state.lastMainMic && !offMic) {
    state.toggleLatched = !state.toggleLatched;
    setLatch(player, state.toggleLatched);
    console.warn(
      `[VCMumbleItem/BP] TOGGLE_EDGE player=${player.name} latched=${state.toggleLatched}`
    );
  }

  if (!hasMic) {
    state.toggleLatched = false;
    setLatch(player, false);
  }

  const effective =
    hasMic && (offMic || (mode === MODE_HOLD ? mainMic : state.toggleLatched));

  if (effective !== state.effective) {
    state.effective = effective;
    console.warn(
      `[VCMumbleItem/BP] MIC_LOCAL player=${player.name} mic=${effective ? "ON" : "OFF"} mode=${mode}`
    );
  }

  replaceMicStatus(player, effective);
  publishMicState(player, effective);
  state.lastMainMic = mainMic;
}

function isOperator(player) {
  try {
    return player.playerPermissionLevel === PlayerPermissionLevel.Operator;
  } catch {
    return false;
  }
}

function readTaggedNumber(player, prefix, fallback) {
  try {
    for (const tag of player.getTags()) {
      if (!tag.startsWith(prefix)) continue;
      const value = Number.parseInt(tag.substring(prefix.length), 10);
      if (Number.isFinite(value) && value >= 1) return value;
    }
  } catch {}
  return fallback;
}

function hasTagPrefix(player, prefix) {
  try {
    for (const tag of player.getTags()) {
      if (tag.startsWith(prefix)) return true;
    }
  } catch {}
  return false;
}

function serverVoiceRangeSnapshot(player) {
  const available = hasTagPrefix(player, RANGE_VALUE_PREFIX);
  const value = readTaggedNumber(player, RANGE_VALUE_PREFIX, 0);
  const maximum = readTaggedNumber(player, RANGE_MAX_PREFIX, 0);
  return { available: available && value >= 1, value, maximum };
}

function currentVoiceRange(player) {
  const serverValue = readTaggedNumber(player, RANGE_VALUE_PREFIX, 0);
  if (serverValue >= 1) {
    player.setDynamicProperty(PROP_VOICE_RANGE, serverValue);
    return serverValue;
  }

  const stored = Number(player.getDynamicProperty(PROP_VOICE_RANGE));
  if (Number.isFinite(stored) && stored >= 1) return Math.floor(stored);

  const legacy = Number(player.getDynamicProperty(LEGACY_PROP_VOICE_RANGE));
  if (Number.isFinite(legacy) && legacy >= 1) return Math.floor(legacy);

  return DEFAULT_VOICE_RANGE;
}

function currentMaxRange(player) {
  return readTaggedNumber(player, RANGE_MAX_PREFIX, DEFAULT_MAX_RANGE);
}

function clearTagsByPrefix(player, prefix) {
  try {
    for (const tag of player.getTags()) {
      if (!tag.startsWith(prefix)) continue;
      try {
        player.removeTag(tag);
      } catch {}
    }
  } catch {}
}

function cleanupLegacyVoiceCraftBridgeTags(player) {
  const prefixes = [
    "voicecraft.vr.",
    "voicecraft.bind.",
    "voicecraft.mic.",
  ];
  for (const prefix of prefixes) clearTagsByPrefix(player, prefix);
}

function nextRangeRequestId() {
  rangeRequestSequence = (rangeRequestSequence + 1) % 1000000;
  return `r${system.currentTick}_${rangeRequestSequence}`;
}

function syncVoiceRangeFromServer(player) {
  const serverValue = readTaggedNumber(player, RANGE_VALUE_PREFIX, 0);
  if (serverValue >= 1) {
    player.setDynamicProperty(PROP_VOICE_RANGE, serverValue);
  }
}

function consumeVoiceRangeAck(player, requestId) {
  if (!requestId) return undefined;
  const prefix = RANGE_ACK_PREFIX + requestId + ".";

  try {
    for (const tag of player.getTags()) {
      if (!tag.startsWith(prefix)) continue;

      const payload = tag.slice(prefix.length);
      const separator = payload.indexOf(".");
      const status = separator >= 0 ? payload.slice(0, separator) : "";
      const rawValue = separator >= 0 ? payload.slice(separator + 1) : "";
      const value = Number.parseInt(rawValue, 10);

      try {
        player.removeTag(tag);
      } catch {}

      if (!Number.isFinite(value) || value < 1) {
        return { status: "error", value: 0 };
      }
      return { status, value };
    }
  } catch {}

  return undefined;
}

function requestVoiceRangeSync(player) {
  const requestId = nextRangeRequestId();
  clearTagsByPrefix(player, RANGE_ACK_PREFIX);
  clearTagsByPrefix(player, RANGE_SYNC_PREFIX);

  try {
    player.addTag(RANGE_SYNC_PREFIX + requestId);
    return requestId;
  } catch (e) {
    console.warn(
      `[VCMumbleItem/BP] voice range sync request failed player=${player.name}: ${e}`
    );
    return "";
  }
}

function requestVoiceRange(player, value) {
  value = Math.floor(Number(value));
  if (!Number.isFinite(value) || value < 1 || value > 30000000) {
    player.sendMessage("§c[VC Mumble] ระยะเสียงต้องเป็นจำนวนเต็มตั้งแต่ 1 ขึ้นไป§r");
    return "";
  }

  const maximum = currentMaxRange(player);
  if (!isOperator(player) && value > maximum) {
    player.sendMessage(
      `§c[VC Mumble] ระยะสูงสุดที่เซิร์ฟเวอร์กำหนดคือ ${maximum} บล็อก§r`
    );
    return "";
  }

  const requestId = nextRangeRequestId();
  clearTagsByPrefix(player, RANGE_REQUEST_PREFIX);
  clearTagsByPrefix(player, RANGE_ACK_PREFIX);
  clearTagsByPrefix(player, RANGE_SYNC_PREFIX);

  try {
    player.addTag(`${RANGE_REQUEST_PREFIX}${requestId}.${value}`);
    return requestId;
  } catch (e) {
    console.warn(
      `[VCMumbleItem/BP] voice range request failed player=${player.name}: ${e}`
    );
    return "";
  }
}


function readTaggedLevel(player, prefix, fallback) {
  try {
    for (const tag of player.getTags()) {
      if (!tag.startsWith(prefix)) continue;
      const value = Number.parseInt(tag.substring(prefix.length), 10);
      if (Number.isFinite(value) && value >= 0 && value <= 4) return value;
    }
  } catch {}
  return fallback;
}

function attenuationLabel(level) {
  switch (Math.max(0, Math.min(4, Number(level) || 0))) {
    case 0:
      return "ปิดการลดเสียง";
    case 1:
      return "เบา";
    case 3:
      return "แรง";
    case 4:
      return "แรงมาก";
    default:
      return "ปกติ";
  }
}

function currentAttenuationLevel(player) {
  return readTaggedLevel(player, ATTN_VALUE_PREFIX, 2);
}

function serverAttenuationSnapshot(player) {
  const available = hasTagPrefix(player, ATTN_VALUE_PREFIX);
  const value = readTaggedLevel(player, ATTN_VALUE_PREFIX, -1);
  return { available: available && value >= 0 && value <= 4, value };
}

function nextAttenuationRequestId() {
  attenuationRequestSequence = (attenuationRequestSequence + 1) % 1000000;
  return `a${system.currentTick}_${attenuationRequestSequence}`;
}

function consumeAttenuationAck(player, requestId) {
  if (!requestId) return undefined;
  const prefix = ATTN_ACK_PREFIX + requestId + ".";

  try {
    for (const tag of player.getTags()) {
      if (!tag.startsWith(prefix)) continue;

      const payload = tag.slice(prefix.length);
      const separator = payload.indexOf(".");
      const status = separator >= 0 ? payload.slice(0, separator) : "";
      const rawValue = separator >= 0 ? payload.slice(separator + 1) : "";
      const value = Number.parseInt(rawValue, 10);

      try {
        player.removeTag(tag);
      } catch {}

      if (!Number.isFinite(value) || value < 0 || value > 4) {
        return { status: "error", value: 2 };
      }
      return { status, value };
    }
  } catch {}

  return undefined;
}

function requestAttenuationSync(player) {
  const requestId = nextAttenuationRequestId();
  clearTagsByPrefix(player, ATTN_ACK_PREFIX);
  clearTagsByPrefix(player, ATTN_SYNC_PREFIX);

  try {
    player.addTag(ATTN_SYNC_PREFIX + requestId);
    return requestId;
  } catch (e) {
    console.warn(
      `[VCMumbleItem/BP] attenuation sync request failed player=${player.name}: ${e}`
    );
    return "";
  }
}

function requestAttenuation(player, level) {
  level = Math.floor(Number(level));
  if (!Number.isFinite(level) || level < 0 || level > 4) {
    player.sendMessage("§c[VC Mumble] ระดับเสียงตามระยะต้องอยู่ระหว่าง 0-4§r");
    return "";
  }

  const requestId = nextAttenuationRequestId();
  clearTagsByPrefix(player, ATTN_REQUEST_PREFIX);
  clearTagsByPrefix(player, ATTN_ACK_PREFIX);
  clearTagsByPrefix(player, ATTN_SYNC_PREFIX);

  try {
    player.addTag(`${ATTN_REQUEST_PREFIX}${requestId}.${level}`);
    return requestId;
  } catch (e) {
    console.warn(
      `[VCMumbleItem/BP] attenuation request failed player=${player.name}: ${e}`
    );
    return "";
  }
}

function modeUiLabel(mode) {
  return mode === MODE_TOGGLE ? "Toggle" : "Hold-to-Talk";
}

function applyMicModeFromUi(
  player,
  newMode,
  statusText,
  modeText,
  holdDisabled,
  toggleDisabled
) {
  setMode(player, newMode);

  if (newMode === MODE_TOGGLE) {
    const mainMic = isMicId(getMainId(player));
    const offMic = isMicId(getOffId(player));
    setLatch(player, !!(mainMic && !offMic));
  } else {
    setLatch(player, false);
  }

  states.delete(player.id);
  system.run(() => {
    try {
      evaluate(player);
      reassertMicFlags(player);
      const refreshed = stateFor(player);
      statusText.setData(
        `สถานะไมค์: ${refreshed.effective ? "§aON" : "§cOFF"}§r\n`
      );
      modeText.setData(`โหมด: §e${modeUiLabel(refreshed.mode)}§r\n`);
      holdDisabled.setData(refreshed.mode === MODE_HOLD);
      toggleDisabled.setData(refreshed.mode === MODE_TOGGLE);
    } catch (e) {
      console.warn(`[VCMumbleItem/BP] mode update failed player=${player.name}: ${e}`);
    }
  });
}

function spawnVoiceRangePreviewPoint(player, location) {
  try {
    // Player-targeted particles keep the visualization private.
    player.spawnParticle(VOICE_RANGE_PREVIEW_PARTICLE, location);
  } catch {
    // Large radii can touch unloaded chunks. Skip only those points.
  }
}

function showVoiceRangePreview(player, rawRadius) {
  const radius = Math.max(1, Math.floor(Number(rawRadius) || 1));

  let center;
  try {
    center = player.location;
  } catch {
    return;
  }

  // Dense wireframe volume. The refresh rate is unchanged; only the
  // number of points in each draw is increased so the dome reads as continuous.
  const equatorPoints = Math.max(
    VOICE_RANGE_PREVIEW_MIN_POINTS,
    Math.min(
      VOICE_RANGE_PREVIEW_MAX_POINTS,
      Math.ceil((Math.PI * 2 * radius) / 2)
    )
  );
  const centerY = center.y + 0.12;
  const latitudeDegrees = [-60, -30, 0, 30, 60];

  for (const latitudeDeg of latitudeDegrees) {
    const latitude = (latitudeDeg * Math.PI) / 180;
    const horizontalRadius = radius * Math.cos(latitude);
    const y = centerY + radius * Math.sin(latitude);
    const latitudePoints =
      latitudeDeg === 0
        ? equatorPoints
        : Math.max(
            36,
            Math.floor(equatorPoints * Math.max(0.5, Math.cos(latitude)))
          );

    for (let i = 0; i < latitudePoints; i++) {
      const angle = (Math.PI * 2 * i) / latitudePoints;
      spawnVoiceRangePreviewPoint(player, {
        x: center.x + Math.cos(angle) * horizontalRadius,
        y,
        z: center.z + Math.sin(angle) * horizontalRadius,
      });
    }
  }

  const meridianCount = 8;
  const meridianPoints = 25;
  for (let meridian = 0; meridian < meridianCount; meridian++) {
    const longitude = (Math.PI * meridian) / meridianCount;
    for (let step = 0; step < meridianPoints; step++) {
      const latitude =
        -Math.PI / 2 + (Math.PI * step) / (meridianPoints - 1);
      const horizontalRadius = radius * Math.cos(latitude);
      spawnVoiceRangePreviewPoint(player, {
        x: center.x + Math.cos(longitude) * horizontalRadius,
        y: centerY + Math.sin(latitude) * radius,
        z: center.z + Math.sin(longitude) * horizontalRadius,
      });
    }
  }
}

const openSettingsPlayers = new Set();
const openSettingsForms = new Map();

async function showSettings(player) {
  if (openSettingsPlayers.has(player.id)) {
    player.sendMessage("§e[VC Mumble] หน้าตั้งค่า Mic เปิดอยู่แล้ว§r");
    return;
  }

  openSettingsPlayers.add(player.id);
  let refreshId;

  try {
    ensureMic(player);
    evaluate(player);
    syncVoiceRangeFromServer(player);

    const initial = stateFor(player);
    const initialRange = currentVoiceRange(player);
    const initialMax = Math.max(1, currentMaxRange(player));
    const initialAttenuation = currentAttenuationLevel(player);

    const statusText = new ObservableString(
      `\nสถานะไมค์: ${initial.effective ? "§aON" : "§cOFF"}§r\n`
    );
    const modeText = new ObservableString(`โหมด: §e${modeUiLabel(initial.mode)}§r\n`);
    const rangeText = new ObservableString(
      `ระยะเสียงปัจจุบัน: §b${initialRange} บล็อก§r\n`
    );
    const rangeConfirmText = new ObservableString(
      "สถานะ Endstone: §eกำลังซิงก์ระยะเสียง...§r\n"
    );
    const attenuationText = new ObservableString(
      `เสียงตามระยะ: §d${attenuationLabel(initialAttenuation)} (ระดับ ${initialAttenuation})§r\n`
    );
    const attenuationConfirmText = new ObservableString(
      "สถานะ Distance Volume: §eกำลังซิงก์...§r\n"
    );
    const offhandText = new ObservableString(
      isMicId(getOffId(player))
        ? "มือซ้าย: §aMic อยู่มือซ้าย — บังคับ ON§r\n"
        : "มือซ้าย: §7ไม่มี Mic§r\n"
    );
    const serverLimitText = new ObservableString(
      isOperator(player)
        ? "สิทธิ์: §dOperator — Endstone อนุญาตสูงสุด 1000 บล็อก§r\n"
        : `ระยะสูงสุด: §b${initialMax} บล็อก§r\n`
    );

    const holdDisabled = new ObservableBoolean(initial.mode === MODE_HOLD);
    const toggleDisabled = new ObservableBoolean(initial.mode === MODE_TOGGLE);
    const advancedVisible = new ObservableBoolean(false, { clientWritable: true });
    const rangeSlider = new ObservableNumber(Math.min(initialRange, initialMax), {
      clientWritable: true,
    });
    const sliderMax = new ObservableNumber(initialMax);
    const customRange = new ObservableString(String(initialRange), {
      clientWritable: true,
    });
    let lastSliderRange = Math.floor(rangeSlider.getData());
    let queuedSliderRange = null;
    let sliderCommitDueTick = 0;

    let confirmedRange = initialRange;
    let pendingRange = null;
    let pendingRequestId = "";
    let pendingChecks = 0;
    let syncRequestId = requestVoiceRangeSync(player);
    let syncChecks = 0;
    let nextPeriodicSyncTick = system.currentTick + 100;

    let confirmedAttenuation = initialAttenuation;
    let pendingAttenuation = null;
    let pendingAttenuationRequestId = "";
    let pendingAttenuationChecks = 0;
    let attenuationSyncRequestId = requestAttenuationSync(player);
    let attenuationSyncChecks = 0;
    let nextAttenuationSyncTick = system.currentTick + 100;

    const submitRange = (rawValue) => {
      const value = Number.parseInt(String(rawValue ?? ""), 10);
      if (!Number.isFinite(value)) {
        rangeConfirmText.setData("สถานะ Endstone: §cกรุณาระบุระยะเป็นตัวเลข§r\n");
        return;
      }

      const cooldownSeconds = voiceRangeCooldownSeconds(player);
      if (cooldownSeconds > 0) {
        queuedSliderRange = value;
        sliderCommitDueTick = system.currentTick + 20;
        customRange.setData(String(value));
        rangeConfirmText.setData(
          `สถานะ Endstone: §eคูลดาวน์ ${cooldownSeconds} วิ • Preview ${value} บล็อก§r\n`
        );
        return;
      }

      const requestId = requestVoiceRange(player, value);
      if (!requestId) {
        rangeConfirmText.setData("สถานะ Endstone: §cส่งคำขอระยะเสียงไม่สำเร็จ§r\n");
        return;
      }

      pendingRange = value;
      pendingRequestId = requestId;
      pendingChecks = 0;
      syncRequestId = "";
      syncChecks = 0;
      customRange.setData(String(value));

      const maxNow = sliderMax.getData();
      if (value >= 1 && value <= maxNow) {
        lastSliderRange = value;
        rangeSlider.setData(value);
      }

      rangeConfirmText.setData(
        `สถานะ Endstone: §eกำลังรอยืนยัน ${value} บล็อก...§r\n`
      );
    };

    const submitQuickRange = (rawValue) => {
      const value = Math.floor(Number(rawValue));
      if (!Number.isFinite(value) || value < 1) return;

      lastSliderRange = value;
      rangeSlider.setData(value);
      showVoiceRangePreview(player, value);

      // Repeated taps on the same quick button must not create duplicate
      // requests. If a request is pending, retain only the newest value.
      if (value === pendingRange) return;
      if (!pendingRequestId && value === confirmedRange) {
        rangeConfirmText.setData(
          `สถานะ Endstone: §aใช้อยู่แล้ว — ${value} บล็อก§r\n`
        );
        return;
      }
      if (pendingRequestId) {
        queuedSliderRange = value;
        sliderCommitDueTick = system.currentTick;
        return;
      }

      queuedSliderRange = null;
      sliderCommitDueTick = 0;
      submitRange(value);
    };

    const submitAttenuation = (rawLevel) => {
      const level = Number.parseInt(String(rawLevel ?? ""), 10);
      if (!Number.isFinite(level) || level < 0 || level > 4) {
        attenuationConfirmText.setData(
          "สถานะ Distance Volume: §cระดับต้องอยู่ระหว่าง 0-4§r\n"
        );
        return;
      }

      const requestId = requestAttenuation(player, level);
      if (!requestId) {
        attenuationConfirmText.setData(
          "สถานะ Distance Volume: §cส่งคำขอไม่สำเร็จ§r\n"
        );
        return;
      }

      pendingAttenuation = level;
      pendingAttenuationRequestId = requestId;
      pendingAttenuationChecks = 0;
      attenuationSyncRequestId = "";
      attenuationSyncChecks = 0;
      attenuationConfirmText.setData(
        `สถานะ Distance Volume: §eกำลังรอยืนยัน ${attenuationLabel(level)}...§r\n`
      );
    };

    const form = new CustomForm(player, "VC Mumble • Mic Settings")
      .label(statusText)
      .spacer()
      .label(modeText)
      .spacer()
      .label(rangeText)
      .spacer()
      .label(offhandText)
      .spacer()
      .label(serverLimitText)
      .spacer()
      .divider()
      .header("Mic Mode")
      .label("Hold-to-Talk\nถือ Mic = เปิดเสียง\nเลิกถือ = ปิดเสียง\n")
      .button(
        "Hold-to-Talk",
        () =>
          applyMicModeFromUi(
            player,
            MODE_HOLD,
            statusText,
            modeText,
            holdDisabled,
            toggleDisabled
          ),
        { disabled: holdDisabled }
      )
      .spacer()
      .label("Toggle\nหยิบ Mic ขึ้นมาหนึ่งครั้งเพื่อสลับ ON/OFF\n")
      .button(
        "Toggle",
        () =>
          applyMicModeFromUi(
            player,
            MODE_TOGGLE,
            statusText,
            modeText,
            holdDisabled,
            toggleDisabled
          ),
        { disabled: toggleDisabled }
      )
      .spacer()
      .divider()
      .header("Voice Range")
      .label(rangeConfirmText)
      .spacer()
      .button("10 บล็อก", () => submitQuickRange(10))
      .button("20 บล็อก", () => submitQuickRange(20))
      .button("30 บล็อก", () => submitQuickRange(30))
      .spacer()
      .slider("ระยะเสียงแบบ Slider", rangeSlider, 1, sliderMax, {
        step: 1,
        description:
          "ลากเพื่อเปลี่ยนระยะทันที • โดม Preview จะเห็นเฉพาะตัวคุณเอง",
      })
      .spacer()
      .divider()
      .header("Reset")
      .label("คืน Mic Mode เป็น Hold-to-Talk\nVoice Range = 30 บล็อก\n")
      .button("คืนค่าเริ่มต้น", () => {
        const resetRange = isOperator(player) ? 30 : Math.min(30, sliderMax.getData());
        customRange.setData(String(resetRange));
        lastSliderRange = Math.min(resetRange, sliderMax.getData());
        rangeSlider.setData(lastSliderRange);
        applyMicModeFromUi(
          player,
          MODE_HOLD,
          statusText,
          modeText,
          holdDisabled,
          toggleDisabled
        );
        submitRange(resetRange);
      })
      .spacer()
      .closeButton();

    openSettingsForms.set(player.id, form);

    refreshId = system.runInterval(() => {
      try {
        const refreshed = stateFor(player);
        const nextMax = Math.max(1, currentMaxRange(player));

        statusText.setData(
          `สถานะไมค์: ${refreshed.effective ? "§aON" : "§cOFF"}§r\n`
        );
        modeText.setData(`โหมด: §e${modeUiLabel(refreshed.mode)}§r\n`);
        rangeText.setData(`ระยะเสียงปัจจุบัน: §b${confirmedRange} บล็อก§r\n`);
        attenuationText.setData(
          `เสียงตามระยะ: §d${attenuationLabel(confirmedAttenuation)} (ระดับ ${confirmedAttenuation})§r\n`
        );
        offhandText.setData(
          isMicId(getOffId(player))
            ? "มือซ้าย: §aMic อยู่มือซ้าย — บังคับ ON§r\n"
            : "มือซ้าย: §7ไม่มี Mic§r\n"
        );
        serverLimitText.setData(
          isOperator(player)
            ? `สิทธิ์: §dOperator — ระยะสูงสุด ${nextMax} บล็อก§r\n`
            : `ระยะสูงสุด: §b${nextMax} บล็อก§r\n`
        );
        holdDisabled.setData(refreshed.mode === MODE_HOLD);
        toggleDisabled.setData(refreshed.mode === MODE_TOGGLE);

        sliderMax.setData(nextMax);
        if (rangeSlider.getData() > nextMax && !isOperator(player)) {
          lastSliderRange = nextMax;
          rangeSlider.setData(nextMax);
        }

        const sliderValue = Math.max(
          1,
          Math.min(Math.floor(rangeSlider.getData()), nextMax)
        );
        if (sliderValue !== lastSliderRange) {
          // Preview stays realtime, but the authoritative Endstone update is
          // debounced so dragging cannot create a request/ACK + disk-write storm.
          lastSliderRange = sliderValue;
          showVoiceRangePreview(player, sliderValue);

          if (!pendingRequestId && sliderValue === confirmedRange) {
            queuedSliderRange = null;
            rangeConfirmText.setData(
              `สถานะ Endstone: §aใช้อยู่แล้ว — ${confirmedRange} บล็อก§r\n`
            );
          } else {
            queuedSliderRange = sliderValue;
            sliderCommitDueTick =
              system.currentTick + VOICE_RANGE_COMMIT_DEBOUNCE_TICKS;
          }
        }

        if (
          queuedSliderRange !== null &&
          !pendingRequestId &&
          system.currentTick >= sliderCommitDueTick
        ) {
          const valueToCommit = queuedSliderRange;
          queuedSliderRange = null;
          submitRange(valueToCommit);
        }

        if (pendingRequestId) {
          const ack = consumeVoiceRangeAck(player, pendingRequestId);
          const snapshot = serverVoiceRangeSnapshot(player);
          const implicitAck =
            !ack &&
            snapshot.available &&
            pendingRange !== null &&
            snapshot.value === pendingRange;

          if (ack || implicitAck) {
            const acceptedValue = ack?.value ?? snapshot.value;
            if (acceptedValue >= 1) {
              confirmedRange = acceptedValue;
              player.setDynamicProperty(PROP_VOICE_RANGE, confirmedRange);
              customRange.setData(String(confirmedRange));
              if (
                queuedSliderRange === null &&
                confirmedRange <= nextMax
              ) {
                lastSliderRange = confirmedRange;
                rangeSlider.setData(confirmedRange);
              }
            }

            if (implicitAck || (ack?.status === "ok" && acceptedValue === pendingRange)) {
              startVoiceRangeCooldown(player);
              rangeConfirmText.setData(
                `สถานะ Endstone: §aยืนยันแล้ว — ${acceptedValue} บล็อก • คูลดาวน์ 30 วิ${implicitAck ? " (server sync)" : ""}§r\n`
              );
            } else {
              rangeConfirmText.setData(
                `สถานะ Endstone: §cไม่รับค่าที่ขอ — ใช้ ${confirmedRange} บล็อก§r\n`
              );
            }

            pendingRange = null;
            pendingRequestId = "";
            pendingChecks = 0;
            nextPeriodicSyncTick = system.currentTick + 100;
          } else {
            pendingChecks++;
            if (pendingChecks >= 40) {
              if (snapshot.available) {
                rangeConfirmText.setData(
                  `สถานะ Endstone: §6ยังไม่ได้ ACK — server ยังรายงาน ${snapshot.value} บล็อก§r\n`
                );
              } else {
                rangeConfirmText.setData(
                  "สถานะ Endstone: §cไม่พบ range/ACK contract\n\nต้องใช้ VC Mumble Endstone v0.3.0+§r\n"
                );
              }
            }
          }
        } else {
          if (!syncRequestId && system.currentTick >= nextPeriodicSyncTick) {
            syncRequestId = requestVoiceRangeSync(player);
            syncChecks = 0;
            nextPeriodicSyncTick = system.currentTick + 100;
          }

          if (syncRequestId) {
            const syncAck = consumeVoiceRangeAck(player, syncRequestId);
            const syncSnapshot = serverVoiceRangeSnapshot(player);
            if (syncAck || syncSnapshot.available) {
              const syncValue = syncAck?.value ?? syncSnapshot.value;
              if (syncValue >= 1) {
                confirmedRange = syncValue;
                player.setDynamicProperty(PROP_VOICE_RANGE, confirmedRange);
                customRange.setData(String(confirmedRange));
                if (
                  queuedSliderRange === null &&
                  confirmedRange <= nextMax
                ) {
                  lastSliderRange = confirmedRange;
                  rangeSlider.setData(confirmedRange);
                }
              }
              rangeConfirmText.setData(
                `สถานะ Endstone: §aเชื่อมต่อแล้ว — ${confirmedRange} บล็อก§r\n`
              );
              syncRequestId = "";
              syncChecks = 0;
              nextPeriodicSyncTick = system.currentTick + 100;
            } else {
              syncChecks++;
              if (syncChecks >= 40) {
                rangeConfirmText.setData(
                  "สถานะ Endstone: §cไม่พบ v0.3.0 range contract\n\nตรวจสอบ/อัปเดต Endstone plugin§r\n"
                );
                syncRequestId = "";
                syncChecks = 0;
                nextPeriodicSyncTick = system.currentTick + 100;
              }
            }
          }
        }

        if (pendingAttenuationRequestId) {
          const ack = consumeAttenuationAck(player, pendingAttenuationRequestId);
          const snapshot = serverAttenuationSnapshot(player);
          const implicitAck =
            !ack &&
            snapshot.available &&
            pendingAttenuation !== null &&
            snapshot.value === pendingAttenuation;

          if (ack || implicitAck) {
            const acceptedLevel = ack?.value ?? snapshot.value;
            if (acceptedLevel >= 0 && acceptedLevel <= 4) {
              confirmedAttenuation = acceptedLevel;
            }

            if (
              implicitAck ||
              (ack?.status === "ok" && acceptedLevel === pendingAttenuation)
            ) {
              attenuationConfirmText.setData(
                `สถานะ Distance Volume: §aยืนยันแล้ว — ${attenuationLabel(acceptedLevel)} (ระดับ ${acceptedLevel})§r\n`
              );
            } else {
              attenuationConfirmText.setData(
                `สถานะ Distance Volume: §cไม่รับค่าที่ขอ — ใช้ ${attenuationLabel(confirmedAttenuation)}§r\n`
              );
            }

            pendingAttenuation = null;
            pendingAttenuationRequestId = "";
            pendingAttenuationChecks = 0;
            nextAttenuationSyncTick = system.currentTick + 100;
          } else {
            pendingAttenuationChecks++;
            if (pendingAttenuationChecks >= 40) {
              attenuationConfirmText.setData(
                snapshot.available
                  ? `สถานะ Distance Volume: §6ยังไม่ได้ ACK — server ยังรายงานระดับ ${snapshot.value}§r\n`
                  : "สถานะ Distance Volume: §cไม่พบ attenuation contract — ต้องใช้ VC Mumble Endstone v0.4.2+§r\n"
              );
            }
          }
        } else {
          if (
            !attenuationSyncRequestId &&
            system.currentTick >= nextAttenuationSyncTick
          ) {
            attenuationSyncRequestId = requestAttenuationSync(player);
            attenuationSyncChecks = 0;
            nextAttenuationSyncTick = system.currentTick + 100;
          }

          if (attenuationSyncRequestId) {
            const syncAck = consumeAttenuationAck(
              player,
              attenuationSyncRequestId
            );
            const snapshot = serverAttenuationSnapshot(player);

            if (syncAck || snapshot.available) {
              const syncLevel = syncAck?.value ?? snapshot.value;
              if (syncLevel >= 0 && syncLevel <= 4) {
                confirmedAttenuation = syncLevel;
              }
              attenuationConfirmText.setData(
                `สถานะ Distance Volume: §aเชื่อมต่อแล้ว — ${attenuationLabel(confirmedAttenuation)} (ระดับ ${confirmedAttenuation})§r\n`
              );
              attenuationSyncRequestId = "";
              attenuationSyncChecks = 0;
              nextAttenuationSyncTick = system.currentTick + 100;
            } else {
              attenuationSyncChecks++;
              if (attenuationSyncChecks >= 40) {
                attenuationConfirmText.setData(
                  "สถานะ Distance Volume: §cไม่พบ attenuation contract — ตรวจสอบ VC Mumble Endstone v0.4.2+§r\n"
                );
                attenuationSyncRequestId = "";
                attenuationSyncChecks = 0;
                nextAttenuationSyncTick = system.currentTick + 100;
              }
            }
          }
        }
      } catch (e) {
        console.warn(
          `[VCMumbleItem/BP] settings refresh failed player=${player.name}: ${e}`
        );
      }
    }, 5);

    await form.show();
  } catch (e) {
    console.warn(`[VCMumbleItem/BP] DDUI form failed player=${player.name}: ${e}`);
  } finally {
    if (refreshId !== undefined) system.clearRun(refreshId);
    openSettingsForms.delete(player.id);
    openSettingsPlayers.delete(player.id);
  }
}

function handleMicUse(player) {
  if (!player) return;
  system.run(() => showSettings(player));
}

system.beforeEvents.startup.subscribe((ev) => {
  ev.itemComponentRegistry.registerCustomComponent("vcmumble:open_settings", {
    onUse(arg) {
      handleMicUse(arg.source);
    },
  });
});

world.afterEvents.playerSpawn.subscribe((ev) => {
  const player = ev.player;
  system.run(() => {
    if (ev.initialSpawn === true) {
      cleanupLegacyVoiceCraftBridgeTags(player);
      migrateDynamicProperties(player);
      states.delete(player.id);
    }

    migrateLegacyItems(player);
    ensureMic(player);
    reassertMicFlags(player);
    syncVoiceRangeFromServer(player);
    states.delete(player.id);
    evaluate(player);

    console.warn(
      `[VCMumbleItem/BP] READY player=${player.name} mode=${getMode(player)} range=${currentVoiceRange(player)} micTag=${stateFor(player).effective ? "on" : "off"}`
    );
  });
});

world.afterEvents.playerLeave.subscribe((ev) => {
  states.delete(ev.playerId);
  openSettingsPlayers.delete(ev.playerId);
  openSettingsForms.delete(ev.playerId);
});

// One-tick evaluation keeps Hold-to-Talk responsive and immediately mirrors
// the state into vcmumble.mic.on/off for the Endstone plugin.
system.runInterval(() => {
  for (const player of world.getAllPlayers()) {
    try {
      evaluate(player);
    } catch (e) {
      console.warn(`[VCMumbleItem/BP] evaluate failed player=${player.name}: ${e}`);
    }
  }
}, 1);

system.runInterval(() => {
  for (const player of world.getAllPlayers()) {
    try {
      ensureMic(player);
      reassertMicFlags(player);
      syncVoiceRangeFromServer(player);
      publishMicState(player, stateFor(player).effective);
    } catch {}
  }
}, 40);

console.warn(
  "[VCMumbleItem/BP] Loaded PARTICLE-EXPERIMENT — dense green owner-only 3D Voice Range preview + 30s range cooldown"
);
