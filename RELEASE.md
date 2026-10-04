# Release & Veröffentlichung

Anleitung, um aus diesem Repo ein signiertes, auslieferbares Artefakt zu bauen –
für den Play Store (`.aab`) **oder** Sideload/eigenen Server (`.apk`).

> Begriffe: **Debug-Build** = Entwicklung, automatisch mit Android-Debug-Key signiert.
> **Release-Build** = ausgeliefert, mit deinem eigenen *Upload-Key* signiert, mit R8/ProGuard
> minifiziert. Nur Release-Artefakte gehören in den Store / auf Geräte von Nutzern.

---

## 1. Einmalig: Upload-Keystore erzeugen

Der Keystore signiert jedes Release. **Verlierst du ihn, kannst du im Play Store nie wieder
Updates unter derselben App-ID (`ch.schreibwerkstatt.mobile`) veröffentlichen.** Sicher und
getrennt vom Repo aufbewahren (Passwort-Manager / Backup).

```bash
keytool -genkey -v -keystore upload-keystore.jks \
  -keyalg RSA -keysize 2048 -validity 10000 -alias upload
```

`keytool` fragt nach zwei Passwörtern (Store + Key – dürfen identisch sein) und ein paar
Namens-/Org-Feldern. Die Keystore-Datei (`*.jks`) ist via `.gitignore` ausgeschlossen.

## 2. Einmalig: `keystore.properties` anlegen

Aus der Vorlage kopieren und ausfüllen – diese Datei ist gitignored und darf **nie**
committet werden (Passwörter!):

```bash
cp keystore.properties.template keystore.properties
```

```properties
storeFile=upload-keystore.jks   # relativ zum Projekt-Root
storePassword=<dein Store-Passwort>
keyAlias=upload
keyPassword=<dein Key-Passwort>
```

Solange `keystore.properties` **fehlt**, baut der Release-Build trotzdem – aber **unsigniert**
(`app-<flavor>-release-unsigned.apk`). So bleiben CI und frische Checkouts funktionsfähig. Sobald die
Datei vorhanden und vollständig ist, signiert Gradle automatisch.

## 3. Vor jedem Release: Version hochzählen

Einzige Quelle der Wahrheit: [`version.properties`](version.properties).

```properties
versionName=0.2.0   # nutzersichtbar (SemVer); fliesst auch in X-Client-Version
versionCode=2       # MUSS bei jedem Store-Upload streng monoton steigen
```

`versionCode` ist die für den Play Store entscheidende Zahl – zwei Uploads mit gleichem
`versionCode` lehnt Google ab.

## 4. Bauen

Die App hat zwei Flavors (Dimension `distribution`): `play` für den Store (ohne
Selbst-Update) und `github` für Sideload/GitHub-Releases (mit In-App-Update). Jeder Kanal
bekommt sein eigenes Artefakt.

```bash
# Play Store – Android App Bundle (play-Flavor):
./gradlew bundlePlayRelease
#   → app/build/outputs/bundle/playRelease/app-play-release.aab

# Sideload / GitHub-Release – APK (github-Flavor):
./gradlew assembleGithubRelease
#   → app/build/outputs/apk/github/release/app-github-release.apk   (signiert)
#     bzw. app-github-release-unsigned.apk, falls keystore.properties fehlt
```

Der Release-Build ist **minifiziert** (R8, `isMinifyEnabled = true`). Nach Änderungen an
Netzwerk-DTOs, Room-Entities oder der `EditorBridge` das Release-Artefakt **real testen** –
Minify kann reflektierten/serialisierten Code entfernen. Keep-Regeln stehen in
[`app/proguard-rules.pro`](app/proguard-rules.pro); fehlt etwas, generiert R8 Vorschläge in
`app/build/outputs/mapping/release/missing_rules.txt`.

## 5. Veröffentlichen

### A) Google Play Store

1. **Play Console** (developer.google.com/play): Entwicklerkonto (einmalig 25 USD,
   Identitätsprüfung). Für eine Firmen-App → **Organisations-Account** (D-U-N-S); umgeht die
   Closed-Test-Pflicht (20 Tester / 14 Tage), die für neue *persönliche* Accounts gilt.
2. App anlegen, `.aab` in einen Track laden (**Internal → Closed → Production**).
3. Pflicht-Formulare: Datenschutzerklärung (URL), Data-Safety, Inhaltsfreigabe, Zielgruppe.
4. **Play App Signing**: Google verwaltet den finalen Signing-Key; du lädst nur mit deinem
   Upload-Key hoch (Schritt 1–2).

**Stolpersteine speziell für diesen self-hosted Client:**
- **Cleartext-HTTP** ([network_security_config.xml](app/src/main/res/xml/network_security_config.xml))
  ist absichtlich erlaubt (LAN ohne TLS) – im Data-Safety-Formular deklarieren; für reine
  Store-Builds ggf. HTTPS-only erzwingen.
- **Token-Pairing**: Ein Reviewer erreicht deinen Server nicht. Test-Server + Demo-Token in
  den Reviewer-Notizen hinterlegen, sonst Ablehnung wegen „nicht funktionsfähig".

### B) Sideload / eigener Server (oft passender für ein self-hosted Tool)

Signierte `.apk` aus Schritt 4 direkt vom Server ausliefern (analog zum Editor-Bundle-Mechanismus).
Nutzer müssen „Installation aus unbekannten Quellen" erlauben. Kein Review, keine Gebühr,
sofortige Updates – aber kein automatisches Update über den Store.

### C) Play Internal App Sharing

Geschlossener Tester-Kreis per Link, ohne vollen Review – guter Mittelweg.

---

## Checkliste pro Release

- [ ] `versionCode` erhöht, `versionName` angepasst ([version.properties](version.properties))
- [ ] `./gradlew bundlePlayRelease` bzw. `assembleGithubRelease` erfolgreich
- [ ] Release-Artefakt auf echtem Gerät getestet (Pairing, Sync, Editor-WebView, Diktat)
- [ ] Keystore + `keystore.properties` sicher gesichert (nicht im Repo)
