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

## v2.26 - dragon stops after screen lock on Samsung
One UI has no Autostart manager, so it can stop the dragon service while the phone sleeps. Changes (untested on a Samsung):
- The icon finder (Android restarts accessibility services itself) now starts the dragon service again whenever it connects or sees a screen change and the dragon should be on but its service is gone (at most once every 30 s). It will not restart a dragon you stopped with the Stop button.
- The dragon also restarts after the app is updated (MY_PACKAGE_REPLACED).
- New "Dragon health" card on the app screen: service running or not, icon finder connected or not, auto-restart count, and a log of the last 16 events (app process started, screen off, unlocked, dragon service started/stopped by you or by the system, icon finder connected/disconnected, restarts). Only event names and times are stored, on the phone.
- On Samsung, "Background running" tries to open Samsung's battery page, falls back to App info.
- Limits: if One UI force-stops the whole app or switches the icon finder off, nothing inside the app can start it again. Use Settings > Battery > Background usage limits > Never sleeping apps and turn off Put unused apps to sleep.

## v2.27 - Samsung never sent the unlock broadcast
The v2.26 health log showed it: after "Screen off" there was never an "Unlocked" event, while the service and icon finder stayed alive (0 restarts). The unlock broadcast is not delivered on that Samsung, so the dragon stayed hidden as if the phone were still locked. Now:
- every refresh reads the real state (screen awake and no lock screen) instead of relying on the broadcast,
- after "screen on" the lock state is checked every 0.4 s for up to 2 minutes,
- the re-checks after unlock also refresh the dragon, and the log gets "Screen on ..." and "Unlock found by checking" events.

## v2.28 - dragon stays hidden after returning from Recents
Seen on a second Samsung: open Recents, switch to another app, press home, and the dragon stays hidden although the phone is unlocked. Opening and minimizing the dragon app brought it back. The home check trusted an old "recents is showing" note that One UI never cleared. Now:
- After any window change while the dragon is hidden, the icon finder re-reads the live screen at 0.3, 0.9 and 2 s. If the launcher is in front, shows its icons and no recents views, the old note is dropped and the dragon comes back.
- Safety net: while the dragon is hidden, the phone is awake and unlocked and the app is closed, the same re-check runs every 1 s for 20 s after the last window change, then every 5 s. It stops when the dragon is shown, the screen turns off or the app opens. A re-check only reads the top window's app name; the icons are scanned only if the launcher is that window.
- Health log: "Home found by re-check (was hidden: reason)" when a re-check fixed a stuck state, and "Dragon app opened; home screen was detected / NOT detected (reason)" every time the app opens.

