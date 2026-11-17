package patcher

import (
	"regexp"
	"strings"
)

// The web player's routes for custom apps (/marketplace, /lyrics-plus, ...)
// only exist on the client. Loading one directly, e.g. when an extension or
// the Marketplace reloads the page, makes Spotify's server answer with its
// "page not found" document. The host serves the app shell instead and the
// client side router renders the custom app.
var localePrefixRe = regexp.MustCompile(`^/intl-[a-zA-Z]{2,3}(?:-[a-zA-Z0-9]{2,4})?/`)

// DocumentShellPath returns the path of the document the host should load for
// a main frame navigation to urlPath: "/" (or "/intl-xx/") when the first path
// segment is a configured custom app, "" when the request needs no rewrite.
func (p *Patcher) DocumentShellPath(urlPath string) string {
	prefix := "/"
	rest := strings.TrimPrefix(urlPath, "/")
	if m := localePrefixRe.FindString(urlPath); m != "" {
		prefix = m
		rest = urlPath[len(m):]
	}
	segment, _, _ := strings.Cut(rest, "/")
	if segment == "" {
		return ""
	}
	for _, app := range p.cfg.CustomApps {
		if segment == app {
			return prefix
		}
	}
	return ""
}

// OpenScriptSrc replaces the web player's script-src. Spicetify on the
// desktop runs without a CSP; extensions and themes load scripts from any
// host (Marketplace: cdn.jsdelivr.net, raw.githubusercontent.com) and use
// inline scripts.
const OpenScriptSrc = "script-src * 'unsafe-inline' 'unsafe-eval' blob: data:"

// RewriteCSP returns the Content-Security-Policy header with script-src (and
// script-src-elem / script-src-attr, if present) opened up like the desktop
// client. Hashes and nonces are dropped with the old directive; they would
// make browsers ignore 'unsafe-inline'. Every other directive is kept.
func RewriteCSP(header string) string {
	if strings.TrimSpace(header) == "" {
		return header
	}
	var out []string
	replaced, hasDefault := false, false
	for _, directive := range strings.Split(header, ";") {
		d := strings.TrimSpace(directive)
		if d == "" {
			continue
		}
		name, _, _ := strings.Cut(d, " ")
		switch strings.ToLower(name) {
		case "script-src":
			if !replaced {
				out = append(out, OpenScriptSrc)
				replaced = true
			}
		case "script-src-elem", "script-src-attr":
			// Drop: they would override the open script-src.
		case "default-src":
			hasDefault = true
			out = append(out, d)
		default:
			out = append(out, d)
		}
	}
	if !replaced && hasDefault {
		// default-src would otherwise restrict scripts.
		out = append(out, OpenScriptSrc)
	}
	return strings.Join(out, "; ")
}
