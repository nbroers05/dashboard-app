# Changelog

## 0.1.1 (2 nov 2026)

- Titelbalk werkte niet: Tauri vergelijkt de rechten van een website alleen op de origin (`https://nbroers05.github.io`), niet op het pad. Het patroon `…/Dashboard/*` paste daardoor nooit en elke vensteropdracht werd geweigerd ("not allowed by ACL"). Nu staat er de origin; binnen die origin laat de app alleen `/Dashboard/` toe.
- Links met `target=_blank` deden niets: de opener-plugin onderschepte de klik en vroeg via IPC om te openen, wat de site (terecht) niet mag. Dat onderscheppen staat uit; de app vangt het nieuwe venster zelf op en opent het in de standaardbrowser.
- Links naar andere sites openen nu op een eigen thread in de standaardbrowser, en elke poging komt in `%APPDATA%\io.github.nbroers05.dashboard\logs\app.log`.

## 0.1.0 (2 nov 2026)

Eerste versie (fase 1 van het grote idee "App (pc eerst)").

- Venster zonder Windows-rand dat `https://nbroers05.github.io/Dashboard/?app=pc` laadt; de titelbalk tekent het dashboard zelf
- Onthoudt positie, maat, gemaximaliseerd en schermvullend
- Eén exemplaar: tweede start = bestaand venster naar voren
- Systeemvak: klik = tonen/verbergen; menu met Tonen, Opnieuw laden, Automatisch opstarten (standaard uit), Afsluiten
- Sluitknop = naar het systeemvak
- Dashboard en Spotify-inlog blijven in de app; andere links en nieuwe vensters gaan naar de standaardbrowser
- Website mag alleen de titelbalk-vensterrechten gebruiken, en alleen vanaf `https://nbroers05.github.io/Dashboard/*`
- Gebouwd door GitHub Actions; niet ondertekend
