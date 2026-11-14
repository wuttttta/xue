# 学 (xue)

An Android app that marks the Chinese characters on screen, in any app, with a light underline. Characters in
your list get your level's colour from the quiz at [varie.lol/h](https://varie.lol/h/) (warm: New grey, L1
yellow … L5 dark red, by how often you get them wrong); every other character gets a blue by how common it is
(ƒ, F1 commonest … F5 rarest). Same colours as the quiz.

Tap the floating 学 button, then any marked character: the quiz's card for it opens in a sheet over the app.

Status: steps 1–3 of the plan (marks by frequency, your levels, look mode with the panel). Next: OCR for text
in images.

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
   Paste the **Edit list** link (same panel) instead to also sign the panel's quiz in to your list: it carries
   your list's edit key, which stays on the phone (it is handed to the panel's quiz once, then forgotten by the app).
4. Open a Chinese page in Chrome. The settings screen lists every app seen and whether it gives
   per-character boxes.

The marks never take a touch: everything under them works as usual. Only in look mode (after a tap on the
floating 学 button) does the next tap go to 学: on a character it opens the panel, anywhere else it ends look mode. Turn them and the button off with the
**Show marks** switch, or turn the service off in Accessibility.

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
