// Spicetify Android: playback diagnostics.
//
// Runs as part of the document start script, also in safe mode (where it is
// the only script the app adds), and writes to the app's diagnostics log
// (Settings → Maintenance → Diagnostics, tag "player"). It only observes
// browser APIs (fetch, XHR, WebSocket, EME, media elements); the web player's
// own objects are not touched. URLs are logged without query strings, which
// can carry access tokens.
(function spicetifyAndroidDiagnostics() {
  "use strict";
  if (window.SpicetifyAndroidDiagnostics) return;

  const native = window.SpicetifyAndroid || null;
  let budget = 400;
  const log = (message, tag = "player") => {
    if (budget-- <= 0) return;
    const text = typeof message === "string" ? message : JSON.stringify(message);
    // The app keeps at most 1000 characters per line.
    for (let i = 0; i < Math.min(text.length, 4500); i += 900) {
      const part = (i ? "… " : "") + text.slice(i, i + 900);
      try {
        if (native && typeof native.log === "function") native.log(tag, part);
        else if (native && typeof native.postMessage === "function") native.postMessage(JSON.stringify({ method: "log", args: [tag, part] }));
        else console.debug(`[spicetify-android:${tag}]`, part);
      } catch {}
    }
  };
  const errText = (e) => `${e?.name || "Error"}: ${e?.message || e}`;
  const guard = (name, fn) => {
    try {
      fn();
    } catch (e) {
      console.warn(`[spicetify-android] diagnostics: ${name} unavailable`, e);
    }
  };

  // Requests are always logged when they fail; while "watching" (a few
  // seconds after a play tap or a playback test) every request is logged.
  let watchUntil = 0;
  let watchMessages = 0;
  const watch = (ms) => {
    watchUntil = Date.now() + ms;
    watchMessages = 0;
  };
  const watching = () => Date.now() < watchUntil;
  const SPOTIFY_HOST = /(^|\.)(spotify\.com|spotifycdn\.com|scdn\.co)$/;
  const parse = (url) => {
    try {
      const u = new URL(String(url), location.href);
      return SPOTIFY_HOST.test(u.hostname) ? u : null;
    } catch {
      return null;
    }
  };
  const short = (u) => `${u.host}${u.pathname}`.slice(0, 140);
  const net = (method, u, status, started) => {
    const ok = typeof status === "number" && status >= 200 && status < 400;
    if (ok && !watching()) return;
    log(`${method} ${short(u)} -> ${status} (${Math.round(performance.now() - started)} ms)`, "player-net");
  };

  guard("fetch", () => {
    const fetch = window.fetch;
    if (typeof fetch !== "function") return;
    window.fetch = function (input, init) {
      const result = fetch.apply(this, arguments);
      const u = parse(typeof input === "string" || input instanceof URL ? input : input?.url);
      if (!u) return result;
      const method = String(init?.method || input?.method || "GET").toUpperCase();
      const started = performance.now();
      result.then(
        (res) => {
          net(method, u, res.status, started);
          if (!res.ok && /remote-config-resolver/.test(u.pathname)) {
            res
              .clone()
              .text()
              .then((t) => log(`remote config ${res.status}: ${t.slice(0, 300)}`, "player-net"))
              .catch(() => {});
          }
        },
        (e) => net(method, u, `failed (${errText(e)})`, started),
      );
      return result;
    };
  });

  guard("XMLHttpRequest", () => {
    const proto = XMLHttpRequest.prototype;
    // oxlint-disable-next-line typescript/unbound-method -- re-applied with the request as `this`
    const open = proto.open;
    // oxlint-disable-next-line typescript/unbound-method -- re-applied with the request as `this`
    const send = proto.send;
    const meta = new WeakMap();
    proto.open = function (method, url) {
      const u = parse(url);
      if (u) meta.set(this, { method: String(method).toUpperCase(), u });
      return open.apply(this, arguments);
    };
    proto.send = function () {
      const m = meta.get(this);
      if (m) {
        const started = performance.now();
        this.addEventListener("loadend", () => net(m.method, m.u, this.status || "failed", started), { once: true });
      }
      return send.apply(this, arguments);
    };
  });

  // The dealer WebSocket delivers Connect commands (play, pause) to the local
  // player. Message bodies are not logged, only their type and uri.
  guard("WebSocket", () => {
    const Native = window.WebSocket;
    if (typeof Native !== "function") return;
    class LoggedWebSocket extends Native {
      constructor(url, protocols) {
        super(url, protocols);
        const u = parse(url);
        if (!u) return;
        const name = short(u);
        log(`ws connecting ${name}`, "player-net");
        this.addEventListener("open", () => log(`ws open ${name}`, "player-net"));
        this.addEventListener("error", () => log(`ws error ${name}`, "player-net"));
        this.addEventListener("close", (e) => log(`ws closed ${name} code=${e.code} clean=${e.wasClean} ${e.reason || ""}`, "player-net"));
        this.addEventListener("message", (e) => {
          if (!watching() || watchMessages++ > 15) return;
          let what = typeof e.data === "string" ? "text" : "binary";
          try {
            const m = JSON.parse(e.data);
            what = [m.type, m.uri, m.message_ident, m.key].filter(Boolean).join(" ") || "json";
          } catch {}
          log(`ws message ${name}: ${what.slice(0, 160)}`, "player-net");
        });
      }
    }
    window.WebSocket = LoggedWebSocket;
  });

  // DRM (Widevine) and Media Source Extensions.
  guard("EME", () => {
    const rmksa = navigator.requestMediaKeySystemAccess?.bind(navigator);
    if (rmksa) {
      navigator.requestMediaKeySystemAccess = (keySystem, configs) => {
        const robustness = [
          ...new Set((configs || []).flatMap((c) => [...(c.videoCapabilities || []), ...(c.audioCapabilities || [])]).map((c) => c.robustness)),
        ]
          .filter(Boolean)
          .join(",");
        const result = rmksa(keySystem, configs);
        result.then(
          (access) => {
            const audio = access.getConfiguration?.()?.audioCapabilities?.map((c) => `${c.contentType} ${c.robustness}`) ?? [];
            log(`DRM ${keySystem} ok -> ${audio.join("; ") || "no audio config"}`);
          },
          (e) => {
            // Spotify probes several key systems; only Widevine matters on Android.
            if (/widevine/.test(keySystem)) log(`DRM ${keySystem} unavailable (${robustness || "default"}): ${errText(e)}`);
          },
        );
        return result;
      };
    }
    const wrapAsync = (proto, name, describe) => {
      const original = proto?.[name];
      if (typeof original !== "function") return;
      proto[name] = function (...args) {
        const result = original.apply(this, args);
        Promise.resolve(result).then(
          () => describe && log(`${describe(args)} ok`),
          (e) => log(`${describe ? describe(args) : name} failed: ${errText(e)}`),
        );
        return result;
      };
    };
    wrapAsync(window.MediaKeySystemAccess?.prototype, "createMediaKeys", () => "DRM createMediaKeys");
    wrapAsync(window.HTMLMediaElement?.prototype, "setMediaKeys", () => "DRM setMediaKeys");
    wrapAsync(window.MediaKeySession?.prototype, "generateRequest", (args) => `DRM licence request (${args[0]})`);
    wrapAsync(window.MediaKeySession?.prototype, "update", () => "DRM licence");

    const MS = window.MediaSource;
    if (MS) {
      const isTypeSupported = MS.isTypeSupported?.bind(MS);
      const reported = new Set();
      if (isTypeSupported) {
        MS.isTypeSupported = (type) => {
          const ok = isTypeSupported(type);
          if (!ok && !reported.has(type) && reported.size < 20) {
            reported.add(type);
            log(`MSE unsupported: ${type}`);
          }
          return ok;
        };
      }
      // oxlint-disable-next-line typescript/unbound-method -- re-applied with the media source as `this`
      const addSourceBuffer = MS.prototype.addSourceBuffer;
      MS.prototype.addSourceBuffer = function (type) {
        try {
          const buffer = addSourceBuffer.call(this, type);
          log(`MSE source buffer ${type}`);
          return buffer;
        } catch (e) {
          log(`MSE source buffer ${type} failed: ${errText(e)}`);
          throw e;
        }
      };
    }
  });

  // Media elements: Spotify's player keeps them outside the DOM.
  const media = new Set();
  guard("media", () => {
    const watched = new WeakSet();
    const track = (el) => {
      media.add(new WeakRef(el));
      if (watched.has(el)) return;
      watched.add(el);
      el.addEventListener("error", () => log(`media error ${el.error?.code ?? "?"}: ${el.error?.message || "no message"}`));
      el.addEventListener("playing", () => log(`media playing (${el.tagName.toLowerCase()})`));
    };
    // oxlint-disable-next-line typescript/unbound-method -- re-applied with the element as `this`
    const play = HTMLMediaElement.prototype.play;
    HTMLMediaElement.prototype.play = function (...args) {
      track(this);
      const result = play.apply(this, args);
      Promise.resolve(result).then(
        () => watching() && log(`media play() ok (ready=${this.readyState})`),
        (e) => log(`media play() rejected: ${errText(e)}`),
      );
      return result;
    };
  });

  window.addEventListener("unhandledrejection", (e) => {
    const text = errText(e.reason);
    if (watching() || /play|media|drm|licen|widevine|eme|device|connect/i.test(`${text} ${e.reason?.stack || ""}`)) log(`unhandled: ${text}`);
  });

  // --- State snapshots ----------------------------------------------------
  const pick = (obj, limit = 20) => {
    if (!obj || typeof obj !== "object") return obj ?? null;
    const out = {};
    for (const key of Object.keys(obj).slice(0, 60)) {
      const v = obj[key];
      if (v === null || ["string", "number", "boolean"].includes(typeof v)) out[key] = typeof v === "string" ? v.slice(0, 60) : v;
      else if (Array.isArray(v) && v.length && v.every((x) => typeof x === "string")) out[key] = v.slice(0, 5);
      if (Object.keys(out).length >= limit) break;
    }
    return out;
  };
  const platform = () => window.Spicetify?.Platform;
  const playerState = () => {
    try {
      return platform()?.PlayerAPI?.getState?.() ?? window.Spicetify?.Player?.origin?._state;
    } catch {
      return undefined;
    }
  };
  /** Short summary of player, Connect device and media elements. */
  const summary = () => {
    const state = playerState();
    const connect = platform()?.ConnectAPI?.state;
    const device = connect?.activeDevice;
    const elements = [...media].map((r) => r.deref()).filter(Boolean);
    return {
      track: state?.item?.uri || null,
      paused: state ? !!state.isPaused : null,
      buffering: state?.isBuffering ?? null,
      device: device ? `${device.name || "?"} (${device.type || "?"}${device.isLocal ? ", local" : ""})` : null,
      connect: connect?.connectionStatus ?? null,
      media: elements.map((m) => `${m.tagName.toLowerCase()} paused=${m.paused} ready=${m.readyState} t=${Math.round(m.currentTime)}`),
    };
  };
  /** Everything that may explain why playback does not start. */
  const details = async () => {
    const P = platform();
    const state = playerState();
    const restrictions = {};
    for (const [key, value] of Object.entries(state?.restrictions || {})) {
      if (value === false || (Array.isArray(value) && value.length)) restrictions[key] = value;
    }
    let product = null;
    for (const source of [P?.ProductStateAPI, P?.ProductStateAPI?.productStateApi, P?.UserAPI?._product_state]) {
      try {
        // oxlint-disable-next-line no-await-in-loop -- first source that answers wins
        const values = await source?.getValues?.();
        if (values && typeof values === "object") {
          product = {};
          for (const key of ["type", "catalogue", "ads", "on-demand", "shuffle-eligible", "streaming-rules", "player-license", "financial-product"]) {
            if (key in values) product[key] = values[key];
          }
          break;
        }
      } catch {}
    }
    const connect = P?.ConnectAPI?.state;
    const devices = (connect?.devices || []).map(
      (d) => `${d.name}|${d.type}${d.isActive ? "|active" : ""}${d.isLocal ? "|local" : ""}${d.isDisabled ? "|disabled" : ""}`,
    );
    let storage = {};
    try {
      for (let i = 0; i < localStorage.length; i++) {
        const key = localStorage.key(i);
        if (/player|coordinator|device|connect|playback|lock/i.test(key)) storage[key] = String(localStorage.getItem(key)).slice(0, 80);
      }
    } catch {
      storage = "unavailable";
    }
    return {
      ...summary(),
      context: state?.context?.uri || null,
      restrictions,
      product,
      devices,
      coordinator: pick(P?.StandalonePlayerCoordinatorAPI),
      storage,
    };
  };
  const environment = () => ({
    touchPoints: navigator.maxTouchPoints,
    coarse: matchMedia("(pointer: coarse)").matches,
    hoverNone: matchMedia("(hover: none)").matches,
    uaMobile: navigator.userAgentData?.mobile ?? null,
    uaPlatform: navigator.userAgentData?.platform ?? null,
    screen: `${screen.width}x${screen.height}@${devicePixelRatio}`,
    visibility: document.visibilityState,
  });

  // A tap on a play control: log requests for 8 s and the state afterwards.
  document.addEventListener(
    "click",
    (e) => {
      const control = e.target?.closest?.(
        '[data-testid="play-button"], [data-testid="control-button-playpause"], [data-testid="tracklist-row"] button, [aria-label][data-encore-id="buttonPrimary"]',
      );
      if (!control) return;
      const name = control.getAttribute("data-testid") || control.getAttribute("aria-label") || control.tagName;
      const before = JSON.stringify(summary());
      watch(8000);
      setTimeout(async () => log(`tap ${name}: before ${before} after ${JSON.stringify(await details())}`), 2000);
    },
    true,
  );

  /** Calls the player API directly and logs what it returns (Settings → Diagnostics). */
  const testPlayback = async () => {
    watch(10_000);
    log(`test: environment ${JSON.stringify(environment())}`);
    const api = platform()?.PlayerAPI;
    if (!api) {
      log("test: PlayerAPI unavailable (safe mode or page not ready)");
      return;
    }
    log(`test: before ${JSON.stringify(await details())}`);
    const state = playerState();
    try {
      const call = state?.item ? (state.isPaused ? "resume" : "pause") : "play";
      const result = state?.item ? await api[call]() : await api.play({ uri: "spotify:playlist:37i9dQZF1DXcBWIGoYBM5M" }, {}, {});
      log(`test: ${call}() resolved ${JSON.stringify(pick(result)) ?? "undefined"}`);
    } catch (e) {
      log(`test: player call rejected: ${errText(e)}`);
    }
    setTimeout(async () => log(`test: after ${JSON.stringify(await details())}`), 3000);
  };

  log(`environment ${JSON.stringify(environment())}`);
  window.SpicetifyAndroidDiagnostics = { log, summary, details, environment, testPlayback, watch };
})();
