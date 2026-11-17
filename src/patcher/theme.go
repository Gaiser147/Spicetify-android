package patcher

import (
	"encoding/json"
	"sort"
	"strings"

	"github.com/go-ini/ini"
	"github.com/spicetify/cli/src/apply"
)

// ColorSchemes returns the scheme names in a theme's color.ini as a JSON array,
// in file order.
func ColorSchemes(colorIni string) string {
	names := []string{}
	cfg, err := ini.InsensitiveLoad([]byte(colorIni))
	if err == nil {
		for _, s := range cfg.Sections() {
			if s.Name() == ini.DefaultSection {
				continue
			}
			names = append(names, s.Name())
		}
	}
	b, _ := json.Marshal(names)
	return string(b)
}

// ColorCSS builds colors.css for the given color.ini content and scheme name,
// using the same rules as `spicetify apply`: an empty or unknown scheme falls
// back to the first scheme; an empty or invalid color.ini yields the base colors.
func ColorCSS(colorIni string, scheme string) string {
	cfg, err := ini.InsensitiveLoad([]byte(colorIni))
	if err != nil {
		return apply.ColorCSS(nil)
	}
	sections := cfg.Sections()
	if len(sections) < 2 {
		return apply.ColorCSS(nil)
	}
	section := sections[1]
	if scheme != "" {
		if s, err := cfg.GetSection(scheme); err == nil {
			section = s
		}
	}
	return sortLines(apply.ColorCSS(section.KeysHash()))
}

// sortLines makes the generated CSS deterministic (map iteration order).
func sortLines(css string) string {
	var vars, rgb []string
	for _, l := range strings.Split(css, "\n") {
		t := strings.TrimSpace(l)
		switch {
		case strings.HasPrefix(t, "--spice-rgb-"):
			rgb = append(rgb, l)
		case strings.HasPrefix(t, "--spice-"):
			vars = append(vars, l)
		}
	}
	sort.Strings(vars)
	sort.Strings(rgb)
	return ":root {\n" + strings.Join(vars, "\n") + "\n\n" + strings.Join(rgb, "\n") + "\n}\n"
}
