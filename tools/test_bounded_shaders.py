"""Dispatch coverage must include shared GLSL bodies and inline frame indices."""
from pathlib import Path
import tempfile
import unittest

from bounded_shaders import WHOLE_KERNELS, bounded_source, expanded_source, write_variant


ROOT = Path(__file__).resolve().parents[1]
SHADERS = ROOT.parent / "SpektraLab-Windows/engine/src/shaders/vulkan"
ANDROID_SHADERS = ROOT / "app/src/main/cpp/shaders/vulkan"
EXPRESSION = ("gl_GlobalInvocationID.y * (gl_NumWorkGroups.x * "
              "gl_WorkGroupSize.x) + gl_GlobalInvocationID.x")


class BoundedShaderTests(unittest.TestCase):
    def test_every_frame_kernel_has_a_variant(self):
        shaders = {p.name: p for p in SHADERS.glob("*.comp")}
        shaders.update({p.name: p for p in ANDROID_SHADERS.glob("*.comp")})
        self.assertGreaterEqual(len(shaders), 47)
        with tempfile.TemporaryDirectory() as folder:
            for shader in shaders.values():
                with self.subTest(kernel=shader.stem):
                    variant = write_variant(shader, Path(folder), (SHADERS,))
                    if shader.stem in WHOLE_KERNELS:
                        self.assertIsNone(variant)
                        continue
                    self.assertIsNotNone(variant)
                    source = expanded_source(shader, (SHADERS,))
                    generated = variant.read_text(encoding="utf-8")
                    # After undoing only the index substitution/declaration,
                    # every material/colour/geometry expression is unchanged.
                    declaration = "layout(push_constant) uniform TheiaDispatch { uint base; } theia_dispatch;\n"
                    restored = generated.replace(declaration, "")
                    from bounded_shaders import GLOBAL_INDEX
                    normalized = GLOBAL_INDEX.sub("THEIA_INDEX", source)
                    self.assertEqual(restored.replace("(theia_dispatch.base + gl_GlobalInvocationID.x)", "THEIA_INDEX"), normalized)

    def test_includes_and_inline_indices_are_not_silently_skipped(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root / "body.glsl").write_text(f"void main() {{ uint i = {EXPRESSION}; }}\n")
            shader = root / "test.comp"
            shader.write_text('#version 450\n#extension GL_GOOGLE_include_directive : require\n#include "body.glsl"\n')
            result = write_variant(shader, root / "out")
            self.assertIsNotNone(result)
            generated = result.read_text()
            self.assertNotIn("gl_GlobalInvocationID.y", generated)
            self.assertLess(generated.index("#extension"), generated.index("layout(push_constant)"))

    def test_unknown_shape_dependency_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "additional dispatch-shape"):
            bounded_source(f"#version 450\nvoid main() {{ uint i={EXPRESSION}; uint y=gl_GlobalInvocationID.y; }}")

    def test_include_cycles_and_missing_files_are_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            shader = Path(folder) / "loop.comp"
            shader.write_text('#include "loop.comp"\n')
            with self.assertRaisesRegex(ValueError, "Cyclic"):
                expanded_source(shader)
            shader.write_text('#include "missing.glsl"\n')
            with self.assertRaisesRegex(ValueError, "Missing"):
                expanded_source(shader)


if __name__ == "__main__":
    unittest.main()
