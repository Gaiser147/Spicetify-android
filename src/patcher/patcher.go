// Package patcher exposes Spicetify's patch engine as a small, string-only API
// so it can be bound with gomobile and used by the Android port.
//
// The Android app loads the Spotify web player (open.spotify.com) in a WebView.
// Every JavaScript/CSS bundle the WebView downloads from the web player CDN is
// passed through PatchAsset, which runs exactly the same patches the desktop
// CLI applies to the extracted xpui folder (see src/preprocess and src/apply).
package patcher

import (
	"encoding/json"
	"fmt"
	"path"
	"regexp"
	"sort"
	"strings"
	"sync"

	"github.com/spicetify/cli/src/apply"
	"github.com/spicetify/cli/src/preprocess"
)

// EngineVersion changes whenever patch output may change, so the Android
// cache of patched bundles can be invalidated.
const EngineVersion = "1"

// DefaultSpotifyVersion is the desktop version the web player is treated as.
// The web player is always the latest build, so version-gated legacy patches
// must stay disabled.
const DefaultSpotifyVersion = "1.2.80"

// Config mirrors the [Setting]/[Preprocesses]/[AdditionalOptions] sections of
// config-xpui.ini. It is passed in as JSON.
type Config struct {
	SpicetifyVersion string `json:"spicetifyVersion"`
	SpotifyVersion   string `json:"spotifyVersion"`

	CurrentTheme         string `json:"currentTheme"`
	ColorScheme          string `json:"colorScheme"`
	InjectThemeJS        bool   `json:"injectThemeJs"`
	CheckSpicetifyUpdate bool   `json:"checkSpicetifyUpdate"`

	ExposeAPIs     bool `json:"exposeApis"`
	DisableSentry  bool `json:"disableSentry"`
	DisableLogging bool `json:"disableLogging"`
	RemoveRTL      bool `json:"removeRtl"`
	SidebarConfig  bool `json:"sidebarConfig"`
	HomeConfig     bool `json:"homeConfig"`
	ExpFeatures    bool `json:"expFeatures"`

	Extensions []string `json:"extensions"`
	CustomApps []string `json:"customApps"`
	// CustomAppExtensions lists, per custom app, the files of its manifest's
	// "subfiles_extension" entry.
	CustomAppExtensions map[string][]string `json:"customAppExtensions"`
	// ExtraScripts are loaded after the helpers and before user extensions
	// (relative to BasePath), e.g. the Android bridge.
	ExtraScripts []string `json:"extraScripts"`
	// ExtraStyles are additional stylesheets (relative to BasePath).
	ExtraStyles []string `json:"extraStyles"`

	// BasePath is the same-origin URL prefix the host serves Spicetify files
	// from. Defaults to "/__spicetify/".
	BasePath string `json:"basePath"`
}

// DefaultConfig returns the config the desktop CLI ships with.
func DefaultConfig() Config {
	return Config{
		SpicetifyVersion: "android",
		SpotifyVersion:   DefaultSpotifyVersion,
		CurrentTheme:     "SpicetifyDefault",
		ColorScheme:      "",
		ExposeAPIs:       true,
		DisableSentry:    true,
		DisableLogging:   true,
		SidebarConfig:    true,
		HomeConfig:       true,
		ExpFeatures:      true,
		BasePath:         "/__spicetify/",
	}
}

// Patcher patches web player assets. It is safe for concurrent use.
type Patcher struct {
	cfg     Config
	content *preprocess.ContentPatcher
	flags   apply.Flag

	mu     sync.Mutex
	report map[string][]string
}

