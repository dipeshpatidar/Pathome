from __future__ import annotations

import argparse
import base64
import hashlib
import hmac
import json
import os
from pathlib import Path
from typing import Any

from pathome_nlp.dataset_io import align_token_labels, load_jsonl
from train_token_classifier import compute_metrics

MIN_HOLDOUT_EXAMPLES = 100
MIN_HOLDOUT_EXAMPLES_PER_FIELD = 5
MIN_OVERALL_F1 = 0.95
MIN_PER_FIELD_F1 = 0.90
MIN_PRECISION = 0.98


def signing_key() -> bytes:
    value = os.environ.get("PATHOME_MODEL_SIGNING_KEY", "").encode("utf-8")
    if len(value) < 32:
        raise ValueError("PATHOME_MODEL_SIGNING_KEY must contain at least 32 bytes")
    return value


def sign_payload(payload: dict[str, Any], key: bytes) -> tuple[str, str]:
    canonical = json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    encoded = base64.urlsafe_b64encode(canonical.encode("utf-8")).decode("ascii").rstrip("=")
    signature = hmac.new(key, encoded.encode("ascii"), hashlib.sha256).hexdigest()
    return encoded, signature


def file_fingerprint(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def entity_spans(label_ids: list[int], id_to_label: dict[int, str]) -> set[tuple[str, int, int]]:
    spans: set[tuple[str, int, int]] = set()
    current_field: str | None = None
    current_start = -1

    for index, label_id in enumerate(label_ids + [0]):
        label = id_to_label.get(int(label_id), "O") if label_id != -100 else "O"
        prefix, separator, field = label.partition("-")
        continues = separator and prefix == "I" and field == current_field
        if current_field is not None and not continues:
            spans.add((current_field, current_start, index))
            current_field = None
            current_start = -1
        if separator and prefix == "B":
            current_field = field
            current_start = index
        elif separator and prefix == "I" and current_field is None:
            current_field = field
            current_start = index
    return spans


def strict_entity_metrics(
    predicted_rows: list[list[int]],
    expected_rows: list[list[int]],
    id_to_label: dict[int, str],
) -> dict[str, Any]:
    true_positive = false_positive = false_negative = 0
    by_field: dict[str, dict[str, int]] = {}

    for predicted_row, expected_row in zip(predicted_rows, expected_rows):
        filtered_predictions = [
            int(predicted) for predicted, expected in zip(predicted_row, expected_row) if expected != -100
        ]
        filtered_expected = [int(expected) for expected in expected_row if expected != -100]
        predicted_spans = entity_spans(filtered_predictions, id_to_label)
        expected_spans = entity_spans(filtered_expected, id_to_label)
        matched = predicted_spans.intersection(expected_spans)
        unexpected = predicted_spans.difference(expected_spans)
        missing = expected_spans.difference(predicted_spans)
        true_positive += len(matched)
        false_positive += len(unexpected)
        false_negative += len(missing)
        for field, _start, _end in matched:
            by_field.setdefault(field, {"truePositive": 0, "falsePositive": 0, "falseNegative": 0})[
                "truePositive"
            ] += 1
        for field, _start, _end in unexpected:
            by_field.setdefault(field, {"truePositive": 0, "falsePositive": 0, "falseNegative": 0})[
                "falsePositive"
            ] += 1
        for field, _start, _end in missing:
            by_field.setdefault(field, {"truePositive": 0, "falsePositive": 0, "falseNegative": 0})[
                "falseNegative"
            ] += 1

    precision = true_positive / max(1, true_positive + false_positive)
    recall = true_positive / max(1, true_positive + false_negative)
    f1 = 2 * precision * recall / max(1e-12, precision + recall)
    per_field: dict[str, dict[str, float | int]] = {}
    for field, counts in sorted(by_field.items()):
        field_precision = counts["truePositive"] / max(
            1, counts["truePositive"] + counts["falsePositive"]
        )
        field_recall = counts["truePositive"] / max(
            1, counts["truePositive"] + counts["falseNegative"]
        )
        field_f1 = 2 * field_precision * field_recall / max(
            1e-12, field_precision + field_recall
        )
        per_field[field] = {
            **counts,
            "precision": field_precision,
            "recall": field_recall,
            "f1": field_f1,
        }

    minimum_field_f1 = min((metrics["f1"] for metrics in per_field.values()), default=0.0)
    return {
        "precision": precision,
        "recall": recall,
        "f1": f1,
        "minimum_field_f1": minimum_field_f1,
        "hallucination_rate": false_positive / max(1, true_positive + false_positive),
        "false_positive_entities": false_positive,
        "false_negative_entities": false_negative,
        "perField": per_field,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Evaluate a local candidate once on the sealed holdout set")
    parser.add_argument("--holdout", type=Path, required=True)
    parser.add_argument("--model", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    release_signing_key = signing_key()

    os.environ["HF_HUB_OFFLINE"] = "1"
    os.environ["HF_DATASETS_OFFLINE"] = "1"
    os.environ["TOKENIZERS_PARALLELISM"] = "false"

    import numpy as np
    import torch
    from transformers import AutoModelForTokenClassification, AutoTokenizer

    records = load_jsonl(args.holdout, "HOLDOUT")
    if len(records) < MIN_HOLDOUT_EXAMPLES:
        raise ValueError(f"Need at least {MIN_HOLDOUT_EXAMPLES} sealed holdout examples; found {len(records)}")

    artifact_checksum_path = args.model / "artifact.sha256"
    if not artifact_checksum_path.is_file():
        raise ValueError("The candidate model is missing artifact.sha256")
    artifact_checksum = artifact_checksum_path.read_text(encoding="utf-8").strip().lower()
    if len(artifact_checksum) != 64 or any(character not in "0123456789abcdef" for character in artifact_checksum):
        raise ValueError("The candidate model checksum is invalid")

    tokenizer = AutoTokenizer.from_pretrained(args.model, local_files_only=True, use_fast=True)
    model = AutoModelForTokenClassification.from_pretrained(args.model, local_files_only=True)
    model.eval()
    id_to_label = {int(key): value for key, value in model.config.id2label.items()}
    label_to_id = {value: key for key, value in id_to_label.items()}

    logits_rows: list[Any] = []
    labels_rows: list[Any] = []
    with torch.inference_mode():
        for record in records:
            encoded = tokenizer(
                record["text"], truncation=True, max_length=512, return_offsets_mapping=True, return_tensors="pt"
            )
            offsets = [tuple(item) for item in encoded.pop("offset_mapping")[0].tolist()]
            labels = align_token_labels(offsets, record["spans"], label_to_id)
            output = model(**encoded)
            logits_rows.append(output.logits[0].cpu().numpy())
            labels_rows.append(np.asarray(labels))

    max_length = max(len(row) for row in labels_rows)
    logits = np.zeros((len(logits_rows), max_length, len(id_to_label)), dtype=np.float32)
    labels = np.full((len(labels_rows), max_length), -100, dtype=np.int64)
    for index, (logit_row, label_row) in enumerate(zip(logits_rows, labels_rows)):
        logits[index, : len(logit_row)] = logit_row
        labels[index, : len(label_row)] = label_row

    predictions = np.argmax(logits, axis=-1)
    metrics = strict_entity_metrics(predictions.tolist(), labels.tolist(), id_to_label)
    token_metrics = compute_metrics((logits, labels))
    metrics["false_positive_tokens"] = int(token_metrics["false_positive_tokens"])
    metrics["tokenMetrics"] = token_metrics
    model_fields = sorted({
        label.partition("-")[2]
        for label in id_to_label.values()
        if label.startswith("B-")
    })
    holdout_field_counts = {
        field: int(
            metrics["perField"].get(field, {}).get("truePositive", 0)
            + metrics["perField"].get(field, {}).get("falseNegative", 0)
        )
        for field in model_fields
    }
    underrepresented_fields = [
        field
        for field, count in holdout_field_counts.items()
        if count < MIN_HOLDOUT_EXAMPLES_PER_FIELD
    ]
    passed = (
        metrics["f1"] >= MIN_OVERALL_F1
        and metrics["minimum_field_f1"] >= MIN_PER_FIELD_F1
        and metrics["precision"] >= MIN_PRECISION
        and metrics["false_positive_tokens"] == 0
        and metrics["hallucination_rate"] == 0
        and not underrepresented_fields
    )
    report_payload = {
        "artifactChecksum": artifact_checksum,
        "holdoutDatasetSha256": file_fingerprint(args.holdout),
        "holdoutExamples": len(records),
        "holdoutFieldCounts": holdout_field_counts,
        "fieldCoveragePassed": not underrepresented_fields,
        "underrepresentedFields": underrepresented_fields,
        "metrics": metrics,
        "gates": {
            "minimumOverallF1": MIN_OVERALL_F1,
            "minimumPerFieldF1": MIN_PER_FIELD_F1,
            "minimumPrecision": MIN_PRECISION,
            "zeroFalsePositiveTokens": True,
            "minimumExamplesPerField": MIN_HOLDOUT_EXAMPLES_PER_FIELD,
        },
        "passed": passed,
    }
    signed_payload, signature = sign_payload(report_payload, release_signing_key)
    report = {
        **report_payload,
        "signatureAlgorithm": "HMAC-SHA256",
        "signedPayload": signed_payload,
        "signature": signature,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2))
    return 0 if passed else 1


if __name__ == "__main__":
    raise SystemExit(main())
