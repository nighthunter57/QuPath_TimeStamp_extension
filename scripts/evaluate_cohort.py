"""Evaluate checked human-recording transcripts without loading a model or uploading data."""
import argparse
import json
from pathlib import Path

from scripts.score_transcript import load_concepts, score


def evaluate(manifest_path):
    manifest_path = Path(manifest_path)
    document = json.loads(manifest_path.read_text(encoding="utf-8"))
    if document.get("version") != 1 or not document.get("recordings"):
        raise ValueError("A version 1 manifest with recordings is required")
    rows = []
    identifiers = set()
    for item in document["recordings"]:
        if item.get("source") != "human" or item.get("consent_confirmed") is not True or item.get("reference_checked") is not True:
            raise ValueError("Each human recording needs consent and a manually checked reference")
        identifier = item["id"]
        if not isinstance(identifier, str) or not identifier or identifier in identifiers:
            raise ValueError("Recording IDs must be nonempty and unique")
        identifiers.add(identifier)
        def path(key):
            return manifest_path.parent / item[key]
        if not path("audio").is_file():
            raise ValueError(f"Original audio missing for {identifier}")
        reference = path("reference").read_text(encoding="utf-8")
        if not reference.strip():
            raise ValueError(f"Reference is empty for {identifier}")
        concepts = load_concepts(path("concepts")) if item.get("concepts") else []
        metrics = score(reference, path("hypothesis").read_text(encoding="utf-8"), concepts)
        # Do not include transcript text or clinical terms in the summary.
        metrics["medical_concepts"].pop("missing_items", None)
        rows.append({"id": identifier, "metrics": metrics})
    totals = {}
    for metric in ("raw", "domain_normalized"):
        errors = sum(row["metrics"][metric]["errors"] for row in rows)
        words = sum(row["metrics"][metric]["words"] for row in rows)
        totals[metric] = {"errors": errors, "words": words, "wer": 100 * errors / max(1, words)}
    missing = sum(row["metrics"]["medical_concepts"]["missing"] for row in rows)
    expected = sum(row["metrics"]["medical_concepts"]["expected"] for row in rows)
    totals["medical_concepts"] = {"missing": missing, "expected": expected,
                                  "error_rate": 100 * missing / expected if expected else None}
    return {"version": 1, "recordings": rows, "totals": totals,
            "limitations": "Consent and reference checks are operator attestations. This report does not measure latency, speaker attribution, or confidence calibration."}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path)
    args = parser.parse_args()
    print(json.dumps(evaluate(args.manifest), indent=2))
