// Command devhost serves Spicetify files and patches web player assets over
// HTTP, mirroring what the Android app does inside its WebView. It is used by
// the browser end-to-end test (android/e2e) and to preview Spicetify for the
// web player in a desktop browser.
//
//	go run ./src/patcher/cmd/devhost -root build/spicetify-assets -apps lyrics-plus,marketplace
//
// Endpoints:
//
//	GET  /boot.js                       document start script
//	POST /patch?url=<asset url>         body: original asset, response: patched asset
//	GET  /__spicetify/<path>            Spicetify files (wrapper, extensions, theme, ...)
//	GET  /custom-app/<app>.<js|css>     custom app chunk
//	GET  /report                        which patches were applied
package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"strings"

	"github.com/spicetify/cli/src/patcher"
)

type host struct {
	root     string
	p        *patcher.Patcher
	theme    string
	scheme   string
	snippets []string
}

func main() {
	root := flag.String("root", ".", "layout root (jsHelper/, Extensions/, CustomApps/, Themes/, android/)")
	addr := flag.String("addr", "127.0.0.1:8787", "listen address")
	theme := flag.String("theme", "SpicetifyDefault", "theme name")
	scheme := flag.String("scheme", "", "color scheme")
	exts := flag.String("extensions", "", "comma separated extension file names")
	apps := flag.String("apps", "", "comma separated custom app names")
	extra := flag.String("extra-scripts", "android/bridge.js,android/mobile.js", "extra scripts below /__spicetify/")
	styles := flag.String("extra-styles", "android/mobile.css", "extra stylesheets below /__spicetify/")
	cssMap := flag.String("css-map", "", "css map JSON file")
	overrides := flag.String("config", "", `JSON merged into the patcher config, e.g. {"expFeatures":false}`)
	flag.Parse()

	h := &host{root: *root, theme: *theme, scheme: *scheme}
	cfg := patcher.DefaultConfig()
	cfg.CurrentTheme = *theme
	cfg.ColorScheme = *scheme
	cfg.Extensions = split(*exts)
	cfg.CustomApps = split(*apps)
	cfg.ExtraScripts = split(*extra)
	cfg.ExtraStyles = split(*styles)
	cfg.CustomAppExtensions = map[string][]string{}
	for _, app := range cfg.CustomApps {
		manifest, _ := os.ReadFile(filepath.Join(*root, "CustomApps", app, "manifest.json"))
		var files []string
		_ = json.Unmarshal([]byte(patcher.CustomAppExtensionFiles(string(manifest))), &files)
		if len(files) > 0 {
			cfg.CustomAppExtensions[app] = files
		}
	}
	if *overrides != "" {
		if err := json.Unmarshal([]byte(*overrides), &cfg); err != nil {
			log.Fatalf("invalid -config: %v", err)
		}
	}
	cfgJSON, _ := json.Marshal(cfg)
	var mapJSON []byte
	if *cssMap != "" {
		var err error
		if mapJSON, err = os.ReadFile(*cssMap); err != nil {
			log.Fatal(err)
		}
	}
	p, err := patcher.New(string(cfgJSON), string(mapJSON))
	if err != nil {
		log.Fatal(err)
	}
	h.p = p

	http.HandleFunc("/boot.js", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/javascript")
		io.WriteString(w, p.DocumentStartScript())
	})
	http.HandleFunc("/patch", func(w http.ResponseWriter, r *http.Request) {
		body, err := io.ReadAll(r.Body)
		if err != nil {
			http.Error(w, err.Error(), 400)
			return
		}
		io.WriteString(w, p.PatchAsset(r.URL.Query().Get("url"), string(body)))
	})
	http.HandleFunc("/report", func(w http.ResponseWriter, r *http.Request) {
		io.WriteString(w, p.Report())
	})
	http.HandleFunc("/custom-app/", h.customApp)
	http.HandleFunc("/__spicetify/", h.spicetifyFile)

	log.Printf("devhost listening on http://%s", *addr)
	log.Fatal(http.ListenAndServe(*addr, nil))
}

func (h *host) read(rel string) ([]byte, error) {
	return os.ReadFile(filepath.Join(h.root, filepath.FromSlash(rel)))
}

func (h *host) spicetifyFile(w http.ResponseWriter, r *http.Request) {
	rel := strings.TrimPrefix(r.URL.Path, "/__spicetify/")
	var candidates []string
	_ = json.Unmarshal([]byte(h.p.ResolveSpicetifyPath(rel)), &candidates)
	for _, c := range candidates {
		var data []byte
		var err error
		switch c {
		case patcher.ResourceColorsCSS:
			ini, _ := h.read("Themes/" + h.theme + "/color.ini")
			data = []byte(patcher.ColorCSS(string(ini), h.scheme))
		case patcher.ResourceUserCSS:
			css, _ := h.read("Themes/" + h.theme + "/user.css")
			snippets, _ := json.Marshal(h.snippets)
			data = []byte(patcher.UserCSS(string(css), string(snippets)))
		default:
			data, err = h.read(c)
			if err != nil {
				continue
			}
		}
		w.Header().Set("Content-Type", patcher.ContentType(rel))
		w.Write(data)
		return
	}
	http.NotFound(w, r)
}

func (h *host) customApp(w http.ResponseWriter, r *http.Request) {
	name := strings.TrimPrefix(r.URL.Path, "/custom-app/")
	ext := filepath.Ext(name)
	app := strings.TrimSuffix(name, ext)
	dir := "CustomApps/" + app + "/"
	switch ext {
	case ".json":
		manifest, err := h.read(dir + "manifest.json")
		if err != nil {
			manifest = []byte("{}")
		}
		w.Header().Set("Content-Type", "application/json")
		w.Write(manifest)
	case ".css":
		css, _ := h.read(dir + "style.css")
		w.Header().Set("Content-Type", "text/css")
		w.Write(css)
	case ".js":
		js, err := h.read(dir + "index.js")
		if err != nil {
			http.NotFound(w, r)
			return
		}
		manifest, _ := h.read(dir + "manifest.json")
		var subfiles []string
		_ = json.Unmarshal([]byte(patcher.CustomAppSubfiles(string(manifest))), &subfiles)
		for _, f := range subfiles {
			if sub, err := h.read(dir + f); err == nil {
				js = append(append(js, '\n'), sub...)
			}
		}
		w.Header().Set("Content-Type", "text/javascript")
		io.WriteString(w, patcher.CustomAppChunk(app, string(js)))
	default:
		http.NotFound(w, r)
	}
}

func split(s string) []string {
	var out []string
	for _, v := range strings.Split(s, ",") {
		if v = strings.TrimSpace(v); v != "" {
			out = append(out, v)
		}
	}
	return out
}

func init() {
	log.SetFlags(0)
	log.SetPrefix(fmt.Sprintf("[devhost %d] ", os.Getpid()))
}
