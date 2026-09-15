""".fit 文件生成器 — adapted from IAMAI-Dev/KeepTrack (GPL-3.0)."""
import ctypes
import math
import os
from pathlib import Path
import queue
import threading
import tkinter as tk
from tkinter import ttk, filedialog, messagebox
from dataclasses import dataclass, replace
from datetime import datetime, timedelta
import webbrowser

from coordTransform import wgs84_to_gcj02, gcj02_to_wgs84, bd09_to_gcj02
from routes import Route, load_gpx
from map_picker import MapPicker
from geographiclib.geodesic import Geodesic
from fit_tool.fit_file_builder import FitFileBuilder
from fit_tool.profile.messages.activity_message import ActivityMessage
from fit_tool.profile.messages.event_message import EventMessage
from fit_tool.profile.messages.file_id_message import FileIdMessage
from fit_tool.profile.messages.lap_message import LapMessage
from fit_tool.profile.messages.record_message import RecordMessage
from fit_tool.profile.messages.session_message import SessionMessage
from fit_tool.profile.profile_type import Event, EventType, FileType, Manufacturer, Sport, SubSport

APP_NAME = ".fit文件生成器"
VERSION = "2.1.0"
SOURCES = ("高德 / 腾讯（GCJ-02）", "GPS / Google 地球（WGS84）", "百度（BD-09）")
BG, CARD, INK, MUTED, ACCENT = "#edf2f7", "#ffffff", "#18283f", "#61738a", "#2563eb"


def to_wgs84(lat, lon, source):
    """Iteratively invert the GCJ transform; input accuracy remains the limiting factor."""
    if source == SOURCES[1]:
        return lat, lon
    if source == SOURCES[2]:
        lon, lat = bd09_to_gcj02(lon, lat)
    guess_lon, guess_lat = gcj02_to_wgs84(lon, lat)
    for _ in range(12):
        mapped_lon, mapped_lat = wgs84_to_gcj02(guess_lon, guess_lat)
        dl, da = mapped_lon - lon, mapped_lat - lat
        guess_lon -= dl
        guess_lat -= da
        if max(abs(dl), abs(da)) < 1e-10:
            break
    return guess_lat, guess_lon


@dataclass(frozen=True)
class Run:
    distance: float
    duration: int
    cadence: int
    lat: float
    lon: float
    bearing: float
    straight: float
    radius: float
    start: datetime
    route: Route | None = None

    @property
    def perimeter(self):
        return 2 * self.straight + 2 * math.pi * self.radius


def track_xy(distance, straight, radius, bearing):
    """Distance-parametrized stadium, starting at the southwest tangent, going north."""
    arc = math.pi * radius
    s = distance % (2 * straight + 2 * arc)
    if s < straight:
        x, y = -radius, -straight / 2 + s
    elif s < straight + arc:
        a = math.pi - (s - straight) / radius
        x, y = radius * math.cos(a), straight / 2 + radius * math.sin(a)
    elif s < 2 * straight + arc:
        x, y = radius, straight / 2 - (s - straight - arc)
    else:
        a = (s - 2 * straight - arc) / radius
        x, y = radius * math.cos(a), -straight / 2 - radius * math.sin(a)
    a = math.radians(bearing)
    return x * math.cos(a) + y * math.sin(a), -x * math.sin(a) + y * math.cos(a)


def track_position(run, distance):
    if run.route is not None:
        return run.route.position(distance)
    east, north = track_xy(distance, run.straight, run.radius, run.bearing)
    result = Geodesic.WGS84.Direct(run.lat, run.lon, math.degrees(math.atan2(east, north)), math.hypot(east, north))
    return result["lat2"], result["lon2"]


