import math
from pathlib import Path
from tempfile import TemporaryDirectory
from datetime import datetime, timedelta
import unittest
from dataclasses import replace
from geographiclib.geodesic import Geodesic
from coordTransform import wgs84_to_gcj02
from fit_tool.fit_file import FitFile
from fit_tool.profile.messages.record_message import RecordMessage
from fit_tool.profile.messages.session_message import SessionMessage
from fit_tool.profile.messages.lap_message import LapMessage
from main import Run, to_wgs84, SOURCES, track_xy, varied_track_xy, track_position, motion_samples, generate_fit


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

    def test_natural_track_is_bounded_continuous_and_different_each_lap(self):
        run = replace(self.run, track_variation=3)
        seed = int(run.start.timestamp())
        def position(s, bearing=run.bearing):
            return varied_track_xy(s, run.straight, run.radius, bearing, run.track_variation, seed)
        for s in [run.perimeter * i / 80 for i in range(401)]:
            self.assertLessEqual(math.dist(position(s), track_xy(s, run.straight, run.radius, run.bearing)), 3.000001)
        laps = [[position(lap * run.perimeter + run.perimeter * i / 40) for i in range(40)] for lap in range(3)]
        for a, b in zip(laps, laps[1:]):
            self.assertGreater(max(math.dist(x, y) for x, y in zip(a, b)), 0.5)
        for lap in range(3):
            for boundary in [0, run.straight, run.straight + math.pi * run.radius,
                             2 * run.straight + math.pi * run.radius, run.perimeter]:
                s = lap * run.perimeter + boundary
                self.assertLess(math.dist(position(s - 0.001), position(s + 0.001)), 0.0025)
        x, y = position(123, 0)
        rx, ry = position(123, 90)
        self.assertAlmostEqual(rx, y)
        self.assertAlmostEqual(ry, -x)
        other = replace(run, start=datetime(2026, 9, 15, 8))
        self.assertGreater(Geodesic.WGS84.Inverse(*track_position(run, 123), *track_position(other, 123))['s12'], .1)

    def test_natural_track_small_radius_and_zero_mode(self):
        for s in range(1000):
            ideal = track_xy(s, 1, 5, 62.5)
            self.assertEqual(varied_track_xy(s, 1, 5, 62.5, 0, 1), ideal)
            varied = varied_track_xy(s, 1, 5, 62.5, 8, 1)
            self.assertLessEqual(math.dist(ideal, varied), 1.500001)

    def test_natural_fit_preserves_metrics_and_has_no_position_jumps(self):
        run = replace(self.run, distance=3010, duration=1068, track_variation=3, step_length=.8)
        with TemporaryDirectory(dir=Path(__file__).parent) as temp:
            path = Path(temp) / 'natural.fit'
            generate_fit(run, path)
            messages = [r.message for r in FitFile.from_file(str(path)).records if not r.is_definition]
            points = [m for m in messages if isinstance(m, RecordMessage)]
            summaries = [m for m in messages if isinstance(m, (LapMessage, SessionMessage))]
            lengths = [Geodesic.WGS84.Inverse(a.position_lat, a.position_long, b.position_lat, b.position_long)['s12']
                       for a, b in zip(points, points[1:])]
            for length, point in zip(lengths, points[1:]):
                self.assertLess(length, point.speed * 1.2)
                self.assertGreater(length, point.speed * .8)
            self.assertLess(abs(sum(lengths) - run.distance), run.distance * .02)
            for point, distance in [(points[0], 0), (points[-1], run.distance)]:
                self.assertLess(Geodesic.WGS84.Inverse(point.position_lat, point.position_long, *track_position(run, distance))['s12'], .02)
            for summary in summaries:
                self.assertEqual(summary.total_distance, 3010)
                self.assertEqual(summary.total_timer_time, 1068)
                self.assertEqual(summary.avg_step_length, 800)
                self.assertEqual((summary.avg_cadence + summary.avg_fractional_cadence) * 2, 171)

    def test_generation_rejects_invalid_track_variation(self):
        with TemporaryDirectory(dir=Path(__file__).parent) as temp:
            path = Path(temp) / 'invalid.fit'
            for value in [-.1, 8.01, float('nan'), float('inf'), True, '3']:
                with self.subTest(value=value), self.assertRaises(ValueError):
                    generate_fit(replace(self.run, track_variation=value), path)
                self.assertFalse(path.exists())

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
                for point, (_, speed) in zip(points, motion_samples(run)):
                    self.assertEqual((point.cadence + point.fractional_cadence)*2, cadence)
                    self.assertAlmostEqual(point.step_length, speed * 60 / cadence * 1000, delta=0.051)
                for summary in summaries:
                    self.assertEqual((summary.avg_cadence+summary.avg_fractional_cadence)*2, cadence)
                    self.assertEqual(summary.total_distance, 1000)
                    self.assertEqual(summary.total_timer_time, 300)
                    self.assertAlmostEqual(summary.avg_step_length, run.effective_step_length * 1000, delta=0.051)
                self.assertEqual(points[-1].timestamp-points[0].timestamp, 300000)
                distance = sum(Geodesic.WGS84.Inverse(a.position_lat,a.position_long,b.position_lat,b.position_long)["s12"] for a,b in zip(points,points[1:]))
                self.assertLess(abs(distance-1000), 1)
                with self.assertRaises(FileExistsError):
                    generate_fit(run, path)

    def test_speed_profile_totals_bounds_smoothness_and_repeatability(self):
        for duration in [1, 2, 9, 30, 300, 1800, 86400]:
            for variation in [0, 12, 30]:
                run = replace(self.run, duration=duration, speed_variation=variation)
                with self.subTest(duration=duration, variation=variation):
                    samples = motion_samples(run)
                    speeds = [speed for _, speed in samples[1:]]
                    average = run.distance / run.duration
                    self.assertEqual(len(samples), duration + 1)
                    self.assertEqual(samples[0][0], 0)
                    self.assertEqual(samples[-1][0], run.distance)
                    self.assertAlmostEqual(math.fsum(speeds), run.distance, places=7)
                    self.assertGreater(min(speeds), 0)
                    self.assertGreaterEqual(min(speeds), average * (1 - variation / 100) - 1e-8)
                    self.assertLessEqual(max(speeds), average * (1 + variation / 100) + 1e-8)
                    for (before, _), (after, speed) in zip(samples, samples[1:]):
                        self.assertAlmostEqual(after - before, speed, places=8)
                    if duration >= 300 and variation:
                        self.assertGreater(max(speeds) - min(speeds), average * variation / 100)
                        self.assertLess(max(abs(a - b) for a, b in zip(speeds, speeds[1:])), average * variation / 100 * .2)
        self.assertEqual(motion_samples(self.run), motion_samples(self.run))
        other = replace(self.run, start=self.run.start + timedelta(days=1))
        self.assertNotEqual(motion_samples(self.run), motion_samples(other))

    def test_variable_fit_speed_distance_positions_and_summary_agree(self):
        with TemporaryDirectory(dir=Path(__file__).parent) as temp:
            path = Path(temp) / 'variable.fit'
            generate_fit(self.run, path)
            messages = [r.message for r in FitFile.from_file(str(path)).records if not r.is_definition]
            points = [m for m in messages if isinstance(m, RecordMessage)]
            speeds = [point.speed for point in points[1:]]
            average = self.run.distance / self.run.duration
            self.assertLess(min(speeds), average * .95)
            self.assertGreater(max(speeds), average * 1.05)
            self.assertAlmostEqual(math.fsum(speeds), self.run.distance, delta=self.run.duration * .0005)
            for before, after in zip(points, points[1:]):
                self.assertEqual(after.timestamp - before.timestamp, 1000)
                self.assertAlmostEqual(after.distance - before.distance, after.speed, delta=.011)
                gps_speed = Geodesic.WGS84.Inverse(before.position_lat, before.position_long,
                                                  after.position_lat, after.position_long)['s12']
                self.assertAlmostEqual(gps_speed, after.speed, delta=.03)
            for point in points:
                expected = track_position(self.run, point.distance)
                self.assertLess(Geodesic.WGS84.Inverse(point.position_lat, point.position_long, *expected)['s12'], .025)
                self.assertAlmostEqual(point.step_length, point.speed * 60 / self.run.cadence * 1000, delta=.25)
                self.assertAlmostEqual(point.power, point.speed * 70 * 1.05, delta=.54)
            for summary in (m for m in messages if isinstance(m, (LapMessage, SessionMessage))):
                self.assertEqual(summary.total_distance, self.run.distance)
                self.assertEqual(summary.total_timer_time, self.run.duration)
                self.assertAlmostEqual(summary.avg_speed, average, delta=.0005)
                self.assertEqual(summary.max_speed, max(speeds))
                self.assertGreater(summary.max_speed, summary.avg_speed)

    def test_zero_speed_variation_keeps_constant_motion(self):
        run = replace(self.run, speed_variation=0)
        with TemporaryDirectory(dir=Path(__file__).parent) as temp:
            path = Path(temp) / 'constant.fit'
            generate_fit(run, path)
            messages = [r.message for r in FitFile.from_file(str(path)).records if not r.is_definition]
            points = [m for m in messages if isinstance(m, RecordMessage)]
            self.assertEqual(len({point.speed for point in points}), 1)
            self.assertEqual(len({point.step_length for point in points}), 1)
            for summary in (m for m in messages if isinstance(m, (LapMessage, SessionMessage))):
                self.assertEqual(summary.avg_speed, summary.max_speed)

    def test_generation_rejects_invalid_speed_variation_and_overflow(self):
        with TemporaryDirectory(dir=Path(__file__).parent) as temp:
            path = Path(temp) / 'invalid.fit'
            invalid = [replace(self.run, speed_variation=value)
                       for value in [-.1, 30.01, float('nan'), float('inf'), True, '12']]
            invalid.extend([replace(self.run, distance=19500, duration=300, step_length=.8),
                            replace(self.run, distance=5000, cadence=30)])
            for run in invalid:
                with self.subTest(run=run), self.assertRaises(ValueError):
                    generate_fit(run, path)
                self.assertFalse(path.exists())

    def test_custom_step_length_roundtrip(self):
        for length in [0.01, 0.8, 1.23456, 6.5]:
            with self.subTest(step_length=length), TemporaryDirectory(dir=Path(__file__).parent) as temp:
                run = replace(self.run, distance=5, duration=2, step_length=length)
                path = Path(temp) / "custom.fit"
                generate_fit(run, path)
                messages = [r.message for r in FitFile.from_file(str(path)).records if not r.is_definition]
                points = [m for m in messages if isinstance(m, RecordMessage)]
                summaries = [m for m in messages if isinstance(m, (SessionMessage, LapMessage))]
                self.assertEqual(len(points), 3)
                self.assertEqual(len(summaries), 2)
                for point in points:
                    self.assertAlmostEqual(point.step_length, length * 1000, delta=0.051)
                    self.assertEqual((point.cadence + point.fractional_cadence) * 2, run.cadence)
                for summary in summaries:
                    self.assertAlmostEqual(summary.avg_step_length, length * 1000, delta=0.051)
                    self.assertEqual(summary.total_distance, run.distance)
                    self.assertEqual(summary.total_timer_time, run.duration)
                    self.assertEqual((summary.avg_cadence + summary.avg_fractional_cadence) * 2, run.cadence)

    def test_generation_rejects_invalid_custom_step_length(self):
        invalid = [0, -0.8, 0.009, 6.501, float('nan'), float('inf'), True, '0.8']
        with TemporaryDirectory(dir=Path(__file__).parent) as temp:
            path = Path(temp) / "invalid.fit"
            for length in invalid:
                with self.subTest(step_length=length), self.assertRaises(ValueError):
                    generate_fit(replace(self.run, step_length=length), path)
                self.assertFalse(path.exists())

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
