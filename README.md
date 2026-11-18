# 学 (xue)

An Android app that marks the Chinese characters on screen, in any app. Every character gets a light underline
in a blue by how common it is (ƒ, F1 commonest … F5 rarest); characters in your list also get a soft highlight
in your level's colour from the quiz at [varie.lol/h](https://varie.lol/h/) (New grey, L1 yellow … L5 dark red,
by how often you get them wrong). Same colours as the quiz.

With **tap mode** on (Android 13+, experimental), a plain tap on any character opens the quiz's card for it in
a sheet over the app, like Lens. Without it: tap the **学 look** Quick Settings tile, then the character.

Tap mode works the way Android allows: 学 asks for touch exploration (as screen readers do) and takes touches
through a `TouchInteractionController`. The whole screen except the characters is a touch-exploration
passthrough region, so Android hands those touches straight to the app; characters in links and buttons stay in
it too. A touch that starts on a character comes to 学: a quick tap opens the panel, anything else (moving,
holding, a second finger) is handed back while the finger is still down. The **学 tap** Quick Settings tile turns
it on and off. Some apps change behaviour when touch exploration is on (they think a screen reader is running).

A tapped character opens its card from the quiz, filled with your data (date added, error rate) even when the
panel's quiz isn't signed in: the app gives it your list and results. With **Quiz me** on, the quiz asks the
character as a question instead.

**Lens** (from 学's notification or the **学 lens** tile), like Google Lens on the screen: a still screenshot
covers the screen with every character in it marked, the app's text and the Chinese in pictures (OCR). Tap a
character for its card, drag over a sentence to open it in the quiz (split into words, as text shared to it), ×
or Back to close. The app underneath gets nothing while it is open. In tap mode a drag from a character also opens
the sentence.

While 学 is on, a small 学 shows in the status bar (Android 13+: allow notifications); pulled down, it lists the
modes that are on, with buttons for Lens, Tap and OCR.

**OCR mode** (extra, off by default, Android 11+): characters in pictures, video and games, which have no text
for the accessibility service, are read from screenshots with ML Kit's on-device Chinese text recognition and
marked the same way (tap them too). The model isn't in the APK: Google Play services downloads it once, the first
time OCR is turned on, and keeps it. Turn it on with the **学 OCR** Quick Settings tile when you need it; it reads
every 1.5 s and after each scroll, so it costs battery. Screenshots are taken by the accessibility service itself
(no screen-recording prompt; Android 14+ captures the app's window alone) and never leave the phone.

Status: all four steps of the plan (marks by frequency, your levels, look-up panel with tap mode, OCR).

## Install

[<img src="https://raw.githubusercontent.com/ImranR98/Obtainium/main/assets/graphics/badge_obtainium.png" alt="Get it on Obtainium" height="54">](https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/wuttttta/xue)

1. With [Obtainium](https://github.com/ImranR98/Obtainium) on the phone, tap the button above: it adds the app and
   keeps it updated from this repo's releases. Or download [`xue.apk` from the latest release](https://github.com/wuttttta/xue/releases/latest/download/xue.apk)
   and install it (allow installing from your browser when asked).
2. Open 学 → **Turn on in accessibility settings** → Installed apps / Downloaded apps → **学 marks** → on.
   - Android 13+: if the switch is greyed out ("restricted setting"), open **App info → ⋮ → Allow restricted
     settings**, then try again.
3. Under **Your list**, paste your list's link and tap **Save**. In the quiz it's under the person icon → account →
   share **Raw text** (`…/h/list/CODE.txt`) or **Word list** (`…/h/#pick=CODE`); the code alone works too.
   Paste the **Edit list** link (same panel) instead to also sign the panel's quiz in to your list, at once: words
   you add in the panel and answers you give there then sync like in Chrome. The link carries your list's edit key,
   which stays on the phone (the panel's quiz keeps it in its own storage; the app doesn't).
4. Open a Chinese page in Chrome. The settings screen lists every app seen and whether it gives
   per-character boxes.

The marks never take a touch: everything under them works as usual. Only in look mode (after a tap on the
**学 look** tile, or on the optional floating 学 button) does the next tap go to 学: on a character it opens the
panel, anywhere else it ends look mode. Add the tile from 学's settings (Android 13+) or by editing Quick Settings. Turn them off with the **Show marks** switch (look mode still works, showing them while it lasts), or turn the service off in Accessibility.

Sideload only: Google Play restricts accessibility services outside disability tools.

## Your list and levels

The app reads (never writes) two pastes on [pastebox](https://paste.varie.lol): your word list, by the code you
type, and your quiz results, found through the list's `# stats: <code>` header line, which the quiz writes. Each
character of a listed word is yours; its level is computed as in the quiz from its last answers: none yet → New,
else `min(5, 1 + floor(share wrong × 5))`. Results kept for whole words don't colour single characters.
The app syncs every 15 minutes while it runs, or on **Sync now**, and keeps the last copy for offline use.

## Privacy

The service reads the text of the app in front of you only to find Chinese characters and where they are.
Nothing it reads is stored or sent. Network calls: reading your list and results from paste.varie.lol, and the
**Update** button, which downloads the public frequency file from varie.lol/h.

## Build

The Android build tools don't run on the dev machine (a Raspberry Pi), so builds run on GitHub Actions
(`.github/workflows/build.yml`): every push to `main` builds a debug APK and publishes it as release `v0.1.<run number>`.

Each build is signed with the runner's throwaway debug key unless these repo secrets are set, so without them
an update fails (in Obtainium too) until the old app is uninstalled:
`XUE_KEYSTORE_BASE64` (base64 of a PKCS#12 keystore) and `XUE_KEYSTORE_PASSWORD`; the key alias is `xue`
(override with `XUE_KEY_ALIAS`).

## Data and credits

- `app/src/main/assets/wfreq.txt`: a copy of [varie.lol/h/wfreq.txt](https://varie.lol/h/wfreq.txt), built by the
  varie repo from [jieba](https://github.com/fxsjy/jieba)'s `dict.txt` (MIT). A character's ƒ is its own line
  (how often it is a word on its own); characters missing from the file count as F5.
- Colours from varie.lol/h.
- OCR: [ML Kit Text Recognition v2](https://developers.google.com/ml-kit/vision/text-recognition/v2), Chinese
  model, through Google Play services (Google's ML Kit terms).
