from __future__ import annotations

import json
import re
from collections import Counter
from pathlib import Path
from typing import Any, Iterable

PHONE_PATTERN = re.compile(r"(?<!\d)\+?(?:\d[\s-]?){8,13}(?!\d)")
EMAIL_PATTERN = re.compile(r"\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b", re.IGNORECASE)
ALLOWED_PARTITIONS = {"TRAIN", "VALIDATION", "HOLDOUT"}


def validate_record(record: dict[str, Any], expected_partition: str | None = None) -> None:
    example_id = record.get("exampleId")
    text = record.get("text")
    partition = record.get("datasetPartition")
    spans = record.get("spans")

    if not isinstance(example_id, str) or not example_id:
        raise ValueError("Every record must have an exampleId")
    if not isinstance(text, str) or not text.strip():
        raise ValueError(f"{example_id}: text is missing")
    if partition not in ALLOWED_PARTITIONS:
        raise ValueError(f"{example_id}: invalid dataset partition {partition!r}")
    if expected_partition and partition != expected_partition:
        raise ValueError(f"{example_id}: expected {expected_partition}, found {partition}")
    if PHONE_PATTERN.search(text):
        raise ValueError(f"{example_id}: an unmasked phone number reached the training export")
    if EMAIL_PATTERN.search(text):
        raise ValueError(f"{example_id}: an unmasked email address reached the training export")
    if not isinstance(spans, list) or not spans:
        raise ValueError(f"{example_id}: no approved source spans")

    previous_end = -1
    for span in sorted(spans, key=lambda item: (item.get("start", -1), item.get("end", -1))):
        field = span.get("field")
        start = span.get("start")
        end = span.get("end")
        source_text = span.get("text")
        if not isinstance(field, str) or not field:
            raise ValueError(f"{example_id}: span field is missing")
        if not isinstance(start, int) or not isinstance(end, int) or start < 0 or end <= start:
            raise ValueError(f"{example_id}: invalid span offsets for {field}")
        if end > len(text):
            raise ValueError(f"{example_id}: span for {field} exceeds the text length")
        if start < previous_end:
            raise ValueError(f"{example_id}: overlapping spans are not allowed")
        if text[start:end] != source_text:
            raise ValueError(f"{example_id}: stored source text does not match its offsets")
        previous_end = end


def load_jsonl(path: Path, expected_partition: str | None = None) -> list[dict[str, Any]]:
    records: list[dict[str, Any]] = []
    seen_ids: set[str] = set()
    with path.open("r", encoding="utf-8") as handle:
        for line_number, line in enumerate(handle, start=1):
            if not line.strip():
                continue
            try:
                record = json.loads(line)
                validate_record(record, expected_partition)
            except (json.JSONDecodeError, ValueError) as error:
                raise ValueError(f"{path}:{line_number}: {error}") from error
            if record["exampleId"] in seen_ids:
                raise ValueError(f"{path}:{line_number}: duplicate exampleId {record['exampleId']}")
            seen_ids.add(record["exampleId"])
            records.append(record)
    return records


def ensure_disjoint(*datasets: Iterable[dict[str, Any]]) -> None:
    seen: set[str] = set()
    for dataset in datasets:
        current = {record["exampleId"] for record in dataset}
        overlap = seen.intersection(current)
        if overlap:
            raise ValueError(f"Dataset leakage detected for examples: {sorted(overlap)[:5]}")
        seen.update(current)


def entity_counts(records: Iterable[dict[str, Any]]) -> Counter[str]:
    counts: Counter[str] = Counter()
    for record in records:
        counts.update(span["field"] for span in record["spans"])
    return counts


def label_names(records: Iterable[dict[str, Any]]) -> list[str]:
    fields = sorted(entity_counts(records))
    labels = ["O"]
    for field in fields:
        labels.extend((f"B-{field}", f"I-{field}"))
    return labels


def align_token_labels(
    offsets: list[tuple[int, int]],
    spans: list[dict[str, Any]],
    label_to_id: dict[str, int],
) -> list[int]:
    labels: list[int] = []
    seen_span_indexes: set[int] = set()
    for token_start, token_end in offsets:
        if token_start == token_end:
            labels.append(-100)
            continue

        matched_index = None
        for span_index, span in enumerate(spans):
            if token_start < span["end"] and token_end > span["start"]:
                matched_index = span_index
                break

        if matched_index is None:
            labels.append(label_to_id["O"])
            continue

        span = spans[matched_index]
        prefix = "I" if matched_index in seen_span_indexes else "B"
        labels.append(label_to_id[f"{prefix}-{span['field']}"])
        seen_span_indexes.add(matched_index)

    if len(seen_span_indexes) != len(spans):
        missing = sorted(set(range(len(spans))).difference(seen_span_indexes))
        raise ValueError(f"Tokenizer truncation removed approved spans at indexes {missing}")
    return labels
