# Telefoon-app (Android)

De enige app op de S24, in de huisstijl (Noah Huisstijl, Jetpack Compose). Onderin drie tabjes:

- **Vandaag**: wat er speelt (van de telefoon zelf: Spotify, YouTube, alles met een mediasessie), weer en regen (Open-Meteo en Buienradar, rechtstreeks), AH-uren deze week, de rest van de dag en Canvas-deadlines uit de agenda van de telefoon. Niks koppelen.
- **Planner**: komt in de volgende fase. Nu een knop naar de planner op de website.
- **Reageren**: Nog reageren met tabjes per app (WhatsApp, Snap, Insta, Dating), hoe lang iets al wacht (rood na 2 dagen), met een kruisje om een regel weg te halen. Rechtsboven **instellingen**: toegang, inloggen, welke apps, laatste meldingen.

Poster, en Titelkaart als de telefoon op donker staat.

De meldingenlezer van Nog reageren werkt zoals eerst: melding van een chat-app = regel; aangetikt of chat geopend = regel weg; zelf weggeveegd of alles wissen = blijft staan. Het laatste bericht staat alleen in Supabase en gaat nooit naar het archief.

## Installeren of bijwerken (op de telefoon)

1. Open in Firefox: https://github.com/nbroers05/dashboard-app/releases/download/telefoon-v0.3.0/dashboard-telefoon.apk
2. Open het gedownloade bestand en tik op **Bijwerken** (of Installeren). Vraagt Android om toestemming voor Firefox, zet "Toestaan vanaf deze bron" aan.
3. Open de app. Op **Vandaag** tik je één keer op **Agenda van de telefoon gebruiken** en kies je Toestaan.
4. Stond Nog reageren er al op: inloggen en toegang tot meldingen blijven staan. Zo niet: Reageren › instellingen › **Toegang tot meldingen geven** en inloggen met de mail en het wachtwoord van de planner.
5. Staat de schakelaar bij toegang grijs of krijg je "Beperkte instelling": houd het pictogram ingedrukt, kies **App-info**, tik rechtsboven op **⋮** en kies **Beperkte instellingen toestaan**.
6. Accu: Reageren › instellingen › **Accu-instelling openen**, ga naar **Accu** en kies **Onbeperkt**.

De app heet nu **Dashboard** en heeft het 窓-icoon. Het is dezelfde app als Nog reageren (zelfde naam in Android, zelfde sleutel), dus bijwerken gaat over de oude heen.

Zie je bij Vandaag geen colleges of Canvas: dan synchroniseert de Samsung-agenda die agenda's niet. Agenda-app › Instellingen › Agenda's beheren, en zet ze aan.

## Eenmalig: de sleutel (al gedaan)

Elke versie moet met dezelfde sleutel ondertekend zijn. Die staat als geheim op GitHub (`maak-sleutel.py`, alleen nodig als hij ooit kwijt is; een nieuwe sleutel betekent eerst de app verwijderen).

## Als iets niet klopt

Reageren › instellingen › **meldingen.exe** toont wat er met elke melding gebeurde, zonder namen en zonder tekst. Stuur daar een screenshot van.

## Versies

| Versie | Wat |
|---|---|
| 0.3.0 | **Telefoon-app, fase 1**: in de huisstijl met drie tabjes. Vandaag (speler, weer, AH-uren, rest van de dag, Canvas), Reageren (lijst uit Supabase met tabjes per app), instellingen als eigen scherm. Naam Dashboard, icoon 窓. Download heet nu `dashboard-telefoon.apk` (tag `telefoon-v…`). |
| 0.2.0 | Hinge, Tinder en Bumble erbij. Snapchat-meldingen zonder echte afzender eruit. Laatste bericht onder de naam als de app de tekst meestuurt. |
| 0.1.0 | Eerste versie: meldingen lezen, regels in Supabase zetten en weghalen, apps en groepen instellen. |

Nieuwe versie: `versionName` en `versionCode` in `app/build.gradle.kts` ophogen. Elke push in deze map bouwt de app en zet hem bij de download van die versie. Lokaal bouwen kan niet (geen Android SDK op de pc); fouten lezen met `gh run view <id> --log-failed`.
