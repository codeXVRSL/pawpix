#!/usr/bin/env python3
"""Writes the HTML for every PawPixel Facebook graphic. Render them with render.sh (Chromium via Playwright).

The look is the app's own "toy box": cream paper, ink text, candy colours, white sticker panels with a
sand lip, Pixelify Sans headlines, Nunito body, and the sprite engine's real pets and rooms.
"""
import os, textwrap

ROOT = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(ROOT, "..", ".."))
A = os.path.join(REPO, "marketing", "assets")          # exported by the sprite engine
F = os.path.join(REPO, "composeApp", "src", "commonMain", "composeResources", "font")
OUT = os.path.join(ROOT, "html")
os.makedirs(OUT, exist_ok=True)

CSS = f"""
@font-face {{ font-family: Pixelify; src: url({F}/pixelify_bold.ttf); font-weight: 700; }}
@font-face {{ font-family: Pixelify; src: url({F}/pixelify_medium.ttf); font-weight: 500; }}
@font-face {{ font-family: Nunito; src: url({F}/nunito_extrabold.ttf); font-weight: 800; }}
@font-face {{ font-family: Nunito; src: url({F}/nunito_bold.ttf); font-weight: 700; }}
@font-face {{ font-family: Nunito; src: url({F}/nunito_semibold.ttf); font-weight: 600; }}
@font-face {{ font-family: Nunito; src: url({F}/nunito_regular.ttf); font-weight: 400; }}
:root {{
  --cream: #FFF4E6; --paper: #FFFBF4; --ink: #2B2135; --ink2: #6E6287; --sand: #E6D5C3; --sand2: #F1E3D1;
  --coral: #E0334C; --coral-lip: #B0243A; --butter: #FFCF5C; --sky: #7FCBEC; --leaf: #9BD98A; --salmon: #FF8E7E;
  --lavender: #BDAFF3; --pink: #FFA9C9; --mint: #93E3C7; --peach: #FFC7A3; --night: #1E1B2E; --good: #2E8B57;
}}
* {{ box-sizing: border-box; }}
html, body {{ margin: 0; background: var(--cream); color: var(--ink); font-family: Nunito, sans-serif; overflow: hidden; }}
.page {{ position: relative; overflow: hidden; }}
.px {{ image-rendering: pixelated; image-rendering: crisp-edges; display: block; }}
h1, h2, .pixel {{ font-family: Pixelify, monospace; font-weight: 700; margin: 0; line-height: 1.02; letter-spacing: -0.01em; }}
.sub {{ font-family: Nunito; font-weight: 800; }}
.body {{ font-family: Nunito; font-weight: 600; color: var(--ink2); }}
.sticker {{ background: white; border: 3px solid var(--sand); border-radius: 36px; box-shadow: 0 8px 0 var(--sand); }}
.key {{ display: inline-flex; align-items: center; gap: 14px; background: var(--coral); color: white; border-radius: 999px; box-shadow: 0 8px 0 var(--coral-lip); font-family: Nunito; font-weight: 800; }}
.key.white {{ background: white; color: var(--ink); box-shadow: 0 8px 0 var(--sand); border: 3px solid var(--sand); }}
.brand {{ position: absolute; display: flex; align-items: center; gap: 14px; }}
.brand .mark {{ width: 56px; height: 56px; border-radius: 16px; background: var(--coral); display: grid; place-items: center; box-shadow: 0 5px 0 var(--coral-lip); }}
.brand .mark img {{ width: 32px; }}
.brand .word {{ font-family: Pixelify; font-weight: 700; font-size: 44px; color: var(--ink); }}
.bubble {{ position: absolute; background: white; border: 3px solid var(--sand); border-radius: 28px; padding: 16px 28px; font-weight: 800; font-size: 34px; }}
.bubble:after {{ content: ""; position: absolute; left: 50%; bottom: -22px; margin-left: -16px; border: 16px solid transparent; border-top-color: var(--sand); border-bottom: 0; }}
.bubble:before {{ content: ""; position: absolute; left: 50%; bottom: -16px; margin-left: -12px; border: 12px solid transparent; border-top-color: white; border-bottom: 0; z-index: 1; }}
.dots {{ background-image: radial-gradient(var(--sand2) 2.5px, transparent 2.6px); background-size: 28px 28px; }}
.candy {{ width: 120px; height: 120px; border-radius: 34px; display: grid; place-items: center; border: 4px solid rgba(0,0,0,.18); }}
.candy img {{ width: 60px; }}
.meter {{ display: flex; gap: 6px; }}
.meter i {{ display: block; width: 34px; height: 20px; border-radius: 6px; background: var(--sand2); border-bottom: 4px solid #CDB9A2; }}
.meter i.on.butter {{ background: var(--butter); border-color: #D9A52E; }}
.meter i.on.sky {{ background: var(--sky); border-color: #3F97BD; }}
.meter i.on.mint {{ background: var(--mint); border-color: #53B898; }}
.meter i.on.salmon {{ background: var(--salmon); border-color: #D9574A; }}
.foot {{ position: absolute; left: 0; right: 0; display: flex; justify-content: center; align-items: center; gap: 18px; color: var(--ink2); font-weight: 700; font-size: 28px; }}
"""

