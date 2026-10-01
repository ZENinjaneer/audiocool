"""Audio decoding with PyAV (its wheels bundle FFmpeg, so no system ffmpeg is needed).

The phone records mono 44.1 kHz AAC, either in ADTS framing (``.aac``) or in MP4 (``.m4a``).
Speech models want 16 kHz mono float32.
"""

from __future__ import annotations

import logging
from pathlib import Path

import av
import numpy as np

log = logging.getLogger(__name__)

SAMPLE_RATE = 16_000


def decode(path: str | Path, sample_rate: int = SAMPLE_RATE) -> np.ndarray:
    """Decodes an audio file to mono float32 at ``sample_rate``.

    Damaged packets are skipped (a recording cut off mid-frame still decodes). If the first
    frame doesn't start at 0, the gap is filled with silence so sample positions match the
    file's timeline, which is what note offsets and transcript times refer to.
    """
    chunks: list[np.ndarray] = []
    with av.open(str(path)) as container:
        streams = [s for s in container.streams if s.type == "audio"]
        if not streams:
            raise ValueError(f"{Path(path).name} has no audio")
        stream = streams[0]
        resampler = av.AudioResampler(format="flt", layout="mono", rate=sample_rate)
        first = True
        for packet in container.demux(stream):
            try:
                frames = packet.decode()
            except av.error.InvalidDataError:
                continue
            for frame in frames:
                if first:
                    first = False
                    if frame.pts is not None and frame.time_base is not None:
                        start = float(frame.pts * frame.time_base)
                        if 0 < start < 30:
                            chunks.append(np.zeros(int(round(start * sample_rate)), np.float32))
                for out in resampler.resample(frame):
                    chunks.append(out.to_ndarray().reshape(-1))
        for out in resampler.resample(None):
            chunks.append(out.to_ndarray().reshape(-1))
    if not chunks:
        return np.zeros(0, np.float32)
    return np.ascontiguousarray(np.concatenate(chunks), dtype=np.float32)


def probe_duration_ms(path: str | Path) -> int:
    """Length of an audio file in ms without decoding it.

    MP4 stores the length; ADTS doesn't (FFmpeg's figure is a bitrate estimate), so for .aac
    the packets are counted instead.
    """
    with av.open(str(path)) as container:
        streams = [s for s in container.streams if s.type == "audio"]
        if not streams:
            raise ValueError(f"{Path(path).name} has no audio")
        stream = streams[0]
        is_adts = container.format.name in ("aac", "adts")
        if not is_adts:
            if stream.duration and stream.time_base:
                return int(round(float(stream.duration * stream.time_base) * 1000))
            if container.duration:
                return int(container.duration // 1000)
        total = 0
        last_end = 0
        for packet in container.demux(stream):
            if packet.size == 0:
                continue
            if packet.duration:
                total += packet.duration
            if packet.pts is not None and packet.duration:
                last_end = max(last_end, packet.pts + packet.duration)
        ticks = max(total, last_end)
        return int(round(float(ticks * stream.time_base) * 1000)) if stream.time_base else 0


def encode_aac(samples: np.ndarray, path: str | Path, sample_rate: int = 44_100, bitrate: int = 96_000) -> None:
    """Writes mono float samples as AAC: ADTS for .aac, MP4 for .m4a (like the phone records).

    Used by the tests and the benchmark to make phone-like files.
    """
    path = Path(path)
    fmt = "adts" if path.suffix == ".aac" else "ipod"
    with av.open(str(path), mode="w", format=fmt) as out:
        stream = out.add_stream("aac", rate=sample_rate)
        stream.bit_rate = bitrate
        stream.layout = "mono"
        data = np.asarray(samples, dtype=np.float32).reshape(1, -1)
        frame_size = 1024
        pts = 0
        for i in range(0, data.shape[1], frame_size * 16):
            block = np.ascontiguousarray(data[:, i : i + frame_size * 16])
            frame = av.AudioFrame.from_ndarray(block, format="flt", layout="mono")
            frame.sample_rate = sample_rate
            frame.pts = pts
            pts += block.shape[1]
            for packet in stream.encode(frame):
                out.mux(packet)
        for packet in stream.encode(None):
            out.mux(packet)


def resample(samples: np.ndarray, from_rate: int, to_rate: int) -> np.ndarray:
    """Resamples mono float audio with FFmpeg's resampler."""
    if from_rate == to_rate:
        return np.asarray(samples, dtype=np.float32)
    resampler = av.AudioResampler(format="flt", layout="mono", rate=to_rate)
    data = np.ascontiguousarray(np.asarray(samples, dtype=np.float32).reshape(1, -1))
    out = []
    step = from_rate * 10
    for i in range(0, data.shape[1], step):
        frame = av.AudioFrame.from_ndarray(np.ascontiguousarray(data[:, i : i + step]), format="flt", layout="mono")
        frame.sample_rate = from_rate
        out += [f.to_ndarray().reshape(-1) for f in resampler.resample(frame)]
    out += [f.to_ndarray().reshape(-1) for f in resampler.resample(None)]
    return np.concatenate(out).astype(np.float32) if out else np.zeros(0, np.float32)
