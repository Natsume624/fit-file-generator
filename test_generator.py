import math
from pathlib import Path
from tempfile import TemporaryDirectory
from datetime import datetime
import unittest
from dataclasses import replace
from geographiclib.geodesic import Geodesic
from coordTransform import wgs84_to_gcj02
from fit_tool.fit_file import FitFile
from fit_tool.profile.messages.record_message import RecordMessage
from fit_tool.profile.messages.session_message import SessionMessage
from fit_tool.profile.messages.lap_message import LapMessage
from main import Run, to_wgs84, SOURCES, track_xy, track_position, generate_fit


class GeneratorTests(unittest.TestCase):
    def setUp(self):
        self.run = Run(1000, 300, 171, 30.5800521, 114.3307788, 62.5, 84.39, 36.8, datetime(2026, 9, 14, 8))

    def test_coordinate_roundtrip(self):
        for lat, lon in [(30.58, 114.33), (39.9, 116.4), (22.54, 114.05)]:
            gcj_lon, gcj_lat = wgs84_to_gcj02(lon, lat)
            actual_lat, actual_lon = to_wgs84(gcj_lat, gcj_lon, SOURCES[0])
            self.assertLess(Geodesic.WGS84.Inverse(lat, lon, actual_lat, actual_lon)["s12"], 0.01)
        self.assertEqual(to_wgs84(51, -0.1, SOURCES[1]), (51, -0.1))
        self.assertEqual(to_wgs84(51, -0.1, SOURCES[0]), (51, -0.1))

    def test_constant_distance_and_closed_track(self):
        run = self.run
        points = [track_position(run, run.perimeter*i/1000) for i in range(1001)]
        distances = [Geodesic.WGS84.Inverse(*a, *b)["s12"] for a,b in zip(points, points[1:])]
        self.assertLess(abs(sum(distances)-run.perimeter), 0.01)
        self.assertLess(max(distances)-min(distances), 0.0001)
        self.assertLess(Geodesic.WGS84.Inverse(*points[0], *points[-1])["s12"], 0.0001)

    def test_bearing_and_segment_continuity(self):
        run = self.run
        for s in [0, run.straight, run.straight+math.pi*run.radius, 2*run.straight+math.pi*run.radius, run.perimeter]:
            before = track_xy(s-0.001, run.straight, run.radius, 0)
            after = track_xy(s+0.001, run.straight, run.radius, 0)
            self.assertAlmostEqual(math.dist(before, after), 0.002, places=6)
        x, y = track_xy(0, run.straight, run.radius, 0)
        rx, ry = track_xy(0, run.straight, run.radius, 90)
        self.assertAlmostEqual(rx, y)
        self.assertAlmostEqual(ry, -x)

    def test_fit_roundtrip_cadence_and_distance(self):
        for cadence in [170, 171, 300]:
            run = replace(self.run, cadence=cadence)
            with TemporaryDirectory(dir=Path(__file__).parent) as temp:
                path = Path(temp)/"test.fit"
                generate_fit(run, path)
                parsed = FitFile.from_file(str(path))
                messages = [r.message for r in parsed.records if not r.is_definition]
                points = [m for m in messages if isinstance(m, RecordMessage)]
                summaries = [m for m in messages if isinstance(m, (SessionMessage, LapMessage))]
                self.assertEqual(len(points), run.duration+1)
                self.assertEqual(len(summaries), 2)
                for point in points:
                    self.assertEqual((point.cadence + point.fractional_cadence)*2, cadence)
                for summary in summaries:
                    self.assertEqual((summary.avg_cadence+summary.avg_fractional_cadence)*2, cadence)
                    self.assertEqual(summary.total_distance, 1000)
                    self.assertEqual(summary.total_timer_time, 300)
                self.assertEqual(points[-1].timestamp-points[0].timestamp, 300000)
                distance = sum(Geodesic.WGS84.Inverse(a.position_lat,a.position_long,b.position_lat,b.position_long)["s12"] for a,b in zip(points,points[1:]))
                self.assertLess(abs(distance-1000), 1)
                with self.assertRaises(FileExistsError):
                    generate_fit(run, path)

    def test_generation_rejects_unsafe_resource_inputs(self):
        invalid = [
            replace(self.run, duration=0),
            replace(self.run, duration=86_401),
            replace(self.run, cadence=30.5),
            replace(self.run, distance=float('nan')),
            replace(self.run, lat=91),
        ]
        for run in invalid:
            with self.subTest(run=run), self.assertRaises(ValueError):
                generate_fit(run, Path('unused.fit'))


if __name__ == "__main__":
    unittest.main()
