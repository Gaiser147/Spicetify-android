// Package spicengine is the gomobile binding of Spicetify's patch engine
// (github.com/spicetify/cli/src/patcher) for the Android app.
//
// Build: gomobile bind -target=android -androidapi 26 -javapkg app.spicetify.android \
//
//	-o ../app/libs/spicengine.aar .
//
// Java: app.spicetify.android.spicengine.Spicengine (package functions) and
// app.spicetify.android.spicengine.Engine.
package spicengine

import "github.com/spicetify/cli/src/patcher"

// Engine wraps a configured patcher.
type Engine struct {
	p *patcher.Patcher
}

// NewEngine creates an engine from a JSON config (patcher.Config) and a JSON
// CSS translation map.
func NewEngine(configJSON string, cssMapJSON string) (*Engine, error) {
	p, err := patcher.New(configJSON, cssMapJSON)
	if err != nil {
		return nil, err
	}
	return &Engine{p: p}, nil
}

// PatchAsset patches a web player JS/CSS asset.
func (e *Engine) PatchAsset(url string, content string) string { return e.p.PatchAsset(url, content) }

// DocumentStartScript returns the script to inject at document start.
func (e *Engine) DocumentStartScript() string { return e.p.DocumentStartScript() }

// ResolveSpicetifyPath returns candidate files (JSON array) for a path below /__spicetify/.
func (e *Engine) ResolveSpicetifyPath(rel string) string { return e.p.ResolveSpicetifyPath(rel) }

// DocumentShellPath returns the document to load instead of a main frame
// navigation to a custom app route ("/" or "/intl-xx/"), "" for none.
func (e *Engine) DocumentShellPath(path string) string { return e.p.DocumentShellPath(path) }

// RewriteCSP opens up script-src in the page's Content-Security-Policy.
func RewriteCSP(header string) string { return patcher.RewriteCSP(header) }

// Report returns which patches were applied (JSON).
func (e *Engine) Report() string { return e.p.Report() }

// Version is the engine version, used as part of the patch cache key.
func Version() string { return patcher.Version() }

// DesktopUserAgent is the user agent the WebView must use.
func DesktopUserAgent() string { return patcher.DesktopUserAgent }

// AssetKind classifies a web player URL: "main", "vendor", "chunk", "css",
// "chunk-css" or "" for assets that are not patched.
func AssetKind(url string) string {
	kind, _ := patcher.Classify(url)
	return kind
}

// CustomAppName returns the custom app name if url requests a custom app
// chunk (spicetify-routes-<app>...), or "".
func CustomAppName(url string) string {
	app, _, ok := patcher.IsCustomAppAsset(url)
	if !ok {
		return ""
	}
	return app
}

// CustomAppChunk wraps a custom app's concatenated JavaScript into a chunk.
func CustomAppChunk(app string, js string) string { return patcher.CustomAppChunk(app, js) }

// CustomAppSubfiles returns the manifest "subfiles" (JSON array).
func CustomAppSubfiles(manifestJSON string) string { return patcher.CustomAppSubfiles(manifestJSON) }

// CustomAppExtensionFiles returns the manifest "subfiles_extension" (JSON array).
func CustomAppExtensionFiles(manifestJSON string) string {
	return patcher.CustomAppExtensionFiles(manifestJSON)
}

// ColorSchemes lists the schemes of a color.ini (JSON array).
func ColorSchemes(colorIni string) string { return patcher.ColorSchemes(colorIni) }

// ColorCSS builds colors.css for a color.ini and scheme.
func ColorCSS(colorIni string, scheme string) string { return patcher.ColorCSS(colorIni, scheme) }

// UserCSS joins a theme's user.css with snippets (JSON array of CSS strings).
func UserCSS(themeCSS string, snippetsJSON string) string {
	return patcher.UserCSS(themeCSS, snippetsJSON)
}

// ContentType returns the MIME type for a path.
func ContentType(path string) string { return patcher.ContentType(path) }
