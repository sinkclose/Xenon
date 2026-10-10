# Prism frame pipeline

Enable **Settings → Blur and Liquid Glass → Prism rendering pipeline** (Android 13+).
The switch persists, defaults to off and takes effect on the next drawable draw.

Prism now changes both frame preparation and the GPU refraction program. The
previous implementation only retained display lists and ran identical GPU work.
It therefore could not be expected to improve a GPU-bound scrolling workload.

The original AGSL resources remain the reference/off path. Prism selects
`prism_glass_shader.agsl` or `prism_glass_shader_advanced.agsl`, with the same
uniforms, source resolution, Gaussian blur, seven dispersion samples, tint,
highlight program and navigation-lens material:

- Standard: bypass finite-difference normals and Snell calculations wherever
  the original XY displacement is zero (outside the edge band or zero intensity).
- Advanced: evaluate straight-edge/interior distance without a square root;
  for zero depth, normalize the corner vector once and omit the unused radial
  vector. The nonzero-depth path remains available. Reuse the central color
  for the green dispersion contribution.
- Shader selection follows the Prism switch, including explicit GlassEngine
  display-list refreshes. Compilation failure falls back to the original shader.
- For hashed source content, blur/tint-mode changes update the existing blur
  graph without recapturing the scene. Capture-resolution changes still record
  fresh content; unknown/animated content is never assumed cacheable.

`blur3/drawable/PrismGlassPipeline.java` assembles two retained display lists:
background replay and surface decoration. Scene capture and hardware Gaussian
blur remain shared. This is not a replacement of the entire capture architecture.

Each draw checks background mapping and surface style separately. Content
invalidation or a source transform re-records the background. Color, stroke or
corner changes update decoration as needed. Material parameters update the existing
RenderEffect without recording background commands. Alpha changes only the
RenderNode property. Size changes and switching pipelines synchronize both layers.
Unknown source content invalidation is preserved, rather than assumed cacheable.
Source preparation still runs before replay, so cached backgrounds see child-node
content and blur updates. No per-frame bitmap readback or new shader compilation is
introduced.

Regression checks execute the production Java effect, compositor and drawable
methods without an Android SDK. On 120 changing frames after initial recording:

| Change | Previous background / surface recordings | Prism background / surface recordings |
| --- | --- | --- |
| Alpha | 121 / 121 | 1 / 1 |
| Tint color | 121 / 121 | 1 / 121 |
| Source transform | 121 / 121 | 121 / 1 |
| Source content invalidation | 121 / 121 | 121 / 1 |
| Refraction material | 121 / 121 | 1 / 1 |
| Surface size | 121 / 121 | 121 / 121 |

These counts measure Java display-list work, not GPU frame time.

`test_glass_prism_math.py` executes arithmetic translated directly from all four
production shader sources as C++ floats. Across 10,311,948 pixel comparisons
(asymmetric/oversized corners, multiple sizes, materials, dispersion and depth),
channel differences stay below 0.0002. In that CPU arithmetic harness, square-root
calls fall from 47,550,174 to 26,125,230; source evaluations fall from 27,873,884 to
25,365,036. These are source-level operation counts, not compiled GPU instruction
counts or frame-time measurements: a driver may already eliminate duplicate work.
The harness does not emulate AGSL half precision or compile shaders on Android.

No FPS gain or pixel-identical device rendering is claimed without device testing.
Full-resolution blur/refraction render targets remain potential bottlenecks.

The arithmetic harness additionally requires a C++17 compiler.

Run `PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s tests -p 'test_glass*.py' -v`
and the `test_progressive_blur.py` suite for regression checks.
`./gradlew :TMessagesProj:compileDebugJavaWithJavac` checks Android integration.
