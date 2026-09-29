#!/usr/bin/env python3
"""
TizenInstaller by Vilementar
Standalone desktop installer for Samsung Galaxy Watch (Tizen OS).
"""

import os
import re
import sys
import time
import shutil
import socket
import ipaddress
import zipfile
import threading
import subprocess
from io import BytesIO
from concurrent.futures import ThreadPoolExecutor, as_completed

import customtkinter as ctk
from PIL import Image, ImageDraw

# ═══════════════════════════════════════════════════════════════
#  PATHS & RESOLUTION
# ═══════════════════════════════════════════════════════════════

if getattr(sys, "frozen", False):
    BASE_DIR = sys._MEIPASS if hasattr(sys, "_MEIPASS") else os.path.dirname(sys.executable)
    APP_DIR = os.path.dirname(sys.executable)
else:
    BASE_DIR = os.path.dirname(os.path.abspath(__file__))
    APP_DIR = BASE_DIR

def resolve_sdb() -> str:
    candidates = [
        os.path.join(BASE_DIR, "sdb.exe"),
        os.path.join(APP_DIR, "sdb.exe"),
        os.path.join(BASE_DIR, "sdb"),
        os.path.join(APP_DIR, "sdb"),
        r"C:\Users\Vilementar\.gemini\antigravity\scratch\TizenWatchInstaller\sdb.exe",
        r"C:\tizen-studio\tools\sdb.exe",
        r"C:\Program Files\Galaxy Watch Studio\tizen\tools\sdb.exe",
    ]
    for c in candidates:
        if os.path.isfile(c):
            return c
    found = shutil.which("sdb")
    return found or "sdb"

SDB_BIN = resolve_sdb()
SDB_PORT = 26101
NOISE_RE = re.compile(r"\* The version of SDB.*?\*", re.S)

# ═══════════════════════════════════════════════════════════════
#  SDB WRAPPERS
# ═══════════════════════════════════════════════════════════════

def sdb_cmd(cmd_list, timeout=30):
    try:
        startupinfo = None
        if os.name == 'nt':
            startupinfo = subprocess.STARTUPINFO()
            startupinfo.dwFlags |= subprocess.STARTF_USESHOWWINDOW
            startupinfo.wShowWindow = subprocess.SW_HIDE

        p = subprocess.run(
            [SDB_BIN] + cmd_list,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=timeout,
            startupinfo=startupinfo
        )
        out = NOISE_RE.sub("", p.stdout or "").strip()
        err = NOISE_RE.sub("", p.stderr or "").strip()
        return p.returncode, out, err
    except Exception as e:
        return -1, "", str(e)

def sdb_shell(cmd_str, target=None, timeout=30):
    args = []
    if target:
        args.extend(["-s", target])
    args.extend(["shell", cmd_str])
    return sdb_cmd(args, timeout=timeout)

def sdb_push(local_path, remote_path, target=None, timeout=120):
    args = []
    if target:
        args.extend(["-s", target])
    args.extend(["push", local_path, remote_path])
    rc, out, err = sdb_cmd(args, timeout=timeout)
    return rc == 0

# ═══════════════════════════════════════════════════════════════
#  NETWORK DISCOVERY
# ═══════════════════════════════════════════════════════════════

def get_local_subnet():
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
    except Exception:
        ip = "192.168.1.1"
    base = ".".join(ip.split(".")[:3])
    return ip, f"{base}.0/24"

def probe_port(ip_str, port=SDB_PORT, timeout=0.5):
    try:
        sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        sock.settimeout(timeout)
        res = sock.connect_ex((ip_str, port))
        sock.close()
        if res == 0:
            return ip_str
    except Exception:
        pass
    return None

def scan_network_fast(cidr, progress_cb=None, workers=120):
    net = ipaddress.IPv4Network(cidr, strict=False)
    hosts = list(net.hosts())
    total = len(hosts)
    found = []
    completed = 0

    with ThreadPoolExecutor(max_workers=workers) as pool:
        futs = {pool.submit(probe_port, str(h)): h for h in hosts}
        for f in as_completed(futs):
            r = f.result()
            if r:
                found.append(r)
            completed += 1
            if progress_cb:
                progress_cb(completed / total)

    return sorted(found, key=lambda x: int(x.split(".")[-1]))

# ═══════════════════════════════════════════════════════════════
#  PACKAGE PARSER & METADATA
# ═══════════════════════════════════════════════════════════════

