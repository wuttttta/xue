# 学 (xue)

An Android app that marks the Chinese characters on screen, in any app, with a light underline coloured by
how common each character is (ƒ, F1 commonest … F5 rarest). Companion to the quiz at
[varie.lol/h](https://varie.lol/h/), with the same colours.

Status: **step 1** of the plan (accessibility service + frequency layer). Next: your own levels from the quiz,
a "look" mode with the quiz card on a tap, and OCR for text in images.

## Install

[<img src="https://raw.githubusercontent.com/ImranR98/Obtainium/main/assets/graphics/badge_obtainium.png" alt="Get it on Obtainium" height="54">](https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/wuttttta/xue)

1. With [Obtainium](https://github.com/ImranR98/Obtainium) on the phone, tap the button above: it adds the app and
   keeps it updated from this repo's releases. Or download [`xue.apk` from the latest release](https://github.com/wuttttta/xue/releases/latest/download/xue.apk)
   and install it (allow installing from your browser when asked).
2. Open 学 → **Turn on in accessibility settings** → Installed apps / Downloaded apps → **学 marks** → on.
   - Android 13+: if the switch is greyed out ("restricted setting"), open **App info → ⋮ → Allow restricted
     settings**, then try again.
3. Open a Chinese page in Chrome. The settings screen lists every app seen and whether it gives
   per-character boxes.

The marks never take a touch: everything under them works as usual. Turn them off with the **Show marks**
switch, or turn the service off in Accessibility.

Sideload only: Google Play restricts accessibility services outside disability tools.

## Privacy

The service reads the text of the app in front of you only to find Chinese characters and where they are.
Nothing is stored or sent; the only network call is the **Update** button, which downloads the public
frequency file from varie.lol/h.

## Build

The Android build tools don't run on the dev machine (a Raspberry Pi), so builds run on GitHub Actions
(`.github/workflows/build.yml`): every push to `main` builds a debug APK and publishes it as release `v0.1.<run number>`.

Each build is signed with the runner's throwaway debug key unless these repo secrets are set, so without them
an update fails (in Obtainium too) until the old app is uninstalled:
`XUE_KEYSTORE_BASE64` (base64 of a `.jks`), `XUE_KEYSTORE_PASSWORD`, `XUE_KEY_ALIAS`.

## Data and credits

- `app/src/main/assets/wfreq.txt`: a copy of [varie.lol/h/wfreq.txt](https://varie.lol/h/wfreq.txt), built by the
  varie repo from [jieba](https://github.com/fxsjy/jieba)'s `dict.txt` (MIT). A character's ƒ is its own line
  (how often it is a word on its own); characters missing from the file count as F5.
- Colours from varie.lol/h.
