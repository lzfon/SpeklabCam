"""Compare explicit PC and Android RGBA16 captures without an 8-bit screenshot oracle.

Requires NumPy. Input captures are local test evidence, never application assets.
The PC metadata must come from the reference binary and bind its source SHA-256.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

import numpy as np


def digest(path: Path) -> str:
    result = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            result.update(block)
    return result.hexdigest()


def compare(pc_dir: Path, android_dir: Path, source: Path) -> dict:
    reference = json.loads((pc_dir / "pc-reference.json").read_text(encoding="utf-8"))
    candidate = json.loads((android_dir / "pc-parity-capture.json").read_text(encoding="utf-8"))
    source_hash = digest(source)
    if reference["source_sha256"] != source_hash:
        raise ValueError("RAW source differs from the PC reference")
    results = []
    for pc_name, android_name in (
        ("pc-direct-srgb", "android-full-srgb"),
        ("pc-direct-prophoto", "android-full-prophoto"),
    ):
        expected = reference[pc_name]["result"]["image"]
        actual = next(row for row in candidate if row["name"] == android_name)
        width, height = expected["width"], expected["height"]
        if (actual["width"], actual["height"]) != (width, height):
            raise ValueError(f"{android_name}: full dimensions changed")
        old_path, new_path = pc_dir / f"{pc_name}.rgba16", android_dir / f"{android_name}.rgba16"
        if any(path.stat().st_size != width * height * 8 for path in (old_path, new_path)):
            raise ValueError("Invalid tightly packed RGBA16 capture size")
        old_hash = digest(old_path)
        if old_hash != reference[pc_name]["sha256"]:
            raise ValueError("PC reference pixels changed")
        old = np.memmap(old_path, dtype="<u2", mode="r", shape=(height, width, 4))
        new = np.memmap(new_path, dtype="<u2", mode="r", shape=(height, width, 4))
        maximum, total, changed = 0, 0, 0
        for y in range(0, height, 32):
            a, b = old[y : y + 32], new[y : y + 32]
            if not np.all(a[:, :, 3] == 65535) or not np.all(b[:, :, 3] == 65535):
                raise ValueError("Native RGB result lost opaque alpha")
            error = np.abs(a[:, :, :3].astype(np.int32) - b[:, :, :3].astype(np.int32))
            maximum = max(maximum, int(error.max()))
            total += int(error.sum())
            changed += int(np.count_nonzero(error))
        count = width * height * 3
        # Apply the upstream 3e-5 cross-GPU bar to normalized U16 values.
        # Two code values exceed it; never round the tolerance up to fit a result.
        results.append(dict(
            reference=pc_name, candidate=android_name, width=width, height=height,
            channels_compared=count, max_u16=maximum, mean_u16=total / count,
            differing_channels=changed, passed=maximum / 65535.0 <= 3e-5,
            reference_sha256=old_hash, candidate_sha256=digest(new_path),
        ))
    return dict(source_sha256=source_hash, cases=results,
                passed=all(row["passed"] for row in results),
                scope="Full-size deterministic native film/paper; grain and glare disabled. Not a UI/post-grade parity verdict.")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pc-dir", type=Path, required=True)
    parser.add_argument("--android-dir", type=Path, required=True)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    report = compare(args.pc_dir, args.android_dir, args.source)
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2))
    if not report["passed"]:
        raise SystemExit("Independent PC/Android native pixel parity failed")


if __name__ == "__main__":
    main()