// New creates a patcher from a JSON config and a JSON CSS translation map
// (css-map.json format: {"hashedClass": "semantic-class"}). Either may be
// empty to use the defaults / no CSS translation.
func New(configJSON string, cssMapJSON string) (*Patcher, error) {
	cfg := DefaultConfig()
	if strings.TrimSpace(configJSON) != "" {
		if err := json.Unmarshal([]byte(configJSON), &cfg); err != nil {
			return nil, fmt.Errorf("invalid config: %w", err)
		}
	}
	if cfg.SpotifyVersion == "" {
		cfg.SpotifyVersion = DefaultSpotifyVersion
	}
	if cfg.BasePath == "" {
		cfg.BasePath = "/__spicetify/"
	}
	if !strings.HasSuffix(cfg.BasePath, "/") {
		cfg.BasePath += "/"
	}

	cssMap := map[string]string{}
	if strings.TrimSpace(cssMapJSON) != "" {
		if err := json.Unmarshal([]byte(cssMapJSON), &cssMap); err != nil {
			return nil, fmt.Errorf("invalid css map: %w", err)
		}
	}

	return &Patcher{
		cfg: cfg,
		content: preprocess.NewContentPatcher(cssMap, preprocess.Flag{
			DisableSentry:  cfg.DisableSentry,
			DisableLogging: cfg.DisableLogging,
			RemoveRTL:      cfg.RemoveRTL,
			ExposeAPIs:     cfg.ExposeAPIs,
			SpotifyVer:     cfg.SpotifyVersion,
		}),
		flags: apply.Flag{
			CurrentTheme:         cfg.CurrentTheme,
			ColorScheme:          cfg.ColorScheme,
			InjectThemeJS:        cfg.InjectThemeJS,
			CheckSpicetifyUpdate: cfg.CheckSpicetifyUpdate,
			Extension:            cfg.Extensions,
			CustomApp:            cfg.CustomApps,
			SidebarConfig:        cfg.SidebarConfig,
			HomeConfig:           cfg.HomeConfig,
			ExpFeatures:          cfg.ExpFeatures,
			SpicetifyVer:         cfg.SpicetifyVersion,
			SpotifyVer:           cfg.SpotifyVersion,
		},
		report: map[string][]string{},
	}, nil
}

// Version returns the engine version used for cache keys.
func Version() string { return EngineVersion }

// Asset kinds.
const (
	KindUnknown  = ""
	KindMain     = "main"   // web-player.<hash>.js   ~ desktop xpui.js
	KindVendor   = "vendor" // vendor~web-player.<hash>.js ~ desktop vendor~xpui.js
	KindChunk    = "chunk"  // lazily loaded chunk (xpui-routes-*, dwp-*, numeric ids, ...)
	KindCSS      = "css"    // web-player.<hash>.css   ~ desktop xpui.css
	KindChunkCSS = "chunk-css"
)

var hashSuffixRe = regexp.MustCompile(`^(.+?)\.[0-9a-f]{6,}\.(js|css)$`)

// Classify maps a web player asset URL to an asset kind and the desktop file
// name whose patches apply to it.
func Classify(assetURL string) (kind string, desktopName string) {
	u := assetURL
	if i := strings.IndexAny(u, "?#"); i >= 0 {
		u = u[:i]
	}
	base := path.Base(u)
	ext := path.Ext(base)
	if ext != ".js" && ext != ".css" {
		return KindUnknown, ""
	}
	name := strings.TrimSuffix(base, ext)
	if m := hashSuffixRe.FindStringSubmatch(base); m != nil {
		name = m[1]
	}

	if ext == ".css" {
		if name == "web-player" || name == "xpui" {
			return KindCSS, "xpui.css"
		}
		return KindChunkCSS, name + ".css"
	}

	switch name {
	case "web-player", "xpui":
		return KindMain, "xpui.js"
	case "vendor~web-player", "vendor~xpui":
		return KindVendor, "vendor~xpui.js"
	}
	return KindChunk, name + ".js"
}

// IsCustomAppAsset reports whether the URL requests a custom app chunk
// (spicetify-routes-<app>[.<anything>].js|css) and returns the app name.
func IsCustomAppAsset(assetURL string) (app string, ext string, ok bool) {
	u := assetURL
	if i := strings.IndexAny(u, "?#"); i >= 0 {
		u = u[:i]
	}
	m := customAppAssetRe.FindStringSubmatch(path.Base(u))
	if m == nil {
		return "", "", false
	}
	return m[1], m[2], true
}

var customAppAssetRe = regexp.MustCompile(`^spicetify-routes-(.+?)(?:\.[^./]+)?\.(js|css)$`)

// PatchAsset applies all patches relevant to the asset at assetURL and
// returns the patched content. Unknown assets are returned unchanged.
func (p *Patcher) PatchAsset(assetURL string, content string) string {
	kind, desktopName := Classify(assetURL)
	var applied []string

	switch kind {
	case KindMain:
		content = p.content.PatchJS(desktopName, content)
		applied = append(applied, "preprocess")
		content, applied = p.patchAdditional(content, applied)
	case KindVendor:
		content = p.content.PatchJS(desktopName, content)
		applied = append(applied, "preprocess")
		if p.cfg.ExpFeatures {
			before := content
			content = apply.PatchExpFeatures(content, p.flags)
			if before != content {
				applied = append(applied, "expFeatures")
			}
		}
	case KindChunk:
		content = p.content.PatchJS(desktopName, content)
		applied = append(applied, "preprocess")
		content, applied = p.patchAdditional(content, applied)
	case KindCSS, KindChunkCSS:
		content = p.content.PatchCSS(desktopName, content)
		applied = append(applied, "css")
	default:
		return content
	}

	p.mu.Lock()
	p.report[path.Base(assetURL)] = applied
	p.mu.Unlock()
	return content
}

