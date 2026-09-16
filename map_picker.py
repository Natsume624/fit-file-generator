"""Loopback-only map editor bridge. Browser renders tiles; GPX stays local."""
import json
import math
import mimetypes
from collections import OrderedDict
from pathlib import Path
import secrets
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit, parse_qs, urlencode
from urllib.request import Request, urlopen

WEB_ROOT = Path(__file__).resolve().parent / 'web'
MAX_REQUEST_BYTES = 4096
MAX_SEARCH_RESPONSE_BYTES = 500_000
MAX_SEARCH_CACHE_ENTRIES = 64
CONTENT_SECURITY_POLICY = (
    "default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
    "img-src 'self' data: https://tile.openstreetmap.org; connect-src 'self'; "
    "font-src 'self'; object-src 'none'; base-uri 'none'; form-action 'self'; "
    "frame-ancestors 'none'"
)


def validate_selection(value):
    if not isinstance(value, dict):
        raise ValueError('请选择有效跑道参数')
    limits = {'lat': (-85, 85), 'lon': (-180, 180), 'bearing': (0, 360), 'straight': (1, 1000), 'radius': (5, 300)}
    result = {}
    for key, (low, high) in limits.items():
        number = float(value[key])
        if not math.isfinite(number) or not low <= number <= high:
            raise ValueError('跑道参数超出范围')
        result[key] = number
    return result


