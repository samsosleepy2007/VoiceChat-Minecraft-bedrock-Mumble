# Owner-only attachable Voice Range preview experiment

- Experimental branch only; do not merge to main until client testing passes.
- No `player.json` changes.
- Uses Mic item durability as a hidden range transport channel (`max_durability=152`, `unbreakable=true`).
- Attachable scale: `query.is_local_player * max(0, (query.remaining_durability - 1) / 30)`.
- Geometry: green wireframe sphere, 5 latitude rings + 8 meridians, 624 geo cubes.
- Preview is enabled while DDUI is open and hidden when the form closes.
- Particle preview is disabled in this experiment so the attachable result can be judged independently.

Green texture bytes are stored in `vcmumble_range_green.png.b64`.