class TizenPackageInfo:
    def __init__(self, filepath):
        self.filepath = filepath
        self.filename = os.path.basename(filepath)
        self.is_wgt = self.filename.lower().endswith(".wgt")
        self.is_tpk = self.filename.lower().endswith(".tpk")
        self.pkgid = "Unknown"
        self.appid = "Unknown"
        self.app_name = os.path.splitext(self.filename)[0]
        self.version = "1.0.0"
        self.is_watchface = False
        self.icon_image = None
        self.size_mb = os.path.getsize(filepath) / (1024 * 1024)
        self._parse()

    def _parse(self):
        try:
            with zipfile.ZipFile(self.filepath, "r") as zf:
                names = zf.namelist()
                icon_data = None

                # TPK Manifest
                if "tizen-manifest.xml" in names:
                    manifest = zf.read("tizen-manifest.xml").decode("utf-8", errors="replace")
                    m_pkg = re.search(r'package\s*=\s*"([^"]+)"', manifest)
                    if m_pkg: self.pkgid = m_pkg.group(1)

                    m_ver = re.search(r'version\s*=\s*"([^"]+)"', manifest)
                    if m_ver: self.version = m_ver.group(1)

                    m_label = re.search(r'<label>([^<]+)</label>', manifest)
                    if m_label: self.app_name = m_label.group(1)

                    m_app = re.search(r'<watch-application[^>]+appid\s*=\s*"([^"]+)"', manifest)
                    if m_app:
                        self.appid = m_app.group(1)
                        self.is_watchface = True
                    else:
                        m_app2 = re.search(r'<ui-application[^>]+appid\s*=\s*"([^"]+)"', manifest)
                        if m_app2: self.appid = m_app2.group(1)

                    m_icon = re.search(r'<icon>([^<]+)</icon>', manifest)
                    if m_icon:
                        icon_name = m_icon.group(1)
                        for n in names:
                            if n.endswith(icon_name) and not n.startswith("__"):
                                icon_data = zf.read(n)
                                break

                # WGT Config
                elif "config.xml" in names:
                    config = zf.read("config.xml").decode("utf-8", errors="replace")
                    m_id = re.search(r'<widget[^>]+id\s*=\s*"([^"]+)"', config)
                    if m_id: self.pkgid = self.appid = m_id.group(1)

                    m_ver = re.search(r'version\s*=\s*"([^"]+)"', config)
                    if m_ver: self.version = m_ver.group(1)

                    m_name = re.search(r'<name>([^<]+)</name>', config)
                    if m_name: self.app_name = m_name.group(1)

                    if "watchface" in config.lower() or "clock" in config.lower():
                        self.is_watchface = True

                    m_icon = re.search(r'<icon\s+src\s*=\s*"([^"]+)"', config)
                    if m_icon:
                        icon_src = m_icon.group(1)
                        if icon_src in names:
                            icon_data = zf.read(icon_src)

                # Fallback icon search
                if not icon_data:
                    for n in names:
                        if n.lower().endswith((".png", ".jpg", ".webp")) and ("icon" in n.lower() or "logo" in n.lower()):
                            icon_data = zf.read(n)
                            break

                if icon_data:
                    im = Image.open(BytesIO(icon_data)).convert("RGBA")
                    self.icon_image = self._make_squircle(im, 80)
        except Exception:
            pass

    @staticmethod
    def _make_squircle(img, size=80):
        img = img.resize((size, size), Image.Resampling.LANCZOS)
        mask = Image.new("L", (size, size), 0)
        draw = ImageDraw.Draw(mask)
        draw.rounded_rectangle([(0, 0), (size, size)], radius=18, fill=255)
        out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        out.paste(img, (0, 0), mask)
        return out

# ═══════════════════════════════════════════════════════════════
#  MODERN THEME PALETTE
# ═══════════════════════════════════════════════════════════════

PALETTE = {
    "bg": "#0D0E12",
    "card": "#171920",
    "card_alt": "#1E212A",
    "border": "#282B37",
    "text": "#F5F6FA",
    "text_muted": "#8A90A2",
    "primary": "#0C66E4",
    "primary_hover": "#0052CC",
    "accent_subtle": "#16233B",
    "success_bg": "#122A1E",
    "success_text": "#22C55E",
    "success_border": "#1B4D33",
    "error_bg": "#33161A",
    "error_text": "#F87171",
    "console_bg": "#111217",
    "badge_bg": "#252936"
}

# ═══════════════════════════════════════════════════════════════
#  MAIN APPLICATION
# ═══════════════════════════════════════════════════════════════

