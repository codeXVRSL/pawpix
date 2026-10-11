#!/usr/bin/env python3
"""PawPixel launch art, 2026 ("Kept Light", see PHILOSOPHY.md).

    python3 marketing/launch-2026/build.py <sprites dir> <screenshots dir>

The sprites dir holds 1x PNGs exported from the real sprite engine (pets with their photo coats,
rooms, effects); the screenshots dir holds CI screenshots of the real app. Everything is drawn
on one grid, pixels scaled with nearest-neighbour only.
"""
import os, sys
from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
SPR, SHOTS = sys.argv[1], sys.argv[2]
OUT = HERE
FONTS = os.path.join(HERE, "..", "..", "composeApp", "src", "commonMain", "composeResources", "font")
MONO = os.path.join(HERE, "fonts", "DMMono-Regular.ttf")

CREAM, SAND, INK, PLUM, NIGHT = (255, 247, 236), (255, 233, 207), (43, 33, 53), (58, 46, 74), (23, 18, 36)
CORAL, CORAL_SOFT, EDGE, MUTED = (217, 54, 79), (255, 220, 225), (230, 213, 195), (106, 92, 120)
GLOW = [(255, 240, 222), (255, 233, 212), (255, 226, 202)]

def font(name, size):
    path = MONO if name == "mono" else os.path.join(FONTS, name + ".ttf")
    return ImageFont.truetype(path, size)

def sprite(name, scale):
    im = Image.open(os.path.join(SPR, name + ".png")).convert("RGBA")
    box = im.getbbox()
    im = im.crop(box)
    return im.resize((im.width * scale, im.height * scale), Image.NEAREST)

