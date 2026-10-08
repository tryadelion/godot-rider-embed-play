# Changelog

## 0.1.3 — 2026-10-08

- Replace a platform API scheduled for removal (game speed list renderer), so newer IDE builds no longer
  flag the plugin.

## 0.1.2 — 2026-09-29

- **Play again.** Once a game ends, whether by Stop, the Run window or the game quitting on its own,
  the Play Preview's Stop button turns into **Play** and lets you start that scene again from the preview.
- Running a configuration again reuses its stopped tab instead of adding a new one.
- **Screen shapes.** Preview at 21:9, 16:10, 16:9, 4:3, Standard Mobile (19.5:9) or Narrow Mobile (20:9),
  fitted to the panel's width and height. Phone screens can be turned sideways.
- **Screenshots** from the preview toolbar, at 1×, 2× or 4× the view's resolution. Saved as PNG and copied
  to the clipboard.
- **Mute / unmute** the game from the preview.
- **Game speed**: set the time scale from 0.1× to 4×.
- The preview's controls moved into a toolbar row, with status on its own line below.

## 0.1.1 — 2026-09-24

First release.

- Run Godot 4 scenes inside an IDE tool window, from a *Godot Scene* run configuration or a right-click
  on a `.tscn`.
- Full renderer (Forward+, Vulkan or Metal), viewport follows the panel and the project's stretch settings,
  sharp on HiDPI.
- Hover-based input with drag gestures that continue outside the panel.
- Windowless mode on macOS.
- ⚠️ Experimental: pause overlay while the Godot editor's debugger (and Rider through it) holds a breakpoint.
