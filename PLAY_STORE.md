# Google Play checklist for Home Dragon

## 1. Signing (do this once)
The release build is signed with a private upload key when these four GitHub Secrets exist
(repo > Settings > Secrets and variables > Actions > New repository secret):

| Secret name | Value |
|---|---|
| UPLOAD_KEYSTORE_BASE64 | the whole text of `upload-keystore.base64.txt` |
| UPLOAD_STORE_PASSWORD | the store password |
| UPLOAD_KEY_ALIAS | the key alias |
| UPLOAD_KEY_PASSWORD | the store password again (PKCS12 keys use the store password; the build handles this) |

Without them the build uses the old debug key (fine for your own phone, rejected by Play).
Keep the keystore file and passwords backed up. In Play Console turn on Play App Signing and upload the `.aab`
from the `HomeDragon-release-aab` artifact. Builds signed with the upload key install only after you uninstall a
build signed with the debug key once.

## 2. Files for the store
- Privacy policy link: https://github.com/ramimrohan-ops/Virtual-Pet-Dragon/blob/main/PRIVACY.md
- App icon (512x512): `play-icon-512.png` (in the store-files zip)
- App bundle: artifact `HomeDragon-release-aab`; APK for your own phone: `HomeDragon-release-apk`

## 3. App content answers
**Data safety:** collects no data, shares no data. No data is sent off the device (no internet permission).
Privacy policy: link above.

**Content rating questionnaire:** fantasy creature breathing cartoon fire at icons; no blood, no real violence, no user
interaction, no purchases, no location sharing. Target audience: 13 and over (do not pick children).

**Foreground service declaration (specialUse):**
- Feature: keeps the animated pet dragon drawn over the home screen
- User impact if interrupted: the dragon disappears from the home screen until the user starts it again
- Use case text: "Always-on animated overlay pet shown while the user is on the home screen; no other foreground service type fits"
- Video: record Start dragon, the notification appearing, the dragon on the home screen, then Stop

**Accessibility declaration (not an accessibility tool):**
- Why the API is used: app functionality. Finds home-screen icon positions and detects when the home screen is in front, so the pet can interact with the icons
- Personal or sensitive data collected or shared: none; nothing leaves the device
- Video: show the Setup > Icon finder row, the in-app disclosure ("Allow the icon finder?") with Agree and continue,
  Android's Accessibility settings with the service switched on, then the dragon sitting on icons

**Permissions in use:** display over other apps, foreground service (special use), notifications, start after reboot,
accessibility service (disclosed above). Battery exemption is NOT requested; the app only opens the system battery list.

## 4. Before production
New personal developer accounts may need a closed test with 12 or more testers for 14 days first. Check the
Play Console dashboard for the exact requirement on your account.

## 5. Store listing text (copy and paste)
**App name:** Home Dragon

**Short description (80 max):** A cute pet dragon that lives on your home screen and plays with your icons.

**Full description:**
Home Dragon puts a friendly pet dragon on your Android home screen. It sits on your app icons, hops between them, flies
across the screen and breathes harmless cartoon fire that fades away. Nothing is ever changed on your icons.

- Lives on your home screen and fades away when you open an app
- Hops, flies and perches on your icons and widgets
- Size, speed, frame rate and effects sliders, flame colours and see-through body and wings
- Optional Icon finder (accessibility permission, always asked first) lets the dragon find where your icons are
- No account, no ads, no internet access, no data collected

**Category:** Personalization (or Entertainment). **Contact email:** ramimrohan@gmail.com

## 6. Videos to record (screen recording on your phone, about 30 to 60 seconds each)
**Video 1, foreground service:** open Home Dragon, tap Start dragon, pull down the notification shade to show the
"Dragon is on your home screen" notification, go to the home screen and show the dragon, then tap Stop in the
notification.

**Video 2, accessibility:** open Home Dragon, scroll to Setup, tap the Icon finder row, show the "Allow the icon finder?"
dialog and tap Agree and continue, then Android's Accessibility settings with Home Dragon switched on, go to the home
screen and show the dragon sitting on icons. Upload each video to YouTube as Unlisted and paste the link in Play Console.