def page(name, w, h, body, extra_css=""):
    html = f"""<!doctype html><html><head><meta charset="utf-8"><style>{CSS}
html, body {{ width: {w}px; height: {h}px; }} .page {{ width: {w}px; height: {h}px; }} {extra_css}</style></head>
<body><div class="page">{body}</div></body></html>"""
    with open(os.path.join(OUT, f"{name}.html"), "w") as f:
        f.write(html)
    print(f"{name} {w} {h}")

def brand(x, y, dark=False):
    word = "color:white" if dark else ""
    return f'<div class="brand" style="left:{x}px;top:{y}px"><div class="mark"><img class="px" src="{A}/icon-paw-white.png"></div><div class="word" style="{word}">PawPixel</div></div>'

def pet(name, k=10, x=0, y=0, extra=""):
    # The exports are 12x (612 wide): a width of 51*k shows every pet pixel as k screen pixels.
    return f'<img class="px" src="{A}/pet-{name}.png" style="position:absolute;left:{x}px;top:{y}px;width:{51*k}px;{extra}">'

def effect(name, k=8, x=0, y=0):
    return f'<img class="px" src="{A}/effect-{name}.png" style="position:absolute;left:{x}px;top:{y}px;height:auto;transform:scale({k/12});transform-origin:top left">'

def meter(colour, on, n=5):
    return '<div class="meter">' + "".join(f'<i class="{"on " + colour if i < on else ""}"></i>' for i in range(n)) + "</div>"

def need(colour_var, icon, m_colour, on, x, y):
    return f'''<div class="sticker" style="position:absolute;left:{x}px;top:{y}px;width:176px;padding:16px 14px 18px;border-radius:30px">
      <div class="candy" style="background:var(--{colour_var});margin:0 auto 14px"><img class="px" src="{A}/icon-{icon}.png"></div>{meter(m_colour, on)}</div>'''

# ---------------------------------------------------------------- 0. profile picture (1080x1080)
page("profile", 1080, 1080, f"""
<div style="position:absolute;inset:0;background:var(--coral)"></div>
<div style="position:absolute;left:0;right:0;bottom:0;height:200px;background:var(--coral-lip)"></div>
{pet("cat-orange-idle", 16, 132, 150)}
""")

# ---------------------------------------------------------------- 1. cover (1640x624; keep the message inside the middle 1200px)
page("cover", 1640, 624, f"""
<img class="px" src="{A}/room-cover-day.png" style="position:absolute;left:0;top:0;width:1640px">
<div style="position:absolute;left:0;right:0;top:0;height:624px;background:linear-gradient(90deg, rgba(255,244,230,.92) 0%, rgba(255,244,230,.85) 38%, rgba(255,244,230,0) 60%)"></div>
<div style="position:absolute;left:300px;top:150px">
  <h1 style="font-size:150px">PawPixel</h1>
  <div class="sub" style="font-size:50px;margin-top:18px">Your real pet, in pixels.</div>
  <div class="body" style="font-size:32px;margin-top:14px">Its mood follows the real care you give.</div>
</div>
{pet("cat-tabby-idle", 7, 960, 212)}
{pet("dog-golden-idle", 7, 1230, 220)}
{effect("heart", 7, 1290, 130)}
<div class="key" style="position:absolute;left:300px;top:480px;padding:16px 34px;font-size:32px"><img class="px" src="{A}/icon-paw-white.png" style="width:30px">Free on Android · iOS soon</div>
""")

