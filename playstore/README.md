# Play Store Publishing — Schreibwerkstatt (Android)

Arbeitsmappe für den Play-Console-Upload; hier liegen die Console-Inhalte.

## Build für die Console

```
./gradlew bundlePlayRelease
# → app/build/outputs/bundle/playRelease/app-play-release.aab
```

**Zwingend der `play`-Flavor.** Der `github`-Flavor enthält das In-App-Update
(`REQUEST_INSTALL_PACKAGES` + Installer-Intent) und wäre ein Verstoss gegen Googles
„Device and Network Abuse" — siehe CLAUDE.md „Vertriebskanäle". Gegenprobe am fertigen AAB:

```
unzip -p …/app-play-release.aab base/dex/classes.dex | strings | grep -c package-archive   # muss 0 sein
```

Vor dem Build prüfen, dass `demo.properties` und `keystore.properties` im Root liegen
(sonst fehlt der Demo-Button bzw. ist das AAB unsigniert).

## App-Signatur (beim ersten Upload, danach nicht mehr änderbar)

**Ziel:** Play signiert mit **demselben Schlüssel** wie die GitHub-APKs
(`upload-keystore.jks`, Alias `upload`, SHA-256 `91:A6:8F:11:…:8A:00:7A`). Dann sind
Play- und GitHub-Build untereinander update-kompatibel, und die Paket-Registrierung der
Entwicklerverifizierung (gleicher Fingerabdruck) passt ohne zweiten Eintrag.
Lässt man Google einen neuen Schlüssel erzeugen, muss jeder, der den Kanal wechselt,
deinstallieren — und verliert dabei nicht synchronisierte Offline-Änderungen.

Ablauf:
1. Console → App → *Testen → Geschlossener Test* → Release erstellen → bei
   **„App-Signatur durch Google Play"** auf *Signaturschlüssel ändern* →
   **„Schlüssel aus Java-Keystore exportieren und hochladen"**.
2. `pepk.jar` und `encryption_public_key.pem` herunterladen (die `.pem` ist pro App
   eindeutig — nicht wiederverwenden).
3. `scripts/play-export-signing-key.sh <pepk.jar> <encryption_public_key.pem>` — fragt
   Keystore- und Key-Passwort ab (stehen in `keystore.properties`) und schreibt
   `play-signing-key.zip` ins Scratch-Verzeichnis (nie ins Repo).
4. Die ZIP in der Console hochladen. **Keinen separaten Upload-Schlüssel** angeben: dann
   gilt derselbe Schlüssel auch als Upload-Schlüssel, und `bundlePlayRelease` signiert
   wie bisher über `keystore.properties`.
5. ZIP danach löschen. In der Console unter *App-Integrität* prüfen, dass der
   SHA-256 des App-Signaturschlüssels `91:A6:8F:11:…` lautet.

## Assets (`assets/`)
| Datei | Verwendung | Format |
|---|---|---|
| `icon-512.png` | App-Icon (Store) | 512×512, deckend, aus Marken-SVG gerendert |
| `feature-graphic-1024x500.png` | Feature-Graphic | 1024×500 |
| `screenshots-de/01-buecher.png` | Phone-Screenshot: Bücherliste | 1080×2160 |
| `screenshots-de/02-gliederung.png` | Phone-Screenshot: Kapitel/Abschnitte | 1080×2160 |
| `screenshots-de/03-editor.png` | Phone-Screenshot: Fokus-Editor | 1080×2160 |
| `screenshots-de/04-recherche.png` | Phone-Screenshot: Recherche-Board | 1080×2160 |

Screenshots: Emulator (Pixel 7, API 35), App-Sprache de-CH, gegen die Demo-Instanz,
Stand 2.15.0. Auf 2:1 beschnitten (Play erlaubt höchstens Seitenverhältnis 2:1),
Status- und Gestenleiste dabei entfernt. Neu aufnehmen, sobald Ideen ausgeliefert sind.

## Datenschutzerklärung
**Datenschutz-URL für die Console:** `https://demo.schreibwerkstatt.app/datenschutz`
(live, aus `routes/public.js` des Mutterprojekts). Sie spricht bislang nur vom
**macOS-Client** — der Android-Abschnitt muss im Mutterprojekt ergänzt werden, **bevor**
die App eingereicht wird (Data Safety und Policy müssen übereinstimmen).
`datenschutz-android.html` ist der Textbaustein dafür, keine separat zu hostende Seite.

## Data Safety (Abschnitt „Datensicherheit")

Was die **App** vom Gerät überträgt — nicht, was der Server sonst über die Web-App
erhebt. Übertragung an den vom Nutzer gewählten Server.

