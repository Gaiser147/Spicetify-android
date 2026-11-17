// The desktop client's PlayerAPI exposes `_state`, `_events` and `_queue`,
// which the wrapper and many extensions rely on. The web player (used by the
// Android port) exposes the same data through `getState()`, `getEvents()` and
// queue methods instead. This adds the desktop-style fields when missing.
const patched = new WeakSet();

export function withPlayerCompat(origin) {
  if (!origin || typeof origin !== "object" || patched.has(origin)) return origin;
  patched.add(origin);

  try {
    if (!("_state" in origin) && typeof origin.getState === "function") {
      Object.defineProperty(origin, "_state", { configurable: true, get: () => origin.getState() });
    }

    if (!origin._events && typeof origin.getEvents === "function") {
      const events = origin.getEvents();
      const emitter = typeof events?.addListener === "function" ? events : events?._emitter;
      if (emitter && typeof emitter.addListener === "function") {
        Object.defineProperty(origin, "_events", { configurable: true, value: emitter });
      }
    }

    if (!origin._queue && typeof origin.addToQueue === "function") {
      const call =
        (name) =>
        (...args) =>
          origin[name]?.(...args);
      Object.defineProperty(origin, "_queue", {
        configurable: true,
        value: {
          addToQueue: call("addToQueue"),
          removeFromQueue: call("removeFromQueue"),
          clearQueue: call("clearQueue"),
          insertIntoQueue: call("insertIntoQueue"),
          reorderQueue: call("reorderQueue"),
          getQueue: call("getQueue"),
        },
      });
    }
  } catch (e) {
    console.warn("[spicetifyWrapper] PlayerAPI compatibility shim failed", e);
  }
  return origin;
}
