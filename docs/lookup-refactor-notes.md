# Lookup Refactor Notes

## Keep

- Keep the current dictionary header behavior:
  - dictionary header appears below frequency/pitch badges
  - tapping a dictionary header collapses only that dictionary's definitions
  - this behavior exists in:
    - dictionary page
    - main lookup popup
    - reader lookup popup

## Desired recursive lookup behavior

- First lookup in reader should stay like the original 9Player lookup:
  - same size
  - same buttons
  - same general position under/near subtitle
- Recursive lookups should open on top of the first lookup:
  - overlap
  - move upward a little
  - remain opaque
  - keep full lookup UI, not a reduced summary card

## What went wrong in the discarded experiment

- We changed the first reader lookup too much.
- We moved the first layer into a new global popup host instead of preserving the old feel.
- We shrank and faded layers, which the user explicitly did not want.
- We replaced full child layers with reduced cards, so buttons/audio/Anki behavior no longer matched the first layer.
- We also changed dictionary page lookup architecture too aggressively at the same time, which made debugging harder.

## What to avoid next time

- Do not redesign the first reader lookup.
- Do not remove or shrink the existing buttons on recursive child layers.
- Do not make child layers semi-transparent unless explicitly requested.
- Do not change dictionary page behavior in the same pass as reader popup behavior.
- Do not replace full lookup cards with compressed summaries.
- Do not move top-level reader lookup to a global top-of-screen popup host.

## Safer implementation plan

1. Preserve current reader first-layer UI exactly.
2. Add a second visual layer that reuses the same full lookup card component.
3. Only adjust child-layer position, not first-layer layout.
4. Keep dictionary page separate until reader nested lookup feels correct.
5. After reader nested lookup is correct, evaluate whether dictionary page should share any components.

## Validation checklist

- First reader lookup looks the same as before.
- Second lookup clearly appears as a new page on top, not as the same page moving.
- Child layers keep audio and Anki buttons.
- Child layers are not smaller.
- Child layers are opaque.
- Dictionary headers still collapse per dictionary only.
