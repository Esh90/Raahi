"""pydub helpers: ffmpeg discovery, silence trim, loudness normalisation."""
from __future__ import annotations

import io
import shutil
import subprocess

from pydub import AudioSegment
from pydub.silence import detect_leading_silence

TARGET_DBFS = -18.0     # average loudness every clip is normalised to
PEAK_CEILING = -1.0     # never louder than this peak
SILENCE_THRESH = -45.0  # dBFS below which audio counts as silence
KEEP_PAD_MS = 20        # keep a little breath before/after the speech
TAIL_MS = 60            # silence appended so joined clips don't run together
SAMPLE_RATE = 44100


def _setup_ffmpeg() -> None:
    if shutil.which("ffmpeg"):
        return
    try:
        import imageio_ffmpeg
        AudioSegment.converter = imageio_ffmpeg.get_ffmpeg_exe()
    except ImportError:
        pass  # pydub will warn; install imageio-ffmpeg or system ffmpeg


_setup_ffmpeg()


def load(src) -> AudioSegment:
    """Decode any audio file (path) or bytes via ffmpeg -> wav. Avoids pydub's need for
    ffprobe (imageio-ffmpeg ships ffmpeg only)."""
    data = None
    if isinstance(src, (bytes, bytearray)):
        data, src_arg = bytes(src), "pipe:0"
    else:
        src_arg = str(src)
    cmd = [AudioSegment.converter, "-hide_banner", "-loglevel", "error", "-i", src_arg,
           "-f", "wav", "-ac", "1", "-ar", str(SAMPLE_RATE), "pipe:1"]
    out = subprocess.run(cmd, input=data, capture_output=True, check=True).stdout
    return AudioSegment.from_wav(io.BytesIO(out))


def trim(seg: AudioSegment) -> AudioSegment:
    start = detect_leading_silence(seg, silence_threshold=SILENCE_THRESH)
    end = detect_leading_silence(seg.reverse(), silence_threshold=SILENCE_THRESH)
    start = max(0, start - KEEP_PAD_MS)
    end = max(0, end - KEEP_PAD_MS)
    trimmed = seg[start:len(seg) - end]
    return trimmed if len(trimmed) > 50 else seg


def normalise(seg: AudioSegment) -> AudioSegment:
    if seg.dBFS == float("-inf"):
        return seg
    seg = seg.apply_gain(TARGET_DBFS - seg.dBFS)
    if seg.max_dBFS > PEAK_CEILING:
        seg = seg.apply_gain(PEAK_CEILING - seg.max_dBFS)
    return seg


def finish(seg: AudioSegment, tail_ms: int = TAIL_MS) -> AudioSegment:
    """Full pipeline used for every clip (recorded, generated or live)."""
    seg = seg.set_channels(1).set_frame_rate(SAMPLE_RATE)
    seg = normalise(trim(seg))
    return seg + AudioSegment.silent(duration=tail_ms, frame_rate=SAMPLE_RATE)


def export_mp3(seg: AudioSegment, path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    seg.export(path, format="mp3", bitrate="64k")
