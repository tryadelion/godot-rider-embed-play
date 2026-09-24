extends SceneTree
## Godot Embed Play shim.
##
## Started by the IDE plugin with:
##   godot --path <project> [--rendering-driver X] --resolution 64x64 \
##         -s res://.godot/gel/gel_shim.gd -- --gel-port=N --gel-scene=res://x.tscn
##         [--gel-w=W --gel-h=H --gel-sync=1 --gel-fps=60]
##
## What it does:
##   * shrinks the real OS window to a tiny, borderless, unfocusable, click-through square
##     (Vulkan/Metal still need a surface, so the window cannot be removed entirely);
##   * loads the scene into a SubViewport whose size follows the IDE panel;
##   * streams every rendered frame as raw RGBA8 to the IDE over a localhost TCP socket;
##   * turns input messages from the IDE into Godot InputEvents.
##
## Wire protocol (little-endian). IDE -> Godot:
##   0x01 RESIZE       i32 w, i32 h
##   0x02 MOUSE_MOVE   f32 x, f32 y, f32 rel_x, f32 rel_y, u8 button_mask, u8 mods
##   0x03 MOUSE_BUTTON f32 x, f32 y, u8 button, u8 pressed, u8 double, u8 mods
##   0x04 WHEEL        f32 x, f32 y, f32 dx, f32 dy, u8 mods
##   0x05 KEY          i32 java_vk, i32 unicode, u8 pressed, u8 echo, u8 mods, u8 location
##   0x06 FOCUS        u8 focused
##   0x07 ACK          (one frame consumed)
##   0x08 QUIT
## Godot -> IDE:
##   0x81 FRAME        i32 w, i32 h, u8 format(0 = RGBA8), f32 game_fps, i32 len, bytes
##   0x82 HELLO        u16 len, utf8 text
##   0x83 MOUSE_MODE   u8 Input.MouseMode (sent on change; the IDE emulates capture/hide/confine)
## mods bits: 1 shift, 2 ctrl, 4 alt, 8 meta.  button: 1 left, 2 right, 3 middle (Godot numbering).

const MSG_RESIZE := 0x01
const MSG_MOUSE_MOVE := 0x02
const MSG_MOUSE_BUTTON := 0x03
const MSG_WHEEL := 0x04
const MSG_KEY := 0x05
const MSG_FOCUS := 0x06
const MSG_ACK := 0x07
const MSG_QUIT := 0x08
const MSG_FRAME := 0x81
const MSG_HELLO := 0x82
const MSG_MOUSE_MODE := 0x83

const MAX_IN_FLIGHT := 2

var _port := 0
var _scene_path := ""
var _width := 1280
var _height := 720
var _force_sync := false
var _stream_fps := 60.0  # capture cap; the game itself keeps running uncapped
const HOST_SIZE := Vector2i(64, 64)

var _peer: StreamPeerTCP
var _connected := false
var _svp: SubViewport
var _scene: Node
var _rd: RenderingDevice
var _in_flight := 0
var _button_mask := 0
var _scene_ready := false
var _hello_sent := false
# Async readback results; the callback may run off the main thread, so hand them over under a lock.
var _ready_mutex := Mutex.new()
var _ready_frames: Array = []
# Bumped on every resize: async readbacks of the old texture may never call back.
var _gen := 0
var _last_capture_us := 0
var _last_request_us := 0
var _host_pos := Vector2i.ZERO
# --embedded (macOS): no OS window exists, so there is nothing to hide.
var _embedded := false
var _sent_mouse_mode := -1
# Project stretch settings, emulated on the SubViewport (it has no stretch of its own).
var _stretch_mode := "disabled"
var _stretch_aspect := "keep"
var _base_size := Vector2i(1152, 648)
var _stretch_scale := 1.0


func _initialize() -> void:
	_parse_args()
	if _port <= 0 or _scene_path.is_empty():
		push_error("[gel] need --gel-port and --gel-scene")
		quit(2)
		return
	_embedded = DisplayServer.get_name() == "embedded"
	if not _embedded:
		_hide_window()
	_rd = RenderingServer.get_rendering_device()
	if _rd == null:
		_force_sync = true
	# Autoloads are added after _initialize() returns; the scene may depend on them,
	# so build the viewport + scene one frame later.
	var driver := Driver.new()
	driver.name = "GelDriver"
	driver.shim = self
	root.add_child(driver)
	_peer = StreamPeerTCP.new()
	_peer.big_endian = false
	var err := _peer.connect_to_host("127.0.0.1", _port)
	if err != OK:
		push_error("[gel] connect_to_host failed: %d" % err)
		quit(3)


