// End-to-end check of the Android port's web layer in desktop Chromium.
// See harness.mjs for how the WebView setup is reproduced. Playback is not
// tested (Chromium here has no Widevine).
//
//   node android/e2e/e2e.mjs [--marketplace <dir>] [--out <dir>]
import { mkdirSync, writeFileSync } from "node:fs";
import { join, resolve } from "node:path";

import { DESKTOP_UA, arg, devices, repo, startHarness, waitForStablePage } from "./harness.mjs";

const out = resolve(arg("--out", join(repo, "build/e2e")));
mkdirSync(out, { recursive: true });
const {
  openPlayer,
  stop,
  report: patchReport,
  apps,
} = await startHarness({
  out,
  marketplace: arg("--marketplace", ""),
  extensions: ["bookmark.js", "loopyLoop.js", "trashbin.js"],
});

const results = [];
const check = (name, ok, detail = "") => {
  results.push({ name, ok, detail });
  console.log(`${ok ? "PASS" : "FAIL"}  ${name}${detail ? ` — ${detail}` : ""}`);
};

// Desktop / tablet sized viewport.
{
  const { browser, page, errors, stats } = await openPlayer({ viewport: { width: 1280, height: 800 } }, { fakeNative: true });
  const state = await page
    .waitForFunction(
      () =>
        window.Spicetify?.Platform?.History &&
        window.Spicetify?.React &&
        window.Spicetify?.ReactDOM &&
        window.Spicetify?.Player?.origin &&
        window.Spicetify?.Topbar,
      null,
      { timeout: 90_000 },
    )
    .then(() => true)
    .catch(() => false);
  check("Spicetify core APIs available", state);
  // A reload on the first start leaves the Android WebView blank.
  check("No page reload on first start", stats.navigations === 1, `${stats.navigations} navigation(s)`);
  const sw = await page.evaluate(() => ({ api: "serviceWorker" in navigator, controller: !!globalThis.navigator.serviceWorker?.controller }));
  check("Service worker disabled", !sw.api && !sw.controller, JSON.stringify(sw));
  const info = await page.evaluate(() => ({
    config: window.Spicetify?.Config,
    platformKeys: Object.keys(window.Spicetify?.Platform || {}).length,
    uri: typeof window.Spicetify?.URI?.from === "function",
    contextMenu: typeof window.Spicetify?.ContextMenu?.Item === "function",
    reactComponents: Object.keys(window.Spicetify?.ReactComponent || {}).length,
    colors: getComputedStyle(document.documentElement).getPropertyValue("--spice-text").trim(),
    userCss: [...document.querySelectorAll("link.userCSS")].map((l) => l.getAttribute("href")),
    bridge: typeof window.SpicetifyAndroidBridge?.command === "function",
    playerShim: typeof window.Spicetify?.Player?.origin?._events?.addListener === "function",
  }));
  check("Spicetify.Config injected", info.config?.platform === "android", JSON.stringify(info.config?.custom_apps));
  check("Spicetify.Platform resolved", info.platformKeys > 20, `${info.platformKeys} APIs`);
  check("Spicetify.URI exposed", info.uri);
  check("Spicetify.ContextMenu available", info.contextMenu);
  check("Spicetify.ReactComponent exposed", info.reactComponents > 5, `${info.reactComponents} components`);
  check("Theme colors applied", info.colors.startsWith("#"), info.colors);
  check("Android bridge loaded", info.bridge);
  check("PlayerAPI compatibility shim", info.playerShim);

  // Custom app links in the top bar (with names and icons from the manifests).
  const navLinks = await page
    .waitForFunction(
      (expected) => {
        const labels = [...document.querySelectorAll("#global-nav-bar [aria-label]")].map((e) => e.getAttribute("aria-label"));
        return expected.every((name) => labels.includes(name)) && labels;
      },
      apps.includes("marketplace") ? ["Marketplace", "Lyrics"] : ["Lyrics"],
      { timeout: 20_000 },
    )
    .then((h) => h.jsonValue())
    .catch(() => null);
  check("Custom app links in the top bar", !!navLinks, JSON.stringify(navLinks));
  // Native manager button (Spicetify's own top bar mount does not exist on the web).
  const manager = await page
    .waitForSelector("#spicetify-android-manager", { state: "visible", timeout: 10_000 })
    .then(async (el) => {
      await el.click();
      return page.evaluate(() => (window.__nativeMessages || []).some((m) => m.method === "openManager"));
    })
    .catch(() => false);
  check("Spicetify manager button opens the native manager", manager);
  if (apps.includes("marketplace")) {
    await page.click('#global-nav-bar [aria-label="Marketplace"]').catch(() => {});
    await page.waitForTimeout(1500);
    check("Marketplace link navigates", await page.evaluate(() => location.pathname.startsWith("/marketplace")));
  }

  // Custom app route.
  await page.evaluate(() => window.Spicetify.Platform.History.push("/lyrics-plus"));
  const lyrics = await page
    .waitForFunction(
      () => {
        const t = document.querySelector("#main-view")?.innerText || "";
        return !/Something went wrong/.test(t) && !!document.querySelector("#main-view [class*=lyrics]");
      },
      null,
      { timeout: 30_000 },
    )
    .then(() => true)
    .catch(() => false);
  check("Custom app /lyrics-plus renders", lyrics);
  await page.screenshot({ path: join(out, "desktop-lyrics-plus.png") });

  if (apps.includes("marketplace")) {
    await page.evaluate(() => window.Spicetify.Platform.History.push("/marketplace"));
    const mp = await page
      .waitForFunction(
        () =>
          !/Something went wrong/.test(document.querySelector("#main-view")?.innerText || "") &&
          !!document.querySelector("#main-view [class*=marketplace]"),
        null,
        { timeout: 30_000 },
      )
      .then(() => true)
      .catch(() => false);
    check("Custom app /marketplace renders", mp);
    await page.waitForTimeout(3000);
    await page.screenshot({ path: join(out, "desktop-marketplace.png") });
  }

  // Android Auto library: search and browse through the bridge (works without login).
  const auto = await page.evaluate(async () => {
    try {
      const L = window.SpicetifyAndroidBridge.library;
      const results = await L.search("daft punk");
      const playlist = results.find((r) => r.id.startsWith("playlist|"));
      const album = results.find((r) => r.id.startsWith("album|"));
      const playlistItems = playlist ? await L.browse(playlist.id) : [];
      const albumItems = album ? await L.browse(album.id) : [];
      return {
        results: results.length,
        playlistItems: playlistItems.length,
        albumItems: albumItems.length,
        art: results.filter((r) => r.artUrl).length,
      };
    } catch (e) {
      return { error: String(e?.message || e) };
    }
  });
  check("Auto library: search", auto.results > 3, JSON.stringify(auto));
  check("Auto library: browse playlist and album", auto.playlistItems > 0 && auto.albumItems > 0);

  await page.evaluate(() => window.Spicetify.Platform.History.push("/"));
  await page.waitForTimeout(3000);
  await page.screenshot({ path: join(out, "desktop-home.png") });
  writeFileSync(join(out, "desktop-errors.log"), errors.join("\n"));
  await browser.close();
}

