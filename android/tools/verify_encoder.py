"""Decode EncoderSmoke outputs and check actual FIT cadence and step-length fields."""
import sys
from pathlib import Path

from fit_tool.fit_file import FitFile
from fit_tool.profile.messages.lap_message import LapMessage
from fit_tool.profile.messages.record_message import RecordMessage
from fit_tool.profile.messages.session_message import SessionMessage
from geographiclib.geodesic import Geodesic


def verify(path, cadence, custom_length_mm=None):
    messages = [r.message for r in FitFile.from_file(str(path)).records if not r.is_definition]
    records = [m for m in messages if isinstance(m, RecordMessage)]
    summaries = [m for m in messages if isinstance(m, (LapMessage, SessionMessage))]
    session = next(m for m in summaries if isinstance(m, SessionMessage))
    expected = custom_length_mm
    if expected is None:
        expected = session.total_distance * 60 / session.total_timer_time / cadence * 1000
    assert len(records) == int(session.total_timer_time) + 1
    assert len(summaries) == 2
    for record in records:
        assert record.step_length is not None, f"{path}: missing record step_length"
        instant_length = custom_length_mm if custom_length_mm is not None else record.speed * 60 / cadence * 1000
        assert abs(record.step_length - instant_length) < 0.3, (path, record.step_length, instant_length)
        assert (record.cadence + record.fractional_cadence) * 2 == cadence
    speeds = [record.speed for record in records[1:]]
    average = session.total_distance / session.total_timer_time
    assert min(speeds) < average < max(speeds)
    assert records[0].distance == 0 and records[-1].distance == session.total_distance
    assert abs(sum(speeds) - session.total_distance) <= session.total_timer_time * .0005
    for before, after in zip(records, records[1:]):
        assert after.timestamp - before.timestamp == 1000
        assert abs(after.distance - before.distance - after.speed) < .011
    for summary in summaries:
        assert summary.avg_step_length is not None, f"{path}: missing {type(summary).__name__} avg_step_length"
        assert abs(summary.avg_step_length - expected) < 0.1, (path, summary.avg_step_length, expected)
        assert (summary.avg_cadence + summary.avg_fractional_cadence) * 2 == cadence
        assert abs(summary.avg_speed - average) <= .0005
        assert summary.max_speed == max(speeds)
    print(f"{path.name}: OK ({len(records)} records, {session.avg_step_length} mm/step)")


def verify_natural(path):
    # Independent FIT decoding also checks the Java/Python geometry stays in sync.
    sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
    from datetime import datetime, timezone
    from main import Run, track_position, motion_samples
    messages = [r.message for r in FitFile.from_file(str(path)).records if not r.is_definition]
    points = [m for m in messages if isinstance(m, RecordMessage)]
    run = Run(5000, 1800, 171, 30.58, 114.33, 62.5, 84.39, 36.8,
              datetime(2026, 9, 14, 0, tzinfo=timezone.utc), step_length=.8, track_variation=3)
    for point, (distance, speed) in zip(points, motion_samples(run)):
        expected = track_position(run, distance)
        assert Geodesic.WGS84.Inverse(point.position_lat, point.position_long, *expected)['s12'] < .02
        assert abs(point.distance - distance) <= .0051
        assert abs(point.speed - speed) <= .00051
    lengths = [Geodesic.WGS84.Inverse(a.position_lat, a.position_long, b.position_lat, b.position_long)['s12']
               for a, b in zip(points, points[1:])]
    assert all(length < point.speed * 1.2 for length, point in zip(lengths, points[1:]))
    assert abs(sum(lengths) - run.distance) < run.distance * .02
    print(f"{path.name}: natural geometry OK (GPS polyline {sum(lengths):.2f} m)")


if __name__ == "__main__":
    if len(sys.argv) not in (5, 6):
        raise SystemExit("Usage: verify_encoder.py auto.fit route.fit custom.fit route-custom.fit [natural.fit]")
    for path, cadence, custom in zip(sys.argv[1:], (171, 170, 171, 170, 171), (None, None, 800, 800, 800)):
        verify(Path(path), cadence, custom)
    if len(sys.argv) == 6:
        verify_natural(Path(sys.argv[5]))
