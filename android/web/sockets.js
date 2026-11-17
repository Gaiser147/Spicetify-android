// Spicetify Android: Spotify's dealer WebSocket through the app.
//
// The dealer connection delivers Connect commands (play, pause, skip) to the
// web player's local player. In the Android WebView it connects and answers
// pings, but Spotify never pushes a command over it, so playback does not
// start (the same account plays in Chrome on the same device). With
// window.__spicetifyAndroidNativeSockets set by the app, WebSockets to the
// dealer are opened natively (OkHttp, desktop browser headers) and bridged to
// the page through the SpicetifyAndroid message channel. Everything else uses
// the WebView's own WebSocket.
(function spicetifyAndroidSockets() {
  "use strict";
  if (window.SpicetifyAndroidSockets) return;

  const native = window.SpicetifyAndroid || null;
  const enabled = window.__spicetifyAndroidNativeSockets === true && !!native && typeof native.postMessage === "function";
  const DEALER_HOST = /(^|[.-])dealer(\.g2)?\.spotify\.com$/;
  const sockets = new Map();
  let nextId = 1;
  const post = (method, ...args) => native.postMessage(JSON.stringify({ method, args }));
  const log = (message) => window.SpicetifyAndroidDiagnostics?.log(message, "dealer");

  const CONNECTING = 0;
  const OPEN = 1;
  const CLOSING = 2;
  const CLOSED = 3;

  class NativeSocket extends EventTarget {
    constructor(url) {
      super();
      this.url = url;
      this.readyState = CONNECTING;
      this.protocol = "";
      this.extensions = "";
      this.binaryType = "blob";
      this.bufferedAmount = 0;
      this.onopen = null;
      this.onmessage = null;
      this.onclose = null;
      this.onerror = null;
      this.id = nextId++;
      sockets.set(this.id, this);
      post("wsOpen", this.id, url);
    }

    send(data) {
      if (this.readyState !== OPEN) throw new DOMException("WebSocket is not open", "InvalidStateError");
      post("wsSend", this.id, typeof data === "string" ? data : String(data));
    }

    close(code = 1000, reason = "") {
      if (this.readyState >= CLOSING) return;
      this.readyState = CLOSING;
      post("wsClose", this.id, code, reason);
    }

    emit(event) {
      this.dispatchEvent(event);
      const handler = this[`on${event.type}`];
      if (typeof handler === "function") handler.call(this, event);
    }
  }
  for (const [name, value] of Object.entries({ CONNECTING, OPEN, CLOSING, CLOSED })) {
    Object.defineProperty(NativeSocket, name, { value });
    Object.defineProperty(NativeSocket.prototype, name, { value });
  }

  window.SpicetifyAndroidSockets = {
    enabled,
    /** Called by the app: open, message (data), close (code, reason), error. */
    event(id, type, data, reason) {
      const socket = sockets.get(id);
      if (!socket) return;
      switch (type) {
        case "open":
          socket.readyState = OPEN;
          socket.emit(new Event("open"));
          break;
        case "message":
          socket.emit(new MessageEvent("message", { data, origin: new URL(socket.url).origin }));
          break;
        case "error":
          socket.emit(new Event("error"));
          break;
        case "close":
          socket.readyState = CLOSED;
          sockets.delete(id);
          socket.emit(new CloseEvent("close", { code: Number(data) || 1006, reason: reason || "", wasClean: Number(data) === 1000 }));
          break;
      }
    },
  };

  if (!enabled) return;
  const PageWebSocket = window.WebSocket;
  function BridgedWebSocket(url, protocols) {
    try {
      const u = new URL(String(url), location.href);
      if (u.protocol === "wss:" && DEALER_HOST.test(u.hostname)) {
        log(`native connection to ${u.host}`);
        return new NativeSocket(u.href);
      }
    } catch {}
    return protocols === undefined ? new PageWebSocket(url) : new PageWebSocket(url, protocols);
  }
  BridgedWebSocket.prototype = PageWebSocket.prototype;
  for (const name of ["CONNECTING", "OPEN", "CLOSING", "CLOSED"]) Object.defineProperty(BridgedWebSocket, name, { value: PageWebSocket[name] });
  window.WebSocket = BridgedWebSocket;
})();