# ---------------------------------------------------------------- 2. Day 1: meet the pixel twin (1080x1350)
page("day1-meet-the-twin", 1080, 1350, f"""
<div class="dots" style="position:absolute;inset:0"></div>
{brand(60, 56)}
<h1 style="position:absolute;left:60px;top:160px;font-size:96px;width:960px">Meet your pet's<br>pixel twin.</h1>
<div class="sticker" style="position:absolute;left:60px;top:420px;width:440px;height:520px;overflow:hidden;padding:0;border-radius:40px">
  <img src="{A}/photo-crop.png" style="width:100%;height:100%;object-fit:cover;display:block">
</div>
<img class="px" src="{A}/icon-paw.png" style="position:absolute;left:506px;top:650px;width:68px;transform:rotate(90deg);opacity:.0">
<div style="position:absolute;left:512px;top:640px;width:56px;height:56px;color:var(--coral);font-size:90px;line-height:56px;text-align:center;font-family:Pixelify">›</div>
<div class="sticker" style="position:absolute;left:580px;top:420px;width:440px;height:520px;padding:0;overflow:hidden;border-radius:40px;background:var(--paper)">
  <img class="px" src="{A}/room-square-day.png" style="position:absolute;left:-70px;top:-330px;width:580px">
  {pet("cat-tabby-idle", 7, 40, 150)}
  {effect("heart", 9, 300, 60)}
</div>
<div class="body" style="position:absolute;left:60px;top:985px;font-size:36px;width:960px;line-height:1.3">One photo of your dog or cat becomes a pixel pet that looks like <b style="color:var(--ink)">them</b>, lives on your home screen, and gets hungry when you skip dinner.</div>
<div class="key" style="position:absolute;left:60px;top:1180px;padding:20px 40px;font-size:36px"><img class="px" src="{A}/icon-paw-white.png" style="width:34px">Free on Android</div>
<div class="body" style="position:absolute;left:520px;top:1200px;font-size:30px">No ads. No account.</div>
""")

# ---------------------------------------------------------------- 3. Day 2: how it works, a three-card carousel (1080x1080 each)
def step_card(name, n, title, sub, art):
    page(name, 1080, 1080, f"""
<div class="dots" style="position:absolute;inset:0"></div>
{brand(60, 56)}
<div class="pixel" style="position:absolute;right:60px;top:56px;font-size:44px;color:var(--ink2)">{n} / 3</div>
<div style="position:absolute;left:60px;top:160px;width:960px;height:560px;overflow:hidden;border-radius:36px">{art}</div>
<h2 style="position:absolute;left:60px;top:790px;font-size:84px;width:960px;white-space:nowrap">{title}</h2>
<div class="body" style="position:absolute;left:60px;top:930px;font-size:34px;width:960px;line-height:1.3">{sub}</div>
""")

step_card("day2-how-1", 1, "1. Snap a photo.", "PawPixel cuts your pet out and draws them in pixels, right on your phone. Their colours, their markings.", f"""
<div class="sticker" style="position:absolute;left:40px;top:40px;width:360px;height:440px;overflow:hidden;padding:0;transform:rotate(-4deg)"><img src="{A}/photo-crop.png" style="width:100%;height:100%;object-fit:cover"></div>
<div style="position:absolute;left:430px;top:230px;color:var(--coral);font-size:120px;font-family:Pixelify">›</div>
<div class="sticker" style="position:absolute;left:540px;top:40px;width:380px;height:440px;padding:0;overflow:hidden;transform:rotate(3deg);background:var(--paper)">{pet("cat-tabby-idle", 7, 12, 90)}{effect("sparkle", 6, 290, 60)}</div>
""")
step_card("day2-how-2", 2, "2. Add their care.", "Feeding, water, walks, litter, medicine. Each one gets a meter, like a virtual pet's hunger bar, driven by real life.", f"""
<div style="position:absolute;left:0;top:130px;transform:scale(1.22);transform-origin:top left">{need("butter", "bowl", "butter", 2, 0, 0)}{need("sky", "drop", "sky", 4, 200, 0)}{need("mint", "paw", "mint", 3, 400, 0)}{need("salmon", "ball", "salmon", 1, 600, 0)}</div>
<div class="bubble" style="left:260px;top:0px;font-size:34px">One meter per task, like a hunger bar</div>
""")
step_card("day2-how-3", 3, "3. Tap Done. Hearts.", "Feed them for real, tap the meter, and your pixel pet eats, does zoomies and sends up hearts.", f"""
<img class="px" src="{A}/room-square-day.png" style="position:absolute;left:0;top:-330px;width:960px">
<div style="position:absolute;inset:0;border-radius:36px;border:3px solid var(--sand)"></div>
{pet("cat-tabby-eat", 8, 300, 130)}{effect("heart", 10, 600, 80)}{effect("bowl", 10, 170, 380)}
<div class="key" style="position:absolute;left:660px;top:410px;padding:16px 34px;font-size:34px;background:var(--butter);color:var(--ink);box-shadow:0 8px 0 #D9A52E"><img class="px" src="{A}/icon-check.png" style="width:28px">Fed</div>
""")

