#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
KeyBridge PC 端 —— 接收手机发来的按键事件，用 SendInput 注入为系统键盘输入。

用法（Windows）:
    python keybridge_server.py                # USB 模式：自动 adb reverse
    python keybridge_server.py --lan          # 允许手机经 WiFi 连接（手机里填电脑 IP）
    python keybridge_server.py --port 27182   # 指定端口（与 App 设置一致）
    python keybridge_server.py --adb "D:\\Android\\Sdk\\platform-tools\\adb.exe"
    type events.txt | python keybridge_server.py --test --dry-run   # 本地调试（不真注入）

事件协议（每行一个 JSON，UTF-8）:
    {"t":"k","c":"A","d":1}   按键 code=A 按下(d=1) / 抬起(d=0)
    {"t":"x","s":"hello"}     输入文本（Unicode，逐字符注入）
    {"t":"p"}                 心跳

仅依赖 Python 标准库 + adb（USB 模式）。普通键用扫描码注入，
DirectInput / 老游戏也能识别；多媒体键走 VK；文本走 KEYEVENTF_UNICODE。
"""

import argparse
import ctypes
import json
import os
import shutil
import socket
import subprocess
import sys
import threading

from ctypes import wintypes

if sys.platform != "win32":
    sys.exit("此脚本依赖 Windows SendInput，请在 Windows 上运行"
             "（其他平台可用 pynput 改写 inject 部分实现同样协议）。")

APP_PORT_DEFAULT = 27182

# ---------------- Windows SendInput ----------------

INPUT_KEYBOARD = 1
KEYEVENTF_EXTENDEDKEY = 0x0001
KEYEVENTF_KEYUP = 0x0002
KEYEVENTF_UNICODE = 0x0004
KEYEVENTF_SCANCODE = 0x0008

user32 = ctypes.WinDLL("user32", use_last_error=True)
SendInput = user32.SendInput
SendInput.argtypes = (ctypes.c_uint, ctypes.c_void_p, ctypes.c_int)
SendInput.restype = ctypes.c_uint


class KEYBDINPUT(ctypes.Structure):
    _fields_ = [("wVk", wintypes.WORD), ("wScan", wintypes.WORD),
                ("dwFlags", wintypes.DWORD), ("time", wintypes.DWORD),
                ("dwExtraInfo", ctypes.c_size_t)]


class MOUSEINPUT(ctypes.Structure):
    _fields_ = [("dx", wintypes.LONG), ("dy", wintypes.LONG),
                ("mouseData", wintypes.DWORD), ("dwFlags", wintypes.DWORD),
                ("time", wintypes.DWORD), ("dwExtraInfo", ctypes.c_size_t)]


class HARDWAREINPUT(ctypes.Structure):
    _fields_ = [("uMsg", wintypes.DWORD), ("wParamL", wintypes.WORD),
                ("wParamH", wintypes.WORD)]


class _INPUTU(ctypes.Union):
    _fields_ = [("ki", KEYBDINPUT), ("mi", MOUSEINPUT), ("hi", HARDWAREINPUT)]


class INPUT(ctypes.Structure):
    _fields_ = [("type", wintypes.DWORD), ("u", _INPUTU)]


# ---------------- 键名映射表 ----------------
# 键名 -> Set 1 扫描码（make code）
SCAN = {
    "ESC": 0x01, "1": 0x02, "2": 0x03, "3": 0x04, "4": 0x05, "5": 0x06,
    "6": 0x07, "7": 0x08, "8": 0x09, "9": 0x0A, "0": 0x0B,
    "MINUS": 0x0C, "EQUALS": 0x0D, "BKSP": 0x0E, "TAB": 0x0F,
    "Q": 0x10, "W": 0x11, "E": 0x12, "R": 0x13, "T": 0x14, "Y": 0x15,
    "U": 0x16, "I": 0x17, "O": 0x18, "P": 0x19,
    "LBRACKET": 0x1A, "RBRACKET": 0x1B, "ENTER": 0x1C, "CTRL_L": 0x1D,
    "A": 0x1E, "S": 0x1F, "D": 0x20, "F": 0x21, "G": 0x22, "H": 0x23,
    "J": 0x24, "K": 0x25, "L": 0x26, "SEMICOLON": 0x27, "QUOTE": 0x28,
    "GRAVE": 0x29, "SHIFT_L": 0x2A, "BACKSLASH": 0x2B,
    "Z": 0x2C, "X": 0x2D, "C": 0x2E, "V": 0x2F, "B": 0x30,
    "N": 0x31, "M": 0x32, "COMMA": 0x33, "PERIOD": 0x34, "SLASH": 0x35,
    "SHIFT_R": 0x36, "PRTSC": 0x37, "ALT_L": 0x38, "SPACE": 0x39,
    "CAPS": 0x3A,
    "F1": 0x3B, "F2": 0x3C, "F3": 0x3D, "F4": 0x3E, "F5": 0x3F, "F6": 0x40,
    "F7": 0x41, "F8": 0x42, "F9": 0x43, "F10": 0x44, "F11": 0x57, "F12": 0x58,
    "HOME": 0x47, "UP": 0x48, "PGUP": 0x49, "LEFT": 0x4B, "RIGHT": 0x4D,
    "END": 0x4F, "DOWN": 0x50, "PGDN": 0x51, "INS": 0x52, "DEL": 0x53,
    "WIN": 0x5B, "CTRL_R": 0x1D, "ALT_R": 0x38, "MENU": 0x5D,
}

# 注入时需带 KEYEVENTF_EXTENDEDKEY 的键（方向/编辑区/右键/Win）
EXTENDED = {"HOME", "UP", "PGUP", "LEFT", "RIGHT", "END", "DOWN", "PGDN",
            "INS", "DEL", "WIN", "CTRL_R", "ALT_R", "MENU", "PRTSC"}

# 多媒体键无扫描码，用 VK 注入
VK = {
    "VOL_PLUS": 0xAF, "VOL_MINUS": 0xAE, "MUTE": 0xAD,
    "MEDIA_PLAY": 0xB3, "MEDIA_NEXT": 0xB0, "MEDIA_PREV": 0xB1,
    "MEDIA_STOP": 0xB2,
}

DRY_RUN = False
VERBOSE = False


def _mk_kbd(wvk=0, wscan=0, flags=0):
    i = INPUT()
    i.type = INPUT_KEYBOARD
    i.u.ki.wVk = wvk
    i.u.ki.wScan = wscan
    i.u.ki.dwFlags = flags
    return i


def inject_event(inp_list):
    if DRY_RUN:
        for i in inp_list:
            ki = i.u.ki
            print("  [DRY] wVk=0x%04X wScan=0x%04X flags=0x%08X"
                  % (ki.wVk, ki.wScan, ki.dwFlags))
        return
    arr = (INPUT * len(inp_list))(*inp_list)
    n = SendInput(len(arr), arr, ctypes.sizeof(INPUT))
    if n != len(inp_list):
        print("[!] SendInput 失败 err=%d" % ctypes.get_last_error())


def key_action(code, down):
    """注入单个按键的按下/抬起。"""
    flags_up = 0 if down else KEYEVENTF_KEYUP
    if code in VK:
        if DRY_RUN:
            print("  [DRY] VK 0x%02X %s" % (VK[code], "down" if down else "up"))
            return
        inject_event([_mk_kbd(wvk=VK[code], flags=flags_up)])
        return
    scan = SCAN.get(code)
    if scan is None:
        print("[?] 未知键名: %r" % code)
        return
    flags = KEYEVENTF_SCANCODE | flags_up
    if code in EXTENDED:
        flags |= KEYEVENTF_EXTENDEDKEY
    if DRY_RUN:
        print("  [DRY] scan 0x%02X %s %s"
              % (scan, "down" if down else "up",
                 "(ext)" if code in EXTENDED else ""))
        return
    inject_event([_mk_kbd(wscan=scan, flags=flags)])


def text_action(s):
    """按 UTF-16 码元逐个注入 Unicode 字符（支持代理对）。"""
    if not s:
        return
    data = s.encode("utf-16-le")
    units = [data[i] | (data[i + 1] << 8) for i in range(0, len(data), 2)]
    events = []
    for u in units:
        if u == 0:
            continue
        events.append(_mk_kbd(wscan=u, flags=KEYEVENTF_UNICODE))
        events.append(_mk_kbd(wscan=u, flags=KEYEVENTF_UNICODE | KEYEVENTF_KEYUP))
    if DRY_RUN:
        print("  [DRY] text %r -> %d events" % (s, len(events)))
        return
    # 分批注入，避免一次塞太多事件
    for i in range(0, len(events), 16):
        inject_event(events[i:i + 16])


# ---------------- 事件处理 ----------------

class ClientSession(object):
    """一个手机连接。断开时把还没抬起的键全部释放，防止按键卡死。"""

    def __init__(self, name):
        self.name = name
        self.down = set()

    def handle(self, obj):
        t = obj.get("t")
        if t == "k":
            code = str(obj.get("c", ""))
            down = int(obj.get("d", 1)) == 1
            if down:
                self.down.add(code)
            else:
                self.down.discard(code)
            if VERBOSE:
                print("[%s] %s %s" % (self.name, code, "down" if down else "up"))
            key_action(code, down)
        elif t == "x":
            s = str(obj.get("s", ""))
            if VERBOSE:
                print("[%s] text %r" % (self.name, s))
            text_action(s)
        elif t == "p":
            if VERBOSE:
                print("[%s] ping" % self.name)
        else:
            print("[?] 未知事件: %r" % obj)

    def release_all(self):
        for code in list(self.down):
            key_action(code, False)
        self.down.clear()


def serve_connection(conn, addr):
    name = "%s:%d" % (addr[0], addr[1])
    print("[+] 手机已连接: %s" % name)
    session = ClientSession(name)
    try:
        conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        f = conn.makefile("rb")
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                obj = json.loads(line.decode("utf-8", "replace"))
            except ValueError:
                print("[!] 非 JSON 行: %r" % line[:120])
                continue
            session.handle(obj)
    except (ConnectionError, OSError):
        pass
    finally:
        try:
            conn.close()
        except OSError:
            pass
        session.release_all()
        print("[-] 连接断开（已释放所有按住的键）: %s" % name)


# ---------------- adb 辅助 ----------------

def find_adb(explicit=None):
    if explicit:
        return explicit
    p = shutil.which("adb")
    if p:
        return p
    candidates = [
        os.path.expandvars(r"%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"),
        r"D:\Android\Sdk\platform-tools\adb.exe",
        r"C:\Android\Sdk\platform-tools\adb.exe",
    ]
    for c in candidates:
        if os.path.isfile(c):
            return c
    return None


def adb_watchdog(adb, port, stop):
    ok_printed = False
    while not stop.is_set():
        try:
            r = subprocess.run(
                [adb, "reverse", "tcp:%d" % port, "tcp:%d" % port],
                stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                universal_newlines=True, timeout=10)
            if r.returncode == 0:
                if not ok_printed:
                    print("[+] adb reverse 已建立: 手机:127.0.0.1:%d -> 电脑:%d"
                          % (port, port))
                    ok_printed = True
            else:
                ok_printed = False
                msg = ((r.stderr or "") + (r.stdout or "")).strip().splitlines()
                print("[.] 等待手机（插 USB、开 USB 调试、允许授权）: %s"
                      % (msg[-1] if msg else "未检测到设备"))
        except Exception as e:
            ok_printed = False
            print("[!] adb 执行失败: %s" % e)
        stop.wait(5)


# ---------------- main ----------------

def main():
    global DRY_RUN, VERBOSE
    ap = argparse.ArgumentParser(description="KeyBridge PC 端")
    ap.add_argument("--port", type=int, default=APP_PORT_DEFAULT)
    ap.add_argument("--lan", action="store_true",
                    help="监听 0.0.0.0，允许 WiFi 连接")
    ap.add_argument("--adb", help="adb.exe 路径（默认从 PATH / 常见目录找）")
    ap.add_argument("--no-adb", action="store_true",
                    help="跳过 adb reverse（纯 WiFi 模式）")
    ap.add_argument("--test", action="store_true",
                    help="从 stdin 读事件（调试用，不监听端口）")
    ap.add_argument("--dry-run", action="store_true",
                    help="只打印不真正注入")
    ap.add_argument("-v", "--verbose", action="store_true")
    args = ap.parse_args()

    DRY_RUN = args.dry_run
    VERBOSE = args.verbose

    if args.test:
        s = ClientSession("test")
        for line in sys.stdin:
            line = line.strip()
            if not line:
                continue
            try:
                s.handle(json.loads(line))
            except ValueError as e:
                print("[!] 坏行: %r (%s)" % (line[:120], e))
        return

    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    bind = "0.0.0.0" if args.lan else "127.0.0.1"
    srv.bind((bind, args.port))
    srv.listen(4)
    print("=" * 60)
    print("KeyBridge PC 端已启动   监听 %s:%d" % (bind, args.port))
    print("手机 App 设置里的地址: %s:%d"
          % ("电脑局域网IP" if args.lan else "127.0.0.1", args.port))
    print("Ctrl+C 退出")
    print("=" * 60)

    stop = threading.Event()
    if not args.no_adb:
        adb = find_adb(args.adb)
        if adb is None:
            print("[!] 找不到 adb —— USB 模式不可用。")
            print("    请安装 platform-tools 或用 --adb 指定路径；"
                  "或改用 --lan WiFi 模式。")
        else:
            print("[*] adb: %s" % adb)
            threading.Thread(target=adb_watchdog, args=(adb, args.port, stop),
                             daemon=True).start()

    try:
        while True:
            conn, addr = srv.accept()
            threading.Thread(target=serve_connection, args=(conn, addr),
                             daemon=True).start()
    except KeyboardInterrupt:
        print("\n[*] 退出")
    finally:
        stop.set()
        srv.close()


if __name__ == "__main__":
    main()
