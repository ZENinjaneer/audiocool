"""Speed (real-time factor) and accuracy (WER) of the transcription models on real speech.

    .venv/bin/python tools/benchmark.py                       # TED talk from TED-LIUM long-form
    .venv/bin/python tools/benchmark.py --models parakeet-tdt-0.6b-v3 --rows 0 1
    .venv/bin/python tools/benchmark.py --audio talk.m4a --ref talk.txt

The audio is first re-encoded the way the phone records (AAC, mono, 44.1 kHz, 96 kbps, .m4a), so
the measurement includes decoding a phone file. WER uses simple normalization: lowercase,
punctuation stripped (hyphens and slashes become spaces), numbers left as written.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import time
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(HERE))
os.environ.setdefault("HF_HOME", str(HERE / ".cache" / "huggingface"))
os.environ.setdefault("TRANSFORMERS_VERBOSITY", "error")

ROWS_URL = "https://datasets-server.huggingface.co/rows?dataset=distil-whisper/tedlium-long-form&config=default&split=test&offset={offset}&length=1"


def normalize(text: str) -> list[str]:
    t = text.lower().replace("’", "'").replace("‘", "'")
    t = re.sub(r"[-–—/]", " ", t)
    t = re.sub(r"[^a-z0-9' ]+", "", t)
    words = [w.strip("'") for w in t.split()]
    return [w for w in words if w]


_WN = []


def whisper_normalizer():
    """Whisper's English text normalizer, if installed (pip install whisper-normalizer)."""
    if not _WN:
        try:
            from whisper_normalizer.english import EnglishTextNormalizer

            _WN.append(EnglishTextNormalizer())
        except ImportError:
            _WN.append(None)
    return _WN[0]


def wer(ref: list[str], hyp: list[str]) -> dict:
    """Word error rate with substitution/deletion/insertion counts (Levenshtein on words)."""
    n, m = len(ref), len(hyp)
    prev = list(range(m + 1))
    ops_prev = [(0, 0, j) for j in range(m + 1)]  # (sub, del, ins)
    for i in range(1, n + 1):
        cur = [i] + [0] * m
        ops_cur = [(0, i, 0)] + [(0, 0, 0)] * m
        r = ref[i - 1]
        for j in range(1, m + 1):
            if r == hyp[j - 1]:
                cur[j], ops_cur[j] = prev[j - 1], ops_prev[j - 1]
                continue
            sub, dele, ins = prev[j - 1] + 1, prev[j] + 1, cur[j - 1] + 1
            best = min(sub, dele, ins)
            cur[j] = best
            if best == sub:
                s, d, k = ops_prev[j - 1]; ops_cur[j] = (s + 1, d, k)
            elif best == dele:
                s, d, k = ops_prev[j]; ops_cur[j] = (s, d + 1, k)
            else:
                s, d, k = ops_cur[j - 1]; ops_cur[j] = (s, d, k + 1)
        prev, ops_prev = cur, ops_cur
    s, d, k = ops_prev[m]
    return {"wer": prev[m] / max(n, 1), "sub": s, "del": d, "ins": k, "ref_words": n}


def fetch_ted(row: int, cache: Path) -> tuple[Path, str, str]:
    cache.mkdir(parents=True, exist_ok=True)
    wav, ref = cache / f"tedlium-{row}.wav", cache / f"tedlium-{row}.txt"
    meta = cache / f"tedlium-{row}.json"
    if not (wav.exists() and ref.exists()):
        with urllib.request.urlopen(ROWS_URL.format(offset=row), timeout=60) as r:
            data = json.load(r)
        item = data["rows"][0]["row"]
        urllib.request.urlretrieve(item["audio"][0]["src"], wav)
        ref.write_text(item["text"])
        meta.write_text(json.dumps({"speaker": item.get("speaker_id")}))
    speaker = json.loads(meta.read_text()).get("speaker") if meta.exists() else None
    return wav, ref.read_text(), speaker or f"row {row}"


def phone_like(src: Path, cache: Path) -> Path:
    """Re-encodes audio the way the phone records it."""
    from audiocool_desktop import audio

    dst = cache / (src.stem + ".m4a")
    if not dst.exists():
        samples = audio.decode(src, sample_rate=44_100)
        audio.encode_aac(samples, dst, sample_rate=44_100, bitrate=96_000)
    return dst


