# Launch art, 2026

The design idea is in `PHILOSOPHY.md` ("Kept Light"). Everything here is drawn from the real app: the pets come from the sprite engine (with their photo coats, so the tabbies keep their stripes), and the store frames hold real screenshots of the app taken by CI.

| File | Size | Use |
|---|---|---|
| `store/ios/01–05` | 1320 × 2868 | App Store screenshots (6.9-inch iPhone) |
| `store/android/01–05` | 1080 × 1920 | Google Play phone screenshots |
| `poster.png` | 2160 × 2700 | Launch poster (4:5; prints well at A3 or 8 × 10 in) |
| `post-01-same-face.png` | 1080 × 1350 | Social post: photo to pixel pet |
| `post-02-moods.png` | 1080 × 1350 | Social post: mood follows care |
| `post-03-household.png` | 1080 × 1350 | Social post: dogs, cats and bunnies |

The five store frames tell one story in order: the pixel twin, real care, the home-screen widget, Lost and Found, sharing. Frame 03 places the real widget renders on a plain home screen; the frosted squares stand in for other apps.

The poster says "Coming soon". Change that line in `build.py` once the app is in the stores.

## Rebuilding

```
python3 marketing/launch-2026/build.py marketing/launch-2026/sprites <screenshots dir>
```

`sprites/` holds the 1× engine exports. The screenshots dir needs these files from the CI results branches (`ci-results/<branch>-ios` and `-android`, see CLAUDE.md), named with an `ios-` or `android-` prefix:
- **iOS:** `02-your-pixel-pet`, `07-after-done`, `25-lost-alert`, `13-share-card`, `widgets/widget-home-medium-fine-light`, `widgets/widget-home-large-hungry-light`
- **Android:** `e2e/02-your-pixel-pet`, `e2e/06-after-done`, `e2e/14-home-screen-widget-a-beat-later`, `e2e/27-alert`, `e2e/21-pet-id-card`

Fonts are the app's own (Pixelify Sans, Nunito) plus DM Mono for the small labels (all OFL).
