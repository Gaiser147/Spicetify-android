// Ad-hoc debugging: opens the patched web player and prints the result of an
// expression after a delay.  node android/e2e/debug-eval.mjs "<js expr>" [waitMs]
import { join } from "node:path";

import { DESKTOP_UA, devices, repo, startHarness } from "./harness.mjs";

const expr = process.argv[2] || "Object.keys(window.Spicetify)";
const wait = Number(process.argv[3] || 20000);
const h = await startHarness({ out: join(repo, "build/e2e-debug"), port: 8788 });
const { browser, page, errors } = await h.openPlayer(
  process.env.PHONE ? { ...devices["Pixel 7"], userAgent: DESKTOP_UA } : { viewport: { width: 1280, height: 800 } },
);
await page.waitForTimeout(wait);
console.log(JSON.stringify(await page.evaluate(`(async()=>{ try { return await (${expr}); } catch(e) { return "ERR " + e.stack } })()`), null, 1));
console.log("---- errors ----\n" + errors.filter((e) => !/console\.debug|Google Analytics|cookielaw|requestStorageAccess/.test(e)).join("\n"));
if (process.env.SHOT) await page.screenshot({ path: process.env.SHOT });
await browser.close();
h.stop();
process.exit(0);
