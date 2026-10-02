"""Decode EncoderSmoke outputs and check actual FIT cadence and step-length fields."""
import sys
from pathlib import Path

from fit_tool.fit_file import FitFile
from fit_tool.profile.messages.lap_message import LapMessage
from fit_tool.profile.messages.record_message import RecordMessage
from fit_tool.profile.messages.session_message import SessionMessage


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
        assert abs(record.step_length - expected) < 0.1, (path, record.step_length, expected)
        assert (record.cadence + record.fractional_cadence) * 2 == cadence
    for summary in summaries:
        assert summary.avg_step_length is not None, f"{path}: missing {type(summary).__name__} avg_step_length"
        assert abs(summary.avg_step_length - expected) < 0.1, (path, summary.avg_step_length, expected)
        assert (summary.avg_cadence + summary.avg_fractional_cadence) * 2 == cadence
    print(f"{path.name}: OK ({len(records)} records, {session.avg_step_length} mm/step)")


if __name__ == "__main__":
    if len(sys.argv) != 5:
        raise SystemExit("Usage: verify_encoder.py auto.fit route.fit custom.fit route-custom.fit")
    for path, cadence, custom in zip(sys.argv[1:], (171, 170, 171, 170), (None, None, 800, 800)):
        verify(Path(path), cadence, custom)
