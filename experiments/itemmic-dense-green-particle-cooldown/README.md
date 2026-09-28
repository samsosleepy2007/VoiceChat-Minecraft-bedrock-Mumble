# Dense green particle Voice Range experiment

- Test-only snapshot. Do not merge to main until in-game approval.
- Restores player-targeted particle preview; no attachable/geo and no player.json.
- Preview refresh interval remains 5 ticks.
- Density: 5 latitude rings (-60,-30,0,30,60), up to 96 points per ring, 8 meridians x 25 points.
- At ~30 blocks, one preview draw is roughly 550 particle points instead of ~100-140.
- Particle dot texture is bright green.
- Authoritative range changes have a 30-second cooldown after a successful Endstone ACK.
- Slider preview remains realtime during cooldown; latest queued value applies when cooldown expires while the DDUI remains open.
- MCSV manifests are temporarily versioned 2.10.1 EXP only to bust Bedrock client resource-pack cache.
