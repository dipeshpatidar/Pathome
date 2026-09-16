from __future__ import annotations

import argparse
import base64
import hashlib
import hmac
import json
import os
import random
from pathlib import Path
from typing import Any

from pathome_nlp.dataset_io import (
    align_token_labels,
    ensure_disjoint,
    entity_counts,
    label_names,
    load_jsonl,
)

MIN_TRAIN_EXAMPLES = 500
MIN_VALIDATION_EXAMPLES = 100
MIN_TRAIN_EXAMPLES_PER_FIELD = 25
MIN_VALIDATION_EXAMPLES_PER_FIELD = 10
SEED = 42


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


def require_dataset_size(train: list[dict[str, Any]], validation: list[dict[str, Any]]) -> None:
    if len(train) < MIN_TRAIN_EXAMPLES:
        raise ValueError(f"Need at least {MIN_TRAIN_EXAMPLES} approved training examples; found {len(train)}")
    if len(validation) < MIN_VALIDATION_EXAMPLES:
        raise ValueError(
            f"Need at least {MIN_VALIDATION_EXAMPLES} approved validation examples; found {len(validation)}"
        )

    train_counts = entity_counts(train)
    validation_counts = entity_counts(validation)
    for field in sorted(set(train_counts).union(validation_counts)):
        if train_counts[field] < MIN_TRAIN_EXAMPLES_PER_FIELD:
            raise ValueError(f"Field {field} has only {train_counts[field]} training labels")
        if validation_counts[field] < MIN_VALIDATION_EXAMPLES_PER_FIELD:
            raise ValueError(f"Field {field} has only {validation_counts[field]} validation labels")


def compute_metrics(eval_prediction: Any) -> dict[str, float]:
    import numpy as np

    logits, labels = eval_prediction
    predictions = np.argmax(logits, axis=-1)
    true_positive = false_positive = false_negative = 0
    for predicted_row, label_row in zip(predictions, labels):
        for predicted, expected in zip(predicted_row, label_row):
            if expected == -100:
                continue
            predicted_entity = predicted != 0
            expected_entity = expected != 0
            if predicted == expected and expected_entity:
                true_positive += 1
            elif predicted_entity:
                false_positive += 1
            if expected_entity and predicted != expected:
                false_negative += 1

    precision = true_positive / max(1, true_positive + false_positive)
    recall = true_positive / max(1, true_positive + false_negative)
    f1 = 2 * precision * recall / max(1e-12, precision + recall)
    hallucination_rate = false_positive / max(1, true_positive + false_positive)
    return {
        "precision": precision,
        "recall": recall,
        "f1": f1,
        "hallucination_rate": hallucination_rate,
        "false_positive_tokens": float(false_positive),
    }


def encode_records(records: list[dict[str, Any]], tokenizer: Any, label_to_id: dict[str, int]) -> list[dict[str, Any]]:
    encoded_records: list[dict[str, Any]] = []
    for record in records:
        encoded = tokenizer(
            record["text"],
            truncation=True,
            max_length=512,
            return_offsets_mapping=True,
        )
        offsets = [tuple(offset) for offset in encoded.pop("offset_mapping")]
        encoded["labels"] = align_token_labels(offsets, record["spans"], label_to_id)
        encoded_records.append(encoded)
    return encoded_records


def directory_fingerprint(path: Path) -> str:
    digest = hashlib.sha256()
    for file_path in sorted(
        item for item in path.rglob("*") if item.is_file() and item.name != "artifact.sha256"
    ):
        digest.update(str(file_path.relative_to(path)).encode("utf-8"))
        with file_path.open("rb") as handle:
            for chunk in iter(lambda: handle.read(1024 * 1024), b""):
                digest.update(chunk)
    return digest.hexdigest()


