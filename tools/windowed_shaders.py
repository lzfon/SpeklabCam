"""Generate descriptor-relative addressing without changing image arithmetic.

Window origins are uint32 element offsets, not pixel offsets. Absolute invocation
indices still seed noise and calculate geometry; only storage addresses relocate.
"""
import re
from pathlib import Path

from bounded_shaders import WHOLE_KERNELS, bounded_source, expanded_source


BLOCK = re.compile(
    r"layout\s*\(([^)]*)\)\s*(?:(?:readonly|writeonly)\s+)?buffer\s+\w+\s*"
    r"\{\s*(?:float|uint)\s+(\w+)\s*\[\s*\]\s*;\s*\}\s*(\w+)?\s*;"
)


def relative_accesses(source: str, token: str, binding: int) -> str:
    pattern = re.compile(r"(?<![\w.])" + re.escape(token) + r"\s*\[")
    result = []
    start = 0
    for match in pattern.finditer(source):
        if match.start() < start:
            continue  # nested access was handled with its enclosing expression
        depth = 1
        end = match.end()
        while end < len(source) and depth:
            depth += (source[end] == "[") - (source[end] == "]")
            end += 1
        if depth:
            raise ValueError(f"Unbalanced storage address: {token}")
        expression = source[match.end():end - 1]
        if not expression.strip():
            continue  # unsized array declaration
        expression = relative_accesses(expression, token, binding)
        result.extend((source[start:match.end()],
                       f"uint({expression}) - theia_dispatch.origin[{binding}]", "]"))
        start = end
    result.append(source[start:])
    return "".join(result)


def windowed_source(shader: Path, include_dirs: tuple[Path, ...]) -> str | None:
    if shader.stem in WHOLE_KERNELS:
        return None
    original = expanded_source(shader, include_dirs)
    source = bounded_source(original)
    if source is None:
        raise ValueError(f"Unreviewed window kernel: {shader.name}")
    blocks = []
    for match in BLOCK.finditer(original):
        binding = re.search(r"\bbinding\s*=\s*(\d+)", match[1])
        if binding is None or int(binding[1]) >= 16:
            raise ValueError(f"Unreviewed storage binding: {shader.name}")
        token = f"{match[3]}.{match[2]}" if match[3] else match[2]
        blocks.append((token, int(binding[1])))
    if len(blocks) != len(re.findall(r"\bbuffer\s+\w+\s*\{", original)):
        raise ValueError(f"Unreviewed storage element type: {shader.name}")
    if not blocks:
        raise ValueError(f"No storage contract: {shader.name}")
    source = source.replace("uint base; } theia_dispatch;",
                            "uint base; uint end; uint origin[16]; } theia_dispatch;")
    for token, binding in blocks:
        source = relative_accesses(source, token, binding)
    # A band's last workgroup may have padding threads even before frame end.
    # Reject them before touching any descriptor, including atomic statistics.
    source, count = re.subn(r"void\s+main\s*\(\s*\)\s*\{", lambda m: m[0] +
        "\n    if (theia_dispatch.base + gl_GlobalInvocationID.x >= theia_dispatch.end) return;\n", source)
    if not count:
        raise ValueError(f"Missing main: {shader.name}")
    return source


def write_windowed(shader: Path, directory: Path, include_dirs: tuple[Path, ...]) -> Path | None:
    source = windowed_source(shader, include_dirs)
    if source is None:
        return None
    directory.mkdir(parents=True, exist_ok=True)
    result = directory / shader.name
    result.write_text(source, encoding="utf-8", newline="\n")
    return result
