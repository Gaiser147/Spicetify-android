// Some modules export objects whose properties cannot be read, e.g. a
// cross-origin iframe window in the web player. Reading any property of those
// throws, which would abort every later scan, so they are skipped.
export function isAccessible(value) {
  if ((typeof value !== "object" && typeof value !== "function") || !value) return true;
  try {
    void value.$$typeof;
    return true;
  } catch {
    return false;
  }
}

export function getObjectValues(value) {
  if (typeof value !== "object" || !value) return [];
  try {
    return Object.values(value).filter(isAccessible);
  } catch {
    return [];
  }
}

// Evaluating a module whose dependencies are not registered yet throws, and
// the bundler then keeps the half-initialised module in its cache, breaking the
// app later ("Cannot access 'x' before initialization"). This happens in the
// web player, where chunks are loaded lazily. Only evaluate modules whose
// static dependencies are all registered.
const factoryDepsCache = new WeakMap();
const factoryRequireParamRe = /^[^(]*\(\s*[\w$]*\s*,\s*[\w$]*\s*,\s*([\w$]+)\s*\)/;

function getFactoryDeps(factory) {
  if (typeof factory !== "function") return [];
  let deps = factoryDepsCache.get(factory);
  if (deps) return deps;
  deps = [];
  try {
    const src = Function.prototype.toString.call(factory);
    const param = src.match(factoryRequireParamRe)?.[1];
    if (param) {
      const escaped = param.replace(/\$/g, "\\$");
      const re = new RegExp(`(?<![\\w$.])${escaped}\\((\\d+)\\)`, "g");
      for (const m of src.matchAll(re)) deps.push(m[1]);
    }
  } catch {}
  factoryDepsCache.set(factory, deps);
  return deps;
}

export function createDependencyChecker(require) {
  const known = new Set();
  const isReady = (moduleId, seen = new Set()) => {
    const id = String(moduleId);
    if (known.has(id)) return true;
    if (!Object.hasOwn(require.m, id)) return false;
    if (seen.has(id)) return true;
    seen.add(id);
    for (const dep of getFactoryDeps(require.m[id])) if (!isReady(dep, seen)) return false;
    known.add(id);
    return true;
  };
  return isReady;
}

function safeRequire(require, isReady, id) {
  if (!isReady(id)) return { ok: false };
  try {
    return { ok: true, exported: require(id) };
  } catch {
    return { ok: false };
  }
}

export function getModuleInventory(require) {
  const isReady = createDependencyChecker(require);
  const chunks = Object.entries(require.m);
  const cache = Object.keys(require.m).map((id) => safeRequire(require, isReady, id).exported);
  const webpackFactories = new Set(Object.values(require.m));
  const modules = cache.flatMap(getObjectValues);
  const functionModules = modules.flatMap((module) => {
    if (typeof module === "function") return [module];
    return getObjectValues(module).filter((value) => typeof value === "function" && !webpackFactories.has(value));
  });

  return { chunks, cache, modules, functionModules };
}

export function createModuleInventoryScanner() {
  const processedIds = new Set();
  let isReady = null;
  const chunks = [];
  const cache = [];
  const modules = [];
  const functionModules = [];

  return {
    chunks,
    cache,
    modules,
    functionModules,
    scan(require) {
      isReady ??= createDependencyChecker(require);
      const webpackFactories = new Set(Object.values(require.m));
      const newChunks = [];
      const newModules = [];
      const newFunctionModules = [];

      for (const id of Object.keys(require.m)) {
        if (processedIds.has(id)) continue;

        // A lazily loaded chunk can register a module before all of its
        // dependencies are available; evaluating it then throws. Retry on the
        // next scan instead of aborting this one.
        const result = safeRequire(require, isReady, id);
        if (!result.ok) continue;
        const exported = result.exported;
        processedIds.add(id);

        const factory = require.m[id];
        const chunkEntry = [id, factory];
        chunks.push(chunkEntry);
        newChunks.push(chunkEntry);

        cache.push(exported);

        for (const value of getObjectValues(exported)) {
          modules.push(value);
          newModules.push(value);

          if (typeof value === "function") {
            functionModules.push(value);
            newFunctionModules.push(value);
            continue;
          }

          for (const inner of getObjectValues(value)) {
            if (typeof inner === "function" && !webpackFactories.has(inner)) {
              functionModules.push(inner);
              newFunctionModules.push(inner);
            }
          }
        }
      }

      return { chunks: newChunks, modules: newModules, functionModules: newFunctionModules };
    },
  };
}

export function groupBy(values, keyFinder) {
  if (typeof Object.groupBy === "function") return Object.groupBy(values, keyFinder);
  return values.reduce((a, b) => {
    const key = typeof keyFinder === "function" ? keyFinder(b) : b[keyFinder];
    a[key] = a[key] ? [...a[key], b] : [b];
    return a;
  }, {});
}
