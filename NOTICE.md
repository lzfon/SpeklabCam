# SpeklabCam（SpekLab相机）— attribution and license notices

## Modified version notice（修改版声明）

This repository is a **modified version** of Theia, maintained by **无限好运**
(Douyin: [无限好运](https://v.douyin.com/r0yaZLMMTU8/); fork repository:
<https://github.com/lzfon/SpeklabCam>; app display name: SpekLab相机, project name: SpeklabCam).
Per GPL-3.0-or-later §2(c): the changes are documented with dates in
[CHANGELOG-custom.md](CHANGELOG-custom.md) and [README.md](README.md); the complete
corresponding source code for every distributed APK is published in this repository
(engine resources and the prebuilt upstream `libspektralab.so` are tracked here as well,
see [COMPLIANCE.md](COMPLIANCE.md)).

Public channels (the Douyin link and the repository URL above) are the maintainer's contact
information only. They are **not additional license terms**: when you redistribute this fork
or a further modified version you may replace them with your own, provided the upstream
copyright notices, license texts and the CC BY-SA attribution requirements below remain intact.

To keep this fork installable next to upstream Theia, the Android application id was changed
from `com.spektralab.android` (upstream) to `com.spektralabcam.android`. Java/Kotlin package
names, resource ids and upstream copyright headers are left untouched.

Versioning: public releases use SemVer 2.0 in `versionName` with no suffix at all (`1.0.0`, `1.1.0`,
`1.0.1`). The upstream base is recorded on a separate line in the in-app About screen, in the Release
notes and in this file — it is deliberately **not** appended to `versionName`, because Android settings
pages show that string verbatim. `versionCode` increases monotonically starting at 1000. Internal
debug counters (v87, v88 …) never appear in tags, release titles or the app.
See [README.md](README.md#版本规则).

This fork is not affiliated with, endorsed by, or representing deepspyder or Andrea Volpato;
their copyright and license notices below remain in effect.

## Application code

Theia, formerly SpektraLab Android, is distributed under **GPL-3.0-or-later**.
See [LICENSE](LICENSE) for the GNU General Public License version 3 text.
Existing upstream copyright and license notices remain in effect.

Repository: https://github.com/deepspyder/theia

## spektrafilm

Theia is based on **spektrafilm by Andrea Volpato**.

- Canonical source: https://github.com/andreavolpato/spektrafilm
- Original spektrafilm profiles, LUTs and direct derivatives: **CC BY-SA 4.0**.
- The complete original notice, preamble and license are preserved verbatim in
  [licenses/SPEKTRAFILM_LICENSE.txt](licenses/SPEKTRAFILM_LICENSE.txt).

The license copy comes from the shared checkout's
`license/SPEKTRAFILM_LICENSE.txt`. Its existing attribution requirements and
distinction between legal terms and the author's additional requests are retained.
When distributing changed profiles or LUTs, preserve the source attribution and
identify the changes as required by their license.

The shared engine also contains separately authored product profiles under
`engine/resources_product/`. Their provenance is documented in that directory's
`NOTICE.md`; resource sync copies it into the application as
`app/src/main/assets/engine/resources/Product-profiles-NOTICE.md`.
Consult that notice and the notices shipped with each resource rather than
assuming every resource has the same origin or license.

## SpektraLab post-print colour adjustments

The version 1 vibrance and eight-band HSL mathematics in
`app/src/main/java/com/spektralab/android/core/color/ColorAdjustments.kt` and
`app/src/main/cpp/curve_grade.cpp` are adapted from SpektraLab's
GPL-3.0-or-later application code: `app/SpektraLab/Sources/Engine/Layer2Kernel.swift`
and `app/SpektraLab/Sources/Model/Adjustments.swift`, commit
`1952c63537ec576d24422f1065b12495631346a5`. Modified on 2026-11-11 for Android
with immutable versioned parameters, a Kotlin reference, bounded JNI/C++ CPU
strips, and integration with Theia's existing grading order and output.
The upstream code's GPL-3.0-or-later terms remain in effect; no film or paper
profiles were modified by this adaptation.

## Fonts, icons and native dependencies

| Component | Attribution / terms | License location |
| --- | --- | --- |
| Resource Han Rounded CN | Cyano Hao; portions by Adobe. SIL Open Font License 1.1. | [Bundled OFL and copyrights](app/src/main/assets/licenses/Resource-Han-Rounded-OFL.txt); [font documentation](docs/project/fonts.md). |
| Lucide icons | Lucide Icons and Contributors. ISC; additional notices are retained in the bundled file. | [Bundled license](app/src/main/assets/licenses/lucide-LICENSE.txt). |
| LibRaw | RAW decoding dependency; distributed by its authors under LGPL 2.1 / CDDL 1.0 dual-license terms. | Pinned Android source and notices: [LibRaw dependency notes](docs/compatibility/libraw-upgrade-202611.md); bundled `app/src/main/assets/licenses/LibRaw-*` files. |
| LLVM OpenMP runtime | NDK 27.0.12077973 runtime, statically linked for bounded RAW/lens parallelism. Apache 2.0 with LLVM exceptions; additional upstream notices retained. | [Complete NDK LLVM notices](app/src/main/assets/licenses/LLVM-NOTICE.txt). |
| volk | Arseny Kapoulkine. MIT. | `SpektraLab-Windows/engine/third_party/volk/LICENSE.md` in the shared checkout. |
| stb | Its authors' public-domain / MIT terms. | `SpektraLab-Windows/engine/third_party/stb/LICENSE`; resource sync also ships `stb-LICENSE.txt`. |
| Liberation imprint fonts | Copyright and SIL Open Font License notices supplied with the fonts. | `SpektraLab-Windows/engine/resources_product/fonts/LICENSE`; resource sync also ships `Imprint-fonts-LICENSE.txt`. |

The engine and dependency paths above describe the default sibling checkout
layout; those files are not all part of this Android repository. Preserve the
corresponding license and copyright files when distributing those components.
AndroidX and other build dependencies retain their own notices and licenses.

## Film recipes (presets/)

[presets/speklabcam-recipes-v1.json](presets/speklabcam-recipes-v1.json) contains eight colour recipes
authored by the maintainer of this fork (无限好运), each of which is a set of numeric darkroom and
grading parameters plus a short textual note. They are original to this fork — no upstream profile,
LUT or `.3fr` value is copied into them — and they are distributed under the same
**GPL-3.0-or-later** terms as the rest of the code in this repository. They are **not** bundled in
the APK: users download and import them deliberately.

The recipes select film and paper stocks by their existing identifiers (`kodak_vision3_500t`,
`kodak_2383`, `kodak_2393`, `kodak_vision3_250d`, `cinestill_800t`). The imaging data behind those
identifiers is upstream spektrafilm material and stays under CC BY-SA 4.0 with the attribution
requirements described above; importing a recipe does not change that.

Each recipe is named after a film whose overall colour mood it attempts to describe in words.
**This repository contains no stills, screenshots, posters or frames from any of those films** —
the titles are descriptive references only, and the rights to those works and to any imagery from
them belong to their respective rightsholders.

## Screenshots and names

The README uses the newly supplied device screenshots in `docs/images/*.jpg`.
Some screenshots retain the former application name, SpektraLab. They have not
been retouched to replace the branding.

Photographs, screenshots, fonts, logos and other non-code assets retain their
respective rights and applicable permissions; the application code license does
not replace their individual terms. Film, paper and company names identify the
simulated materials and belong to their respective trademark owners. They do
not imply endorsement or affiliation.
