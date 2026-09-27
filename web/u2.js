// Shared by the page and this test: U2-Net-p preprocessing and mask extraction (as in rembg).
function u2Input(rgba320) {           // rgba320: Uint8ClampedArray 320*320*4
  const N = 320 * 320, t = new Float32Array(3 * N);
  let max = 1; for (let i = 0; i < N; i++) max = Math.max(max, rgba320[i*4], rgba320[i*4+1], rgba320[i*4+2]);
  const mean = [0.485, 0.456, 0.406], std = [0.229, 0.224, 0.225];
  for (let i = 0; i < N; i++) for (let c = 0; c < 3; c++) t[c*N + i] = (rgba320[i*4+c] / max - mean[c]) / std[c];
  return t;
}
function u2Mask(out320) {             // Float32Array 320*320 -> normalised 0..1
  let mi = Infinity, ma = -Infinity; for (const v of out320) { if (v < mi) mi = v; if (v > ma) ma = v; }
  const r = new Float32Array(out320.length), d = (ma - mi) || 1;
  for (let i = 0; i < r.length; i++) r[i] = (out320[i] - mi) / d;
  return r;
}
if (typeof module !== 'undefined') module.exports = { u2Input, u2Mask };
