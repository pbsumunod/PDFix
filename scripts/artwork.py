"""Rebuild PDFix's code-native store artwork. Requires Pillow, no network."""
from pathlib import Path
from xml.sax.saxutils import escape
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "release" / "artwork"
OUT.mkdir(parents=True, exist_ok=True)
SCALE = 3
INK = "#263E34"
GREEN = "#33594A"
PAPER = "#F7F6F2"
SAGE = "#E2ECD8"
FOLD = "#CBDDBA"
MUTED = "#657066"


def font(size, serif=False):
    candidates = ([Path("C:/Windows/Fonts/georgia.ttf"), Path("/usr/share/fonts/truetype/dejavu/DejaVuSerif.ttf")]
                  if serif else [ROOT / "app/src/main/assets/fonts/Inter-Regular.ttf", Path("C:/Windows/Fonts/arial.ttf"), Path("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf")])
    for candidate in candidates:
        if candidate.is_file():
            return ImageFont.truetype(str(candidate), round(size * SCALE))
    return ImageFont.load_default(size=round(size * SCALE))


class Artwork:
    def __init__(self, width, height, background):
        self.width, self.height = width, height
        self.image = Image.new("RGB", (width * SCALE, height * SCALE), background)
        self.draw = ImageDraw.Draw(self.image)
        self.svg = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" viewBox="0 0 {width} {height}">',
                    '<title>PDFix</title>', f'<rect width="{width}" height="{height}" fill="{background}"/>']

    def rect(self, x, y, width, height, color, radius=0):
        box = tuple(round(v * SCALE) for v in (x, y, x + width, y + height))
        self.draw.rounded_rectangle(box, radius=round(radius * SCALE), fill=color)
        self.svg.append(f'<rect x="{x}" y="{y}" width="{width}" height="{height}" rx="{radius}" fill="{color}"/>')

    def polygon(self, points, color):
        self.draw.polygon([(round(x * SCALE), round(y * SCALE)) for x, y in points], fill=color)
        coords = " ".join(f"{x},{y}" for x, y in points)
        self.svg.append(f'<polygon points="{coords}" fill="{color}"/>')

    def line(self, points, color, width):
        scaled = [(round(x * SCALE), round(y * SCALE)) for x, y in points]
        self.draw.line(scaled, fill=color, width=round(width * SCALE), joint="curve")
        radius = width * SCALE / 2
        for x, y in scaled:
            self.draw.ellipse((round(x - radius), round(y - radius), round(x + radius), round(y + radius)), fill=color)
        coords = " ".join(f"{x},{y}" for x, y in points)
        self.svg.append(f'<polyline points="{coords}" fill="none" stroke="{color}" stroke-width="{width}" stroke-linecap="round" stroke-linejoin="round"/>')

    def text(self, x, y, value, size, color=INK, serif=False):
        self.draw.text((round(x * SCALE), round(y * SCALE)), value, fill=color, font=font(size, serif), anchor="lt")
        family = "Georgia, DejaVu Serif, serif" if serif else "DejaVu Sans, Arial, sans-serif"
        self.svg.append(f'<text x="{x}" y="{y + size * .78}" font-family="{family}" font-size="{size}" fill="{color}">{escape(value)}</text>')

    def paper(self, x, y, width, height, fill=PAPER, fold=FOLD):
        corner = width * .24
        self.polygon([(x, y), (x + width - corner, y), (x + width, y + corner),
                      (x + width, y + height), (x, y + height)], fill)
        self.polygon([(x + width - corner, y), (x + width - corner, y + corner), (x + width, y + corner)], fold)

    def save(self, basename, rgba=False):
        image = self.image.resize((self.width, self.height), Image.Resampling.LANCZOS)
        if rgba:
            image = image.convert("RGBA")
        image.save(OUT / f"{basename}.png", optimize=True)
        (OUT / f"{basename}.svg").write_text("\n".join(self.svg + ["</svg>"]) + "\n", encoding="utf-8")
        print(f"{basename}: {image.size[0]} x {image.size[1]}, {image.mode}, {(OUT / f'{basename}.png').stat().st_size:,} bytes")


icon = Artwork(512, 512, GREEN)
icon.paper(137, 94, 238, 324)
icon.line([(183, 205), (310, 205)], GREEN, 18)
icon.line([(183, 256), (283, 256)], GREEN, 18)
icon.line([(193, 332), (234, 373), (316, 291)], GREEN, 21)
icon.save("play-icon-512", rgba=True)

feature = Artwork(1024, 500, PAPER)
feature.rect(624, 0, 400, 500, SAGE)
feature.rect(80, 71, 35, 5, GREEN, 2)
feature.text(80, 106, "PDFix", 24, GREEN)
feature.text(75, 165, "Paperwork,", 60, serif=True)
feature.text(75, 240, "made easy.", 60, serif=True)
feature.text(80, 358, "Scan. Fill. Sign. Share.", 23, MUTED)
feature.rect(705, 88, 215, 302, "#B8CBB1", 8)
feature.rect(678, 112, 215, 302, "#D0DFC4", 8)
feature.paper(659, 133, 217, 294, "#FFFFFF", FOLD)
feature.text(686, 183, "YOUR DOCUMENT", 11, MUTED)
feature.line([(687, 215), (821, 215)], GREEN, 7)
feature.line([(687, 236), (791, 236)], "#CDD6CA", 6)
feature.line([(687, 257), (821, 257)], "#CDD6CA", 6)
feature.rect(686, 289, 162, 55, SAGE, 5)
feature.line([(704, 317), (715, 328), (735, 305)], GREEN, 5)
feature.line([(751, 313), (829, 313)], GREEN, 5)
feature.line([(751, 327), (813, 327)], "#A5B59F", 4)
feature.line([(687, 389), (695, 374), (697, 388), (711, 368), (710, 389), (725, 379), (735, 389), (756, 374), (769, 383), (814, 383)], GREEN, 2.5)
feature.save("feature-graphic-1024x500")

(OUT / "README.md").write_text("""# Store artwork

- `play-icon-512.png`: 512 x 512, RGBA PNG, full square. Google Play applies the outer mask and shadow.
- `feature-graphic-1024x500.png`: 1024 x 500, RGB PNG, no transparency.
- Matching SVG files are editable vector sources. Typography uses available Georgia/DejaVu fonts; regenerate on the same environment for identical lettering.

Rebuild from the project root with `python scripts/artwork.py` (requires Pillow). No AI-generated imagery, remote resources, or browser is used. Artwork follows the app's cream, sage, and dark-green document/checkmark identity.

These are marketing graphics, not app screenshots. Capture listing screenshots from the actual final app separately.
""", encoding="utf-8")