// Existing install: the web player's service worker already cached the
// original (unpatched) bundles. Spicetify must still be applied.
{
  const { browser, page, errors } = await openPlayer({ viewport: { width: 1280, height: 800 } }, { preinstallServiceWorker: true });
  const info = await page.evaluate(async () => ({
    platformApis: Object.keys(window.Spicetify?.Platform || {}).length,
    patched: typeof window.Spicetify?.showNotification === "function",
    swApi: "serviceWorker" in navigator,
    precache: (await caches.keys()).filter((k) => /workbox|precache/.test(k)).length,
  }));
  check("Existing service worker: Spicetify still applied", info.platformApis > 20 && info.patched, JSON.stringify(info));
  check("Existing service worker hidden from the page", !info.swApi);
  // Leave and come back (as with the login page): Spicetify must still apply.
  await page.goto("https://www.spotify.com/", { waitUntil: "domcontentloaded" }).catch(() => {});
  await page.goto("https://open.spotify.com/", { waitUntil: "domcontentloaded" });
  await waitForStablePage(page);
  const again = await page.evaluate(() => ({
    platformApis: Object.keys(window.Spicetify?.Platform || {}).length,
    patched: typeof window.Spicetify?.showNotification === "function",
  }));
  check("Spicetify applied after leaving and returning", again.platformApis > 20 && again.patched, JSON.stringify(again));
  writeFileSync(join(out, "sw-errors.log"), errors.join("\n"));
  await browser.close();
}

