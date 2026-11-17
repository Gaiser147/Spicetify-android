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
  // The app injects `SpicetifyAndroid` with WebViewCompat.addWebMessageListener
  // (postMessage, restricted to open.spotify.com) or, on old WebViews, as a
  // @JavascriptInterface with one method per message.
  const send = (method, ...args) => {
    try {
      if (!native) return;
      if (typeof native[method] === "function") native[method](...args);
      else if (typeof native.postMessage === "function") native.postMessage(JSON.stringify({ method, args }));
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

  // --- Media library for Android Auto ------------------------------------
  // Uses the web player's own APIs (Spicetify.Platform, GraphQL); the Web API
  // is only a last resort because it rate-limits web player tokens.
  const api = async (path) => {
    const url = `https://api.spotify.com/v1${path}`;
    const token = Spicetify.Platform?.AuthorizationAPI?.getState?.()?.token?.accessToken;
    const res = await fetch(url, { headers: token ? { Authorization: `Bearer ${token}` } : {} });
    if (!res.ok) throw new Error(`HTTP ${res.status} for ${path}`);
    return res.json();
  };
  const gql = async (name, variables) => {
    const def = Spicetify.GraphQL?.Definitions?.[name];
    if (!def) throw new Error(`GraphQL ${name} unavailable`);
    const res = await Spicetify.GraphQL.Request(def, variables);
    if (res?.errors?.length) throw new Error(res.errors[0].message);
    return res.data;
  };

  const sourcesOf = (o) =>
    o?.images?.items?.[0]?.sources ??
    o?.coverArt?.sources ??
    o?.albumOfTrack?.coverArt?.sources ??
    o?.visuals?.avatarImage?.sources ??
    o?.album?.images ??
    o?.album?.coverArt?.sources ??
    (Array.isArray(o?.images) ? o.images : null);
  const imageOf = (o) => {
    const list = (sourcesOf(o) || []).filter((i) => i?.url);
    if (!list.length) return "";
    const fit = list.filter((i) => !i.width || i.width <= 640).sort((x, y) => (y.width || 0) - (x.width || 0));
    return (fit[0] || list[0]).url;
  };
  const artistsOf = (o) => {
    const list = Array.isArray(o?.artists) ? o.artists : (o?.artists?.items ?? []);
    const text = list
      .map((a) => a?.name ?? a?.profile?.name)
      .filter(Boolean)
      .join(", ");
    return text || o?.album?.artist?.name || o?.owner?.name || o?.ownerV2?.data?.name || "";
  };
  const typeOf = (o) => String(o?.type || o?.__typename || o?.uri?.split(":")[1] || "").toLowerCase();
  const playId = (context, track) => `play|${context || ""}|${track || ""}`;

  /** Maps Platform / GraphQL / Web API objects to library items. */
  const toItem = (o, context, fallbackArt) => {
    if (!o?.uri || (!o?.name && !o?.profile?.name)) return null;
    const title = o.name ?? o.profile.name;
    const type = typeOf(o);
    const art = imageOf(o) || fallbackArt || "";
    if (type === "track" || type === "episode") {
      return { id: playId(context, o.uri), title, subtitle: artistsOf(o), artUrl: art, playable: true, browsable: false };
    }
    if (type === "album" || type === "playlist") {
      return { id: `${type}|${o.uri}`, title, subtitle: artistsOf(o), artUrl: art, playable: true, browsable: true };
    }
    if (type === "artist") return { id: playId(o.uri, ""), title, subtitle: "", artUrl: art, playable: true, browsable: false };
    return null;
  };
  const compact = (list) => list.filter(Boolean);

  let username = null;
  const getUsername = async () =>
    (username ??=
      Spicetify.Platform?.UserAPI?._product_state?.cache?.get?.("username") ??
      (await Spicetify.Platform?.RootlistAPI?.getUsername?.()) ??
      (await api("/me")).id);
  const likedContext = async () => `spotify:user:${await getUsername()}:collection`;

  const libraryItems = async () => {
    const res = await Spicetify.Platform.LibraryAPI.getContents({ filters: [], sortOrder: ["0"], textFilter: "", offset: 0, limit: 500 });
    return res?.items ?? [];
  };

  const library = {
    async browse(nodeId) {
      const [kind, uri] = nodeId.split("|");
      switch (kind) {
        case "root":
          return [
            { id: "recent", title: "Recently played", browsable: true },
            { id: "liked", title: "Liked Songs", browsable: true, playable: true },
            { id: "playlists", title: "Playlists", browsable: true },
            { id: "albums", title: "Albums", browsable: true },
          ];
        case "recent": {
          let items = [];
          try {
            const res = await Spicetify.Platform.RecentsAPI.getContents({ limit: 50 });
            items = res?.items ?? res ?? [];
          } catch {}
          if (!items.length) items = ((await api("/me/player/recently-played?limit=40")).items || []).map((i) => i.track);
          const seen = new Set();
          return compact(items.filter((i) => i?.uri && !seen.has(i.uri) && seen.add(i.uri)).map((i) => toItem(i)));
        }
        case "liked": {
          const context = await likedContext();
          const res = await Spicetify.Platform.LibraryAPI.getTracks({ limit: 100, offset: 0 });
          return compact((res?.items ?? []).map((t) => toItem(t, context)));
        }
        case "playlists": {
          const flatten = (rows) => rows.flatMap((r) => (r.type === "folder" ? flatten(r.items ?? []) : [r]));
          let rows = [];
          try {
            rows = flatten((await Spicetify.Platform.RootlistAPI.getContents())?.items ?? []);
          } catch {
            rows = await libraryItems();
          }
          return compact(rows.filter((r) => typeOf(r) === "playlist").map((p) => toItem(p)));
        }
        case "albums":
          return compact((await libraryItems()).filter((r) => typeOf(r) === "album").map((a) => toItem(a)));
        case "playlist": {
          const res = await Spicetify.Platform.PlaylistAPI.getContents(uri, { limit: 200 });
          return compact((res?.items ?? []).filter((t) => t.isPlayable !== false).map((t) => toItem(t, uri)));
        }
        case "album": {
          const data = await gql("queryAlbumTracks", { uri, offset: 0, limit: 100 });
          let art = "";
          try {
            art = imageOf((await gql("getAlbum", { uri, locale: "", offset: 0, limit: 1 }))?.albumUnion);
          } catch {}
          return compact((data?.albumUnion?.tracksV2?.items ?? []).map((i) => toItem({ ...i.track, type: "track" }, uri, art)));
        }
        default:
          return [];
      }
    },
    async search(query) {
      const data = await gql("searchModalResults", {
        searchTerm: query,
        offset: 0,
        limit: 20,
        numberOfTopResults: 20,
        includeAuthors: false,
      });
      const items = (data?.searchV2?.topResultsV2?.itemsV2 ?? []).map((i) => i?.item?.data).filter(Boolean);
      return compact(items.map((o) => toItem(o, typeOf(o) === "track" ? o.albumOfTrack?.uri : undefined)));
    },
  };

  /** Plays a library item id ("play|context|track", "playlist|uri", "liked", ...). */
  const playItem = async (itemId) => {
    const [kind, a, b] = itemId.split("|");
    if (kind === "play") {
      const context = a || b;
      const options = a && b ? { skipTo: { uri: b } } : {};
      return Spicetify.Player.playUri(context, {}, options);
    }
    if (kind === "liked") return Spicetify.Player.playUri(await likedContext());
    if (a) return Spicetify.Player.playUri(a);
  };

  const playSearch = async (query) => {
    const results = await library.search(query);
    const first = results.find((r) => r.playable);
    if (first) await playItem(first.id);
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
    playItem: (itemId) => playItem(itemId),
    playSearch: (query) => playSearch(query),
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
    /**
     * Asynchronous request from the app; the result is sent back with
     * onResult(id, json) as {ok, items} or {ok: false, error}.
     */
    request(id, method, arg) {
      const run = method === "search" ? library.search(arg) : library.browse(arg);
      run
        .then((items) => send("onResult", id, JSON.stringify({ ok: true, items })))
        .catch((e) => send("onResult", id, JSON.stringify({ ok: false, error: String(e?.message || e) })));
      return true;
    },
    /** Called by the app: `SpicetifyAndroidBridge.command("next")`. */
    command(name, arg) {
      const fn = commands[name];
      if (!fn) return false;
      try {
        Promise.resolve(fn(arg)).catch((e) => console.warn("[spicetify-android] command failed", name, e));
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
    library,
    /** Opens the native Spicetify manager (no-op outside the app). */
    openManager: () => send("openManager"),
    hasNative: !!native,
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
  const managerIcon =
    '<svg role="img" height="16" width="16" viewBox="0 0 16 16" fill="currentColor" aria-hidden="true"><path d="M8 0a1.5 1.5 0 0 1 1.5 1.5v.3a6 6 0 0 1 1.7.7l.2-.2a1.5 1.5 0 0 1 2.1 2.1l-.2.2c.3.5.6 1.1.7 1.7h.3a1.5 1.5 0 0 1 0 3h-.3a6 6 0 0 1-.7 1.7l.2.2a1.5 1.5 0 0 1-2.1 2.1l-.2-.2a6 6 0 0 1-1.7.7v.3a1.5 1.5 0 0 1-3 0v-.3a6 6 0 0 1-1.7-.7l-.2.2a1.5 1.5 0 0 1-2.1-2.1l.2-.2a6 6 0 0 1-.7-1.7h-.3a1.5 1.5 0 0 1 0-3h.3c.1-.6.4-1.2.7-1.7l-.2-.2a1.5 1.5 0 0 1 2.1-2.1l.2.2c.5-.3 1.1-.6 1.7-.7v-.3A1.5 1.5 0 0 1 8 0zm0 5a3 3 0 1 0 0 6 3 3 0 0 0 0-6z"/></svg>';
  waitFor(
    () => window.Spicetify?.Topbar?.Button && Spicetify.Menu?.Item,
    () => {
      if (!native) return;
      try {
        new Spicetify.Topbar.Button("Spicetify", managerIcon, () => send("openManager"), false, true);
      } catch (e) {
        console.warn("[spicetify-android] topbar button failed", e);
      }
      try {
        new Spicetify.Menu.Item("Spicetify (Android)", false, () => send("openManager")).register();
      } catch {}
    },
  );

  // Spicetify's top bar buttons need the desktop client's history/action
  // buttons, which the web player does not have. Add an own button to the
  // global nav bar unless a Spicetify top bar button got mounted.
  const ensureManagerButton = () => {
    if (!native) return;
    const nav = document.getElementById("global-nav-bar");
    if (!nav || document.querySelector(".spicetify-topbar-button") || document.getElementById("spicetify-android-manager")) return;
    const right = nav.lastElementChild;
    if (!right) return;
    const button = document.createElement("button");
    button.id = "spicetify-android-manager";
    button.type = "button";
    button.title = "Spicetify";
    button.setAttribute("aria-label", "Spicetify");
    button.innerHTML = managerIcon;
    button.addEventListener("click", () => send("openManager"));
    right.prepend(button);
  };
  setInterval(ensureManagerButton, 2000);

  // Tells the app the player is up (it shows a recovery bar if this never comes).
  waitFor(
    () => window.Spicetify?.Platform?.History && document.querySelector("#main-view"),
    () =>
      send(
        "onBridgeReady",
        JSON.stringify({
          version: window.Spicetify?.Config?.version || "",
          platformApis: Object.keys(window.Spicetify?.Platform || {}).length,
          react: !!window.Spicetify?.React,
        }),
      ),
  );
})();