def dots(img, step, color, off=0):
    """The app's wallpaper: one square every [step] pixels, on the grid."""
    d = ImageDraw.Draw(img)
    s = max(4, step // 10)
    for y in range(step // 2 + off, img.height, step):
        for x in range(step // 2 + off, img.width, step):
            d.rectangle([x, y, x + s - 1, y + s - 1], fill=color)

def stepped_glow(img, cx, cy, r, colors):
    """Light as concentric squared steps, not a gradient."""
    d = ImageDraw.Draw(img)
    n = len(colors)
    for i, c in enumerate(colors):
        rr = int(r * (n - i) / n)
        d.rounded_rectangle([cx - rr, cy - rr, cx + rr, cy + rr], radius=rr // 2, fill=c)

def shadow_band(img, cx, y, w, color, h=None):
    d = ImageDraw.Draw(img)
    h = h or max(8, w // 10)
    d.rounded_rectangle([cx - w // 2, y - h // 2, cx + w // 2, y + h // 2], radius=h // 2, fill=color)

def text_center(img, y, s, f, fill, tracking=0):
    d = ImageDraw.Draw(img)
    if tracking:
        widths = [d.textlength(ch, font=f) for ch in s]
        total = sum(widths) + tracking * (len(s) - 1)
        x = (img.width - total) / 2
        for ch, w in zip(s, widths):
            d.text((x, y), ch, font=f, fill=fill); x += w + tracking
        return
    w = d.textlength(s, font=f)
    d.text(((img.width - w) / 2, y), s, font=f, fill=fill)

def tracked(d, xy, s, f, fill, tracking):
    x, y = xy
    for ch in s:
        d.text((x, y), ch, font=f, fill=fill); x += d.textlength(ch, font=f) + tracking
    return x

def tracked_width(d, s, f, tracking):
    return sum(d.textlength(ch, font=f) for ch in s) + tracking * (len(s) - 1)

def device(shot_path, screen_w, radius_ratio=0.085, bezel_ratio=0.032):
    """A flat, ink-coloured phone holding a real screenshot."""
    shot = Image.open(shot_path).convert("RGB")
    sh = int(screen_w * shot.height / shot.width)
    shot = shot.resize((screen_w, sh), Image.LANCZOS)
    b = int(screen_w * bezel_ratio)
    W, H = screen_w + 2 * b, sh + 2 * b
    dev = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(dev)
    R = int(W * radius_ratio)
    d.rounded_rectangle([0, 0, W - 1, H - 1], radius=R, fill=INK)
    mask = Image.new("L", (screen_w, sh), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, screen_w - 1, sh - 1], radius=R - b, fill=255)
    dev.paste(shot, (b, b), mask)
    return dev

FLOOR, FLOOR_LINE, FLOOR_DOT = (246, 221, 184), (233, 201, 154), (238, 210, 170)

def floor(img, top, u):
    """The room's floor: a band with its skirting line and a sprinkle of squares, as in the app."""
    d = ImageDraw.Draw(img)
    d.rectangle([0, top, img.width, img.height], fill=FLOOR)
    d.rectangle([0, top, img.width, top + int(10 * u)], fill=FLOOR_LINE)
    step = int(66 * u); sq = max(4, int(8 * u))
    for row, y in enumerate(range(top + int(54 * u), img.height - sq, step // 1)):
        for x in range(step // 2 + (step // 2 if row % 2 else 0), img.width, step):
            d.rectangle([x, y, x + sq - 1, y + sq - 1], fill=FLOOR_DOT)

def home_screen(platform):
    """A plain home screen around the real widget renders: dusk wallpaper, frosted squares for other apps, PawPixel's real icon."""
    W, H = (1320, 2868) if platform == "ios" else (1080, 2400)
    img = Image.new("RGB", (W, H))
    bands = [(255, 214, 186), (250, 204, 190), (238, 196, 204), (220, 192, 222), (200, 188, 232), (182, 178, 232)]
    for i, c in enumerate(bands):
        ImageDraw.Draw(img).rectangle([0, H * i // len(bands), W, H * (i + 1) // len(bands)], fill=c)
    over = Image.new("RGBA", (W, H), (0, 0, 0, 0)); od = ImageDraw.Draw(over)
    icon = Image.open(os.path.join(HERE, "..", "..", "docs", "brand", "icon-512.png")).convert("RGBA")
    def app(x, y, size, real=False):
        r = int(size * 0.24)
        if real:
            ic = icon.resize((size, size), Image.LANCZOS)
            m = Image.new("L", (size, size), 0); ImageDraw.Draw(m).rounded_rectangle([0, 0, size - 1, size - 1], radius=r, fill=255)
            over.paste(ic, (x, y), m)
        else:
            od.rounded_rectangle([x, y, x + size, y + size], radius=r, fill=(255, 255, 255, 96))
    d = ImageDraw.Draw(img)
    if platform == "ios":
        d.text((int(W * 0.13), 70), "9:41", font=font("nunito_bold", 52), fill=(255, 255, 255))
        med = Image.open(os.path.join(SHOTS, "ios-widget-home-medium-fine-light.png")).convert("RGBA")
        big = Image.open(os.path.join(SHOTS, "ios-widget-home-large-hungry-light.png")).convert("RGBA")
        x0 = (W - med.width) // 2
        def rounded(im, r=64):
            m = Image.new("L", im.size, 0); ImageDraw.Draw(m).rounded_rectangle([0, 0, im.width - 1, im.height - 1], radius=r, fill=255); return m
        over.paste(med, (x0, 230), rounded(med))
        size, gap = 192, (med.width - 4 * 192) // 3
        y = 230 + med.height + 80
        for k in range(4): app(x0 + k * (size + gap), y, size, real=(k == 1))
        over.paste(big, (x0, y + size + 120), rounded(big))
        y2 = y + size + 120 + big.height + 80
        for k in range(4): app(x0 + k * (size + gap), y2, size)
        od.rounded_rectangle([x0 - 30, H - 330, x0 + med.width + 30, H - 90], radius=90, fill=(255, 255, 255, 70))
        for k in range(4): app(x0 + k * (size + gap), H - 306, size)
    else:
        d.text((52, 40), "5:02", font=font("nunito_bold", 44), fill=(255, 255, 255))
        shot = Image.open(os.path.join(SHOTS, "android-14-home-screen-widget-a-beat-later.png")).convert("RGBA")
        wid = shot.crop((38, 170, 422, 895))
        m = Image.new("L", wid.size, 0); ImageDraw.Draw(m).rounded_rectangle([0, 0, wid.width - 1, wid.height - 1], radius=58, fill=255)
        wx, wy = 60, 200
        over.paste(wid, (wx, wy), m)
        size = 168; cols = [W - 60 - 2 * size - 70, W - 60 - size]
        for row in range(3):
            for k, cx in enumerate(cols): app(cx, wy + row * (size + 96), size, real=(row == 0 and k == 0))
        for row in range(2):
            for k in range(4): app(60 + k * ((W - 120 - size) // 3), wy + wid.height + 110 + row * (size + 96), size)
        for k in range(4): app(60 + k * ((W - 120 - size) // 3), H - 360, size)
    img.paste(over, (0, 0), over)
    path = os.path.join(SHOTS, f"{platform}-home-synth.png")
    img.save(path)
    return path

# ---------------------------------------------------------------- store screenshots

STORE = [
    # (file, plate, headline lines, subline, screenshot key, pet, pet frame)
    ("01-pixel-twin", "01", ["Meet your pet's", "pixel twin"], "From one photo, made on your phone.", "02-your-pixel-pet", "tabby", "idle"),
    ("02-real-care", "02", ["Their mood follows", "real care"], "Feed, walk, brush. Tap Done and watch.", "after-done", "tabby", "eat"),
    ("03-home-screen", "03", ["Lives on your", "home screen"], "Breathes, blinks and gets hungry there too.", "widget", "grey", "blink"),
    ("04-if-lost", "04", ["If they ever", "get lost"], "One tap alerts owners nearby.", "lost", "corgi", "idle"),
    ("05-share", "05", ["Show them off"], "A looping GIF or a before-and-after card.", "share", "golden", "walk"),
]

SHOT_FOR = {
    "ios": {"02-your-pixel-pet": "ios-02-your-pixel-pet", "after-done": "ios-07-after-done", "widget": "ios-home-synth",
            "lost": "ios-25-lost-alert", "share": "ios-13-share-card"},
    "android": {"02-your-pixel-pet": "android-02-your-pixel-pet", "after-done": "android-06-after-done",
                "widget": "android-home-synth", "lost": "android-27-alert", "share": "android-21-pet-id-card"},
}

def store_frame(platform, W, H, spec):
    name, plate, lines, sub, key, pet, frame = spec
    u = W / 1320  # one design unit
    img = Image.new("RGB", (W, H), CREAM)
    dots(img, int(66 * u), SAND)
    d = ImageDraw.Draw(img)
    # Plate tag: a specimen label, tracked mono, with hairlines either side.
    tag = f"PLATE {plate}  ·  PAWPIXEL"
    tf = font("mono", int(30 * u))
    tw = tracked_width(d, tag, tf, int(6 * u))
    ty = int(150 * u)
    tracked(d, ((W - tw) / 2, ty), tag, tf, MUTED, int(6 * u))
    for x0, x1 in [((W - tw) / 2 - 150 * u, (W - tw) / 2 - 30 * u), ((W + tw) / 2 + 30 * u, (W + tw) / 2 + 150 * u)]:
        d.rectangle([x0, ty + 18 * u, x1, ty + 20 * u], fill=EDGE)
    hf = font("pixelify_bold", int(118 * u))
    y = ty + int(80 * u)
    for line in lines:
        text_center(img, y, line, hf, INK); y += int(132 * u)
    sf = font("nunito_semibold", int(46 * u))
    text_center(img, y + int(18 * u), sub, sf, MUTED)
    top = y + int(110 * u)
    # The phone stands behind the room's floor: large, its lower part hidden by the floor band.
    floor_top = H - int(270 * u)
    shot = os.path.join(SHOTS, SHOT_FOR[platform][key] + ".png")
    dev = device(shot, int(W * 0.78))
    dx = (W - dev.width) // 2; dy = top
    sh = Image.new("RGBA", dev.size, (0, 0, 0, 0))
    ImageDraw.Draw(sh).rounded_rectangle([0, 0, dev.width - 1, dev.height - 1], radius=int(dev.width * 0.085), fill=EDGE + (255,))
    img.paste(sh, (dx + int(22 * u), dy + int(22 * u)), sh)
    img.paste(dev, (dx, dy), dev)
    floor(img, floor_top, u)
    # The pet sits on the floor in front of the phone, with a flat shadow.
    p = sprite(f"pet-{pet}-{frame}", max(4, int(9 * u)))
    px = min(dx + dev.width - int(p.width * 0.62), W - p.width - int(48 * u))
    feet = floor_top + int(84 * u)
    shadow_band(img, px + p.width // 2, feet, int(p.width * 0.78), FLOOR_LINE, int(18 * u))
    img.paste(p, (px, feet - p.height + int(6 * u)), p)
    out = os.path.join(OUT, "store", platform)
    os.makedirs(out, exist_ok=True)
    img.save(os.path.join(out, f"{name}.png"))
    return img

# ---------------------------------------------------------------- poster and posts

def label(d, x, y, s, size, fill=MUTED, tracking=None, center=False):
    f = font("mono", size); t = int(size * 0.28) if tracking is None else tracking
    if center: x = x - tracked_width(d, s, f, t) / 2
    tracked(d, (x, y), s, f, fill, t)

PHOTO = os.path.join(HERE, "..", "..", "composeApp", "src", "androidInstrumentedTest", "assets", "pet.jpg")

def cat_photo():
    """The repo's own test cat, as a square around its face."""
    im = Image.open(PHOTO).convert("RGB"); w, h = im.size; c = min(w, h)
    x0 = int((w - c) * 0.42)
    return im.crop((x0, 0, x0 + c, c))

def swatches(photo, n=10):
    """The photo as n x n squares of colour (box-averaged), the way the engine first reads a face."""
    return photo.resize((n, n), Image.BOX)

def rounded_paste(img, im, xy, r):
    m = Image.new("L", im.size, 0); ImageDraw.Draw(m).rounded_rectangle([0, 0, im.width - 1, im.height - 1], radius=r, fill=255)
    img.paste(im, xy, m)

def lineup(img, names, y_floor, scale, x0, x1, frame="idle"):
    ps = [sprite(f"pet-{n}-{frame}", scale) for n in names]
    total = sum(p.width for p in ps); gap = (x1 - x0 - total) / (len(ps) - 1)
    x = x0
    for p in ps:
        shadow_band(img, int(x + p.width / 2), y_floor, int(p.width * 0.8), FLOOR_LINE, max(8, scale * 2))
        img.paste(p, (int(x), y_floor - p.height + scale), p); x += p.width + gap

def poster():
    W, H = 2160, 2700
    img = Image.new("RGB", (W, H), CREAM); dots(img, 90, SAND)
    d = ImageDraw.Draw(img)
    label(d, W / 2, 150, "A FIELD GUIDE TO BEING LOOKED AFTER  ·  PLATE 00", 30, center=True)
    d.rectangle([W / 2 - 520, 214, W / 2 + 520, 216], fill=EDGE)
    # Three magnitudes of one cat: the photograph, a hundred squares of colour, the pixel twin.
    cell, top = 480, 430
    xs = [200, (W - cell) // 2, W - 200 - cell]
    photo = cat_photo()
    rounded_paste(img, photo.resize((cell, cell), Image.LANCZOS), (xs[0], top), 40)
    sw = swatches(photo).resize((cell, cell), Image.NEAREST)
    gap_img = Image.new("RGB", (cell, cell), CREAM); gd = ImageDraw.Draw(gap_img)
    step = cell / 10
    for gy in range(10):
        for gx in range(10):
            c = sw.getpixel((int(gx * step + 1), int(gy * step + 1)))
            gd.rectangle([gx * step + 3, gy * step + 3, (gx + 1) * step - 4, (gy + 1) * step - 4], fill=c)
    img.paste(gap_img, (xs[1], top))
    stepped_glow(img, xs[2] + cell // 2, top + cell // 2, cell // 2, GLOW)
    cat = sprite("pet-tabby-idle", 9)
    shadow_band(img, xs[2] + cell // 2, top + cell - 56, int(cat.width * 0.8), FLOOR_LINE, 20)
    img.paste(cat, (xs[2] + (cell - cat.width) // 2, top + cell - 56 - cat.height + 9), cat)
    # Hairline arrows between the figures.
    for a, b in [(xs[0] + cell, xs[1]), (xs[1] + cell, xs[2])]:
        y = top + cell // 2; d.rectangle([a + 36, y - 1, b - 36, y + 1], fill=MUTED)
        for k in range(1, 5): d.rectangle([b - 36 - k * 5, y - k * 5, b - 36 - k * 5 + 2, y - k * 5 + 2], fill=MUTED); d.rectangle([b - 36 - k * 5, y + k * 5 - 2, b - 36 - k * 5 + 2, y + k * 5], fill=MUTED)
    for x, t in zip(xs, ["FIG. 1  PHOTOGRAPH", "FIG. 2  100 SQUARES", "FIG. 3  PIXEL TWIN"]):
        label(d, x + cell / 2, top + cell + 48, t, 26, center=True)
    # The one phrase that matters.
    hf = font("pixelify_bold", 210)
    text_center(img, 1250, "Your pet,", hf, INK)
    text_center(img, 1480, "in pixels.", hf, CORAL)
    sf = font("nunito_semibold", 60)
    text_center(img, 1760, "Made from one photo. Lives on your home screen.", sf, MUTED)
    text_center(img, 1845, "Gets hungry when you forget dinner.", sf, MUTED)
    # The household, on the floor.
    floor(img, 2280, 2.2)
    lineup(img, ["tabby", "grey", "corgi", "golden", "bun", "snow"], 2280 + 40, 6, 260, W - 260)
    wf = font("pixelify_bold", 84)
    text_center(img, 2440, "PawPixel", wf, INK)
    label(d, W / 2, 2560, "COMING SOON  ·  ANDROID AND IPHONE", 30, fill=MUTED, center=True)
    img.save(os.path.join(OUT, "poster.png"))

def post_reveal():
    W, H = 1080, 1350
    img = Image.new("RGB", (W, H), CREAM); dots(img, 60, SAND); d = ImageDraw.Draw(img)
    label(d, W / 2, 80, "OBSERVATION 01  ·  ONE PHOTO", 22, center=True)
    photo = cat_photo()
    s = 370; top = 180; m = 90
    rounded_paste(img, photo.resize((s, s), Image.LANCZOS), (m, top), 32)
    stepped_glow(img, W - m - s // 2, top + s // 2, s // 2, GLOW)
    cat = sprite("pet-tabby-idle", 6)
    shadow_band(img, W - m - s // 2, top + s - 40, int(cat.width * 0.8), FLOOR_LINE, 12)
    img.paste(cat, (W - m - s // 2 - cat.width // 2, top + s - 40 - cat.height + 6), cat)
    y = top + s // 2; a, b = m + s + 34, W - m - s - 34
    d.rectangle([a, y - 1, b, y + 1], fill=MUTED)
    for k in range(1, 5): d.rectangle([b - k * 5, y - k * 5, b - k * 5 + 2, y - k * 5 + 2], fill=MUTED); d.rectangle([b - k * 5, y + k * 5 - 2, b - k * 5 + 2, y + k * 5], fill=MUTED)
    label(d, m + s / 2, top + s + 30, "BEFORE", 20, center=True); label(d, W - m - s / 2, top + s + 30, "AFTER", 20, center=True)
    hf = font("pixelify_bold", 104)
    text_center(img, 760, "Same face.", hf, INK); text_center(img, 880, "New pixels.", hf, CORAL)
    text_center(img, 1040, "Even the tabby stripes come along.", font("nunito_semibold", 40), MUTED)
    floor(img, 1180, 1.0)
    text_center(img, 1230, "PawPixel", font("pixelify_bold", 46), INK)
    img.save(os.path.join(OUT, "post-01-same-face.png"))

def post_moods():
    W, H = 1080, 1350
    img = Image.new("RGB", (W, H), CREAM); dots(img, 60, SAND); d = ImageDraw.Draw(img)
    label(d, W / 2, 80, "OBSERVATION 02  ·  MOOD FOLLOWS CARE", 22, center=True)
    hf = font("pixelify_bold", 96)
    text_center(img, 150, "Their mood", hf, INK); text_center(img, 260, "follows real care", hf, INK)
    moods = [("happy", "HAPPY", "fed, walked, loved", "heart"), ("hungry", "HUNGRY", "dinner is late", "bowl"),
             ("sleepy", "SLEEPY", "after 10 pm", "zzz"), ("sad", "SAD", "missed a lot", "rain")]
    cw, ch = 400, 360; x0, y0 = (W - 2 * cw - 40) // 2, 430
    for i, (key, name, note, fx) in enumerate(moods):
        cx = x0 + (i % 2) * (cw + 40); cy = y0 + (i // 2) * (ch + 40)
        d.rounded_rectangle([cx, cy, cx + cw, cy + ch], radius=36, fill=(255, 251, 244), outline=EDGE, width=4)
        p = sprite(f"pet-tabby-mood-{key}", 5)
        fy = cy + ch - 120
        shadow_band(img, cx + cw // 2, fy, int(p.width * 0.8), FLOOR_LINE, 12)
        px = cx + (cw - p.width) // 2
        img.paste(p, (px, fy - p.height + 5), p)
        e = sprite(f"effect-{fx}", 7)
        img.paste(e, (px + p.width - e.width // 2, fy - p.height - e.height // 2), e)
        label(d, cx + cw / 2, cy + ch - 86, name, 24, fill=CORAL if key == "happy" else INK, center=True)
        text_center_at(d, cx + cw / 2, cy + ch - 46, note, font("nunito_semibold", 26), MUTED)
    text_center(img, 1250, "PawPixel", font("pixelify_bold", 42), INK)
    img.save(os.path.join(OUT, "post-02-moods.png"))

def text_center_at(d, cx, y, s, f, fill):
    d.text((cx - d.textlength(s, font=f) / 2, y), s, font=f, fill=fill)

def post_lineup():
    W, H = 1080, 1350
    img = Image.new("RGB", (W, H), CREAM); dots(img, 60, SAND); d = ImageDraw.Draw(img)
    label(d, W / 2, 80, "OBSERVATION 03  ·  THE HOUSEHOLD", 22, center=True)
    hf = font("pixelify_bold", 100)
    text_center(img, 170, "Dogs, cats", hf, INK); text_center(img, 285, "and bunnies", hf, INK)
    text_center(img, 430, "Pointy ears, floppy ears, lop ears.", font("nunito_semibold", 40), MUTED)
    floor(img, 860, 1.0)
    lineup(img, ["tabby", "corgi", "snow"], 900, 6, 110, W - 110)
    lineup(img, ["grey", "golden", "bun"], 1140, 5, 170, W - 170, frame="blink")
    text_center(img, 1250, "PawPixel", font("pixelify_bold", 46), INK)
    img.save(os.path.join(OUT, "post-03-household.png"))

if __name__ == "__main__":
    home_screen("ios"); home_screen("android")
    for spec in STORE:
        store_frame("ios", 1320, 2868, spec)
        store_frame("android", 1080, 1920, spec)
    print("store frames done")
    poster(); post_reveal(); post_moods(); post_lineup()
    print("poster and posts done")
