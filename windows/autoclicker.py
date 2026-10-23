# -*- coding: utf-8 -*-
"""اتو کلیکر ویندوز - شروع کلیک در ساعت دقیق، با تنظیم فاصله و شرط توقف"""
import ctypes
import datetime
import json
import os
import random
import socket
import struct
import sys
import threading
import time
import tkinter as tk
from tkinter import ttk, messagebox

user32 = ctypes.windll.user32
try:
    ctypes.windll.shcore.SetProcessDpiAwareness(2)   # مختصات واقعی پیکسل
except Exception:
    try:
        user32.SetProcessDPIAware()
    except Exception:
        pass
try:
    ctypes.windll.winmm.timeBeginPeriod(1)           # دقت زمان‌سنج ۱ میلی‌ثانیه
except Exception:
    pass


class POINT(ctypes.Structure):
    _fields_ = [("x", ctypes.c_long), ("y", ctypes.c_long)]


def get_pos():
    p = POINT()
    user32.GetCursorPos(ctypes.byref(p))
    return p.x, p.y


BUTTONS = {
    "چپ": (0x0002, 0x0004),
    "راست": (0x0008, 0x0010),
    "وسط": (0x0020, 0x0040),
}


def do_click(x, y, button, move):
    if move:
        user32.SetCursorPos(int(x), int(y))
    down, up = BUTTONS[button]
    user32.mouse_event(down, 0, 0, 0, 0)
    user32.mouse_event(up, 0, 0, 0, 0)


NTP_HOSTS = ["time.google.com", "pool.ntp.org", "time.cloudflare.com", "time.windows.com"]


def sntp(host, timeout=2.0):
    """یک نمونه SNTP؛ خروجی: (اختلاف ساعت، تأخیر رفت‌وبرگشت) به ثانیه"""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.settimeout(timeout)
    try:
        addr = (socket.gethostbyname(host), 123)
        t0 = time.time()
        p0 = time.perf_counter()
        s.sendto(b"\x1b" + 47 * b"\0", addr)
        data, _ = s.recvfrom(48)
        t3 = t0 + (time.perf_counter() - p0)
        if len(data) < 48:
            return None

        def ts(off):
            sec, frac = struct.unpack("!II", data[off:off + 8])
            return sec - 2208988800 + frac / 2 ** 32

        t1, t2 = ts(32), ts(40)
        return ((t1 - t0) + (t2 - t3)) / 2, (t3 - t0) - (t2 - t1)
    except Exception:
        return None
    finally:
        s.close()


def ntp_offset():
    """بهترین نمونه: (اختلاف، تأخیر، میزبان) یا None. مثبت یعنی ساعت سیستم عقب است."""
    best = None
    ok = 0
    for h in NTP_HOSTS:
        got = False
        for _ in range(4):
            r = sntp(h)
            if r:
                got = True
                if best is None or r[1] < best[1]:
                    best = (r[0], r[1], h)
        if got:
            ok += 1
        if ok >= 2:
            break
    return best


def tcp_ping(host_input, count=6):
    """پینگ به‌صورت زمان اتصال TCP به پورت 443؛ خروجی: (کمترین، میانگین، بیشترین، نوسان، قطع، کل) به میلی‌ثانیه"""
    host = host_input.strip()
    for pre in ("https://", "http://"):
        if host.startswith(pre):
            host = host[len(pre):]
    host = host.split("/")[0].split(":")[0]
    if not host:
        return None
    try:
        ip = socket.gethostbyname(host)
    except Exception:
        return None
    samples, lost = [], 0
    for _ in range(count):
        try:
            t0 = time.perf_counter()
            c = socket.create_connection((ip, 443), timeout=2)
            samples.append((time.perf_counter() - t0) * 1000)
            c.close()
        except Exception:
            lost += 1
        time.sleep(0.15)
    if not samples:
        return (0, 0, 0, 0, lost, count)
    jit = (sum(abs(samples[i] - samples[i - 1]) for i in range(1, len(samples))) / (len(samples) - 1)
           if len(samples) > 1 else 0)
    return (min(samples), sum(samples) / len(samples), max(samples), jit, lost, count)


