// Spicetify Android bridge.
// Connects the web player to the native app: playback state for the media
// notification / lock screen / Bluetooth controls, back navigation and the
// native Spicetify manager. `window.SpicetifyAndroid` is a @JavascriptInterface
// injected by the app; without it (e.g. in a desktop browser) this is a no-op
// apart from exposing `window.SpicetifyAndroidBridge`.
(function spicetifyAndroidBridge() {
  "use strict";
  if (window.SpicetifyAndroidBridge) return;

  const native = window.SpicetifyAndroid || null;
  const send = (method, ...args) => {
    try {
      if (native && typeof native[method] === "function") native[method](...args);
    } catch (e) {
      console.warn("[spicetify-android] native call failed", method, e);
    }
  };

  const imageUrl = (uri) => {
    if (!uri) return "";
    if (uri.startsWith("spotify:image:")) return `https://i.scdn.co/image/${uri.slice("spotify:image:".length)}`;
    if (uri.startsWith("https://")) return uri;
    return "";
  };

  const snapshot = () => {
    const P = window.Spicetify?.Player;
    const state = P?.origin?._state;
    const item = state?.item;
    if (!item) return { hasTrack: false };
    const md = item.metadata || {};
    const artists = Array.isArray(item.artists) ? item.artists.map((a) => a.name).join(", ") : md.artist_name || "";
    let position = 0;
    try {
      position = Math.max(0, Math.round(P.getProgress()));
    } catch {}
    return {
      hasTrack: true,
      uri: item.uri || "",
      title: item.name || md.title || "",
      artist: artists,
      album: item.album?.name || md.album_title || "",
      artUrl: imageUrl(md.image_xlarge_url || md.image_large_url || md.image_url || item.images?.[0]?.url),
      duration: Math.round(state.duration || Number(md.duration) || 0),
      position,
      isPaused: !!state.isPaused,
      shuffle: !!state.shuffle,
      repeat: state.repeat || 0,
      liked: md["collection.in_collection"] === "true",
      hasNext: state.restrictions?.canSkipNext !== false,
      hasPrevious: state.restrictions?.canSkipPrevious !== false,
      timestamp: Date.now(),
    };
  };

  let lastSent = "";
  const pushState = (force) => {
    const json = JSON.stringify(snapshot());
    // Position changes every tick; only resend unchanged tracks every 5 s.
    const key = json.replace(/"(position|timestamp)":\d+,?/g, "");
    if (!force && key === lastSent) return;
    lastSent = key;
    send("onPlaybackState", json);
  };

  const commands = {
    play: () => Spicetify.Player.play(),
    pause: () => Spicetify.Player.pause(),
    togglePlay: () => Spicetify.Player.togglePlay(),
    next: () => Spicetify.Player.next(),
    previous: () => Spicetify.Player.back(),
    seek: (ms) => Spicetify.Player.seek(Number(ms)),
    toggleShuffle: () => Spicetify.Player.toggleShuffle(),
    toggleRepeat: () => Spicetify.Player.toggleRepeat(),
    toggleHeart: () => Spicetify.Player.toggleHeart(),
    stop: () => Spicetify.Player.pause(),
    navigate: (target) => {
      if (!target) return;
      let path = target;
      if (target.startsWith("spotify:")) {
        const uri = Spicetify.URI?.from?.(target);
        path = uri?.toURLPath?.(true) ?? `/${target.split(":").slice(1).join("/")}`;
      } else if (/^https?:\/\/open\.spotify\.com/.test(target)) {
        path = new URL(target).pathname.replace(/^\/intl-[a-z]{2}(-[a-z]{2})?/i, "");
      }
      Spicetify.Platform.History.push(path);
    },
  };

  window.SpicetifyAndroidBridge = {
    /** Called by the app: `SpicetifyAndroidBridge.command("next")`. */
    command(name, arg) {
      const fn = commands[name];
      if (!fn) return false;
      try {
        fn(arg);
      } catch (e) {
        console.warn("[spicetify-android] command failed", name, e);
        return false;
      }
      setTimeout(() => pushState(true), 300);
      return true;
    },
    /** Returns true when the web app handled the back press. */
    goBack() {
      if (window.SpicetifyAndroidLayout?.closeOverlays?.()) return true;
      // Close open overlays (menus, dialogs, full screen views) first.
      const overlay = document.querySelector("#context-menu, [role=dialog], .GenericModal, .main-contextMenu-menu, .spicetify-android-drawer-open");
      if (overlay) {
        document.dispatchEvent(new KeyboardEvent("keydown", { key: "Escape", bubbles: true }));
        document.body.classList.remove("spicetify-android-drawer-open");
        return true;
      }
      const H = Spicetify?.Platform?.History;
      if (H && (H.entries?.length > 1 || H.index > 0 || window.history.length > 1) && H.location?.pathname !== "/") {
        H.goBack();
        return true;
      }
      return false;
    },
    state: snapshot,
  };

  const waitFor = (check, cb) => {
    const t = setInterval(() => {
      if (check()) {
        clearInterval(t);
        cb();
      }
    }, 200);
  };

  waitFor(
    () => window.Spicetify?.Player?.addEventListener && Spicetify.Player.origin?._state,
    () => {
      Spicetify.Player.addEventListener("songchange", () => pushState(true));
      Spicetify.Player.addEventListener("onplaypause", () => pushState(true));
      setInterval(() => pushState(false), 1000);
      setInterval(() => pushState(true), 5000);
      pushState(true);
    },
  );

  waitFor(
    () => window.Spicetify?.Platform?.History?.listen,
    () => {
      const report = (location) => send("onNavigation", location?.pathname || "/");
      Spicetify.Platform.History.listen(report);
      report(Spicetify.Platform.History.location);
    },
  );

  // Native manager entry in the top bar and the profile menu.
  waitFor(
    () => window.Spicetify?.Topbar?.Button && Spicetify.Menu?.Item,
    () => {
      if (!native) return;
      const icon =
        '<svg role="img" height="16" width="16" viewBox="0 0 16 16" fill="currentColor"><path d="M8 0a1.5 1.5 0 0 1 1.5 1.5v.3a6 6 0 0 1 1.7.7l.2-.2a1.5 1.5 0 0 1 2.1 2.1l-.2.2c.3.5.6 1.1.7 1.7h.3a1.5 1.5 0 0 1 0 3h-.3a6 6 0 0 1-.7 1.7l.2.2a1.5 1.5 0 0 1-2.1 2.1l-.2-.2a6 6 0 0 1-1.7.7v.3a1.5 1.5 0 0 1-3 0v-.3a6 6 0 0 1-1.7-.7l-.2.2a1.5 1.5 0 0 1-2.1-2.1l.2-.2a6 6 0 0 1-.7-1.7h-.3a1.5 1.5 0 0 1 0-3h.3c.1-.6.4-1.2.7-1.7l-.2-.2a1.5 1.5 0 0 1 2.1-2.1l.2.2c.5-.3 1.1-.6 1.7-.7v-.3A1.5 1.5 0 0 1 8 0zm0 5a3 3 0 1 0 0 6 3 3 0 0 0 0-6z"/></svg>';
      try {
        new Spicetify.Topbar.Button("Spicetify", icon, () => send("openManager"), false, true);
      } catch (e) {
        console.warn("[spicetify-android] topbar button failed", e);
      }
      try {
        new Spicetify.Menu.Item("Spicetify (Android)", false, () => send("openManager")).register();
      } catch {}
    },
  );

  send("onBridgeReady", JSON.stringify({ version: window.Spicetify?.Config?.version || "" }));
})();
