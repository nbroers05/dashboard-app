# Telefoon-app (Android)

De enige app op de S24, in de huisstijl (Noah Huisstijl, Jetpack Compose). Onderin vijf tabjes, met een minispeler erboven zolang er iets speelt (niet op Media; tik = naar Media, knop = pauze):

- **Vandaag**: groet en klok, de dag als chips (appie, regen, Canvas), weer met buienalarm, appie (vandaag of de volgende dienst, uren deze week en netto), Canvas met vinkjes (dezelfde als op de pc), meldingen (nog reageren, Episodes Left, mail), to-do (tik = de lijst, dezelfde als op de pc) en check-in.
- **Planner**: komt in een volgende fase. Nu een knop naar de planner op de website.
- **Media**: filterchips (alles, film, series, f1, feeds). Grote speler met songtekst (LRCLIB, loopt mee), film met tabjes vanavond (van het dashboard, met bios) en laatst gekeken (Letterboxd), Episodes Left (stand van de pc), F1 met aftellen (Jolpica), vrienden (Letterboxd), Spotify-maand (van het dashboard) en feeds (nieuws, filmnieuws, YouTube).
- **Data**: check-in invullen (dezelfde vier vragen als op de pc: stemming, energie, focus, stress, plus een notitie; gaat naar het privé archief, zonder netwerk eerst in een wachtrij), kerncijfers (Spotify deze week, appie deze maand, buiten, stappen), pc-uren deze week als staafjes, Memory Lane (tik = ander jaar) en "Alle grafieken" naar Mijn data. Leest het privé archief met het archief-token uit de inlogbundel (vault.json), dat de app bij het koppelen met de wachtzin ophaalt.
- **Reageren**: Nog reageren met tabjes per app, hoe lang iets al wacht (rood na 2 dagen), kruisje om weg te halen. Rechtsboven **instellingen**.

Waar het vandaan komt: muziek en agenda van de telefoon zelf; weer en regen rechtstreeks (Open-Meteo, Buienradar); Nog reageren uit de Supabase van de planner; Canvas, to-do, Episodes, mail, check-ins en het AH-loon uit de Supabase van het dashboard (het dashboard op de pc zet Canvas, Episodes, mail en check-ins klaar, dus die lopen achter als de pc uit staat). De netto rekent de app zelf uit met dezelfde formule als het dashboard.

Poster, en Titelkaart als de telefoon op donker staat.

De meldingenlezer van Nog reageren werkt zoals eerst: melding van een chat-app = regel; aangetikt of chat geopend = regel weg; zelf weggeveegd of alles wissen = blijft staan. Het laatste bericht staat alleen in Supabase en gaat nooit naar het archief.

## Installeren of bijwerken (op de telefoon)

1. Open in Firefox: https://github.com/nbroers05/dashboard-app/releases/download/telefoon-v0.6.0/dashboard-telefoon.apk
2. Open het gedownloade bestand en tik op **Bijwerken** (of Installeren). Vraagt Android om toestemming voor Firefox, zet "Toestaan vanaf deze bron" aan.
3. Open de app. Op **Vandaag** tik je één keer op **Agenda van de telefoon gebruiken** en kies je Toestaan.
4. Stond Nog reageren er al op: inloggen en toegang tot meldingen blijven staan. Zo niet: Reageren › instellingen › **Toegang tot meldingen geven** en inloggen met de mail en het wachtwoord van de planner.
5. Staat de schakelaar bij toegang grijs of krijg je "Beperkte instelling": houd het pictogram ingedrukt, kies **App-info**, tik rechtsboven op **⋮** en kies **Beperkte instellingen toestaan**.
6. Accu: Reageren › instellingen › **Accu-instelling openen**, ga naar **Accu** en kies **Onbeperkt**.
7. Dashboard koppelen (één keer; wie al gekoppeld was vóór 0.6.0 doet het op het Data-tabje nog één keer voor het archief): Reageren › instellingen › **dashboard.exe**, typ je wachtzin (dezelfde als bij inloggen op het dashboard) en tik op **Koppelen**. Dat duurt een paar seconden. De wachtzin zelf wordt niet bewaard.

De app heet nu **Dashboard** en heeft het 窓-icoon. Het is dezelfde app als Nog reageren (zelfde naam in Android, zelfde sleutel), dus bijwerken gaat over de oude heen.

Zie je bij Vandaag geen colleges of Canvas: dan synchroniseert de Samsung-agenda die agenda's niet. Agenda-app › Instellingen › Agenda's beheren, en zet ze aan.

## Eenmalig: de sleutel (al gedaan)

Elke versie moet met dezelfde sleutel ondertekend zijn. Die staat als geheim op GitHub (`maak-sleutel.py`, alleen nodig als hij ooit kwijt is; een nieuwe sleutel betekent eerst de app verwijderen).

## Als iets niet klopt

Reageren › instellingen › **meldingen.exe** toont wat er met elke melding gebeurde, zonder namen en zonder tekst. Stuur daar een screenshot van.

## Versies

| Versie | Wat |
|---|---|
| 0.6.0 | **Fase C**: het Data-tabje zoals de mockup (check-in, kerncijfers, pc-uren, Memory Lane). Koppelen met de wachtzin haalt nu ook het archief-token uit de inlogbundel. |
| 0.5.0 | **Fase B**: het Media-tabje zoals de mockup (speler met songtekst, film, Episodes Left, F1, vrienden, Spotify-maand, feeds). |
| 0.4.0 | **Fase A van de nieuwe mockup**: vijf tabjes (Vandaag, Planner, Media, Data, Reageren) en een minispeler. Vandaag zoals de mockup: groet en klok, chips, weer, appie met netto, Canvas met vinkjes, meldingen (reageren, Episodes, mail), to-do en check-in. Koppelen met het dashboard via je wachtzin. Media, Data en Planner zijn nog eenvoudig. |
| 0.3.0 | **Telefoon-app, fase 1**: in de huisstijl met drie tabjes. Vandaag (speler, weer, AH-uren, rest van de dag, Canvas), Reageren (lijst uit Supabase met tabjes per app), instellingen als eigen scherm. Naam Dashboard, icoon 窓. Download heet nu `dashboard-telefoon.apk` (tag `telefoon-v…`). |
| 0.2.0 | Hinge, Tinder en Bumble erbij. Snapchat-meldingen zonder echte afzender eruit. Laatste bericht onder de naam als de app de tekst meestuurt. |
| 0.1.0 | Eerste versie: meldingen lezen, regels in Supabase zetten en weghalen, apps en groepen instellen. |

Nieuwe versie: `versionName` en `versionCode` in `app/build.gradle.kts` ophogen. Elke push in deze map bouwt de app en zet hem bij de download van die versie. Lokaal bouwen kan niet (geen Android SDK op de pc); fouten lezen met `gh run view <id> --log-failed`.
