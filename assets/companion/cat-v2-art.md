# Cat companion v2 source art

Generated with the built-in `image_gen` tool on 2026-10-19; no CLI fallback or background removal. Source: `cat-v2-source.png` (1774 × 887 RGBA PNG).

Direction: apricot tabby kitten, cream muzzle/tummy/paws, cocoa eyes, pink inner ears, simple warm cel shading and a large expressive face that survives small keyboard display sizes. Same orange/cream identity as the original vector cat, with anime mascot proportions.

Grid is four columns × two rows, ordered left to right:

1. Front rest (sitting).
2. Front dance, happy closed eyes and lifted paw/foot.
3. Left three-quarter.
4. Left profile.
5. Back.
6. Right profile.
7. Right three-quarter.
8. Curled asleep.

Validation: `sips -g hasAlpha` reports `yes`. Pixel inspection finds alpha extrema `(0, 255)` and 840,300 fully transparent pixels. The background is genuinely transparent, not a painted checkerboard. All eight subjects are present in the requested order.

Integration caution: the generated size has fractional nominal cells (443.5 px). The silhouettes approach the top and bottom of cells, particularly the dancing cat's top ear; requested 15% margins were not achieved. Normalize each cell independently into padded integer-sized runtime tiles before consumption, preserving relative cat scale and aligning resting feet/sleeping body to the same baseline. No runtime asset or code is changed by this source deliverable.

A targeted built-in edit was attempted to add padding and request 2048 × 1024 output. It returned 1774 × 887 RGB with an opaque painted checkerboard (`sips hasAlpha: no`), so that candidate was rejected. The original true-alpha file above remains the selected deliverable. No programmatic background removal was performed.

## Generation prompt

Use case: stylized-concept. Production sprite atlas for VietTelex keyboard companion. Draw ONE consistent adorable kawaii animal CAT, not a human: warm apricot-orange kitten with cream muzzle, cream tummy and little cream paws, three simple darker forehead stripes, huge luminous cocoa eyes, small pink nose, subtle rosy cheeks and softly rounded triangular ears. Japanese anime mascot, beautiful clean dark-brown outlines, flat pastel color blocks with only one simple cel-shadow tone, exceptionally clear silhouette readable at 44pt. Elegant polished expressive art, no fuzzy texture. Layout: exactly FOUR columns by TWO rows, eight equally sized square cells, landscape canvas aspect ratio 2:1. Every pose centered within its own invisible cell, consistent body scale, all features and tail safely inside cell with at least 15% margin. Row1 left to right: 1 front-facing relaxed standing kitten, 2 front-facing happy dancing kitten with paws raised and one foot lifted, 3 same kitten turned left three-quarter, 4 same kitten exact left profile. Row2 left to right: 5 same kitten full back view, 6 exact right profile, 7 right three-quarter, 8 kitten curled up asleep with tail wrapped around body and closed eyes. The standing kitten has a large head and petite rounded body; every standing pose same ear-top and baseline, sleeping pose sits on that baseline. NO other characters, NO clothing, NO labels, NO grid lines, NO text, NO ground, NO cast shadow, NO scenery. Essential file requirement: actual transparent PNG alpha background, fully transparent pixels outside the kitten; never paint a checkerboard pattern or solid white background. This is an asset atlas, not a mockup of an atlas.
