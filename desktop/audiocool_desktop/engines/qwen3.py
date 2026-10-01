"""Qwen3-ASR 1.7B with the Qwen3 forced aligner, through Hugging Face Transformers.

Qwen3-ASR is the most accurate open-weights English model on the Open ASR Leaderboard (as of
Sept 2026). It outputs punctuated, cased text but no timestamps, so a second, non-autoregressive
model (Qwen3-ForcedAligner-0.6B) places each word in the audio (80 ms resolution).
"""

from __future__ import annotations

import gc
import logging
import unicodedata

import numpy as np

from .base import SR, Cancelled, Engine, IsCancelled, OomSplitter, Progress, batches_by_length, is_oom, quiet_transformers
from .segmenter import Word

log = logging.getLogger(__name__)


def _kept(ch: str) -> bool:
    """Characters the aligner keeps (letters, digits, apostrophes), as in its word splitter."""
    return ch == "'" or unicodedata.category(ch)[0] in "LN"


def _has_cjk(text: str) -> bool:
    return any(unicodedata.east_asian_width(c) in ("W", "F") and unicodedata.category(c).startswith("L") for c in text)


def attach_times(text: str, aligned: list[dict], offset_s: float, chunk_s: float) -> list[Word]:
    """Puts the aligner's times on the original (punctuated) words.

    The aligner's words are the transcript's whitespace-separated tokens with punctuation removed,
    in order, so they pair up one-to-one with tokens that keep any letters or digits. Tokens of
    pure punctuation ride along with the previous word. If the pairing doesn't hold (e.g. CJK
    text), times are spread over the chunk by word length instead.
    """
    tokens = text.split()
    content = [t for t in tokens if any(_kept(c) for c in t)]
    if not tokens:
        return []
    if len(content) != len(aligned) or _has_cjk(text):
        return spread_words(tokens, offset_s, chunk_s)
    words: list[Word] = []
    prefix = ""
    ai = 0
    for tok in tokens:
        if not any(_kept(c) for c in tok):
            if words:
                words[-1].text += " " + tok
            else:
                prefix += tok + " "
            continue
        a = aligned[ai]
        ai += 1
        start, end = float(a["start_time"]), float(a["end_time"])
        words.append(Word(prefix + tok, offset_s + start, offset_s + max(end, start)))
        prefix = ""
    return words


def spread_words(tokens: list[str], offset_s: float, chunk_s: float) -> list[Word]:
    total = sum(len(t) + 1 for t in tokens) or 1
    words, pos = [], 0.0
    for t in tokens:
        share = (len(t) + 1) / total * chunk_s
        words.append(Word(t, offset_s + pos, offset_s + pos + share))
        pos += share
    return words


class Qwen3Engine(Engine):
    max_chunk_s = 30.0

    def __init__(
        self,
        model_id: str,
        name: str,
        device: str,
        repo: str = "Qwen/Qwen3-ASR-1.7B-hf",
        aligner_repo: str = "Qwen/Qwen3-ForcedAligner-0.6B-hf",
        language: str | None = "English",
    ):
        super().__init__(model_id, name, device)
        self.repo = repo
        self.aligner_repo = aligner_repo
        self.language = language
        self.model = None

    @property
    def loaded(self) -> bool:
        return self.model is not None

    def load(self) -> None:
        if self.model is not None:
            return
        import torch
        from transformers import AutoModelForTokenClassification, AutoProcessor, Qwen3ASRForConditionalGeneration

        quiet_transformers()
        self.dtype = torch.bfloat16 if self.device == "cuda" else torch.float32
        self.processor = AutoProcessor.from_pretrained(self.repo)
        model = Qwen3ASRForConditionalGeneration.from_pretrained(self.repo, dtype=self.dtype).to(self.device).eval()
        self.aligner_processor = AutoProcessor.from_pretrained(self.aligner_repo)
        self.aligner = AutoModelForTokenClassification.from_pretrained(self.aligner_repo, dtype=self.dtype).to(self.device).eval()
        self.model = model

    def unload(self) -> None:
        self.model = None
        self.aligner = None
        self.processor = None
        self.aligner_processor = None
        gc.collect()
        if self.device == "cuda":
            import torch

            torch.cuda.empty_cache()

    def _transcribe_batch(self, audios: list[np.ndarray]) -> list[str]:
        import torch

        langs = [self.language] * len(audios) if self.language else None
        inputs = self.processor.apply_transcription_request(audio=audios, language=langs)
        inputs = inputs.to(self.device, self.dtype)
        longest = max(len(a) for a in audios) / SR
        max_new = int(longest * 12) + 48  # fast speech is ~4 words/s, ~1.3 tokens per word
        with torch.inference_mode():
            out = self.model.generate(**inputs, max_new_tokens=max_new, do_sample=False)
        generated = out[:, inputs["input_ids"].shape[1]:]
        return [t.strip() for t in self.processor.decode(generated, return_format="transcription_only")]

    def _align_batch(self, audios: list[np.ndarray], texts: list[str]) -> list[list[dict]]:
        import torch

        inputs, word_lists = self.aligner_processor.prepare_forced_aligner_inputs(
            audio=audios, transcript=texts, language=self.language or "English"
        )
        inputs = inputs.to(self.device, self.dtype)
        with torch.inference_mode():
            logits = self.aligner(**inputs).logits
        return self.aligner_processor.decode_forced_alignment(
            logits=logits,
            input_ids=inputs["input_ids"],
            word_lists=word_lists,
            timestamp_token_id=self.aligner.config.timestamp_token_id,
        )

    def recognize(self, chunks: list[np.ndarray], offsets_s: list[float], progress: Progress, cancelled: IsCancelled) -> list[list[Word]]:
        self.load()
        lengths = [len(c) for c in chunks]
        gpu = self.device == "cuda"
        asr_batches = batches_by_length(lengths, max_items=24 if gpu else 2, max_total=(24 if gpu else 2) * 30 * SR)
        total = max(sum(lengths), 1)
        texts: list[str] = [""] * len(chunks)
        done = 0
        splitter = OomSplitter()
        # Recognition takes most of the time; alignment is a single forward pass per batch.
        for batch in asr_batches:
            if cancelled():
                raise Cancelled()
            out = splitter.run(batch, lambda sub: self._transcribe_batch([chunks[i] for i in sub]))
            for i, t in zip(batch, out):
                texts[i] = t
            done += sum(lengths[i] for i in batch)
            progress(0.85 * done / total)

        results: list[list[Word]] = [[] for _ in chunks]
        progress(0.85, "Timing the words")
        todo = [i for i, t in enumerate(texts) if any(_kept(c) for c in t)]
        align_batches = batches_by_length([lengths[i] for i in todo], max_items=24 if gpu else 2, max_total=(24 if gpu else 2) * 30 * SR)
        done = 0

        def align(sub: list[int]) -> list[list[dict]]:
            try:
                return self._align_batch([chunks[i] for i in sub], [texts[i] for i in sub])
            except Exception as e:  # keep the text even if alignment fails
                if is_oom(e):
                    raise
                log.warning("Alignment failed for %d chunks (%s); spreading times evenly", len(sub), e)
                return [[] for _ in sub]

        for batch in align_batches:
            if cancelled():
                raise Cancelled()
            idx = [todo[k] for k in batch]
            for i, st in zip(idx, splitter.run(idx, align)):
                results[i] = attach_times(texts[i], st, offsets_s[i], lengths[i] / SR)
            done += sum(lengths[i] for i in idx)
            progress(0.85 + 0.15 * done / max(sum(lengths[i] for i in todo), 1))
        return results