# ---------------------------------------------------------------- 4. Day 3: mood follows real care (1080x1350)
page("day3-mood", 1080, 1350, f"""
{brand(60, 56)}
<h1 style="position:absolute;left:60px;top:150px;font-size:88px;width:960px">Their mood follows<br>real care.</h1>
<div class="sticker" style="position:absolute;left:60px;top:400px;width:960px;height:400px;padding:0;overflow:hidden;background:#EFE6DC">
  <img class="px" src="{A}/room-cover-dusk.png" style="position:absolute;left:0;top:-120px;width:1180px;filter:saturate(.6)">
  {pet("cat-tabby-sad", 7, 90, 90)}{effect("exclaim", 10, 330, 40)}
  <div style="position:absolute;right:50px;top:60px;width:430px"><div class="sub" style="font-size:48px">Skipped dinner?</div><div class="body" style="font-size:30px;margin:8px 0 22px">Hungry. Pacing by the bowl.</div>{meter("butter", 0)}</div>
</div>
<div class="sticker" style="position:absolute;left:60px;top:830px;width:960px;height:400px;padding:0;overflow:hidden;background:var(--paper)">
  <img class="px" src="{A}/room-cover-day.png" style="position:absolute;left:0;top:-120px;width:1180px">
  {pet("cat-tabby-stretch", 7, 90, 90)}{effect("heart", 10, 330, 30)}
  <div style="position:absolute;right:50px;top:60px;width:430px"><div class="sub" style="font-size:48px">Fed on time.</div><div class="body" style="font-size:30px;margin:8px 0 22px">Happy. Zoomies.</div>{meter("butter", 5)}</div>
</div>
<div class="foot" style="top:1265px">Real tasks, real timing, a real-looking pet.</div>
""")