class TizenInstallerApp(ctk.CTk):
    def __init__(self):
        super().__init__()

        self.title("TizenInstaller by Vilementar")
        self.geometry("980x760")
        self.minsize(860, 680)

        ctk.set_appearance_mode("Dark")
        ctk.set_default_color_theme("blue")
        self.configure(fg_color=PALETTE["bg"])

        self.sdb = SDB_BIN
        self.connected_ip = None
        self.device_model = "No device"
        self.package_info = None
        self.is_scanning = False
        self.is_installing = False

        icon_path = os.path.join(BASE_DIR, "app_icon.ico")
        if not os.path.exists(icon_path):
            icon_path = os.path.join(APP_DIR, "app_icon.ico")
        if os.path.exists(icon_path):
            try:
                self.iconbitmap(icon_path)
            except Exception:
                pass

        self._build_ui()
        self.after(300, self._init_sdb_check)

    def _build_ui(self):
        self.grid_columnconfigure(0, weight=1)
        self.grid_rowconfigure(0, weight=0) # Header
        self.grid_rowconfigure(1, weight=1) # Content Area

        # ─── 1. TOP HEADER ───
        header = ctk.CTkFrame(self, fg_color="transparent")
        header.grid(row=0, column=0, sticky="ew", padx=28, pady=(20, 10))
        header.grid_columnconfigure(0, weight=1)
        header.grid_columnconfigure(1, weight=0)

        title_box = ctk.CTkFrame(header, fg_color="transparent")
        title_box.grid(row=0, column=0, sticky="w")

        # Galaxy Watch Studio Logo
        icon_png_path = os.path.join(BASE_DIR, "app_icon.png")
        if not os.path.exists(icon_png_path):
            icon_png_path = os.path.join(APP_DIR, "app_icon.png")
        if os.path.exists(icon_png_path):
            try:
                h_img = Image.open(icon_png_path).convert("RGBA")
                h_img = TizenPackageInfo._make_squircle(h_img, 46)
                self.ctk_h_img = ctk.CTkImage(light_image=h_img, dark_image=h_img, size=(46, 46))
                lbl_logo = ctk.CTkLabel(title_box, text="", image=self.ctk_h_img)
                lbl_logo.pack(side="left", padx=(0, 14))
            except Exception:
                pass

        title_text_box = ctk.CTkFrame(title_box, fg_color="transparent")
        title_text_box.pack(side="left")

        lbl_title = ctk.CTkLabel(
            title_text_box,
            text="TizenInstaller",
            font=ctk.CTkFont(family="Segoe UI", size=24, weight="bold"),
            text_color=PALETTE["text"]
        )
        lbl_title.pack(anchor="w")

        lbl_subtitle = ctk.CTkLabel(
            title_text_box,
            text="by Vilementar • Galaxy Watch Studio SDB Tool",
            font=ctk.CTkFont(family="Segoe UI", size=13),
            text_color=PALETTE["text_muted"]
        )
        lbl_subtitle.pack(anchor="w")

        # Connection Status Pill
        self.pill_status = ctk.CTkFrame(
            header,
            fg_color=PALETTE["error_bg"],
            corner_radius=20,
            border_width=1,
            border_color=PALETTE["border"]
        )
        self.pill_status.grid(row=0, column=1, sticky="e")

        self.lbl_status_pill = ctk.CTkLabel(
            self.pill_status,
            text="● Disconnected",
            font=ctk.CTkFont(family="Segoe UI", size=12, weight="bold"),
            text_color=PALETTE["error_text"],
            padx=16,
            pady=6
        )
        self.lbl_status_pill.pack()

        # ─── 2. CONTENT LAYOUT ───
        content = ctk.CTkFrame(self, fg_color="transparent")
        content.grid(row=1, column=0, sticky="nsew", padx=28, pady=(0, 20))
        content.grid_columnconfigure(0, weight=6) # Left Control Panel
        content.grid_columnconfigure(1, weight=4) # Right Activity Log
        content.grid_rowconfigure(0, weight=1)

        left_col = ctk.CTkFrame(content, fg_color="transparent")
        left_col.grid(row=0, column=0, sticky="nsew", padx=(0, 14))
        left_col.grid_columnconfigure(0, weight=1)

        right_col = ctk.CTkFrame(content, fg_color="transparent")
        right_col.grid(row=0, column=1, sticky="nsew", padx=(14, 0))
        right_col.grid_columnconfigure(0, weight=1)
        right_col.grid_rowconfigure(0, weight=1)

        # ─── CARD A: DEVICE (WI-FI SDB) ───
        card_device = ctk.CTkFrame(
            left_col,
            fg_color=PALETTE["card"],
            corner_radius=18,
            border_width=1,
            border_color=PALETTE["border"]
        )
        card_device.pack(fill="x", pady=(0, 16))

        dev_title_bar = ctk.CTkFrame(card_device, fg_color="transparent")
        dev_title_bar.pack(fill="x", padx=20, pady=(16, 8))

        ctk.CTkLabel(
            dev_title_bar,
            text="Watch Connection (Wi-Fi)",
            font=ctk.CTkFont(family="Segoe UI", size=15, weight="bold"),
            text_color=PALETTE["text"]
        ).pack(side="left")

        self.lbl_device_model = ctk.CTkLabel(
            dev_title_bar,
            text="SM-R800",
            font=ctk.CTkFont(family="Segoe UI", size=12),
            text_color=PALETTE["primary"],
            fg_color=PALETTE["accent_subtle"],
            corner_radius=8,
            padx=8,
            pady=2
        )
        self.lbl_device_model.pack(side="right")

        # Inputs Grid
        input_frame = ctk.CTkFrame(card_device, fg_color="transparent")
        input_frame.pack(fill="x", padx=20, pady=(0, 12))
        input_frame.grid_columnconfigure(0, weight=3)
        input_frame.grid_columnconfigure(1, weight=1)

        self.entry_ip = ctk.CTkEntry(
            input_frame,
            placeholder_text="Watch IP (e.g. 192.168.1.49)",
            height=38,
            corner_radius=12,
            border_width=1,
            border_color=PALETTE["border"],
            fg_color=PALETTE["card_alt"],
            font=ctk.CTkFont(family="Segoe UI", size=13)
        )
        self.entry_ip.grid(row=0, column=0, sticky="ew", padx=(0, 8))
        self.entry_ip.insert(0, "192.168.1.49")

        self.entry_port = ctk.CTkEntry(
            input_frame,
            placeholder_text="26101",
            height=38,
            corner_radius=12,
            border_width=1,
            border_color=PALETTE["border"],
            fg_color=PALETTE["card_alt"],
            font=ctk.CTkFont(family="Segoe UI", size=13)
        )
        self.entry_port.grid(row=0, column=1, sticky="ew")
        self.entry_port.insert(0, "26101")

        # Buttons
        btn_frame = ctk.CTkFrame(card_device, fg_color="transparent")
        btn_frame.pack(fill="x", padx=20, pady=(0, 16))
        btn_frame.grid_columnconfigure(0, weight=1)
        btn_frame.grid_columnconfigure(1, weight=1)

        self.btn_scan = ctk.CTkButton(
            btn_frame,
            text="🔍 Scan Network (Auto)",
            height=36,
            corner_radius=12,
            fg_color=PALETTE["card_alt"],
            hover_color=PALETTE["border"],
            font=ctk.CTkFont(family="Segoe UI", size=13),
            command=self._start_scan
        )
        self.btn_scan.grid(row=0, column=0, sticky="ew", padx=(0, 8))

        self.btn_connect = ctk.CTkButton(
            btn_frame,
            text="Connect to Watch",
            height=36,
            corner_radius=12,
            fg_color=PALETTE["primary"],
            hover_color=PALETTE["primary_hover"],
            font=ctk.CTkFont(family="Segoe UI", size=13, weight="bold"),
            command=self._toggle_connect
        )
        self.btn_connect.grid(row=0, column=1, sticky="ew")

        self.scan_progress = ctk.CTkProgressBar(card_device, height=3, corner_radius=2)
        self.scan_progress.pack(fill="x", padx=20, pady=(0, 14))
        self.scan_progress.set(0)

        # ─── CARD B: PACKAGE (.TPK / .WGT) ───
        card_pkg = ctk.CTkFrame(
            left_col,
            fg_color=PALETTE["card"],
            corner_radius=18,
            border_width=1,
            border_color=PALETTE["border"]
        )
        card_pkg.pack(fill="x", pady=(0, 16))

        pkg_header = ctk.CTkFrame(card_pkg, fg_color="transparent")
        pkg_header.pack(fill="x", padx=20, pady=(16, 12))

        ctk.CTkLabel(
            pkg_header,
            text="Target Package (.tpk / .wgt)",
            font=ctk.CTkFont(family="Segoe UI", size=15, weight="bold"),
            text_color=PALETTE["text"]
        ).pack(side="left")

        btn_browse = ctk.CTkButton(
            pkg_header,
            text="Browse File...",
            height=30,
            width=110,
            corner_radius=10,
            fg_color=PALETTE["primary"],
            hover_color=PALETTE["primary_hover"],
            font=ctk.CTkFont(family="Segoe UI", size=12),
            command=self._browse_file
        )
        btn_browse.pack(side="right")

        self.pkg_display = ctk.CTkFrame(card_pkg, fg_color=PALETTE["card_alt"], corner_radius=14)
        self.pkg_display.pack(fill="x", padx=20, pady=(0, 16))

        self.lbl_icon = ctk.CTkLabel(self.pkg_display, text="", width=64, height=64)
        self.lbl_icon.pack(side="left", padx=16, pady=16)

        info_box = ctk.CTkFrame(self.pkg_display, fg_color="transparent")
        info_box.pack(side="left", fill="both", expand=True, pady=14)

        self.lbl_app_name = ctk.CTkLabel(
            info_box,
            text="No package selected",
            font=ctk.CTkFont(family="Segoe UI", size=15, weight="bold"),
            text_color=PALETTE["text"],
            anchor="w"
        )
        self.lbl_app_name.pack(fill="x")

        self.lbl_app_meta = ctk.CTkLabel(
            info_box,
            text="Select or drop a .tpk or .wgt watch face file",
            font=ctk.CTkFont(family="Segoe UI", size=12),
            text_color=PALETTE["text_muted"],
            anchor="w"
        )
        self.lbl_app_meta.pack(fill="x", pady=(2, 0))

        # ─── CARD C: INSTALLATION & OPTIONS ───
        card_install = ctk.CTkFrame(
            left_col,
            fg_color=PALETTE["card"],
            corner_radius=18,
            border_width=1,
            border_color=PALETTE["border"]
        )
        card_install.pack(fill="x")

        ctk.CTkLabel(
            card_install,
            text="Installation Options",
            font=ctk.CTkFont(family="Segoe UI", size=15, weight="bold"),
            text_color=PALETTE["text"]
        ).pack(anchor="w", padx=20, pady=(16, 8))

        self.switch_profile = ctk.CTkSwitch(
            card_install,
            text="Auto-sync device-profile.xml",
            font=ctk.CTkFont(family="Segoe UI", size=12),
            progress_color=PALETTE["primary"]
        )
        self.switch_profile.pack(anchor="w", padx=20, pady=4)
        self.switch_profile.select()

        self.switch_autolaunch = ctk.CTkSwitch(
            card_install,
            text="Auto-launch watch face after install (wf_set)",
            font=ctk.CTkFont(family="Segoe UI", size=12),
            progress_color=PALETTE["primary"]
        )
        self.switch_autolaunch.pack(anchor="w", padx=20, pady=(4, 16))
        self.switch_autolaunch.select()

        # Primary Action Button
        self.btn_install = ctk.CTkButton(
            card_install,
            text="INSTALL ON WATCH",
            height=48,
            corner_radius=14,
            fg_color=PALETTE["primary"],
            hover_color=PALETTE["primary_hover"],
            font=ctk.CTkFont(family="Segoe UI", size=15, weight="bold"),
            command=self._start_install
        )
        self.btn_install.pack(fill="x", padx=20, pady=(0, 20))

        # ─── RIGHT COL: ACTIVITY LOG ───
        card_log = ctk.CTkFrame(
            right_col,
            fg_color=PALETTE["card"],
            corner_radius=18,
            border_width=1,
            border_color=PALETTE["border"]
        )
        card_log.pack(fill="both", expand=True)

        log_top = ctk.CTkFrame(card_log, fg_color="transparent")
        log_top.pack(fill="x", padx=16, pady=(16, 8))

        ctk.CTkLabel(
            log_top,
            text="Activity Log (SDB)",
            font=ctk.CTkFont(family="Segoe UI", size=15, weight="bold"),
            text_color=PALETTE["text"]
        ).pack(side="left")

        btn_clear = ctk.CTkButton(
            log_top,
            text="Clear",
            height=26,
            width=65,
            corner_radius=8,
            fg_color=PALETTE["card_alt"],
            hover_color=PALETTE["border"],
            font=ctk.CTkFont(family="Segoe UI", size=11),
            command=self._clear_log
        )
        btn_clear.pack(side="right")

        self.txt_log = ctk.CTkTextbox(
            card_log,
            fg_color=PALETTE["console_bg"],
            text_color="#CBD5E1",
            font=ctk.CTkFont(family="Consolas", size=12),
            corner_radius=12,
            border_width=1,
            border_color=PALETTE["border"]
        )
        self.txt_log.pack(fill="both", expand=True, padx=16, pady=(0, 16))

        self._set_default_icon()

    def _set_default_icon(self):
        icon_png_path = os.path.join(BASE_DIR, "app_icon.png")
        if not os.path.exists(icon_png_path):
            icon_png_path = os.path.join(APP_DIR, "app_icon.png")
        if os.path.exists(icon_png_path):
            try:
                gws_img = Image.open(icon_png_path).convert("RGBA")
                gws_img = TizenPackageInfo._make_squircle(gws_img, 64)
                ctk_img = ctk.CTkImage(light_image=gws_img, dark_image=gws_img, size=(64, 64))
                self.lbl_icon.configure(image=ctk_img)
                return
            except Exception:
                pass
        img = Image.new("RGBA", (64, 64), (30, 33, 42, 255))
        draw = ImageDraw.Draw(img)
        draw.rounded_rectangle([(0, 0), (64, 64)], radius=16, fill=(40, 44, 55, 255))
        draw.rectangle([(24, 20), (40, 44)], outline=(120, 130, 150, 255), width=2)
        ctk_img = ctk.CTkImage(light_image=img, dark_image=img, size=(64, 64))
        self.lbl_icon.configure(image=ctk_img)

    def log(self, text, tag="INFO"):
        ts = time.strftime("%H:%M:%S")
        prefix = f"[{ts}] "
        if tag == "OK":
            line = f"{prefix}✔ {text}\n"
        elif tag == "ERR":
            line = f"{prefix}✖ {text}\n"
        elif tag == "WARN":
            line = f"{prefix}⚠ {text}\n"
        elif tag == "STEP":
            line = f"\n{prefix}▶ {text}\n"
        else:
            line = f"{prefix}{text}\n"

        def _append():
            self.txt_log.insert("end", line)
            self.txt_log.see("end")

        self.after(0, _append)

    def _clear_log(self):
        self.txt_log.delete("1.0", "end")

    # ═══════════════════════════════════════════════════════════════
    #  SDB ENGINE LOGIC
    # ═══════════════════════════════════════════════════════════════

    def _init_sdb_check(self):
        self.log(f"SDB binary: {self.sdb}")
        threading.Thread(target=self._check_active_connection, daemon=True).start()

    def _check_active_connection(self):
        rc, out, err = sdb_cmd(["devices"])
        m = re.search(r"(\d+\.\d+\.\d+\.\d+:\d+)\s+device\s*([^\r\n]*)", out)
        if m:
            ip_port = m.group(1)
            model = m.group(2).strip() or "SM-R800"
            self.connected_ip = ip_port
            self.device_model = model
            self.after(0, lambda: self._update_connection_state(True))
            self.log(f"Active connection detected: {ip_port} ({model})", "OK")
        else:
            self.after(0, lambda: self._update_connection_state(False))

    def _update_connection_state(self, connected):
        if connected:
            self.pill_status.configure(fg_color=PALETTE["success_bg"])
            self.lbl_status_pill.configure(
                text=f"● Connected: {self.connected_ip}",
                text_color=PALETTE["success_text"]
            )
            self.lbl_device_model.configure(text=self.device_model)
            self.btn_connect.configure(
                text="Disconnect",
                fg_color=PALETTE["card_alt"],
                hover_color=PALETTE["border"]
            )
        else:
            self.pill_status.configure(fg_color=PALETTE["error_bg"])
            self.lbl_status_pill.configure(
                text="● Disconnected",
                text_color=PALETTE["error_text"]
            )
            self.btn_connect.configure(
                text="Connect to Watch",
                fg_color=PALETTE["primary"],
                hover_color=PALETTE["primary_hover"]
            )

    def _toggle_connect(self):
        if self.connected_ip:
            target = self.connected_ip
            self.log(f"Disconnecting {target}...", "INFO")
            sdb_cmd(["disconnect", target])
            self.connected_ip = None
            self._update_connection_state(False)
            self.log("Disconnected.", "WARN")
        else:
            ip = self.entry_ip.get().strip()
            port = self.entry_port.get().strip() or "26101"
            if not ip:
                self.log("Please enter the watch IP address!", "ERR")
                return

            target = f"{ip}:{port}"
            self.btn_connect.configure(state="disabled", text="Connecting...")
            threading.Thread(target=self._do_connect, args=(target,), daemon=True).start()

    def _do_connect(self, target):
        self.log(f"Connecting to {target}...", "INFO")
        rc, out, err = sdb_cmd(["connect", target], timeout=15)
        self.log(f"SDB response: {out or err}")

        rc, dev_out, _ = sdb_cmd(["devices"])
        if target in dev_out and "device" in dev_out:
            m = re.search(re.escape(target) + r"\s+device\s*([^\r\n]*)", dev_out)
            model = m.group(1).strip() if m else "Galaxy Watch"
            self.connected_ip = target
            self.device_model = model or "SM-R800"
            self.after(0, lambda: self._update_connection_state(True))
            self.log(f"Connected and authorized: {target}", "OK")
        else:
            self.connected_ip = None
            self.after(0, lambda: self._update_connection_state(False))
            self.log("Connection failed. Make sure Wi-Fi debugging is enabled on watch.", "ERR")

        self.after(0, lambda: self.btn_connect.configure(state="normal"))

    def _start_scan(self):
        if self.is_scanning: return
        self.is_scanning = True
        self.btn_scan.configure(state="disabled", text="Scanning...")
        self.scan_progress.set(0)
        threading.Thread(target=self._do_scan, daemon=True).start()

    def _do_scan(self):
        local_ip, cidr = get_local_subnet()
        self.log(f"Scanning subnet {cidr} on port {SDB_PORT}...", "INFO")

        def on_prog(v):
            self.after(0, lambda: self.scan_progress.set(v))

        t0 = time.time()
        found = scan_network_fast(cidr, progress_cb=on_prog)
        dur = time.time() - t0

        self.after(0, lambda: self.scan_progress.set(1.0))
        self.log(f"Scan completed in {dur:.1f}s. Devices found: {len(found)}")

        if found:
            watch_ip = found[0]
            self.log(f"Detected watch: {watch_ip}", "OK")
            self.after(0, lambda: self.entry_ip.delete(0, "end"))
            self.after(0, lambda: self.entry_ip.insert(0, watch_ip))
            if not self.connected_ip:
                self._do_connect(f"{watch_ip}:{SDB_PORT}")
        else:
            self.log("No Tizen devices found in subnet. Enter IP manually.", "WARN")

        self.is_scanning = False
        self.after(0, lambda: self.btn_scan.configure(state="normal", text="🔍 Scan Network (Auto)"))

    # ═══════════════════════════════════════════════════════════════
    #  FILE HANDLING & METADATA
    # ═══════════════════════════════════════════════════════════════

    def _browse_file(self):
        from tkinter import filedialog
        path = filedialog.askopenfilename(
            title="Select Tizen Package",
            filetypes=[("Tizen Packages (*.tpk, *.wgt)", "*.tpk *.wgt"), ("All Files", "*.*")]
        )
        if path:
            self._load_package(path)

    def _load_package(self, path):
        self.log(f"Loading package: {path}")
        info = TizenPackageInfo(path)
        self.package_info = info

        self.lbl_app_name.configure(text=info.app_name)
        type_str = "Watch Face" if info.is_watchface else ("TPK App" if info.is_tpk else "WGT Widget")
        meta_str = f"ID: {info.pkgid} • Version: {info.version} • {type_str} ({info.size_mb:.2f} MB)"
        self.lbl_app_meta.configure(text=meta_str)

        if info.icon_image:
            ctk_img = ctk.CTkImage(light_image=info.icon_image, dark_image=info.icon_image, size=(64, 64))
            self.lbl_icon.configure(image=ctk_img)
        else:
            self._set_default_icon()

        self.log(f"Identified: {info.app_name} [{info.pkgid}] v{info.version}", "OK")

    # ═══════════════════════════════════════════════════════════════
    #  INSTALLATION ENGINE (4-STEP ESCALATION)
    # ═══════════════════════════════════════════════════════════════

    def _start_install(self):
        if self.is_installing: return
        if not self.package_info:
            self.log("Please select a .tpk or .wgt file first!", "ERR")
            return
        if not self.connected_ip:
            self.log("Watch is not connected! Connect to the watch IP address.", "ERR")
            return

        self.is_installing = True
        self.btn_install.configure(state="disabled", text="Installing...")
        threading.Thread(target=self._run_install_pipeline, daemon=True).start()

    def _run_install_pipeline(self):
        pkg = self.package_info
        target = self.connected_ip
        fname = pkg.filename
        ptype = "wgt" if pkg.is_wgt else "tpk"
        remote_path = f"/opt/usr/apps/tmp/{fname}"

        self.log(f"Starting installation process for {fname}...", "STEP")

        # 1. Sync device-profile.xml if enabled
        if self.switch_profile.get() and ptype == "tpk":
            self._sync_device_profile(target)

        # 2. Push package to watch
        self.log(f"Pushing {fname} to watch...")
        sdb_shell("mkdir -p /opt/usr/apps/tmp && chmod 777 /opt/usr/apps/tmp", target=target)
        if not sdb_push(pkg.filepath, remote_path, target=target):
            self.log("Failed to push file to watch (check disk space or Wi-Fi)!", "ERR")
            self._finish_install(False)
            return

        sdb_shell(f"chmod 666 {remote_path}", target=target)
        self.log("File transferred successfully.", "OK")

        installed_ok = False
        method_used = None

        # ── STEP 1 (DEFAULT): MOUNT-INSTALL (-w) ──
        self.log("Step 1/4: Mount-install bypass (pkgcmd -i -w) [DEFAULT]...", "STEP")
        rc, out, err = sdb_shell(f"pkgcmd -i -t {ptype} -p {remote_path} -w", target=target, timeout=90)
        ok, code, msg = self._parse_pkgcmd(f"{out}\n{err}")
        if ok:
            installed_ok = True
            method_used = "mount-install (-w)"
        else:
            self.log(f"Mount-install failed: code={code} ({msg}). Trying fallbacks...", "WARN")

        # ── STEP 2 (FALLBACK): STANDARD INSTALL ──
        if not installed_ok:
            self.log("Step 2/4: Standard install fallback (pkgcmd -i)...", "STEP")
            rc, out, err = sdb_shell(f"pkgcmd -i -t {ptype} -p {remote_path}", target=target, timeout=90)
            ok, code, msg = self._parse_pkgcmd(f"{out}\n{err}")
            if ok:
                installed_ok = True
                method_used = "standard"
            else:
                self.log(f"Standard install failed: code={code} ({msg})", "WARN")

        # ── STEP 3 (FALLBACK): DEBUG MODE (-G) ──
        if not installed_ok:
            self.log("Step 3/4: Debug mode fallback (pkgcmd -i -G)...", "STEP")
            rc, out, err = sdb_shell(f"pkgcmd -i -t {ptype} -p {remote_path} -G", target=target, timeout=90)
            ok, code, msg = self._parse_pkgcmd(f"{out}\n{err}")
            if ok:
                installed_ok = True
                method_used = "debug-mode (-G)"
            else:
                self.log(f"Debug install failed: code={code} ({msg})", "WARN")

        # ── STEP 4 (FALLBACK): REINSTALL CLEAN ──
        if not installed_ok and pkg.pkgid != "Unknown":
            self.log(f"Step 4/4: Clean reinstall (uninstalling {pkg.pkgid} + mount-install)...", "STEP")
            sdb_shell(f"pkgcmd -u -n {pkg.pkgid}", target=target, timeout=30)
            rc, out, err = sdb_shell(f"pkgcmd -i -t {ptype} -p {remote_path} -w", target=target, timeout=90)
            ok, code, msg = self._parse_pkgcmd(f"{out}\n{err}")
            if ok:
                installed_ok = True
                method_used = "reinstall (-w)"
            else:
                self.log(f"Reinstall failed: code={code} ({msg})", "ERR")

        # Summary
        if installed_ok:
            self.log(f"SUCCESS! Installed via method: {method_used}", "OK")

            # Auto Launch if Watch Face
            if self.switch_autolaunch.get() and pkg.is_watchface and pkg.pkgid != "Unknown":
                self.log(f"Activating watch face {pkg.pkgid} on display...")
                sdb_shell(f'launch_app com.samsung.w-home watchface wf_set wf_id "{pkg.pkgid}"', target=target)
                self.log("Watch face launched successfully!", "OK")
            self._finish_install(True)
        else:
            self.log("Installation failed across all 4 methods.", "ERR")
            if code == "-32":
                self.log("Cause: Certificate not registered for this watch DUID.", "WARN")
            elif code == "-3":
                self.log("Cause: Invalid Root CA chain for distributor certificate.", "WARN")
            self._finish_install(False)

    def _sync_device_profile(self, target):
        candidate_profiles = [
            r"C:\Users\Vilementar\SamsungCertificate\jj\device-profile.xml",
            r"C:\Users\Vilementar\SamsungCertificate\Samsung\device-profile.xml",
            r"C:\Users\Vilementar\GearWatchDesigner\keystore\device-profile.xml",
        ]
        for p in candidate_profiles:
            if os.path.isfile(p):
                self.log(f"Syncing device profile: {os.path.basename(p)}")
                sdb_shell("mkdir -p /home/owner/share/tmp/sdk_tools && chmod 777 /home/owner/share/tmp/sdk_tools", target=target)
                sdb_push(p, "/home/owner/share/tmp/sdk_tools/device-profile.xml", target=target)
                sdb_shell("chmod 666 /home/owner/share/tmp/sdk_tools/device-profile.xml", target=target)
                break

    def _parse_pkgcmd(self, text):
        if "key[end] val[ok]" in text:
            return True, "0", "OK"
        m_err = re.search(r"key\[error\]\s+val\[(-?\d+)\](?:\s+error message:\s*:?([^\n\r]+))?", text)
        if m_err:
            return False, m_err.group(1), m_err.group(2).strip() if m_err.group(2) else ""
        if "key[end] val[fail]" in text:
            return False, "-1", "Fail"
        return False, "?", "Unknown"

    def _finish_install(self, success):
        self.is_installing = False
        self.after(0, lambda: self.btn_install.configure(state="normal", text="INSTALL ON WATCH"))

if __name__ == "__main__":
    app = TizenInstallerApp()
    app.mainloop()
