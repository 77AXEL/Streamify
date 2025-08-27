from socket import socket, AF_INET, SOCK_STREAM, IPPROTO_TCP, TCP_NODELAY
from struct import pack , unpack
from ppadb.client import Client
from PIL import Image, ImageTk
from queue import Queue, Empty
from tkinter import Tk, Label
from threading import Thread
from time import sleep, time
from pynput import keyboard
from math import hypot
from os import system, path
import sys

class VNC_Client:
    def __init__(self, host='127.0.0.1', port=5900):
        self.host = host
        self.port = port
        self.sock = None
        self.bits_per_pixel = 16
        
    def connect(self):
        self.sock = socket(AF_INET, SOCK_STREAM)
        self.sock.settimeout(5)
        self.sock.setsockopt(IPPROTO_TCP, TCP_NODELAY, 1)
        self.sock.connect((self.host, self.port))
        self.sock.send(b'RFB 003.008\n')
        self.sock.recv(12)
        
        num_auth = unpack('!B', self.sock.recv(1))[0]
        self.sock.recv(num_auth)
        self.sock.send(pack('!B', 1))
        
        auth_result_bytes = b''
        while len(auth_result_bytes) < 4:
            chunk = self.sock.recv(4 - len(auth_result_bytes))
            if not chunk:
                raise Exception("Connection closed")
            auth_result_bytes += chunk
        auth_result = unpack('!I', auth_result_bytes)[0]
        if auth_result != 0:
            raise Exception("Auth failed")
        
        server_init_data = self.sock.recv(8192)
        width = unpack('!H', server_init_data[0:2])[0]
        height = unpack('!H', server_init_data[2:4])[0]
        self.bits_per_pixel = unpack('!B', server_init_data[4:5])[0]
        self.sock.send(pack('!B', 1))
        self.sock.send(pack('!BBHI', 2, 0, 1, 0))

        return width, height
        
    def captureScreen(self):
        try:
            self.sock.send(pack('!BBHHHH', 3, 0, 0, 0, 320, 700))
            msg_type = unpack('!B', self.sock.recv(1))[0]
            if msg_type != 0:
                return None
            
            self.sock.recv(1)
            num_rects = unpack('!H', self.sock.recv(2))[0]
            
            for _ in range(num_rects):
                rect_data = self.sock.recv(12)
                if len(rect_data) != 12:
                    return None
                
                rect_x, rect_y, rect_w, rect_h, encoding = unpack('!HHHHI', rect_data)
                
                if encoding == 0:
                    bytes_per_pixel = self.bits_per_pixel // 8
                    expected_size = rect_w * rect_h * bytes_per_pixel
                    pixel_data = bytearray()
                    remaining = expected_size
                    while remaining > 0:
                        chunk_size = min(remaining, 65536)
                        chunk = self.sock.recv(chunk_size)
                        if not chunk:
                            return None
                        pixel_data.extend(chunk)
                        remaining -= len(chunk)
                    
                    if len(pixel_data) != expected_size:
                        return None
                    
                    if self.bits_per_pixel == 32:
                        img = Image.frombytes('RGBA', (rect_w, rect_h), bytes(pixel_data))
                    else:
                        return None
                    
                    img = img.crop((20, 0, 300, 700))
                    img = img.resize((320, 700), Image.Resampling.LANCZOS)
                    
                    return img
                else:
                    return None
            
            return None
            
        except:
            return None
    
    def close(self):
        if self.sock:
            try:
                self.sock.close()
            except:
                pass

