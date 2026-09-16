# Private property extraction model

This directory contains the local-only training pipeline for Pathome's property field extractor. It never sends prompts, labels, owner details, or model artifacts to an external API.

## Safety model

- Only examples explicitly approved through the parser-learning curation API are exported.
- The database assigns deterministic `TRAIN`, `VALIDATION`, and sealed `HOLDOUT` partitions before approval.
- Training uses only `TRAIN`; checkpoint selection uses only `VALIDATION`; the candidate is tested once against `HOLDOUT`.
- Training is refused below 500 training and 100 validation examples, or when any field lacks minimum coverage. Release evaluation requires 100 holdout examples, coverage for every model field, exact-boundary F1 and precision gates, and zero unsupported predictions.
- Unsupported manual additions, personal fields, overlapping spans, duplicates, conflicting truth, and invalid values are excluded before export.
- The trainer accepts only a local pretrained-model directory and passes `local_files_only=True` when loading it.
- Every candidate records the exact training/validation dataset fingerprint; registration rejects mismatched manifests, modified artifacts, or holdout reports produced for another artifact.
- A candidate is not automatically connected to the production parser. It must pass the sealed holdout gate and later run in shadow mode behind the deterministic parser.

## Local setup

Create an isolated Python environment and install `requirements.txt`. Download the chosen multilingual base model once on an approved machine, scan it, and copy the complete model directory into private infrastructure. Training and evaluation then run with `HF_HUB_OFFLINE=1` and do not require internet access.

Export approved examples from the locally running backend:

```bash
PATHOME_ADMIN_TOKEN="<temporary-admin-token>" \
python export_dataset.py --base-url http://127.0.0.1:8080 --output-dir data/approved
```

Train only after the minimum data and per-field coverage gates are met:

```bash
PATHOME_MODEL_SIGNING_KEY="<private-release-signing-key>" \
python train_token_classifier.py \
  --train data/approved/train.jsonl \
  --validation data/approved/validation.jsonl \
  --base-model models/base/multilingual-token-classifier \
  --output-dir models/candidates/property-extractor-v1
```

Run the sealed holdout evaluation once for a release candidate:

```bash
PATHOME_MODEL_SIGNING_KEY="<private-release-signing-key>" \
python evaluate_candidate.py \
  --holdout data/approved/holdout.jsonl \
  --model models/candidates/property-extractor-v1/artifact \
  --output models/candidates/property-extractor-v1/holdout-metrics.json
```

Register the resulting relative artifact path and the `datasetFingerprint` printed by the trainer through the admin-only model registry API. Configure `pathome.parser.model-directory` to the absolute private candidate-model directory in each deployment. Registration permits only files below that directory.

The backend must receive the same signing key through `PATHOME_MODEL_SIGNING_KEY`. Keep it outside the repository and available only to the controlled release job and model registry. Unsigned or modified manifests and holdout reports are rejected.

The current deterministic parser remains active until a separately reviewed integration enables a passing artifact in shadow mode.
