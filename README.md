# Sweet 97.7 LED: Android TV app

Shows the website's LED screen (`https://sweet977.freehosting.dev/#led`) full screen on the Android TV that drives the LED wall.

- **Opens by itself** when the TV turns on (on Android 10 and later this needs "Display over other apps", which the app asks for once).
- **Made for slow internet**: the page, its code, fonts, pictures and videos are saved on the TV the first time they are used and loaded from the TV after that. Only the small live updates come over the internet.
- **Never blanks**: if the internet drops, the last screen keeps running and catches up when the connection returns.
- **Looks after itself**: reloads a frozen page, restarts if the page engine crashes, refreshes once a night at 4 AM.
- **Updates**: checks this project's releases once a day and downloads a newer version quietly. Nothing pops up on the LED wall; it installs when someone presses OK on the remote and picks *Install the new app version*.

## Remote control

| Button | Does |
|---|---|
| OK (or Menu) | Opens the menu: reload, open-at-power-on on/off, update, clear saved files, about, close |
| Back twice | Closes the app |

## Building

GitHub builds it: every change to `main` (or **Actions → Build TV app → Run workflow**) produces a signed APK and publishes it as the latest release:

`https://github.com/stephjoshph/sweet977-tv/releases/latest/download/sweet977-tv.apk`

The build needs one repository secret, **TV_SIGNING** (Settings → Secrets and variables → Actions). Keep a copy of it somewhere safe: every future version must be signed with the same key, or it won't install over the old one.

To point the app at a different website, change `SITE` in `app/src/main/java/fm/sweet977/tv/Config.java`.
