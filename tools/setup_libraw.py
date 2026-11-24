"""Fetch and verify the Android-only pinned LibRaw source; never overwrite a modified tree."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import tempfile
import urllib.request
from zipfile import ZipFile


ROOT = Path(__file__).resolve().parents[1]
LOCK = json.loads((Path(__file__).with_name("libraw.lock.json")).read_text(encoding="utf-8"))


def digest(path: Path) -> str:
    result = hashlib.sha256()
    with path.open("rb") as source:
        while block := source.read(1024 * 1024):
            result.update(block)
    return result.hexdigest()


def prepare(dependencies: Path, archive: Path) -> Path:
    """Validate the entire archive and existing tree before extracting anything."""
    if digest(archive) != LOCK["archive_sha256"]:
        raise ValueError("LibRaw archive SHA-256 mismatch; source tree was not changed")
    source = dependencies / LOCK["directory"]
    with ZipFile(archive) as bundle:
        expected: dict[Path, str] = {}
        for entry in bundle.infolist():
            name = PurePosixPath(entry.filename)
            if name.is_absolute() or ".." in name.parts or "\\" in entry.filename:
                raise ValueError("Unsafe LibRaw archive path")
            target = (dependencies / entry.filename).resolve()
            if not target.is_relative_to(source.resolve()):
                raise ValueError("Unexpected LibRaw archive root")
            if not entry.is_dir():
                if target in expected:
                    raise ValueError("Duplicate LibRaw archive entry")
                expected[target] = hashlib.sha256(bundle.read(entry)).hexdigest()
        if source.exists():
            actual = {file.resolve() for file in source.rglob("*") if file.is_file()}
            if actual != set(expected):
                raise ValueError("Incomplete or unexpected pinned LibRaw files; existing tree retained")
            for file, checksum in expected.items():
                if digest(file) != checksum:
                    raise ValueError(f"Modified pinned LibRaw file retained: {file}")
        else:
            bundle.extractall(dependencies)
    return source


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dependencies", type=Path, default=ROOT.parent / "deps")
    parser.add_argument("--archive", type=Path, help="Use an existing archive, still SHA-256 verified")
    parser.add_argument("--proxy", help="Optional HTTP(S) proxy, e.g. http://127.0.0.1:7890")
    args = parser.parse_args()
    dependencies = args.dependencies.resolve()
    dependencies.mkdir(parents=True, exist_ok=True)
    archive = args.archive.resolve() if args.archive else dependencies / (LOCK["directory"] + ".zip")
    if not archive.is_file():
        if args.archive:
            parser.error("Specified archive does not exist")
        opener = urllib.request.build_opener(
            urllib.request.ProxyHandler({"http": args.proxy, "https": args.proxy})
        ) if args.proxy else urllib.request.build_opener()
        temporary: Path | None = None
        try:
            with tempfile.NamedTemporaryFile(dir=dependencies, suffix=".download", delete=False) as target:
                temporary = Path(target.name)
                with opener.open(LOCK["url"], timeout=60) as response:
                    while block := response.read(1024 * 1024):
                        target.write(block)
            if digest(temporary) != LOCK["archive_sha256"]:
                raise ValueError("Downloaded LibRaw archive SHA-256 mismatch")
            os.replace(temporary, archive)
        finally:
            if temporary is not None:
                temporary.unlink(missing_ok=True)
    source = prepare(dependencies, archive)
    print(f"LibRaw {LOCK['version']} ({LOCK['commit']}): {source}")
    print("Archive and every extracted source file verified; shared desktop dependency unchanged.")


if __name__ == "__main__":
    main()
