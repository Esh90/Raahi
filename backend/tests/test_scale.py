import numpy as np

from server.perception.depth import ScaleFitter, write_saved_scale, read_saved_scale
from server.perception.detector import Detection


def test_scale_fitter_pinhole_ratio():
    h, w = 240, 416
    raw = np.full((h, w), 0.5, np.float32)
    # door 2.0m tall, fy default 320, box height 320 px → Z = 2.0
    # but image is only 240 tall so use height 200, Z = 2.0 * 320 / 200 = 3.2
    det = Detection("door", 0.9, (40, 20, 80, 220))
    f = ScaleFitter()
    f.update(raw, [det], (h, w))
    metres = f.apply(raw)
    z = float(np.median(metres[20:220, 40:80]))
    expected = 2.0 * 320.0 / 200.0
    assert abs(z - expected) / expected < 0.05


def test_clip_and_persist_user_scale(tmp_path, monkeypatch):
    from server.perception import depth as d

    monkeypatch.setattr(d, "SCALE_FILE", tmp_path / "depth_scale.txt")
    monkeypatch.setattr(d, "SCALE_FILE_MODELS", tmp_path / "models_depth_scale.txt")
    monkeypatch.delenv("RAAHI_DEPTH_SCALE", raising=False)
    monkeypatch.delenv("BASIRAT_DEPTH_SCALE", raising=False)
    saved = write_saved_scale(0.89 / 1.40)
    assert 0.62 < saved < 0.66
    monkeypatch.setenv("RAAHI_DEPTH_SCALE", "1.0")
    assert abs(read_saved_scale() - saved) < 1e-4