def generate_fit(run, destination, progress=lambda _: None):
    builder = FitFileBuilder(auto_define=True, min_string_size=50)
    start_ts = int(run.start.timestamp() * 1000)
    end_ts = start_ts + run.duration * 1000
    speed = run.distance / run.duration
    # FIT uses strides/min (two steps), including fractional cadence for odd spm.
    rpm = run.cadence / 2
    whole, fraction = math.floor(rpm), rpm % 1
    # Keep the original secondary running metrics, consistently summarized.
    heart_rate = 165 if speed > 10/3 else 150 if speed > 25/9 else 135 if speed > 50/21 else 125
    power = round(speed * 70 * 1.05)
    stance = max(80, min(500, round(300 - (run.cadence - 150) * 2.8)))
    ident = FileIdMessage()
    ident.type, ident.manufacturer, ident.product = FileType.ACTIVITY, Manufacturer.DEVELOPMENT.value, 1
    ident.time_created = start_ts
    builder.add(ident)
    for event_type, timestamp in [(EventType.START, start_ts)]:
        event = EventMessage()
        event.event, event.event_type, event.timestamp = Event.TIMER, event_type, timestamp
        builder.add(event)
    for second in range(run.duration + 1):
        distance = run.distance * second / run.duration
        lat, lon = track_position(run, distance)
        record = RecordMessage()
        record.timestamp = start_ts + second * 1000
        record.position_lat, record.position_long = lat, lon
        record.distance, record.speed = distance, speed
        record.cadence, record.fractional_cadence = whole, fraction
        record.step_length = speed * 60 / run.cadence * 1000
        record.heart_rate, record.power, record.stance_time = heart_rate, power, stance
        record.altitude = 20.0
        builder.add(record)
        if second % 100 == 0:
            progress(second / run.duration)
    stop = EventMessage()
    stop.event, stop.event_type, stop.timestamp = Event.TIMER, EventType.STOP_ALL, end_ts
    builder.add(stop)
    start_lat, start_lon = track_position(run, 0)
    end_lat, end_lon = track_position(run, run.distance)
    for summary in (LapMessage(), SessionMessage()):
        summary.sport, summary.sub_sport = Sport.RUNNING, SubSport.GENERIC
        summary.timestamp, summary.start_time = end_ts, start_ts
        summary.total_elapsed_time = summary.total_timer_time = run.duration
        summary.total_distance = run.distance
        summary.avg_speed = summary.max_speed = speed
        summary.avg_cadence = summary.max_cadence = whole
        summary.avg_fractional_cadence = summary.max_fractional_cadence = fraction
        summary.total_strides = round(rpm * run.duration / 60)
        summary.avg_step_length = speed * 60 / run.cadence * 1000
        summary.avg_heart_rate = summary.max_heart_rate = heart_rate
        summary.avg_power, summary.avg_stance_time = power, stance
        summary.total_calories = round(run.distance / 1000 * 70 * 1.036)
        summary.start_position_lat, summary.start_position_long = start_lat, start_lon
        if isinstance(summary, LapMessage):
            summary.end_position_lat, summary.end_position_long = end_lat, end_lon
        else:
            summary.num_laps = 1
        builder.add(summary)
    activity = ActivityMessage()
    activity.timestamp, activity.total_timer_time, activity.num_sessions = end_ts, run.duration, 1
    builder.add(activity)
    # Exclusive reservation avoids silently overwriting a pre-existing export.
    payload = builder.build().to_bytes()
    with open(destination, "xb") as output:
        output.write(payload)
    progress(1.0)


