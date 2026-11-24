"""Capture a deterministic native-film reference from an explicit PC portable build.

This starts its own protocol host, never the running editor. Parameters are the
PC engine's flat wire JSON; grain_active and glare_active must explicitly be false.
No app sidecars, source photographs or existing capture files are overwritten.
"""
from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
from pathlib import Path
import struct
import subprocess


def sha(path: Path) -> str:
    result = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            result.update(block)
    return result.hexdigest()


class Host:
    def __init__(self, executable: Path, resources: Path, log: Path):
        self.log = log.open("xb")
        self.process = subprocess.Popen(
            [str(executable), "--resources", str(resources)], stdin=subprocess.PIPE,
            stdout=subprocess.PIPE, stderr=self.log,
            creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
        )
        self.reader = ThreadPoolExecutor(max_workers=1)
        self.sequence = 0

    def exact(self, size: int) -> bytes:
        chunks = bytearray()
        while len(chunks) < size:
            block = self.process.stdout.read(size - len(chunks))
            if not block:
                raise RuntimeError("PC host closed its output; inspect its log")
            chunks.extend(block)
        return bytes(chunks)

    def reply(self, sequence: int) -> tuple[dict, bytes]:
        while True:
            header_size, payload_size = struct.unpack("<II", self.exact(8))
            if header_size > 64 * 1024 * 1024 or payload_size > 1024 * 1024 * 1024:
                raise ValueError("PC protocol frame exceeds the audit budget")
            header = json.loads(self.exact(header_size))
            payload = self.exact(payload_size)
            if header.get("id") != sequence:
                continue
            if not header.get("ok"):
                raise RuntimeError(header.get("error"))
            return header["result"], payload

    def call(self, method: str, **params) -> tuple[dict, bytes]:
        self.sequence += 1
        header = json.dumps(dict(id=self.sequence, method=method, params=params)).encode("utf-8")
        self.process.stdin.write(struct.pack("<II", len(header), 0) + header)
        self.process.stdin.flush()
        try:
            return self.reader.submit(self.reply, self.sequence).result(timeout=180)
        except BaseException:
            self.process.kill()
            raise

    def close(self) -> None:
        try:
            if self.process.poll() is None:
                self.call("shutdown")
        finally:
            self.process.stdin.close()
            try:
                self.process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait(timeout=10)
            self.reader.shutdown(wait=True)
            self.log.close()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pc-root", type=Path, required=True)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--params", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    params = json.loads(args.params.read_text(encoding="utf-8"))
    if params.get("grain_active") is not False or params.get("glare_active") is not False:
        parser.error("Explicitly disable stochastic grain and glare in the comparison parameters")
    executable = (args.pc_root / "spektralab-host.exe").resolve(strict=True)
    resources = (args.pc_root / "host/engine").resolve(strict=True)
    source = args.source.resolve(strict=True)
    args.output.mkdir(parents=True, exist_ok=False)
    report = dict(source_sha256=sha(source), host_sha256=sha(executable), request=params,
                  scope="Deterministic native film/paper; not arbitrary UI/post-grade equality")
    manifest = args.pc_root / "BUILD-MANIFEST.json"
    if manifest.is_file():
        report["build_manifest"] = json.loads(manifest.read_text(encoding="utf-8"))
    host = Host(executable, resources, args.output / "pc-host.log")
    try:
        report["hello"], _ = host.call("hello")
        report["open"], _ = host.call("open", path=str(source), params=dict(params, output_color_space="sRGB"),
                                      decode=dict(white_balance=dict(mode="as_shot"), lens_correction=False))
        session = report["open"]["session"]
        for name, space in (("pc-direct-srgb", "sRGB"), ("pc-direct-prophoto", "ProPhoto RGB")):
            host.call("set_params", session=session, delta=dict(output_color_space=space))
            result, payload = host.call("render", session=session, tier="full", format="rgba16")
            path = args.output / f"{name}.rgba16"
            with path.open("xb") as stream:
                stream.write(payload)
            report[name] = dict(result=result, sha256=sha(path))
            (args.output / "pc-reference.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
        print(json.dumps(dict(output=str(args.output), source_sha256=report["source_sha256"],
                              native_color_spaces=2), indent=2))
    finally:
        host.close()


if __name__ == "__main__":
    main()