func _parse_args() -> void:
	for arg in OS.get_cmdline_user_args():
		if arg.begins_with("--gel-port="):
			_port = int(arg.get_slice("=", 1))
		elif arg.begins_with("--gel-scene="):
			_scene_path = arg.get_slice("=", 1)
		elif arg.begins_with("--gel-w="):
			_width = maxi(1, int(arg.get_slice("=", 1)))
		elif arg.begins_with("--gel-h="):
			_height = maxi(1, int(arg.get_slice("=", 1)))
		elif arg.begins_with("--gel-sync="):
			_force_sync = arg.get_slice("=", 1) == "1"
		elif arg.begins_with("--gel-fps="):
			_stream_fps = maxf(0.0, float(arg.get_slice("=", 1)))


func _hide_window() -> void:
	var win: Window = root
	win.title = "Godot Embed Play (hidden host window)"
	# Projects may start maximized/fullscreen; size and position are ignored until windowed.
	win.mode = Window.MODE_WINDOWED
	win.borderless = true
	win.unfocusable = true
	win.always_on_top = false
	win.min_size = Vector2i.ZERO
	win.max_size = HOST_SIZE
	win.size = HOST_SIZE
	# Root stretch would only scale the (empty) host window; the scene's stretch is emulated below.
	win.content_scale_mode = Window.CONTENT_SCALE_MODE_DISABLED
	var sid := win.current_screen
	var spos := DisplayServer.screen_get_position(sid)
	var ssize := DisplayServer.screen_get_size(sid)
	_host_pos = spos + ssize - Vector2i(4, 4)
	win.position = _host_pos
	# A polygon entirely outside the window: every real mouse event falls through.
	win.mouse_passthrough_polygon = PackedVector2Array([
		Vector2(-20, -20), Vector2(-19, -20), Vector2(-20, -19)])
	# The root viewport only hosts the SubViewport; do not spend GPU on it.
	root.disable_3d = true
	root.gui_disable_input = true


## Scenes can still grab the root window (get_window().mode = ..., fullscreen toggles).
## Put it back every frame; cheap when nothing changed.
func _enforce_hidden() -> void:
	if _embedded:
		return
	var win: Window = root
	if win.mode != Window.MODE_WINDOWED:
		win.mode = Window.MODE_WINDOWED
	if win.size != HOST_SIZE:
		win.size = HOST_SIZE
	if win.position != _host_pos:
		win.position = _host_pos
	if not win.borderless:
		win.borderless = true


func _read_stretch_settings() -> void:
	_stretch_mode = str(ProjectSettings.get_setting("display/window/stretch/mode", "disabled"))
	_stretch_aspect = str(ProjectSettings.get_setting("display/window/stretch/aspect", "keep"))
	_base_size = Vector2i(
		int(ProjectSettings.get_setting("display/window/size/viewport_width", 1152)),
		int(ProjectSettings.get_setting("display/window/size/viewport_height", 648)))
	_stretch_scale = float(ProjectSettings.get_setting("display/window/stretch/scale", 1.0))


## Mirror Window stretch for a SubViewport of size _width x _height.
## canvas_items: render at full size, 2D laid out in base-size units (size_2d_override).
## viewport: render at base size; the IDE scales the image.
func _apply_size() -> void:
	if _svp == null:
		return
	var target := Vector2i(_width, _height)
	if _stretch_mode == "disabled" or _base_size.x <= 0 or _base_size.y <= 0:
		_svp.size = target
		_svp.size_2d_override = Vector2i.ZERO
		_svp.size_2d_override_stretch = false
		return
	if _stretch_aspect == "keep":
		# Letterbox: render only the base-aspect rect; the IDE centers it with bars.
		var fit := minf(float(target.x) / _base_size.x, float(target.y) / _base_size.y)
		target = Vector2i(maxi(1, roundi(_base_size.x * fit)), maxi(1, roundi(_base_size.y * fit)))
	var sx := float(target.x) / _base_size.x
	var sy := float(target.y) / _base_size.y
	var factor := minf(sx, sy)
	if _stretch_aspect == "ignore":
		factor = 0.0
	var logical := Vector2(_base_size)
	match _stretch_aspect:
		"expand":
			logical = Vector2(target) / factor
		"keep_width":
			logical = Vector2(_base_size.x, target.y / sx)
			factor = sx
		"keep_height":
			logical = Vector2(target.x / sy, _base_size.y)
			factor = sy
		"ignore":
			logical = Vector2(_base_size)
	logical /= maxf(_stretch_scale, 0.01)
	var logical_i := Vector2i(maxi(1, roundi(logical.x)), maxi(1, roundi(logical.y)))
	if _stretch_mode == "viewport":
		_svp.size = logical_i
		_svp.size_2d_override = Vector2i.ZERO
		_svp.size_2d_override_stretch = false
	else:
		_svp.size = target
		_svp.size_2d_override = logical_i
		_svp.size_2d_override_stretch = true


