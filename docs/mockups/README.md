# UI mockups

HTML mockups of the app's screens, as starting points for designing new UI.

- `kit.css`: colours from `Theme.kt`, sizes from the composables, at 1 CSS px = 1 dp. Each
  component class names the composable it copies.
- `screens.html`: every screen, built from the kit with the `just seed` sample data. Open it in a
  browser.

## Making a mockup

1. Make a new HTML file next to `screens.html` (or in your scratchpad) that links `kit.css`.
2. Copy the `.phone` blocks of the screens you're changing from `screens.html`, then change them.
3. Before you build on a screen, check it against the app: read its composable, or take a
   screenshot on the emulator (see "Check UI changes on the emulator" in `AGENTS.md`). If the
   stored screen has drifted, fix it in `screens.html` too.
4. To show a flow, put the phones in a `.flow` row with `<span class="arrow">→</span>` between
   them, a `.step-cap` caption above each, and `.tap` on the element the user taps.
5. Show several variants side by side when the design is still open.

Mockups of new designs are throwaway; don't commit them unless asked. Do commit fixes to
`screens.html` and `kit.css`, and update them when a change to the app lands.
