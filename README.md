# Home Dragon (Android)

A realistic dragon in the style of your reference photo (teal back, orange scaled sides, purple-orange tail underside, frilled head with swept-back horns and crest, big membrane wings). Icons are its only ground: it never walks. When it sits on an icon it holds the photo's pose: upright chest, S-curved neck, front paws straight down, haunch tucked, tail curled behind and both wings raised and open so they stay visible. It hops to a neighbouring icon, or takes off and flies a random curved path in any direction and lands on another icon. From a perch or in the air it breathes a blue plasma flame at an icon, leaving smoke and a scorch mark with blue embers (visual only, icons cool down after about 30 seconds).

Large icons are bigger ground. When the icon finder sees a widget or a large folder (anything about 1.5 times wider than a normal icon), the dragon can stand up on it, fold its wings and crawl along the top edge, then sit down again. On normal icons it still never walks: it hops or flies between icons.

The dragon is drawn procedurally in code (spine, scales, IK legs, jointed wings) to match the photo's look and pose. It is not the photo itself.

**Status: source code only. It has not been compiled or run on a device.** Expect to fix a small error or two on the first build.

## Build (GitHub, no Android Studio)

1. Put these files at the top level of a GitHub repo (the `.github/workflows/build-apk.yml` file starts the build on every push).
2. Open the repo's Actions tab, open the latest "Build APK" run (wait about 5 minutes for the green tick).
3. Under Artifacts, download HomeDragon-release-apk, extract `app-release.apk`, tap it on the phone and allow "Install unknown apps".

Every build is signed with the same fixed key, so a new APK installs over the old one without uninstalling.

## First-run setup (Redmi Note 14 Pro+, HyperOS)

| Step | Where | Why |
|---|---|---|
| 1 | Button 1, allow "Display over other apps" | Draws the dragon above the launcher |
| 2 | Button 2, switch on "Home Dragon icon finder" | Reads icon positions. Without it a manual grid is used |
| 3 | Button 3, battery "No restrictions", plus Autostart on | Keeps the service alive after screen-off |
| 4 | Recent apps, long-press the app card, lock it | Stops HyperOS clearing it from RAM |
| 5 | Start dragon | Starts the foreground service |

Rooted shortcuts (optional, in a root shell):

```
appops set com.ramim.homedragon SYSTEM_ALERT_WINDOW allow
dumpsys deviceidle whitelist +com.ramim.homedragon
```

## How it meets your requirements

| Requirement | How |
|---|---|
| 120 Hz while screen is on | Frames come from Choreographer (display vsync). The overlay window requests the highest refresh mode at the current resolution. Settings, Display, Refresh rate must be on 120 Hz. |
| Pause when screen is off | `ACTION_SCREEN_OFF` removes the frame callback. No timers, no drawing. |
| Instant on wake or unlock | The view, bitmaps and dragon state stay in RAM inside the foreground service. Resume only re-posts the frame callback. It resumes on unlock (or on screen-on if there is no lock screen). |
| Live screen, not a screenshot | Transparent `TYPE_APPLICATION_OVERLAY` window over the real launcher. Touches pass through. |

## Page-swipe fade

When the launcher scrolls sideways (the icon finder sees scroll events), the dragon fades out in about 0.09 s and fades back in about 0.17 s after the new page settles, landing on one of that page's icons. If the launcher sends no scroll events, the dragon still fades in when it notices the icon layout changed to a different page.

## Home screen or app?

