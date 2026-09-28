# VC Mumble Unified MCSV Plugin

Endstone plugin ตัวเดียวสำหรับระบบ Minecraft Bedrock proximity voice บน MCSV

## Architecture

```text
Minecraft + Item Mic Addon
        |
        v
VC Mumble Endstone v0.5.0
        |
        +-- เปิด Mumble Server เองบน MCSV :18655
        +-- ส่ง player state ภายในเครื่องผ่าน UDP 127.0.0.1:47855
        +-- Mic ON/OFF จาก Item Mic
        +-- Voice Range จาก Item Mic
        +-- Dimension isolation
        +-- Distance attenuation level 3
```

ไม่มี Android/mobile bridge, ไม่มี shared secret และไม่ต้องติดตั้ง MumbleHost แยกอีกตัว

## Mumble

ค่าเริ่มต้น:

- Port: `18655`
- Max users: `20`
- Mumble username: ใช้ชื่อ Minecraft โดยตรง
- Proximity feed: `127.0.0.1:47855`
- Default Voice Range: `30`
- Max Voice Range: `150`
- Distance attenuation: level `3`

Mumble runtime ใช้ `mumble-server-vc` ที่มี VC proximity routing patch ภายในตัว server

## Item Mic integration

Plugin อ่าน scoreboard tags จาก addon:

- `vcmumble.mic.on`
- `vcmumble.mic.off`
- `vcmumble.vr.request.*`
- `vcmumble.vr.sync.*`

และตอบกลับ range ผ่าน:

- `vcmumble.vr.value.*`
- `vcmumble.vr.max.*`
- `vcmumble.vr.ack.*`

เสียงจะไม่ route เมื่อผู้พูด Mic OFF, อยู่คนละ dimension หรืออยู่นอก Voice Range

## Command

`/vcb` แสดงสถานะ Mumble Host, port, Mic, Voice Range, attenuation และจำนวนผู้เล่นที่กำลัง track

Operator สามารถ restart Mumble Host จากหน้าสถานะได้

## Config

```toml
[tracking]
interval_ticks = 2
position_epsilon = 0.05
rotation_epsilon = 1.0
heartbeat_seconds = 15

[mumble]
port = 18655
users = 20

[local_state]
host = "127.0.0.1"
port = 47855
max_queue = 4096

[voice]
default_range = 30
max_range = 150
default_attenuation_level = 3
```

`local_state.host` ถูกบังคับเป็น localhost ใน unified MCSV mode เพื่อไม่เปิด state feed ออกสู่ภายนอก
