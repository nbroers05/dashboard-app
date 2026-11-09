# Nog reageren (Android)

Klein appje voor de S24 dat het vak Nog reageren in de planner vult. Het stuurt alleen naam, app en tijd naar Supabase, nooit de berichttekst. Het werkt ook als de pc uit staat.

- Melding van een chat-app = regel in de planner.
- Melding aangetikt, of de app haalt hem zelf weg omdat je de chat opent = regel weg.
- Zelf weggeveegd of alles wissen = regel blijft staan.

## Eenmalig: de sleutel instellen (op de pc)

Elke versie moet met dezelfde sleutel ondertekend zijn, anders kun je niet bijwerken. Dit maakt de sleutel, zet hem als geheim op GitHub en start de bouw:

```bash
python -m pip install cryptography
```

```bash
python "C:\Users\broer\Documents\Claude eigen mapje\dashboard-app\android\maak-sleutel.py"
```

Doe dit maar één keer. Een nieuwe sleutel betekent dat je de app eerst moet verwijderen voordat je een nieuwe versie kunt installeren.

## Installeren (op de telefoon)

1. Open in Firefox: https://github.com/nbroers05/dashboard-app/releases/download/reageren-v0.1.0/nog-reageren.apk
2. Open het gedownloade bestand. Vraagt Android om toestemming voor Firefox, zet "Toestaan vanaf deze bron" aan.
3. Open de app en tik op **Toegang tot meldingen geven**.
4. Staat de schakelaar grijs of krijg je "Beperkte instelling": ga terug, houd het pictogram van Nog reageren ingedrukt, kies **App-info**, tik rechtsboven op **⋮** en kies **Beperkte instellingen toestaan**. Daarna lukt stap 3 wel.
5. Log in met de mail en het wachtwoord van de planner.
6. Tik op **Accu-instelling openen**, ga naar **Accu** en kies **Onbeperkt**.

Heb je `supabase.sql` nog niet opnieuw gedraaid, dan staat er in de app "Tabel niet gevonden".

## Als iets niet klopt

Onder **Laatste meldingen** in de app staat wat er met elke melding gebeurde, zonder namen en zonder tekst. Stuur daar een screenshot van.

Standaard staan WhatsApp, Instagram en Snapchat aan en groepen uit. Dat stel je in de app in.

## Versies

| Versie | Wat |
|---|---|
| 0.1.0 | Eerste versie: meldingen lezen, regels in Supabase zetten en weghalen, apps en groepen instellen. |

Nieuwe versie: `versionName` en `versionCode` in `app/build.gradle.kts` ophogen. Elke push in deze map bouwt de app en zet hem bij de download van die versie.
