# Chick v2 art source

Generated with the built-in image generation tool on 2026-10-19. No runtime code changed.

`chick-v2-source.png`: 1536 × 1024 RGBA PNG, four columns × two rows, 384 × 512 cells. Read left to right, then next row:

1. Front rest, eyes open.
2. Front happy dance, wings raised and one foot lifted.
3. Left three-quarter.
4. Left profile.
5. Back.
6. Right profile.
7. Right three-quarter.
8. Curled asleep.

Visual inspection: all eight poses present, consistent yellow plumage, cream belly, orange feet/beak, warm brown outlines and peach cheeks. Silhouettes stay inside cells. Standing feet are approximately aligned, but final runtime exports should normalize pivots; this is a source atlas, not a bundled runtime texture. It contains only two front expressions, not a complete dance cycle.

Transparency validation: `sips -g hasAlpha` reports yes. Read-only Pillow inspection found alpha extrema 0–254, 883,222 fully transparent pixels and 620,860 pixels with alpha >=250. Sampled outer corners and cell gutters have alpha=0. The RGB channels contain brown/gold beneath transparent pixels; viewers that ignore alpha display this hidden color. Preserve alpha during export. Interior is almost opaque rather than exactly 255, so verify on light/dark keyboard backgrounds before shipping. No programmatic background removal performed.

## Generation prompt

Use case: stylized-concept. Create a production game companion sprite atlas: exactly FOUR equal columns and TWO equal rows, canvas 1536x1024. Exactly EIGHT depictions of ONE identical adorable yellow baby chick animal. Genuine transparent alpha background, entirely empty outside chick silhouettes; do not paint a checkerboard, ground shadow, backdrop, labels or borders. Cute premium kawaii anime mobile mascot: round fluffy yellow head and plump tiny body, three soft feather tufts, small orange beak and feet, glossy dark brown eyes with one white highlight, peach cheek patches, cream belly, small wings. Clean warm brown outline with simple flat pastel cel shading. No detailed fur, tiny accessories or gradients. Readable at 44pt keyboard toolbar size. Consistent identity, dimensions and scale across all eight cells. Each cell 384x512; chick centered horizontally with ample blank transparent padding, chick about 240px high, feet share same baseline in each row. Row1 left to right: front neutral/rest eyes open; front joyful dance eyes closed smiling wings raised one foot lifted; left three-quarter view; full left side profile. Row2 left to right: full rear view with tail tuft; full right side profile; right three-quarter view; asleep curled into a soft rounded oval with eyes shut, beak tucked to chest. All bodies fully inside own cell; no touching cell edges, no overlapping. Chick character only, absolutely no human.

Original generated file: `/Users/ptrinh/.codex/generated_images/01a15438-9d4d-7cd1-b962-4c469fc07298/exec-9631dac1-65c2-4f19-8e37-e978c905c66e.png`.
