# Cinema additions

Install the ready-made look with `themes/cinema` (see its README). Extra opt-in keys for a theme's `config.json`. Everything is off by default, so existing themes do not change.

| Key | Type | What it does |
|---|---|---|
| `weather_lat`, `weather_lon`, `weather_city`, `weather_unit` | numbers, string, `"c"`/`"f"` | An "at a glance" weather line (Open-Meteo, at most one request every 20 min, only while Home is shown). Needs the `INTERNET` permission, which this fork adds. |
| `weather_card` | bool | Weather as a glass card (`true`, default) or plain text (`false`). |
| `lights` | list | WiZ lights, plugs and groups as buttons, found by MAC on the local network (UDP 38899, no cloud). OK toggles; hold OK to dim with left/right and change colour with up/down on a colour bulb. |
| `lights_style` | `"tiles"`/`"round"` | A panel of tiles beside the grid, or round buttons under the header. |
| `lights_capsule`, `lights_scale_percent`, `lights_width_percent` | bool, 70-180, 15-45 | Look and size of the lights. |
| `glass_panel` | bool | A frosted-glass panel behind the app grid. |
| `grid_width_percent`, `grid_top_percent`, `tile_scale_percent` | 40-100, 0-70, 40-130 | Where and how big the app grid is. |
| `glow_style` | `"aura"` | New value: a wide halo of the focused app's colour over the screen (besides `radial` and `horizon`). |
| `ambient_strength` | 10-100 | How strongly the focused app's colour tints the screen. |
| `library_grid`, `library_exclude`, `show_library_pill` | bool, list, bool | The App library as a grid with an opaque panel (OK opens, hold OK offers Add to Home), minus the listed packages. |

The launcher also has a small easter egg: Up ten times within five seconds, and a mascot hops over the tiles
(`res/drawable-nodpi/mascot.png`; replace it with your own image).
