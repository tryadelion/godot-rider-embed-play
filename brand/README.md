# Brand assets

Sleepyfant Software logo for Godot Embed Play, transparent background.

| File | Use |
|---|---|
| `sleepyfant-godot-logo-1024.png` | Master logo, light backgrounds. Source of `pluginIcon.svg` and `docs/images/logo.png` |
| `sleepyfant-godot-logo-dark-1024.png` | Same logo with a lighter gear for dark backgrounds. Source of `pluginIcon_dark.svg` |
| `toolwindow_icon.py` | Generates the monochrome tool window / run configuration icons in `src/main/resources/icons` |

Cleanup applied to the original artwork: stray hairlines between gear and body and faint dust specks
removed; nothing else changed.

The logo is based on the Godot logo by Andrea Calabró (CC BY 4.0). See `NOTICE`.

## Regenerating icons

```sh
# plugin icon (40x40 SVG wrapping a 256 px PNG), light and dark
python3 - <<'PY'
from PIL import Image
for src, dst in [("brand/sleepyfant-godot-logo-1024.png", "/tmp/icon_light.png"),
                 ("brand/sleepyfant-godot-logo-dark-1024.png", "/tmp/icon_dark.png")]:
    Image.open(src).resize((256, 256), Image.LANCZOS).save(dst, optimize=True)
PY
# then embed each PNG as base64 in META-INF/pluginIcon.svg / pluginIcon_dark.svg (<image href="data:image/png;base64,...">)

# monochrome icons: python3 brand/toolwindow_icon.py <out.svg> <color> <size> right
#   light #6C707E, dark #CED0D6; sizes: 13 classic, 16 compact/run config, 20 new UI (@20x20)
```
