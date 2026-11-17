package patcher

import (
	"encoding/json"
	"io"
	"net/http"
	"os"
	"regexp"
	"strings"
	"testing"
	"time"
)

func TestClassify(t *testing.T) {
	cases := map[string][2]string{
		"https://open.spotifycdn.com/cdn/build/web-player/web-player.73ca6ea8.js":         {KindMain, "xpui.js"},
		"https://open.spotifycdn.com/cdn/build/web-player/vendor~web-player.eda92aec.js":  {KindVendor, "vendor~xpui.js"},
		"https://open.spotifycdn.com/cdn/build/web-player/encore~web-player.550f1cd1.js":  {KindChunk, "encore~web-player.js"},
		"https://open.spotifycdn.com/cdn/build/web-player/xpui-routes-search.1a2b3c4d.js": {KindChunk, "xpui-routes-search.js"},
		"https://open.spotifycdn.com/cdn/build/web-player/dwp-top-bar.1a2b3c4d.js?x=1":    {KindChunk, "dwp-top-bar.js"},
		"https://open.spotifycdn.com/cdn/build/web-player/web-player.ce1435f7.css":        {KindCSS, "xpui.css"},
		"https://open.spotifycdn.com/cdn/build/web-player/1234.ce1435f7.css":              {KindChunkCSS, "1234.css"},
		"https://open.spotifycdn.com/cdn/images/foo.png":                                  {KindUnknown, ""},
	}
	for url, want := range cases {
		kind, name := Classify(url)
		if kind != want[0] || name != want[1] {
			t.Errorf("Classify(%q) = %q, %q; want %q, %q", url, kind, name, want[0], want[1])
		}
	}
}

func TestIsCustomAppAsset(t *testing.T) {
	cases := map[string][2]string{
		"https://open.spotifycdn.com/cdn/build/web-player/spicetify-routes-lyrics-plus.undefined.js": {"lyrics-plus", "js"},
		"https://open.spotifycdn.com/cdn/build/web-player/spicetify-routes-marketplace.css":          {"marketplace", "css"},
		"https://open.spotifycdn.com/cdn/build/web-player/spicetify-routes-new-releases.abc.css?v=1": {"new-releases", "css"},
	}
	for url, want := range cases {
		app, ext, ok := IsCustomAppAsset(url)
		if !ok || app != want[0] || ext != want[1] {
			t.Errorf("IsCustomAppAsset(%q) = %q, %q, %v; want %q, %q", url, app, ext, ok, want[0], want[1])
		}
	}
	if _, _, ok := IsCustomAppAsset("https://open.spotifycdn.com/cdn/build/web-player/web-player.123456.js"); ok {
		t.Error("web-player bundle must not be a custom app asset")
	}
}

func TestDocumentStartScript(t *testing.T) {
	p, err := New(`{"extensions":["bookmark.js","mod.mjs"],"customApps":["lyrics-plus"],"customAppExtensions":{"marketplace":["extension.js"]},"extraScripts":["android/bridge.js"]}`, "")
	if err != nil {
		t.Fatal(err)
	}
	s := p.DocumentStartScript()
	for _, want := range []string{
		`"helper/spicetifyWrapper.js"`,
		`"extensions/bookmark.js"`,
		`{"src":"extensions/mod.mjs","module":true}`,
		`"extensions/marketplace/extension.js"`,
		`"android/bridge.js"`,
		`"custom_apps":["lyrics-plus"]`,
		`"platform":"android"`,
		`"/__spicetify/"`,
		`delete Navigator.prototype.serviceWorker`,
		`unregister()`,
	} {
		if !strings.Contains(s, want) {
			t.Errorf("document start script is missing %s", want)
		}
	}
}

func TestCustomAppChunk(t *testing.T) {
	c := CustomAppChunk("lyrics-plus", "function render(){}")
	if !strings.Contains(c, `push([["spicetify-routes-lyrics-plus"],{"spicetify-routes-lyrics-plus":`) || !strings.Contains(c, "function render(){}") {
		t.Errorf("unexpected chunk: %s", c)
	}
}