func _setup_scene() -> void:
	_svp = SubViewport.new()
	_svp.name = "GelViewport"
	_read_stretch_settings()
	_apply_size()
	_svp.render_target_update_mode = SubViewport.UPDATE_ALWAYS
	_svp.handle_input_locally = true
	_svp.physics_object_picking = true
	_svp.audio_listener_enable_3d = true
	_svp.audio_listener_enable_2d = true
	root.add_child(_svp)

	var packed := load(_scene_path) as PackedScene
	if packed == null:
		push_error("[gel] cannot load scene: %s" % _scene_path)
		quit(4)
		return
	_scene = packed.instantiate()
	_svp.add_child(_scene)
	_scene_ready = true
	print("[gel] display server %s" % DisplayServer.get_name())
	print("[gel] scene %s, viewport %s, stretch %s/%s base %s, stream cap %s fps" % [
		_scene_path, _svp.size, _stretch_mode, _stretch_aspect, _base_size,
		"none" if _stream_fps <= 0.0 else str(_stream_fps)])


func _tick() -> void:
	if _peer == null:
		return
	_peer.poll()
	var status := _peer.get_status()
	if status == StreamPeerTCP.STATUS_CONNECTING:
		return
	if status != StreamPeerTCP.STATUS_CONNECTED:
		print("[gel] IDE disconnected, quitting")
		_peer = null
		quit()
		return
	if not _hello_sent:
		_hello_sent = true
		_send_hello()
	if not _scene_ready:
		_setup_scene()
		if not _scene_ready:
			return
	_enforce_hidden()
	_sync_mouse_mode()
	_read_messages()
	if _peer == null:
		return
	_flush_ready_frames()
	_maybe_capture()


# ---------------------------------------------------------------- outgoing

func _send_hello() -> void:
	var info := Engine.get_version_info()
	var text := "Godot %s pid %d driver %s" % [
		info.get("string", "?"), OS.get_process_id(), RenderingServer.get_current_rendering_driver_name()]
	var bytes := text.to_utf8_buffer()
	var buf := StreamPeerBuffer.new()
	buf.big_endian = false
	buf.put_u8(MSG_HELLO)
	buf.put_u16(bytes.size())
	buf.put_data(bytes)
	_peer.put_data(buf.data_array)


func _sync_mouse_mode() -> void:
	var mode := int(Input.mouse_mode)
	if mode == _sent_mouse_mode:
		return
	_sent_mouse_mode = mode
	var buf := StreamPeerBuffer.new()
	buf.put_u8(MSG_MOUSE_MODE)
	buf.put_u8(mode)
	_peer.put_data(buf.data_array)


func _maybe_capture() -> void:
	if _svp == null:
		return
	var now := Time.get_ticks_usec()
	# Watchdog: a readback that never called back (texture freed, device lost) must not stall the stream.
	if _in_flight > 0 and now - _last_request_us > 500_000:
		_in_flight = 0
		_gen += 1
	if _in_flight >= MAX_IN_FLIGHT:
		return
	if _stream_fps > 0.0 and now - _last_capture_us < int(1_000_000.0 / _stream_fps):
		return
	_last_capture_us = now
	_last_request_us = now
	var tex := _svp.get_texture()
	if tex == null:
		return
	if _force_sync:
		_capture_sync(tex)
		return
	var rd_rid := RenderingServer.texture_get_rd_texture(tex.get_rid())
	if not rd_rid.is_valid():
		return
	var fmt := _rd.texture_get_format(rd_rid)
	if fmt.format != RenderingDevice.DATA_FORMAT_R8G8B8A8_UNORM \
			and fmt.format != RenderingDevice.DATA_FORMAT_R8G8B8A8_SRGB:
		print("[gel] viewport texture format %d not RGBA8, falling back to sync readback" % fmt.format)
		_force_sync = true
		return
	_in_flight += 1
	var err := _rd.texture_get_data_async(rd_rid, 0, _on_async_data.bind(fmt.width, fmt.height, _gen))
	if err != OK:
		_in_flight -= 1
		print("[gel] texture_get_data_async failed (%d), falling back to sync readback" % err)
		_force_sync = true


