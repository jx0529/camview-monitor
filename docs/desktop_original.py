```python
#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Multi‑Camera Monitoring System (GPU Accelerated Edition) – 完整版
功能：
- GPU 加速 (Auto/CUDA/OpenCL)
- 分组管理、排序、懒加载
- 摄像头旋转/翻转（右键菜单）
- 放大窗口可拖动画面
- 工具栏分组下拉菜单 + 水平滚动
- 深色主题，无多余白边/弹窗
"""
import cv2
import numpy as np
import threading
import time
import json
import os
import tkinter as tk
import socket
from tkinter import (Tk, Frame, Label, Button, Menu, Toplevel, simpledialog,
                     messagebox, Entry, Text, BooleanVar, Checkbutton, Listbox,
                     MULTIPLE, Scrollbar, END, NORMAL, DISABLED,
                     PanedWindow, OptionMenu, StringVar, ttk, Radiobutton, Canvas)
from PIL import Image, ImageTk
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime

# ================== 配置 ==================
CONFIG_FILE = "camera_system_config.json"
DEFAULT_CONFIG = {
    "default_user": "admin",
    "default_password": "admin123",
    "rtsp_templates": [
        "rtsp://{user}:{password}@{ip}:554/Streaming/Channels/101",
        "rtsp://{user}:{password}@{ip}:554/Streaming/Channels/102",
        "rtsp://{user}:{password}@{ip}:554/cam/realmonitor?channel=1&subtype=0",
        "rtsp://{user}:{password}@{ip}:554/onvif1",
        "rtsp://{user}:{password}@{ip}:554/h264",
    ],
    "max_cols": 3,
    "show_fps": True,
    "connection_timeout_ms": 2000,
    "saved_cameras": [],
    "camera_groups": {"默认分组": []},
    "current_group": "默认分组",
    "items_per_page": 9,
    "gpu_acceleration": True,
    "hw_decode": True,
    "gpu_mode": "auto",
}

def ip_to_key(ip):
    try:
        return tuple(map(int, ip.split('.')))
    except Exception:
        return (0,0,0,0)

# ================== GPU 加速 ==================
class GPUAccelerator:
    MODE_NONE = 0
    MODE_OPENCV_OPENCL = 1
    MODE_CUPY = 2

    def __init__(self, preferred_mode='auto'):
        self.mode = self.MODE_NONE
        self.cupy = None
        self.info = "CPU模式"
        self.preferred_mode = preferred_mode
        self._detect()

    def _detect(self):
        if self.preferred_mode in ('cuda', 'auto'):
            try:
                import cupy as cp
                cp.cuda.Device(0).use()
                cp.array([1,2,3])
                cp.asnumpy(cp.array([1]))
                self.cupy = cp
                self.mode = self.MODE_CUPY
                props = cp.cuda.runtime.getDeviceProperties(0)
                self.info = f"CUDA: {props['name'].decode()}"
                print(f"[GPU] CUDA 加速已启用 — {self.info}")
                return
            except Exception as e:
                print(f"[GPU] CUDA 初始化失败: {e}")
        if self.preferred_mode in ('opencl', 'auto'):
            try:
                if cv2.ocl.haveOpenCL():
                    cv2.ocl.setUseOpenCL(True)
                    if cv2.ocl.useOpenCL():
                        self.mode = self.MODE_OPENCV_OPENCL
                        device = cv2.ocl.Device.getDefault()
                        self.info = f"OpenCL: {device.name()}"
                        print(f"[GPU] OpenCL 加速已启用 — {self.info}")
                        return
            except Exception as e:
                print(f"[GPU] OpenCL 尝试失败: {e}")
        print("[GPU] 未检测到 GPU 加速，回退到 CPU 模式")

    def is_available(self):
        return self.mode != self.MODE_NONE

    def resize(self, frame, dsize):
        if frame is None:
            return None
        if not isinstance(frame, np.ndarray):
            try:
                frame = np.array(frame)
            except Exception:
                return None
        if self.mode == self.MODE_OPENCV_OPENCL:
            try:
                umat = cv2.UMat(frame)
                ures = cv2.resize(umat, dsize)
                return ures.get()
            except Exception:
                pass
        elif self.mode == self.MODE_CUPY:
            try:
                cp = self.cupy
                gpu_arr = cp.array(frame)
                np_arr = cp.asnumpy(gpu_arr)
                return cv2.resize(np_arr, dsize)
            except Exception:
                try:
                    return cv2.resize(frame, dsize)
                except Exception:
                    return frame
        return cv2.resize(frame, dsize)

    def cvtColor(self, frame, code):
        if frame is None:
            return None
        if not isinstance(frame, np.ndarray):
            try:
                frame = np.array(frame)
            except Exception:
                return None
        if self.mode == self.MODE_OPENCV_OPENCL:
            try:
                umat = cv2.UMat(frame)
                ures = cv2.cvtColor(umat, code)
                return ures.get()
            except Exception:
                pass
        elif self.mode == self.MODE_CUPY:
            try:
                cp = self.cupy
                arr = cp.array(frame)
                np_arr = cp.asnumpy(arr)
                return cv2.cvtColor(np_arr, code)
            except Exception:
                try:
                    return cv2.cvtColor(frame, code)
                except Exception:
                    return frame
        return cv2.cvtColor(frame, code)

GPU = None
def init_gpu(preferred_mode='auto'):
    global GPU
    GPU = GPUAccelerator(preferred_mode=preferred_mode)
    return GPU

# ================== 视频流 ==================
class CameraStream:
    def __init__(self, cam_id, ip, user, password, timeout_ms, hw_decode=True):
        self.cam_id = cam_id
        self.ip = ip
        self.user = user
        self.password = password
        self.timeout_ms = timeout_ms
        self.hw_decode = hw_decode
        self.cap = None
        self.current_frame = None
        self.running = False
        self.thread = None
        self.fps = 0.0
        self.url_used = None
        self.connected = False
        self.connecting = True
        self.rotation = 0
        self.flip_h = False
        self.flip_v = False

    def start(self, templates):
        self.running = True
        self.thread = threading.Thread(target=self._connect_and_read, args=(templates,), daemon=True)
        self.thread.start()
        return True

    def _try_open_capture(self, url):
        cap = cv2.VideoCapture(url, cv2.CAP_FFMPEG)
        cap.set(cv2.CAP_PROP_OPEN_TIMEOUT_MSEC, self.timeout_ms)
        cap.set(cv2.CAP_PROP_READ_TIMEOUT_MSEC, self.timeout_ms)
        if self.hw_decode and hasattr(cv2, 'CAP_PROP_HW_ACCELERATION'):
            for hw_flag, hw_name in [(cv2.VIDEO_ACCELERATION_ANY,"Auto"),
                                     (cv2.VIDEO_ACCELERATION_D3D11,"D3D11"),
                                     (cv2.VIDEO_ACCELERATION_VAAPI,"VAAPI")]:
                try:
                    cap.set(cv2.CAP_PROP_HW_ACCELERATION, hw_flag)
                    if cap.isOpened():
                        print(f"[HW] {self.ip} 使用 {hw_name} 硬件解码")
                        return cap
                except Exception:
                    continue
            cap.release()
            cap = cv2.VideoCapture(url, cv2.CAP_FFMPEG)
            cap.set(cv2.CAP_PROP_OPEN_TIMEOUT_MSEC, self.timeout_ms)
            cap.set(cv2.CAP_PROP_READ_TIMEOUT_MSEC, self.timeout_ms)
        return cap

    def _connect_and_read(self, templates):
        for template in templates:
            if not self.running:
                return
            url = template.format(ip=self.ip, user=self.user, password=self.password)
            cap = self._try_open_capture(url)
            if cap.isOpened():
                self.url_used = url
                self.cap = cap
                self.connected = True
                self.connecting = False
                break
            cap.release()
        if not self.connected:
            self.connecting = False
            return
        frame_cnt = 0
        fps_timer = time.time()
        while self.running:
            if self.cap is None or not self.cap.isOpened():
                time.sleep(0.5)
                continue
            ret, frame = self.cap.read()
            if not ret:
                self.cap.release()
                time.sleep(1)
                if not self.running:
                    return
                self.cap = cv2.VideoCapture(self.url_used, cv2.CAP_FFMPEG)
                continue
            self.current_frame = frame
            frame_cnt += 1
            now = time.time()
            if now - fps_timer >= 1.0:
                self.fps = frame_cnt / (now - fps_timer)
                frame_cnt = 0
                fps_timer = now
            time.sleep(0.03)

    def get_frame(self):
        return self.current_frame

    def get_fps(self):
        return self.fps if self.connected else 0.0

    def stop(self):
        self.running = False
        if self.thread and self.thread.is_alive():
            self.thread.join(timeout=1.0)
        if self.cap:
            self.cap.release()
        self.current_frame = None

    def apply_transform(self, frame):
        if frame is None:
            return None
        if self.flip_h and self.flip_v:
            frame = cv2.flip(frame, -1)
        elif self.flip_h:
            frame = cv2.flip(frame, 1)
        elif self.flip_v:
            frame = cv2.flip(frame, 0)
        if self.rotation == 90:
            frame = cv2.rotate(frame, cv2.ROTATE_90_CLOCKWISE)
        elif self.rotation == 180:
            frame = cv2.rotate(frame, cv2.ROTATE_180)
        elif self.rotation == 270:
            frame = cv2.rotate(frame, cv2.ROTATE_90_COUNTERCLOCKWISE)
        return frame

    def to_dict(self):
        return {"ip": self.ip, "user": self.user, "password": self.password}

# ================== 分组管理 ==================
class GroupManager:
    def __init__(self, app):
        self.app = app
        self.groups = {}
        self.current_group = None
        self.current_page = 0
        self.items_per_page = self.app.config.get("items_per_page", 9)

    def load_from_config(self, groups_data, current_group):
        self.groups = groups_data if groups_data else {"默认分组": []}
        self.current_group = current_group if current_group in self.groups else list(self.groups.keys())[0]
        self.current_page = 0

    def get_group_list(self):
        return list(self.groups.keys())

    def add_group(self, group_name):
        if group_name in self.groups:
            return False
        self.groups[group_name] = []
        self.save()
        return True

    def delete_group(self, group_name):
        if group_name == "默认分组":
            return False
        if group_name in self.groups:
            default_group = "默认分组"
            for ip in self.groups[group_name]:
                if ip not in self.groups[default_group]:
                    self.groups[default_group].append(ip)
            del self.groups[group_name]
            if self.current_group == group_name:
                self.current_group = default_group
                self.current_page = 0
            self.save()
            return True
        return False

    def rename_group(self, old_name, new_name):
        if old_name not in self.groups or new_name in self.groups:
            return False
        self.groups[new_name] = self.groups.pop(old_name)
        if self.current_group == old_name:
            self.current_group = new_name
        self.save()
        return True

    def move_camera(self, ip, from_group, to_group):
        if from_group not in self.groups or to_group not in self.groups:
            return False
        if ip in self.groups[from_group]:
            self.groups[from_group].remove(ip)
            if ip not in self.groups[to_group]:
                self.groups[to_group].append(ip)
            self.save()
            if self.current_group == from_group:
                total = self.get_total_pages(from_group)
                if self.current_page >= total:
                    self.current_page = max(0, total - 1)
            return True
        return False

    def get_cameras_in_group(self, group_name):
        return self.groups.get(group_name, [])

    def get_total_pages(self, group_name=None):
        if group_name is None:
            group_name = self.current_group
        count = len(self.groups.get(group_name, []))
        return max(1, (count + self.items_per_page - 1) // self.items_per_page)

    def get_current_page_cameras(self):
        cams = self.get_cameras_in_group(self.current_group)
        start = self.current_page * self.items_per_page
        end = start + self.items_per_page
        return cams[start:end]

    def next_page(self):
        total = self.get_total_pages()
        if self.current_page + 1 < total:
            self.current_page += 1
            return True
        return False

    def prev_page(self):
        if self.current_page > 0:
            self.current_page -= 1
            return True
        return False

    def set_items_per_page(self, value):
        self.items_per_page = value
        self.current_page = 0
        self.save()

    def sort_all_groups(self):
        for group_name in self.groups:
            self.groups[group_name].sort(key=ip_to_key)
        self.save()

    def sort_groups_by_name(self):
        sorted_groups = {}
        if "默认分组" in self.groups:
            sorted_groups["默认分组"] = self.groups["默认分组"]
        other_groups = {k: v for k, v in self.groups.items() if k != "默认分组"}
        for name in sorted(other_groups.keys()):
            sorted_groups[name] = other_groups[name]
        self.groups = sorted_groups
        if self.current_group not in self.groups:
            self.current_group = list(self.groups.keys())[0]
            self.current_page = 0
        self.save()

    def sort_current_group(self):
        group = self.current_group
        ips = self.groups.get(group, [])
        ips.sort(key=ip_to_key)
        self.groups[group] = ips
        self.save()

    def save(self):
        self.app.config['camera_groups'] = self.groups
        self.app.config['current_group'] = self.current_group
        self.app.config['items_per_page'] = self.items_per_page
        self.app.save_config()

# ================== 设置窗口（可滚动） ==================
class SettingsWindow:
    def __init__(self, parent, config, save_callback, gpu_switch_callback):
        self.win = Toplevel(parent)
        self.win.title("系统设置")
        self.win.geometry("600x700")
        self.win.minsize(550, 500)
        self.win.configure(bg='#0d1117')
        self.win.transient(parent)
        self.win.grab_set()
        self.config = config
        self.save_callback = save_callback
        self.gpu_switch_callback = gpu_switch_callback
        self.create_widgets()

    def create_widgets(self):
        # 固定标题
        header = Frame(self.win, bg='#161b22', height=45)
        header.pack(fill='x')
        header.pack_propagate(False)
        Label(header, text="⚙ 系统设置", bg='#161b22', fg='#c9d1d9',
              font=('微软雅黑', 13, 'bold')).pack(side='left', padx=15, pady=10)

        # 滚动区域
        canvas = Canvas(self.win, bg='#0d1117', highlightthickness=0)
        scrollbar = Scrollbar(self.win, orient='vertical', command=canvas.yview)
        canvas.configure(yscrollcommand=scrollbar.set)
        scrollbar.pack(side='right', fill='y')
        canvas.pack(side='left', fill='both', expand=True)

        container = Frame(canvas, bg='#0d1117')
        canvas.create_window((0, 0), window=container, anchor='nw', width=self.win.winfo_width()-20)
        container.bind("<Configure>", lambda e: canvas.configure(scrollregion=canvas.bbox("all")))
        self.win.bind("<Configure>", lambda e: canvas.itemconfig(1, width=e.width-20) if canvas.winfo_exists() else None)

        fields = [
            ("default_user_entry", "默认用户名:", self.config["default_user"], False),
            ("default_pwd_entry", "默认密码:", self.config["default_password"], True),
            ("max_cols_entry", "每行最大列数:", str(self.config["max_cols"]), False),
            ("timeout_entry", "连接超时(毫秒):", str(self.config["connection_timeout_ms"]), False),
        ]

        for i, (attr, label, value, show_pwd) in enumerate(fields):
            Label(container, text=label, bg='#0d1117', fg='#c9d1d9',
                  font=('微软雅黑', 10)).grid(row=i, column=0, sticky='e', padx=5, pady=8)
            entry = Entry(container, width=35, bg='#0d1117', fg='#c9d1d9',
                          insertbackground='#c9d1d9', relief='flat', highlightthickness=1,
                          highlightcolor='#58a6ff', highlightbackground='#30363d',
                          font=('微软雅黑', 10))
            if show_pwd:
                entry.config(show='*')
            entry.insert(0, value)
            entry.grid(row=i, column=1, sticky='w', padx=5, pady=8)
            setattr(self, attr, entry)

        row = len(fields)
        self.show_fps_var = BooleanVar(master=self.win, value=self.config["show_fps"])
        Checkbutton(container, text="显示帧率(FPS)", variable=self.show_fps_var,
                    bg='#0d1117', fg='#c9d1d9', selectcolor='#0d1117',
                    activebackground='#0d1117', activeforeground='#c9d1d9',
                    font=('微软雅黑', 10)).grid(row=row, column=0, columnspan=2, sticky='w', pady=8)
        row += 1

        Label(container, text="GPU 加速模式:", bg='#0d1117', fg='#c9d1d9',
              font=('微软雅黑', 10, 'bold')).grid(row=row, column=0, sticky='e', padx=5, pady=8)
        gpu_frame = Frame(container, bg='#0d1117')
        gpu_frame.grid(row=row, column=1, sticky='w', padx=5, pady=8)
        self.gpu_mode_var = StringVar(master=self.win, value=self.config.get("gpu_mode", "auto"))
        modes = [("auto", "自动检测 (优先CUDA)"), ("cuda", "强制 CUDA"), ("opencl", "强制 OpenCL")]
        for val, text in modes:
            Radiobutton(gpu_frame, text=text, variable=self.gpu_mode_var, value=val,
                        bg='#0d1117', fg='#c9d1d9', selectcolor='#0d1117',
                        activebackground='#0d1117', activeforeground='#c9d1d9',
                        font=('微软雅黑', 9)).pack(anchor='w')
        row += 1

        current_gpu = GPU.info if GPU else "未初始化"
        Label(container, text=f"当前 GPU: {current_gpu}", bg='#0d1117', fg='#58a6ff',
              font=('微软雅黑', 9)).grid(row=row, column=0, columnspan=2, sticky='w', padx=20)
        row += 1

        self.gpu_var = BooleanVar(master=self.win, value=self.config.get("gpu_acceleration", True))
        Checkbutton(container, text="启用 GPU 图像处理加速", variable=self.gpu_var,
                    bg='#0d1117', fg='#c9d1d9', selectcolor='#0d1117',
                    activebackground='#0d1117', activeforeground='#c9d1d9',
                    font=('微软雅黑', 10)).grid(row=row, column=0, columnspan=2, sticky='w', pady=4)
        row += 1

        self.hw_var = BooleanVar(master=self.win, value=self.config.get("hw_decode", True))
        Checkbutton(container, text="启用硬件视频解码 (FFMPEG HWAccel)", variable=self.hw_var,
                    bg='#0d1117', fg='#c9d1d9', selectcolor='#0d1117',
                    activebackground='#0d1117', activeforeground='#c9d1d9',
                    font=('微软雅黑', 10)).grid(row=row, column=0, columnspan=2, sticky='w', pady=4)
        row += 1

        Label(container, text="每页显示数量:", bg='#0d1117', fg='#c9d1d9',
              font=('微软雅黑', 10)).grid(row=row, column=0, sticky='e', padx=5, pady=8)
        self.items_per_page_var = StringVar(master=self.win, value=str(self.config.get("items_per_page", 9)))
        om = OptionMenu(container, self.items_per_page_var, "4", "6", "9", "12", "16")
        om.config(bg='#0d1117', fg='#c9d1d9', relief='flat', highlightthickness=1,
                  highlightcolor='#58a6ff', highlightbackground='#30363d', font=('微软雅黑', 10))
        om["menu"].config(bg='#0d1117', fg='#c9d1d9', activebackground='#58a6ff',
                          activeforeground='#ffffff', relief='flat', font=('微软雅黑', 10))
        om.grid(row=row, column=1, sticky='w', padx=5)
        row += 1

        Label(container, text="RTSP地址模板(每行一条):", bg='#0d1117', fg='#c9d1d9',
              font=('微软雅黑', 10)).grid(row=row, column=0, columnspan=2, sticky='w', pady=(15, 5))
        row += 1
        self.templates_text = Text(container, height=8, width=65, bg='#0d1117', fg='#c9d1d9',
                                   insertbackground='#c9d1d9', relief='flat', highlightthickness=1,
                                   highlightcolor='#58a6ff', highlightbackground='#30363d',
                                   font=('Consolas', 10))
        self.templates_text.insert('1.0', '\n'.join(self.config["rtsp_templates"]))
        self.templates_text.grid(row=row, column=0, columnspan=2, padx=5, pady=5)
        row += 1

        btn_frame = Frame(self.win, bg='#0d1117')
        btn_frame.pack(pady=20, side='bottom', fill='x')
        Button(btn_frame, text="💾 保存", command=self.save_settings, relief='flat',
               bg='#238636', fg='white', padx=25, pady=6, font=('微软雅黑', 10),
               activebackground='#2ea043', cursor='hand2').pack(side='left', padx=10)
        Button(btn_frame, text="取消", command=self.win.destroy, relief='flat',
               bg='#21262d', fg='#c9d1d9', padx=25, pady=6, font=('微软雅黑', 10),
               activebackground='#30363d', cursor='hand2').pack(side='left', padx=10)

    def save_settings(self):
        try:
            new_config = {
                "default_user": self.default_user_entry.get().strip(),
                "default_password": self.default_pwd_entry.get().strip(),
                "max_cols": int(self.max_cols_entry.get().strip()),
                "connection_timeout_ms": int(self.timeout_entry.get().strip()),
                "show_fps": self.show_fps_var.get(),
                "gpu_acceleration": self.gpu_var.get(),
                "hw_decode": self.hw_var.get(),
                "gpu_mode": self.gpu_mode_var.get(),
                "rtsp_templates": [line.strip() for line in self.templates_text.get('1.0', 'end').splitlines() if line.strip()],
                "saved_cameras": self.config.get("saved_cameras", []),
                "camera_groups": self.config.get("camera_groups", {}),
                "current_group": self.config.get("current_group", "默认分组"),
                "items_per_page": int(self.items_per_page_var.get())
            }
            self.save_callback(new_config)
            if new_config["gpu_mode"] != self.config.get("gpu_mode", "auto"):
                self.gpu_switch_callback(new_config["gpu_mode"])
            self.win.destroy()
        except Exception as e:
            messagebox.showerror("错误", f"输入无效: {e}")

# ================== 主应用 ==================
class CameraApp:
    def __init__(self, root, splash=None):
        self.root = root
        self.splash = splash
        self.root.title("Multi‑Camera Monitoring System [GPU加速版]")
        self.root.geometry("1500x900")
        self.root.minsize(1200, 700)
        self.root.configure(bg='#0d1117')
        self.root.protocol("WM_DELETE_WINDOW", self.on_closing)

        self.config = self.load_config()
        self.lock = threading.Lock()
        self.running = True
        self.cam_id_counter = 1
        self.active_cameras = {}
        self._layout_cache = None

        self.group_mgr = GroupManager(self)
        groups_data = self.config.get("camera_groups", {"默认分组": []})
        current_group = self.config.get("current_group", "默认分组")
        self.group_mgr.load_from_config(groups_data, current_group)

        if self.splash:
            try:
                self.splash.destroy()
            except:
                pass

        self.root.after_idle(self._init_ui)

    def _init_ui(self):
        try:
            self._setup_styles()
            self._create_ui()
            self.load_saved_cameras()
            self.root.update_idletasks()
            self.last_render_ts = 0
            self.render_interval = 0.08
            self.root.after(200, self.update_video)
        except tk.TclError as e:
            if "application has been destroyed" not in str(e):
                raise

    def _setup_styles(self):
        try:
            if not self.root.winfo_exists():
                return
            style = ttk.Style()
            style.theme_use('clam')
            style.configure('Dark.Horizontal.TProgressbar', background='#58a6ff', troughcolor='#161b22')
        except (tk.TclError, RuntimeError):
            pass

    def _create_ui(self):
        if not self.root.winfo_exists():
            return

        # 深色分割条
        self.paned = PanedWindow(self.root, orient='horizontal',
                                 sashrelief='flat', sashwidth=2,
                                 bg='#0d1117')
        self.paned.pack(fill='both', expand=True)

        self.left_container = Frame(self.paned, bg='#010409')
        self.paned.add(self.left_container, width=1200)

        self.right_panel = Frame(self.paned, bg='#0d1117', width=260, highlightthickness=0)
        self.right_panel.pack_propagate(False)
        self.paned.add(self.right_panel, width=260)

        # ---------- 可滚动工具栏 ----------
        toolbar_frame = Frame(self.left_container, bg='#161b22', height=48)
        toolbar_frame.pack(side='top', fill='x')
        toolbar_frame.pack_propagate(False)

        toolbar_canvas = Canvas(toolbar_frame, bg='#161b22', highlightthickness=0, height=48)
        toolbar_canvas.pack(side='left', fill='both', expand=True)
        h_scrollbar = Scrollbar(toolbar_frame, orient='horizontal', command=toolbar_canvas.xview)
        h_scrollbar.pack(side='bottom', fill='x')
        toolbar_canvas.configure(xscrollcommand=h_scrollbar.set)

        inner_toolbar = Frame(toolbar_canvas, bg='#161b22')
        toolbar_canvas.create_window((0, 0), window=inner_toolbar, anchor='nw')
        def configure_toolbar(event):
            toolbar_canvas.configure(scrollregion=toolbar_canvas.bbox("all"))
        inner_toolbar.bind("<Configure>", configure_toolbar)

        self._create_toolbar_buttons(inner_toolbar)

        # ---------- 状态栏 ----------
        self.statusbar = Frame(self.left_container, bg='#161b22', height=28)
        self.statusbar.pack(side='bottom', fill='x')
        self.statusbar.pack_propagate(False)
        self.status_label = Label(self.statusbar, text="就绪", bg='#161b22', fg='#8b949e', font=('微软雅黑', 9))
        self.status_label.pack(side='left', padx=10)
        self.time_label = Label(self.statusbar, text="", bg='#161b22', fg='#8b949e', font=('微软雅黑', 9))
        self.time_label.pack(side='right', padx=10)
        self._update_clock()

        # ---------- 画面显示 ----------
        self.display_frame = Frame(self.left_container, bg='#010409')
        self.display_frame.pack(fill='both', expand=True)
        self.canvas = tk.Canvas(self.display_frame, bg='#010409', highlightthickness=0)
        self.canvas.pack(fill='both', expand=True)

        self._create_right_panel()

    def _create_toolbar_buttons(self, parent):
        # ---- 摄像头菜单 ----
        cam_btn = Button(parent, text="📷 摄像头", font=('微软雅黑', 10),
                         bg='#21262d', fg='#c9d1d9', relief='flat',
                         activebackground='#30363d', activeforeground='#ffffff',
                         padx=12, pady=4, cursor='hand2')
        cam_btn.pack(side='left', padx=(15,5), pady=8)
        cam_menu = Menu(cam_btn, tearoff=0, bg='#161b22', fg='#c9d1d9',
                        activebackground='#58a6ff', activeforeground='#ffffff',
                        font=('微软雅黑', 10), borderwidth=0)
        cam_menu.add_command(label="➕ 添加单个摄像头", command=self.add_single)
        cam_menu.add_command(label="➕➕ 批量添加摄像头", command=self.add_batch)
        cam_menu.add_command(label="🔍 扫描网段", command=self.scan_network)
        cam_menu.add_separator()
        cam_menu.add_command(label="🗑 删除摄像头", command=self.delete_cameras)
        cam_btn.bind("<Button-1>", lambda e: cam_menu.post(e.x_root, e.y_root + 35))

        # ---- 分组管理菜单 ----
        group_btn = Button(parent, text="📁 分组管理", font=('微软雅黑', 10),
                           bg='#21262d', fg='#c9d1d9', relief='flat',
                           activebackground='#30363d', activeforeground='#ffffff',
                           padx=12, pady=4, cursor='hand2')
        group_btn.pack(side='left', padx=5, pady=8)
        group_menu = Menu(group_btn, tearoff=0, bg='#161b22', fg='#c9d1d9',
                          activebackground='#58a6ff', activeforeground='#ffffff',
                          font=('微软雅黑', 10), borderwidth=0)
        group_menu.add_command(label="➕ 新增分组", command=self.add_group)
        group_menu.add_command(label="✏ 重命名分组", command=self.rename_group)
        group_menu.add_command(label="🗑 删除分组", command=self.del_group)
        group_btn.bind("<Button-1>", lambda e: group_menu.post(e.x_root, e.y_root + 35))

        # ---- 排序菜单 ----
        sort_btn = Button(parent, text="🔃 排序", font=('微软雅黑', 10),
                          bg='#21262d', fg='#c9d1d9', relief='flat',
                          activebackground='#30363d', activeforeground='#ffffff',
                          padx=12, pady=4, cursor='hand2')
        sort_btn.pack(side='left', padx=5, pady=8)
        sort_menu = Menu(sort_btn, tearoff=0, bg='#161b22', fg='#c9d1d9',
                         activebackground='#58a6ff', activeforeground='#ffffff',
                         font=('微软雅黑', 10), borderwidth=0)
        sort_menu.add_command(label="当前组 IP 排序", command=self.sort_current_group)
        sort_menu.add_command(label="所有组 IP 排序", command=self.sort_all_groups)
        sort_menu.add_command(label="分组名称排序", command=self.sort_groups_by_name)
        sort_btn.bind("<Button-1>", lambda e: sort_menu.post(e.x_root, e.y_root + 35))

        # ---- 工具菜单 ----
        tools_btn = Button(parent, text="🔧 工具", font=('微软雅黑', 10),
                           bg='#21262d', fg='#c9d1d9', relief='flat',
                           activebackground='#30363d', activeforeground='#ffffff',
                           padx=12, pady=4, cursor='hand2')
        tools_btn.pack(side='left', padx=5, pady=8)
        tools_menu = Menu(tools_btn, tearoff=0, bg='#161b22', fg='#c9d1d9',
                          activebackground='#58a6ff', activeforeground='#ffffff',
                          font=('微软雅黑', 10), borderwidth=0)
        tools_menu.add_command(label="⚙ 系统设置", command=self.open_settings)
        tools_menu.add_command(label="🔄 重启所有摄像头", command=self.reload_all_cameras)
        tools_menu.add_separator()
        tools_menu.add_command(label="✕ 退出", command=self.on_closing, foreground='#f85149')
        tools_btn.bind("<Button-1>", lambda e: tools_menu.post(e.x_root, e.y_root + 35))

        # ---- 分隔线 ----
        Frame(parent, width=1, bg='#30363d').pack(side='left', fill='y', padx=5, pady=10)

        # ---- 分页控件 ----
        self.prev_btn = Button(parent, text="◀", command=self.prev_page, relief='flat',
                               state=DISABLED, font=('微软雅黑', 11), bg='#21262d', fg='#c9d1d9',
                               activebackground='#30363d', width=3, cursor='hand2')
        self.prev_btn.pack(side='left', padx=2, pady=8)
        self.page_label = Label(parent, text="", font=('微软雅黑', 10), bg='#161b22',
                                fg='#58a6ff', width=14)
        self.page_label.pack(side='left', padx=5, pady=8)
        self.next_btn = Button(parent, text="▶", command=self.next_page, relief='flat',
                               state=DISABLED, font=('微软雅黑', 11), bg='#21262d', fg='#c9d1d9',
                               activebackground='#30363d', width=3, cursor='hand2')
        self.next_btn.pack(side='left', padx=2, pady=8)

        # ---- 独立的退出按钮 ----
        Button(parent, text="✕ 退出", command=self.on_closing, font=('微软雅黑', 10),
               bg='#da3633', fg='white', relief='flat',
               activebackground='#f85149', padx=12, pady=4, cursor='hand2').pack(side='right', padx=15, pady=8)

    def _create_right_panel(self):
        header = Frame(self.right_panel, bg='#161b22', height=40)
        header.pack(fill='x', side='top')
        header.pack_propagate(False)
        Label(header, text="📁 分组管理", bg='#161b22', fg='#c9d1d9',
              font=('微软雅黑', 12, 'bold')).pack(side='left', padx=15, pady=8)

        Label(self.right_panel, text="分组列表", bg='#0d1117', fg='#8b949e',
              font=('微软雅黑', 10)).pack(anchor='w', padx=10, pady=(10,2))
        frame_group = Frame(self.right_panel, bg='#0d1117')
        frame_group.pack(fill='x', padx=10, pady=5)
        scrollbar1 = Scrollbar(frame_group, bg='#0d1117', troughcolor='#161b22',
                               activebackground='#30363d', relief='flat')
        scrollbar1.pack(side='right', fill='y')
        self.group_listbox = Listbox(frame_group, bg='#0d1117', fg='#c9d1d9',
                                     selectbackground='#58a6ff', selectforeground='#ffffff',
                                     relief='flat', highlightthickness=1, highlightcolor='#30363d',
                                     highlightbackground='#30363d', font=('微软雅黑', 10),
                                     height=10, yscrollcommand=scrollbar1.set, exportselection=False)
        self.group_listbox.pack(side='left', fill='both', expand=True)
        scrollbar1.config(command=self.group_listbox.yview)
        self.group_listbox.bind('<<ListboxSelect>>', self.on_group_select)

        Label(self.right_panel, text="当前页摄像头（可多选）", bg='#0d1117', fg='#8b949e',
              font=('微软雅黑', 10)).pack(anchor='w', padx=10, pady=(15,2))
        frame_cam = Frame(self.right_panel, bg='#0d1117')
        frame_cam.pack(fill='both', expand=True, padx=10, pady=5)
        scrollbar2 = Scrollbar(frame_cam, bg='#0d1117', troughcolor='#161b22',
                               activebackground='#30363d', relief='flat')
        scrollbar2.pack(side='right', fill='y')
        self.cam_listbox = Listbox(frame_cam, bg='#0d1117', fg='#c9d1d9',
                                   selectbackground='#58a6ff', selectforeground='#ffffff',
                                   relief='flat', highlightthickness=1, highlightcolor='#30363d',
                                   highlightbackground='#30363d', font=('微软雅黑', 10),
                                   selectmode=MULTIPLE, height=12, yscrollcommand=scrollbar2.set,
                                   exportselection=False)
        self.cam_listbox.pack(side='left', fill='both', expand=True)
        scrollbar2.config(command=self.cam_listbox.yview)
        self.cam_listbox.bind("<Button-3>", self.show_camera_context_menu)

        btn_move = Button(self.right_panel, text="📤 批量移动选中", command=self.move_selected_cameras,
                          relief='flat', bg='#238636', fg='white', font=('微软雅黑', 10),
                          activebackground='#2ea043', cursor='hand2')
        btn_move.pack(pady=10, padx=10, fill='x')

        self.right_stats = Label(self.right_panel, text="", bg='#0d1117', fg='#8b949e',
                                 font=('微软雅黑', 9))
        self.right_stats.pack(side='bottom', pady=10)

        self.refresh_group_list()
        self.refresh_camera_list()

    def refresh_group_list(self):
        if not self.root.winfo_exists():
            return
        self.group_listbox.delete(0, END)
        for i, group in enumerate(self.group_mgr.get_group_list()):
            self.group_listbox.insert(END, group)
            if group == self.group_mgr.current_group:
                self.group_listbox.itemconfig(i, {'bg': '#161b22', 'fg': '#58a6ff'})
        idx = self.group_mgr.get_group_list().index(self.group_mgr.current_group)
        self.group_listbox.selection_set(idx)
        self.group_listbox.see(idx)

    def refresh_camera_list(self):
        if not self.root.winfo_exists():
            return
        self.cam_listbox.delete(0, END)
        current_ips = self.group_mgr.get_current_page_cameras()
        for ip in current_ips:
            cam = self.active_cameras.get(ip)
            if cam and cam.connected:
                status = "🟢"
            elif cam and cam.connecting:
                status = "🟡"
            else:
                status = "🔴"
            self.cam_listbox.insert(END, f"{status} {ip}")

    def on_group_select(self, event):
        selection = self.group_listbox.curselection()
        if selection:
            group = self.group_listbox.get(selection[0])
            if group != self.group_mgr.current_group:
                self.group_mgr.current_group = group
                self.group_mgr.current_page = 0
                self.group_mgr.save()
                self.refresh_group_list()
                self.update_page_controls()
                self.refresh_camera_list()

    def update_page_controls(self):
        if not self.root.winfo_exists():
            return
        total_pages = self.group_mgr.get_total_pages()
        total_cams = len(self.group_mgr.get_cameras_in_group(self.group_mgr.current_group))
        self.page_label.config(text=f"第 {self.group_mgr.current_page + 1} / {total_pages} 页")
        self.prev_btn.config(state=NORMAL if self.group_mgr.current_page > 0 else DISABLED)
        self.next_btn.config(state=NORMAL if self.group_mgr.current_page + 1 < total_pages else DISABLED)
        self.refresh_camera_list()
        active_count = len(self.active_cameras)
        total_all = sum(len(ips) for ips in self.group_mgr.groups.values())
        gpu_tag = f" | GPU: {GPU.info}" if GPU and GPU.is_available() else ""
        self.status_label.config(
            text=f"分组: {self.group_mgr.current_group} | 当前组: {total_cams}个 | "
                 f"活跃: {active_count}个 | 总计: {total_all}个{gpu_tag}"
        )
        self.right_stats.config(text=f"当前组共 {total_cams} 个摄像头\n系统总计 {total_all} 个摄像头")

    def prev_page(self):
        if self.group_mgr.prev_page():
            self.update_page_controls()

    def next_page(self):
        if self.group_mgr.next_page():
            self.update_page_controls()

    def add_group(self):
        name = simpledialog.askstring("添加分组", "请输入分组名称:", parent=self.root)
        if name:
            if self.group_mgr.add_group(name):
                self.refresh_group_list()
            else:
                messagebox.showerror("错误", "分组已存在")

    def rename_group(self):
        selection = self.group_listbox.curselection()
        if not selection:
            messagebox.showinfo("提示", "请先在右侧分组列表中选中一个分组")
            return
        old_name = self.group_listbox.get(selection[0])
        new_name = simpledialog.askstring("重命名", f"将 '{old_name}' 重命名为:", parent=self.root)
        if new_name and new_name != old_name:
            if self.group_mgr.rename_group(old_name, new_name):
                self.refresh_group_list()
            else:
                messagebox.showerror("错误", "名称已存在或无效")

    def del_group(self):
        selection = self.group_listbox.curselection()
        if not selection:
            messagebox.showinfo("提示", "请先在右侧分组列表中选中一个分组")
            return
        group = self.group_listbox.get(selection[0])
        if group == "默认分组":
            messagebox.showwarning("警告", "不能删除默认分组")
            return
        if messagebox.askyesno("确认", f"确定删除分组 '{group}'？\n该分组下的摄像头将移动到'默认分组'"):
            if self.group_mgr.delete_group(group):
                self.refresh_group_list()
                self.update_page_controls()
                self.refresh_camera_list()

    def sort_current_group(self):
        self.group_mgr.sort_current_group()
        self.update_page_controls()
        self.refresh_camera_list()
        messagebox.showinfo("排序完成", f"分组 '{self.group_mgr.current_group}' 已按 IP 排序")

    def sort_all_groups(self):
        self.group_mgr.sort_all_groups()
        self.refresh_group_list()
        self.update_page_controls()
        self.refresh_camera_list()
        messagebox.showinfo("排序完成", "所有分组已按 IP 排序")

    def sort_groups_by_name(self):
        self.group_mgr.sort_groups_by_name()
        self.refresh_group_list()
        self.update_page_controls()
        self.refresh_camera_list()
        messagebox.showinfo("排序完成", "分组已按名称排序（默认分组始终在最前）")

    def move_selected_cameras(self):
        selected = self.cam_listbox.curselection()
        if not selected:
            messagebox.showinfo("提示", "请先在右侧摄像头列表中选择要移动的摄像头")
            return
        ips_to_move = [self.cam_listbox.get(i).split()[-1] for i in selected]
        groups = self.group_mgr.get_group_list()
        if len(groups) <= 1:
            messagebox.showinfo("提示", "没有其他分组可以移动")
            return
        win = Toplevel(self.root)
        win.title("选择目标分组")
        win.geometry("280x350")
        win.configure(bg='#0d1117')
        win.transient(self.root)
        Label(win, text="📁 选择目标分组:", bg='#0d1117', fg='#c9d1d9',
              font=('微软雅黑', 11)).pack(pady=15)
        frame = Frame(win, bg='#0d1117')
        frame.pack(fill='both', expand=True, padx=20, pady=5)
        scrollbar = Scrollbar(frame, bg='#0d1117', troughcolor='#161b22')
        scrollbar.pack(side='right', fill='y')
        listbox = Listbox(frame, selectmode='single', bg='#0d1117', fg='#c9d1d9',
                          selectbackground='#58a6ff', selectforeground='#ffffff',
                          relief='flat', highlightthickness=1, highlightcolor='#30363d',
                          font=('微软雅黑', 10), yscrollcommand=scrollbar.set)
        for g in groups:
            if g != self.group_mgr.current_group:
                listbox.insert(END, g)
        listbox.pack(fill='both', expand=True)
        scrollbar.config(command=listbox.yview)
        def confirm():
            sel = listbox.curselection()
            if not sel:
                messagebox.showwarning("提示", "请选择一个分组")
                return
            target = listbox.get(sel[0])
            count = 0
            for ip in ips_to_move:
                if self.group_mgr.move_camera(ip, self.group_mgr.current_group, target):
                    if ip in self.active_cameras:
                        self.active_cameras[ip].stop()
                        del self.active_cameras[ip]
                    count += 1
            win.destroy()
            messagebox.showinfo("移动完成", f"成功移动 {count} 个摄像头到 '{target}'")
            self.refresh_group_list()
            self.update_page_controls()
            self.refresh_camera_list()
        Button(win, text="📤 确认移动", command=confirm, relief='flat',
               bg='#238636', fg='white', padx=20, pady=6, font=('微软雅黑', 10),
               activebackground='#2ea043', cursor='hand2').pack(pady=15)

    def show_camera_context_menu(self, event):
        idx = self.cam_listbox.nearest(event.y)
        if idx < 0:
            return
        ip = self.cam_listbox.get(idx).split()[-1]
        groups = self.group_mgr.get_group_list()
        if len(groups) <= 1:
            return
        menu = Menu(self.root, tearoff=0, bg='#161b22', fg='#c9d1d9',
                    activebackground='#58a6ff', activeforeground='#ffffff',
                    font=('微软雅黑', 10), borderwidth=0)
        menu.add_command(label=f"🔍 放大 {ip}", command=lambda: self.show_magnify_window(ip))
        menu.add_separator()
        move_menu = Menu(menu, tearoff=0, bg='#161b22', fg='#c9d1d9',
                         activebackground='#58a6ff', activeforeground='#ffffff',
                         font=('微软雅黑', 10), borderwidth=0)
        for g in groups:
            if g != self.group_mgr.current_group:
                move_menu.add_command(label=g, command=lambda tg=g: self.move_camera_and_refresh(ip, tg))
        menu.add_cascade(label="移动到分组", menu=move_menu)

        rotate_menu = Menu(menu, tearoff=0, bg='#161b22', fg='#c9d1d9',
                           activebackground='#58a6ff', activeforeground='#ffffff',
                           font=('微软雅黑', 10), borderwidth=0)
        for angle in [0, 90, 180, 270]:
            rotate_menu.add_command(label=f"{angle}°", command=lambda a=angle: self.set_rotation(ip, a))
        menu.add_cascade(label="旋转", menu=rotate_menu)

        flip_menu = Menu(menu, tearoff=0, bg='#161b22', fg='#c9d1d9',
                         activebackground='#58a6ff', activeforeground='#ffffff',
                         font=('微软雅黑', 10), borderwidth=0)
        flip_menu.add_command(label="水平翻转", command=lambda: self.set_flip(ip, 'h'))
        flip_menu.add_command(label="垂直翻转", command=lambda: self.set_flip(ip, 'v'))
        menu.add_cascade(label="翻转", menu=flip_menu)

        menu.add_separator()
        menu.add_command(label="🗑 删除", foreground='#f85149',
                         command=lambda: self.delete_single_camera(ip))
        menu.post(event.x_root, event.y_root)

    def move_camera_and_refresh(self, ip, target_group):
        if self.group_mgr.move_camera(ip, self.group_mgr.current_group, target_group):
            if ip in self.active_cameras:
                self.active_cameras[ip].stop()
                del self.active_cameras[ip]
            self.refresh_group_list()
            self.update_page_controls()
            self.refresh_camera_list()

    def set_rotation(self, ip, angle):
        for cam_info in self.config.get('saved_cameras', []):
            if cam_info['ip'] == ip:
                cam_info['rotation'] = angle
                break
        self.save_config()
        cam = self.active_cameras.get(ip)
        if cam:
            cam.rotation = angle

    def set_flip(self, ip, axis):
        for cam_info in self.config.get('saved_cameras', []):
            if cam_info['ip'] == ip:
                if axis == 'h':
                    cam_info['flip_h'] = not cam_info.get('flip_h', False)
                else:
                    cam_info['flip_v'] = not cam_info.get('flip_v', False)
                break
        self.save_config()
        cam = self.active_cameras.get(ip)
        if cam:
            if axis == 'h':
                cam.flip_h = not cam.flip_h
            else:
                cam.flip_v = not cam.flip_v

    def load_config(self):
        if os.path.exists(CONFIG_FILE):
            with open(CONFIG_FILE, 'r', encoding='utf-8') as f:
                saved = json.load(f)
                config = DEFAULT_CONFIG.copy()
                config.update(saved)
                for key in ["camera_groups", "current_group", "items_per_page", "saved_cameras",
                            "gpu_acceleration", "hw_decode", "gpu_mode"]:
                    if key not in config:
                        config[key] = DEFAULT_CONFIG[key]
                for cam in config.get('saved_cameras', []):
                    if 'rotation' not in cam:
                        cam['rotation'] = 0
                    if 'flip_h' not in cam:
                        cam['flip_h'] = False
                    if 'flip_v' not in cam:
                        cam['flip_v'] = False
                return config
        return DEFAULT_CONFIG.copy()

    def save_config(self):
        with open(CONFIG_FILE, 'w', encoding='utf-8') as f:
            json.dump(self.config, f, indent=2, ensure_ascii=False)

    def load_saved_cameras(self):
        all_ips = set()
        for ips in self.group_mgr.groups.values():
            all_ips.update(ips)
        saved_ips = {c['ip'] for c in self.config.get('saved_cameras', [])}
        for ip in all_ips:
            if ip not in saved_ips:
                self.config.setdefault('saved_cameras', []).append({
                    'ip': ip,
                    'user': self.config['default_user'],
                    'password': self.config['default_password'],
                    'rotation': 0,
                    'flip_h': False,
                    'flip_v': False
                })
        self.save_config()

    def add_camera(self, ip, user=None, password=None):
        if user is None:
            user = self.config['default_user']
        if password is None:
            password = self.config['default_password']
        with self.lock:
            existing = [c for c in self.config.get('saved_cameras', []) if c['ip'] == ip]
            if existing:
                return False
            cam_id = self.cam_id_counter
            self.cam_id_counter += 1
            self.config.setdefault('saved_cameras', []).append({
                'ip': ip, 'user': user, 'password': password,
                'rotation': 0, 'flip_h': False, 'flip_v': False
            })
            if ip not in self.group_mgr.groups[self.group_mgr.current_group]:
                self.group_mgr.groups[self.group_mgr.current_group].append(ip)
            self.group_mgr.save()
            self.save_config()
        def start_if_needed():
            if not self.root.winfo_exists():
                return
            current_ips = self.group_mgr.get_current_page_cameras()
            if ip in current_ips and ip not in self.active_cameras:
                hw = self.config.get("hw_decode", True)
                cam = CameraStream(cam_id, ip, user, password, self.config['connection_timeout_ms'], hw_decode=hw)
                for info in self.config.get('saved_cameras', []):
                    if info['ip'] == ip:
                        cam.rotation = info.get('rotation', 0)
                        cam.flip_h = info.get('flip_h', False)
                        cam.flip_v = info.get('flip_v', False)
                        break
                cam.start(self.config['rtsp_templates'])
                self.active_cameras[ip] = cam
            self.refresh_camera_list()
        self.root.after(0, start_if_needed)
        return True

    def delete_cameras(self):
        all_ips = []
        for ips in self.group_mgr.groups.values():
            all_ips.extend(ips)
        all_ips = sorted(set(all_ips), key=ip_to_key)
        if not all_ips:
            messagebox.showinfo("提示", "系统中没有摄像头")
            return
        win = Toplevel(self.root)
        win.title("删除摄像头")
        win.geometry("400x500")
        win.configure(bg='#0d1117')
        win.transient(self.root)
        Label(win, text="请选择要删除的摄像头（可多选）", bg='#0d1117', fg='#c9d1d9',
              font=('微软雅黑', 11)).pack(pady=10)
        frame = Frame(win, bg='#0d1117')
        frame.pack(fill='both', expand=True, padx=15, pady=5)
        scrollbar = Scrollbar(frame, bg='#0d1117', troughcolor='#161b22')
        scrollbar.pack(side='right', fill='y')
        listbox = Listbox(frame, selectmode=MULTIPLE, bg='#0d1117', fg='#c9d1d9',
                          selectbackground='#58a6ff', selectforeground='#ffffff',
                          relief='flat', highlightthickness=1, highlightcolor='#30363d',
                          font=('微软雅黑', 10), yscrollcommand=scrollbar.set)
        for ip in all_ips:
            listbox.insert(END, ip)
        listbox.pack(side='left', fill='both', expand=True)
        scrollbar.config(command=listbox.yview)
        def do_delete():
            selected = listbox.curselection()
            if not selected:
                win.destroy()
                return
            ips_to_delete = [listbox.get(i) for i in selected]
            for ip in ips_to_delete:
                for group, ips in list(self.group_mgr.groups.items()):
                    if ip in ips:
                        ips.remove(ip)
                if ip in self.active_cameras:
                    self.active_cameras[ip].stop()
                    del self.active_cameras[ip]
            self.config['saved_cameras'] = [c for c in self.config.get('saved_cameras', [])
                                            if c['ip'] not in ips_to_delete]
            self.group_mgr.save()
            self.save_config()
            win.destroy()
            messagebox.showinfo("删除完成", f"已删除 {len(ips_to_delete)} 个摄像头")
            self.update_page_controls()
            self.refresh_camera_list()
        Button(win, text="🗑 删除选中", command=do_delete, relief='flat',
               bg='#da3633', fg='white', padx=20, pady=6, font=('微软雅黑', 10),
               activebackground='#f85149', cursor='hand2').pack(pady=15)

    def delete_single_camera(self, ip):
        if not messagebox.askyesno("确认删除", f"确定删除摄像头 {ip}？\n此操作不可恢复。"):
            return
        for group, ips in list(self.group_mgr.groups.items()):
            if ip in ips:
                ips.remove(ip)
        if ip in self.active_cameras:
            self.active_cameras[ip].stop()
            del self.active_cameras[ip]
        self.config['saved_cameras'] = [c for c in self.config.get('saved_cameras', []) if c['ip'] != ip]
        self.group_mgr.save()
        self.save_config()
        self.update_page_controls()
        self.refresh_camera_list()

    def reload_all_cameras(self):
        def worker():
            for cam in list(self.active_cameras.values()):
                cam.stop()
            if self.root.winfo_exists():
                self.root.after(0, self.active_cameras.clear)
                self.root.after(0, lambda: messagebox.showinfo("重启完成", "所有摄像头已重新连接"))
        threading.Thread(target=worker, daemon=True).start()
        messagebox.showinfo("提示", "正在后台重新连接摄像头，请稍候...")

    def add_single(self):
        ip = simpledialog.askstring("添加摄像头", "请输入 IP 地址：", parent=self.root)
        if not ip:
            return
        user = simpledialog.askstring("用户名", f"用户名（默认：{self.config['default_user']}）：", parent=self.root)
        if user is None or user == "":
            user = self.config['default_user']
        pwd = simpledialog.askstring("密码", f"密码（默认：{self.config['default_password']}）：", parent=self.root,
                                     show='*')
        if pwd is None or pwd == "":
            pwd = self.config['default_password']
        def worker():
            success = self.add_camera(ip, user, pwd)
            if not success:
                if self.root.winfo_exists():
                    self.root.after(0, lambda: messagebox.showerror("错误", f"摄像头 {ip} 已存在"))
            else:
                if self.root.winfo_exists():
                    self.root.after(0, self.update_page_controls)
        threading.Thread(target=worker, daemon=True).start()

    def add_batch(self):
        range_str = simpledialog.askstring("批量添加", "请输入 IP 范围\n示例：192.168.31.88-120", parent=self.root)
        if not range_str:
            return
        try:
            if '-' not in range_str:
                messagebox.showerror("错误", "格式错误，缺少 '-'")
                return
            base, end_part = range_str.split('-')
            last_dot = base.rfind('.')
            if last_dot == -1:
                messagebox.showerror("错误", "无效的 IP 格式")
                return
            prefix = base[:last_dot + 1]
            start_num = int(base[last_dot + 1:])
            end_num = int(end_part.strip())
            if start_num > end_num:
                start_num, end_num = end_num, start_num
            user = simpledialog.askstring("批量添加", f"用户名（默认：{self.config['default_user']}）：", parent=self.root)
            if user is None or user == "":
                user = self.config['default_user']
            pwd = simpledialog.askstring("批量添加", f"密码（默认：{self.config['default_password']}）：", parent=self.root,
                                         show='*')
            if pwd is None or pwd == "":
                pwd = self.config['default_password']
            def worker():
                success = 0
                failed = []
                for num in range(start_num, end_num + 1):
                    ip = f"{prefix}{num}"
                    if self.add_camera(ip, user, pwd):
                        success += 1
                    else:
                        failed.append(ip)
                    time.sleep(0.05)
                msg = f"批量添加完成\n成功：{success}\n失败：{len(failed)}"
                if failed:
                    msg += f"\n失败IP：{', '.join(failed[:5])}"
                    if len(failed) > 5:
                        msg += f" 等共{len(failed)}个"
                if self.root.winfo_exists():
                    self.root.after(0, lambda: messagebox.showinfo("结果", msg))
                    self.root.after(0, self.update_page_controls)
            threading.Thread(target=worker, daemon=True).start()
            messagebox.showinfo("提示", "正在后台批量添加，请稍后查看结果")
        except Exception as e:
            messagebox.showerror("错误", f"批量添加失败：{e}")

    def _parse_ip_ranges(self, input_str):
        import ipaddress
        ranges = []
        for part in input_str.split(','):
            part = part.strip()
            if not part:
                continue
            if '/' in part:
                try:
                    network = ipaddress.ip_network(part, strict=False)
                    hosts = list(network.hosts())
                    if hosts:
                        ranges.append((str(hosts[0]), str(hosts[-1])))
                except Exception:
                    continue
                continue
            if '-' not in part:
                continue
            left, right = part.split('-', 1)
            left = left.strip()
            right = right.strip()
            if right.count('.') == 3:
                try:
                    start_ip = ipaddress.ip_address(left)
                    end_ip = ipaddress.ip_address(right)
                    if start_ip > end_ip:
                        start_ip, end_ip = end_ip, start_ip
                    current = int(start_ip)
                    end = int(end_ip)
                    while current <= end:
                        net_addr = ipaddress.ip_address((current // 256) * 256)
                        net_end = min(end, int(net_addr) + 255)
                        ranges.append((str(ipaddress.ip_address(current)),
                                       str(ipaddress.ip_address(net_end))))
                        current = net_end + 1
                except Exception:
                    continue
            else:
                try:
                    last_dot = left.rfind('.')
                    if last_dot == -1:
                        continue
                    prefix = left[:last_dot + 1]
                    start_num = int(left[last_dot + 1:])
                    end_num = int(right)
                    if start_num > end_num:
                        start_num, end_num = end_num, start_num
                    ranges.append((f"{prefix}{start_num}", f"{prefix}{end_num}"))
                except Exception:
                    continue
        return ranges

    def _get_local_networks(self):
        import socket
        import ipaddress
        networks = []
        try:
            import psutil
            for iface, addrs in psutil.net_if_addrs().items():
                for addr in addrs:
                    if addr.family == socket.AF_INET and not addr.address.startswith('127.'):
                        ip = addr.address
                        netmask = addr.netmask
                        if netmask:
                            network = ipaddress.ip_network(f"{ip}/{netmask}", strict=False)
                            hosts = list(network.hosts())
                            if len(hosts) > 1:
                                networks.append((str(hosts[0]), str(hosts[-1])))
        except ImportError:
            pass
        return networks

    def scan_network(self):
        scan_win = Toplevel(self.root)
        scan_win.title("扫描网段")
        scan_win.geometry("500x420")
        scan_win.configure(bg='#0d1117')
        scan_win.transient(self.root)
        scan_win.grab_set()
        Label(scan_win, text="🔍 扫描网段", bg='#0d1117', fg='#c9d1d9',
              font=('微软雅黑', 14, 'bold')).pack(pady=15)
        mode_var = StringVar(master=scan_win, value="manual")
        Radiobutton(scan_win, text="手动输入网段", variable=mode_var, value="manual",
                    bg='#0d1117', fg='#c9d1d9', selectcolor='#0d1117',
                    activebackground='#0d1117', font=('微软雅黑', 10)).pack(anchor='w', padx=30, pady=5)
        Label(scan_win, text="支持格式：192.168.1.1-254, 192.168.2.1-254, 10.0.0.0/24",
              bg='#0d1117', fg='#8b949e', font=('微软雅黑', 9)).pack(anchor='w', padx=50)
        text_input = Text(scan_win, height=4, width=50, bg='#161b22', fg='#c9d1d9',
                          insertbackground='#c9d1d9', relief='flat', highlightthickness=1,
                          highlightcolor='#58a6ff', font=('Consolas', 10))
        text_input.pack(padx=30, pady=5, fill='x')
        text_input.insert('1.0', '192.168.1.1-254')
        try:
            import psutil
            has_psutil = True
        except ImportError:
            has_psutil = False
        if has_psutil:
            Radiobutton(scan_win, text="自动扫描本机所有网段", variable=mode_var, value="auto",
                        bg='#0d1117', fg='#c9d1d9', selectcolor='#0d1117',
                        activebackground='#0d1117', font=('微软雅黑', 10)).pack(anchor='w', padx=30, pady=5)
        else:
            Label(scan_win, text="（安装 psutil 可自动扫描本机网段：pip install psutil）",
                  bg='#0d1117', fg='#484f58', font=('微软雅黑', 9)).pack(anchor='w', padx=30)
        Label(scan_win, text="用户名：", bg='#0d1117', fg='#c9d1d9',
              font=('微软雅黑', 10)).pack(anchor='w', padx=30, pady=(15,0))
        user_entry = Entry(scan_win, width=40, bg='#161b22', fg='#c9d1d9',
                           insertbackground='#c9d1d9', relief='flat', font=('微软雅黑', 10))
        user_entry.pack(padx=30, pady=2, fill='x')
        user_entry.insert(0, self.config['default_user'])
        Label(scan_win, text="密码：", bg='#0d1117', fg='#c9d1d9',
              font=('微软雅黑', 10)).pack(anchor='w', padx=30, pady=(10,0))
        pwd_entry = Entry(scan_win, width=40, bg='#161b22', fg='#c9d1d9',
                          insertbackground='#c9d1d9', relief='flat', show='*', font=('微软雅黑', 10))
        pwd_entry.pack(padx=30, pady=2, fill='x')
        pwd_entry.insert(0, self.config['default_password'])
        def do_scan():
            user = user_entry.get().strip() or self.config['default_user']
            pwd = pwd_entry.get().strip() or self.config['default_password']
            if mode_var.get() == "auto":
                ranges = self._get_local_networks()
                if not ranges:
                    messagebox.showwarning("提示", "未检测到本机网段，请手动输入")
                    return
            else:
                input_text = text_input.get('1.0', 'end').strip()
                ranges = self._parse_ip_ranges(input_text)
                if not ranges:
                    messagebox.showerror("错误", "无效的网段格式\n示例：192.168.1.1-254, 192.168.2.1-254")
                    return
            scan_win.destroy()
            self._execute_scan(ranges, user, pwd)
        Button(scan_win, text="开始扫描", command=do_scan, relief='flat',
               bg='#238636', fg='white', padx=30, pady=8, font=('微软雅黑', 11),
               activebackground='#2ea043', cursor='hand2').pack(pady=20)

    def _execute_scan(self, ranges, user, pwd):
        import ipaddress
        all_ips = []
        for start_ip, end_ip in ranges:
            try:
                start = int(ipaddress.ip_address(start_ip))
                end = int(ipaddress.ip_address(end_ip))
                for ip_int in range(start, end + 1):
                    all_ips.append(str(ipaddress.ip_address(ip_int)))
            except Exception:
                continue
        total = len(all_ips)
        if total == 0:
            messagebox.showinfo("提示", "没有需要扫描的 IP")
            return
        if total > 10000:
            if not messagebox.askyesno("确认", f"即将扫描 {total} 个 IP，这可能需要较长时间。\n是否继续？"):
                return
        progress_win = Toplevel(self.root)
        progress_win.title("扫描中")
        progress_win.geometry("500x300")
        progress_win.configure(bg='#0d1117')
        progress_win.transient(self.root)
        progress_win.resizable(False, False)
        Label(progress_win, text="🔍 正在扫描多个网段", bg='#0d1117', fg='#c9d1d9',
              font=('微软雅黑', 13, 'bold')).pack(pady=10)
        range_text = ", ".join([f"{s}-{e.split('.')[-1]}" for s, e in ranges[:3]])
        if len(ranges) > 3:
            range_text += f" 等共{len(ranges)}个网段"
        Label(progress_win, text=f"网段：{range_text}", bg='#0d1117', fg='#8b949e',
              font=('微软雅黑', 9)).pack()
        progress_bar = ttk.Progressbar(progress_win, orient='horizontal', length=400,
                                       mode='determinate', style='Dark.Horizontal.TProgressbar')
        progress_bar.pack(pady=15)
        progress_label = Label(progress_win, text="准备扫描...", bg='#0d1117', fg='#8b949e',
                               font=('微软雅黑', 10))
        progress_label.pack()
        stats_label = Label(progress_win, text="", bg='#0d1117', fg='#58a6ff',
                            font=('微软雅黑', 9))
        stats_label.pack(pady=5)
        cancel_flag = False
        def cancel():
            nonlocal cancel_flag
            cancel_flag = True
            progress_win.destroy()
        Button(progress_win, text="取消扫描", command=cancel, relief='flat',
               bg='#21262d', fg='#c9d1d9', padx=25, pady=6, font=('微软雅黑', 10),
               activebackground='#30363d', cursor='hand2').pack(pady=15)
        max_workers = min(100, max(50, total // 100))
        completed = 0
        found_ips = []
        lock = threading.Lock()
        def check_port(ip, port=554, timeout=0.3):
            if cancel_flag:
                return None
            try:
                sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
                sock.settimeout(timeout)
                result = sock.connect_ex((ip, port))
                sock.close()
                return ip if result == 0 else None
            except Exception:
                return None
        def update_progress():
            if not progress_win.winfo_exists():
                return
            progress_bar['value'] = (completed / total) * 100
            progress_label.config(text=f"已扫描：{completed}/{total} ({completed * 100 // total}%)")
            stats_label.config(text=f"发现摄像头：{len(found_ips)} 个 | 线程：{max_workers}")
        def run_scan():
            nonlocal completed
            with ThreadPoolExecutor(max_workers=max_workers) as executor:
                future_to_ip = {executor.submit(check_port, ip): ip for ip in all_ips}
                for future in as_completed(future_to_ip):
                    if cancel_flag:
                        executor.shutdown(wait=False, cancel_futures=True)
                        break
                    with lock:
                        completed += 1
                    if completed % 10 == 0 or completed == total:
                        self.root.after(0, update_progress)
                    result = future.result()
                    if result:
                        with lock:
                            found_ips.append(result)
            if not cancel_flag:
                self.root.after(0, update_progress)
                self.root.after(500, progress_win.destroy)
                self.root.after(600, lambda: self.show_scan_results(found_ips, user, pwd))
            else:
                self.root.after(0, lambda: messagebox.showinfo("扫描取消",
                                                               f"已扫描 {completed} 个 IP，发现 {len(found_ips)} 个摄像头"))
        threading.Thread(target=run_scan, daemon=True).start()

    def show_scan_results(self, found_ips, user, pwd):
        if not found_ips:
            messagebox.showinfo("扫描结果", "未发现可用的摄像头")
            return
        win = Toplevel(self.root)
        win.title("扫描结果")
        win.geometry("450x550")
        win.configure(bg='#0d1117')
        win.transient(self.root)
        Label(win, text=f"🔍 发现 {len(found_ips)} 个可用摄像头",
              bg='#0d1117', fg='#c9d1d9', font=('微软雅黑', 12, 'bold')).pack(pady=15)
        frame = Frame(win, bg='#0d1117')
        frame.pack(fill='both', expand=True, padx=20, pady=5)
        scrollbar = Scrollbar(frame, bg='#0d1117', troughcolor='#161b22')
        scrollbar.pack(side='right', fill='y')
        listbox = Listbox(frame, selectmode=MULTIPLE, bg='#0d1117', fg='#c9d1d9',
                          selectbackground='#58a6ff', selectforeground='#ffffff',
                          relief='flat', highlightthickness=1, highlightcolor='#30363d',
                          font=('微软雅黑', 10), yscrollcommand=scrollbar.set)
        for ip in found_ips:
            listbox.insert(END, ip)
        listbox.pack(fill='both', expand=True)
        scrollbar.config(command=listbox.yview)
        listbox.select_set(0, END)
        def add_selected():
            selected = listbox.curselection()
            if not selected:
                win.destroy()
                return
            ips_to_add = [listbox.get(i) for i in selected]
            existing_ips = {c['ip'] for c in self.config.get('saved_cameras', [])}
            new_ips = [ip for ip in ips_to_add if ip not in existing_ips]
            skipped = len(ips_to_add) - len(new_ips)
            added = 0
            for ip in new_ips:
                if self.add_camera(ip, user, pwd):
                    added += 1
            win.destroy()
            msg = f"成功添加 {added} 个摄像头"
            if skipped > 0:
                msg += f"\n跳过 {skipped} 个（已存在）"
            messagebox.showinfo("添加完成", msg)
            self.update_page_controls()
        Button(win, text="✅ 添加选中", command=add_selected, relief='flat',
               bg='#238636', fg='white', padx=20, pady=6, font=('微软雅黑', 10),
               activebackground='#2ea043', cursor='hand2').pack(pady=15)

    def open_settings(self):
        def on_save(new_config):
            old_items = self.config.get("items_per_page", 9)
            self.config.update(new_config)
            self.save_config()
            new_items = self.config.get("items_per_page", 9)
            if new_items != old_items:
                self.group_mgr.set_items_per_page(new_items)
                self.update_page_controls()
                self.refresh_camera_list()
            messagebox.showinfo("设置已保存",
                                "部分设置（如 RTSP 模板、连接超时）需要点击「重启所有摄像头」才能生效。\n"
                                "显示布局和 FPS 设置已立即生效。")
        def on_gpu_switch(new_mode):
            global GPU
            old_info = GPU.info if GPU else "None"
            GPU = init_gpu(preferred_mode=new_mode)
            messagebox.showinfo("GPU 切换", f"GPU 模式已切换\n之前：{old_info}\n当前：{GPU.info}")
            self.update_page_controls()
        SettingsWindow(self.root, self.config, on_save, on_gpu_switch)

    def sync_active_cameras(self):
        if not self.root.winfo_exists():
            return
        current_ips = set(self.group_mgr.get_current_page_cameras())
        to_stop = [ip for ip in list(self.active_cameras.keys()) if ip not in current_ips]
        for ip in to_stop:
            self.active_cameras[ip].stop()
            del self.active_cameras[ip]
        saved_info = {cam['ip']: cam for cam in self.config.get("saved_cameras", [])}
        hw = self.config.get("hw_decode", True)
        for ip in current_ips:
            if ip not in self.active_cameras:
                info = saved_info.get(ip, {})
                user = info.get('user', self.config['default_user'])
                pwd = info.get('password', self.config['default_password'])
                cam = CameraStream(self.cam_id_counter, ip, user, pwd, self.config['connection_timeout_ms'],
                                   hw_decode=hw)
                cam.rotation = info.get('rotation', 0)
                cam.flip_h = info.get('flip_h', False)
                cam.flip_v = info.get('flip_v', False)
                self.cam_id_counter += 1
                cam.start(self.config['rtsp_templates'])
                self.active_cameras[ip] = cam

    def render_grid(self, width, height):
        if not self.root.winfo_exists():
            return
        current_ips = self.group_mgr.get_current_page_cameras()
        num_cams = len(current_ips)
        self.canvas.delete("all")
        self.canvas._images = {}
        if num_cams == 0:
            self._layout_cache = None
            self.canvas.create_text(width // 2, height // 2, text="📷 暂无摄像头",
                                    fill="#8b949e", font=('微软雅黑', 16, 'bold'))
            self.canvas.create_text(width // 2, height // 2 + 35, text="点击「摄像头」菜单添加或切换分组",
                                    fill="#484f58", font=('微软雅黑', 11))
            return
        cols = min(self.config['max_cols'], num_cams)
        rows = (num_cams + cols - 1) // cols
        gap = 6
        cell_w = max((width - gap * (cols + 1)) // cols, 160)
        cell_h = max((height - gap * (rows + 1)) // rows, 120)
        self._layout_cache = {
            'num_cams': num_cams, 'cols': cols, 'rows': rows,
            'cell_w': cell_w, 'cell_h': cell_h, 'gap': gap
        }
        for idx, ip in enumerate(current_ips):
            row = idx // cols
            col = idx % cols
            x0 = gap + col * (cell_w + gap)
            y0 = gap + row * (cell_h + gap)
            self._draw_camera_cell(ip, x0, y0, cell_w, cell_h)

    def _draw_camera_cell(self, ip, x0, y0, w, h):
        if not self.root.winfo_exists():
            return
        cam = self.active_cameras.get(ip)
        frame = cam.get_frame() if cam else None
        if cam and frame is not None:
            frame = cam.apply_transform(frame)
        use_gpu = self.config.get("gpu_acceleration", True) and GPU and GPU.is_available()
        info_h = 26
        display_h = h - info_h
        if frame is not None and isinstance(frame, np.ndarray) and frame.size > 0:
            try:
                fh, fw = frame.shape[:2]
                scale = min((w - 4) / fw, display_h / fh)
                new_w = int(fw * scale)
                new_h = int(fh * scale)
                if use_gpu:
                    resized = GPU.resize(frame, (new_w, new_h))
                else:
                    resized = cv2.resize(frame, (new_w, new_h))
                cell_img = np.full((h, w, 3), (23, 17, 13), dtype=np.uint8)
                x_off = (w - new_w) // 2
                y_off = info_h + (display_h - new_h) // 2
                cell_img[y_off:y_off + new_h, x_off:x_off + new_w] = resized
                cv2.rectangle(cell_img, (0, 0), (w - 1, info_h), (34, 27, 22), -1)
                cv2.putText(cell_img, ip, (10, 18), cv2.FONT_HERSHEY_SIMPLEX, 0.5, (217, 209, 201), 1)
                if self.config['show_fps'] and cam:
                    fps = cam.get_fps()
                    color = (0, 255, 0) if fps > 15 else (0, 165, 255) if fps > 5 else (0, 0, 255)
                    cv2.putText(cell_img, f"{fps:.1f}fps", (w - 75, 18),
                                cv2.FONT_HERSHEY_SIMPLEX, 0.5, color, 1)
                cv2.circle(cell_img, (w - 95, 12), 4, (0, 255, 0), -1)
            except Exception as e:
                cell_img = self._create_error_cell(ip, w, h, "帧错误")
        else:
            cell_img = np.full((h, w, 3), (23, 17, 13), dtype=np.uint8)
            cv2.rectangle(cell_img, (0, 0), (w - 1, info_h), (34, 27, 22), -1)
            cv2.putText(cell_img, ip, (10, 18), cv2.FONT_HERSHEY_SIMPLEX, 0.5, (217, 209, 201), 1)
            if cam and cam.connecting:
                status = "连接中..."
                dot_color = (0, 165, 255)
            elif cam and not cam.connected:
                status = "连接失败"
                dot_color = (0, 0, 255)
            else:
                status = "无信号"
                dot_color = (158, 148, 139)
            cv2.putText(cell_img, status, (w // 2 - 50, h // 2 + 5),
                        cv2.FONT_HERSHEY_SIMPLEX, 0.6, (158, 148, 158), 1)
            cv2.circle(cell_img, (w - 95, 12), 4, dot_color, -1)
        cv2.rectangle(cell_img, (0, 0), (w - 1, h - 1), (61, 54, 48), 1)
        try:
            if use_gpu:
                img_rgb = GPU.cvtColor(cell_img, cv2.COLOR_BGR2RGB)
            else:
                img_rgb = cv2.cvtColor(cell_img, cv2.COLOR_BGR2RGB)
            img_pil = Image.fromarray(img_rgb)
            img_tk = ImageTk.PhotoImage(master=self.root, image=img_pil)
            self.canvas.create_image(x0, y0, anchor='nw', image=img_tk)
            self.canvas._images[ip] = img_tk
            self.canvas.create_rectangle(x0, y0, x0 + w, y0 + h,
                                         outline="", fill="", tags=(f"cam_{ip}",))
            self.canvas.tag_bind(f"cam_{ip}", "<Button-1>",
                                 lambda e, ip=ip: self.on_cam_click(ip))
            self.canvas.tag_bind(f"cam_{ip}", "<Button-3>",
                                 lambda e, ip=ip: self.on_cam_right_click(e, ip))
        except Exception as e:
            print(f"绘制摄像头 {ip} 失败: {e}")

    def _create_error_cell(self, ip, w, h, error_msg):
        info_h = 26
        cell_img = np.full((h, w, 3), (23, 17, 13), dtype=np.uint8)
        cv2.rectangle(cell_img, (0, 0), (w - 1, info_h), (34, 27, 22), -1)
        cv2.putText(cell_img, ip, (10, 18), cv2.FONT_HERSHEY_SIMPLEX, 0.5, (217, 209, 201), 1)
        cv2.putText(cell_img, error_msg, (w // 2 - 60, h // 2 + 5),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.5, (158, 148, 158), 1)
        cv2.circle(cell_img, (w - 95, 12), 4, (0, 0, 255), -1)
        cv2.rectangle(cell_img, (0, 0), (w - 1, h - 1), (61, 54, 48), 1)
        return cell_img

    def update_video(self):
        if not self.running or not self.root.winfo_exists():
            return
        try:
            self.sync_active_cameras()
            width = max(self.display_frame.winfo_width(), 640)
            height = max(self.display_frame.winfo_height(), 480)
            now = time.time()
            if now - self.last_render_ts >= self.render_interval:
                self.last_render_ts = now
                self.render_grid(width, height)
        except tk.TclError:
            self.running = False
            return
        except Exception as e:
            import traceback
            print(f"Update video error：{e}")
            traceback.print_exc()
        if self.running:
            try:
                self.root.after(80, self.update_video)
            except tk.TclError:
                self.running = False

    def on_cam_click(self, ip):
        self.show_magnify_window(ip)

    def on_cam_right_click(self, event, ip):
        if not self.root.winfo_exists():
            return
        menu = Menu(self.root, tearoff=0, bg='#161b22', fg='#c9d1d9',
                    activebackground='#58a6ff', activeforeground='#ffffff',
                    font=('微软雅黑', 10), borderwidth=0)
        menu.add_command(label="🔍 放大画面", command=lambda: self.show_magnify_window(ip))
        menu.add_separator()
        move_menu = Menu(menu, tearoff=0, bg='#161b22', fg='#c9d1d9',
                         activebackground='#58a6ff', activeforeground='#ffffff',
                         font=('微软雅黑', 10), borderwidth=0)
        for g in self.group_mgr.get_group_list():
            if g != self.group_mgr.current_group:
                move_menu.add_command(label=f"📁 {g}",
                                      command=lambda tg=g: self.move_camera_and_refresh(ip, tg))
        menu.add_cascade(label="移动到分组", menu=move_menu)

        rotate_menu = Menu(menu, tearoff=0, bg='#161b22', fg='#c9d1d9',
                           activebackground='#58a6ff', activeforeground='#ffffff',
                           font=('微软雅黑', 10), borderwidth=0)
        for angle in [0, 90, 180, 270]:
            rotate_menu.add_command(label=f"{angle}°", command=lambda a=angle: self.set_rotation(ip, a))
        menu.add_cascade(label="旋转", menu=rotate_menu)

        flip_menu = Menu(menu, tearoff=0, bg='#161b22', fg='#c9d1d9',
                         activebackground='#58a6ff', activeforeground='#ffffff',
                         font=('微软雅黑', 10), borderwidth=0)
        flip_menu.add_command(label="水平翻转", command=lambda: self.set_flip(ip, 'h'))
        flip_menu.add_command(label="垂直翻转", command=lambda: self.set_flip(ip, 'v'))
        menu.add_cascade(label="翻转", menu=flip_menu)

        menu.add_separator()
        menu.add_command(label="🗑 删除摄像头", foreground='#f85149',
                         command=lambda: self.delete_single_camera(ip))
        menu.post(event.x_root, event.y_root)

    def show_magnify_window(self, ip):
        if not self.root.winfo_exists():
            return
        saved_info = {cam['ip']: cam for cam in self.config.get("saved_cameras", [])}
        info = saved_info.get(ip, {})
        user = info.get('user', self.config['default_user'])
        pwd = info.get('password', self.config['default_password'])
        with self.lock:
            cam_id = self.cam_id_counter
            self.cam_id_counter += 1
        hw = self.config.get("hw_decode", True)
        cam = CameraStream(cam_id, ip, user, pwd, self.config['connection_timeout_ms'], hw_decode=hw)
        cam.rotation = info.get('rotation', 0)
        cam.flip_h = info.get('flip_h', False)
        cam.flip_v = info.get('flip_v', False)
        cam.start(self.config['rtsp_templates'])
        win = Toplevel(self.root)
        win.title(f"放大画面 - {ip}")
        win.geometry("960x720")
        win.minsize(400, 300)
        win.configure(bg='#0d1117')
        win.protocol("WM_DELETE_WINDOW", lambda: self._close_magnify(win, cam))
        win.bind("<Escape>", lambda e: self._close_magnify(win, cam))
        header = Frame(win, bg='#161b22', height=40)
        header.pack(fill='x', side='top')
        header.pack_propagate(False)
        status_dot = Label(header, text="●", bg='#161b22', fg='#238636', font=('微软雅黑', 12))
        status_dot.pack(side='left', padx=(15, 5))
        Label(header, text=ip, bg='#161b22', fg='#c9d1d9', font=('微软雅黑', 12, 'bold')).pack(side='left')
        close_btn = Button(header, text="✕", command=lambda: self._close_magnify(win, cam),
                           bg='#161b22', fg='#8b949e', relief='flat', font=('微软雅黑', 12),
                           activebackground='#f85149', activeforeground='white', cursor='hand2')
        close_btn.pack(side='right', padx=15)
        container = Frame(win, bg='#010409')
        container.pack(fill='both', expand=True, padx=2, pady=2)
        canvas = Canvas(container, bg='#010409', highlightthickness=0)
        canvas.pack(fill='both', expand=True)
        def on_drag_start(event):
            canvas.scan_mark(event.x, event.y)
        def on_drag_move(event):
            canvas.scan_dragto(event.x, event.y, gain=1)
        canvas.bind("<ButtonPress-1>", on_drag_start)
        canvas.bind("<B1-Motion>", on_drag_move)
        use_gpu = self.config.get("gpu_acceleration", True) and GPU and GPU.is_available()
        def update():
            if not win.winfo_exists():
                return
            try:
                frame = cam.get_frame()
                if frame is not None and isinstance(frame, np.ndarray) and frame.size > 0:
                    frame = cam.apply_transform(frame)
                    w = max(canvas.winfo_width(), 640)
                    h = max(canvas.winfo_height(), 480)
                    h_img, w_img = frame.shape[:2]
                    scale = min(w / w_img, h / h_img)
                    new_w = int(w_img * scale)
                    new_h = int(h_img * scale)
                    if use_gpu:
                        resized = GPU.resize(frame, (new_w, new_h))
                        img_rgb = GPU.cvtColor(resized, cv2.COLOR_BGR2RGB)
                    else:
                        resized = cv2.resize(frame, (new_w, new_h))
                        img_rgb = cv2.cvtColor(resized, cv2.COLOR_BGR2RGB)
                    img_pil = Image.fromarray(img_rgb)
                    img_tk = ImageTk.PhotoImage(master=win, image=img_pil)
                    canvas.delete("all")
                    canvas.create_image(0, 0, anchor='nw', image=img_tk)
                    canvas.image = img_tk
                    canvas.config(scrollregion=(0, 0, new_w, new_h))
                    fps = cam.get_fps()
                    if fps > 10:
                        status_dot.config(fg='#238636')
                    elif fps > 0:
                        status_dot.config(fg='#d29922')
                    else:
                        status_dot.config(fg='#8b949e')
            except Exception as e:
                print(f"放大窗口更新失败: {e}")
            win.after(50, update)
        update()

    def _close_magnify(self, win, cam):
        cam.stop()
        try:
            win.destroy()
        except Exception:
            pass

    def on_closing(self):
        self.running = False
        try:
            self.config['saved_cameras'] = list(self.config.get('saved_cameras', []))
            self.group_mgr.save()
            self.save_config()
        except Exception:
            pass
        for cam in list(self.active_cameras.values()):
            try:
                cam.stop()
            except Exception:
                pass
        self.active_cameras.clear()
        try:
            self.root.destroy()
        except Exception:
            pass

    def show_cam_menu(self, event):
        self.cam_menu.post(event.x_root, event.y_root + 35)

    def _update_clock(self):
        if not self.running or not self.root.winfo_exists():
            return
        now = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
        try:
            self.time_label.config(text=now)
        except Exception:
            pass
        try:
            self.root.after(1000, self._update_clock)
        except tk.TclError:
            self.running = False

# ================== 入口 ==================
def main():
    root = Tk()
    root.withdraw()
    app = CameraApp(root, splash=None)
    root.deiconify()
    root.mainloop()

if __name__ == "__main__":
    init_gpu(preferred_mode='auto')
    main()
```