class ADB_Client:
    def init(self, host="127.0.0.1", port=5037):
        system("adb start-server && adb forward tcp:5900 tcp:5900")

        self.codes = {
            "Key.enter": "66",
            "Key.space": "62",
            "Key.tab": "61",
            "Key.caps_lock": "115",
            "Key.backspace": "67"
        }

        self.app_is_running = False
        self.server_is_running = False

        self.client = Client(host=host, port=port)
        self.devices = self.client.devices()
        if len(self.devices) == 0:
            return None
        elif len(self.devices) > 1:
            return False
        
        self.device = self.devices[0]

        if not "com.app.streamify" in self.device.shell("pm list packages"):
            self.device.install("app-debug.apk")
            self.device.shell("pm grant com.app.streamify android.permission.POST_NOTIFICATIONS")

        self.screen_width, self.screen_height = map(int, self.device.shell("wm size").strip().split(":")[1].strip().split("x"))
        self.model = self.device.shell("getprop ro.product.model")
        self.device_info = dict(
            model = self.model,
            screen_width = self.screen_width,
            screen_height = self.screen_height
        )
        
        return self.device_info
    
    def start_app(self):
        if not self.app_is_running:
            self.device.shell("am start -n com.app.streamify/.MainActivity")
            self.app_is_running = True

    def close_app(self):
        if self.app_is_running:
            self.device.shell("am force-stop com.app.streamify")
            self.app_is_running = False

    def start_app_server(self):
        if self.app_is_running and not self.server_is_running:
            self.device.shell("am broadcast -a com.app.streamify.START_VNC_ADB")
            self.server_is_running = True

    def close_app_server(self):
        if self.app_is_running and self.server_is_running:
            self.device.shell("am broadcast -a com.app.streamify.START_VNC_ADB")
            self.server_is_running = False

    def close_client(self):
        self.client.close()

    def tap(self, x, y):
        self.device.shell(f"input tap {x} {y}")

    def swipe(self, start_x, start_y, end_x, end_y):
        self.device.shell(f"input swipe {start_x} {start_y} {end_x} {end_y}")
    
    def keyboard(self, key):
        try:
            self.device.shell(f"input text {key.char}")
        except:
            try:
                key_code = self.codes[str(key)]
                self.device.shell(f"input keyevent {key_code}")
            except:
                pass
    
    def long_press(self, x, y, duration_ms=600):
        self.device.shell(f"input swipe {x} {y} {x} {y} {duration_ms}")
    