func _capture_sync(tex: ViewportTexture) -> void:
	var img := tex.get_image()
	if img == null:
		return
	if img.get_format() != Image.FORMAT_RGBA8:
		img.convert(Image.FORMAT_RGBA8)
	_in_flight += 1
	_send_frame(img.get_width(), img.get_height(), img.get_data())


func _on_async_data(data: PackedByteArray, w: int, h: int, gen: int) -> void:
	# May arrive from the render thread; _tick() sends it on the main thread.
	_ready_mutex.lock()
	_ready_frames.append([w, h, data, gen])
	_ready_mutex.unlock()


func _flush_ready_frames() -> void:
	_ready_mutex.lock()
	var frames := _ready_frames
	_ready_frames = []
	_ready_mutex.unlock()
	for f: Array in frames:
		if f[3] != _gen:
			continue  # stale: from before a resize/watchdog reset, already written off
		_send_frame(f[0], f[1], f[2])


func _send_frame(w: int, h: int, data: PackedByteArray) -> void:
	if _peer == null or _peer.get_status() != StreamPeerTCP.STATUS_CONNECTED:
		return
	if data.size() != w * h * 4:
		_in_flight = maxi(0, _in_flight - 1)
		return
	var hdr := StreamPeerBuffer.new()
	hdr.big_endian = false
	hdr.put_u8(MSG_FRAME)
	hdr.put_32(w)
	hdr.put_32(h)
	hdr.put_u8(0)
	hdr.put_float(Engine.get_frames_per_second())
	hdr.put_32(data.size())
	_peer.put_data(hdr.data_array)
	_peer.put_data(data)


# ---------------------------------------------------------------- incoming

func _read_messages() -> void:
	while _peer != null and _peer.get_status() == StreamPeerTCP.STATUS_CONNECTED \
			and _peer.get_available_bytes() >= 1:
		var t := _peer.get_u8()
		match t:
			MSG_RESIZE:
				var w := _peer.get_32()
				var h := _peer.get_32()
				_resize(w, h)
			MSG_MOUSE_MOVE:
				var x := _peer.get_float()
				var y := _peer.get_float()
				var rx := _peer.get_float()
				var ry := _peer.get_float()
				var mask := _peer.get_u8()
				var mods := _peer.get_u8()
				_mouse_motion(x, y, rx, ry, mask, mods)
			MSG_MOUSE_BUTTON:
				var x := _peer.get_float()
				var y := _peer.get_float()
				var button := _peer.get_u8()
				var pressed := _peer.get_u8() != 0
				var dbl := _peer.get_u8() != 0
				var mods := _peer.get_u8()
				_mouse_button(x, y, button, pressed, dbl, mods)
			MSG_WHEEL:
				var x := _peer.get_float()
				var y := _peer.get_float()
				var dx := _peer.get_float()
				var dy := _peer.get_float()
				var mods := _peer.get_u8()
				_wheel(x, y, dx, dy, mods)
			MSG_KEY:
				var vk := _peer.get_32()
				var unicode := _peer.get_32()
				var pressed := _peer.get_u8() != 0
				var echo := _peer.get_u8() != 0
				var mods := _peer.get_u8()
				var location := _peer.get_u8()
				_key(vk, unicode, pressed, echo, mods, location)
			MSG_FOCUS:
				var focused := _peer.get_u8() != 0
				_focus(focused)
			MSG_ACK:
				_in_flight = maxi(0, _in_flight - 1)
			MSG_QUIT:
				print("[gel] quit requested by IDE")
				quit()
				return
			_:
				push_error("[gel] unknown message type %d, closing" % t)
				_peer.disconnect_from_host()
				quit(5)
				return