func TestColorCSS(t *testing.T) {
	colorIni, err := os.ReadFile("../../Themes/SpicetifyDefault/color.ini")
	if err != nil {
		t.Fatal(err)
	}
	var schemes []string
	if err := json.Unmarshal([]byte(ColorSchemes(string(colorIni))), &schemes); err != nil || len(schemes) == 0 {
		t.Fatalf("ColorSchemes returned %v (%v)", schemes, err)
	}
	css := ColorCSS(string(colorIni), schemes[0])
	if !strings.HasPrefix(css, ":root {") || !strings.Contains(css, "--spice-text: #") || !strings.Contains(css, "--spice-rgb-text: ") {
		t.Errorf("unexpected colors.css:\n%s", css)
	}
	if css != ColorCSS(string(colorIni), schemes[0]) {
		t.Error("ColorCSS must be deterministic")
	}
	if base := ColorCSS("", ""); !strings.Contains(base, "--spice-text") {
		t.Error("empty color.ini must fall back to base colors")
	}
}

// TestLiveWebPlayer downloads the current web player and checks that the key
// patches still match. It is skipped when open.spotify.com is unreachable.
func TestLiveWebPlayer(t *testing.T) {
	if os.Getenv("SPICETIFY_SKIP_LIVE") != "" {
		t.Skip("SPICETIFY_SKIP_LIVE set")
	}
	client := &http.Client{Timeout: 30 * time.Second}
	get := func(url string) (string, error) {
		req, _ := http.NewRequest("GET", url, nil)
		req.Header.Set("User-Agent", DesktopUserAgent)
		resp, err := client.Do(req)
		if err != nil {
			return "", err
		}
		defer resp.Body.Close()
		b, err := io.ReadAll(resp.Body)
		return string(b), err
	}
	html, err := get("https://open.spotify.com/")
	if err != nil {
		t.Skipf("web player unreachable: %v", err)
	}
	urls := regexp.MustCompile(`https://open\.spotifycdn\.com/cdn/build/web-player/[\w~.-]+\.js`).FindAllString(html, -1)
	if len(urls) == 0 {
		t.Skip("no web player bundles found in page")
	}

	p, err := New(`{"customApps":["lyrics-plus"]}`, "")
	if err != nil {
		t.Fatal(err)
	}
	var main, vendor string
	for _, u := range urls {
		kind, _ := Classify(u)
		switch kind {
		case KindMain:
			main, err = get(u)
			if err != nil {
				t.Fatal(err)
			}
			main = p.PatchAsset(u, main)
		case KindVendor:
			vendor, err = get(u)
			if err != nil {
				t.Fatal(err)
			}
			vendor = p.PatchAsset(u, vendor)
		}
	}
	all := main + vendor
	for _, want := range []string{
		"Spicetify._platform=",
		"Spicetify.ReactComponent.PlatformProvider=",
		"Spicetify.ContextMenuV2._context",
		"Spicetify.Tippy=",
		"spicetify-routes-lyrics-plus",
		`path:"/lyrics-plus/*"`,
		`Spicetify._renderNavLinks(["lyrics-plus"], true),(0,`,
	} {
		if !strings.Contains(all, want) {
			t.Errorf("patched web player is missing %q", want)
		}
	}
	t.Logf("report: %s", p.Report())
}

func TestIsCustomAppManifest(t *testing.T) {
	for path, want := range map[string]string{
		"/spicetify-routes-marketplace.json":         "marketplace",
		"/intl-de/spicetify-routes-lyrics-plus.json": "lyrics-plus",
	} {
		if app, ok := IsCustomAppManifest(path); !ok || app != want {
			t.Errorf("IsCustomAppManifest(%q) = %q, %v", path, app, ok)
		}
	}
	if _, ok := IsCustomAppManifest("/spicetify-routes-x.js"); ok {
		t.Error("js chunk must not be a manifest")
	}
}
