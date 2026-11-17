# Spicetify for Android

Spicetify for Android runs the **Spotify web player** (`open.spotify.com`) inside an Android app and applies Spicetify to it: the same patches, the same wrapper API (`Spicetify.*`), extensions, themes, CSS snippets, custom apps and the Marketplace.

[Deutsch](#deutsch) · [English](#english)

---

## Deutsch

### Warum der Web-Player?

Die offizielle Spotify-App für Android ist nativ geschrieben (Kotlin/Java). Spicetify verändert dagegen den Web-Client (xpui), auf dem der Desktop-Client basiert. Deshalb können Spicetify-Erweiterungen in der nativen App grundsätzlich nicht laufen. Der Web-Player wird aus demselben Code gebaut wie der Desktop-Client. Die Patches von Spicetify greifen dort fast unverändert, siehe [Funktionsweise](#funktionsweise).

### Funktionen

- **Spicetify vollständig:** `Spicetify.Platform`, `Player`, `React`, `ContextMenu`, `Topbar`, `Playbar`, `PopupModal`, `GraphQL`, `CosmosAsync` (für `https://`-APIs) usw.
- **Erweiterungen:** Die mitgelieferten aus `Extensions/` lassen sich an- und ausschalten. Weitere installierst du per URL, als Datei oder über den Marketplace.
- **Themes und Farbschemata:** `SpicetifyDefault` ist dabei, weitere Themes kommen als ZIP (Datei oder URL) oder über den Marketplace.
- **Custom Apps:** Marketplace, Lyrics Plus, New Releases und Reddit sind enthalten, eigene Apps importierst du als ZIP.
- **CSS-Snippets** mit eingebautem Editor.
- **Mobiles Layout** auf Handys: Die Bibliothek wird zum Seitenmenü (Hamburger oder Wisch von links), die Wiedergabeleiste ist kompakt, und „Läuft gerade“, Warteschlange und Lyrics öffnen als Vollbild-Panel (Tipp auf den Titel). Tablets behalten das Desktop-Layout. Du kannst das Layout in den Einstellungen erzwingen.
- **Android-Integration:** Hintergrund-Wiedergabe, Medien-Benachrichtigung, Sperrbildschirm, Bluetooth- und Kopfhörertasten, Zurück-Taste, `open.spotify.com`-Links und `spotify:`-URIs.
- **Spotify Connect** wie im Web-Player (als Gerät und zum Fernsteuern).
- Die Optionen aus `config-xpui.ini` gibt es als Schalter in den Einstellungen: Sentry und Logging deaktivieren, APIs freigeben, Home-, Sidebar- und experimentelle Konfiguration, RTL entfernen.

Den Manager öffnest du über das Zahnrad-Symbol „Spicetify“ in der oberen Leiste des Players oder über den Eintrag im Profilmenü.

### Installation

1. Lade die APK aus den Artefakten des Workflows **Android** (GitHub Actions) herunter oder baue sie selbst (siehe unten).
2. Erlaube die Installation aus unbekannten Quellen und installiere die APK.
3. Melde dich mit **E-Mail/Benutzername und Passwort** oder über **„Mit Code anmelden“** an. Google verbietet die Anmeldung in eingebetteten WebViews, deshalb funktioniert „Weiter mit Google“ nicht.

### Einschränkungen

- Für die Wiedergabe braucht das Gerät **Widevine-Unterstützung in Android System WebView**. Auf zertifizierten Geräten ist das Standard. Halte Android System WebView aktuell.
- Der Web-Player kann **keine Downloads** für den Offline-Modus speichern.
- Interne `sp://`-Endpunkte der Desktop-App gibt es im Web nicht. Erweiterungen, die nur damit arbeiten, funktionieren nicht vollständig.
- Ändert Spotify den Web-Player, können einzelne Patches nicht mehr greifen, genau wie auf dem Desktop. Der Patch-Cache wird automatisch erneuert.
- Spicetify für Android ist kein offizielles Spotify-Produkt.

### Selbst bauen

Voraussetzungen: JDK 17 oder neuer, Android SDK (Plattform 36, NDK 27.2.12479018), Go (siehe `go.mod`), [gomobile](https://pkg.go.dev/golang.org/x/mobile/cmd/gomobile), Node.js und pnpm.

```sh
pnpm install
go install golang.org/x/mobile/cmd/gomobile@latest golang.org/x/mobile/cmd/gobind@latest
cd android
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew :app:assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
```

Gradle baut dabei automatisch:

- `app/libs/spicengine.aar` aus der Go-Patch-Engine (`buildEngine`, mit gomobile),
- `jsHelper/spicetifyWrapper.js` (`buildWrapper`),
- die Assets aus `Extensions/`, `CustomApps/`, `Themes/` und `android/web/` sowie den Marketplace (`fetchMarketplace`; eine feste Version wählst du mit `-Pspicetify.marketplaceVersion=v1.0.8`).

Eine signierte Release-APK bekommst du mit `-Pspicetify.storeFile=… -Pspicetify.storePassword=… -Pspicetify.keyAlias=… -Pspicetify.keyPassword=…`.

---

## English

### Why the web player?

The official Spotify Android app is native code. Spicetify modifies the web client (xpui) that the desktop app is built on, so Spicetify extensions can never run inside the native app. The web player is built from the same source as the desktop client, and Spicetify's patches apply to it almost unchanged.

### Features

- **Spicetify in full:** `Spicetify.Platform`, `Player`, `React`, `ContextMenu`, `Topbar`, `Playbar`, `PopupModal`, `GraphQL`, `CosmosAsync` (for `https://` APIs) and more.
- **Extensions:** turn the bundled ones on or off, or install more from a URL, a file or the Marketplace.
- **Themes and color schemes**, imported as ZIP files or from the Marketplace.
- **Custom apps:** Marketplace, Lyrics Plus, New Releases and Reddit are included, and you can import your own as ZIP files.
- **CSS snippets** with an editor.
- **Phone layout:** the library becomes a drawer, the now playing bar is compact, and the now playing view, queue and lyrics open as a full-screen panel. Tablets keep the desktop layout.
- **Android integration:** background playback, media notification, lock screen, Bluetooth/headset buttons, back button, `open.spotify.com` links and `spotify:` URIs.
- All `config-xpui.ini` options are available as switches.

Open the manager from the "Spicetify" button in the player's top bar or from the profile menu.

### Limitations

- Playback needs **Widevine in Android System WebView** (standard on certified devices).
- The web player cannot save **offline downloads**.
- Desktop-only `sp://` endpoints do not exist on the web.
- Google sign-in is blocked inside WebViews. Use email/password or "Log in with a code".
- Not affiliated with Spotify.

### Building

See the commands in the German section above. The requirements are JDK 17+, Android SDK 36, NDK 27.2.12479018, Go, gomobile, Node.js and pnpm.

---

## How it works / Funktionsweise

```
┌──────────────── Android app (Kotlin, Compose) ─────────────────┐
│ MainActivity ── WebView: https://open.spotify.com (desktop UA)  │
│   ├─ document start script (Spicetify.Config, wrapper, helpers, │
│   │   extensions, theme CSS, android/bridge.js, mobile.js)      │
│   ├─ SpicetifyInterceptor (shouldInterceptRequest)              │
│   │   ├─ open.spotifycdn.com/cdn/build/web-player/*.js|css      │
│   │   │    → download → Go patch engine → disk cache            │
│   │   ├─ …/spicetify-routes-<app>.js|css → custom app chunk     │
│   │   └─ open.spotify.com/__spicetify/** → bundled/user files   │
│   └─ NativeBridge (addWebMessageListener, open.spotify.com only)│
│ PlaybackService: Media3 session mirroring the web player        │
│ ManagerActivity: extensions, themes, apps, snippets, settings   │
└─────────────────────────────────────────────────────────────────┘
        ▲ gomobile (android/engine)
   src/patcher ── src/preprocess, src/apply (shared with the CLI)
```

- **`src/patcher`** is the string-based API on top of the desktop CLI's patch code (`src/preprocess`, `src/apply`). Both apply the same patches. It maps web player bundles to their desktop counterparts (`web-player.*.js` → `xpui.js`, `vendor~web-player.*.js` → `vendor~xpui.js`) and creates the document start script.
- **`android/engine`** is the gomobile binding module, built into `app/libs/spicengine.aar`.
- **`android/web`** contains the web side of the port: `bridge.js` (playback state, commands, back navigation), `mobile.js` and `mobile.css` (phone layout).
- **`src/jsHelper/spicetifyWrapper`** handles the web player: it loads before `<body>` exists, has a Cosmos fallback, maps the PlayerAPI's `_state`/`_events`/`_queue`, and evaluates webpack modules only when their dependencies are loaded.
- The desktop hashes in `css-map.json` also cover the web player, because the two share their class name hashes.

### Development

```sh
pnpm build:wrapper
go test ./src/patcher/...                       # includes a live check against open.spotify.com
pnpm e2e:android --marketplace <dir>            # Playwright: reproduces the WebView setup in Chromium
go run ./src/patcher/cmd/devhost -root <assets> # serve patched assets for manual browser testing
```

`android/e2e/e2e.mjs` checks that Spicetify initializes on the live web player, that custom apps (Lyrics Plus, Marketplace) render, that the theme is applied and that the phone layout works. It saves screenshots to `build/e2e/`.