VK_F6 = 0x75
VK_F8 = 0x77
SETTINGS = os.path.join(os.environ.get("APPDATA", "."), "AutoClickerWin", "settings.json")


class App:
    def __init__(self, root):
        self.root = root
        root.title("اتو کلیکر")
        root.resizable(False, False)
        self.stop_evt = threading.Event()
        self.worker = None
        self._status = "آماده"
        self._f6 = False
        self._f8 = False

        now = datetime.datetime.now() + datetime.timedelta(minutes=1)
        self.now_var = tk.BooleanVar(value=False)
        self.hour = tk.StringVar(value=str(now.hour))
        self.minute = tk.StringVar(value=str(now.minute))
        self.second = tk.StringVar(value="0")
        self.msec = tk.StringVar(value="0")
        self.interval = tk.StringVar(value="300")
        self.unit = tk.StringVar(value="میلی‌ثانیه")
        self.stop_mode = tk.IntVar(value=0)
        self.stop_val = tk.StringVar(value="")
        self.anti = tk.BooleanVar(value=False)
        self.button = tk.StringVar(value="چپ")
        self.use_cursor = tk.BooleanVar(value=False)
        self.x = tk.StringVar(value="")
        self.y = tk.StringVar(value="")
        self.host = tk.StringVar(value="")
        self.early = tk.StringVar(value="0")
        self.ping_comp = tk.BooleanVar(value=False)
        self.offset = 0.0          # اختلاف ساعت سیستم با ساعت واقعی (ثانیه)
        self.offset_at = 0.0
        self.ping_ms = -1.0
        self._calib = ""
        self.load()
        self.build()
        self.poll()
        root.protocol("WM_DELETE_WINDOW", self.close)

    # ---------- رابط کاربری ----------
    def build(self):
        f = ttk.Frame(self.root, padding=14)
        f.grid()
        r = 0

        def row(label, *widgets):
            nonlocal r
            ttk.Label(f, text=label).grid(row=r, column=1, sticky="e", padx=6, pady=4)
            box = ttk.Frame(f)
            box.grid(row=r, column=0, sticky="w")
            for w in widgets:
                w.pack(in_=box, side="left", padx=2)
            r += 1

        ttk.Checkbutton(f, text="شروع فوری (بدون ساعت)", variable=self.now_var)\
            .grid(row=r, column=0, columnspan=2, sticky="e"); r += 1
        row("زمان شروع (ساعت : دقیقه : ثانیه : میلی‌ثانیه)",
            ttk.Spinbox(f, from_=0, to=23, width=4, textvariable=self.hour),
            ttk.Spinbox(f, from_=0, to=59, width=4, textvariable=self.minute),
            ttk.Spinbox(f, from_=0, to=59, width=4, textvariable=self.second),
            ttk.Spinbox(f, from_=0, to=999, width=5, textvariable=self.msec))
        row("فاصله بین کلیک‌ها",
            ttk.Entry(f, width=9, textvariable=self.interval),
            ttk.Combobox(f, width=11, state="readonly", textvariable=self.unit,
                         values=["میلی‌ثانیه", "ثانیه"]))
        row("دکمه ماوس",
            ttk.Combobox(f, width=8, state="readonly", textvariable=self.button,
                         values=list(BUTTONS)))

        ttk.Label(f, text="توقف بعد از").grid(row=r, column=1, sticky="e", padx=6, pady=(8, 0))
        sb = ttk.Frame(f); sb.grid(row=r, column=0, sticky="w", pady=(8, 0))
        ttk.Radiobutton(sb, text="بی‌پایان", variable=self.stop_mode, value=0).pack(side="left")
        ttk.Radiobutton(sb, text="مدت (ثانیه)", variable=self.stop_mode, value=1).pack(side="left")
        ttk.Radiobutton(sb, text="تعداد کلیک", variable=self.stop_mode, value=2).pack(side="left")
        ttk.Entry(sb, width=8, textvariable=self.stop_val).pack(side="left", padx=4)
        r += 1

        ttk.Checkbutton(f, text="ضد تشخیص (جابه‌جایی و تغییر زمان تصادفی کوچک)", variable=self.anti)\
            .grid(row=r, column=0, columnspan=2, sticky="e", pady=4); r += 1

        ttk.Separator(f).grid(row=r, column=0, columnspan=2, sticky="ew", pady=6); r += 1
        ttk.Checkbutton(f, text="کلیک در جای فعلی ماوس (موقعیت ثابت نباشد)", variable=self.use_cursor)\
            .grid(row=r, column=0, columnspan=2, sticky="e"); r += 1
        row("موقعیت کلیک (X , Y)",
            ttk.Entry(f, width=7, textvariable=self.x),
            ttk.Entry(f, width=7, textvariable=self.y),
            ttk.Button(f, text="ثبت بعد از ۳ ثانیه", command=self.capture_delayed))
        ttk.Label(f, text="میانبر: ماوس را روی هدف ببر و F6 بزن تا موقعیت ثبت شود",
                  foreground="#555").grid(row=r, column=0, columnspan=2, sticky="e"); r += 1

        ttk.Separator(f).grid(row=r, column=0, columnspan=2, sticky="ew", pady=6); r += 1
        row("آدرس سایت هدف (برای پینگ)", ttk.Entry(f, width=26, textvariable=self.host))
        row("کالیبراسیون",
            ttk.Button(f, text="اندازه‌گیری پینگ", command=self.do_ping),
            ttk.Button(f, text="همگام‌سازی ساعت", command=self.do_sync))
        row("زودتر کلیک کن (میلی‌ثانیه)", ttk.Entry(f, width=9, textvariable=self.early))
        ttk.Checkbutton(f, text="جبران پینگ: شروع زودتر به اندازه نصف پینگ", variable=self.ping_comp)\
            .grid(row=r, column=0, columnspan=2, sticky="e"); r += 1
        self.calib_var = tk.StringVar(value="")
        ttk.Label(f, textvariable=self.calib_var, foreground="#555", justify="right")\
            .grid(row=r, column=0, columnspan=2, sticky="e"); r += 1

        bf = ttk.Frame(f); bf.grid(row=r, column=0, columnspan=2, pady=10)
        ttk.Button(bf, text="شروع", width=14, command=self.start).pack(side="left", padx=6)
        ttk.Button(bf, text="توقف (F8)", width=14, command=self.stop).pack(side="left", padx=6)
        r += 1
        ttk.Label(f, textvariable=self._status_var()).grid(row=r, column=0, columnspan=2)

    def _status_var(self):
        self.status_var = tk.StringVar(value=self._status)
        return self.status_var

    # ---------- ذخیره و بازیابی ----------
    def load(self):
        try:
            with open(SETTINGS, "r", encoding="utf-8") as fh:
                d = json.load(fh)
            for k in ("interval", "unit", "stop_val", "button", "x", "y", "host", "early"):
                if k in d:
                    getattr(self, k).set(d[k])
            for k in ("anti", "use_cursor", "ping_comp"):
                if k in d:
                    getattr(self, k).set(d[k])
            self.offset = float(d.get("offset", 0.0))
            self.offset_at = float(d.get("offset_at", 0.0))
            self.ping_ms = float(d.get("ping_ms", -1.0))
            if "stop_mode" in d:
                self.stop_mode.set(d["stop_mode"])
        except Exception:
            pass

    def save(self):
        try:
            os.makedirs(os.path.dirname(SETTINGS), exist_ok=True)
            d = {k: getattr(self, k).get() for k in
                 ("interval", "unit", "stop_val", "button", "x", "y", "host", "early",
                  "anti", "use_cursor", "ping_comp", "stop_mode")}
            d.update(offset=self.offset, offset_at=self.offset_at, ping_ms=self.ping_ms)
            with open(SETTINGS, "w", encoding="utf-8") as fh:
                json.dump(d, fh, ensure_ascii=False)
        except Exception:
            pass

    # ---------- ثبت موقعیت و میانبرها ----------
    def capture(self):
        x, y = get_pos()
        self.x.set(str(x)); self.y.set(str(y))
        self._status = f"موقعیت ثبت شد: {x} , {y}"

    def capture_delayed(self, n=3):
        if n > 0:
            self._status = f"ماوس را روی هدف ببر... {n}"
            self.root.after(1000, lambda: self.capture_delayed(n - 1))
        else:
            self.capture()

    def poll(self):
        f6 = bool(user32.GetAsyncKeyState(VK_F6) & 0x8000)
        f8 = bool(user32.GetAsyncKeyState(VK_F8) & 0x8000)
        if f6 and not self._f6:
            self.capture()
        if f8 and not self._f8:
            self.stop()
        self._f6, self._f8 = f6, f8
        self.status_var.set(self._status)
        self.calib_var.set(self.calib_text())
        self.root.after(25, self.poll)

    # ---------- پینگ و همگام‌سازی ساعت ----------
    def calib_text(self):
        if self.offset_at == 0:
            a = "ساعت: هنوز همگام نشده"
        else:
            a = "ساعت: اختلاف %d میلی‌ثانیه (%d دقیقه پیش همگام شد)" % (
                round(self.offset * 1000), (time.time() - self.offset_at) / 60)
        b = "پینگ: هنوز اندازه‌گیری نشده" if self.ping_ms < 0 else "پینگ: %.0f میلی‌ثانیه" % self.ping_ms
        return a + "\n" + b

    def do_ping(self):
        host = self.host.get().strip()
        if not host:
            messagebox.showinfo("اتو کلیکر", "آدرس سایت هدف را بنویس.")
            return
        self._status = "در حال اندازه‌گیری پینگ..."

        def work():
            r = tcp_ping(host)
            if r is None:
                self._status = "آدرس نامعتبر است یا اینترنت قطع است"
            elif r[4] == r[5]:
                self._status = "اتصال به سایت برقرار نشد"
            else:
                mn, avg, mx, jit, lost, total = r
                self.ping_ms = avg
                q = ("ناپایدار" if lost > 0 or jit > 60 else
                     "خوب" if avg < 100 else "متوسط" if avg < 250 else "ضعیف")
                self._status = ("پینگ: کمترین %.0f / میانگین %.0f / بیشترین %.0f ms | نوسان %.0f | قطع %d از %d | کیفیت: %s"
                                % (mn, avg, mx, jit, lost, total, q))
                self.save()
        threading.Thread(target=work, daemon=True).start()

    def do_sync(self):
        self._status = "در حال همگام‌سازی ساعت..."

        def work():
            r = ntp_offset()
            if r is None:
                self._status = "همگام‌سازی ممکن نشد (اینترنت یا مسدود بودن پورت زمان)"
                return
            self.offset, self.offset_at = r[0], time.time()
            self.save()
            d = "عقب" if r[0] >= 0 else "جلو"
            self._status = "ساعت سیستم %d میلی‌ثانیه %s است (منبع: %s)" % (abs(round(r[0] * 1000)), d, r[2])
        threading.Thread(target=work, daemon=True).start()

    # ---------- شروع و توقف ----------
    def running(self):
        return self.worker is not None and self.worker.is_alive()

    def start(self):
        if self.running():
            messagebox.showinfo("اتو کلیکر", "هم‌اکنون در حال اجراست. اول توقف بزن.")
            return
        try:
            iv = float(self.interval.get().replace(",", "."))
            assert iv > 0
            interval = iv / 1000.0 if self.unit.get() == "میلی‌ثانیه" else iv
            mode = self.stop_mode.get()
            stop_val = 0
            if mode != 0:
                stop_val = float(self.stop_val.get())
                assert stop_val > 0
            early = float(self.early.get().replace(",", ".") or 0) / 1000.0
            assert early >= 0
            if self.ping_comp.get() and self.ping_ms > 0:
                early += self.ping_ms / 2000.0
            if self.now_var.get():
                target_true = 0.0
                ts = time.time()
            else:
                h, m, s = int(self.hour.get()), int(self.minute.get()), int(self.second.get())
                ms = int(self.msec.get() or 0)
                assert 0 <= h < 24 and 0 <= m < 60 and 0 <= s < 60 and 0 <= ms < 1000
                now_true = time.time() + self.offset      # زمان واقعی = ساعت سیستم + اختلاف
                t = datetime.datetime.fromtimestamp(now_true).replace(hour=h, minute=m, second=s, microsecond=ms * 1000)
                if t.timestamp() <= now_true:
                    t += datetime.timedelta(days=1)
                target_true = t.timestamp()
                ts = target_true - self.offset - early
            pos = None
            if not self.use_cursor.get():
                pos = (int(self.x.get()), int(self.y.get()))
        except Exception:
            messagebox.showerror("خطا", "مقادیر را کامل و درست وارد کن (موقعیت X و Y یا گزینه جای فعلی ماوس).")
            return
        if interval < 0.04:
            messagebox.showwarning("هشدار", "فاصله کمتر از ۴۰ میلی‌ثانیه ممکن است سیستم را کند کند.")
        self.save()
        self.stop_evt.clear()
        cfg = dict(ts=ts, interval=interval, mode=mode, stop_val=stop_val, pos=pos,
                   button=self.button.get(), anti=self.anti.get(),
                   target_true=target_true, early=early)
        self.worker = threading.Thread(target=self.run, args=(cfg,), daemon=True)
        self.worker.start()

    def stop(self):
        if self.running():
            self.stop_evt.set()

    def close(self):
        self.stop_evt.set()
        self.root.destroy()

    # ---------- هسته کلیک ----------
    def run(self, c):
        stop = self.stop_evt
        # اگر هنوز وقت هست، ساعت را با اینترنت همگام کن و زمان شروع را اصلاح کن
        if c["target_true"] and c["ts"] - time.time() > 4:
            self._status = "همگام‌سازی ساعت قبل از شروع..."
            r = ntp_offset()
            if r:
                self.offset, self.offset_at = r[0], time.time()
                c["ts"] = c["target_true"] - self.offset - c["early"]
                self.save()
        # انتظار تا زمان شروع (۵۰ میلی‌ثانیه آخر بدون خواب برای دقت)
        last = 0
        while not stop.is_set():
            rem = c["ts"] - time.time()
            if rem <= 0:
                break
            if rem > 0.05:
                if time.time() - last > 0.2:
                    last = time.time()
                    left = int(rem)
                    self._status = "منتظر شروع... %02d:%02d:%02d مانده" % (
                        left // 3600, (left % 3600) // 60, left % 60)
                time.sleep(min(rem - 0.03, 0.2))
        if stop.is_set():
            self._status = "متوقف شد"
            return

        count = 0
        t0 = time.perf_counter()
        next_t = t0
        end_t = t0 + c["stop_val"] if c["mode"] == 1 else None
        while not stop.is_set():
            if c["mode"] == 2 and count >= c["stop_val"]:
                break
            if end_t is not None and time.perf_counter() >= end_t:
                break
            if c["pos"] is not None:
                x, y = c["pos"]
                move = True
                if c["anti"]:
                    x += random.randint(-3, 3)
                    y += random.randint(-3, 3)
            else:
                x = y = 0
                move = False
            do_click(x, y, c["button"], move)
            count += 1
            self._status = f"در حال کلیک... تعداد: {count}  (توقف: F8)"
            step = c["interval"]
            if c["anti"]:
                step = max(0.01, step * (0.9 + random.random() * 0.2))
            next_t += step
            while not stop.is_set():
                rem = next_t - time.perf_counter()
                if rem <= 0:
                    break
                if rem > 0.004:
                    time.sleep(rem - 0.003)
        self._status = ("متوقف شد" if stop.is_set() else "پایان") + f": {count} کلیک انجام شد"


def resource_path(name):
    base = getattr(sys, "_MEIPASS", os.path.dirname(os.path.abspath(__file__)))
    return os.path.join(base, name)


if __name__ == "__main__":
    root = tk.Tk()
    try:
        root.iconbitmap(resource_path("icon.ico"))
    except Exception:
        pass
    App(root)
    root.mainloop()
