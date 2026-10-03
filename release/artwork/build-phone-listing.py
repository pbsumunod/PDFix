"""Compose imagegen backdrops with unaltered PDFix screenshots."""

from pathlib import Path
from PIL import Image, ImageDraw, ImageFont


ROOT = Path(__file__).resolve().parent
SOURCE = Path('/Users/pamelasumunod/Downloads')
OUT = ROOT / 'phone-listing-imagegen'
OUT.mkdir(exist_ok=True)

W, H = 1440, 2560
GREEN = '#355E49'
INK = '#243E32'
CREAM = '#F7F6F2'

SCREENS = [
    ('01-pdf-tools.png', 'Screenshot_2026-10-03-19-17-10-535_com.sumclartech.pdfix.debug-edit.png', 'Your PDF tools, together', 'background-01-sage.png'),
    ('02-fill-forms.png', 'Screenshot_2026-10-03-19-19-22-111_com.sumclartech.pdfix.debug-edit.png', 'Fill forms, step by step', 'background-02-peach.png'),
    ('03-sign-pdfs.png', 'Screenshot_2026-10-03-19-22-57-418_com.sumclartech.pdfix.debug-edit.png', 'Sign right on your PDF', 'background-03-lavender.png'),
    ('04-edit-pdfs.png', 'Screenshot_2026-10-03-19-23-08-109_com.sumclartech.pdfix.debug-edit.png', 'Edit PDFs on your phone', 'background-04-sage.png'),
]

heading = ImageFont.truetype('/System/Library/Fonts/Supplemental/Georgia Bold.ttf', 86)
label = ImageFont.truetype('/System/Library/Fonts/Avenir Next.ttc', 34)
number_font = ImageFont.truetype('/System/Library/Fonts/Avenir Next.ttc', 30)

for i, (filename, source, title, background) in enumerate(SCREENS, 1):
    backdrop = Image.open(OUT / background).convert('RGB')
    # Center-crop to the listing aspect ratio before resizing; decoration stays at edges.
    desired = W / H
    current = backdrop.width / backdrop.height
    if current > desired:
        crop_w = round(backdrop.height * desired)
        left = (backdrop.width - crop_w) // 2
        backdrop = backdrop.crop((left, 0, left + crop_w, backdrop.height))
    else:
        crop_h = round(backdrop.width / desired)
        top = (backdrop.height - crop_h) // 2
        backdrop = backdrop.crop((0, top, backdrop.width, top + crop_h))
    canvas = backdrop.resize((W, H), Image.Resampling.LANCZOS)
    d = ImageDraw.Draw(canvas)

    # A restrained editorial header with matching placement across the series.
    d.rectangle((0, 0, W, 344), fill=CREAM)
    d.rounded_rectangle((88, 76, 105, 126), radius=8, fill=GREEN)
    d.text((128, 77), 'PDFix', font=label, fill=GREEN)
    counter = f'{i:02d} / {len(SCREENS):02d}'
    cw = d.textlength(counter, font=number_font)
    d.text((W - 90 - cw, 80), counter, font=number_font, fill=GREEN)
    d.text((88, 168), title, font=heading, fill=INK)
    d.rounded_rectangle((90, 313, 265, 322), radius=4, fill=GREEN)

    # The actual screenshot is scaled uniformly, with no painting on its pixels.
    image = Image.open(SOURCE / source).convert('RGBA')
    target_w = 966
    target_h = round(image.height * target_w / image.width)
    image = image.resize((target_w, target_h), Image.Resampling.LANCZOS)
    x = (W - target_w) // 2
    y = 378
    canvas.paste(image, (x, y), image)
    canvas.save(OUT / filename, optimize=True)
    print(f'{filename}: {W}x{H}, source={source}')
