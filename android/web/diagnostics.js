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
  let dealerMessages = 0;
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
  // Requests of the playback stack are always logged.
  const PLAYBACK_PATH = /track-playback|connect-state\/v1\/devices|storage-resolve|manifests|widevine-license|playplay|audio-attributes/;
  const net = (method, u, status, started) => {
    const ok = typeof status === "number" && status >= 200 && status < 400;
    if (ok && !watching() && !PLAYBACK_PATH.test(u.pathname)) return;
    log(`${method} ${short(u)} -> ${status} (${Math.round(performance.now() - started)} ms)`, "player-net");
  };

  // Device registration and Connect commands: request and response bodies
  // show the capabilities the web player reports and what the server answers.
  // Credential-like fields are removed.
  const BODY_PATH = /track-playback\/v1\/devices|connect-state\/v1\/devices|connect-state\/v1\/cluster|connect\/transfer|player\/command/;
  const SECRET_KEY = /token|auth|secret|password|cookie/i;
  const scrub = (body) => {
    if (body == null || body === "") return "-";
    if (typeof body !== "string") {
      const size = body.byteLength ?? body.size ?? body.length;
      return `[${body.constructor?.name || typeof body}${size != null ? ` ${size} bytes` : ""}]`;
    }
    try {
      return JSON.stringify(JSON.parse(body), (k, v) => (k && SECRET_KEY.test(k) ? "[removed]" : v)).slice(0, 1500);
    } catch {
      return body.replace(/[\w-]{60,}/g, "[…]").slice(0, 1500);
    }
  };
  // A Connect cluster (connect-state responses) in a few fields: which
  // device the server considers active and which devices it knows.
  const clusterSummary = (text) => {
    try {
      const c = JSON.parse(text);
      if (!c || typeof c !== "object" || (!c.devices && !c.player_state)) return null;
      const short8 = (id) => String(id || "-").slice(0, 8);
      const p = c.player_state || {};
      return JSON.stringify({
        active: short8(c.active_device_id),
        devices: Object.entries(c.devices || {}).map(
          ([id, d]) =>
            `${short8(id)} ${d.name || "?"}/${d.device_type || "?"}${d.is_active ? " active" : ""}${d.capabilities?.can_be_player === false ? " no-player" : ""}${d.capabilities?.hidden ? " hidden" : ""}${d.license ? ` ${d.license}` : ""}`,
        ),
        player: `playing=${p.is_playing} paused=${p.is_paused} ${p.track?.uri || "-"} ts=${p.timestamp || "-"}`,
        transfer: c.need_full_player_state ?? c.transfer_data_timestamp ?? undefined,
        timestamp: c.timestamp,
        changed: c.changed_fields,
      });
    } catch {
      return null;
    }
  };
  const logBodies = (method, u, requestBody, status, responseText) => {
    const cluster = typeof responseText === "string" ? clusterSummary(responseText) : null;
    log(
      `${method} ${short(u)} ${status} request ${scrub(requestBody)} response ${cluster ? `cluster ${cluster}` : scrub(responseText)}`,
      "player-body",
    );
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
          // Without its remote config the web player does not play.
          if (/remote-config-resolver/.test(u.pathname)) {
            const body = typeof init?.body === "string" ? init.body : "";
            const set = body.match(/"propertySetId"\s*:\s*"(\w+)"/)?.[1] ?? "?";
            if (res.ok) log(`remote config ${res.status} (property set ${set})`, "player-net");
            else {
              res
                .clone()
                .text()
                .then((t) => log(`remote config ${res.status} (property set ${set}): ${t.slice(0, 300)}`, "player-net"))
                .catch(() => {});
            }
          } else if (BODY_PATH.test(u.pathname)) {
            res
              .clone()
              .text()
              .then((t) => logBodies(method, u, init?.body ?? null, res.status, t))
              .catch(() => {});
          } else if (!res.ok && /track-playback|connect-state/.test(u.pathname)) {
            res
              .clone()
              .text()
              .then((t) => log(`${short(u)} ${res.status}: ${t.slice(0, 300)}`, "player-net"))
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
    proto.send = function (body) {
      const m = meta.get(this);
      if (m) {
        const started = performance.now();
        this.addEventListener(
          "loadend",
          () => {
            net(m.method, m.u, this.status || "failed", started);
            if (BODY_PATH.test(m.u.pathname)) {
              let text = null;
              try {
                text = this.responseType === "" || this.responseType === "text" ? this.responseText : JSON.stringify(this.response);
              } catch {}
              logBodies(m.method, m.u, body ?? null, this.status || "failed", text);
            }
          },
          { once: true },
        );
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
        let opened = performance.now();
        let received = 0;
        const age = () => `${((performance.now() - opened) / 1000).toFixed(1)} s`;
        log(`ws connecting ${name}`, "player-net");
        this.addEventListener("open", () => {
          opened = performance.now();
          log(`ws open ${name}`, "player-net");
        });
        this.addEventListener("error", () => log(`ws error ${name} after ${age()}`, "player-net"));
        this.addEventListener("close", (e) =>
          log(`ws closed ${name} code=${e.code} clean=${e.wasClean} after ${age()}, ${received} messages ${e.reason || ""}`, "player-net"),
        );
        this.addEventListener("message", (e) => {
          received++;
          let what = typeof e.data === "string" ? "text" : "binary";
          try {
            const m = JSON.parse(e.data);
            what = [m.type, m.uri, m.message_ident, m.key].filter(Boolean).join(" ") || "json";
          } catch {}
          const size = typeof e.data === "string" ? e.data.length : (e.data?.byteLength ?? e.data?.size ?? "?");
          // Every message of the first two minutes of a connection (connection
          // id, cluster updates, commands), later ones only while watching.
          if (what === "pong" && !watching()) return;
          const early = performance.now() - opened < 120_000 && dealerMessages++ < 80;
          if (!early && (!watching() || watchMessages++ > 15)) return;
          log(`ws message ${name} +${age()} ${size} B: ${what.slice(0, 160)}`, "player-net");
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
      // Never copy credentials into a log the user shares.
      if (/token|secret|password|auth|cookie|session|key$/i.test(key)) continue;
      const desc = Object.getOwnPropertyDescriptor(obj, key);
      if (!desc || !("value" in desc)) continue;
      const v = desc.value;
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
  // The local player ("harmony") reports registration and playback failures
  // as events; Spotify's UI does not show most of them.
  const HARMONY_EVENTS = [
    "error",
    "authentication_error",
    "autoplay_failed",
    "connection_error",
    "device_deactivated",
    "local_player_disabled",
    "local_player_enabled",
    "logged_out",
    "max_list_errors_reached",
    "max_subscriptions_reached",
    "playback_capped",
    "player_initialization_done",
    "player_initialization_failed",
    "service_registration_error",
    "stalled",
    "stopped_on_background",
    "unrecoverable_failure",
    "registered",
    "registration_aborted",
    "deregistered",
  ];
  const describeEvent = (e) => {
    const d = e?.data ?? e;
    const err = d?.error ?? d?.reason_error ?? (d instanceof Error ? d : null);
    const out = {
      code: err?.code ?? d?.code,
      name: err?.name,
      message: err?.message ?? d?.message,
      status: err?.status ?? d?.status,
      source: d?.source,
      reason: d?.reason,
      fatal: err?.fatal,
      deviceId: d?.deviceId,
    };
    for (const k of Object.keys(out)) if (out[k] === undefined) delete out[k];
    return JSON.stringify(out).slice(0, 400);
  };
  let hookTries = 0;
  const hookPlayer = () => {
    const api = platform()?.PlayerAPI;
    const harmony = api?._harmony;
    if (!harmony || typeof harmony.addListener !== "function") {
      if (hookTries++ < 240) setTimeout(hookPlayer, 500);
      return;
    }
    let hooked = 0;
    for (const name of HARMONY_EVENTS) {
      try {
        harmony.addListener(name, (e) => log(`harmony ${name}: ${describeEvent(e)}`));
        hooked++;
      } catch {}
    }
    try {
      api.getEvents?.()?.addListener?.("error", (e) => log(`player error: ${describeEvent(e)}`));
    } catch {}
    log(`harmony hooked (${hooked} events) ${JSON.stringify(playbackInternals())}`);
  };
  /** Flat state of the playback objects (registration, device id), found below PlayerAPI._harmony. */
  const playbackInternals = () => {
    const root = platform()?.PlayerAPI?._harmony;
    if (!root || typeof root !== "object") return null;
    const out = {};
    const seen = new WeakSet();
    const queue = [[root, "harmony", 0]];
    let visited = 0;
    while (queue.length && visited < 400) {
      const [obj, path, depth] = queue.shift();
      if (!obj || typeof obj !== "object" || seen.has(obj)) continue;
      seen.add(obj);
      visited++;
      if (["_registered", "_deviceId", "_connectionId", "_isRegistered"].some((k) => Object.hasOwn(obj, k))) out[path] = pick(obj, 14);
      if (depth >= 3) continue;
      for (const key of Object.keys(obj).slice(0, 60)) {
        const desc = Object.getOwnPropertyDescriptor(obj, key);
        const v = desc && "value" in desc ? desc.value : undefined;
        if (v && typeof v === "object" && !Array.isArray(v) && !(v instanceof Node)) queue.push([v, `${path}.${key}`, depth + 1]);
      }
    }
    return out;
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
      internals: playbackInternals(),
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
    void echoHeaders();
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
  // Headers the WebView attaches (the app answers /__spicetify/echo and logs them).
  const echoHeaders = () =>
    fetch("/__spicetify/echo", { cache: "no-store" })
      .then((r) => (r.ok ? r.json() : Promise.reject(new Error(`HTTP ${r.status}`))))
      .then((j) => log(`request headers: ${j.headers}`))
      .catch((e) => log(`request headers unavailable: ${errText(e)}`));
  setTimeout(echoHeaders, 1000);
  // Formats the web player (and its ads) use; reported once per page.
  setTimeout(async () => {
    const types = [
      'audio/mp4; codecs="mp4a.40.2"',
      'audio/mp4; codecs="flac"',
      'audio/webm; codecs="opus"',
      'audio/ogg; codecs="vorbis"',
      "audio/mpeg",
      'video/mp4; codecs="avc1.42E01E"',
    ];
    const support = {};
    const audio = document.createElement("audio");
    for (const type of types) {
      support[type] = `mse=${window.MediaSource?.isTypeSupported?.(type) ?? "-"} play=${audio.canPlayType(type) || "no"}`;
    }
    try {
      const info = await navigator.mediaCapabilities?.decodingInfo?.({
        type: "media-source",
        audio: { contentType: 'audio/mp4; codecs="mp4a.40.2"' },
        keySystemConfiguration: { keySystem: "com.widevine.alpha", audio: { robustness: "SW_SECURE_CRYPTO" } },
      });
      if (info) support.widevineDecoding = `supported=${info.supported} smooth=${info.smooth} efficient=${info.powerEfficient}`;
    } catch (e) {
      support.widevineDecoding = errText(e);
    }
    log(`media support ${JSON.stringify(support)}`);
  }, 1500);
  hookPlayer();
  window.SpicetifyAndroidDiagnostics = { log, summary, details, environment, testPlayback, watch };
})();
