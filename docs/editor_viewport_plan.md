# Editor view in Rider: plan

Status: agreed direction, not started. Written 2026-09-24.

Goal: show the Godot **editor's** scene viewport(s) inside Rider, with a live, editable scene hierarchy and
a properties panel. Rider's own Godot plugin only shows a read-only node list parsed from the `.tscn`
(visibility shown, nothing editable, no properties). This is a separate feature from the existing
"Godot Scene" run configuration, which streams a *running game*.

## Premise

- A Godot editor runs with the project open. It may be minimized or buried behind Rider.
  (Same assumption Rider's GDScript language server and debugger already make.)
- An addon in the project pairs that editor with Rider over a localhost socket.
- The editor does all the real work: rendering, gizmos, selection, undo/redo, saving.
  Rider shows its viewports and drives it.
- Rejected: a second, hidden Godot editor. Two editors on one project fight over the `.godot` cache and
  imports, and the editor opens popups and dialogs.

## Architecture

```
Rider (this plugin)                          Godot editor (addon: gel_editor)
┌───────────────────────────────┐   TCP    ┌──────────────────────────────────────┐
│ .tscn "Viewport" tab          │◄─frames──│ EditorPlugin                         │
│  1/2/3/4 viewports (split)    │──input──►│  reads 3D/2D editor SubViewports     │
│ Hierarchy tool window (live)  │◄─tree────│  EditorInterface / selection         │
│ Properties tool window        │──edits──►│  EditorUndoRedoManager               │
└───────────────────────────────┘          │ tiny keep-alive window (see below)   │
                                           └──────────────────────────────────────┘
```

- Reuses the run plugin's pieces: frame protocol and decoding, the Swing view, hover input, gesture anchor.
- The Rider side adds a "Viewport" editor tab next to the existing scene preview for `.tscn` files
  (a `FileEditorProvider` placed after the default editor). JetBrains' plugin is not modified.
- Pairing: the addon listens on a fixed, configurable localhost port and writes it to
  `<project>/.godot/gel/editor.port`. Rider reads that file to connect. Only one Rider connects at a time.

## Keeping the editor rendering while minimized

Checked in Godot 4.7.1's source:

- `Main::iteration` renders only if `DisplayServer::can_any_window_draw()` is true.
- On macOS a window counts as visible only if it is not minimized **and** its occlusion state is visible.
  A window fully covered by others, or on another Space, counts as not visible.
- So with the editor minimized or buried, nothing renders. The language server and our socket still work,
  since they run on the main loop, which keeps ticking.

Fix: the addon opens one extra tiny `Window` of its own that always stays visible.

1. **First choice, clear pixels:** borderless, always on top, click-through (mouse passthrough), unfocusable,
   a few pixels in a screen corner, per-pixel transparent (`transparent` + `transparent_bg`), drawing nothing.
   It needs `display/window/per_pixel_transparency/allowed`. To check: whether the editor honours that
   setting from the open project.
2. **Fallback:** a 1×1 px opaque window in the menu bar's color, same flags.

Not an option: a window with opacity 0 (`alphaValue = 0`). macOS may count it as invisible.

The prototype must first prove the stream keeps running with the main editor window minimized.

Second throttle: the editor redraws only when something changes (low-processor mode). While Rider is
watching, the addon forces redraws of the streamed viewports, or temporarily enables
"update continuously", and restores the user's setting on disconnect.

## Viewports

- The Godot 3D editor has the layouts 1, 2, 2-alt, 3, 3-alt and 4 viewports.
  `EditorInterface.get_editor_viewport_3d(0..3)` gives each viewport's `SubViewport`, and each has its own camera.
- Each visible viewport is streamed separately; Rider arranges them in split panes.
- The editor renders only viewports visible in its current layout. So the layout picked in Rider is applied
  to the editor as well, through the 3D editor's state (`viewport_mode`). This is internal, not public API:
  hacky but workable, and it needs checking on each Godot update.
- Each Rider pane's size is sent to the editor, which sizes that viewport to match (as the run plugin does).
- 2D scenes: `EditorInterface.get_editor_viewport_2d()`, a single viewport.
- Cost scales with total pixels, not viewport count. The stream-fps cap and HiDPI option carry over.

### Input

- Same hover model as the run plugin: pointer over a pane means that pane gets input. Pointer leaves, input
  stops. A held button anchors a gesture that may leave the pane, and the cursor returns to the anchor on release.
- Events are **not** pushed into the `SubViewport`. The editor's viewport logic (orbit, pan, select, gizmos)
  lives in the viewport's surface `Control`. Events are injected at that control's position on the editor
  window, so the editor handles them itself.
- Expected source of fiddly bugs: coordinate mapping, modifier keys, editor shortcuts that need the editor
  window focused.

## Hierarchy (Rider tool window)

- Live from the editor (`EditorInterface.get_edited_scene_root()`), not parsed from the `.tscn`. Instanced
  sub-scenes are included and marked.
- Interactions: select (synced both ways with the editor and the viewports), show/hide, rename, drag to
  reorder or reparent, add node, delete.
- Every change goes through `EditorUndoRedoManager`: Ctrl+Z works in Godot, the scene is marked unsaved,
  and saving writes the `.tscn` normally.
- Updates come from the editor's scene-tree signals, with a periodic full refresh as a safety net.

## Properties (Rider tool window)

Built natively in Swing from the selected node's `get_property_list()` (types, hints, usage flags, groups
and categories). Edits go through `EditorUndoRedoManager`. Values refresh a few times per second, so edits
made in Godot show up too.

- **Tier 1:** bool; int and float with range hints (sliders); String and multiline text; enums and flags;
  Vector2/3/4 (and integer variants); Color; NodePath; resource *path* (pick from the project); Transform
  (position, rotation and scale fields); groups and categories as in Godot's inspector.
- **Tier 2:** nested resources (materials, meshes, shapes) edited inline; arrays and dictionaries.
- **Out of scope:** curves, gradients, animation tracks, custom inspector plugins from other addons. Those
  show an "Open in Godot" button, which selects the node in the editor and raises its window.
- Rejected: streaming Godot's own Inspector dock as pixels. It would give full fidelity, but it only renders
  while the editor window is visible. Moving it into an off-screen view means moving editor internals, which
  breaks with updates and disturbs the user's layout.

## Build order

1. **Pairing and keep-alive.** Addon plus Rider connection, tiny window (clear pixels, then fallback), one
   streamed 3D viewport with orbit and select. Exit criterion: the stream keeps running with the editor
   minimized and with it buried behind a full-screen Rider.
2. **Live hierarchy** with two-way selection sync.
3. **Tier 1 properties.**
4. **Layouts:** 2, 3 and 4 viewports, plus the 2D viewport.
5. **Tier 2 properties.**

Rough size: step 1 as a working prototype is a few days. The rest is incremental on top.

## Risks and open questions

- Tiny window: does macOS count a fully clear window as visible for occlusion? (Fallback exists.)
- Does the editor honour the project's per-pixel-transparency setting for its own windows?
- App Nap or background throttling of a buried editor on macOS: not checked yet.
- The `viewport_mode` state key and other internal editor state may change between Godot versions.
- Input injection into the editor's viewport controls: exact mechanism to be settled in step 1.
- Two Rider windows or projects pairing with one editor: first come, first served; others are refused.
- The Godot editor's own Game view embedding and our run plugin must not interfere (the run plugin already
  filters `game_view:*` / `embed:*` debugger messages).
