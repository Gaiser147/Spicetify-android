// The desktop client's PlayerAPI exposes `_state`, `_events` and `_queue`,
// which Spicetify.Player and many extensions use. The web player's PlayerAPI
// only has the public getters (getState, getEvents, addToQueue, ...).
//
// Spicetify.Player.origin is a Proxy that adds the missing fields for
// Spicetify and extensions. Spotify's own PlayerAPI object is never changed:
// writing fields into it broke playback in the web player. Fields that only
// Spicetify adds (e.g. `_cosmos` on the web player) are kept on the proxy.
const proxies = new WeakMap();

function queueFacade(target) {
  const call =
    (name) =>
    (...args) =>
      target[name]?.(...args);
  return {
    addToQueue: call("addToQueue"),
    removeFromQueue: call("removeFromQueue"),
    clearQueue: call("clearQueue"),
    insertIntoQueue: call("insertIntoQueue"),
    reorderQueue: call("reorderQueue"),
    getQueue: call("getQueue"),
  };
}

function eventEmitter(target) {
  const events = target.getEvents?.();
  const emitter = typeof events?.addListener === "function" ? events : events?._emitter;
  return emitter && typeof emitter.addListener === "function" ? emitter : undefined;
}

export function withPlayerCompat(origin) {
  if (!origin || (typeof origin !== "object" && typeof origin !== "function")) return origin;
  const cached = proxies.get(origin);
  if (cached) return cached;

  const extras = new Map();
  const bound = new WeakMap();
  let queue;
  const virtual = {
    _state: (t) => (typeof t.getState === "function" ? t.getState() : undefined),
    _events: (t) => eventEmitter(t),
    _queue: (t) => (typeof t.addToQueue === "function" ? (queue ??= queueFacade(t)) : undefined),
  };

  const proxy = new Proxy(origin, {
    get(target, prop) {
      if (extras.has(prop)) return extras.get(prop);
      if (!(prop in target) && Object.hasOwn(virtual, prop)) {
        try {
          return virtual[prop](target);
        } catch (e) {
          console.warn(`[spicetifyWrapper] PlayerAPI ${String(prop)} unavailable`, e);
          return undefined;
        }
      }
      const value = Reflect.get(target, prop, target);
      if (typeof value !== "function") return value;
      // Proxy invariant: frozen own properties must be returned as they are.
      const own = Object.getOwnPropertyDescriptor(target, prop);
      if (own && !own.configurable && !own.writable) return value;
      // Methods run on Spotify's object, not on the proxy (private fields).
      let fn = bound.get(value);
      if (!fn) {
        fn = value.bind(target);
        bound.set(value, fn);
      }
      return fn;
    },
    set(target, prop, value) {
      if (prop in target) return Reflect.set(target, prop, value, target);
      extras.set(prop, value);
      return true;
    },
    has(target, prop) {
      return extras.has(prop) || prop in target || (Object.hasOwn(virtual, prop) && virtual[prop](target) !== undefined);
    },
  });
  proxies.set(origin, proxy);
  return proxy;
}
