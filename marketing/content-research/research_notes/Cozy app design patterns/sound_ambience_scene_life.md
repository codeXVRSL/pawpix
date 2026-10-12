# Sound design and ambient "life" in cozy games and pet/companion apps

Scope: concrete, developer-applicable findings for a pixel-art pet living in a lit dollhouse room on a phone (PawPixel already has a day/night room, a window with the sky, weather and seasonal pieces). Four key questions below, plus a short "defaults for PawPixel" inference list under each.

Research caveat: the sandbox proxy only allowed full-page fetches from developer.apple.com and developer.android.com. Everything else (GDC, Audiokinetic, Nookipedia, Stardew wiki, Pok Pok, Finch reviews, forums) is cited from search-result excerpts, so quotes from those are short and I flag where a number came only from a snippet. Where I could not find a developer-stated value I say so in Gaps rather than invent one.

## Key question 1: Which idle and ambient behaviours do Nintendogs, Tamagotchi, Pou, Finch and Pok Pok use, and at what cadence?

### Takeaway
None of the five products publishes its timers, but their behaviours cluster into the same four layers: (1) an always-on physiological loop (breathing, blinking, eyes that track the finger), (2) rare one-shot fidgets picked at random, (3) reactions to the real clock (hunger/dirt accrue from elapsed time, a daily greeting, happiness when you return), and (4) explicit touch reactions (belly rub -> leg kick, poke foot -> pull back). Animation-craft sources put the physiological loop at roughly 3-4 s per breath and 3-6 s between blinks, with fidgets every 30-60 s and larger "wake-up" events on the order of 40-85 s; forum developers who ship pets split blink and idle onto separate randomized timers.

### Cited Findings