class FITGeneratorGUI:
    def __init__(self, root):
        self.root = root
        root.title(APP_NAME)
        root.configure(bg=BG)
        width = min(1120, root.winfo_screenwidth() - 70)
        height = min(900, root.winfo_screenheight() - 100)
        root.geometry(f"{width}x{height}")
        root.minsize(min(860, width), min(650, height))
        root.protocol("WM_DELETE_WINDOW", self.close)
        self.events = queue.Queue()
        self.busy = False
        self.route = None
        self.gpx_routes = []
        self.map_picker = None
        self.fields = {}
        now = datetime.now()
        defaults = dict(distance="5.00", duration="30", cadence="170", count="1", interval="24",
                        date=now.strftime("%Y-%m-%d"), time=now.strftime("%H:%M"),
                        lat="", lon="", source=SOURCES[0], bearing="0",
                        straight="84.39", radius="36.8",
                        output=str(Path(os.path.expanduser("~")) / "Desktop" / "FIT文件"))
        self.v = {key: tk.StringVar(value=value) for key, value in defaults.items()}
        self._styles()
        self._build()
        for key in ("distance", "duration", "cadence", "straight", "radius", "bearing", "lat", "lon", "source"):
            self.v[key].trace_add("write", self._schedule_preview)
        self.preview_job = None
        self.refresh_preview()
        self.root.after(100, self._poll)

    def _styles(self):
        style = ttk.Style()
        style.theme_use("clam")
        style.configure(".", font=("Microsoft YaHei UI", 10), foreground=INK, background=CARD)
        style.configure("TEntry", padding=6, fieldbackground=CARD)
        style.configure("TCombobox", padding=5, fieldbackground=CARD)
        style.configure("TButton", padding=(12, 7))
        style.configure("Accent.TButton", background=ACCENT, foreground="white", font=("Microsoft YaHei UI", 11, "bold"))
        style.map("Accent.TButton", background=[("active", "#1d4ed8"), ("disabled", "#94a3b8")])
        style.configure("TProgressbar", background=ACCENT, troughcolor=BG)

    def close(self):
        if self.busy:
            messagebox.showinfo("正在导出", "请等待当前导出完成后再关闭。", parent=self.root)
            return
        if self.map_picker:
            self.map_picker.close()
        self.root.destroy()

    def _build(self):
        header = tk.Frame(self.root, bg=INK, padx=28, pady=17)
        header.pack(fill="x")
        tk.Label(header, text=APP_NAME, bg=INK, fg="white", font=("Microsoft YaHei UI", 23, "bold")).pack(anchor="w")
        tk.Label(header, text="————Natsume", bg=INK, fg="#bacaf0", font=("Microsoft YaHei UI", 11)).pack(anchor="w", pady=(3, 0))
        footer = tk.Frame(self.root, bg=BG, padx=22, pady=12)
        footer.pack(side="bottom", fill="x")
        self.status = tk.StringVar(value="地图选跑道，或导入 GPX 原路线")
        self.generate_btn = ttk.Button(footer, text="生成 .fit 文件", style="Accent.TButton", command=self.start_generation)
        self.generate_btn.pack(side="right")
        ttk.Button(footer, text="打开输出文件夹", command=self.open_output).pack(side="right", padx=10)
        tk.Label(footer, textvariable=self.status, bg=BG, fg=MUTED, anchor="w").pack(side="left", fill="x", expand=True)
        self.progress = ttk.Progressbar(self.root, mode="determinate")
        self.progress.pack(side="bottom", fill="x", padx=22)
        body = tk.Frame(self.root, bg=BG)
        body.pack(fill="both", expand=True, padx=22, pady=18)
        right_panel = tk.Frame(body, bg=CARD, width=375)
        right_panel.pack(side="right", fill="both", padx=(16, 0))
        right_panel.pack_propagate(False)
        right_canvas = tk.Canvas(right_panel,bg=CARD,highlightthickness=0)
        right_scroll = ttk.Scrollbar(right_panel,orient='vertical',command=right_canvas.yview)
        right_scroll.pack(side='right',fill='y')
        right_canvas.pack(side='left',fill='both',expand=True)
        right_canvas.configure(yscrollcommand=right_scroll.set)
        right = tk.Frame(right_canvas,bg=CARD,padx=18,pady=16)
        right_window = right_canvas.create_window((0,0),window=right,anchor='nw')
        right.bind('<Configure>',lambda e:right_canvas.configure(scrollregion=right_canvas.bbox('all')))
        right_canvas.bind('<Configure>',lambda e:right_canvas.itemconfigure(right_window,width=e.width))
        self.right_canvas = right_canvas
        tk.Label(right, text="跑道预览", bg=CARD, fg=INK, font=("Microsoft YaHei UI", 14, "bold")).pack(anchor="w")
        tk.Label(right, text="北向朝上 · 蓝点为起点 · 按实际尺寸绘制", bg=CARD, fg=MUTED, font=("Microsoft YaHei UI", 9)).pack(anchor="w", pady=(4, 10))
        self.preview = tk.Canvas(right, bg="#f4f7fc", highlightthickness=0, height=245)
        self.preview.pack(fill="x")
        self.preview.bind("<Configure>", lambda e: self._schedule_preview())
        self.metrics = tk.StringVar()
        tk.Label(right, textvariable=self.metrics, bg=CARD, fg=INK, justify="left", font=("Microsoft YaHei UI", 11), anchor="w").pack(fill="x", pady=12)
        self.location_note = tk.StringVar()
        tk.Label(right, textvariable=self.location_note, bg=CARD, fg=MUTED, justify="left", wraplength=315, anchor="w").pack(fill="x")
        ttk.Button(right, text="打开地图 · 选点 / 查看路线 ↗", command=self.open_map).pack(fill="x", pady=(8, 4))
        actions = tk.Frame(right, bg=CARD)
        actions.pack(fill="x", pady=4)
        ttk.Button(actions, text="导入 GPX…", command=self.import_gpx).pack(side="left", expand=True, fill="x", padx=(0, 4))
        ttk.Button(actions, text="切回标准跑道", command=self.clear_gpx).pack(side="left", expand=True, fill="x")
        self.segment_choice = ttk.Combobox(right, state="disabled", values=())
        self.segment_choice.pack(fill="x", pady=4)
        self.segment_choice.bind('<<ComboboxSelected>>', self.select_segment)
        tk.Label(right, text="地图无需 Key；导入 GPX 可离线使用。", bg=CARD, fg=MUTED, justify="left", font=("Microsoft YaHei UI", 9)).pack(anchor="w")
        self.log_text = tk.Text(right, height=4, bg="#f4f7fc", fg=MUTED, relief="flat", font=("Microsoft YaHei UI", 9), wrap="word", state="disabled")
        self.log_text.pack(fill="both", expand=True, pady=(12, 0))
        left = tk.Frame(body, bg=CARD)
        left.pack(side="left", fill="both", expand=True)
        self.canvas = tk.Canvas(left, bg=CARD, highlightthickness=0)
        scroll = ttk.Scrollbar(left, orient="vertical", command=self.canvas.yview)
        scroll.pack(side="right", fill="y")
        self.canvas.pack(side="left", fill="both", expand=True)
        self.canvas.configure(yscrollcommand=scroll.set)
        form = tk.Frame(self.canvas, bg=CARD, padx=20, pady=14)
        window = self.canvas.create_window((0, 0), window=form, anchor="nw")
        form.bind("<Configure>", lambda e: self.canvas.configure(scrollregion=self.canvas.bbox("all")))
        self.canvas.bind("<Configure>", lambda e: self.canvas.itemconfigure(window, width=e.width))
        def scroll_panel(event):
            widget = event.widget
            in_right = False
            while widget:
                if widget is right_panel:
                    in_right = True
                    break
                widget = getattr(widget,'master',None)
            (right_canvas if in_right else self.canvas).yview_scroll(-int(event.delta/120),'units')
        self.root.bind_all("<MouseWheel>", scroll_panel)
        form.columnconfigure(1, weight=1)
        row = 0
        for section, fields in [
            ("01  运动数据", [("距离（km）", "distance"), ("时长（分钟）", "duration"), ("平均步频（步/分钟）", "cadence")]),
            ("02  跑道与坐标", [("坐标来源", "source"), ("中心纬度", "lat"), ("中心经度", "lon"), ("方位角（正北顺时针°）", "bearing"), ("单段直道长度（米）", "straight"), ("弯道半径（米）", "radius")]),
            ("03  时间与导出", [("开始日期（年-月-日）", "date"), ("开始时间（时:分）", "time"), ("生成份数", "count"), ("每次间隔（小时）", "interval"), ("保存目录", "output")])]:
            tk.Label(form, text=section, bg=CARD, fg=ACCENT, font=("Microsoft YaHei UI", 12, "bold")).grid(row=row, column=0, columnspan=2, sticky="w", pady=(10, 10))
            row += 1
            for label, key in fields:
                ttk.Label(form, text=label).grid(row=row, column=0, sticky="w", padx=(0, 12), pady=6)
                if key == "source":
                    widget = ttk.Combobox(form, textvariable=self.v[key], values=SOURCES, state="readonly", width=20)
                else:
                    widget = ttk.Entry(form, textvariable=self.v[key], width=18)
                widget.grid(row=row, column=1, sticky="ew", pady=6)
                self.fields[key] = widget
                row += 1
            if section.startswith("02"):
                ttk.Label(form, text="填写操场中心，不是入口；尺寸按实际跑道填写。", foreground=MUTED, wraplength=380).grid(row=row, column=0, columnspan=2, sticky="w", pady=8)
                row += 1
        ttk.Button(form, text="选择保存文件夹…", command=self.choose_output).grid(row=row, column=1, sticky="e", pady=8)

    def _schedule_preview(self, *_):
        if self.preview_job:
            self.root.after_cancel(self.preview_job)
        self.preview_job = self.root.after(180, self.refresh_preview)

    def _number(self, key, low, high):
        label = {"lat": "纬度", "lon": "经度", "distance": "距离", "duration": "时长",
                 "cadence": "平均步频", "straight": "直道长度", "radius": "弯道半径",
                 "bearing": "方位角", "interval": "时间间隔"}.get(key, key)
        try:
            value = float(self.v[key].get())
        except ValueError:
            raise ValueError(f"{label}：请输入有效数字") from None
        if not math.isfinite(value) or not low <= value <= high:
            raise ValueError(f"{label}：允许范围 {low:g}～{high:g}")
        return value

    def location(self):
        lat, lon = self._number("lat", -85, 85), self._number("lon", -180, 180)
        return to_wgs84(lat, lon, self.v["source"].get())

    def refresh_preview(self):
        self.preview_job = None
        self.preview.delete("all")
        if self.route is not None:
            return self.refresh_route_preview()
        try:
            straight, radius = self._number("straight", 1, 1000), self._number("radius", 5, 300)
            bearing = self._number("bearing", 0, 360)
            length = 2 * straight + 2 * math.pi * radius
            points = [track_xy(length * i / 240, straight, radius, bearing) for i in range(241)]
            w, h = max(280, self.preview.winfo_width()), 245
            bound = max(max(abs(x), abs(y)) for x, y in points)
            scale = min(w - 60, h - 65) / (2 * bound)
            mapped = [(w / 2 + x * scale, h / 2 - y * scale + 8) for x, y in points]
            self.preview.create_line(w / 2, 30, w / 2, h - 12, fill="#dbe3ef", dash=(3, 5))
            self.preview.create_line(20, h / 2 + 8, w - 20, h / 2 + 8, fill="#dbe3ef", dash=(3, 5))
            self.preview.create_line(*[c for p in mapped for c in p], fill="#b7cdfc", width=12)
            self.preview.create_line(*[c for p in mapped for c in p], fill=ACCENT, width=2)
            x, y = mapped[0]
            self.preview.create_oval(x-5, y-5, x+5, y+5, fill=ACCENT, outline="white", width=2)
            self.preview.create_line(*mapped[3], *mapped[8], fill=INK, width=2, arrow="last")
            self.preview.create_text(w / 2, 15, text="N ↑", fill=MUTED)
            d, t = self._number("distance", 0.001, 1000), self._number("duration", 1/60, 1440)
            cadence = self._number("cadence", 30, 300)
            pace = round(t * 60 / d)
            self.metrics.set(f"平均配速   {pace // 60}′{pace % 60:02d}″ /km\n平均步频   {cadence:g} 步/分钟\n跑道周长   {length:.2f} 米\n预计圈数   {d * 1000 / length:.2f} 圈")
        except ValueError as error:
            self.metrics.set(str(error))
        try:
            lat, lon = self.location()
            self.location_note.set(f"导出中心（WGS84）\n纬度 {lat:.7f} · 经度 {lon:.7f}\n已按所选坐标来源处理。")
        except ValueError:
            self.location_note.set("点击“打开地图”选跑道，自动回填坐标；\n也可导入 GPX，直接沿原路线生成。")

    def refresh_route_preview(self):
        points = self.route.preview_points(400)
        latitude = sum(p[0] for p in points)/len(points)
        # Unwrap longitudes for routes crossing the date line.
        longitude = points[0][1]
        xy = [(((lon-longitude+180)%360-180)*math.cos(math.radians(latitude)), lat-latitude) for lat,lon in points]
        minx,maxx = min(p[0] for p in xy),max(p[0] for p in xy)
        miny,maxy = min(p[1] for p in xy),max(p[1] for p in xy)
        w,h = max(280,self.preview.winfo_width()),245
        scale = min((w-40)/max(maxx-minx,1e-9),(h-45)/max(maxy-miny,1e-9))
        mapped = [(w/2+(x-(minx+maxx)/2)*scale,h/2-(y-(miny+maxy)/2)*scale) for x,y in xy]
        self.preview.create_line(*[v for p in mapped for v in p],fill=ACCENT,width=3)
        for (x,y),color in [(mapped[0],'#22a06b'),(mapped[-1],'#e36b4e')]:
            self.preview.create_oval(x-4,y-4,x+4,y+4,fill=color,outline='white')
        try:
            t = self._number('duration',1/60,1440)
            cadence = self._number('cadence',30,300)
            pace = round(t*60/(self.route.length/1000))
            self.metrics.set(f'GPX 距离   {self.route.length/1000:.3f} km\n平均配速   {pace//60}′{pace%60:02d}″ /km\n平均步频   {cadence:g} 步/分钟')
        except ValueError as error:
            self.metrics.set(str(error))
        self.location_note.set(f'{self.route.name}\n{len(self.route.points)} 个原始点 · 保留原路线\n距离按路线计算，时间和步频按当前输入生成。')

    def import_gpx(self):
        if self.busy:
            return
        filename = filedialog.askopenfilename(parent=self.root,title='导入 GPX 轨迹',filetypes=[('GPX 轨迹','*.gpx')])
        if not filename:
            return
        dialog = tk.Toplevel(self.root)
        dialog.title('GPX 坐标来源')
        dialog.transient(self.root)
        dialog.resizable(False,False)
        ttk.Label(dialog,text='标准 GPX / running_page 已转换的导出文件：选择 WGS84。\n只有明确未经转换的高德 / Keep 坐标才选择 GCJ-02。',padding=15).pack()
        source = tk.StringVar(value=SOURCES[1])
        ttk.Combobox(dialog,textvariable=source,values=SOURCES,state='readonly',width=36).pack(padx=15,pady=8)
        def confirm():
            selected = source.get()
            dialog.destroy()
            self.busy = True
            self.generate_btn.configure(state='disabled')
            self.status.set('正在读取 GPX…')
            def worker():
                try:
                    routes = load_gpx(filename, lambda lat,lon:to_wgs84(lat,lon,selected))
                    self.events.put(('gpx-loaded',routes))
                except Exception as error:
                    self.events.put(('error',str(error)))
            threading.Thread(target=worker,daemon=True).start()
        ttk.Button(dialog,text='导入',command=confirm).pack(pady=(4,15))
        dialog.grab_set()

    def select_segment(self, *_):
        if self.busy or not self.gpx_routes:
            return
        self.route = self.gpx_routes[self.segment_choice.current()]
        self.v['distance'].set(f'{self.route.length/1000:.6f}')
        for key in ('distance','source','lat','lon','bearing','straight','radius'):
            self.fields[key].configure(state='disabled')
        self.refresh_preview()
        self.status.set('GPX 原路线已载入，可设置时长与步频')

    def clear_gpx(self):
        if self.busy:
            return
        self.route = None
        self.gpx_routes = []
        self.segment_choice.configure(values=(),state='disabled')
        self.segment_choice.set('')
        for key in ('distance','source','lat','lon','bearing','straight','radius'):
            self.fields[key].configure(state='readonly' if key=='source' else 'normal')
        self.v['distance'].set('5.00')
        self.refresh_preview()
        self.status.set('已切回标准跑道，可在地图上校准')

    def choose_output(self):
        folder = filedialog.askdirectory(parent=self.root)
        if folder:
            self.v["output"].set(folder)

    def open_output(self):
        path = Path(self.v["output"].get().strip())
        if path.is_dir():
            os.startfile(path)
        else:
            messagebox.showinfo("输出目录", "生成文件后即可打开输出文件夹。", parent=self.root)

    def open_map(self):
        if self.busy:
            return
        try:
            try:
                lat,lon = self.location()
            except ValueError:
                lat,lon = None,None
            state = dict(lat=lat,lon=lon,bearing=0 if self.route else self._number('bearing',0,360),straight=84.39 if self.route else self._number('straight',1,1000),radius=36.8 if self.route else self._number('radius',5,300))
            if self.route:
                state['route'] = dict(name=self.route.name,length=self.route.length,points=self.route.preview_points())
            def preview(values):
                r = Run(400,120,170,values['lat'],values['lon'],values['bearing'],values['straight'],values['radius'],datetime.now())
                return [track_position(r,r.perimeter*i/180) for i in range(181)]
            def apply(values):
                ready,result = threading.Event(),{}
                self.events.put(('map-selection',(values,ready,result)))
                ready.wait(4)
                return result.get('ok',False)
            if self.map_picker:
                self.map_picker.close()
            self.map_picker = MapPicker(state,preview,apply)
            webbrowser.open(self.map_picker.url)
            self.status.set('地图已在浏览器打开，调整后点击“应用”')
        except (ValueError,OSError) as error:
            messagebox.showerror('无法打开地图',str(error),parent=self.root)

    def read_run(self):
        lat, lon = self.route.points[0] if self.route else self.location()
        distance = self.route.length if self.route else self._number("distance", 0.001, 1000) * 1000
        duration = self._number("duration", 1/60, 1440) * 60
        if abs(duration - round(duration)) > 1e-6:
            raise ValueError("时长请填写为整秒，例如 0.5 分钟为 30 秒")
        cadence = self._number("cadence", 30, 300)
        if cadence != int(cadence):
            raise ValueError("平均步频请填写整数（步/分钟）")
        start = datetime.strptime(self.v["date"].get() + " " + self.v["time"].get(), "%Y-%m-%d %H:%M")
        return Run(distance, round(duration), int(cadence), lat, lon,
                   0 if self.route else self._number("bearing", 0, 360), 84.39 if self.route else self._number("straight", 1, 1000), 36.8 if self.route else self._number("radius", 5, 300), start,self.route)

    def start_generation(self):
        if self.busy:
            return
        try:
            run = self.read_run()
            count = int(self.v["count"].get())
            if not 1 <= count <= 100:
                raise ValueError("生成份数应为 1～100")
            interval = self._number("interval", 0, 8760)
            if count > 1 and interval * 3600 < run.duration:
                raise ValueError("批量开始时间间隔不能短于运动时长")
            if not self.v["output"].get().strip():
                raise ValueError("请选择保存目录")
            output = Path(self.v["output"].get().strip()).resolve()
            output.mkdir(parents=True, exist_ok=True)
            run.start + timedelta(hours=interval * (count - 1))
        except (ValueError, OSError, OverflowError) as error:
            messagebox.showerror("请检查输入", str(error), parent=self.root)
            return
        self.busy = True
        self.generate_btn.configure(state="disabled")
        self.progress["value"] = 0
        self.status.set("正在生成…")
        threading.Thread(target=self._worker, args=(run, count, interval, output), daemon=True).start()

    def _worker(self, run, count, interval, output):
        try:
            for i in range(count):
                item = replace(run, start=run.start + timedelta(hours=i * interval))
                filename = f"run_{item.start:%Y%m%d_%H%M%S}_{datetime.now():%H%M%S_%f}_{i+1}.fit"
                generate_fit(item, output / filename, lambda p: self.events.put(("progress", (i + p) / count * 100)))
                self.events.put(("log", f"已保存 {filename}\n平均步频 {run.cadence} 步/分钟"))
            self.events.put(("done", f"已生成 {count} 份 FIT 文件"))
        except Exception as error:
            self.events.put(("error", str(error)))

    def _poll(self):
        try:
            while True:
                kind, value = self.events.get_nowait()
                if kind == "progress":
                    self.progress["value"] = value
                elif kind == 'map-selection':
                    values,ready,result = value
                    result['ok'] = not self.busy and self.route is None
                    if result['ok']:
                        self.v['source'].set(SOURCES[1])
                        for key,number in values.items():
                            self.v[key].set(f'{number:.7f}')
                        self.refresh_preview()
                        self.status.set('地图校准已应用：中心、方向与尺寸已回填')
                    ready.set()
                elif kind == 'gpx-loaded':
                    self.busy = False
                    self.generate_btn.configure(state='normal')
                    self.gpx_routes = value
                    self.segment_choice.configure(state='readonly',values=[f'{i+1}. {r.name} ({r.length/1000:.3f} km)' for i,r in enumerate(value)])
                    self.segment_choice.current(0)
                    self.select_segment()
                    if len(value)>1:
                        messagebox.showinfo('GPX 包含多段轨迹',f'发现 {len(value)} 段，默认载入第 1 段。可在右侧下拉框选择，不会把不相连的轨迹强行连起来。',parent=self.root)
                elif kind == "log":
                    self.log_text.configure(state="normal")
                    self.log_text.insert("end", value + "\n")
                    self.log_text.see("end")
                    self.log_text.configure(state="disabled")
                else:
                    self.busy = False
                    self.generate_btn.configure(state="normal")
                    self.status.set(value if kind == "done" else "生成失败，请检查错误提示")
                    if kind == "error":
                        messagebox.showerror("生成失败", value, parent=self.root)
        except queue.Empty:
            pass
        self.root.after(100, self._poll)