func _resize(w: int, h: int) -> void:
	w = clampi(w, 1, 16384)
	h = clampi(h, 1, 16384)
	if w == _width and h == _height:
		return
	_width = w
	_height = h
	_gen += 1
	_in_flight = 0
	_apply_size()


func _apply_mods(ev: InputEventWithModifiers, mods: int) -> void:
	ev.shift_pressed = (mods & 1) != 0
	ev.ctrl_pressed = (mods & 2) != 0
	ev.alt_pressed = (mods & 4) != 0
	ev.meta_pressed = (mods & 8) != 0


func _dispatch(ev: InputEvent) -> void:
	# Input singleton: action state, is_key_pressed, mouse button mask.
	Input.parse_input_event(ev)
	# SubViewport: _input/_gui_input/_unhandled_input of the scene's nodes.
	if _svp != null:
		_svp.push_input(ev)


func _mouse_motion(x: float, y: float, rx: float, ry: float, mask: int, mods: int) -> void:
	var ev := InputEventMouseMotion.new()
	ev.position = Vector2(x, y)
	ev.global_position = ev.position
	ev.relative = Vector2(rx, ry)
	ev.screen_relative = ev.relative
	ev.velocity = ev.relative * Engine.get_frames_per_second()
	ev.screen_velocity = ev.velocity
	ev.button_mask = mask
	_button_mask = mask
	_apply_mods(ev, mods)
	_dispatch(ev)


func _mouse_button(x: float, y: float, button: int, pressed: bool, dbl: bool, mods: int) -> void:
	var bit := 0
	match button:
		MOUSE_BUTTON_LEFT:
			bit = MOUSE_BUTTON_MASK_LEFT
		MOUSE_BUTTON_RIGHT:
			bit = MOUSE_BUTTON_MASK_RIGHT
		MOUSE_BUTTON_MIDDLE:
			bit = MOUSE_BUTTON_MASK_MIDDLE
		MOUSE_BUTTON_XBUTTON1:
			bit = MOUSE_BUTTON_MASK_MB_XBUTTON1
		MOUSE_BUTTON_XBUTTON2:
			bit = MOUSE_BUTTON_MASK_MB_XBUTTON2
	if pressed:
		_button_mask |= bit
	else:
		_button_mask &= ~bit
	var ev := InputEventMouseButton.new()
	ev.position = Vector2(x, y)
	ev.global_position = ev.position
	ev.button_index = button as MouseButton
	ev.pressed = pressed
	ev.double_click = dbl and pressed
	ev.button_mask = _button_mask
	_apply_mods(ev, mods)
	_dispatch(ev)


func _wheel(x: float, y: float, dx: float, dy: float, mods: int) -> void:
	if dy != 0.0:
		_wheel_axis(x, y, MOUSE_BUTTON_WHEEL_DOWN if dy > 0.0 else MOUSE_BUTTON_WHEEL_UP, absf(dy), mods)
	if dx != 0.0:
		_wheel_axis(x, y, MOUSE_BUTTON_WHEEL_RIGHT if dx > 0.0 else MOUSE_BUTTON_WHEEL_LEFT, absf(dx), mods)


func _wheel_axis(x: float, y: float, button: MouseButton, factor: float, mods: int) -> void:
	for pressed in [true, false]:
		var ev := InputEventMouseButton.new()
		ev.position = Vector2(x, y)
		ev.global_position = ev.position
		ev.button_index = button
		ev.factor = factor
		ev.pressed = pressed
		ev.button_mask = _button_mask
		_apply_mods(ev, mods)
		_dispatch(ev)


func _key(vk: int, unicode: int, pressed: bool, echo: bool, mods: int, location: int) -> void:
	var key := _map_vk(vk, location)
	if key == KEY_NONE and unicode <= 0:
		return
	var ev := InputEventKey.new()
	ev.keycode = key
	ev.physical_keycode = key
	ev.key_label = key
	ev.unicode = unicode if (pressed and unicode > 0) else 0
	ev.pressed = pressed
	ev.echo = echo
	ev.location = KEY_LOCATION_LEFT if location == 2 else (KEY_LOCATION_RIGHT if location == 3 else KEY_LOCATION_UNSPECIFIED)
	_apply_mods(ev, mods)
	_dispatch(ev)