// patchAdditional runs the [AdditionalOptions] patches that the desktop CLI
// applies to whichever xpui file contains the matching code.
func (p *Patcher) patchAdditional(content string, applied []string) (string, []string) {
	if len(p.cfg.CustomApps) > 0 && apply.SupportsCustomAppInjection(content) {
		content = apply.PatchCustomApp(content, p.flags)
		applied = append(applied, "customApps")
	}
	if p.cfg.ExpFeatures {
		before := content
		content = apply.PatchExpFeatures(content, p.flags)
		if before != content {
			applied = append(applied, "expFeatures")
		}
	}
	if p.cfg.SidebarConfig {
		before := content
		content = apply.PatchSidebarConfig(content, p.flags)
		if before != content {
			applied = append(applied, "sidebarConfig")
		}
	}
	if p.cfg.HomeConfig {
		before := content
		content = apply.PatchHomeConfig(content)
		if before != content {
			applied = append(applied, "homeConfig")
		}
	}
	return content, applied
}

// Report returns, as JSON, which patch groups were applied to which asset.
func (p *Patcher) Report() string {
	p.mu.Lock()
	defer p.mu.Unlock()
	b, _ := json.Marshal(p.report)
	return string(b)
}

// CustomAppChunk wraps a custom app's concatenated JavaScript (index.js plus
// manifest "subfiles") into the chunk the patched route table loads.
func CustomAppChunk(app string, jsContent string) string {
	return apply.BuildCustomAppChunk(app, jsContent)
}

// DocumentStartScript returns the JavaScript the host must run before any of
// the page's own scripts (Android: WebViewCompat.addDocumentStartJavaScript).
// It defines Spicetify.Config and loads, in order, the theme stylesheets, the
// Spicetify wrapper, the helpers, extra host scripts and all extensions from
// BasePath. The page's CSP forbids inline scripts, so everything except this
// bootstrap is loaded from same-origin URLs.
func (p *Patcher) DocumentStartScript() string {
	cfg := p.cfg
	base := cfg.BasePath

	styles := []string{"colors.css", "user.css"}
	styles = append(styles, cfg.ExtraStyles...)

	// Scripts that must run in order and before xpui (blocking).
	blocking := []string{"helper/spicetifyWrapper.js"}

	// Deferred scripts, mirroring htmlMod's ordering.
	var deferred []script
	if cfg.SidebarConfig {
		deferred = append(deferred, script{Src: "helper/sidebarConfig.js"})
	}
	if cfg.HomeConfig {
		deferred = append(deferred, script{Src: "helper/homeConfig.js"})
	}
	if cfg.ExpFeatures {
		deferred = append(deferred, script{Src: "helper/expFeatures.js"})
	}
	for _, s := range cfg.ExtraScripts {
		deferred = append(deferred, script{Src: s, Module: strings.HasSuffix(s, ".mjs")})
	}
	if cfg.InjectThemeJS {
		deferred = append(deferred, script{Src: "extensions/theme.js"})
	}
	for _, ext := range cfg.Extensions {
		deferred = append(deferred, script{Src: "extensions/" + ext, Module: strings.HasSuffix(ext, ".mjs")})
	}
	apps := make([]string, 0, len(cfg.CustomAppExtensions))
	for app := range cfg.CustomAppExtensions {
		apps = append(apps, app)
	}
	sort.Strings(apps)
	for _, app := range apps {
		for _, f := range cfg.CustomAppExtensions[app] {
			deferred = append(deferred, script{Src: "extensions/" + app + "/" + f, Module: strings.HasSuffix(f, ".mjs")})
		}
	}

	config := map[string]any{
		"version":                cfg.SpicetifyVersion,
		"current_theme":          cfg.CurrentTheme,
		"color_scheme":           cfg.ColorScheme,
		"extensions":             nonNil(cfg.Extensions),
		"custom_apps":            nonNil(cfg.CustomApps),
		"check_spicetify_update": cfg.CheckSpicetifyUpdate,
		"platform":               "android",
	}

	configJSON, _ := json.Marshal(config)
	stylesJSON, _ := json.Marshal(styles)
	blockingJSON, _ := json.Marshal(blocking)
	deferredJSON, _ := json.Marshal(deferred)
	baseJSON, _ := json.Marshal(base)

	return fmt.Sprintf(documentStartTemplate, baseJSON, configJSON, stylesJSON, blockingJSON, deferredJSON)
}