class MapPicker:
    def __init__(self, state, preview, apply):
        self.state, self.preview, self.apply = state, preview, apply
        self.token = secrets.token_urlsafe(24)
        self.cache = OrderedDict()
        self.search_lock = threading.Lock()
        self.last_search = 0.0
        owner = self

        class Handler(BaseHTTPRequestHandler):
            def setup(self):
                super().setup()
                # A local process or browser tab must not hold a worker forever.
                self.connection.settimeout(5)

            def log_message(self, *args):
                pass

            def send(self, status, data, content_type='application/json; charset=utf-8'):
                if not isinstance(data, bytes):
                    data = json.dumps(data, ensure_ascii=False).encode('utf8')
                self.send_response(status)
                self.send_header('Content-Type', content_type)
                self.send_header('Content-Length', str(len(data)))
                self.send_header('X-Content-Type-Options', 'nosniff')
                self.send_header('X-Frame-Options', 'DENY')
                self.send_header('Content-Security-Policy', CONTENT_SECURITY_POLICY)
                self.send_header('Permissions-Policy', 'geolocation=(), camera=(), microphone=()')
                self.send_header('Cross-Origin-Resource-Policy', 'same-origin')
                self.send_header('Referrer-Policy', 'no-referrer')
                self.send_header('Cache-Control', 'no-store')
                self.end_headers()
                self.wfile.write(data)

            def valid(self):
                first, separator, _ = urlsplit(self.path).path.lstrip('/').partition('/')
                return (self.headers.get('Host') == owner.address and separator
                        and secrets.compare_digest(first, owner.token))

            def do_GET(self):
                if not self.valid():
                    return self.send(403, {'error': '访问已失效，请从程序重新打开地图'})
                part = urlsplit(self.path)
                relative = part.path[len('/'+owner.token+'/'):]
                if relative == 'state':
                    return self.send(200, owner.state)
                if relative == 'search':
                    try:
                        query = parse_qs(part.query).get('q', [''])[0].strip()
                        return self.send(200, owner.search(query))
                    except Exception:
                        return self.send(502, {'error': '地点搜索暂时不可用。可缩放地图选点，或导入 GPX。'})
                allowed = {'': 'map.html', 'map.js': 'map.js', 'map.css': 'map.css',
                           'vendor/leaflet.js': 'vendor/leaflet.js', 'vendor/leaflet.css': 'vendor/leaflet.css'}
                if relative not in allowed:
                    return self.send(404, {'error': '不存在'})
                file = WEB_ROOT / allowed[relative]
                content_type = mimetypes.guess_type(str(file))[0] or 'application/octet-stream'
                try:
                    return self.send(200, file.read_bytes(), content_type+'; charset=utf-8')
                except OSError:
                    return self.send(500, {'error': '地图资源缺失，请重新打包'})

            def do_POST(self):
                if not self.valid() or self.headers.get('Origin') != 'http://'+owner.address:
                    return self.send(403, {'error': '访问来源不匹配'})
                endpoint = urlsplit(self.path).path.rsplit('/', 1)[-1]
                if endpoint not in ('preview', 'apply'):
                    return self.send(404, {'error': '不存在'})
                try:
                    lengths = self.headers.get_all('Content-Length', [])
                    if (self.headers.get('Transfer-Encoding') is not None
                            or len(lengths) != 1 or not lengths[0].isdecimal()
                            or self.headers.get_content_type() != 'application/json'):
                        raise ValueError('请求格式无效')
                    size = int(lengths[0])
                    if not 0 < size <= MAX_REQUEST_BYTES:
                        raise ValueError('请求大小无效')
                    body = self.rfile.read(size)
                    if len(body) != size:
                        raise ValueError('请求不完整')
                    values = validate_selection(json.loads(body.decode('utf-8')))
                    if endpoint == 'preview':
                        return self.send(200, {'points': owner.preview(values)})
                    if owner.state.get('route'):
                        return self.send(409, {'error': 'GPX 模式只查看原路线，请回桌面切换至标准跑道后校准'})
                    reply = owner.apply(values)
                    return self.send(200 if reply else 409, {'ok': bool(reply), 'error': '' if reply else '程序正在导出或设置已变化，请回桌面重新打开地图'})
                except (ValueError, KeyError, TypeError, OverflowError, OSError):
                    return self.send(400, {'error': '请输入有效的经纬度和跑道参数'})

        self.server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        self.server.daemon_threads = True
        self.address = f'127.0.0.1:{self.server.server_port}'
        self.url = f'http://{self.address}/{self.token}/'
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def search(self, query):
        if not 2 <= len(query) <= 120:
            return {'results': []}
        with self.search_lock:
            if query in self.cache:
                self.cache.move_to_end(query)
                return self.cache[query]
            if time.monotonic()-self.last_search < 1.2:
                return {'error': '请稍后再搜索', 'results': []}
            self.last_search = time.monotonic()
            request = Request('https://photon.komoot.io/api/?'+urlencode({'q':query, 'limit':5}), headers={'User-Agent':'NatsumeFitGenerator/2.1.1 (personal desktop route editor)'})
            with urlopen(request, timeout=12) as response:
                raw = response.read(MAX_SEARCH_RESPONSE_BYTES + 1)
            if len(raw) > MAX_SEARCH_RESPONSE_BYTES:
                raise ValueError('地点搜索响应过大')
            payload = json.loads(raw)
            if not isinstance(payload, dict) or not isinstance(payload.get('features', []), list):
                raise ValueError('地点搜索响应格式无效')
            results = []
            for feature in payload.get('features', [])[:5]:
                if not isinstance(feature, dict):
                    continue
                geometry = feature.get('geometry', {})
                if not isinstance(geometry, dict):
                    continue
                coordinates = geometry.get('coordinates', [])
                if not isinstance(coordinates, list) or len(coordinates) < 2:
                    continue
                try:
                    lon, lat = float(coordinates[0]), float(coordinates[1])
                except (TypeError, ValueError, OverflowError):
                    continue
                properties = feature.get('properties', {})
                if not isinstance(properties, dict):
                    properties = {}
                label = ' · '.join(str(properties[k]) for k in ('name', 'district', 'street', 'city', 'state') if properties.get(k))
                category = {'university':'大学','bus_stop':'公交站','station':'车站','subway_entrance':'地铁入口','sports_centre':'体育中心','stadium':'体育场','college':'学院','school':'学校'}.get(properties.get('osm_value'))
                if category:
                    label += ' · '+category
                if math.isfinite(lat) and math.isfinite(lon) and -85 <= lat <= 85 and -180 <= lon <= 180:
                    label = ' '.join(label.split())[:200]
                    results.append({'lat':lat, 'lon':lon, 'label':label or '搜索结果'})
            value = {'results':results}
            self.cache[query] = value
            self.cache.move_to_end(query)
            while len(self.cache) > MAX_SEARCH_CACHE_ENTRIES:
                self.cache.popitem(last=False)
            return value

    def close(self):
        self.server.shutdown()
        self.server.server_close()
