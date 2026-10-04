# schreibwerkstatt-mobile

Native **Android-Client** (Kotlin/Jetpack Compose) zur Web-App **schreibwerkstatt**. Die App ist ein dünner, offline-fähiger Client: Sie hält **keine** eigene Geschäftslogik für Buchinhalte, sondern liest/schreibt ausschliesslich über die HTTP-API des Servers und cached lokal in Room. Inhalte (Bücher/Kapitel/Abschnitte), Authentifizierung, STT-Diktat und der Focus-Editor leben serverseitig — die App rendert und synchronisiert sie nur.

**Mutterprojekt:** [`/Users/bd/ClaudeProjects/schreibwerkstatt`](/Users/bd/ClaudeProjects/schreibwerkstatt) (Node.js/SQLite-Server + Web-Frontend). Dort: `CLAUDE.md`, `README.md` (Deployment/Env), `docs/`. Die Server-API ist der **Vertrag** dieser App — siehe Harte Regel „Server-API gehört dem Mutterprojekt".

## Build & Test

```
./gradlew assembleDebug              # beide Flavors bauen (Standard-Test nach jeder Änderung)
./gradlew :app:compileGithubDebugKotlin   # schneller reiner Compile-Check
./gradlew testGithubDebugUnitTest    # JVM-Unit-Tests (Robolectric/JUnit)
./gradlew lint                       # Android-Lint
```