# ---------------------------------------------------------------- 5. Day 4: which one is your pet (1080x1350)
coats = [("cat-orange", "A"), ("cat-black", "B"), ("cat-white", "C"), ("cat-grey", "D"), ("dog-golden", "E"), ("dog-brown", "F"), ("dog-cream", "G"), ("dog-black", "H")]
cells = ""
for i, (c, letter) in enumerate(coats):
    cx, cy = 60 + (i % 4) * 240, 460 + (i // 4) * 330
    cells += f'''<div class="sticker" style="position:absolute;left:{cx}px;top:{cy}px;width:220px;height:290px;padding:0;overflow:hidden;background:var(--paper)">
      {pet(c + "-idle", 4, 8, 50)}<div class="pixel" style="position:absolute;left:16px;top:12px;font-size:44px;color:var(--coral)">{letter}</div></div>'''
page("day4-which-one", 1080, 1350, f"""
<div class="dots" style="position:absolute;inset:0"></div>
{brand(60, 56)}
<h1 style="position:absolute;left:60px;top:160px;font-size:96px;width:960px">Which one is<br>your pet?</h1>
<div class="body" style="position:absolute;left:60px;top:380px;font-size:34px">Comment the letter. Bonus points for their name.</div>
{cells}
<div class="foot" style="top:1190px">Every coat, every marking: PawPixel draws yours from a photo.</div>
<div class="foot" style="top:1250px;color:var(--coral)">Free on Android</div>
""")

# ---------------------------------------------------------------- 6. Day 5: they live on your home screen (1080x1350)
icons_row = "".join(f'<div style="width:74px;height:74px;border-radius:22px;background:{c};opacity:.9"></div>' for c in ["#5B8DEF", "#48C78E", "#F2B84B", "#E0334C"])
page("day5-widget", 1080, 1350, f"""
<div style="position:absolute;inset:0;background:linear-gradient(180deg,#FFE9D2 0%,#FFF4E6 60%)"></div>
{brand(60, 56)}
<h1 style="position:absolute;left:60px;top:150px;font-size:88px;width:600px">They live on<br>your home<br>screen.</h1>
<div class="body" style="position:absolute;left:60px;top:560px;font-size:32px;width:520px;line-height:1.35">Breathes, blinks, wanders. Tap Done on the widget when you feed them for real.</div>
<div class="key white" style="position:absolute;left:60px;top:760px;padding:18px 34px;font-size:32px"><img class="px" src="{A}/icon-phone.png" style="width:30px">Android widget · iOS soon</div>
<!-- the phone -->
<div style="position:absolute;left:620px;top:150px;width:420px;height:1100px;border-radius:64px;background:var(--ink);padding:16px;box-shadow:0 20px 0 #15111E">
  <div style="width:100%;height:100%;border-radius:50px;overflow:hidden;background:linear-gradient(160deg,#C9B8F5,#F7C1D1 60%,#FFE1B8);position:relative">
    <div style="position:absolute;left:0;right:0;top:0;height:60px;display:flex;justify-content:space-between;padding:18px 30px;color:var(--ink);font-weight:800;font-size:22px"><span>7:02</span><span>●●●</span></div>
    <div class="sticker" style="position:absolute;left:22px;top:84px;width:344px;height:270px;padding:0;overflow:hidden;border-radius:30px">
      <img class="px" src="{A}/room-cover-day.png" style="position:absolute;left:-200px;top:-98px;width:800px">
      {pet("cat-tabby-idle", 4, 60, -4)}{effect("heart", 5, 200, 10)}
      <div style="position:absolute;left:0;right:0;bottom:0;height:92px;background:white;padding:14px 18px">
        <div style="font-weight:800;font-size:22px">Chelsea is happy!</div>
        <div class="body" style="font-size:18px">Next: Feed · 6:00 PM</div>
        <div class="key" style="position:absolute;right:16px;top:16px;padding:10px 20px;font-size:20px;box-shadow:0 5px 0 var(--coral-lip)">Done</div>
      </div>
    </div>
    <div style="position:absolute;left:36px;top:400px;display:flex;gap:30px">{icons_row}</div>
    <div style="position:absolute;left:36px;top:520px;display:flex;gap:30px">{icons_row}</div>
    <div style="position:absolute;left:36px;top:640px;display:flex;gap:30px">{icons_row}</div>
    <div style="position:absolute;left:30px;right:30px;bottom:30px;height:100px;border-radius:34px;background:rgba(255,255,255,.55);display:flex;gap:30px;align-items:center;padding:0 30px">{icons_row}</div>
  </div>
</div>
""")

# ---------------------------------------------------------------- 7. Day 6: the #PawPixelTwin challenge (1080x1350)
page("day6-challenge", 1080, 1350, f"""
<div class="dots" style="position:absolute;inset:0"></div>
{brand(60, 56)}
<div class="pixel" style="position:absolute;left:60px;top:150px;font-size:56px;color:var(--coral)">#PawPixelTwin</div>
<h1 style="position:absolute;left:60px;top:220px;font-size:92px;width:960px">Show us your<br>pet's twin.</h1>
<div class="sticker" style="position:absolute;left:60px;top:480px;width:440px;height:500px;padding:0;overflow:hidden;background:var(--paper);border-style:dashed;border-width:4px">
  <img class="px" src="{A}/icon-camera.png" style="position:absolute;left:170px;top:150px;width:100px;opacity:.5">
  <div class="body" style="position:absolute;left:0;right:0;top:290px;text-align:center;font-size:30px">your photo</div>
</div>
<div style="position:absolute;left:512px;top:690px;color:var(--coral);font-size:90px;line-height:56px;text-align:center;font-family:Pixelify">›</div>
<div class="sticker" style="position:absolute;left:580px;top:480px;width:440px;height:500px;padding:0;overflow:hidden;background:var(--paper)">
  <img class="px" src="{A}/room-square-day.png" style="position:absolute;left:-70px;top:-330px;width:580px">
  {pet("dog-golden-idle", 7, 40, 150)}{effect("question", 10, 300, 40)}
</div>
<div class="body" style="position:absolute;left:60px;top:1020px;font-size:34px;width:960px;line-height:1.35">Share your before/after card from the app with <b style="color:var(--ink)">#PawPixelTwin</b>. We feature our favourites every Sunday.</div>
<div class="key" style="position:absolute;left:60px;top:1200px;padding:18px 36px;font-size:32px"><img class="px" src="{A}/icon-share-white.png" style="width:30px">Share → Before/after</div>
""")

# ---------------------------------------------------------------- 8. Day 7: private by design, good night from Naga (1080x1350)
page("day7-goodnight", 1080, 1350, f"""
<img class="px" src="{A}/room-portrait-night.png" style="position:absolute;left:0;top:0;width:1080px">
<div style="position:absolute;inset:0;background:linear-gradient(180deg, rgba(30,27,46,.78) 0%, rgba(30,27,46,.25) 45%, rgba(30,27,46,0) 70%)"></div>
{brand(60, 56, dark=True)}
<h1 style="position:absolute;left:60px;top:160px;font-size:84px;width:960px;color:white">No ads.<br>No account.<br>Your photo never<br>leaves your phone.</h1>
{pet("cat-tabby-asleep", 9, 300, 730)}{effect("zzz", 10, 660, 690)}
<div class="sticker" style="position:absolute;left:60px;top:1150px;width:960px;padding:24px 34px;display:flex;align-items:center;gap:22px;border-radius:30px">
  <img class="px" src="{A}/icon-lock.png" style="width:44px"><div><div class="sub" style="font-size:32px">Private by design. Made in Naga City.</div><div class="body" style="font-size:26px">The pixel pet is drawn on your phone. Nothing is uploaded.</div></div>
</div>
""")

# ---------------------------------------------------------------- 9. Launch poster (1080x1920: stories, and print at A-sizes)
page("poster", 1080, 1920, f"""
<img class="px" src="{A}/room-story-day.png" style="position:absolute;left:0;top:0;width:1080px">
<div style="position:absolute;inset:0;background:linear-gradient(180deg, rgba(255,244,230,.95) 0%, rgba(255,244,230,.9) 38%, rgba(255,244,230,0) 62%)"></div>
{brand(60, 70)}
<h1 style="position:absolute;left:60px;top:200px;font-size:118px;width:960px">Your real pet,<br>in pixels.</h1>
<div class="body" style="position:absolute;left:60px;top:500px;font-size:38px;width:900px;line-height:1.35">One photo. A pixel pet that looks like yours, lives on your home screen, and gets hungry when you skip dinner.</div>
<div style="position:absolute;left:60px;top:700px;display:flex;flex-direction:column;gap:18px;font-size:34px;font-weight:800">
  <div style="display:flex;gap:16px;align-items:center"><img class="px" src="{A}/icon-camera.png" style="width:40px">Snap a photo, meet the twin</div>
  <div style="display:flex;gap:16px;align-items:center"><img class="px" src="{A}/icon-bowl.png" style="width:40px">Care meters for feeding, water, walks, meds</div>
  <div style="display:flex;gap:16px;align-items:center"><img class="px" src="{A}/icon-lock.png" style="width:40px">No ads, no account, nothing uploaded</div>
</div>
{pet("cat-tabby-idle", 12, 240, 1130)}{effect("heart", 10, 700, 1040)}
<div class="sticker" style="position:absolute;left:60px;top:1640px;width:960px;padding:26px 34px;display:flex;align-items:center;gap:28px;border-radius:34px">
  <div style="width:150px;height:150px;border:4px dashed var(--sand);border-radius:20px;display:grid;place-items:center;color:var(--ink2);font-weight:800;font-size:22px;text-align:center">QR<br>code</div>
  <div><div class="sub" style="font-size:40px">Free on Google Play</div><div class="body" style="font-size:28px">iOS coming soon · Made in Naga City</div></div>
</div>
""")
