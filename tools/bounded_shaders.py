"""Generate exact global-index variants; original Windows shaders stay untouched."""
import re
from pathlib import Path


INCLUDE = re.compile(r'^\s*#include\s+"([^"\r\n]+)"\s*$', re.MULTILINE)
GLOBAL_INDEX = re.compile(
    r"gl_GlobalInvocationID\.y\s*\*\s*\(\s*gl_NumWorkGroups\.x\s*\*\s*"
    r"gl_WorkGroupSize\.x\s*\)\s*\+\s*gl_GlobalInvocationID\.x"
)
WHOLE_KERNELS = frozenset({"spk_reduce_max", "spk_split_probe"})


def expanded_source(shader: Path, include_dirs: tuple[Path, ...] = (),
                    stack: tuple[Path, ...] = ()) -> str:
    """Expand quoted GLSL includes before inspecting the dispatch contract.

    Several complete-frame kernels put main/indexing in a shared .glsl body.
    Inspecting only their four-line .comp wrapper silently missed those kernels.
    """
    shader = shader.resolve()
    if shader in stack:
        raise ValueError(f"Cyclic shader include: {shader}")

    def include(match: re.Match) -> str:
        target = next((base / match[1] for base in (shader.parent, *include_dirs)
                       if (base / match[1]).is_file()), None)
        if target is None:
            raise ValueError(f"Missing shader include {match[1]} in {shader}")
        return expanded_source(target, include_dirs, (*stack, shader))

    return INCLUDE.sub(include, shader.read_text(encoding="utf-8"))


def bounded_source(source: str) -> str | None:
    matches = list(GLOBAL_INDEX.finditer(source))
    if not matches:
        return None  # Probes and kernels with a different indexing contract remain whole.
    if "push_constant" in source:
        raise ValueError("Ambiguous shader indexing/push-constant contract")
    remainder = GLOBAL_INDEX.sub("THEIA_INDEX", source)
    if "gl_GlobalInvocationID" in remainder or "gl_NumWorkGroups" in remainder:
        raise ValueError("Kernel has an additional dispatch-shape dependency")
    # Keep #version first. The directive is global, even for kernels whose
    # canonical index is inline in main or in multiple conditional bodies.
    version = re.search(r"^[ \t]*#version[^\n]*\n", source, re.MULTILINE)
    if version is None:
        raise ValueError("Missing shader version")
    transformed = GLOBAL_INDEX.sub("(theia_dispatch.base + gl_GlobalInvocationID.x)", source)
    extensions = re.match(r"(?:\s*#extension[^\n]*\n)*", transformed[version.end():])
    at = version.end() + extensions.end()
    return (transformed[:at] +
            "layout(push_constant) uniform TheiaDispatch { uint base; } theia_dispatch;\n" +
            transformed[at:])


def write_variant(shader: Path, directory: Path,
                  include_dirs: tuple[Path, ...] = ()) -> Path | None:
    if shader.stem in WHOLE_KERNELS:
        return None
    value = bounded_source(expanded_source(shader, include_dirs))
    if value is None:
        return None
    directory.mkdir(parents=True, exist_ok=True)
    output = directory / shader.name
    output.write_text(value, encoding="utf-8", newline="\n")
    return output