Die App hat zwei Flavors (Dimension `distribution`, siehe „Vertriebskanäle"), also heissen
alle variantenspezifischen Tasks `…Github…`/`…Play…`. `assembleDebug` bleibt der
Sammel-Task und deckt beide ab.

- **Pflicht: Nach jeder Code-Änderung `./gradlew assembleDebug` ausführen** und den Erfolg verifizieren, bevor die Änderung als fertig gilt. Schlägt der Build fehl, zuerst fixen.
- **Tests:** JVM-Unit-Tests in [app/src/test/](app/src/test/java/ch/schreibwerkstatt/mobile) sichern bewusst die **Harten Regeln** ab (Auth-Header auf jedem Request, `SaveResult`-Pfade, Delta-Pull überschreibt nie dirty, Pending-Queue, STT-Grösse, URL-/Client-Version-Normalisierung) — kein UI-/Compose-Test. **Nach Änderungen unter `data/` `./gradlew testGithubDebugUnitTest` ausführen.** Ein `Stop`-Hook ([.claude/hooks/run-unit-tests.sh](.claude/hooks/run-unit-tests.sh)) tut das am Turn-Ende automatisch, sobald `.kt`-Dateien geändert wurden, und blockiert bei rotem Lauf.
- **Toolchain:** Compile-Target ist **JDK 17** (`compileOptions`/`kotlinOptions`). Gradle 8.11.1 selbst läuft nur auf **JDK 17–23** — ein installiertes JDK 24+ als `JAVA_HOME` lässt den Wrapper scheitern. Bei Bedarf `JAVA_HOME` auf ein JDK 17/21 zeigen (z.B. das von Android Studio gebündelte JBR).
- Android SDK aus `local.properties` (`sdk.dir`); nicht ins VCS committen.

## Versionierung

- **Einzige Quelle der Wahrheit:** [`version.properties`](version.properties) im Projekt-Root (committet). Enthält `versionName` (nutzersichtbar, SemVer `major.minor.patch`) und `versionCode` (monoton steigende Ganzzahl). [app/build.gradle.kts](app/build.gradle.kts) liest die Datei beim Konfigurieren ein und setzt daraus `defaultConfig.versionName`/`versionCode` sowie `BuildConfig.CLIENT_VERSION` (`"android/<versionName>"`, geht als `X-Client-Version`-Header an den Server). **Version nie direkt in `build.gradle.kts` hartcodieren** — nur `version.properties` ändern.
- **Bump-Regel:** Die Version wird **nicht automatisch** erhöht. Nur **auf ausdrückliche Anweisung des Users** („bumpe die Version", „neue Version" o.Ä.) wird in `version.properties` der `versionName` nach SemVer angehoben (patch/minor/major je nach Anweisung; im Zweifel patch) **und** der `versionCode` um genau 1 erhöht. Danach `./gradlew assembleDebug` zur Verifikation. Beide Felder immer gemeinsam bumpen.

## Architektur

- **Manuelles DI** über `ServiceLocator` ([App.kt](app/src/main/java/ch/schreibwerkstatt/mobile/App.kt)) — kein Hilt. App-weite Singletons (Settings, Token, Network, DB, Repository, BundleManager, SyncCoordinator) lazy; ViewModels ziehen Abhängigkeiten über `context.locator` + eigene `factory`.
- **UI:** Jetpack Compose + Material3, Navigation-Compose ([ui/AppNav.kt](app/src/main/java/ch/schreibwerkstatt/mobile/ui/AppNav.kt)). MVVM: `ui/<feature>/{Screen,ViewModel}.kt`. Screens: `pairing`, `books`, `tree`, `editor`, `settings`.
- **Persistenz:** Room (`data/db/`) als Offline-Cache; DataStore (`data/prefs/SettingsStore`) für nicht-geheime Config; EncryptedSharedPreferences (`data/prefs/TokenStore`) für das Geräte-Token.
- **Netzwerk:** Retrofit + OkHttp + kotlinx.serialization (`data/net/`). `NetworkClient` baut Retrofit pro (variabler) Basis-URL und cached es. DTOs in `data/net/dto/Dtos.kt`.
- **Editor:** Der Focus-Editor läuft als Web-Bundle in einer WebView (`editor/`, `bundle/`, `assets/editor-host/host.html`) — siehe Harte Regeln unten.
- **Sync-Trigger:** Der [`SyncCoordinator`](app/src/main/java/ch/schreibwerkstatt/mobile/data/repo/SyncCoordinator.kt) (App-Scope) bündelt alle Auslöser: Auto-**Flush** bei Connectivity-Wechsel, manueller Voll-Sync (`syncAllNow` → `ContentRepository.syncAllBooks`, Push + Pull aller Bücher), und Registrierung des periodischen Background-**Pulls**. Letzterer ist der [`PeriodicSyncWorker`](app/src/main/java/ch/schreibwerkstatt/mobile/sync/PeriodicSyncWorker.kt) (WorkManager, **stündlich**, `NetworkType.CONNECTED`); er no-opt ohne Token und ist über das Settings-Flag `backgroundSync` (Default an) abschaltbar. Zusätzlich pullt das `TreeViewModel` beim Buch-Öffnen. WorkManager nutzt seine Default-Initialisierung (androidx.startup) — keine eigene `Configuration`.

## Harte Regeln

- **`ContentRepository` ist der einzige Lese-/Schreib-Eintrittspunkt für Buchinhalte.** Native UI **und** der WebView-Editor (über `EditorBridge`) gehen über [ContentRepository](app/src/main/java/ch/schreibwerkstatt/mobile/data/repo/ContentRepository.kt) — **nie** direkt gegen den Server. Nur so teilen beide denselben Room-Cache. Online-Flush + Delta-Pull stecken im [SyncEngine](app/src/main/java/ch/schreibwerkstatt/mobile/data/repo/SyncEngine.kt). (Spiegelt die Content-Store-Facade-Regel des Mutterprojekts.)

- **Offline-first / Pending-Write-Queue.** Editor-Saves persistieren lokal als `dirty`, queuen einen `PendingWriteEntity` und versuchen dann best-effort den Online-Flush (`SaveResult` = `Saved`/`Queued`/`Conflict`/`Locked`). Der Delta-Pull (`GET …/sync`) darf **lokal-dirty Seiten nie mit Server-Stand überschreiben** — der Pending-Write hat Vorrang bis Flush/Konflikt. Konflikt = HTTP **409** (`PAGE_CONFLICT`), Lock = **423** (`PAGE_LOCKED`); Body via `errorBody`.

- **Auth-Modell.** Geräte-Token (`swd_…`) wird verschlüsselt im `TokenStore` abgelegt. Danach setzt `AuthInterceptor` auf **jedem** Request `Authorization: Bearer swd_…` + `X-Client-Version`. **401 → Token verwerfen → `isPaired=false` → Navigation zurück zum Pairing** (siehe `AppNav`). Token nie loggen, nie in DataStore/Klartext ablegen.

- **Pairing = manuelle Token-Eingabe (wie der Mac-Client).** `PairingScreen` ist ein reines Formular: Server-Adresse + ein am Server (Web-UI „Einstellungen → Geräte", `/me/device-tokens`) **vorab erzeugtes** Device-Token (`swd_…`). `PairingViewModel.couple()` verifiziert das Token über `NetworkClient.verifyToken()` (`GET …/config`, liegt hinter dem Auth-Guard) und legt es **erst bei Erfolg** im `TokenStore` ab — kein WebView/OIDC-Flow, keine `POST /me/device-tokens`-Ausstellung aus der App. **Why:** Google-OIDC im WebView ist unzuverlässig (`disallowed_useragent`); die manuelle Eingabe entkoppelt das Pairing vom Login-Flow.

- **Demo-Zugang kommt aus `demo.properties`, nie aus dem Quellcode.** Der „Demo ausprobieren"-Button im `PairingScreen` füllt Server-Adresse + Demo-Token und läuft danach durch denselben `couple()`-Pfad. Die Werte liest [app/build.gradle.kts](app/build.gradle.kts) aus `demo.properties` im Root (**gitignored**, Vorlage `demo.properties.template`) in `BuildConfig.DEMO_SERVER_URL`/`DEMO_DEVICE_TOKEN`; fehlt die Datei, sind die Felder leer, `PairingViewModel.demoAvailable` ist `false` und der Button verschwindet. **Why:** Das GitHub-Repo ist öffentlich — ein Device-Token darf nicht in getrackte Dateien. Es steckt trotzdem im ausgelieferten APK und ist extrahierbar: das Demo-Konto also als wegwerfbar behandeln (regelmässig zurücksetzen, Token bei Bedarf am Server widerrufen).

- **Vertriebskanäle: zwei Flavors, und der Play-Build aktualisiert sich nie selbst.** Dimension `distribution` in [app/build.gradle.kts](app/build.gradle.kts): `github` (Sideload-APK aus GitHub-Releases, `BuildConfig.SELF_UPDATE = true`) und `play` (Google Play, `SELF_UPDATE = false`). Im `play`-Build fehlen `REQUEST_INSTALL_PACKAGES` und der Update-`FileProvider` **im Manifest** — beide stehen nur in [app/src/github/AndroidManifest.xml](app/src/github/AndroidManifest.xml), nicht im `main`-Manifest. Alle Aufrufer von `updateManager` (`AppNav`, `SettingsScreen`) hängen hinter `if (BuildConfig.SELF_UPDATE)`, damit R8 den ganzen Pfad aus dem Play-Artefakt wirft; **keine neue `updateManager`-Referenz ohne diesen Guard**. **Why:** Googles „Device and Network Abuse" verbietet Apps, die sich ausserhalb von Play aktualisieren — mit Permission oder Installer-Intent im AAB ist die Einreichung ein sicherer Reject. Der GitHub-Kanal bleibt davon unberührt.

- **`device_id` ≠ Auth-Token.** Die stabile Installations-UUID (`SettingsStore.deviceId()`) dient nur Konflikt-/Presence-Zuordnung (`PUT …/pages/:id { device_id }`, `device-ping`) und liegt bewusst getrennt vom geheimen Token.

- **Editor-Bundle (OTA) + Same-Origin-WebView.** `BundleManager` lädt `GET /content/editor-bundle.zip` (mit `If-None-Match`/`ETag`, 304 = kein Neuentpacken) ins App-Storage; `host.html` wird aus den Assets dazukopiert. Der `WebViewAssetLoader` serviert das Bundle unter `https://appassets.androidplatform.net/`, damit Same-Origin greift und relative Imports des Bundles funktionieren. **Zip-Slip-Schutz** beim Entpacken nicht entfernen. JS↔native nur über `EditorBridge` (`window.SWHost`), Ergebnisse asynchron via `window.__sw.resolve/reject`.

- **Rechtschreibprüfung läuft über die Bridge, nie über `fetch` im WebView.** Der Spellcheck-Controller kommt aus dem OTA-Bundle, wird aber von `host.html` selbst gemountet (`window.__sw.setSpellcheck`) und bekommt `checkText`/`addWord` injiziert: `EditorBridge.ltCheck`/`ltAddWord` → [SpellcheckClient](app/src/main/java/ch/schreibwerkstatt/mobile/editor/SpellcheckClient.kt) → `POST /languagetool/check` bzw. `POST /dictionary`. **Why:** Der Default des Controllers fetcht relativ — im WebView also gegen `appassets.androidplatform.net`, wo weder Route noch `Authorization`-Header existieren. Buch-/Seiten-ID setzt die Bridge selbst (die Buch-Locale löst der Server aus `bookId` auf); die Prüf-Antwort bleibt roher JSON (LanguageTool-`matches[]`) — kein DTO. Aktiv nur, wenn `/config` `languagetool.enabled` meldet **und** das Settings-Flag `spellcheck` (Default an) gesetzt ist. Badge-/Popover-Texte kommen als i18n-Map aus den `@string/`-Ressourcen durch die Bridge.

- **Die Schreiblinie („Typewriter-Anker") gehört der nativen Seite.** `EditorScreen` misst die Lage der WebView im Fenster (`onGloballyPositioned` + IME-Inset), rechnet daraus mit `typewriterAnchorRatio()` den Anteil der WebView-Höhe, der die Zeile auf die Mitte des **sichtbaren Bildschirms** legt, und schickt ihn über `window.__sw.setTypewriterAnchor` an `host.html`. Dort speist er (a) `host.typewriterAnchor`, den die Engine pro Recenter liest — als Getter installiert und auf `visualViewport` umgerechnet — und (b) die Scroll-Puffer `--sw-pad-top`/`--sw-pad-bottom`, mit denen [editor-host.css](app/src/main/assets/editor-host/editor-host.css) die `100vh`-Formel des Bundles `!important` überschreibt. Die Puffer **wachsen nur** (pro Bildschirmbreite): ein schrumpfender Puffer verschiebt den ganzen Text gegen die Scroll-Position. **Why:** Der Bundle-Default (Anker 0.5, Puffer aus `100vh` vs. `--focus-vh`) gilt für den Browser, wo der Layout-Viewport bei offener Tastatur gross bleibt und keine App-Chrome über dem Editor liegt — in dieser Schale schrumpft die WebView real und Statusleiste/TopAppBar/Sync-Streifen sitzen darüber, die Zeile klebte sonst an der Tastatur. Das IME-Padding hängt deshalb an **`WindowInsets.imeAnimationTarget`** (nicht `imePadding()`): das animierte Inset wäre eine WebView-Grössenänderung pro Frame, jede mit Neu-Layout — sichtbares Flackern.

- **STT-Diktat sendet rohe Audio-Bytes (kein Multipart).** `DictationController` nimmt als `audio/mp4` (AAC, 16 kHz) auf und POSTet die Bytes an `POST /stt/transcribe`; der `Content-Type` trägt den Audio-MIME. Segment < 5 MB (Server-Limit). Erkannter Text wird verbatim über den normalen Save-Pfad eingefügt.

- **Cleartext-HTTP ist absichtlich erlaubt** ([network_security_config.xml](app/src/main/res/xml/network_security_config.xml)), weil die Server-URL self-hosted/variabel ist (LAN ohne TLS). Für produktive Deployments HTTPS verwenden — diese Begründung bleibt, nicht „aufräumen".

- **i18n:** User-sichtbare Strings als `@string/`-Ressourcen ([res/values/strings.xml](app/src/main/res/values/strings.xml)), nicht hartcodiert.
  - **Terminologie (wie im Mutterprojekt):** Die Gliederungseinheit (Code/DB/API: `page`/`pages`, `pageId`, `content/pages/{id}`) heisst im UI **«Abschnitt»** / **«section»**, nie «Seite»/«page». «Seite» nur für Druck-/Webseiten. Bezeichner und Routen bleiben `page`. **Why:** Eine Gliederungseinheit ist beliebig lang, «Seite» kollidiert mit der Druckseite; die App soll dieselben Begriffe zeigen wie Web-UI und Editor-Bundle.

- **Doku-Stil dieser Datei:** Nur **aktueller Stand**. Keine Historie, kein „vorher war …", keine Migrationsnarrative — dafür gibt es `git log`. Begründungen (**Why**) für aktuelle Constraints bleiben.

## Server-API gehört dem Mutterprojekt

Die App ist reiner Konsument dieser Endpunkte (Quelle: `routes/`, `lib/` im Mutterprojekt):

| Endpunkt | App-Seite | Server-Quelle |
|---|---|---|
| `GET config` | `ConfigApi` · Pairing-Token-Check (`NetworkClient.verifyToken`) | `routes/…` (`/config`), `lib/device-auth.js`, `db/device-tokens.js` |
| `GET content/books` · `…/{id}/tree` | `ContentApi` | `routes/content.js` |
| `GET content/books/{id}/sync` | `SyncEngine` | `routes/content.js` (Delta-Pull, `since`/`since_id`/`limit`) |
| `GET`/`PUT content/pages/{id}` | `ContentRepository` | `routes/content.js` (409/423) |
| `POST content/books/{id}/device-ping` | Presence | `routes/content.js` |
| `POST stt/transcribe` | `DictationController` | `routes/stt.js` ([docs/stt.md](/Users/bd/ClaudeProjects/schreibwerkstatt/docs/stt.md)) |
| `POST languagetool/check` · `POST dictionary` | `SpellcheckClient` (via `EditorBridge`) | `routes/languagetool.js`, `routes/dictionary.js` ([docs/languagetool.md](/Users/bd/ClaudeProjects/schreibwerkstatt/docs/languagetool.md)) |
| `GET content/editor-bundle.zip` | `BundleManager` | `lib/editor-bundle.js`, `routes/content.js` ([docs/focus-editor.md](/Users/bd/ClaudeProjects/schreibwerkstatt/docs/focus-editor.md)) |

- **Server-Änderungen NICHT selbst vornehmen.** Braucht eine Aufgabe eine neue/geänderte Server-Route, einen anderen Vertrag (Felder, Statuscodes), eine Config-Flag o.Ä., dann **nicht** im Mutterprojekt editieren, sondern dem User einen **fertigen Prompt-Vorschlag** liefern, den er im Mutterprojekt-Repo (mit dessen `CLAUDE.md`/Regeln) ausführen kann. Format:

  > **Prompt-Vorschlag fürs Mutterprojekt (`schreibwerkstatt`):**
  > „<konkret, was an welcher Route/Datei geändert/ergänzt werden soll, inkl. erwartetem Request/Response-Vertrag, Statuscodes, Auth-Scope und Begründung aus Mobile-Sicht>"

  Grund: Das Mutterprojekt hat eigene Harte Regeln (Content-Store-Facade, Job-Queue, i18n, Sync-Proxy-Ausnahmen). Änderungen dort gehören in dessen Kontext, nicht blind aus diesem Repo.
- **Vertrags-Drift:** Weicht ein DTO (`data/net/dto/Dtos.kt`) von der tatsächlichen Server-Antwort ab, ist die Server-Seite die Wahrheit — DTO anpassen und ggf. Prompt-Vorschlag fürs Mutterprojekt, falls dort etwas inkonsistent ist.