def file_fingerprint(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def dataset_fingerprint(train_path: Path, validation_path: Path) -> tuple[str, str, str]:
    train_checksum = file_fingerprint(train_path)
    validation_checksum = file_fingerprint(validation_path)
    combined = hashlib.sha256()
    combined.update(train_checksum.encode("ascii"))
    combined.update(validation_checksum.encode("ascii"))
    return train_checksum, validation_checksum, combined.hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser(description="Train the private Pathome property span extractor")
    parser.add_argument("--train", type=Path, required=True)
    parser.add_argument("--validation", type=Path, required=True)
    parser.add_argument("--base-model", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()

    if not args.base_model.is_dir():
        raise ValueError("--base-model must be a local model directory")
    release_signing_key = signing_key()

    os.environ["HF_HUB_OFFLINE"] = "1"
    os.environ["HF_DATASETS_OFFLINE"] = "1"
    os.environ["TOKENIZERS_PARALLELISM"] = "false"

    import torch
    from torch.utils.data import Dataset
    from transformers import (
        AutoModelForTokenClassification,
        AutoTokenizer,
        DataCollatorForTokenClassification,
        Trainer,
        TrainingArguments,
        set_seed,
    )

    random.seed(SEED)
    set_seed(SEED)
    torch.use_deterministic_algorithms(True, warn_only=True)

    train_records = load_jsonl(args.train, "TRAIN")
    validation_records = load_jsonl(args.validation, "VALIDATION")
    ensure_disjoint(train_records, validation_records)
    require_dataset_size(train_records, validation_records)

    labels = label_names(train_records)
    label_to_id = {label: index for index, label in enumerate(labels)}
    id_to_label = {index: label for label, index in label_to_id.items()}

    tokenizer = AutoTokenizer.from_pretrained(args.base_model, local_files_only=True, use_fast=True)
    model = AutoModelForTokenClassification.from_pretrained(
        args.base_model,
        local_files_only=True,
        num_labels=len(labels),
        id2label=id_to_label,
        label2id=label_to_id,
    )

    class EncodedDataset(Dataset):
        def __init__(self, rows: list[dict[str, Any]]) -> None:
            self.rows = rows

        def __len__(self) -> int:
            return len(self.rows)

        def __getitem__(self, index: int) -> dict[str, Any]:
            return self.rows[index]

    train_dataset = EncodedDataset(encode_records(train_records, tokenizer, label_to_id))
    validation_dataset = EncodedDataset(encode_records(validation_records, tokenizer, label_to_id))
    args.output_dir.mkdir(parents=True, exist_ok=True)

    training_arguments = TrainingArguments(
        output_dir=str(args.output_dir / "checkpoints"),
        learning_rate=2e-5,
        per_device_train_batch_size=8,
        per_device_eval_batch_size=8,
        num_train_epochs=5,
        weight_decay=0.01,
        eval_strategy="epoch",
        save_strategy="epoch",
        load_best_model_at_end=True,
        metric_for_best_model="f1",
        greater_is_better=True,
        save_total_limit=2,
        seed=SEED,
        data_seed=SEED,
        report_to=[],
        push_to_hub=False,
    )
    trainer = Trainer(
        model=model,
        args=training_arguments,
        train_dataset=train_dataset,
        eval_dataset=validation_dataset,
        processing_class=tokenizer,
        data_collator=DataCollatorForTokenClassification(tokenizer=tokenizer),
        compute_metrics=compute_metrics,
    )
    trainer.train()
    validation_metrics = trainer.evaluate()

    artifact_dir = args.output_dir / "artifact"
    trainer.save_model(artifact_dir)
    tokenizer.save_pretrained(artifact_dir)
    train_checksum, validation_checksum, corpus_checksum = dataset_fingerprint(
        args.train, args.validation
    )
    manifest_payload = {
        "seed": SEED,
        "trainExamples": len(train_records),
        "validationExamples": len(validation_records),
        "trainFieldCounts": dict(sorted(entity_counts(train_records).items())),
        "validationFieldCounts": dict(sorted(entity_counts(validation_records).items())),
        "trainDatasetSha256": train_checksum,
        "validationDatasetSha256": validation_checksum,
        "datasetFingerprint": corpus_checksum,
        "labels": labels,
        "validationMetrics": validation_metrics,
    }
    signed_payload, signature = sign_payload(manifest_payload, release_signing_key)
    manifest = {
        **manifest_payload,
        "signatureAlgorithm": "HMAC-SHA256",
        "signedPayload": signed_payload,
        "signature": signature,
    }
    (artifact_dir / "training_manifest.json").write_text(
        json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    checksum = directory_fingerprint(artifact_dir)
    (artifact_dir / "artifact.sha256").write_text(checksum + "\n", encoding="utf-8")
    print(
        json.dumps(
            {
                "artifact": str(artifact_dir),
                "sha256": checksum,
                "datasetFingerprint": corpus_checksum,
            },
            indent=2,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