func _focus(focused: bool) -> void:
	if not focused:
		# The IDE releases keys itself; also drop any stuck mouse buttons.
		for b: int in [MOUSE_BUTTON_LEFT, MOUSE_BUTTON_RIGHT, MOUSE_BUTTON_MIDDLE]:
			var bit := 1 << (b - 1)
			if _button_mask & bit:
				_mouse_button(-1.0, -1.0, b, false, false, 0)
	if _scene != null:
		# Mirror what the OS would do for a real window.
		_scene.propagate_notification(
			Node.NOTIFICATION_APPLICATION_FOCUS_IN if focused else Node.NOTIFICATION_APPLICATION_FOCUS_OUT)


# java.awt.event.KeyEvent.VK_* -> Godot Key. Letters, digits and most ASCII punctuation
# share their codes; everything else is listed here.
const _VK_MAP := {
	10: KEY_ENTER, 8: KEY_BACKSPACE, 9: KEY_TAB, 27: KEY_ESCAPE, 32: KEY_SPACE,
	16: KEY_SHIFT, 17: KEY_CTRL, 18: KEY_ALT, 157: KEY_META, 524: KEY_META, 525: KEY_MENU,
	20: KEY_CAPSLOCK, 144: KEY_NUMLOCK, 145: KEY_SCROLLLOCK, 19: KEY_PAUSE, 154: KEY_PRINT,
	33: KEY_PAGEUP, 34: KEY_PAGEDOWN, 35: KEY_END, 36: KEY_HOME,
	37: KEY_LEFT, 38: KEY_UP, 39: KEY_RIGHT, 40: KEY_DOWN,
	127: KEY_DELETE, 155: KEY_INSERT, 3: KEY_CLEAR, 156: KEY_HELP,
	112: KEY_F1, 113: KEY_F2, 114: KEY_F3, 115: KEY_F4, 116: KEY_F5, 117: KEY_F6,
	118: KEY_F7, 119: KEY_F8, 120: KEY_F9, 121: KEY_F10, 122: KEY_F11, 123: KEY_F12,
	61440: KEY_F13, 61441: KEY_F14, 61442: KEY_F15, 61443: KEY_F16, 61444: KEY_F17,
	61445: KEY_F18, 61446: KEY_F19, 61447: KEY_F20, 61448: KEY_F21, 61449: KEY_F22,
	61450: KEY_F23, 61451: KEY_F24,
	96: KEY_KP_0, 97: KEY_KP_1, 98: KEY_KP_2, 99: KEY_KP_3, 100: KEY_KP_4,
	101: KEY_KP_5, 102: KEY_KP_6, 103: KEY_KP_7, 104: KEY_KP_8, 105: KEY_KP_9,
	106: KEY_KP_MULTIPLY, 107: KEY_KP_ADD, 108: KEY_KP_ENTER, 109: KEY_KP_SUBTRACT,
	110: KEY_KP_PERIOD, 111: KEY_KP_DIVIDE,
	44: KEY_COMMA, 45: KEY_MINUS, 46: KEY_PERIOD, 47: KEY_SLASH, 59: KEY_SEMICOLON,
	61: KEY_EQUAL, 91: KEY_BRACKETLEFT, 92: KEY_BACKSLASH, 93: KEY_BRACKETRIGHT,
	222: KEY_APOSTROPHE, 192: KEY_QUOTELEFT, 512: KEY_AT, 513: KEY_COLON, 520: KEY_NUMBERSIGN,
	521: KEY_PLUS, 517: KEY_EXCLAM, 519: KEY_PARENLEFT, 522: KEY_PARENRIGHT, 523: KEY_UNDERSCORE,
	151: KEY_ASTERISK, 152: KEY_QUOTEDBL, 153: KEY_LESS, 160: KEY_GREATER,
	161: KEY_BRACELEFT, 162: KEY_BRACERIGHT, 515: KEY_DOLLAR, 514: KEY_ASCIICIRCUM,
}


func _map_vk(vk: int, location: int) -> Key:
	if vk >= 65 and vk <= 90:
		return (KEY_A + (vk - 65)) as Key
	if vk >= 48 and vk <= 57:
		return (KEY_0 + (vk - 48)) as Key
	if location == 4:  # KEY_LOCATION_NUMPAD
		if vk == 10:
			return KEY_KP_ENTER
	if _VK_MAP.has(vk):
		return _VK_MAP[vk] as Key
	return KEY_NONE


class Driver extends Node:
	var shim  # the shim SceneTree; untyped so _tick() resolves dynamically

	func _process(_delta: float) -> void:
		if shim != null:
			shim._tick()
