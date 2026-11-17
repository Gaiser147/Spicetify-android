package preprocess

import (
	"fmt"
	"strings"

	"github.com/spicetify/cli/src/utils"
)

// ContentPatcher applies Spicetify's preprocess patches to in-memory file
// contents. It holds no file system state, so it is shared by the desktop CLI
// (which patches the extracted xpui folder) and the Android port (which
// patches the web player bundles while they are being downloaded).
type ContentPatcher struct {
	flags          Flag
	spotifyVersion utils.SpotifyVersion
	cssMap         map[string]string
	cssMapJS       *strings.Replacer
	cssMapCSS      *strings.Replacer
}

// NewContentPatcher builds a patcher for the given CSS translation map and flags.
func NewContentPatcher(cssTranslationMap map[string]string, flags Flag) *ContentPatcher {
	cssMapPairs := make([]string, 0, len(cssTranslationMap)*4)
	cssMapCSSPairs := make([]string, 0, len(cssTranslationMap)*2)
	for k, v := range cssTranslationMap {
		cssMapPairs = append(cssMapPairs, k+":", `"`+v+`":`)
		cssMapPairs = append(cssMapPairs, k, v)
		cssMapCSSPairs = append(cssMapCSSPairs, k, v)
	}

	return &ContentPatcher{
		flags:          flags,
		spotifyVersion: utils.ParseSpotifyVersion(flags.SpotifyVer),
		cssMap:         cssTranslationMap,
		cssMapJS:       strings.NewReplacer(cssMapPairs...),
		cssMapCSS:      strings.NewReplacer(cssMapCSSPairs...),
	}
}

// PatchJS applies all JavaScript patches. fileName is the desktop file name
// (e.g. "xpui.js", "vendor~xpui.js"); it selects which API exposures apply.
func (p *ContentPatcher) PatchJS(fileName, content string) string {
	flags := p.flags
	spotifyVersion := p.spotifyVersion

	if flags.DisableSentry && (fileName == "xpui.js" || fileName == "xpui-snapshot.js") {
		content = disableSentry(content)
	}

	if flags.DisableLogging {
		content = disableLogging(content)
	}

	if flags.ExposeAPIs {
		switch fileName {
		case "xpui-modules.js", "xpui-snapshot.js":
			content = exposeAPIs_main(content)
			content = exposeAPIs_vendor(content)
		case "xpui.js":
			content = exposeAPIs_main(content)
			if spotifyVersion.AtLeast(1, 2, 57) {
				content = exposeAPIs_vendor(content)
			}
		case "vendor~xpui.js":
			content = exposeAPIs_vendor(content)
		}

		if spotifyVersion.AtLeast(1, 2, 28) && !spotifyVersion.AtLeast(1, 2, 58) {
			utils.ReplaceOnce(&content, `(typeName\])`, func(submatches ...string) string {
				return fmt.Sprintf(`%s || []`, submatches[1])
			})
		}

		// to avoid syntaxerror on Spotify 1.2.78 and above
		if spotifyVersion.AtLeast(1, 2, 0) && !spotifyVersion.AtLeast(1, 2, 78) {
			utils.ReplaceOnce(&content, `\(\({[^}]*,\s*imageSrc`, func(submatches ...string) string {
				return fmt.Sprintf("Spicetify.Snackbar.enqueueImageSnackbar=%s", submatches[0])
			})
		}

		content = additionalPatches(content)
	}

	if fileName == "dwp-top-bar.js" || fileName == "dwp-now-playing-bar.js" || fileName == "dwp-home-chips-row.js" {
		utils.ReplaceOnce(&content, `e\.state\.cinemaState`, func(submatches ...string) string {
			return "e.state?.cinemaState"
		})
	}

	// Bare keys with whitespace before the colon
	// the replacer's k+":" entry can't match these, so handle them first
	content = cssMapBareKeySpaceRe.ReplaceAllStringFunc(content, func(match string) string {
		colonIdx := strings.LastIndex(match, ":")
		key := strings.TrimRight(match[:colonIdx], " \t")
		if v, ok := p.cssMap[key]; ok {
			return `"` + v + `":`
		}
		return match
	})
	// Single pass: k+":" → "v": for bare keys,
	// k -> v for all other occurrences
	content = p.cssMapJS.Replace(content)
	content = colorVariableReplaceForJS(content)

	return content
}

// PatchCSS applies CSS class translation and, for the main stylesheet,
// appends the CSS rules Spicetify components rely on.
func (p *ContentPatcher) PatchCSS(fileName, content string) string {
	content = p.cssMapCSS.Replace(content)
	if p.flags.RemoveRTL {
		content = removeRTL(content)
	}
	if fileName == "xpui.css" || fileName == "xpui-snapshot.css" {
		content = content + SpicetifyBaseCSS
	}
	return content
}

// PatchHTML inserts the user CSS links and the wrapper script into index.html.
func (p *ContentPatcher) PatchHTML(content string) string {
	var tags string
	tags += "<link rel='stylesheet' class='userCSS' href='colors.css'>\n"
	tags += "<link rel='stylesheet' class='userCSS' href='user.css'>\n"

	if p.flags.ExposeAPIs {
		tags += "<script src='helper/spicetifyWrapper.js'></script>\n"
		tags += "<!-- spicetify helpers -->\n"
	}

	utils.Replace(&content, `<body(\sclass="[^"]*")?>`, func(submatches ...string) string {
		return fmt.Sprintf("%s\n%s", submatches[0], tags)
	})

	return content
}

