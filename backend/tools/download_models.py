#!/usr/bin/env python3
"""Download Member B depth weights (run once on the Mac).

    python tools/download_models.py
"""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from server.config import DEPTH_MODEL_ID, ROOT

MODEL_DIR = ROOT / "models"
COREML_REPO = "apple/coreml-depth-anything-v2-small"
COREML_PACKAGE = "DepthAnythingV2SmallF16.mlpackage"


def main() -> None:
    from huggingface_hub import snapshot_download

    MODEL_DIR.mkdir(parents=True, exist_ok=True)
    print(f"1/2 metric indoor  {DEPTH_MODEL_ID}")
    snapshot_download(repo_id=DEPTH_MODEL_ID)
    print("    cached in HF_HOME")
    print(f"2/2 CoreML fallback {COREML_REPO}")
    snapshot_download(
        repo_id=COREML_REPO,
        local_dir=str(MODEL_DIR),
        allow_patterns=[f"{COREML_PACKAGE}/*"],
    )
    print("    ", MODEL_DIR / COREML_PACKAGE)
    print("done. Start the server WITHOUT RAAHI_MOCK=1")


if __name__ == "__main__":
    main()