// Phone viewport with the mobile layout.
{
  const { browser, page, errors } = await openPlayer({ ...devices["Pixel 7"], userAgent: DESKTOP_UA }, { fakeNative: true });
  // Spotify reloads the page once on the first visit; wait for the final render.
  const toggle = await page
    .waitForFunction(
      () => document.querySelector("#spicetify-android-drawer-toggle") && document.querySelectorAll("#main-view img").length > 3,
      null,
      { timeout: 90_000 },
    )
    .then(() => page.$("#spicetify-android-drawer-toggle"))
    .catch(() => null);
  await page.waitForTimeout(2000);
  const mobile = await page.evaluate(() => document.documentElement.classList.contains("spicetify-android-mobile"));
  check("Mobile layout active on phone", mobile);
  await page.screenshot({ path: join(out, "phone-home.png") });
  if (toggle) {
    await toggle.click();
    await page.waitForTimeout(800);
    await page.screenshot({ path: join(out, "phone-drawer.png") });
    const drawerApps = await page.evaluate(() =>
      [...document.querySelectorAll("#spicetify-android-apps .sa-drawer-app")].map((b) => {
        const r = b.getBoundingClientRect();
        return { label: b.textContent.trim(), visible: r.width > 0 && r.x >= 0 && r.x < innerWidth };
      }),
    );
    check(
      "Phone: apps and Spicetify listed in the drawer",
      drawerApps.length >= 2 && drawerApps.every((a) => a.visible) && drawerApps.some((a) => a.label === "Spicetify"),
      JSON.stringify(drawerApps.map((a) => a.label)),
    );
    const lyrics = await page.$('#spicetify-android-apps .sa-drawer-app:has-text("Lyrics")');
    if (lyrics) {
      await lyrics.click();
      await page.waitForTimeout(1500);
      check(
        "Phone: drawer app link navigates and closes the drawer",
        await page.evaluate(() => location.pathname.startsWith("/lyrics-plus") && !document.body.classList.contains("spicetify-android-drawer-open")),
      );
      await page.evaluate(() => window.Spicetify.Platform.History.push("/"));
      await page.waitForTimeout(1000);
    }
  }
  // Marketplace cards: the install button must be visible and tappable on touch
  // screens (real cards need the GitHub API, so a card with the same markup is used).
  const install = await page.evaluate(() => {
    const card = (html) => {
      const el = document.createElement("div");
      el.style.cssText = "position:fixed;left:20px;top:200px;width:200px;z-index:99999";
      el.innerHTML = html;
      // Inside #main: the page does not render stray children of <body>.
      (document.querySelector("#main") || document.body).appendChild(el);
      return el;
    };
    const grid = card(
      '<div class="marketplace-grid" style="display:block"><div class="main-card-card" style="width:180px;height:240px;position:relative"><div class="main-card-PlayButtonContainer"><button class="marketplace-installButton" style="width:40px;height:40px">+</button></div></div></div>',
    );
    const button = grid.querySelector("button");
    let clicked = false;
    button.addEventListener("click", () => (clicked = true));
    const style = getComputedStyle(grid.querySelector(".main-card-PlayButtonContainer"));
    const result = { display: style.display, opacity: style.opacity, pointerEvents: style.pointerEvents };
    const rect = button.getBoundingClientRect();
    result.width = rect.width;
    document.elementFromPoint(rect.x + rect.width / 2, rect.y + rect.height / 2)?.click();
    result.clicked = clicked;
    grid.remove();
    // Control: Spotify's own card play buttons stay hidden on touch screens.
    const plain = card('<div class="main-card-card"><div class="main-card-PlayButtonContainer"><button>x</button></div></div>');
    result.plainCardDisplay = getComputedStyle(plain.querySelector(".main-card-PlayButtonContainer")).display;
    plain.remove();
    return result;
  });
  check(
    "Phone: Marketplace install button visible and tappable",
    install.display !== "none" && install.opacity === "1" && install.width > 0 && install.clicked && install.plainCardDisplay === "none",
    JSON.stringify(install),
  );
  const topBarLinks = await page.evaluate(
    () =>
      [...document.querySelectorAll("#global-nav-bar .custom-navlink, #spicetify-android-manager")].filter((e) => e.getBoundingClientRect().width > 0)
        .length,
  );
  check("Phone: top bar without app links", topBarLinks === 0, `${topBarLinks} visible`);
  check("Mobile drawer toggle present", !!toggle);
  if (toggle) {
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - innerWidth);
    check("No horizontal overflow on phone", overflow <= 1, `${overflow}px`);
  }
  writeFileSync(join(out, "phone-errors.log"), errors.join("\n"));
  await browser.close();
}

const report = await patchReport();
writeFileSync(join(out, "patch-report.json"), report);
stop();
const failed = results.filter((r) => !r.ok);
console.log(`\n${results.length - failed.length}/${results.length} checks passed. Screenshots in ${out}`);
process.exit(failed.length ? 1 : 0);