Nintendogs (DS) and nintendogs + cats (3DS)
- Reviewer description of ambient dog behaviour: the puppies "chew on each others' ears, get scared of unfamiliar objects, howl when sirens go by, and run away from you to go chew on something"; sound scored 8/10 with "appropriate barks and yelps, varying by breed, and there are also some good background sounds that they react to at times" — [Nintendo World Report review](https://nintendoworldreport.com/review/4394/nintendogs-nintendo-ds)
- Touch reactions in the original marketing copy: "Rub the puppy's belly and he might kick his leg; poke his foot and he'll pull it back in surprise" — [product listing quoting Nintendo's description](https://www.ebay.fr/itm/156154248481) (secondary; the same copy appears on retail listings)
- Needs follow the real clock: "the DS's internal clock and calendar make the dog grow hungrier or dirtier based on the elapsed time" — [Game Quarter](https://www.gamequarter.be/game/nintendogs-labrador-friends/); neglect makes the dog "unhappy or even run away" — [Giant Bomb](https://giantbomb.com/wiki/Games/Nintendogs)
- Eye tracking as a liveliness technique (nintendogs + cats): the 3DS team "built real eyeballs that follow moving objects, whereas the earlier game only placed textures on the faces"; Miyamoto noted dogs turn the whole head to look while cats move only their eyes, so the behaviour was built separately; pupils "narrow in bright places and become round in the dark" — [Iwata Asks, nintendogs + cats, Vol. 4](https://iwataasks.nintendo.com/interviews/3ds/how-nintendo-3ds-made/3/2); adding cats "doubled the amount of work" — [Nintendo World Report on the interview](https://www.nintendoworldreport.com/news/25096/nintendogs-started-on-gamecube-16-versions-initially-for-3ds)

Tamagotchi (Bandai)
- Hardware-defined cadence: three buttons, a small non-backlit LCD; care "from egg-hatching to adulthood, around 12 calendar days on average"; children were "perpetually 'on call' to monitor snack time, meal time, play time"; neglect "triggered a warning beep"; the lifecycle "could not be paused" — [The Strong, National Museum of Play](https://www.museumofplay.org/games/tamagotchi/)
- Meters are four hearts each for hunger and happiness; "If all of the hungry or happy hearts are empty, the Tamagotchi will beep at you" — [Tamagotchi Wiki (fan)](https://tamagotchi.fandom.com/wiki/Care)
- Random attention calls exist as a mechanic: "Sometimes he will beep at you for no reason. If this happens, you must discipline him" — [Snap! forum guide](https://forum.snap.berkeley.edu/t/help-with-tamagotchi/19645?page=3) (low-authority fan source; included because it matches the well-known discipline mechanic)
- Design origin: a "raising and caring" game concept from a pet-loving employee; deliberately "rough-cute" hand-drawn look — [Uniqlo 30-years retrospective](https://www.uniqlo.com/sg/en/news/topics/2025121101/) (promotional, secondary)

Pou (Zakeh)
- Touch idle: Pou "reacts to touch — its eyes will follow your finger"; most actions are drag-object-onto-pet; "Pou repeats what the user says" — [SourceForge app listing](https://sourceforge.net/app/pou-tm/iphone/) (secondary)
- Four meters (Food, Health, Fun, Energy) drive happiness — [AndroidAyuda guide](https://en.androidayuda.com/games/lists/virtual-pet-games/)

Finch (Finch Care)
- The bird "reacts to completed tasks, with joyful animations or messages"; the app "works like a digital Tamagotchi" — [Pratt IXD design critique](https://ixd.prattsi.org/2025/09/design-critique-finch-ios-app-2/)
- The bird "greets you with a little message each day, sometimes silly, sometimes thoughtful" and "will still be happy to see you return even after you've been away"; a home-screen widget works as "a passive reminder" that "keeps the bird visible without requiring you to open the app" — [WhistleOut review](https://www.whistleout.com/CellPhones/Apps/finch-self-care-app-review), [Bustle review](https://www.bustle.com/wellness/finch-app-review-features-price)
- Finch's own framing: the design work "straddles the line between wellness and gaming" — [Finch product designer job post](https://dynamitejobs.com/company/finch/remote-job/product-designer-1)
- Reviewer framing: "a kindness engine rather than a streak-based tracker" — [Screenwise](https://screenwiseapp.com/media/finch-app)

Pok Pok Playroom (Snowman spin-out)
- "no levels, no timers, and no 'You Win!' screens"; "soft colors, hand-drawn line art, and actual recorded sounds in place of flashing lights" — [Screenwise](https://screenwiseapp.com/media/pok-pok-playroom-app)
- "each of their sounds is hand recorded by their sound designer, who searches his neighborhood and local thrift stores for objects to record" — [Pok Pok blog](https://playpokpok.com/blog/?p=20)
- Mostly language-free UI, multi-touch for two hands, a minimalist style that "helps children feel calm and lets them focus on exploration"; designers, developers and sound engineers prototype together and test with kids — [Sketch case study](https://www.sketch.com/blog/pok-pok/)
- Real wooden blocks and toys were recorded to recreate the block sounds — [Mac4Ever](https://www.mac4ever.com/gaming/167647-pok-pok-playroom-propose-un-jeu-de-cube-virtuel-alto-s-adventure-alto-s-odyssey) (secondary, French)

Toca Boca (reference for "no pressure" ambience)
- "doesn't make games: it makes digital toys" with no "pre-defined set of rules and objectives" — [Kill Screen](https://www.killscreen.com/what-designers-can-learn-about-play-children/); internal motto "no winning, no losing, no stress, no high scores, no rules" — [reposted Toca Boca story](https://collect.readwriterespond.com/toca-boca-story)

Cadence numbers (animation craft and physiology, not from the pet apps)
- Relaxed breathing "approximately 15-20 breaths per minute for a relaxed character, 20-25 for an alert one"; chest travel "1-2 cm"; secondary weight shift "over a 4-8 second cycle"; one-shot fidgets "about every 30-60 seconds" — [Mocap Online idle-animation guide](https://mocaponline.com/blogs/mocap-news/idle-animation-game-dev-guide) (15-20 breaths/min = 3-4 s per breath; that conversion is mine)
- Offset the timing of belly, chest, shoulders and head; check that the exhale is quicker than the inhale — [Animation Mentor](https://www.animationmentor.com/?p=87282)
- Human spontaneous blink rate measured at 16.04 blinks/min (SD 6.4), normal range 6-30/min — [PMC study](https://pmc.ncbi.nlm.nih.gov/articles/PMC9899223); popular figure 15-20/min, i.e. one blink every 3-4 s — [Healthline](https://www.healthline.com/health/how-many-times-do-you-blink-a-day); blink duration 0.1-0.4 s — [Soma Tech](https://www.somatechnology.com/blog/thursday-thoughts/fast-average-blink)
- Shipping-pet patterns from developers: a fixed loop "is bound to feel artificial once the eye catches on to the repetitive pattern", so add "purposeful randomness" to when idles fire — [DEV Community, "Programming natural idle character animations"](https://dev.to/arkfuldodger/programming-natural-idle-character-animations-4l0g); one AGS developer runs a ~3 s blink timer and a separate ~10 s timer for other idles, and the timers must reset even when the character is busy — [Adventure Game Studio forum](https://www.adventuregamestudio.co.uk/forums/beginners-technical-questions/solved-randomized-idle-blinking); a GDevelop dev blinks every 2.5 s as an overlay on the idle (and notes overlays misalign when the character moves) — [GDevelop forum](https://forum.gdevelop.io/t/solved-making-my-character-blink/46268); a randomized-blink bug came from re-rolling the delay every frame (about 40 times a second) instead of after the blink plays — [AGS forum](https://www.adventuregamestudio.co.uk/forums/index.php?msg=132625)
- A Unity avatar script picks each "wake-up" idle interval at random in roughly 40-85 s — [AnimateCharacter.cs in a public C# corpus](https://huggingface.co/datasets/fasterinnerlooper/lcc_csharp/viewer/default/train?p=987) (weak source; one codebase)
- Pause sprite timers while the document is hidden and resume from elapsed time rather than replaying missed frames — [chat-pet sprite guideline](https://tessl.io/registry/skills/github/posit-dev/positron/chat-pet-sprite-creation)

### Inferences
- A pet's "alive" baseline is cheap: breathing at a 3-4 s cycle with the exhale shorter than the inhale, blinks on their own timer at 3-6 s with a 0.1-0.4 s close, and occasional double-blinks; eyes (or the head, for a dog) tracking a touch point is the single most-cited liveliness trick (Nintendogs + cats eyeballs, Pou's finger-following).
- Species-specific tracking is a free win for PawPixel: cats move eyes only, dogs turn the head (Miyamoto), rabbits could flick an ear toward the touch instead.
- Fidgets (ear twitch, tail swing, stretch, yawn, sniff, look at the window) belong on a separate randomized timer in the 10-20 s band for small ones and 30-60 s for big ones, never re-rolled per frame; the pet's existing uneven timing in `Animator`/`PetBrain` fits this.
- Clock-driven state (hungrier after hours away, a greeting on return, a "happy to see you" reaction) is what every pet app above shares and is what distinguishes a companion from a screensaver; PawPixel already has CareEngine/MoodEngine, so the ambience layer should visibly reflect them (sleep when the lamp is off, sit by the bowl when a feed is due).
- Tamagotchi's random "beep for no reason" shows that occasional unprompted attention bids feel alive, but the beep is the part modern cozy apps dropped; a silent speech bubble or glance toward the camera is the equivalent.

### Gaps
- No developer-published timer values for Nintendogs, Tamagotchi, Pou, Finch or Pok Pok idle events were found; Iwata Asks covers eye tracking, not cadence.
- No source on Finch's haptic or sound micro-interactions; reviews describe animations and messages only.
- Pok Pok's named designers' rationale was not found in a primary interview; the hand-recorded-sound statement is from their blog, the rest from reviews and a Sketch case study.

## Key question 2: What sound palette (instruments, pitch, length) and mix levels make UI feedback feel soft? When do cozy apps stay silent?

### Takeaway
Cozy titles converge on real, hand-recorded or acoustic sources (Unpacking's 14,000 foley files, Pok Pok's thrift-store objects, Stardew's gentle seasonal instruments), short tap sounds derived from one family, music that is allowed to stop (Stardew fills gaps with ambient soundscapes; Animal Crossing scores "the space between the sounds"), and near-silence rather than digital silence. On iOS the rule is non-negotiable: non-essential app sounds must obey the Ring/Silent switch via the Ambient or Solo Ambient audio category.

### Cited Findings

Unpacking (Witch Beam; audio by Jeff and Angela van Dyck)
- The GDC 2022 session "'Unpacking': The Fun Behind the Foley" covered "the unexpected complexity" of producing the foley, "dealing with material and surface interactions, organizing the audio into Reaper for peak efficiency"; the game "leverages over 14,000 foley audio files" — [GDC session preview](https://gdconf.com/news/discover-fun-behind-foley-unpacking-gdc-2022-session); the "14,000 foley audio files" tweet went viral in November 2021 — [GDC Vault listing](https://gdcvault.com/play/1027728/-Unpacking-The-Fun-Behind)
- The team "initially flirted with the idea of using chip-tune effects but chose a hyper-realistic style instead" — [Game Developer write-up of the talk](https://www.gamedeveloper.com/marketing/auditory-tales-from-the-making-of-zen-puzzler-unpacking)
- The van Dycks' own post says the production created "more than 14,000 WAV files with a small team" — [Audiokinetic blog, "Unpacking the Fun Behind the Foley"](https://blog.audiokinetic.com/en/unpacking-the-fun-behind-the-foley/); the full 54-minute talk is embedded at [A Sound Effect](https://www.asoundeffect.com/unpacking-game-audio/)
- The music represents "various phases of the protagonist's life" (fixed per level, not reactive) — [A Sound Effect](https://www.asoundeffect.com/?p=635063)

Animal Crossing (Nintendo; sound director Kazumi Totaka)
- Hourly music: "Every hour, there's a new song that's about maybe a minute or a minute and a half long. That's perfectly, seamlessly loopable so that it can play for the entire hour"; at the top of the hour "the current song that's playing will conclude, the clocktower in the village plaza will chime, then the next song will begin"; mornings "typically upbeat", evenings "more relaxing"; Totaka reportedly said the music "scores the space between the sounds, the sounds of gameplay" — [Gameplay.co, "Animal Crossing: New Harmonies"](https://gameplay.co/animal-crossing-music-new-horizons-scruffy-youtube/) (secondary; the Totaka line is a paraphrase)
- Rain: "rainy weather switches the hourly music to variants with a light ringing noise" — [Nookipedia, Rain](https://nookipedia.com/wiki/Rain); in New Leaf "bells replacing one of the usual instruments during rain" — [ACNL wiki](https://acnl.fandom.com/wiki/Weather?oldid=8581); in New Horizons "a vertical layer system removes percussion from the background music when it rains allowing the rain to stand out" — [Game Music (commentary)](https://gamemusic.net/?p=20207) (single-source claim, not a developer statement)
- Ambience is positional and material: "wind blowing through trees at their exact positions and distinct sounds for each terrain type" — [Audiokinetic blog on ACNH accessibility](https://blog.audiokinetic.com/animal-crossing-is-playable-by-the-totally-blind/) (player analysis); Totaka on New Leaf: river and insect sounds were expanded and "sound effects and the code driving it all play equally important roles" — [Famitsu via Animal Crossing World](https://animalcrossingworld.com/2012/11/highlights-from-an-interview-with-the-developers-of-animal-crossing-new-leaf/)
- Optional pure-ambience modes exist: Happy Home Paradise "soundscapes" add ambient noise such as ocean or rain — [Nintendo Life](https://www.nintendolife.com/guides/animal-crossing-new-horizons-soundscapes-how-to-unlock-all-24-soundscapes-in-happy-home-paradise-dlc)

Stardew Valley (ConcernedApe, primary)
- "each season has multiple songs that play randomly", "seasonal songs don't loop over and over", and "ambient seasonal soundscapes" fill the gaps between songs; the post lists the mood and instruments per season — [Stardew Valley Dev Update #16](https://stardewvalley.net/dev-update-16/)
- The wiki's track table tags each song with season and weather conditions — [Stardew Valley Wiki, Music](https://stardewvalleywiki.com/Music)

A Short Hike (Adam Robinson-Yu; music by Mark Sparling)
- Sparling "suggested creating a dynamic soundtrack that evolves as you wander around the island" — [PlayStation Blog by the developer](https://blog.playstation.com/2021/08/05/crafting-a-tiny-open-world-a-look-behind-the-scenes-at-the-creation-of-a-short-hike/); the score "changes based on world events such as the weather, or player actions such as flying" — [Wikipedia](https://en.wikipedia.org/wiki/A_Short_Hike); it is "layered on top of ambient background noises, with natural sounds taking over between areas" and "grows and chills with the rising altitude and added synths" — [Shacknews review](https://shacknews.com/article/127854/a-short-hike-review-a-slice-of-animal-life?amphtml=1); Sparling was inspired by Animal Crossing: New Leaf for at least one track — [GoNintendo quoting Polygon](https://gonintendo.com/comments/13899)

Pok Pok / Toca Boca
- Pok Pok: hand-recorded sounds, no timers/points, "soft hand-recorded sounds" contrasted with apps that "rely on flashing lights, reward stars, and frenetic music" — [Screenwise](https://screenwiseapp.com/media/pok-pok-playroom-app), [Pok Pok blog](https://playpokpok.com/blog/?p=20); 9to5Mac calls out "delightful sound effects" — [9to5Mac hands-on](https://9to5mac.com/2021/05/20/hands-on-with-pok-pok-playroom-kids-app/)

UI sound craft guidance (indie/vendor blogs; snippets only)
- Taps and clicks around 30-150 ms, notifications up to about 500 ms; derive every UI sound from one source palette (design one confirm sound, then pitch/shorten/filter it for the rest); rising tones read positive, falling tones negative; errors "soft and recoverable rather than harsh"; UI sounds "should sit lower in the mix than instinct suggests"; reserve bigger sounds for rare moments because "if every cue shouts, nothing does" — [Bugnet, UI sound design for indie games](https://bugnet.io/blog/ui-sound-design-for-indie-games) and [Violet Recording, How to design UI and app sounds](https://violetrecording.com/how-to-design-ui-sounds/) (the 30-150 ms and 500 ms figures came from the search excerpt of these two guides; I could not open them to confirm which one states each number)
- "UI's most important job is making sure the player knows what they're doing and what effect it has, and audio plays a big part in how interacting with the UI feels" — [Audiokinetic, Approaching UI audio from a UI design perspective](https://www.audiokinetic.com/en/blog/approaching-ui-audio-ui-design-perspective-1)
- GDC 2025, Jonathan Bell (Formosa Interactive): plan UI audio deliberately, limit variation so feedback stays unambiguous, keep cues readable in busy mixes — [GameDev.net summary](https://gamedev.net/news/5816-what-was-that-noise-mastering-creative-ui-sound-design-with-intention/)
- Give players the option to mute UI sounds; prefer visual cues first and use sound for critical actions — [Wayline](https://www.wayline.io/blog/muting-ui-sounds-transform-gaming)

Silence
- "near-silence usually beats true digital silence, since room tone, wind, and distant ambience keep the world alive while the music rests"; true silence is "its own extreme effect, so spend it maybe once" — [Bugnet, The case for silence in game audio](https://bugnet.io/blog/the-case-for-silence-in-game-audio)
- Akira Yamaoka: a sound designer's role includes "knowing when to use silence" — [PC Gamer, The sound of silence](https://www.pcgamer.com/the-sound-of-silence/)

Platform rules on silence and volume (primary)
- Apple HIG: "People switch a device to silent when they want to avoid being interrupted by unexpected sounds... they also want to silence nonessential sounds, such as keyboard clicks, sound effects, game soundtracks, and other audible feedback. When a device is in silent mode, it plays only the audio that people explicitly initiate, like media playback, alarms, and audio/video messaging." Audio categories: Solo ambient ("a game with a soundtrack"; responds to the silence switch, doesn't mix), Ambient ("lets people play music from another app during gameplay"; responds to the silence switch, mixes with other sounds), Playback (doesn't respond to the switch). "don't make people stop listening to music from another app if you don't need to." "Adjust levels automatically when necessary — don't adjust the overall volume." — [Apple HIG, Playing audio](https://developer.apple.com/design/human-interface-guidelines/playing-audio)
- Apple HIG on multi-channel feedback: "when you provide feedback using color, text, sound, and haptics, people can receive it whether they silence their device, look away from the screen, or use VoiceOver"; "Alerts can lose their impact if you use them too often" — [Apple HIG, Feedback](https://developer.apple.com/design/human-interface-guidelines/feedback)
- An app "cannot programmatically determine the state of the switch" (Apple QA1417); overriding the switch without cause risks rejection — [Apple Developer Forums thread](https://developer.apple.com/forums/thread/11359), [Stack Overflow mirror](https://uq.stanford.edu/answer/39fa64e8-6b26-4664-919b-8071fbdebe94) (secondary)

### Inferences
- Palette for PawPixel: acoustic, recorded or softly synthesized sources (wood, felt, paper, a small bell, a muted kalimba or celesta note) rather than chiptune bleeps; Unpacking explicitly rejected chip-tune even for a pixel-ish game, and Pok Pok's whole brand is "real object" sound.
- One sound family: design a single ~80-120 ms "confirm" tap and derive back/cancel (falling minor third, slightly shorter), select tick (filtered, 30-50 ms), and "task done" (two rising notes, 300-500 ms, plus a soft bell for streaks). Mix taps 6-12 dB below ambience peaks; this follows the "lower than instinct" advice, not a measured standard.
- Silence policy: no soundtrack at all by default; a soft room-tone ambience (lamp hum is too much; use window weather and clock-like ticks sparingly) that can stop; the Animal Crossing hour chime idea maps well to PawPixel's day/night sky changes (a tiny chime at sunrise/sunset only, once).
- Platform: iOS session category Ambient (mixes with the user's own music, obeys the switch); on Android use the STREAM_MUSIC/USAGE_GAME attributes and respect the ringer mode and the app's own sound toggle; haptics should carry the meaning when sound is off (Apple's multi-channel guidance).

### Gaps
- No primary source gives dB mix levels for UI sounds; the "30-150 ms" and "500 ms" durations are from indie guides (snippet-level).
- The Unpacking Audiokinetic post and A Sound Effect talk could not be fetched, so per-object variation counts, surface logic and loudness decisions remain unquoted; the GDC Vault recording is the primary source to watch.
- No Totaka interview on New Horizons specifically; hourly-loop lengths and the percussion-removal claim are from commentary.

## Key question 3: How are weather, time of day and seasons shown in the scene in Animal Crossing / Stardew Valley, and what UI tints accompany them?

### Takeaway
Both games treat the clock and weather as whole-scene modifiers: Animal Crossing changes the music variant, ambience and sky by hour and by rain/snow, with a 5 AM day boundary; Stardew darkens the outdoor scene gradually from early evening (fully dark around 8 PM depending on season), changes window and lamp art indoors at nightfall, lets players tune overlay strength (snow transparency slider), and lets a rare weather (Green Rain) tint even indoor rooms. I found no developer-published colour values for these tints.

### Cited Findings

Animal Crossing: New Horizons
- The in-game day resets at 5 AM, separate from the calendar date change at midnight — [Nookipedia, New day](https://nookipedia.com/wiki/New_day); the sun is "just off-screen, lighting the world", visible on screen only on January 1st; dawn/dusk windows "vary by hemisphere and season" — [KeenGamer](https://www.keengamer.com/articles/news/why-you-see-the-sun-on-january-1st-in-animal-crossing-new-horizons/) (secondary)
- Weather per island is deterministic from a seed covering "clouds, rain, snow, meteor showers, rainbows, auroras, and wind power"; a fan tool (MeteoNook, by dataminer Ninji) reads it hour by hour — [Animal Crossing World](https://animalcrossingworld.com/2020/06/predict-meteor-showers-rainbows-auroras-in-animal-crossing-new-horizons-with-weather-forecaster-tool/)
- Rain changes the music to variants "with a light ringing noise" — [Nookipedia, Rain](https://nookipedia.com/wiki/Rain); hourly music loops of ~1-1.5 min with a chime at the hour and a mood arc from upbeat mornings to relaxing evenings — [Gameplay.co](https://gameplay.co/animal-crossing-music-new-horizons-scruffy-youtube/)
- Positional wind in trees and terrain-specific footsteps make the scene readable by ear alone — [Audiokinetic blog](https://blog.audiokinetic.com/animal-crossing-is-playable-by-the-totally-blind/)

Stardew Valley
- "the screen doesn't get dark until about 8pm, depending on the season"; night for fishing starts at 6 PM; outdoors darkness "builds gradually rather than arriving all at once"; indoors "nightfall brings a change in the windows and lamps, and rooms without enough light sources grow dark"; a room entered before nightfall "stays bright until you leave and re-enter" — [Stardew Valley Wiki, Day Cycle](https://www.stardewvalleywiki.com/Day_Cycle)
- Sunset is fixed at 6:00 PM every day in vanilla (a mod that changes it) — [Nexus Mods, Dynamic Dusk](https://www.nexusmods.com/stardewvalley/mods/40783) (secondary; "sunset" vs "full darkness" are different events, so this does not contradict the wiki)
- An ambient-light mod's changelog says it "no longer applies the standard stardew valley night time color scheme", confirming the base game applies a fixed night colour scheme as an overlay — [Nexus Mods changelog](https://www.nexusmods.com/stardewvalley/mods/4639?tab=logs); a developer-forum reply (not ConcernedApe) says Stardew almost certainly applies a dimming filter over tiles at night rather than night tile variants — [itch.io forum](https://itch.io/post/13924352) (speculation)
- Snow is an adjustable overlay: "snow transparency can be changed in the Options Menu" — [Stardew Valley Wiki, Snow](https://stardewvalleywiki.com/Snow); Green Rain's "translucent green visual effect is visible even when indoors" — [Stardew Valley Wiki, Green Rain](https://stardewvalleywiki.com/Green_Rain); weather "affects the types of fish that can be caught, as well as villager dialogues and behavior" — [Stardew Valley Wiki, Weather](https://stardewvalleywiki.com/Weather)
- Each season has its own randomly chosen songs with ambient soundscapes between them — [Dev Update #16](https://stardewvalley.net/dev-update-16/)

Pixel-art ambience craft
- Restrict a cozy scene to "16-32 colors total"; use "a cool base with warm hues saved for light sources like lamps" so lit spots draw the eye — [BudgetPixel, cozy pixel scene in 5 steps](https://budgetpixel.com/blog/how-to-create-a-cozy-pixel-art-scene-in-5-steps)
- Ambient dust can be literal 1x1 pixels that wander (a Phaser plugin does exactly this) — [dust-phaser-plugin on npm](https://npmjs.com/package/dust-phaser-plugin); commercial pixel loop packs layer "rain, steam, flickering lights, fireflies, snow, lanterns" at low base resolution scaled by whole numbers — [Pixel Nights loop pack listing](https://www.getly.store/product/pixel-nights-animated-lofi-pixel-art-loops-4k-phone-obs-muijfftx) (product listing)
- Keep ambient motion slow so pixel edges survive — [Kling guide](https://kling.ai/topics/animate-pixel-art-ai) (low-authority AI-vendor page; matches common practice)

### Inferences
- Treat time of day as a scene-wide light map multiply plus a warm/cool bias, not a UI-wide tint: Stardew only tints the world; its HUD stays constant, and Animal Crossing's hour change is announced by sound (chime) and music, not by UI colour. For PawPixel, keep panels untinted and let the room's `Room` light map carry evening warmth, with the window beam fading and the lamp pool taking over.
- Weather belongs in the window and in one layer of the room (rain streaks on glass, a dimmer beam, snow transparency low); Stardew's "rare weather tints indoors" shows a full-room tint is acceptable for special events (e.g. a birthday glow) but not for daily rain.
- Nightfall indoors should be a discrete art swap (window art, lamp lit) plus a gradual outdoor darkening; Stardew's "stays bright until you re-enter" quirk is a reason PawPixel should animate the transition on-screen over 1-2 minutes instead.
- Seasonal music is optional; seasonal ambience (window sounds: birds in spring, rain, crickets at night, wind in winter) can replace it, mirroring Stardew's soundscape gap-filling.

### Gaps
- No developer-stated colour/tint values for Animal Crossing or Stardew day/night or weather overlays; Nookipedia's time/weather pages and the Stardew wiki's Day Cycle page could not be fetched directly.
- No source describing Animal Crossing's UI tint changes (if any) by time of day.

## Key question 4: What haptic patterns (iOS UIFeedbackGenerator styles, Android HapticFeedbackConstants) do calm apps use for completion versus error?

### Takeaway
Both platforms now document a semantic pair for completion and failure: iOS `UINotificationFeedbackGenerator` `.success` / `.error` (with `.warning`), and Android `HapticFeedbackConstants.CONFIRM` / `REJECT` (API 30+). Both vendors say to use these documented meanings consistently, keep haptics short and tied to a cause, keep frequent haptics very subtle (or none), make them optional, and match their sharpness to the animation and sound they accompany. Apple's standard for a calm app is explicit: "the best haptic experience is one that people may not be conscious of, but miss when it's turned off."

### Cited Findings

Apple (primary, HIG "Playing haptics")
- Documented meanings — Notification: "Success. Indicates that a task or action has completed. Warning. Indicates that a task or action has produced a warning of some kind. Error. Indicates that an error has occurred." Impact: "Light. Indicates a collision between small or lightweight UI objects. Medium... medium-sized or medium-weight UI objects. Heavy... large or heavyweight UI objects. Rigid... hard or inflexible UI objects. Soft... soft or flexible UI objects." Selection: "Indicates that a UI element's values are changing." — [Apple HIG, Playing haptics](https://developer.apple.com/design/human-interface-guidelines/playing-haptics)
- Best practices (same page): "Use system-provided haptic patterns according to their documented meanings"; "If a haptic doesn't reinforce a cause-and-effect relationship, it can be confusing and seem gratuitous"; "match the intensity and sharpness of a haptic with the intensity and sharpness of the animation it accompanies. You can also synchronize sound with haptics"; "Avoid overusing haptics... Often, the best haptic experience is one that people may not be conscious of, but miss when it's turned off"; "In most apps, prefer playing short haptics that complement discrete events"; "Make haptics optional. Let people turn off or mute haptics"; haptic vibration should not disrupt "the camera, gyroscope, or microphone" — [Apple HIG, Playing haptics](https://developer.apple.com/design/human-interface-guidelines/playing-haptics)
- Custom haptics: transient events "feel like taps or impulses" (the Flashlight button), continuous events "feel like sustained vibrations"; sharpness conveys "soft, rounded, or organic" versus "crisp, precise, or mechanical"; intensity is strength; a jump from a tree can be stronger than a jump in place — [Apple HIG, Playing haptics](https://developer.apple.com/design/human-interface-guidelines/playing-haptics)
- Core Haptics (iOS 13+) is the API for custom patterns — [WWDC19 session 520](https://developer.apple.com/videos/play/wwdc2019/520); call `prepare()` before triggering to reduce latency — [Hacking with Swift](https://www.hackingwithswift.com/example-code/uikit/how-to-generate-haptic-feedback-with-uifeedbackgenerator); Apple does not document acceptable values for `impactOccurred(intensity:)` — [search summary of third-party guides](https://playbooks.com/skills/charleswiltgen/axiom/axiom-haptics) (secondary)
- Accessibility: feedback via "color, text, sound, and haptics" reaches people who silence the device, look away, or use VoiceOver — [Apple HIG, Feedback](https://developer.apple.com/design/human-interface-guidelines/feedback)

Android (primary)
- "When it comes to haptic feedback on mobile devices, less is more"; excess vibration "can be annoying and even numbing" and leads users to turn off all haptics — [Android, Haptics design principles](https://developer.android.com/develop/ui/views/haptics/haptics-principles)
- Classification: Clear (crisp, discrete, "button presses"), Rich (needs wide-bandwidth actuators; fallback required), Buzzy (sharp, "leaves a tingling aftereffect"); "given buzzy haptics or none for touch feedback, choose none" — [Android, Haptics design principles](https://developer.android.com/develop/ui/views/haptics/haptics-principles)
- Guidelines: prefer `HapticFeedbackConstants` for covered actions, then `VibrationEffect` predefined effects and `VibrationEffect.Composition` primitives; "Very frequent events (scrolling, moving a text handle) should be very subtle"; "Important events (refreshing, submitting a form) should be stronger than toggling or list scrolling"; be consistent so users learn what a haptic means; "Haptics should be harmonious with animations and sounds. Out-of-sync haptics can feel unsettling, and users may think the actuator is broken"; avoid `createOneShot` / `Vibrator.vibrate(long)` because "a keyclick should last 10-20 ms, but the actuator may ring for another 20-50 ms after a 20 ms input" — [Android, Haptics design principles](https://developer.android.com/develop/ui/views/haptics/haptics-principles)
- Constants and their documented meanings: `CONFIRM` (API 30) "A haptic effect to signal the confirmation or successful completion of a user interaction"; `REJECT` (API 30) "A haptic effect to signal the rejection or failure of a user interaction"; `CONTEXT_CLICK` (API 23) context click; `SEGMENT_TICK` (API 34) switching between choices "items in a list or discrete points on a slider"; `SEGMENT_FREQUENT_TICK` (API 34) "expected to be very soft, so as not to be uncomfortable when performed a lot in quick succession"; `TOGGLE_ON` / `TOGGLE_OFF` (API 34); `GESTURE_START` / `GESTURE_END` (API 30); `DRAG_START` (API 34); `LONG_PRESS`, `VIRTUAL_KEY`, `KEYBOARD_TAP`, `CLOCK_TICK`; `NO_HAPTICS` (API 34) to skip entirely; `FLAG_IGNORE_GLOBAL_SETTING` deprecated in API 33 (so the user's system haptic setting wins) — [Android, HapticFeedbackConstants reference](https://developer.android.com/reference/android/view/HapticFeedbackConstants)
- Use cases: notify of an event, confirm a state change after a user action, delight (scroll feedback, slider snap, "haptics synced with animations, sound, video, or games") — [Android, Haptics design principles](https://developer.android.com/develop/ui/views/haptics/haptics-principles)

Calm-app specifics
- I found no source that documents the exact haptic choices of Finch, Pok Pok or Toca Boca; the only app-level evidence is the general restraint language above and Apple's "may not be conscious of, but miss when it's turned off".

### Inferences
- Completion (care task done): iOS `.success` notification; Android `CONFIRM` (fallback `VIRTUAL_KEY` on < API 30). Pair with the rising two-note sound and the hearts/celebration animation so all three line up in time (Apple and Android both demand sync).
- Error / can't do that (e.g. feeding a pet that is already full, offline map): iOS `.warning` rather than `.error` for soft, recoverable states; Android `REJECT`; never for validation while typing.
- Touching the pet: iOS Impact `.soft` or `.light` (documented as "soft or flexible" / "small or lightweight" objects), Android `CONTEXT_CLICK` or a very light `VibrationEffect.Composition` tick; a long pet-stroke should NOT be a continuous haptic (Apple: long-running haptics "dilute the meaning"; Android: frequent events "very subtle").
- Scrubbing pickers (furniture catalogue, colour swatches): iOS Selection; Android `SEGMENT_TICK` / `SEGMENT_FREQUENT_TICK`.
- No haptic at all for: ambient idle events, pet reactions the user didn't cause, widget updates, screen transitions; Android's "buzzy or none: choose none" and Apple's cause-and-effect rule both argue against it.
- Ship a haptics toggle in Settings (Apple: "Make haptics optional"), default on, and never bypass the system haptic setting (Android deprecated the ignore flag).

### Gaps
- No documented haptic patterns for Finch, Pok Pok, Toca Boca, Nintendogs or Tamagotchi (the latter two predate phone haptics).
- Apple does not publish recommended intensity values; "light" versus "soft" is semantic, not numeric.

## Developer-applicable defaults for PawPixel (synthesis across the four questions)

These are inferences assembled from the cited findings above; the numbers marked "craft" come from animation guides, not from the pet apps.

- Physiological loop: breath 3-4 s per cycle (craft: 15-20 breaths/min), exhale shorter than inhale, 1 px chest/body rise at the chibi's scale; blink timer 3-6 s with 10-20% double-blinks, blink 0.1-0.3 s; eyes/head/ear turn toward the touch point by species.
- Small fidgets (ear twitch, tail swing, look at the window or the bowl) on a separate randomized timer, roughly every 8-20 s; big idles (stretch, yawn, walk to the rug, sit by the window when it rains) every 30-90 s; re-roll the interval only after an event plays; pause timers while the screen is hidden and resume from elapsed time.
- Reaction delay: wait ~150-300 ms after a tap before the reaction (a real pet notices, then responds); none of the sources give a number, so tune by feel.
- Clock-driven state first: greeting on return, sleeping when the room is dark, bowl-watching when a feed is due, hungrier after hours away (Nintendogs/Tamagotchi/Finch all do this).
- Ambience: dust motes as 1 px particles only inside the window beam; lamp glow breathing slowly (period 4-8 s, tiny amplitude) rather than flickering fast; steam from a cup as 2-3 rising pixels every 1-2 s; all motion slow enough that pixel edges stay crisp.
- Sound: no soundtrack by default; soft weather at the window and sparse pet vocalisations; UI taps 30-150 ms from one acoustic family; a single quiet chime at sunrise and at lamp-on; iOS Ambient category so the user's own music keeps playing and the Ring/Silent switch is honoured; a Sounds toggle and a Haptics toggle in Settings.
- Haptics: `.success` / `CONFIRM` on care completion only, `.warning` / `REJECT` for soft refusals, `.soft` impact / light tick on pet touch, Selection / `SEGMENT_TICK` on pickers, nothing for anything the user did not cause.
