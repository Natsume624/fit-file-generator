"""Local GPX geometry. No network access, no implicit coordinate conversion."""
from bisect import bisect_right
from dataclasses import dataclass
import math
from pathlib import Path
import xml.etree.ElementTree as ET
from geographiclib.geodesic import Geodesic


@dataclass(frozen=True)
class Route:
    name: str
    points: tuple
    cumulative: tuple
    bearings: tuple

    @property
    def length(self):
        return self.cumulative[-1]

    def position(self, distance):
        if distance <= 0:
            return self.points[0]
        if distance >= self.length:
            return self.points[-1]
        i = bisect_right(self.cumulative, distance) - 1
        lat, lon = self.points[i]
        point = Geodesic.WGS84.Direct(lat, lon, self.bearings[i], distance-self.cumulative[i])
        return point['lat2'], point['lon2']

    def preview_points(self, limit=1200):
        # Display-only sampling; exports retain the full source geometry.
        step = max(1, math.ceil((len(self.points)-1)/(limit-1)))
        result = list(self.points[::step])
        if result[-1] != self.points[-1]:
            result.append(self.points[-1])
        return result


def make_route(name, points):
    clean, cumulative, bearings = [], [0.0], []
    for lat, lon in points:
        if not (math.isfinite(lat) and math.isfinite(lon) and -85 <= lat <= 85 and -180 <= lon <= 180):
            raise ValueError('GPX 包含无效经纬度（纬度应在 ±85° 内）')
        if clean:
            inverse = Geodesic.WGS84.Inverse(*clean[-1], lat, lon)
            if inverse['s12'] < 0.001:
                continue
            cumulative.append(cumulative[-1]+inverse['s12'])
            bearings.append(inverse['azi1'])
        clean.append((lat, lon))
    if len(clean) < 2 or cumulative[-1] < 1:
        raise ValueError('轨迹段需要至少两个不同位置，且总长不少于 1 米')
    if cumulative[-1] > 1000000:
        raise ValueError('轨迹段总长超过 1000 km，请先裁剪')
    return Route(name, tuple(clean), tuple(cumulative), tuple(bearings))


def load_gpx(path, convert=lambda lat, lon: (lat, lon)):
    path = Path(path)
    if path.stat().st_size > 20*1024*1024:
        raise ValueError('GPX 超过 20 MB，请先裁剪轨迹')
    data = path.read_bytes()
    # GPX does not need a DTD. Reject entities before the XML parser runs,
    # including the UTF-16 representation of declaration tokens.
    if b'<!DOCTYPE' in data.replace(b'\x00', b'').upper() or b'<!ENTITY' in data.replace(b'\x00', b'').upper():
        raise ValueError('不支持含 DTD 或实体声明的 GPX')
    try:
        root = ET.fromstring(data)
    except ET.ParseError as error:
        raise ValueError('GPX XML 格式错误') from error
    local = lambda tag: tag.rsplit('}', 1)[-1]
    if local(root.tag) != 'gpx':
        raise ValueError('请选择 GPX 文件')
    segments = [node for node in root.iter() if local(node.tag) == 'trkseg']
    if not segments:
        segments = [node for node in root.iter() if local(node.tag) == 'rte']
    routes, point_count = [], 0
    for index, segment in enumerate(segments, 1):
        points = []
        for node in segment:
            if local(node.tag) not in ('trkpt', 'rtept'):
                continue
            point_count += 1
            if point_count > 100000:
                raise ValueError('GPX 轨迹点超过 10 万，请先裁剪')
            try:
                lat, lon = float(node.attrib['lat']), float(node.attrib['lon'])
                if not (math.isfinite(lat) and math.isfinite(lon) and -85 <= lat <= 85 and -180 <= lon <= 180):
                    raise ValueError()
                points.append(convert(lat, lon))
            except (KeyError, ValueError, OverflowError) as error:
                raise ValueError(f'GPX 第 {index} 段包含无效经纬度') from error
        if len(points) >= 2:
            try:
                routes.append(make_route(f'{path.stem} · 第 {index} 段', points))
            except ValueError as error:
                if '两个不同位置' not in str(error):
                    raise
    if not routes:
        raise ValueError('文件中没有可用的轨迹段（仅有地点标记不算轨迹）')
    return routes
