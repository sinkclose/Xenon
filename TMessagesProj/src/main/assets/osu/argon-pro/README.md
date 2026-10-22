# osu! argon pro (2022)

Original audio and textures: team osu!, ppy/osu-resources.
https://github.com/ppy/osu-resources

Audio lookup matches ArgonProSkin: Pro overrides Argon, then the default bank.
The bundled files are unmodified Git blobs; upstream.json records their paths and
source revision. Empty slider-loop WAVs are intentional upstream resources.
The PCM24 soft-hitnormal sample is converted to PCM16 in memory by OsuWave.

Resources licence: Creative Commons Attribution-NonCommercial 4.0 International;
see resources-LICENCE.md. Procedural component source: ppy/osu (MIT), see
code-LICENCE.txt and upstream.json for the inspected source revision.

Android Canvas adaptation: OsuArgonRenderer / OsuArgonAnimation. The source skin
builds most gameplay elements from containers, circles, paths and FontAwesome
icons; there is no set of gameplay SVGs to copy. Score counter PNGs and the osu
texture primitives are included from the resource repository.

Current port includes selectable built-in identity, Pro sound banks, layered hit
circles and shrink/flash, slider bodies/ball/follow ring/ticks/reverse arrows,
follow points, cursor/trail, judgement suppression for Great/Perfect, spinner,
and the main HUD layout. This is an initial native port, not pixel parity:
framework shader glows, curved health-bar shaders, spinner shaders, exact follow
ring release/tick transforms and the HUD graph still require visual matching on
an Android device. Existing playback supports osu!standard only. Results continue
to use the existing score panel.

Visual correction pass: counters now use the upstream 0.125 glyph factor and
-2 spacing, per-character wireframes, split accuracy, original Torus Bold labels,
rounded HP shader path geometry, original early/late icons, beatmap combo colours,
and replay-time cursor expansion. Approach circles use the original texture in a
separate layer above hitobjects/judgements with DrawableHitCircle's 4-to-1 shrink,
fade-in, early-hit disappearance and 50ms fade after start time.
The normal progress bar is 10 HUD units; touching/hovering expands it to 35 over
200ms and release collapses it in 800ms. Skip uses the original full-width overlay
at 70% screen height, 60-square centre button, countdown, fade/press transforms
and UI/submit-select sample. Desktop visual fixtures run the production Canvas
methods through a Java2D adapter; they do not replace Android GPU/device checks.

Spinner correction: ArgonSpinnerDisc / RingArc / ProgressArc / Ticks now drive
split white top/bottom arcs, rounded 15% side arcs, continuous rotation-based
progress, pink fill, Canvas glow approximations, native 30x5 ticks, double centre
rings, Torus SemiBold SPM labels, staged entry and 240ms exit. Tracking and
progress smoothing are evaluated from replay time so seeking restores the view.
The remaining-time approach ring was removed; it is not part of ArgonSpinner.
Hit borders now fade to the upstream translucent vertical accent gradient;
judgement particles include the parent judgement fade and four-unit ring border.
Preview fixtures include spinner entry/progress/completion and hit fading.
These Canvas effects approximate the framework's shaders, not pixel-identical
GPU output. Android device visual verification remains pending.
