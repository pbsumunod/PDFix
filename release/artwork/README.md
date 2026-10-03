# Store artwork

- `play-icon-512.png`: 512 x 512, RGBA PNG, full square. Google Play applies the outer mask and shadow.
- `feature-graphic-1024x500.png`: 1024 x 500, RGB PNG, no transparency.
- Matching SVG files are editable vector sources. Typography uses available Georgia/DejaVu fonts; regenerate on the same environment for identical lettering.

Rebuild from the project root with `python scripts/artwork.py` (requires Pillow). No AI-generated imagery, remote resources, or browser is used. Artwork follows the app's cream, sage, and dark-green document/checkmark identity.

These are marketing graphics, not app screenshots. Capture listing screenshots from the actual final app separately.
