# VC Mumble Endstone — Unified MCSV mode

ตั้งแต่ v0.5.0 ระบบ MCSV ไม่ใช้ Android/mobile bridge แล้ว

## Topology

```text
Item Mic Addon
    |
    v
VC Mumble Endstone v0.5.0
    |
    +-- tracks Minecraft position / dimension / Mic / Voice Range
    |
    +-- local UDP state feed 127.0.0.1:47855
    |
    v
mumble-server-vc
    |
    +-- TCP/UDP 18655 สำหรับ Mumble clients
    +-- proximity routing
    +-- distance attenuation
```

ไม่มี TCP bridge `:27220`, ไม่มี HMAC/shared secret และไม่ต้องใช้แอป VC Mumble Server สำหรับโหมด MCSV นี้

## Player state

Plugin ส่ง state ภายในเครื่อง:

```json
{
  "type": "player_state",
  "name": "MinecraftName",
  "xuid": "...",
  "uuid": "...",
  "mumbleName": "MinecraftName",
  "dimension": "Overworld",
  "x": 10.0,
  "y": 64.0,
  "z": 20.0,
  "yaw": 90.0,
  "pitch": 0.0,
  "voiceRange": 30,
  "voiceEnabled": true,
  "attenuationLevel": 3
}
```

ชื่อ Mumble ใช้ชื่อ Minecraft โดยตรง

## Routing rules

Native Mumble server จะไม่ route เสียงเมื่อ:

- ผู้พูด Mic OFF
- ผู้ฟังอยู่คนละ dimension
- ระยะเกิน Voice Range ของผู้พูด
- state ของผู้เล่นหมดอายุ

ภายในระยะจะใช้ smooth attenuation ตามระดับที่ server กำหนด โดย default = level 3

## Item Mic

Mic ON/OFF มาจาก:

- `vcmumble.mic.on`
- `vcmumble.mic.off`

Voice Range ใช้ request/ACK contract เดิม:

- `vcmumble.vr.request.*`
- `vcmumble.vr.sync.*`
- `vcmumble.vr.value.*`
- `vcmumble.vr.max.*`
- `vcmumble.vr.ack.*`

ดังนั้น Item Mic addon เดิมยังใช้กับ plugin unified ได้โดยไม่ต้องเปลี่ยน protocol

## Ports

- Bedrock: ตาม MCSV allocation
- Mumble: `18655` TCP/UDP
- Local proximity feed: `47855` UDP localhost only

`:27220` ไม่ถูกใช้ใน unified MCSV mode