def main():
    try:
        ctypes.windll.shcore.SetProcessDpiAwareness(1)
    except (AttributeError, OSError):
        pass
    root = tk.Tk()
    FITGeneratorGUI(root)
    root.mainloop()


if __name__ == "__main__":
    import sys
    if len(sys.argv) == 3 and sys.argv[1] == "--self-test-dir":
        # Packaged-runtime smoke check, including Tk resources and FIT decoding.
        import json
        from fit_tool.fit_file import FitFile
        folder = Path(sys.argv[2]).resolve()
        folder.mkdir(parents=True, exist_ok=True)
        try:
            root = tk.Tk()
            root.withdraw()
            app = FITGeneratorGUI(root)
            root.update()
            lat, lon = to_wgs84(30.58, 114.33, SOURCES[0])
            sample = Run(400, 120, 171, lat, lon, 62.5, 84.39, 36.8, datetime(2026, 9, 14, 8))
            path = folder / "runtime-test.fit"
            generate_fit(sample, path)
            decoded = FitFile.from_file(str(path))
            messages = [r.message for r in decoded.records if not r.is_definition]
            session = next(m for m in messages if isinstance(m, SessionMessage))
            assert (session.avg_cadence + session.avg_fractional_cadence) * 2 == 171
            assert session.total_distance == 400
            gpx = folder / 'runtime-test.gpx'
            gpx.write_text('<gpx><trk><trkseg><trkpt lat="30.58" lon="114.33"/><trkpt lat="30.581" lon="114.33"/><trkpt lat="30.581" lon="114.331"/></trkseg></trk></gpx>',encoding='utf8')
            app.gpx_routes = load_gpx(gpx)
            app.segment_choice.configure(values=['sample'],state='readonly')
            app.segment_choice.current(0)
            app.select_segment()
            routed = app.read_run()
            generate_fit(routed,folder/'runtime-route.fit')
            assert routed.route is not None and str(app.fields['distance'].cget('state'))=='disabled'
            from urllib.request import urlopen
            picker = MapPicker({'route':{'name':'test'}},lambda value:[],lambda value:True)
            try:
                with urlopen(picker.url) as response:
                    assert b'leaflet' in response.read()
                with urlopen(picker.url+'vendor/leaflet.js') as response:
                    assert len(response.read())>100000
            finally:
                picker.close()
            result = {"ok": True, "title": root.title(), "cadence": 171, "distance": session.total_distance, 'gpx_distance':routed.distance, 'bundled_map':True}
            root.destroy()
        except Exception:
            import traceback
            result = {"ok": False, "error": traceback.format_exc()}
        (folder / "result.json").write_text(json.dumps(result, ensure_ascii=False), encoding="utf8")
        sys.exit(0 if result["ok"] else 1)
    else:
        main()
