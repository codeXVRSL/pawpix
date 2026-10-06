#!/usr/bin/env python3
"""Assembles the PawPixel Pet Maker web page (Phase 0 likeness test + waitlist).

    ./gradlew :core:jsBrowserProductionLibraryDistribution   # builds the shared engine for the web
    python3 web/build_web.py core/build/dist/js/productionLibrary/PawPixel-core.js

Output goes to web/dist/: the page, the pet-detector model (as base64 text) and the ML runtime.
Get u2netp.onnx from https://github.com/danielgatis/rembg/releases/download/v0.0.0/u2netp.onnx and
ort-wasm-simd-threaded.wasm from the onnxruntime-web 1.20.1 npm package (dist/), and put both in web/.
"""
import base64, pathlib, sys

here = pathlib.Path(__file__).parent
core_js = pathlib.Path(sys.argv[1]).read_text()
dist = here / "dist"; dist.mkdir(exist_ok=True)
u2 = (here / "u2.js").read_text().replace("if (typeof module !== 'undefined') module.exports = { u2Input, u2Mask };", "")
page = (here / "page.template.html").read_text()
page = (page.replace("/*PAWCORE*/", core_js.replace("</script", "<\\/script"))
            .replace("/*SAMPLE_JPEG*/", (here / "sample-photo.b64.txt").read_text().strip())
            .replace("/*SAMPLE_MASK*/", (here / "sample-mask.b64.txt").read_text().strip())
            .replace("/*U2JS*/", u2))
(dist / "pawpixel-pet-maker.html").write_text(page)
model = here / "u2netp.onnx"
if model.exists():
    (dist / "u2netp.onnx.b64.txt").write_text(base64.b64encode(model.read_bytes()).decode())
wasm = here / "ort-wasm-simd-threaded.wasm"
if wasm.exists():
    (dist / wasm.name).write_bytes(wasm.read_bytes())
# The lost-pet share page (PawPixel's "Lost and Found"): a finder without the app sees the alert.
# Needs the server's URL and anon key, so it's written only when they're set.
import os
url, anon = os.environ.get("PAWPIXEL_SUPABASE_URL", ""), os.environ.get("PAWPIXEL_SUPABASE_ANON_KEY", "")
if url and anon:
    (dist / "lost.html").write_text((here / "lost.template.html").read_text().replace("/*SUPABASE_URL*/", url.rstrip("/")).replace("/*ANON_KEY*/", anon))
else:
    print("note: PAWPIXEL_SUPABASE_URL / PAWPIXEL_SUPABASE_ANON_KEY not set; lost.html (the lost-pet share page) not written")
print("Wrote", dist)
