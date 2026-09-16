from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any

from pathome_nlp.dataset_io import validate_record

PARTITIONS = ("TRAIN", "VALIDATION", "HOLDOUT")


def fetch_page(base_url: str, token: str, partition: str, page: int, size: int) -> dict[str, Any]:
    query = urllib.parse.urlencode({"partition": partition, "page": page, "size": size})
    request = urllib.request.Request(
        f"{base_url.rstrip('/')}/api/v1/parser-learning/dataset?{query}",
        headers={"Authorization": f"Bearer {token}", "Accept": "application/json"},
    )
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.load(response)


def export_partition(base_url: str, token: str, output_dir: Path, partition: str) -> dict[str, Any]:
    destination = output_dir / f"{partition.lower()}.jsonl"
    temporary = destination.with_suffix(".jsonl.tmp")
    digest = hashlib.sha256()
    count = 0
    seen: set[str] = set()
    page = 0

    with temporary.open("w", encoding="utf-8") as handle:
        while True:
            payload = fetch_page(base_url, token, partition, page, 200)
            content = payload.get("content", [])
            for record in content:
                validate_record(record, partition)
                example_id = record["exampleId"]
                if example_id in seen:
                    raise ValueError(f"Duplicate example {example_id} in {partition}")
                seen.add(example_id)
                line = json.dumps(record, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
                handle.write(line + "\n")
                digest.update((line + "\n").encode("utf-8"))
                count += 1
            if payload.get("last", True):
                break
            page += 1

    os.replace(temporary, destination)
    return {"partition": partition, "examples": count, "sha256": digest.hexdigest()}


def main() -> int:
    parser = argparse.ArgumentParser(description="Export curator-approved parser data from the local backend")
    parser.add_argument("--base-url", default="http://127.0.0.1:8080")
    parser.add_argument("--output-dir", type=Path, default=Path("data/approved"))
    args = parser.parse_args()

    token = os.environ.get("PATHOME_ADMIN_TOKEN")
    if not token:
        print("PATHOME_ADMIN_TOKEN is required", file=sys.stderr)
        return 2

    args.output_dir.mkdir(parents=True, exist_ok=True)
    manifest = [export_partition(args.base_url, token, args.output_dir, name) for name in PARTITIONS]
    manifest_path = args.output_dir / "manifest.json"
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(f"Exported approved data to {args.output_dir}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
