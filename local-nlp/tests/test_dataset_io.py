from __future__ import annotations

import unittest

from pathome_nlp.dataset_io import align_token_labels, ensure_disjoint, validate_record
from evaluate_candidate import strict_entity_metrics


class DatasetIoTest(unittest.TestCase):
    def test_validates_exact_non_overlapping_source_spans(self) -> None:
        record = {
            "exampleId": "one",
            "datasetPartition": "TRAIN",
            "text": "2 BHK rent 18000",
            "spans": [
                {"field": "bhk", "start": 0, "end": 5, "text": "2 BHK"},
                {"field": "rentAmount", "start": 11, "end": 16, "text": "18000"},
            ],
        }
        validate_record(record, "TRAIN")

    def test_rejects_unmasked_phone_numbers(self) -> None:
        record = {
            "exampleId": "one",
            "datasetPartition": "TRAIN",
            "text": "owner 9876543210 rent 18000",
            "spans": [{"field": "rentAmount", "start": 22, "end": 27, "text": "18000"}],
        }
        with self.assertRaisesRegex(ValueError, "unmasked phone"):
            validate_record(record, "TRAIN")

    def test_rejects_invalid_phone_like_sequences_too(self) -> None:
        record = {
            "exampleId": "one",
            "datasetPartition": "TRAIN",
            "text": "owner number +91 123 456 9810 rent 18000",
            "spans": [{"field": "rentAmount", "start": 35, "end": 40, "text": "18000"}],
        }
        with self.assertRaisesRegex(ValueError, "unmasked phone"):
            validate_record(record, "TRAIN")

    def test_rejects_unmasked_email_addresses(self) -> None:
        record = {
            "exampleId": "one",
            "datasetPartition": "TRAIN",
            "text": "contact owner@example.com rent 18000",
            "spans": [{"field": "rentAmount", "start": 31, "end": 36, "text": "18000"}],
        }
        with self.assertRaisesRegex(ValueError, "unmasked email"):
            validate_record(record, "TRAIN")

    def test_rejects_partition_leakage(self) -> None:
        with self.assertRaisesRegex(ValueError, "leakage"):
            ensure_disjoint([{"exampleId": "one"}], [{"exampleId": "one"}])

    def test_aligns_bio_labels_to_token_offsets(self) -> None:
        labels = align_token_labels(
            [(0, 0), (0, 1), (2, 5), (6, 10)],
            [{"field": "bhk", "start": 0, "end": 5}],
            {"O": 0, "B-bhk": 1, "I-bhk": 2},
        )
        self.assertEqual([-100, 1, 2, 0], labels)

    def test_holdout_metrics_require_exact_field_boundaries(self) -> None:
        metrics = strict_entity_metrics(
            [[1, 0, 0]],
            [[1, 2, 0]],
            {0: "O", 1: "B-rentAmount", 2: "I-rentAmount"},
        )

        self.assertEqual(0.0, metrics["f1"])
        self.assertEqual(1, metrics["false_positive_entities"])
        self.assertEqual(1, metrics["false_negative_entities"])

    def test_holdout_metrics_report_each_fields_exact_score(self) -> None:
        metrics = strict_entity_metrics(
            [[1, 2, 0, 3]],
            [[1, 2, 0, 3]],
            {0: "O", 1: "B-rentAmount", 2: "I-rentAmount", 3: "B-bhk"},
        )

        self.assertEqual(1.0, metrics["f1"])
        self.assertEqual(1.0, metrics["minimum_field_f1"])
        self.assertEqual({"bhk", "rentAmount"}, set(metrics["perField"]))


if __name__ == "__main__":
    unittest.main()
