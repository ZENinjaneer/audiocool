"""NVIDIA Parakeet TDT 0.6B v3 through Hugging Face Transformers (no NeMo needed).

A FastConformer encoder with a token-and-duration transducer: very fast, punctuated and cased
output, and token timestamps straight from the decoder (80 ms resolution).
"""

from __future__ import annotations

import gc
import logging
import warnings

import numpy as np

from .base import SR, Cancelled, Engine, IsCancelled, OomSplitter, Progress, batches_by_length, quiet_transformers
from .segmenter import Word

log = logging.getLogger(__name__)


def features(fe, chunks: list[np.ndarray], device: str):
    """Log-mel features and mask, as ParakeetFeatureExtractor computes them, but on ``device``.

    (The extractor's own ``device`` option leaves the audio on the CPU, so it fails on CUDA.)
    """
    import torch
    from transformers.models.parakeet.feature_extraction_parakeet import EPSILON

    lengths_np = np.array([len(c) for c in chunks])
    batch = np.zeros((len(chunks), int(lengths_np.max())), np.float32)
    for i, c in enumerate(chunks):
        batch[i, : len(c)] = c
    x = torch.from_numpy(batch).to(device)
    lengths = torch.from_numpy(lengths_np).to(device)
    if fe.preemphasis is not None:
        timemask = torch.arange(x.shape[1], device=device)[None, :] < lengths[:, None]
        x = torch.cat([x[:, :1], x[:, 1:] - fe.preemphasis * x[:, :-1]], dim=1).masked_fill(~timemask, 0.0)
    feats = fe._torch_extract_fbank_features(x, device)
    flen = torch.floor_divide(lengths + fe.n_fft // 2 * 2 - fe.n_fft, fe.hop_length)
    mask = torch.arange(feats.shape[1], device=device)[None, :] < flen[:, None]
    m = mask.unsqueeze(-1)
    masked = feats * m
    mean = (masked.sum(dim=1) / flen.unsqueeze(-1)).unsqueeze(1)
    var = (((masked - mean) ** 2) * m).sum(dim=1) / (flen - 1).clamp(min=1).unsqueeze(-1)
    feats = (feats - mean) / (var.sqrt().unsqueeze(1) + EPSILON) * m
    return feats, mask


_PUNCT = set(".,?!:;'\"%/-¡¿…")


def decode_words(sequences, durations, pieces: list[str], skip: set[int], frame_s: float, offsets_s: list[float]) -> list[list[Word]]:
    """Words with times from TDT output: each step's frame is the sum of the durations before it.

    Same result as ParakeetProcessor.decode(..., durations=...) but much faster (that one decodes
    token by token with a streaming detokenizer).
    """
    seq = sequences.cpu().numpy()
    dur = durations.cpu().numpy()
    starts = np.cumsum(dur, axis=1) - dur
    result = []
    for b in range(seq.shape[0]):
        off = offsets_s[b]
        words: list[Word] = []
        for i in range(seq.shape[1]):
            tid = int(seq[b, i])
            if tid in skip:
                continue
            piece = pieces[tid]
            start = off + float(starts[b, i]) * frame_s
            end = off + float(starts[b, i] + dur[b, i]) * frame_s
            if piece.startswith("▁") or not words:
                text = piece.lstrip("▁")
                if text or not words:
                    words.append(Word(text, start, end))
                continue
            w = words[-1]
            w.text += piece
            if not all(c in _PUNCT for c in piece):  # punctuation takes no time of its own
                w.end = max(w.end, end)
        result.append([w for w in words if w.text])
    return result


class ParakeetEngine(Engine):
    max_chunk_s = 30.0

    def __init__(self, model_id: str, name: str, device: str, repo: str = "nvidia/parakeet-tdt-0.6b-v3"):
        super().__init__(model_id, name, device)
        self.repo = repo
        self.model = None
        self.processor = None

    @property
    def loaded(self) -> bool:
        return self.model is not None

    def load(self) -> None:
        if self.model is not None:
            return
        import torch
        from transformers import AutoProcessor, ParakeetForTDT

        quiet_transformers()
        self.dtype = torch.bfloat16 if self.device == "cuda" else torch.float32
        self.processor = AutoProcessor.from_pretrained(self.repo)
        tok = self.processor.tokenizer
        self.pieces = tok.convert_ids_to_tokens(list(range(len(tok))))
        self.skip = {self.processor.blank_token_id, tok.pad_token_id, *tok.all_special_ids}
        self.skip |= {i for i, p in enumerate(self.pieces) if p.startswith("<") and p.endswith(">")}
        self.model = ParakeetForTDT.from_pretrained(self.repo, dtype=self.dtype).to(self.device).eval()
        fe = self.processor.feature_extractor
        subsampling = getattr(getattr(self.model.config, "encoder_config", None), "subsampling_factor", 8)
        self.frame_s = fe.hop_length / fe.sampling_rate * subsampling  # 80 ms per encoder frame

    def unload(self) -> None:
        self.model = None
        self.processor = None
        gc.collect()
        if self.device == "cuda":
            import torch

            torch.cuda.empty_cache()

    def recognize(self, chunks: list[np.ndarray], offsets_s: list[float], progress: Progress, cancelled: IsCancelled) -> list[list[Word]]:
        import torch

        self.load()
        lengths = [len(c) for c in chunks]
        if self.device == "cuda":
            batches = batches_by_length(lengths, max_items=48, max_total=48 * 30 * SR)
        else:
            batches = batches_by_length(lengths, max_items=4, max_total=4 * 30 * SR)
        total = max(sum(lengths), 1)
        done = 0
        results: list[list[Word]] = [[] for _ in chunks]
        splitter = OomSplitter()

        def run(sub: list[int]) -> list[list[Word]]:
            with torch.inference_mode(), warnings.catch_warnings():
                # (The transducer stops at the end of the audio; generate's max_length notice doesn't apply.)
                warnings.filterwarnings("ignore", message=".*max_length.*")
                # Spectrograms on the GPU too: on the CPU they cost more than the model run.
                feats, mask = features(self.processor.feature_extractor, [chunks[i] for i in sub], self.device)
                out = self.model.generate(input_features=feats.to(self.dtype), attention_mask=mask, return_dict_in_generate=True)
            return decode_words(out.sequences, out.durations, self.pieces, self.skip, self.frame_s, [offsets_s[i] for i in sub])

        for batch in batches:
            if cancelled():
                raise Cancelled()
            for i, words in zip(batch, splitter.run(batch, run)):
                results[i] = words
            done += sum(lengths[i] for i in batch)
            progress(done / total)
        return results