type script struct {
	Src    string `json:"src"`
	Module bool   `json:"module,omitempty"`
}

func nonNil(s []string) []string {
	if s == nil {
		return []string{}
	}
	return s
}

// documentStartTemplate runs at document start in the page's main world.
// The wrapper is fetched synchronously and evaluated with indirect eval
// ('unsafe-eval' is allowed by the web player's CSP) so that the Spicetify
// global exists before the patched bundles execute. Everything else is
// appended as same-origin elements as soon as the DOM allows.
//
// The web player's service worker precaches the original bundles and would
// serve them unpatched on later visits (in an Android WebView its requests
// bypass the app's request interceptor). Service worker support is therefore
// hidden from the page, existing registrations and their caches are removed,
// and a page still controlled by an old service worker is reloaded once.
const documentStartTemplate = `(function(){
if (window.top !== window || window.__spicetifyAndroidBoot) return;
if (!/^https:\/\/open\.spotify\.com$/.test(location.origin)) return;
window.__spicetifyAndroidBoot = true;
var sw = navigator.serviceWorker;
if (sw) {
  // Spotify only registers its worker when "serviceWorker" in navigator;
  // without it the web player takes its path for browsers without support.
  try { delete Navigator.prototype.serviceWorker; } catch (e) {}
  if ("serviceWorker" in navigator) {
    try {
      Object.defineProperty(sw, "register", { configurable: true, value: function () {
        return Promise.reject(new DOMException("Service workers are disabled by Spicetify", "SecurityError"));
      } });
    } catch (e) {}
  }
  var controlled = !!sw.controller;
  var cleanup = [];
  if (sw.getRegistrations) cleanup.push(sw.getRegistrations().then(function (regs) {
    return Promise.all(regs.map(function (r) { return r.unregister(); }));
  }));
  if (window.caches && caches.keys) cleanup.push(caches.keys().then(function (keys) {
    return Promise.all(keys.filter(function (k) { return /workbox|precache/i.test(k); }).map(function (k) { return caches.delete(k); }));
  }));
  Promise.all(cleanup).then(function () {
    if (!controlled) return;
    var flag = "spicetify-android:sw-reload";
    try { if (sessionStorage.getItem(flag)) return; sessionStorage.setItem(flag, "1"); } catch (e) { return; }
    location.reload();
  }, function () {});
}
var base = %s;
var config = %s;
var styles = %s;
var blocking = %s;
var deferred = %s;
for (var i = 0; i < blocking.length; i++) {
  try {
    var x = new XMLHttpRequest();
    x.open("GET", base + blocking[i], false);
    x.send(null);
    if (x.status === 200) (0, eval)(x.responseText + "\n//# sourceURL=" + location.origin + base + blocking[i]);
    else console.error("[spicetify-android] failed to load", blocking[i], x.status);
  } catch (e) { console.error("[spicetify-android] error in", blocking[i], e); }
}
window.Spicetify = window.Spicetify || {};
Spicetify.Config = config;
function inject() {
  var head = document.head || document.documentElement;
  for (var i = 0; i < styles.length; i++) {
    var l = document.createElement("link");
    l.rel = "stylesheet"; l.className = "userCSS"; l.href = base + styles[i];
    head.appendChild(l);
  }
  var chain = Promise.resolve();
  deferred.forEach(function (s) {
    chain = chain.then(function () {
      return new Promise(function (resolve) {
        var el = document.createElement("script");
        el.src = base + s.src;
        if (s.module) el.type = "module";
        el.onload = el.onerror = function () { resolve(); };
        (document.body || head).appendChild(el);
      });
    });
  });
}
if (document.head) inject();
else {
  var mo = new MutationObserver(function () { if (document.head) { mo.disconnect(); inject(); } });
  mo.observe(document.documentElement || document, { childList: true, subtree: true });
}
})();`

// DesktopUserAgent is the user agent the host should use so that
// open.spotify.com serves the full web player instead of the mobile landing page.
const DesktopUserAgent = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
