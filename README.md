# Dashboard-app

Een kleine Windows-app (Tauri 2) die het dashboard laadt: https://nbroers05.github.io/Dashboard/?app=pc

De app is alleen een raam. Het dashboard zelf blijft op GitHub Pages, dus elke nieuwe dashboardversie staat meteen in de app (Ctrl+F5 of systeemvak › Opnieuw laden). Een nieuwe app hoef je alleen te installeren als er in deze repo iets verandert.

## Installeren

1. Ga naar **Releases** rechts op https://github.com/nbroers05/dashboard-app en open de nieuwste (bijvoorbeeld `v0.1.0`).
2. Klik onder **Assets** op het bestand dat eindigt op `_x64-setup.exe` (bijvoorbeeld `Dashboard_0.1.0_x64-setup.exe`). Firefox zet het in Downloads.
3. Dubbelklik het bestand. Windows laat een blauw scherm zien: **"Windows heeft uw pc beschermd"** (SmartScreen). Dat komt doordat de app niet ondertekend is (een certificaat kost geld per jaar).
   - Klik op **Meer informatie**
   - Klik op **Toch uitvoeren**
4. Kies de taal, klik **Volgende** › **Installeren** › **Voltooien**. Er is geen beheerderswachtwoord nodig (installeert alleen voor jou).
5. De app start met het inlogscherm van het dashboard: log één keer in met je **wachtzin** en kies "Onthouden". De app heeft zijn eigen opslag, los van Firefox.
6. Wil je dat hij met Windows meestart: klik met rechts op het icoon in het systeemvak (rechtsonder, eventueel achter het pijltje **^**) › **Automatisch opstarten**.

Bijwerken = de nieuwe `_x64-setup.exe` downloaden en installeren; je inlog blijft bewaard.
Verwijderen = Windows-instellingen › Apps › Geïnstalleerde apps › Dashboard › Verwijderen.

## Gebruik

- **Titelbalk**: tekent het dashboard zelf (Y2K = XP-titelbalk, Modern = smalle donkere balk). Slepen om te verplaatsen, dubbelklik = maximaliseren, knoppen rechts: minimaliseren, maximaliseren, sluiten.
- **Sluiten** (✕) verbergt de app naar het systeemvak; hij blijft draaien. Echt afsluiten: systeemvak › **Afsluiten**.
- **F11** = schermvullend aan/uit.
- **Systeemvak**: klik = tonen/verbergen. Rechtsklik = Tonen, Opnieuw laden, Automatisch opstarten (standaard uit), Afsluiten.
- De app onthoudt waar het venster stond en hoe groot het was.
- Start je de app een tweede keer, dan komt het bestaande venster naar voren.
- Links naar andere sites (Feeds, snelkoppelingen, zoeken) openen in je standaardbrowser (Firefox). Alleen het dashboard en de Spotify-inlog blijven in de app.
- **Werkt niet in de app** (tot fase 3): de Firefox-extensies **Episodes Left** en **Sterrenvoorspeller**. Die vakken blijven in de app leeg of tonen de oude stand; in Firefox werken ze gewoon.

## Hoe het gebouwd wordt

Er is geen Rust of Node nodig op de pc: GitHub Actions bouwt alles (`.github/workflows/build.yml`, op `windows-latest` met `tauri-action`).

- Elke push naar `main`: de exe en de installer komen als **artifact** bij de run (tabblad Actions › run › Artifacts, `dashboard-app-windows`).
- Een tag `v*` (bijvoorbeeld `v0.1.1`): ook een **release** met de installer.

Nieuwe versie maken: versie ophogen in `src-tauri/tauri.conf.json`, `src-tauri/Cargo.toml` en `package.json`, regel in `CHANGELOG.md`, dan `git tag v0.1.1` en `git push --tags`.

## Wat de website mag

Alleen pagina's op precies `https://nbroers05.github.io/Dashboard/*` mogen de app iets vragen (`src-tauri/capabilities/default.json`), en alleen voor de titelbalk: slepen, minimaliseren, maximaliseren, sluiten en schermvullend. Geen bestanden, geen commando's, niets anders. Andere sites (ook de Spotify-inlog) mogen niets.

## Bestanden

| Bestand | Wat |
|---|---|
| `src-tauri/src/lib.rs` | Venster (zonder Windows-rand), systeemvak, sluiten = verbergen, welke links in de app blijven |
| `src-tauri/tauri.conf.json` | Naam, versie, installer (NSIS, alleen voor de huidige gebruiker) |
| `src-tauri/capabilities/default.json` | Rechten van de website (zie hierboven) |
| `src-tauri/icons/` | Hetzelfde logo als het dashboard (met Pillow getekend uit `favicon.svg`) |
| `dist/index.html` | Verplicht leeg pagina'tje voor Tauri; wordt niet getoond |
