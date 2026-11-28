"""Local retrieval over the tariff and service knowledge base using TF-IDF (no external services)."""

from __future__ import annotations

import re
from dataclasses import dataclass
from pathlib import Path

import numpy as np
from sklearn.feature_extraction.text import TfidfVectorizer

KNOWLEDGE = Path(__file__).resolve().parent.parent / "knowledge"


@dataclass(frozen=True)
class Passage:
    doc_id: str
    title: str
    text: str


class RagStore:
    def __init__(self, directory: Path = KNOWLEDGE) -> None:
        self.passages: list[Passage] = []
        for path in sorted(directory.glob("*.md")):
            content = path.read_text(encoding="utf-8")
            title_match = re.search(r"^#\s+(.+)$", content, re.MULTILINE)
            title = title_match.group(1).strip() if title_match else path.stem
            for block in re.split(r"\n\s*\n", content):
                block = block.strip()
                if block and not block.startswith("#"):
                    self.passages.append(Passage(path.stem, title, " ".join(block.split())))
        self.vectorizer = TfidfVectorizer(stop_words="english", ngram_range=(1, 2), sublinear_tf=True)
        self.matrix = self.vectorizer.fit_transform([f"{p.title} {p.text}" for p in self.passages])

    def search(self, query: str, limit: int = 2, threshold: float = 0.08) -> list[tuple[Passage, float]]:
        scores = (self.matrix @ self.vectorizer.transform([query]).T).toarray().ravel()
        order = np.argsort(scores)[::-1][:limit]
        return [(self.passages[i], float(scores[i])) for i in order if scores[i] >= threshold]