The icon finder looks at the top-most application window (keyboards, the status bar, picture-in-picture and the dragon's own overlay are ignored). If that window is the launcher, the dragon is shown. If it is any other app, the dragon fades out and the frame loop stops, then it fades back in on the home screen after the icons are read again. The recents screen is detected three ways, all best guesses on HyperOS: the screen's class name, recents-looking views in the launcher's node tree (ids containing recents, overview, task_view or clear_all), and the launcher showing no icons at all for two settled scans. If the icon finder is off, the app cannot tell, so the dragon stays on screen.

## Fire and after-burn

Flame particles are one soft sprite tinted along a smooth colour ramp (white-blue, cyan, blue, indigo, violet) with a hot core while young, and they curl upward as they age. Sparks have glowing heads and fall under gravity. The stream is aimed at the middle of the icon: each particle's launch speed is solved so it arrives there, then pools, splashes and sprays sparks from the centre. After the fire the icon never darkens. It glows white-hot from the middle (soft, no edge ring), shifts to cyan and then deep blue, and cools over about 10 seconds, with small blue pilot flames while it is still hot, floating ash and light smoke.

## Speed

Flying, hopping and crawling are about 20 percent slower than v1.2, with slower wing beats to match.

## Known limits

- Crawling on widgets and big folders depends on the launcher exposing them to the icon finder. If HyperOS hides a widget from accessibility, that widget is not walkable. The manual grid fallback has no big icons.

- Fire is drawn on top of icons. It cannot damage or move real icons.
- Icon positions come from accessibility data. If HyperOS hides some icons from it, those icons get no dragon. Use the grid fallback (turn the icon finder off and set columns and rows).
- The dragon also shows over the recents screen, because the launcher owns that screen too.
- The overlay does not draw above the lock screen.
- The dragon runs at full frame rate even when it is only sitting. An idle frame cap would save battery but conflicts with your 120 Hz request, so it is not included.
- Some HyperOS builds ignore the refresh rate request from overlays. If the demo's fps looks capped at 60, check the display refresh setting first.

## Files

- `DragonModel.kt` the realistic dragon drawing (body, scales, legs, wings, head)
- `DragonView.kt` behaviour (hop, fly, land, fire), blue flame, smoke, sparks, scorch, frame loop
- `DragonService.kt` overlay window, 120 Hz request, screen on/off handling
- `IconFinderService.kt` accessibility service that finds icon rectangles
- `MainActivity.kt` setup screen and size sliders
- `BootReceiver.kt` restarts the dragon after reboot if it was on


## App screen and sliders (v2.0)

The app screen is a simple dark page with a Start/Stop button, a status pill and three percentage sliders that change the dragon live while it runs:

| Slider | Range | What it does |
|---|---|---|
| Quality | 10% to 100% | Caps the number of flame, smoke and spark particles, lowers fire and smoke emission, drops the extra glow layers, and below 25% draws every second frame. The dragon's body model is not changed. |
| Dragon size | 50% to 150% | Scales the dragon on the icons. |
| Dragon speed | 50% to 150% | Speeds up or slows down flying, walking, wing beats, idle waits and the fire itself. Particles and icon cooling stay in real time. |

"Reset all to 100%" puts everything back to the defaults. Below the sliders, three setup rows show whether each permission is on and open the right Android settings screen when tapped. The old fallback-grid sliders were removed (the grid still works with its defaults when the icon finder is off).

## Quality slider and frame pacing (v2.5)

The Quality slider moves in steps of 10% (10, 20 ... 100). Frame rate = screen refresh rate x Quality, the same in every state (flying, fire, walking, sitting).

| Quality | 100% | 90% | 80% | 70% | 60% | 50% | 40% | 30% | 20% | 10% |
|---|---|---|---|---|---|---|---|---|---|---|
| fps on a 120 Hz screen | 120 | 108 | 96 | 84 | 72 | 60 | 48 | 36 | 24 | 12 |

At 100% every vsync is drawn. Below that the app skips a vsync now and then so the average rate is exact, and sleeps on a timer between frames so the CPU is not woken on every vsync. Sitting still: once the dragon has finished its sitting animation (landing, crouch and wing fold) and nothing else is moving, 2 more frames later, it drops to 30 fps at any Quality (never above the Quality rate, so 10-20% stay at 12-24 fps). Anything that moves it brings the full rate back at once.

Fire, smoke and spark amounts are set by the separate Particles slider.

## Simpler dragon model (v2.4)

To save drawing work: the neck frill has 3 spines instead of 5 with a flat colour web, the skull crest has 2 blades instead of 3, and the back spikes are every third segment instead of every second (slightly larger). The scale pattern is only on the thigh and the belly (back, neck, tail and chest are smooth). The wings are unchanged from v2.2.

## Settings screen (v2.14)

Four sliders, all in steps of 10% (Quality and Particles 10-100%, Size and Speed 50-150%). Older saved values snap to the nearest step.

| Slider | Controls | Preview box |
|---|---|---|
| Quality (frame rate) | Frame rate only: screen refresh rate x Quality, and 30 fps once the dragon sits completely still | Two boxes side by side: "sitting" (30 fps, or the Quality rate if lower) and "flying" (the dragon in the flying pose, wings flapping in place, screen rate x Quality). The fps number is big and white in the top-right corner of each box on a soft shadow. |
| Particles | Fire, smoke and spark amount only | The dragon's head breathing fire at a dummy icon, with the flame count |
| Dragon size | Size | The sitting dragon in a box just bigger than the dragon at 150%, with a bar for the width at 100% |
| Dragon speed | Speed | none |

The home-screen dragon is hidden and stopped for as long as the Home Dragon app is open. The previews only animate while you drag a slider and hold the last picture otherwise. Pressing Home brings the dragon back at once. The preview fire is a separate, simplified copy of the real fire effect with the same colours and particle counts.

## v2.22 changes

| Change | Detail |
|---|---|
| Signing | Release is signed with a private upload key from GitHub Secrets when present, otherwise the debug key (see PLAY_STORE.md) |
| API level | compileSdk and targetSdk 36 (Android Gradle plugin 8.11.1, Gradle 8.13) |
| App bundle | The build also makes an .aab (artifact HomeDragon-release-aab) |
| Icon finder disclosure | New in-app screen with Agree and continue / No thanks before Android Accessibility settings open. The service switches itself off until you agree |
| Battery | The battery-exemption permission was removed; the app opens the system battery list instead |
| Service description | Reworded to match exactly what the code does |
| Policy files | PRIVACY.md and PLAY_STORE.md added |

## v2.21 changes

| Change | Detail |
|---|---|
| Transparency scale | Both sliders now run 0% to 100% in steps of 10%. 0% = fully solid, 100% = barely visible (about 10% left, so the dragon never disappears). Defaults 50% (body) and 65% (wings) |
| Independent sliders | Transparency covers the body, wing bones, spikes and claws. Wing transparency covers only the thin wing skin and works on its own, so the skin can be more solid or more see-through than the body |
| Layout | In the Visual card the two sliders sit on the left and one preview box on the right |
| Preview box | The dragon hovering with wings spread over dummy app icons, so the see-through effect is visible. Animates while a slider is dragged |
| Fade | The whole dragon is drawn into one layer and faded as a unit; fire, glow, smoke and the charge-up stay at full brightness. At 0% / 0% no layer is used |

## v2.20 changes

| Change | Detail |
|---|---|
| New "Visual" card | Below "Dragon settings": Flame colours (picker, preview, "Reset to blue" moved here unchanged), Transparency and Wing transparency |
| Transparency slider | 10% to 90% in steps of 10%, default 50%. The whole dragon fades as one piece (wings and body do not show through each other). No preview box |
| Wing transparency slider | 10% to 90% in steps of 10%, default 65%. Only the thin wing skin between the bones follows it; wing bones, spikes and claws follow the Transparency slider. Cannot be more solid than the dragon itself |
| Stays bright | Fire, flames, glow, smoke, the charge-up glow and the orb are drawn after the dragon and are not affected |
| Reset all | Also resets the two new sliders (50% and 65%) |
| Charge-up | 2.0 s instead of 1.5 s: the wave along the tail and spine is 0.5 s longer; gathering into the mouth is still 0.5 s |
| Spine glow | The charge-up glow on the spike tips, back, midpoints and the travelling wave head is about 20% larger; the orb size is unchanged |
| Battery | Transparency draws the dragon into an offscreen layer, a small extra cost while the dragon is on screen |

## v2.19 changes

| Change | Detail |
|---|---|
| Claw scratch | The raised paw tilts so the claw tips touch the cheek just below the eye, with short scratching strokes along the cheek (about 3 per second, 30 fps calm rate) |
| Fire charge-up | Before every fire breath (about 1.5 s): a glow starts at the tail tip, runs up the tail and along the dorsal spikes, lights each spike as it passes, flows up the neck and gathers into an orb in the mouth. The orb grows to about 80% of the mouth opening, the mouth opens halfway, then the breath starts as before |
| Colours | The charge-up uses the flame colours chosen in the app (blue by default) |

The charge-up is drawn glow only (not particles), so the Particles slider does not change it. It runs at the full frame rate like the fire.

## v2.18 change: new sleeping pose

| Part | Detail |
|---|---|
| Body | The sitting body stays upright (no lying down any more) |
| Tail | Sitting shape with the tip a little lower; it rests slightly below the hind feet |
| Head | Neck bent down, head tucked against the chest and resting on the front limb, eye shut |
| Paws | Front paws and hind feet rest on the same ground line |
| Wings | Folded back |
| Tail motion | While asleep the tail tip moves gently in random spells (about 1-3 s) and rests still in between (about 1.5-5 s) |

Lying down and getting up are one smooth blend between the sitting and the sleeping pose: the tail and rear settle first, then the neck bends and the head lowers, and the eye shuts at the end. Sleeping runs at the calm 30 fps rate.

## v2.17 change

| Change | Detail |
|---|---|
| Walk frame rate | Walking is capped at 60 fps: Quality 10-50% gives 12 / 24 / 36 / 48 / 60 fps and 60-100% stays flat at 60. Fly, jump and fire keep the full rate (screen refresh x Quality). Sitting, sleeping and scratching stay at 30 fps or lower. |

## v2.16 changes

| Change | Detail |
|---|---|
| Sleeping tail end | The end of the tail curling round the rump (near the wing) stays visible and sways gently while asleep |
| Fire breathing | Fire is about 20% of the random mood choices (was about 13%) |
| Scratching | About 3 rubs per second (was 5.5) and it runs at the calm 30 fps rate like sitting |

## Moods, sleep and flame colours (v2.15)

**Sitting pose.** Front legs are now bent and simplified (forearm + upper arm), the knuckles are round and the wings end in curved talons.

**Random moods.** When the dragon feels like it, it picks one by weighted dice: walk, sleep, scratch, jump, fly or breathe fire. Moods that do not fit the spot are left out.

| Mood | Rule |
|---|---|
| Walk | Only on large icons/widgets, as before |
| Sleep | Sits, optionally takes a few steps (large icons/widgets only), curls its head onto its front limb, sleeps 3-6 s, then lifts its head again. Only the tail may overhang the edge; if head and body do not fit it flies instead. Narrow surfaces skip the walk. |
| Scratch | Rubs its face with the near front paw for 2-4 s (about 3 rubs per second since v2.16) |

The feet are the anchor and the icon top is the ground. While asleep the frame rate follows the sitting rate (30 fps or the Quality rate if lower). Lying down and getting up are one continuous body animation (no crossfade): the body sinks, the wings fold back along the spine, the haunch slides back, the forearms slide forward, the tail curls round the front and the head settles last on the paws. Each part moves on its own slice of the timeline and getting up plays the same motion in reverse. Only in the last ~7% (when the body already matches the curled drawing) is that drawing blended in, so it ends exactly on the approved sleeping pose.

**Flame colours.** The "Flame colours" card has a live preview, up to 6 colours, hue/saturation/brightness picker, hex field, ready palettes and "Reset to blue". Blue stays the default. The colours blend from hot core to cooled tip and tint the flame, glow and smoke.

Limits: touch cannot wake the dragon (the overlay is not touchable); it wakes by timer, or fades out and re-lands on a page swipe or app switch. Scratching is a simple two-segment arm rub.

## v2.23 - new app logo
- Launcher icon: the new dragon-head logo (adaptive icon, purple background with glow rings), drawn in `res/drawable-nodpi/ic_launcher_fg.png`.
- Notification: white dragon-head silhouette as the small icon (`ic_stat_dragon.png`) and the full-colour dragon on white as the large picture (`ic_notif_large.png`).
- No behaviour changes.

## v2.24 - Samsung One UI
Best-guess Samsung support (not tested on a Samsung phone):
- Setup help on Samsung: battery step points to Background usage limits > Never sleeping apps; accessibility step points to Accessibility > Installed apps and mentions Allow restricted settings if the switch is greyed out.
- Icon finder also accepts One UI Home icon views (BubbleTextView / IconView) that are not flagged clickable.
- No other behaviour changes.

## v2.25 - dragon disappeared after unlock
After unlocking, the dragon could stay hidden while the app still said "running". Cause (found by reading the code, not reproduced on a device): while the lock screen is up the launcher window cannot be read, so the icon finder marked the home screen as covered; the window change after unlock could arrive before the launcher was readable again, and nothing re-checked, so the dragon stayed hidden until restarting it from the app produced new window events.
Fixes: after screen-on and unlock the app asks the icon finder to look again five times (0.3, 0.9, 2, 4 and 8 s); and when a scan sees the launcher in front with its icons, it now marks the home screen as visible by itself.