def main() -> int:
    from audiocool_desktop.engines import Registry, default_specs

    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--models", nargs="*", help="model ids (default: all)")
    p.add_argument("--rows", nargs="*", type=int, default=[0], help="TED-LIUM long-form test rows (default: 0)")
    p.add_argument("--audio", help="your own audio file instead of TED-LIUM")
    p.add_argument("--ref", help="reference transcript (text file) for --audio")
    p.add_argument("--cache", default=str(HERE / ".cache" / "benchmark"))
    p.add_argument("--json", help="write results here")
    p.add_argument("--save-transcripts", action="store_true")
    args = p.parse_args()

    cache = Path(args.cache)
    items = []
    if args.audio:
        items.append((Path(args.audio), Path(args.ref).read_text() if args.ref else None, Path(args.audio).name))
    else:
        for row in args.rows:
            wav, ref, speaker = fetch_ted(row, cache)
            items.append((phone_like(wav, cache), ref, f"TED-LIUM test {row} ({speaker})"))

    registry = Registry(default_specs())
    ids = args.models or registry.order
    import torch

    results = []
    for mid in ids:
        spec = registry.get(mid)
        t0 = time.monotonic()
        engine = registry.engine(mid)
        load_s = time.monotonic() - t0
        if engine.device == "cuda":
            torch.cuda.reset_peak_memory_stats()
        # Warm up (CUDA kernels, allocator) so the timed runs measure steady-state speed.
        engine.transcribe_file(items[0][0])
        for path, ref, label in items:
            t1 = time.monotonic()
            res = engine.transcribe_file(path)
            wall = time.monotonic() - t1
            dur = res.duration_ms / 1000
            hyp = " ".join(s["t"] for s in res.segments)
            row = {
                "model": mid, "name": spec.name, "device": engine.device, "audio": label, "audio_s": round(dur, 1),
                "wall_s": round(wall, 2), "rtf": round(wall / dur, 5), "rtfx": round(dur / wall, 1), "load_s": round(load_s, 1),
                "timings": res.timings, "segments": len(res.segments), "chunks": res.chunks,
                "seg_len_s": _seg_stats(res.segments),
            }
            if engine.device == "cuda":
                row["peak_vram_gb"] = round(torch.cuda.max_memory_allocated() / 1e9, 2)
            if ref:
                row.update({f"wer_{k}" if k != "wer" else "wer": v for k, v in wer(normalize(ref), normalize(hyp)).items()})
                row["wer"] = round(row["wer"] * 100, 2)
                wn = whisper_normalizer()
                if wn is not None:  # the Open ASR Leaderboard's normalizer (numbers, spellings, fillers)
                    row["wer_whisper_norm"] = round(wer(wn(ref).split(), wn(hyp).split())["wer"] * 100, 2)
            results.append(row)
            print(json.dumps(row), flush=True)
            if args.save_transcripts:
                out = cache / f"{Path(path).stem}.{mid}.txt"
                out.write_text("\n".join(f"[{s['s'] / 1000:8.2f} - {s['e'] / 1000:8.2f}] {s['t']}" for s in res.segments))
        registry.unload()

    print("\n| Model | Device | Audio | WER % | WER % (Whisper norm.) | Time | RTFx | Lines (median s) |")
    print("|---|---|---|---|---|---|---|---|")
    for r in results:
        print(f"| {r['name']} | {r['device']} | {r['audio']} ({r['audio_s'] / 60:.1f} min) | {r.get('wer', '-')} | {r.get('wer_whisper_norm', '-')} "
              f"| {r['wall_s']} s | {r['rtfx']}x | {r['segments']} ({r['seg_len_s']['median']}) |")
    if args.json:
        Path(args.json).write_text(json.dumps(results, indent=2))
    return 0


def _seg_stats(segs: list[dict]) -> dict:
    lens = sorted((s["e"] - s["s"]) / 1000 for s in segs)
    if not lens:
        return {"min": 0, "median": 0, "max": 0}
    return {"min": round(lens[0], 1), "median": round(lens[len(lens) // 2], 1), "max": round(lens[-1], 1)}


if __name__ == "__main__":
    sys.exit(main())