| Console-Kategorie | Was konkret | Erhoben | Geteilt | Optional | Zweck |
|---|---|---|---|---|---|
| App-Aktivitäten → *Sonstige von Nutzern erstellte Inhalte* | Texte, Abschnitte, Ideen, Recherche-Notizen | Ja | Nein | Nein | App-Funktionalität |
| App-Aktivitäten → *Interaktionen mit der App* | Schreibzeit pro Buch (Sekunden, ohne Text) | Ja | Nein | Nein | App-Funktionalität, Analysen |
| Audio → *Sprach- oder Tonaufnahmen* | Diktat-Segmente zur Spracherkennung | Ja | Nein | Ja | App-Funktionalität |
| Fotos und Videos → *Fotos* | Über das Teilen-Menü geteilte Bilder (Recherche) | Ja | Nein | Ja | App-Funktionalität |
| Geräte- oder andere IDs | Installations-UUID (`device_id`), Gerätemodell | Ja | Nein | Nein | App-Funktionalität |

- **Geteilt: Nein.** Die KI-Verarbeitung (Standard Anthropic) läuft im Auftrag des
  Betreibers — Google zählt Dienstleister, die im Auftrag verarbeiten, nicht als
  „Teilen". In der Policy steht sie trotzdem (Abschnitt KI-Verarbeitung).
