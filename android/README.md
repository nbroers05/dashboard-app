# Phone app (Android)

The only app on the S24, in the Huisstijl (Noah Huisstijl, Jetpack Compose). Five tabs at the bottom, with a mini player above them while something is playing (not on Media; tap = to Media, button = pause):

- **Today**: greeting and clock, the day as chips (appie, rain, Canvas), weather with rain alert, appie (today or the next shift, hours this week and net pay), Canvas with check marks (the same as on the PC), notifications (still to reply, Episodes Left, mail), to-do (tap = the list, the same as on the PC) and check-in. When a newer app version exists, an **update.exe** window with "New version — install" appears here.
- **Planner**: list, day or week. Day strip Mon to Sun with dots per kind (appie, lecture, deadline, task); tap = that day. List: two weeks as rows per day, with a rain check under every appie shift ("leave 16:35 dry · 19:00 showers") and a countdown for a Canvas deadline; you check off tasks. Day: timeline with the Standard week blocks (dotted = automatic) and a red now line. Week: seven columns. At the bottom habits to check off, with a streak. From the planner's Supabase (same account as Still to reply) and the phone's calendar. Adding or dragging tasks is still done on the website.
- **Media**: filter chips (all, film, series, f1, feeds). Big player with lyrics (LRCLIB, follows along), film with tabs tonight (from the dashboard, with cinema) and last watched (Letterboxd), Episodes Left (status from the PC), F1 with countdown (Jolpica), friends (Letterboxd), Spotify month (from the dashboard) and feeds (news, film news, YouTube).
- **Data**: fill in a check-in (the same four questions as on the PC: mood, energy, focus, stress, plus a note; goes to the private archive, without a network first into a queue), key numbers (Spotify this week, appie this month, outside, steps), PC hours this week as bars, Memory Lane (tap = another year) and "All charts" to My data. Reads the private archive with the archive token from the login bundle (vault.json), which the app gets when linking with the passphrase.
- **Reply**: Still to reply with tabs per app, how long something has been waiting (red after 2 days), a cross to remove it. **settings** at the top right.

Where it comes from: music and calendar from the phone itself; weather and rain directly (Open-Meteo, Buienradar); Still to reply from the planner's Supabase; Canvas, to-do, Episodes, mail, check-ins and the AH wage from the dashboard's Supabase (`_phone.status`, `_phone.wage`; the old keys `_telefoon.stand` and `_telefoon.loon` as fallback). The dashboard on the PC prepares Canvas, Episodes, mail and check-ins, so those lag behind while the PC is off. The app calculates the net pay itself with the same formula as the dashboard (`Loon.kt` = `widgets/ah.js`).

Poster style, and Titelkaart when the phone is set to dark.

The notification reader of Still to reply works as before: notification from a chat app = row; tapped or chat opened = row gone; swiped away yourself or clear all = stays. The last message is only kept in Supabase and never goes to the archive.

## Updating

From 0.8.0 the app updates itself: when you open it, it checks GitHub for the newest release `telefoon-v…`. Is there a newer one, Today and Settings show **New version — install**. Tap it; the first time Android asks to allow installing from Dashboard: turn on **Allow from this source**, go back and tap the button again. Then tap **Update** in Android's installer. Settings › update.exe › **Check now** checks by hand.

## Installing the first time (or 0.7.1 → 0.8.0, by hand)

1. Open in Firefox: https://github.com/nbroers05/dashboard-app/releases/download/telefoon-v0.8.0/dashboard-telefoon.apk
2. Open the downloaded file and tap **Update** (or Install). If Android asks for permission for Firefox, turn on "Allow from this source".
3. Open the app. On **Today** tap **Use the phone's calendar** once and choose Allow.
4. If Still to reply was already on it: login and notification access stay. If not: Reply › settings › **Give notification access** and log in with the planner's email and password.
5. If the access switch is grey or you get "Restricted setting": long-press the icon, choose **App info**, tap **⋮** at the top right and choose **Allow restricted settings**.
6. Battery: Reply › settings › **Open battery setting**, go to **Battery** and choose **Unrestricted**.
7. Link the dashboard (once): Reply › settings › **dashboard.exe**, type your passphrase (the same as when logging in on the dashboard) and tap **Link**. That takes a few seconds. The passphrase itself is not stored.

The app is called **Dashboard** and has the 窓 icon. It's the same app as Still to reply (same name in Android, same key), so updating goes over the old one.

If Today shows no lectures or Canvas: the Samsung calendar doesn't sync those calendars. Calendar app › Settings › Manage calendars, and turn them on.

## Once: the key (already done)

Every version must be signed with the same key. It is stored as a secret on GitHub (`maak-sleutel.py`, only needed if it's ever lost; a new key means removing the app first).

## If something isn't right

Reply › settings › **notifications.exe** shows what happened to each notification, without names and without text. Send a screenshot of it.

## Versions

| Version | What |
|---|---|
| 0.8.0 | English screen text. Reads `_phone.status` and `_phone.wage` (English wage fields; the old Dutch keys as fallback). Auto-update: checks GitHub for a newer `telefoon-v…` release on start and from Settings, downloads it and opens Android's installer. |
| 0.7.1 | Planner closed the app as soon as something without a time was in the list (all-day event or task without a time): fixed. New: crash log under Reply › settings (crash.exe), to pass on if the app ever closes. |
| 0.7.0 | **Phase D**: the Planner tab like the mockup (list, day, week, day strip, rain check, deadlines, habits). With that all five tabs exist. |
| 0.6.0 | **Phase C**: the Data tab like the mockup (check-in, key numbers, PC hours, Memory Lane). Linking with the passphrase now also gets the archive token from the login bundle. |
| 0.5.0 | **Phase B**: the Media tab like the mockup (player with lyrics, film, Episodes Left, F1, friends, Spotify month, feeds). |
| 0.4.0 | **Phase A of the new mockup**: five tabs (Today, Planner, Media, Data, Reply) and a mini player. Linking with the dashboard via your passphrase. |
| 0.3.0 | **Phone app, phase 1**: in the Huisstijl with three tabs. Name Dashboard, icon 窓. The download is called `dashboard-telefoon.apk` (tag `telefoon-v…`). |
| 0.2.0 | Hinge, Tinder and Bumble added. Snapchat notifications without a real sender left out. Last message under the name when the app sends the text along. |
| 0.1.0 | First version: read notifications, put rows in Supabase and remove them, set apps and groups. |

New version: raise `versionName` and `versionCode` in `app/build.gradle.kts`. Every push in this folder builds the app and puts it with the download of that version. Building locally isn't possible (no Android SDK on the PC); read errors with `gh run view <id> --log-failed`.
