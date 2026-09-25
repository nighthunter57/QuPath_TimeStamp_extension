#!/usr/bin/env python3
"""Prepare only the standard recorder's models; reuse complete local snapshots."""

import argparse
from pathlib import Path
from typing import Callable

MODEL_REPOSITORIES = (
    ("live", "Systran/faster-whisper-small.en"),
    ("final", "Systran/faster-whisper-large-v3"),
)
MODEL_FILES = ("config.json", "model.bin", "tokenizer.json")
DOWNLOAD_PATTERNS = (*MODEL_FILES, "preprocessor_config.json", "vocabulary.*")


def model_files_present(directory: Path, repository: str) -> bool:
    required = list(MODEL_FILES)
    if repository.endswith("large-v3"):
        required.append("preprocessor_config.json")
    vocabulary = list(directory.glob("vocabulary.*"))
    return bool(vocabulary) and all(
        path.is_file() and path.stat().st_size > 0
        for path in [*(directory / name for name in required), *vocabulary]
    )


def prepare_model(repository: str, download: Callable, offline: bool = False) -> Path:
    try:
        cached = Path(download(repository, local_files_only=True, allow_patterns=DOWNLOAD_PATTERNS))
        if model_files_present(cached, repository):
            print("Already downloaded — reusing local model files.", flush=True)
            return cached
    except (OSError, ValueError):
        # Missing cache is normal on a new workstation.
        pass
    if offline:
        raise RuntimeError(f"Model files are missing or incomplete: {repository}")
    print("Downloading missing model files. This step can be left unattended.", flush=True)
    directory = Path(download(repository, allow_patterns=DOWNLOAD_PATTERNS))
    if not model_files_present(directory, repository):
        raise RuntimeError(f"Model download is incomplete: {repository}")
    return directory


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--offline", action="store_true", help="Check cached files without any downloads")
    args = parser.parse_args()
    from huggingface_hub import snapshot_download

    for label, repository in MODEL_REPOSITORIES:
        print(f"Preparing {label} transcription model...", flush=True)
        prepare_model(repository, snapshot_download, offline=args.offline)
    print("Both transcription models are available locally.", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
