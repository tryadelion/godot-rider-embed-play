# Godot Extended Launch

IntelliJ-platform plugin (Rider, IDEA, PhpStorm, …) that runs a Godot 4 scene and shows it
**inside a tool window**, with mouse and keyboard forwarded to the game. No floating Godot window.

```
IDE                                   Godot process
┌──────────────────────┐   TCP :n     ┌─────────────────────────────┐
│ "Godot" tool window  │ ◄── frames ──│ gel_shim.gd (-s)            │
│  GelViewPanel        │ ── input ──► │  SubViewport ← your scene   │
│  (Swing, RGBA8)      │ ── resize ─► │  real OS window: 64×64,     │
└──────────────────────┘              │  borderless, unfocusable,   │
                                      │  click-through, in a corner │
                                      └─────────────────────────────┘
```

Godot renders exactly as usual (Vulkan/Metal, Forward+, compute shaders). Each frame is read back
from the GPU asynchronously (`RenderingDevice.texture_get_data_async`, Godot ≥ 4.4) and streamed as
raw RGBA8 over localhost. Latency ≈ 1–2 frames.

## Build

Requires a JDK 17+ to run Gradle (a JDK 21 toolchain is auto-provisioned for compilation).

```sh
# compile against an installed IDE instead of downloading Rider (~1.5 GB):
echo 'gel.localIde=/Users/you/Applications/Rider.app' > local.properties
./gradlew buildPlugin
# -> build/distributions/godot-extended-launch-<version>.zip
```

Install: *Settings → Plugins → ⚙ → Install Plugin from Disk…* → pick the zip.

## Use

1. **Run configuration** *Godot Scene*:
   - *Scene*: `res://demo/m4/foam_lab.tscn` (or a project-relative path)
   - *Project directory*: folder with `project.godot` (empty = IDE project root)
   - *Godot executable*: `godot` on `PATH`, or a full path
   - *Rendering driver*: e.g. `vulkan` → `--rendering-driver vulkan`
   - *Launcher script* (optional): a script that prepares things and ends with
     `exec godot --path . "$@"` — e.g. `tools/gpu_run.sh`. Put script-specific flags such as
     `--vulkan` into *Script args*.
2. Or right-click a `.tscn` → **Run**. Set defaults (launcher script, driver) once in
   *Run → Edit Configurations → Edit configuration templates → Godot Scene*; produced configs inherit them.
3. The *Godot* tool window opens with one tab per running scene. Click into it to send input;
   resize it and the viewport follows. *Stop* or closing the tab quits Godot. Console output is in
   the normal Run window.

## How the shim gets into the project

At launch the plugin writes `gel_shim.gd` to `<project>/.godot/gel/` (the `.godot` cache folder is
already git-ignored in every Godot project) and starts Godot with
`--resolution 64x64 -s res://.godot/gel/gel_shim.gd -- --gel-port=… --gel-scene=…`.
With `-s`, Godot skips the main scene but still registers autoloads, so the scene runs in its normal
environment, only inside a `SubViewport` instead of the root window.

## Input

Hover-based, no capture:

- pointer over the view: the view takes keyboard focus, mouse and keys go to the game;
- pointer leaves: focus returns to where it was (usually the editor), held keys are released;
- press a mouse button inside: that spot is the anchor. While any button is held, motion keeps going to
  the game even outside the view (orbit, drag). On release the cursor jumps back to the anchor.

The cursor is never hidden or locked, whatever `Input.mouse_mode` the game sets. The game's cursor shape
is mirrored.

On macOS the plugin starts Godot with `--embedded` (the windowless display server the Godot editor uses
for its Game view) plus `--remote-debug` pointing at a tiny debugger server in the plugin. Godot then has
no OS window and no Dock icon, and mouse capture requests never touch the real cursor. The debugger
server works in one of two modes, shown in the view's status bar:

- **Godot editor reachable** on the configured port (default 6007; in the editor enable
  *Debug → Keep Debug Server Open*): the plugin relays the game's debugger to the editor. The editor, and
  Rider's GDScript debugger attached to it, own breakpoints, stepping and variables. When the game stops,
  the view blurs the last frame and shows a pause overlay until it resumes. The editor's Game-view
  embedding messages are filtered out so the editor does not try to host the game. Rider only follows the
  editor's *first* debugger session, so no editor-launched game may be running at the same time.
- **No editor:** the game is told to skip `breakpoint` statements and not break on script errors, since
  nothing could resume a break. Errors still print to the Run console.

## Limitations

- Non-macOS: no embedded mode, so a game that captures the mouse still grabs the OS cursor.
- `Input.warp_mouse()` from the game is ignored.
- IME / dead keys: no. Physical key codes assume a US layout.
- `get_tree().current_scene` is `null` (the scene is not a child of root).
- Compatibility renderer (no `RenderingDevice`): falls back to synchronous readback automatically.

## Protocol

See the header of `src/main/resources/godot/gel_shim.gd`.
