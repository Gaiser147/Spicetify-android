package patcher

import (
	"strings"
	"testing"
)

func TestDocumentShellPath(t *testing.T) {
	p, err := New(`{"customApps":["marketplace","lyrics-plus"]}`, "")
	if err != nil {
		t.Fatal(err)
	}
	cases := map[string]string{
		"/":                         "",
		"/marketplace":              "/",
		"/marketplace/":             "/",
		"/marketplace/tab/themes":   "/",
		"/lyrics-plus":              "/",
		"/intl-de/marketplace":      "/intl-de/",
		"/intl-pt-BR/lyrics-plus/x": "/intl-pt-BR/",
		"/intl-de/":                 "",
		"/album/123":                "",
		"/marketplacefoo":           "",
		"/search/marketplace":       "",
	}
	for in, want := range cases {
		if got := p.DocumentShellPath(in); got != want {
			t.Errorf("DocumentShellPath(%q) = %q; want %q", in, got, want)
		}
	}
}

func TestRewriteCSP(t *testing.T) {
	in := "script-src 'self' 'unsafe-eval' blob: open.spotifycdn.com 'sha256-WfsTi7oVogdF9vq5d14s2birjvCglqWF842fyHhzoNw='; frame-ancestors 'self' https://app.smartly.io/*;"
	got := RewriteCSP(in)
	want := OpenScriptSrc + "; frame-ancestors 'self' https://app.smartly.io/*"
	if got != want {
		t.Fatalf("RewriteCSP = %q; want %q", got, want)
	}
	if strings.Contains(RewriteCSP("default-src 'self'; script-src-elem 'self'; script-src 'self'"), "script-src-elem") {
		t.Error("script-src-elem must be dropped")
	}
	if got := RewriteCSP("default-src 'self'"); got != "default-src 'self'; "+OpenScriptSrc {
		t.Errorf("default-src only: %q", got)
	}
	if got := RewriteCSP("frame-ancestors 'self'"); got != "frame-ancestors 'self'" {
		t.Errorf("unrelated policy changed: %q", got)
	}
	if RewriteCSP("") != "" {
		t.Error("empty header must stay empty")
	}
}
