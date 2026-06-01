# Bevy HTML Support (RustRover Plugin)

This plugin improves Bevy and `bevy_extended_ui` development inside RustRover.

## What It Adds

- HTML support for `bevy_extended_ui` templates:
  - completion
  - documentation
  - inspections and formatter integration
- `Beu Component` action in the `New` context menu for folders.
- `Bevy` entry in the New Project wizard.

## Bevy Project Wizard

The wizard can configure:

- Bevy version (from `bevyengine/bevy` tags)
- local Rust version display (`rustc --version`)
- Rust edition
- optional `bevy_extended_ui` dependency:
  - version selection (`main (git)` supported)
  - feature selection
- Bevy assets (multi-select checkbox list from `https://bevy.org/assets/`)

When `bevy_extended_ui` with `extended-framework`/`extended_framework` is selected, the wizard also generates:

- `assets/index.html`
- `assets/components/main.component.rs`
- `assets/components/main.component.html`
- `assets/components/main.component.css`
- registry marker file in `src/` and `mod` include in `src/main.rs`

## Links

- Bevy: https://github.com/bevyengine/bevy
- bevy_extended_ui: https://github.com/exepta/bevy_extended_ui
- Bevy assets: https://bevy.org/assets/
