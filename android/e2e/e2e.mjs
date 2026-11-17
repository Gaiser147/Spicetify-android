// End-to-end check of the Android port's web layer in desktop Chromium.
// See harness.mjs for how the WebView setup is reproduced. Playback is not
// tested (Chromium here has no Widevine).
//
//   node android/e2e/e2e.mjs [--marketplace <dir>] [--out <dir>]
import { mkdirSync, writeFileSync } from "node:fs";
import { join, resolve } from "node:path";

import { DESKTOP_UA, arg, devices, repo, startHarness } from "./harness.mjs";

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
  const { browser, page, errors, stats } = await openPlayer({ viewport: { width: 1280, height: 800 } });
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

// Phone viewport with the mobile layout.
{
  const { browser, page, errors } = await openPlayer({ ...devices["Pixel 7"], userAgent: DESKTOP_UA });
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
    await toggle.click();
  }
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