// SpicetifyBaseCSS is appended to the main stylesheet (xpui.css / web-player.css).
var SpicetifyBaseCSS = `
						:where(.spicetify-topbar-button){align-items:center;display:flex}
						.spicetify-topbar-button>button{align-items:center;display:flex;height:32px;justify-content:center;padding:0;width:32px}
						:where(.main-addButton-button){align-items:center;background:transparent;border:0;color:rgba(var(--spice-rgb-text),.7);display:flex;height:32px;justify-content:center;padding:0;width:32px}
						:where(.main-addButton-button):focus,:where(.main-addButton-button):hover{color:var(--spice-text)}
						.main-addButton-button.main-addButton-active{color:var(--spice-button)}
						.main-addButton-button.main-addButton-active:focus,.main-addButton-button.main-addButton-active:hover{color:var(--spice-button-active)}
						.main-addButton-button.main-addButton-disabled{opacity:.4}
						:where(.main-gridContainer-gridContainer){display:grid;grid-gap:var(--grid-gap);grid-auto-rows:min-content;grid-template-columns:repeat(auto-fill,minmax(var(--min-column-width),1fr))}
						.main-gridContainer-fixedWidth{grid-template-columns:repeat(auto-fill,var(--column-width));width:calc((var(--column-count) - 1) * var(--grid-gap) + var(--column-count) * var(--column-width))}.main-cardImage-imageWrapper{background-color:var(--card-color,#333);border-radius:var(--card-image-radius,6px);box-shadow:0 8px 24px rgba(0,0,0,.5);padding-bottom:100%;position:relative;width:100%}.main-cardImage-circular{--card-image-radius:50%}.main-card-imagePlaceholder,.main-cardImage-image{height:100%;left:0;position:absolute;top:0;width:100%}.main-card-card{border-radius:8px;-webkit-box-flex:1;background:var(--spice-player);-ms-flex:1;flex:1;isolation:isolate;padding:16px;position:relative;-webkit-transition:background-color .3s;transition:background-color .3s;width:100%}.main-card-card:hover,.main-card-card[data-context-menu-open=true]{background:var(--spice-card)}.main-card-card:focus-within{background:var(--spice-card)}.main-card-card .main-card-cardLink{position:absolute;z-index:0}.RjYPjR7FsVf42b3a5Efm:before,.Z78JmW4GUvvxLXSFjb7R:not(:last-child):after{content:"\2022";margin:0 4px}.main-card-card a,.main-card-card button{position:relative;z-index:1}.main-card-cardLink{bottom:0;content:"";cursor:pointer;left:0;overflow:hidden;right:0;text-indent:100%;top:0;white-space:nowrap;z-index:0}.main-card-cardTitle{z-index:1}.main-card-cardTitleLink:focus,.main-card-cardTitleLink:hover{text-decoration:none}.Wmr5qZ5jui6X37XCrChA{opacity:0}.main-card-newEpisodeIndicator{margin-top:3px}.main-card-imageContainer{pointer-events:none;position:relative}.main-card-imageContainerOld,.main-card-imageContainerSkeleton{margin-bottom:16px}.main-card-cardMetadata{min-height:62px;line-clamp:2;-webkit-line-clamp:2;overflow:hidden;text-overflow:ellipsis}.main-card-DownloadStatusIndicator{bottom:12px;inset-inline-end:12px;position:absolute}.main-card-PlayButtonContainer{border-radius:500px;bottom:8px;-webkit-box-shadow:0 8px 8px rgba(var(--spice-rgb-shadow),.3);box-shadow:0 8px 8px rgba(var(--spice-rgb-shadow),.3);inset-inline-end:8px;opacity:0;pointer-events:none;position:absolute;-webkit-transform:translateY(8px);transform:translateY(8px);z-index:2}@media(pointer:coarse){.main-card-PlayButtonContainer{display:none}}@media(prefers-reduced-motion:no-preference){.main-card-PlayButtonContainer{-webkit-transition:opacity .2s ease-out,-webkit-transform .2s ease-out;transition:transform .2s ease-out,opacity .2s ease-out,-webkit-transform .2s ease-out}}.main-card-PlayButtonContainerVisible{opacity:1;pointer-events:auto;position:absolute;-webkit-transform:translateY(0);transform:translateY(0)}.main-card-cardContainer{--animation-speed:0.2s!important;white-space:normal}.main-card-card:hover .main-card-PlayButtonContainer,.main-card-cardContainer:hover .main-card-PlayButtonContainer{opacity:1;-webkit-transform:translateY(0);transform:translateY(0)}.main-card-card:focus-within .main-card-PlayButtonContainer,.main-card-cardContainer:focus-within{opacity:1;-webkit-transform:translateY(0);transform:translateY(0)}@media(pointer:fine){.main-card-card .main-card-cardLink:hover{cursor:pointer}.main-card-card:hover .main-card-PlayButtonContainer,.main-card-cardContainer:hover .main-card-PlayButtonContainer{opacity:1;pointer-events:auto;position:absolute}.main-card-card:focus-within .main-card-PlayButtonContainer,.main-card-cardContainer:focus-within .main-card-PlayButtonContainer{opacity:1;pointer-events:auto;position:absolute}}.main-card-cardMetadata,.v3isO2phyJAoZRkmme0G{display:-webkit-box;-webkit-box-orient:vertical}
					` + legacyFixedWidthGridCSS
