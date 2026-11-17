// Test harness that reproduces the Android WebView setup in desktop Chromium:
// document start script, patched web player bundles, same-origin Spicetify
// files and custom app chunks, all served by the Go devhost.
import { spawn } from "node:child_process";
import { cpSync, existsSync, mkdirSync, rmSync, symlinkSync } from "node:fs";
import { createRequire } from "node:module";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const require = createRequire(import.meta.url);
export const { chromium, devices } = require("playwright");

export const repo = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
export const DESKTOP_UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

/**
 * Stages the asset layout, starts the devhost and returns helpers.
 * @param {{out: string, marketplace?: string, apps?: string[], extensions?: string[], port?: number}} options
 */
export async function startHarness({ out, marketplace = "", apps: baseApps = ["lyrics-plus", "new-releases"], extensions = [], port = 8787 }) {
  const hostUrl = `http://127.0.0.1:${port}`;
  if (!existsSync(join(repo, "jsHelper/spicetifyWrapper.js"))) throw new Error("run `pnpm build:wrapper` first");

  // Same layout the Android app bundles as assets/spicetify.
  const stage = join(out, "assets");
  rmSync(stage, { recursive: true, force: true });
  mkdirSync(stage, { recursive: true });
  for (const [dst, src] of [
    ["jsHelper", "jsHelper"],
    ["Extensions", "Extensions"],
    ["Themes", "Themes"],
    ["android", "android/web"],
  ]) {
    symlinkSync(join(repo, src), join(stage, dst));
  }
  cpSync(join(repo, "CustomApps"), join(stage, "CustomApps"), { recursive: true });
  const apps = [...baseApps];
  if (marketplace && existsSync(marketplace)) {
    cpSync(marketplace, join(stage, "CustomApps/marketplace"), { recursive: true });
    apps.push("marketplace");
  }

  const devhostBin = join(out, "devhost");
  await new Promise((res, rej) =>
    spawn("go", ["build", "-o", devhostBin, "./src/patcher/cmd/devhost"], { cwd: repo, stdio: "inherit" }).on("exit", (c) =>
      c === 0 ? res() : rej(new Error("go build failed")),
    ),
  );
  const cssMap = join(repo, "css-map.json");
  const devhost = spawn(
    devhostBin,
    [
      "-root",
      stage,
      "-addr",
      `127.0.0.1:${port}`,
      "-apps",
      apps.join(","),
      "-extensions",
      extensions.join(","),
      ...(existsSync(cssMap) ? ["-css-map", cssMap] : []),
    ],
    { cwd: repo, stdio: ["ignore", "ignore", "inherit"] },
  );
  const stop = () => devhost.kill("SIGTERM");
  process.on("exit", stop);

  for (let i = 0; ; i++) {
    try {
      // oxlint-disable-next-line no-await-in-loop -- polling until the server is up
      await fetch(`${hostUrl}/report`);
      break;
    } catch {
      if (i > 400) throw new Error("devhost did not start");
      // oxlint-disable-next-line no-await-in-loop -- polling until the server is up
      await new Promise((r) => setTimeout(r, 100));
    }
  }
  const boot = await (await fetch(`${hostUrl}/boot.js`)).text();

  /** Opens open.spotify.com with Spicetify injected. */
  async function openPlayer(contextOptions = {}) {
    const browser = await chromium.launch();
    const context = await browser.newContext({ userAgent: DESKTOP_UA, locale: "en-US", ...contextOptions });
    const errors = [];
    const page = await context.newPage();
    page.on("pageerror", (e) => errors.push(`[pageerror] ${e.stack || e}`));
    page.on("response", (r) => {
      if (r.status() >= 400) errors.push(`[http ${r.status()}] ${r.url()}`);
    });
    page.on("console", (m) => {
      if (m.type() === "error" || m.type() === "warning" || /spicetify/i.test(m.text())) errors.push(`[console.${m.type()}] ${m.text()}`);
    });

    await context.addInitScript({ content: boot });
    // Requests can still be in flight when the browser closes; their errors
    // must not crash the run.
    const safe = (handler) => async (route) => {
      try {
        await handler(route);
      } catch (e) {
        if (!/closed|Target page|browser context/i.test(String(e?.message))) errors.push(`[route] ${e?.message || e}`);
      }
    };
    await context.route(
      "https://open.spotifycdn.com/cdn/build/web-player/**",
      safe(async (route) => {
        const url = route.request().url();
        const m = url.match(/\/spicetify-routes-(.+?)(?:\.[^./]+)?\.(js|css)(?:\?|$)/);
        if (m) {
          const r = await fetch(`${hostUrl}/custom-app/${m[1]}.${m[2]}`);
          return route.fulfill({
            status: r.status,
            body: await r.text(),
            headers: { "content-type": m[2] === "js" ? "text/javascript" : "text/css", "access-control-allow-origin": "*" },
          });
        }
        if (!/\.(js|css)(\?|$)/.test(url)) return route.continue();
        const response = await route.fetch();
        const body = await response.text();
        const patched = await (await fetch(`${hostUrl}/patch?url=${encodeURIComponent(url)}`, { method: "POST", body })).text();
        const headers = { ...response.headers() };
        delete headers["content-length"];
        delete headers["content-encoding"];
        return route.fulfill({ status: response.status(), headers, body: patched });
      }),
    );
    await context.route(
      "https://open.spotify.com/__spicetify/**",
      safe(async (route) => {
        const path = new URL(route.request().url()).pathname;
        const r = await fetch(`${hostUrl}${path}`);
        return route.fulfill({
          status: r.status,
          body: Buffer.from(await r.arrayBuffer()),
          headers: { "content-type": r.headers.get("content-type") || "application/octet-stream" },
        });
      }),
    );

    // Stop routing before closing so in-flight handlers end cleanly.
    const close = browser.close.bind(browser);
    browser.close = async () => {
      await context.unrouteAll({ behavior: "ignoreErrors" }).catch(() => {});
      await close();
    };

    await page.goto("https://open.spotify.com/", { waitUntil: "domcontentloaded", timeout: 90_000 });
    await waitForStablePage(page);
    return { browser, context, page, errors };
  }

  const report = async () => (await fetch(`${hostUrl}/report`)).text();
  return { openPlayer, stop, report, apps, hostUrl };
}

/**
 * Spotify reloads the page once on a first visit. Waits until Spicetify is
 * ready and the document survived a few seconds without another navigation.
 */
export async function waitForStablePage(page, timeout = 120_000) {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    try {
      // oxlint-disable-next-line no-await-in-loop -- sequential by design
      await page.waitForFunction(() => window.Spicetify?.Events?.webpackLoaded && window.Spicetify?.Platform?.History, null, {
        timeout: 60_000,
      });
      const marker = Math.random();
      // oxlint-disable-next-line no-await-in-loop -- sequential by design
      await page.evaluate((m) => {
        window.__e2eMarker = m;
      }, marker);
      // oxlint-disable-next-line no-await-in-loop -- sequential by design
      await page.waitForTimeout(4000);
      // oxlint-disable-next-line no-await-in-loop -- sequential by design
      if ((await page.evaluate(() => window.__e2eMarker)) === marker) return;
    } catch {
      // navigation in progress; retry
    }
  }
  throw new Error("page did not become stable");
}

export const arg = (name, def) => {
  const i = process.argv.indexOf(name);
  return i > 0 ? process.argv[i + 1] : def;
};
