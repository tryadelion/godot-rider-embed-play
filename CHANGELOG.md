# Changelog

## 0.1.2 — 2026-09-28

- **Play again.** Once a game ends, whether by Stop, the Run window or the game quitting on its own,
  the Play Preview's Stop button turns into **Play** and starts the scene again.
- Running a configuration again reuses its stopped tab instead of adding a new one.
- Stop and Play buttons now show icons.

## 0.1.1 — 2026-09-24

First release.

- Run Godot 4 scenes inside an IDE tool window, from a *Godot Scene* run configuration or a right-click
  on a `.tscn`.
- Full renderer (Forward+, Vulkan or Metal), viewport follows the panel and the project's stretch settings,
  sharp on HiDPI.
- Hover-based input with drag gestures that continue outside the panel.
- Windowless mode on macOS.
- ⚠️ Experimental: pause overlay while the Godot editor's debugger (and Rider through it) holds a breakpoint.