## v2.29 - Log tab and "allow always running" prompt
- The app now has two tabs under the header: **Dragon** (start button, sliders, flame colours, setup) and **Log** (Dragon health card). The log keeps 50 events and has Copy log and Clear log buttons. Copy log puts the status and events on the clipboard so they can be pasted into a message.
- The **Background running** row first shows Android's own "allow always running" pop-up (needs the REQUEST_IGNORE_BATTERY_OPTIMIZATIONS permission). After it is allowed, the next tap opens the phone's battery pages (on Samsung: Samsung's battery page or App info). Samsung keeps its own "Never sleeping apps" list; whether it honors this pop-up is not known.

## v2.30 - restart advice after setup
Once, when all three Setup rows (Draw over other apps, Icon finder, Background running) are On, the app asks "Restart your phone?" with **Restart now** and **Restart later**. An app cannot restart a phone itself (this app assumes an unrooted phone), so Restart now opens the phone's power menu through the icon finder and the user taps Restart. Needs Android 12 or newer; on older versions the app shows a note to hold the power button. The pop-up is shown once per install (it also appears once after updating from an older version). Both choices are written to the Log tab. A restart is advised because Samsung forum reports say apps sometimes appear in "Never sleeping apps" only after one; it is not a guaranteed fix.

## v2.31 - icon finder help for "restricted setting"
Android blocks accessibility services of apps installed outside an app store. The App info menu item "Allow restricted settings" only appears after the greyed-out switch has been tapped once. Tapping the Icon finder row (when it is not yet on) now shows the three steps in the right order, with **Open Accessibility** and **Open App info** buttons that keep the steps on screen. The dialog closes by itself once the icon finder is on. The app cannot grant the setting itself: Android requires the user to do it.

## v2.4 - first-run page and Setup highlight
- **"Before you start" page** (full screen, first open after installing): short cards for Draw over other apps (pop-up window), Icon finder (Accessibility, restricted setting), Background running (battery), Notification and One restart, plus a privacy line (no internet access, no data collected). It cannot appear during the install itself, because Android owns that screen. It is skipped when the three Setup rows are already On (e.g. after an update) and re-opens from the "What does it ask for?" link on the Setup card.
- **Closing the page** (button or back) switches to the Dragon tab, scrolls to the Setup card and pulses an orange outline around the **whole** Setup card with a "Start here" label for about 5 seconds. It stops when you tap a row. While any row is not On, the card pulses briefly each time the app is opened. It only points; it never taps or enables anything.
- The notification question now comes after the page is closed instead of on top of it.

## v2.5 - Godzilla-style charge-up and Charge-up time slider
- **Charge-up before every fire breath**, inspired by the 2014 atomic breath (not a frame-by-frame copy): a bright wave runs from the tail tip along the spine spikes up to the head, the spikes keep glowing and flicker faster while it builds, sparks and shrinking rings are pulled into an orb in the mouth, and at the moment of the breath it flashes and sends out a ring.
- **Colours follow the flame colours** chosen in the app (the throat glow inside the open mouth too). Default is the original blue.
- **Visible on any wallpaper:** a dark soft halo sits behind each glow. The glow does not fade with the Transparency slider. The number of converging sparks follows the Particles slider.
- **New slider "Charge-up time"** (main Dragon tab): Off, then 0.5 to 3.0 s in 0.5 s steps, default 1.5 s (was a fixed, faint 2 s). Applies live and is included in "Reset all". The preview box shows the mouth part (orb, sparks, flash); the spine wave is only seen on the real dragon. The time is in real seconds and does not depend on Dragon speed. Longer time = slightly bigger, brighter finish. Costs a little extra battery during the charge only.

## v2.6 - preview boxes
- **Charge-up time preview:** shows the whole sitting dragon (not just the head) with the real effect: glow wave along the spine, orb, sparks, rings, flash, then the breath into empty space (no dummy icon). It stays still while the slider is dragged and plays on a loop after you let go, until you touch the slider again. It pauses when you leave the Dragon tab or the app.
- **Flame colours preview:** stays still when the tab opens. Changing a colour plays two loops of fire in the new colours, then it pauses on a still picture.
- **Transparency preview and hints:** since Android 12 the system makes the dragon's "draw over other apps" window (the kind that lets taps through) 20% see-through, so the most solid the dragon can be on the home screen is about 80%. The preview now shows the same cap on Android 12+, and the slider hints say so. Nothing changes on Android 10 and 11.

## v2.7 - separate transparency sliders, real logo, paw animation, preview text
- **Transparency and Wing transparency are fully separate.** The wing skin is drawn at its own alpha and everything else at the body alpha, with no shared calculation. Both sliders now have the same short hint: "0% = most solid (max 80% on Android 12+)." (Android makes the overlay window 20% see-through; see v2.6.)
- **Real app logo** in the header of the app (the dragon head of the launcher icon, cropped to its centre with rounded corners) instead of the emoji badge.
- **Sitting front legs move:** upper arm and forearm follow the breathing, the paw flexes, and every few seconds one paw lifts a little (the two legs out of step). Only while sitting; the scratch, lying down, walking and flying poses are unchanged. Applies to the sitting previews too.
- **Preview text at the bottom centre** in every preview box (SITTING / FLYING, flames up to, charge-up, body and wings, width at 100%). The big fps number stays top-right.

## v2.8 - new charge-up path, spine sparks, scratch fix, preview rules
- **Charge-up starts at 9 points:** the tail tip and the 8 wing claw tips (4 per wing). The glow runs from each claw tip along its finger bone to the wrist, then along the arm bone through the elbow to the shoulder; the tail glow runs along the spine spikes to the shoulder. They arrive together and flash, then one glow goes up the neck into the mouth, where sparks and rings gather into an orb. The wing positions are read from the wings as they are drawn, so the glow follows the bones while they move.
- **Gradient colours:** the charge-up uses an 8-step gradient of your flame colours (hot core to cool tip) for the glow, sparks, rings, orb and flash. The glow starts in the cool end colour at the claw tips and the tail tip and gets hotter towards the shoulder, neck and mouth.
- **Spine sparks:** short electric arcs jump between neighbouring spike tips while it charges, keep going at full strength for the whole breath, then fade to zero in about 1 second. Count follows the Particles slider; off when Charge-up time is Off. The spikes and bones keep a soft glow while the sparks run.
- **Scratch:** the paw now points up and forward from the wrist while scratching, so the claw tips touch the cheek and the claws are above the wrist. The wrist is limited to what the arm can reach, so the arm never breaks apart.
- **Preview rules:** every looping preview stops about 5 seconds after the last touch of its slider, at the end of the loop it is playing (wing flap, sway or fire cycle), on a still picture. Touching again restarts it. The Transparency preview (flying dragon) loops from the first touch. The Charge-up time preview stays still while dragging, loops after release, and takes the flame colours at once when they change.
- The charge-up drawing is shared by the dragon and the preview (ChargeFx.kt).

## v2.9 - continuous charge-up waves, throat orb, imploding particles, one size on every page
- **Same dragon size on every home-screen page.** The size now comes from the normal app icon size (a low percentile of the icon widths, so widgets and big folders do not pull it up) and is kept across pages. It is re-measured only when the screen size changes (rotation) or the launcher grid really changes (three layouts in a row that differ by more than 20%). The Size slider works on top of it as before.
- **Continuous waves, not a loop.** The 8 wing claw tips and the tail tip are steady glowing sources. Each one keeps sending waves, one after another: along the finger bone to the wrist, the arm bone through the elbow to the shoulder; from the tail tip along the spine to the shoulder and on up the neck to the throat. Several waves are on a bone at once, nothing restarts. Waves are sent for the whole charge-up and the whole breath, then stop; the ones on the way finish and everything fades out in about a second. Every wave that reaches the shoulders flashes there. Wave speed follows the Charge-up time (a wave needs about 60% of it to reach the shoulder).
- **Spine sparks:** exactly 12 of the 15 gaps between spike tips spark at a time, chosen at random, a new set 10 times a second, for the whole charge-up and breath. After the breath the number steps down to 0 as it fades. The Particles slider no longer changes the number of arcs.
- **Everything gathers in the throat** (the back of the open mouth, where the neck meets the head). The orb grows there; the flash at the end of the charge is at the mouth tip.
- **Orb shrinks during the breath:** it stays through the whole breath, gets steadily smaller, is smallest when the breath ends, then fades with the sparks.
- **Imploding particles replace the rings:** bright glowing particles start on a wide circle around the throat, curve inward in a spiral, speed up, leave a short trail, shrink and merge into the orb. Coloured from the cool to the hot end of your flame gradient, with a faint dark halo so they show on bright wallpapers. Count follows the Particles slider (about 15 to 30). Same in the charge-up preview.
- **Mouth fully open for the whole charge-up** (opens in about a quarter of a second), stays open through the breath and closes at the end. Home screen and preview. With Charge-up time Off nothing changes.

## v3.0 - mouth closes a little for the breath (includes everything from v2.9)
- With Charge-up time on, the mouth is fully open during the charge-up, then closes smoothly to about 40% open (in about 0.2 s) when the breath starts, stays there for the whole breath, and closes completely at the end. Home screen and charge-up preview. The flame glow at the mouth keeps its size. With Charge-up time Off nothing changes.

## v3.1 - charge-up gathers in the mouth, straight implosion, Charge-up quality slider
- **Orb and imploding particles in the middle of the open mouth** (behind the teeth), not in the neck. The neck waves run on into the mouth. The orb follows how far the mouth is open.
- **Imploding particles fall in straight lines**, no spiral. Each starts at rest on a wide circle around the orb and speeds up steadily toward its centre, with a straight streak behind it that is longer the faster it goes. 120 particles at 100% quality, a little bigger and brighter than before, with one shared dark halo behind the swarm so they show on bright wallpapers.
- **New slider "Charge-up quality"** (10..100%, default 100%, with its own preview box) under Charge-up time. One slider scales three things together: sparks at a time (12 at 100%, 6 at 50%, 1 at 10%), imploding particles (120 / 60 / 12) and waves on each bone (about 3 / 2 / 1). The Particles slider no longer changes the charge-up. With Charge-up time Off the quality slider does nothing.
- **No fire in the mouth before the breath:** the flame glow inside the mouth (and the flare in the preview) only appears when the breath starts. During the charge-up the mouth shows only the charge building.
- **Mouth:** fully open through the charge-up and the whole breath (this replaces the 40% of v3.0), then it closes slowly (about 0.8 s). With Charge-up time Off nothing changes.

## v3.2 - the orb is finished right before the breath
- **Orb complete at 85% of the charge-up time** (it used to reach full size only at the instant the breath started). For the last 15% the finished orb holds, a little brighter and throbbing, and then the breath starts.
- **Imploding particles thin out and stop** arriving by 85%. No particles are pulled in during the breath; the orb just shrinks through the breath as before. Home screen and charge-up previews.
- With a short Charge-up time (0.5 s) the hold is only about 0.08 s; at 3.0 s it is about 0.45 s.

## v3.3 - one wave, wave thickness slider, one shared charge-up preview with Sitting / Flying
- **One wave during the charge-up.** Each of the 9 sources (8 claw tips, tail tip) sends a single wave: along the finger bone to the wrist, the arm bone to the shoulder (flash there); from the tail tip along the spine and neck into the mouth. It reaches the mouth at about 85% of the charge-up time, as the orb is complete. The bones and spikes stay softly lit behind the wave until the breath ends. No new waves during the breath; the arcs between the spikes go on as before.
- **New slider "Charge-up wave thickness"** (50..200%, default 100%): widens the wave along the bone and thickens its glow. Only the wave itself changes, not the softly lit bones.
- **Charge-up quality** now scales only the sparks and the imploding particles (the wave count is always one).
- **One shared preview box** for Charge-up time, Charge-up quality and Charge-up wave thickness (it used to be one box per slider). Same rules: still while dragging, loops after release, stops about 5 seconds after the last touch of any of the three sliders. Caption: time, quality and wave.
- **Sitting / Flying switch** in the top-left corner of that box. Tap to change the pose; the charge-up then plays twice in the new pose. Flying = hovering with the wings spread and flapping slowly, the wave follows the moving wing bones. The choice is remembered.

## v3.4 - an unreadable window in front no longer hides the dragon
- Before, a window the Icon finder could not read counted as "not the home screen": the dragon faded out and paused (log: "another app or unreadable window in front"). Now it is decided in this order:
  1. a fresh icon scan finds the home screen icons -> home, the dragon keeps going;
  2. the last window-change event names a package: the launcher -> keeps going, another app -> hidden (an app that blocks accessibility, such as a banking app, still hides it);
  3. nothing says which app it is -> the dragon keeps doing what it was doing for 5 seconds, then hides if the window is still unreadable.
- Screen off or locked, and the Home Dragon app being open, pause it as before.
- New Log tab lines say which step decided, e.g. "Unreadable window in front: the icon scan found the home screen, kept going" or "Unreadable window from <package> in front: hidden".

## v3.5 - no time limit for an unreadable window, home needs two icons
- **Unreadable window with no package name:** the dragon keeps doing what it was doing, with no 5-second limit (v3.4 hid it after 5 s). It changes only when something known arrives: a readable window, a window event that names a package, or an icon scan. The scan and the package-name steps of v3.4 are unchanged. Log line: "Unreadable window in front, no package name: kept going". Screen off or locked, and the Home Dragon app being open, still pause it.
- **Two icons are enough for the home screen** (it used to be four). Changed in all four places: the scan's "nothing found" counter, the clearing of an old recents flag, the home decision, and the page-change detection in the dragon. A page with one or two icons, or mostly widgets, is now recognised as home; a screen with a few labelled tappable items (an open folder, a search panel) may now count as home too.

## v3.6 - automatic restarts after unlock and while a window is unreadable
- **After unlock: one restart.** When the unlock is detected (the unlock broadcast, or "Unlock found by checking"), the dragon restarts once 1.0 s later (overlay re-attached if missing, settings applied, icon list re-checked). If the launcher could not be reached at that 1 s point, it waits up to 30 s and does one more restart as soon as the launcher is found. Skipped when the dragon is already showing normally. Only one round at a time.
- **Unreadable window in front: restart every 1 s.** While the top window is unreadable (no package name), a light restart runs every second. It stops when the launcher is found (a readable launcher window, or an icon scan with 2 or more icons = home screen or app drawer), when the window becomes readable, when the screen goes off, when an app is open, or when the Icon finder is off.
- Log tab: one line when the series starts and one when it ends.
- The normal scan on launcher events and the v3.4/v3.5 decision rules are unchanged.

## v4.0 - automatic restarts removed
- **Removed** everything added in v3.6: the restart 1 s after unlock, the extra restart when the launcher becomes reachable, and the light restart every 1 s while the front window is unreadable (with their Log lines).
- **Removed** the extra re-checks the dragon service scheduled after every unlock or screen-on (0.3, 0.9, 2, 4 and 8 s).
- **Kept** the unlock detection itself: the unlock broadcast ("Unlocked") and "Unlock found by checking (no unlock broadcast)".
- **Kept** the v3.4/v3.5 rules (an unreadable window no longer hides the dragon, two icons = home screen) and the icon finder's own normal re-checks after a window change while the dragon is hidden.

## v4.1 - no fade-out, slow smooth fade-in, two re-checks
- **No fade-out:** every time the dragon hides (another app in front, page swipe, screen off) it disappears at once.
- **Fade-in 0.4 s, smooth:** every time it reappears it fades in over about 0.4 s with a soft start and soft end (it used to be about 0.17 s). Applies to window changes and page swipes alike. The overlay is still capped at 80% opacity on Android 12+.
- **Icon finder re-checks** after a window change while the dragon is hidden: 2 re-checks at 0.4 s and 0.8 s (they were 0.3, 0.9 and 2 s).

## v4.2 - usage access, new Setup card, dead code removed
- **Usage access (optional).** A new row in the Setup card opens Android's Usage access page; once switched on, Android itself tells the dragon which app is in front (only the app name, used live). It names an app even when its window cannot be read (banking or secure apps), and it is cheap: no screen scan is needed to hide the dragon. If the permission is off, everything works as in v4.1. It does not count towards "setup finished".
  - Hidden-dragon polling and the re-checks after a window change use it first: another app in front -> hidden at once. If the focused window is plainly the launcher, the window is believed (Android's list can lag a moment).
  - An unreadable window is re-checked once more 0.4 s and 0.8 s later, so an app that opens from the home screen is caught even if Android's list was one step behind.
- **New Setup card:** four rows, each with an icon tile, one short title and a button (Turn on / On); no description lines; a small "?" opens the info page, which is shortened too. The Log tab shows a "Usage access: on/off" line.
- **Dead code removed:** unused fields, helper functions, parameters, an unused painter method, imports and API checks made unnecessary by minSdk 29 (details in the commit). No behaviour changes.
- Privacy policy and Play notes mention usage access.