- **Sitzungsgebunden („ephemeral")** für Audio nur ankreuzen, wenn der Server die
  Aufnahme nach der Transkription verwirft — vom Mutterprojekt bestätigen lassen
  (steht im Prompt-Vorschlag).
- **Verschlüsselung bei der Übertragung: Nein.** Die App erlaubt absichtlich HTTP für
  self-hosted LAN-Server (siehe CLAUDE.md). „Ja" wäre damit nicht wahr; die Policy
  erklärt es.
- **Löschung beantragen: Ja** — formlos per E-Mail, wie in der Policy. Die App legt
  keine Konten an (nur Token-Pairing); eine Lösch-URL ist trotzdem gefragt →
  Datenschutz-URL mit Anker auf den Abschnitt „Deine Rechte"/Löschung.
- Kein Tracking, keine Werbung, keine Weitergabe zu Werbezwecken.

## Übrige Console-Formulare (Antworten)

| Formular | Antwort |
|---|---|
| **App-Zugriff** | „Alle oder einige Funktionen sind eingeschränkt" → Anleitung: *App öffnen → „Demo ausprobieren" tippen* (koppelt automatisch). Fallback: Server-Adresse `https://demo.schreibwerkstatt.app` + Token aus `reviewer-access.local.md` → *Koppeln*. |
| **Werbung** | Nein, enthält keine Werbung. |
| **Inhaltseinstufung (IARC)** | Kategorie *Alle anderen App-Typen* (Produktivität). Gewalt/Sex/Drogen/Glücksspiel/Sprache: alles Nein. Nutzer-Interaktion: **Ja** — Bücher lassen sich mit eingeladenen Lesern teilen; kein öffentlicher Austausch, kein Chat zwischen Nutzern. Standort-Weitergabe: Nein. Digitale Käufe: Nein. Ergebnis erwartet: PEGI 3 / USK 0 bzw. „Jedes Alter" mit Hinweis „Nutzer interagieren". |
| **Zielgruppe** | 18+ (vermeidet die Familienrichtlinien; die App spricht keine Kinder an). Nicht für Kinder ansprechend: Nein. |
| **Nachrichten-App** | Nein. |
| **Behörden-App** | Nein. |
| **Finanzfunktionen** | Keine. |
| **Gesundheit** | Keine Gesundheitsfunktionen. |
| **Berechtigungen** | `RECORD_AUDIO` nur für Diktat, beim ersten Diktat abgefragt. Keine sensiblen Berechtigungen, die ein Erklärformular brauchen. |
| **Kategorie** | App → *Produktivität*. Tags: Notizen, Schreiben. |
| **Kontakt** | E-Mail wie in der Datenschutzerklärung; Website `https://schreibwerkstatt.app`. |

## Berechtigungen (Begründung, falls nachgefragt)
- `RECORD_AUDIO` — Diktatfunktion (Speech-to-Text), nur bei aktiver Nutzung.
- `INTERNET` / `ACCESS_NETWORK_STATE` — Synchronisierung mit dem Server.
- `VIBRATE` — taktiles Feedback bei Diktat-Start/-Stop.
- `WAKE_LOCK` / `RECEIVE_BOOT_COMPLETED` / `FOREGROUND_SERVICE` — von WorkManager
  (periodischer Hintergrund-Pull) eingezogen, nicht selbst deklariert.
- `REQUEST_INSTALL_PACKAGES` ist im Play-Build **nicht** enthalten (nur `github`-Flavor).

## App-Zugriff / Reviewer-Login (PFLICHT, sonst Ablehnung)
Die App ist ein gepairter Client → ohne erreichbaren Server + gültiges Geräte-Token
kommt der Google-Reviewer an keinen Inhalt.

**Zugangsdaten** (Demo-Konto, Web-Passwort, Geräte-Token) stehen in
`playstore/reviewer-access.local.md` — gitignored, weil dieses Repo öffentlich ist.
Token/Passwort deshalb **nie** in dieser Datei, im Listing oder in Screenshots.

**In der App:** Der Pairing-Screen hat einen Button **„Demo ausprobieren"**, der
Adresse + Demo-Token selbst einträgt und koppelt. Die Werte kommen beim Build aus
`demo.properties` im Repo-Root (gitignored); ohne diese Datei fehlt der Button.
**Vor jedem Release-Build für die Console prüfen, dass `demo.properties` vorhanden und
das Token gültig ist** — ein widerrufenes Token heisst Ablehnung.

## Store-Listing — Deutsch (Standardsprache de-CH)

**App-Name (max. 30):** Schreibwerkstatt

**Kurzbeschreibung (max. 80):**
> Dein Buch unterwegs: schreiben, diktieren, recherchieren – auch offline.

**Vollständige Beschreibung (max. 4000):**
> Schreibwerkstatt ist die Android-App zur Schreibplattform Schreibwerkstatt. Du
> schreibst an deinen Büchern weiter, wo immer du gerade bist: im Zug, im Café oder
> ganz ohne Netz.
>
> **Ablenkungsfrei schreiben**
> Der Fokus-Editor blendet alles aus, was nicht zum aktuellen Absatz gehört. Die
> Schreibzeile bleibt in der Bildschirmmitte, auch wenn die Tastatur offen ist.
>
> **Gliederung im Griff**
> Bücher, Kapitel und Abschnitte – übersichtlich wie in der Web-App, mit Suche über
> Titel und Inhalt.
>
> **Diktieren statt tippen**
> Sprich deinen Text ein; er wird erkannt und direkt an der Schreibstelle eingefügt.
>
> **Offline weiterarbeiten**
> Alles, was du schreibst, wird zuerst auf dem Gerät gespeichert und synchronisiert,
> sobald wieder Verbindung besteht. Hat jemand anderes am selben Abschnitt
> gearbeitet, werden die Änderungen zusammengeführt statt überschrieben.
>
> **Recherche festhalten**
> Teile Links und Bilder aus dem Browser oder jeder anderen App direkt ins
> Recherche-Board deines Buchs.
>
> **Verlauf und Rechtschreibung**
> Frühere Fassungen eines Abschnitts ansehen, vergleichen und wiederherstellen.
> Die Rechtschreibprüfung markiert Fehler beim Schreiben.
>
> **Voraussetzung**
> Du brauchst ein Konto auf einem Schreibwerkstatt-Server – etwa schreibwerkstatt.app
> oder deinem eigenen. Das Zugangstoken erstellst du dort unter „Einstellungen →
> Geräte". Ohne Konto kannst du die App mit „Demo ausprobieren" an einem Beispielbuch
> testen.
>
> Keine Werbung, kein Tracking.

## Store-Listing — English (en-US)

**App name:** Schreibwerkstatt

**Short description (max. 80):**
> Your book on the go: write, dictate, research – even offline.

**Full description:**
> Schreibwerkstatt is the Android app for the Schreibwerkstatt writing platform. Keep
> working on your books wherever you are: on the train, in a café, or with no
> connection at all.
>
> **Distraction-free writing**
> The focus editor fades out everything but the paragraph you are working on. Your
> writing line stays in the middle of the screen, even with the keyboard open.
>
> **Your outline at hand**
> Books, chapters and sections – laid out as in the web app, with search across
> titles and content.
>
> **Dictate instead of typing**
> Speak your text; it is transcribed and inserted right where you are writing.
>
> **Keep working offline**
> Everything you write is saved on your device first and synced as soon as you are
> back online. If someone else worked on the same section, changes are merged
> instead of overwritten.
>
> **Capture research**
> Share links and images from your browser or any other app straight into your
> book's research board.
>
> **History and spell check**
> View, compare and restore earlier versions of a section. Spell check flags
> mistakes as you type.
>
> **Requirements**
> You need an account on a Schreibwerkstatt server – such as schreibwerkstatt.app or
> your own. Create the access token there under "Settings → Devices". No account?
> Tap "Try the demo" to explore a sample book.
>
> No ads, no tracking.

Sobald die Ideen-Funktion ausgeliefert ist, einen Absatz ergänzen
(„Ideen festhalten: über Schnelleinstellungen, App-Shortcut oder Teilen-Menü").

## Noch offen
1. **Signaturschlüssel** beim ersten Upload wie oben exportieren.
2. **Closed Testing** — persönliche Konten brauchen eine Mindestzahl Tester über
   14 Tage am Stück, bevor Production freigeschaltet wird (aktuelle Zahl in der
   Console).
3. **Datenschutzseite um Android ergänzen** (Mutterprojekt) — vor der Einreichung.
4. **Play-Release-AAB auf einem echten Gerät smoke-testen** — R8-minifiziert, Debug
   nicht. Per `bundletool build-apks --local-testing` installieren und Pairing → Buch
   → Editor → Diktat → Rechtschreibung → Teilen → Sync durchspielen.

**Erledigt:** Demo-Server + Reviewer-Token, Demo-Button, Icon/Feature-Graphic,
Screenshots (de), `play`-Flavor ohne Selbst-Update, targetSdk 36, signiertes AAB baut,
Paket-Registrierung (Entwicklerverifizierung).
