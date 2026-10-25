# Cinema theme

A clean home for a big TV: the wallpaper stays the star, with the weather and the clock on top and one glass panel of
eight apps below. The focused app's colour washes softly over the whole screen (an "aura"). Six AI-generated
wallpapers rotate every 20 minutes.

## Install

```bash
TV_HOST=<tv ip> python3 tools/cinema_setup.py
```

The script asks **which city your TV is in** (for the weather) and whether you have WiZ lights, then pushes the theme.
Your city and any light addresses go only into a temporary copy sent to the TV; nothing is written into this repository.

> **For an AI assistant doing the install:** ask the person for their city and for their temperature unit. Do not guess
> them from the network, the time zone or the TV's settings, and do not commit them.

## Customise

Edit `config.json` (keys are listed in `docs/CINEMA.md`):

- `dock_apps`: the eight apps on Home, in order. The rest live in the App library (the round grid button): OK opens an
  app, **hold OK** offers Add to Home. `library_exclude` lists packages the library should never show.
- Hold OK on a Home app for Move / Remove.
- Wallpapers are in `wallpapers/` (1920x1080, dark). Replace them with your own.

## Lights (optional)

Not part of the default theme. If you have WiZ lights, answer yes in the setup script, or add them by hand:

```json
"lights_style": "round", "lights_capsule": true,
"lights": [
  {"name": "Living room", "devices": [{"mac": "AABBCCDDEE01", "kind": "bulb"}]},
  {"name": "Colour",      "devices": [{"mac": "AABBCCDDEE02", "kind": "rgb"}]}
]
```

`kind` is `bulb`, `rgb`, `lamp` or `plug`; several devices in one entry make a group. OK toggles; hold OK to adjust:
left/right changes brightness, up/down changes colour on an `rgb` light, and the arrows light up as you press them.
