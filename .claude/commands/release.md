---
description: Version bumpen, builden, committen, taggen, pushen + signiertes GitHub-Release mit APK anlegen
---

Führe den kompletten Release-Workflow für schreibwerkstatt-mobile aus. Dieser Command ist die **gezielte Freigabe** des Users — wenn er aufgerufen wird, den ganzen Ablauf ohne weitere Rückfrage durchziehen (außer der Build schlägt fehl).

Optionales Argument `$ARGUMENTS` = gewünschte Bump-Höhe (`patch` | `minor` | `major`). Wenn leer: Höhe aus den uncommitteten/seit dem letzten Tag liegenden Änderungen ableiten — neue nutzersichtbare Features → `minor`, sonst `patch`, im Zweifel `patch`.

Schritte:

1. **Stand prüfen:** `git status` + `git diff --stat` ansehen, damit der Commit-Message-Text die Änderungen korrekt beschreibt. Aktuelle Version aus [version.properties](version.properties) lesen.
2. **Bumpen:** In [version.properties](version.properties) `versionName` nach SemVer anheben (Höhe s.o.) **und** `versionCode` um genau 1 erhöhen. Beide Felder gemeinsam. Nie in `build.gradle.kts` hartcodieren.
3. **Build + Tests verifizieren:** `./gradlew assembleDebug testGithubDebugUnitTest lint` muss BUILD SUCCESSFUL liefern — dieselben Checks wie der CI-Job `build`. Bei Fehler: stoppen und fixen, nicht weitermachen. **Why:** Nach Schritt 7 ist das APK auf GitHub und der `UpdateChecker` der Clients zieht es sofort; die CI meldet erst danach. Der Stop-Hook greift hier nicht (läuft erst am Turn-Ende und nur bei geänderten `.kt`-Dateien).
4. **Committen:** Alle Änderungen `git add -A`, Commit mit aussagekräftiger Message (Features in Stichpunkten + `Release <versionName> (versionCode <n>)`). Co-Authored-By-Trailer wie üblich.
5. **Taggen + pushen:** `git tag v<versionName>`, dann `git push origin main` **und** `git push origin v<versionName>`.
6. **Signierten Release-APK bauen:** `./gradlew assembleGithubRelease` (nutzt `keystore.properties`, ~2.5 MB minifiziert). Ergebnis: `app/build/outputs/apk/github/release/app-github-release.apk`. **Zwingend der `github`-Flavor** — nur der hat das In-App-Update; der `play`-Flavor geht nie auf GitHub (siehe CLAUDE.md „Vertriebskanäle").
7. **GitHub-Release anlegen:** APK in den Scratchpad als `schreibwerkstatt-mobile-v<versionName>.apk` kopieren, dann
   `gh release create v<versionName> --title "v<versionName>" --notes "<Release-Notes aus den Änderungen>" <pfad-zur-umbenannten-apk>`.

**Warum der signierte Release-APK (nicht Debug):** Der `UpdateChecker` liest `releases/latest`, nimmt das erste `.apk`-Asset und vergleicht den Tag (`v` gestrippt) per SemVer gegen die laufende App. Das Update lässt sich nur über eine APK installieren, die mit demselben Keystore wie die installierte App signiert ist.

Am Ende: knappe Zusammenfassung mit der neuen Version, Commit-Hash und Release-URL.
