// Spicetify Android: one-time cleanup of stored web player state, run before
// any of the page's scripts (normal and safe mode).
//
// 1: Up to 0.1.0, the experimental features helper wrote remote config values
//    into the property defaults (spicetify-exp-features) and pinned all of
//    them as overrides (<user>:remote-config-overrides). The changed defaults
//    break the remote config request (404, property set not found) and the
//    pinned overrides keep stale values even in safe mode; playback did not
//    start. Both are removed once.
(function spicetifyAndroidMigrate() {
  "use strict";
  const KEY = "spicetify-android:migration";
  const VERSION = 1;
  try {
    const done = Number(localStorage.getItem(KEY)) || 0;
    if (done >= VERSION) return;
    const removed = [];
    for (let i = localStorage.length - 1; i >= 0; i--) {
      const key = localStorage.key(i);
      if (key === "spicetify-exp-features" || key.endsWith(":remote-config-overrides")) {
        localStorage.removeItem(key);
        removed.push(key);
      }
    }
    localStorage.setItem(KEY, String(VERSION));
    if (removed.length) window.SpicetifyAndroidDiagnostics?.log(`migration ${VERSION}: removed ${removed.join(", ")}`);
  } catch {}
})();
