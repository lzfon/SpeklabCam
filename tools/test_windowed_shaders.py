"""Regression checks for descriptor-local addresses and absolute stochastic identity."""
from pathlib import Path
import re
import unittest

from bounded_shaders import WHOLE_KERNELS
from windowed_shaders import BLOCK, relative_accesses, windowed_source


ROOT = Path(__file__).resolve().parents[1]
SHADERS = ROOT.parent / "SpektraLab-Windows/engine/src/shaders/vulkan"


class WindowedShaderTests(unittest.TestCase):
    def test_nested_indices_and_writable_lvalues(self):
        self.assertEqual(relative_accesses("a[a[i+1u]] = a[j];", "a", 2),
                         "a[uint(a[uint(i+1u) - theia_dispatch.origin[2]]) - theia_dispatch.origin[2]] = a[uint(j) - theia_dispatch.origin[2]];")
        self.assertEqual(relative_accesses("float a[]; aa[i];", "a", 1), "float a[]; aa[i];")

    def test_every_noncollective_kernel_has_a_window_contract(self):
        shaders = {p.name: p for p in SHADERS.glob("*.comp")}
        shaders.update({p.name: p for p in (ROOT / "app/src/main/cpp/shaders/vulkan").glob("*.comp")})
        contracts = (ROOT / "app/src/main/cpp/windows/window_plan.hpp").read_text(encoding="utf-8")
        for shader in shaders.values():
            with self.subTest(kernel=shader.stem):
                source = windowed_source(shader, (SHADERS,))
                if shader.stem in WHOLE_KERNELS:
                    self.assertIsNone(source)
                    continue
                self.assertIn(f'"{shader.stem}"', contracts)
                self.assertIn("uint origin[16]", source)
                self.assertNotIn("gl_GlobalInvocationID.y", source)
                self.assertIn("theia_dispatch.end) return", source)
                self.assertTrue(list(BLOCK.finditer(source)))
                # RNG still receives the absolute index. Relocating only buffer
                # indices must never repeat stochastic grain at band boundaries.
                if shader.stem.startswith("spk_grain_"):
                    self.assertIn("rng_init(i,", source)
                for match in re.finditer(r"theia_dispatch.origin\[(\d+)\]", source):
                    self.assertLess(int(match[1]), 16)


if __name__ == "__main__":
    unittest.main()
