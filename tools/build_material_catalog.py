"""Derive the small Android material directory from the exact packaged profiles."""
import argparse
import json
import re
from pathlib import Path


def build(profiles: Path) -> dict:
    sources = {p.stem: json.loads(p.read_text(encoding="utf-8")) for p in sorted(profiles.glob("*.json"))}
    if not sources:
        raise ValueError("Sync engine resources before building the material catalog")
    materials = []
    for key, source in sources.items():
        base = source.get("derived_from")
        info = source["info"]
        if base is not None:
            if not isinstance(base, str) or not re.fullmatch(r"[a-zA-Z0-9_-]{1,96}", base):
                raise ValueError("Invalid material alias")
            original = sources[base]
            if "derived_from" in original:
                raise ValueError("Nested material aliases are unsupported")
            info = {**original["info"], **info, "stock": key}
        stock = info["stock"]
        if info.get("stage") not in ("filming", "printing"):
            continue
        if info.get("type") not in ("positive", "negative"):
            raise ValueError(f"Invalid material type: {stock}")
        materials.append({
            "id": stock, "name": info.get("name", stock), "stage": info["stage"], "type": info["type"],
            "target_print": info.get("target_print"), "monochrome": info.get("channel_model") == "bw",
            "antihalation": info.get("antihalation", ""), "derived_from": base,
        })
    if len({m["id"] for m in materials}) != len(materials):
        raise ValueError("Duplicate material identity")
    return {"schema": 1, "materials": materials}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--profiles", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    data = build(args.profiles)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(data, ensure_ascii=False, separators=(",", ":")) + "\n", encoding="utf-8")
    print(f"Generated {len(data['materials'])} materials: {args.output.stat().st_size} bytes")


if __name__ == "__main__":
    main()
