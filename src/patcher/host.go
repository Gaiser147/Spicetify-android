package patcher

import (
	"encoding/json"
	"path"
	"strings"
)

// The functions in this file describe how the host (the Android app, or the
// devhost used for testing) maps requests under BasePath to Spicetify files.
// Both hosts call them so the layout is defined in one place.
//
// File layout (bundled assets, optionally overridden by user files):
//
//	jsHelper/            spicetifyWrapper.js, sidebarConfig.js, homeConfig.js, expFeatures.js
//	Extensions/          *.js, *.mjs
//	CustomApps/<app>/    index.js, manifest.json, style.css, ...
//	Themes/<theme>/      color.ini, user.css, theme.js, assets/
//	android/             bridge.js, mobile.css, mobile.js
//	snippets/            *.css (appended to user.css)

// Special resources that are generated instead of read from a file.
const (
	ResourceColorsCSS = "generated:colors.css"
	ResourceUserCSS   = "generated:user.css"
)

// ResolveSpicetifyPath maps a path below BasePath (e.g. "helper/spicetifyWrapper.js")
// to a list of candidate files (relative to the layout root), tried in order.
// For generated resources a single Resource* constant is returned.
func (p *Patcher) ResolveSpicetifyPath(rel string) string {
	b, _ := json.Marshal(p.resolve(rel))
	return string(b)
}

func (p *Patcher) resolve(rel string) []string {
	rel = strings.TrimPrefix(path.Clean("/"+rel), "/")
	if rel == "" || strings.HasPrefix(rel, "..") {
		return nil
	}
	theme := p.cfg.CurrentTheme

	switch {
	case rel == "colors.css":
		return []string{ResourceColorsCSS}
	case rel == "user.css":
		return []string{ResourceUserCSS}
	case strings.HasPrefix(rel, "helper/"):
		return []string{"jsHelper/" + strings.TrimPrefix(rel, "helper/")}
	case strings.HasPrefix(rel, "android/"):
		return []string{rel}
	case rel == "extensions/theme.js":
		if theme == "" {
			return nil
		}
		return []string{"Themes/" + theme + "/theme.js"}
	case strings.HasPrefix(rel, "extensions/"):
		name := strings.TrimPrefix(rel, "extensions/")
		if i := strings.Index(name, "/"); i > 0 {
			// Custom app extension file: extensions/<app>/<file>
			return []string{"CustomApps/" + name}
		}
		return []string{"Extensions/" + name}
	case strings.HasPrefix(rel, "assets/"):
		rest := strings.TrimPrefix(rel, "assets/")
		var out []string
		if theme != "" {
			out = append(out, "Themes/"+theme+"/assets/"+rest)
		}
		if i := strings.Index(rest, "/"); i > 0 {
			out = append(out, "CustomApps/"+rest)
		}
		return out
	}
	// Theme assets are copied to the xpui root on desktop, so user.css may
	// reference them relative to itself.
	if theme != "" {
		return []string{"Themes/" + theme + "/assets/" + rel}
	}
	return nil
}

// ContentType returns the MIME type for a Spicetify path.
func ContentType(rel string) string {
	switch strings.ToLower(path.Ext(rel)) {
	case ".js", ".mjs":
		return "text/javascript"
	case ".css":
		return "text/css"
	case ".json":
		return "application/json"
	case ".svg":
		return "image/svg+xml"
	case ".png":
		return "image/png"
	case ".jpg", ".jpeg":
		return "image/jpeg"
	case ".gif":
		return "image/gif"
	case ".webp":
		return "image/webp"
	case ".woff2":
		return "font/woff2"
	case ".woff":
		return "font/woff"
	case ".ttf":
		return "font/ttf"
	case ".html":
		return "text/html"
	}
	return "application/octet-stream"
}

// CustomAppManifest is the subset of a custom app's manifest.json the host needs.
type CustomAppManifest struct {
	Files          []string `json:"subfiles"`
	ExtensionFiles []string `json:"subfiles_extension"`
	Assets         []string `json:"assets"`
}

// CustomAppSubfiles returns, as a JSON array, the files whose content must be
// appended to index.js (in order) before wrapping it with CustomAppChunk.
func CustomAppSubfiles(manifestJSON string) string {
	var m CustomAppManifest
	_ = json.Unmarshal([]byte(manifestJSON), &m)
	b, _ := json.Marshal(nonNil(m.Files))
	return string(b)
}

// CustomAppExtensionFiles returns the manifest's "subfiles_extension" entry as JSON.
func CustomAppExtensionFiles(manifestJSON string) string {
	var m CustomAppManifest
	_ = json.Unmarshal([]byte(manifestJSON), &m)
	b, _ := json.Marshal(nonNil(m.ExtensionFiles))
	return string(b)
}

// UserCSS joins the theme's user.css with the enabled CSS snippets.
func UserCSS(themeCSS string, snippetsJSON string) string {
	var snippets []string
	_ = json.Unmarshal([]byte(snippetsJSON), &snippets)
	var b strings.Builder
	b.WriteString(themeCSS)
	for _, s := range snippets {
		b.WriteString("\n/* spicetify-android snippet */\n")
		b.WriteString(s)
	}
	return b.String()
}