if __name__ == "__main__":
    
    color1 = "#f0f0f0"
    color2 = "#39dcab"
    color3 = "#0f0f0f"

    root = Tk()
    root.title("Streamify")
    root.geometry("320x700")
    
    ic_launcher = Image.open("ic_launcher.png")
    ic_launcher = ImageTk.PhotoImage(ic_launcher)

    root.iconphoto(True, ic_launcher)
    root.resizable(0, 0)
    root.config(bg=color1)

    if sys.platform.startswith("win"):
        from ctypes import windll
        windll.shell32.SetCurrentProcessExplicitAppUserModelID("com.app.streamify")
        root.iconbitmap("ic_launcher.ico")

    streamify_label = Label(root, text="Streamify", fg=color2, bg=color1, font=("Arial", 20, "bold"))
    streamify_label.pack()
    streamify_label.place(x= 95, y=300)

    status_label = Label(text="Launching ..", fg=color3, bg=color1)
    status_label.pack(pady=340)

    _ADB_Client = ADB_Client()
    _VNC_Client = VNC_Client()

    VNC_Connected = False

    def ADB_VNC_Connecting_Handler():
        global VNC_Connected, screen_width, screen_height
        device_info = _ADB_Client.init()

        while not device_info:
            device_info = _ADB_Client.init()
            if device_info == None:
                status_label.config(text="No devices connected !")
            elif device_info == False:
                status_label.config(text="Too many devices connected !")
            sleep(3)
            
        model = device_info["model"].strip()
        screen_width = device_info["screen_width"]
        screen_height = device_info["screen_height"]

        status_label.config(text="Waiting for VNC authentication ..")
        root.title(model)

        _ADB_Client.start_app()
        sleep(3)
        _ADB_Client.start_app_server()

        while True:
            try:
                _VNC_Client.connect()
                status_label.config(text=f"{model} Connected !")
                VNC_Connected = True
                sleep(0.5)
                streamify_label.destroy()
                status_label.destroy()
                break
            except:
                sleep(1)

    Thread(target=ADB_VNC_Connecting_Handler, daemon=True).start()

    mirroring_label = Label(root, bd=0)
    mirroring_label.pack()
    old_img = None

    def update_screen():
        global old_img
        
        while True:
            if VNC_Connected:
                try:
                    img = _VNC_Client.captureScreen()
                    if img and img != old_img:
                        tk_img = ImageTk.PhotoImage(img)
                        mirroring_label.config(image=tk_img)
                        mirroring_label.image = tk_img
                        old_img = img
                except:
                    pass

    Thread(target=update_screen, daemon=True).start()

    UI_W, UI_H = 320, 700

    SWIPE_THRESHOLD_UI = 12
    MOVE_CANCEL_LP_UI = 8
    LONG_PRESS_MS = 500
    TAP_DEBOUNCE_MS = 180

    closed = False

    def scale_to_device(px, py):
        return (int(px * screen_width / UI_W), int(py * screen_height / UI_H))

    cmd_q = Queue()

    def adb_worker():
        while not closed:
            try:
                job = cmd_q.get(timeout=0.2)
            except Empty:
                continue
            try:
                t = job["type"]
                if t == "tap":
                    _ADB_Client.tap(job["x"], job["y"])
                elif t == "swipe":
                    _ADB_Client.swipe(job["x0"], job["y0"], job["x1"], job["y1"])
                elif t == "long":
                    _ADB_Client.long_press(job["x"], job["y"], job["duration"])
            finally:
                cmd_q.task_done()

    Thread(target=adb_worker, daemon=True).start()

    state = "idle"
    press_id = 0
    start_ui = (0, 0)
    curr_ui = (0, 0)
    longpress_job = None
    action_triggered = False
    last_tap_ts = 0.0

    def reset_gesture_state():
        global state, longpress_job, action_triggered
        if longpress_job is not None:
            try:
                root.after_cancel(longpress_job)
            except Exception:
                pass
            longpress_job = None
        state = "idle"
        action_triggered = False

    def gesture_distance_ui(a, b):
        dx = b[0] - a[0]
        dy = b[1] - a[1]
        return hypot(dx, dy)

    def do_long_press(pid):
        global state, action_triggered

        if closed or pid != press_id or state not in ("pressing", "moving"):
            return
        if gesture_distance_ui(start_ui, curr_ui) > MOVE_CANCEL_LP_UI:
            return

        x_dev, y_dev = scale_to_device(*start_ui)
        cmd_q.put({"type": "long", "x": x_dev, "y": y_dev, "duration": LONG_PRESS_MS})
        state = "long"
        action_triggered = True

    def on_press(e):
        global state, press_id, start_ui, curr_ui, longpress_job, action_triggered
        start_ui = (e.x, e.y)
        curr_ui = (e.x, e.y)
        action_triggered = False
        press_id += 1
        state = "pressing"

        def _cb(pid=press_id):
            do_long_press(pid)

        longpress_job = root.after(LONG_PRESS_MS, _cb)

    def on_motion(e):
        global curr_ui, state, longpress_job
        curr_ui = (e.x, e.y)
        if state == "pressing":
            if gesture_distance_ui(start_ui, curr_ui) > MOVE_CANCEL_LP_UI and longpress_job is not None:
                try:
                    root.after_cancel(longpress_job)
                except Exception:
                    pass
                longpress_job = None
                state = "moving"

    def on_release(e):
        global last_tap_ts
        if longpress_job is not None:
            try:
                root.after_cancel(longpress_job)
            except Exception:
                pass

        if action_triggered or state == "long":
            reset_gesture_state()
            return

        end_ui = (e.x, e.y)
        move = gesture_distance_ui(start_ui, end_ui)

        if move < SWIPE_THRESHOLD_UI:
            now = time()
            if (now - last_tap_ts) * 1000.0 >= TAP_DEBOUNCE_MS:
                x_dev, y_dev = scale_to_device(*start_ui)
                cmd_q.put({"type": "tap", "x": x_dev, "y": y_dev})
                last_tap_ts = now
        else:
            x0, y0 = scale_to_device(*start_ui)
            x1, y1 = scale_to_device(*end_ui)
            cmd_q.put({"type": "swipe", "x0": x0, "y0": y0, "x1": x1, "y1": y1})

        reset_gesture_state()

    def on_closing():
        global closed
        closed = True
        try:
            _ADB_Client.close_app_server()
            _ADB_Client.close_app()
            _VNC_Client.close()
        finally:
            root.destroy()

    def keyboard_handler():
        def on_press(key):
            _ADB_Client.keyboard(key)
        def on_release(key):
            return closed
        with keyboard.Listener(on_press=on_press, on_release=on_release) as listener:
            listener.join()

    Thread(target=keyboard_handler, daemon=True).start()

    root.bind("<ButtonPress-1>", on_press)
    root.bind("<B1-Motion>", on_motion)
    root.bind("<ButtonRelease-1>", on_release)
    root.protocol("WM_DELETE_WINDOW", on_closing)

    root.mainloop()