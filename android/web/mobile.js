// Spicetify Android mobile layout.
//
// The web player is built for >= 800px wide windows. On phones this script
// switches to a single column layout: the library sidebar becomes a drawer,
// the right sidebar (now playing view / queue / lyrics) becomes a full screen
// panel and the now playing bar is compacted (see mobile.css).
//
// Spotify's class names are hashed and change between builds, so elements are
// identified by their CSS grid area names instead and tagged with stable
// `sa-*` classes that mobile.css targets.
(function spicetifyAndroidMobile() {
  "use strict";
  if (window.__spicetifyAndroidMobile) return;
  window.__spicetifyAndroidMobile = true;

  const root = document.documentElement;
  const BREAKPOINT = 840;
  const forced = () => {
    try {
      return localStorage.getItem("spicetify-android:layout") || "auto";
    } catch {
      return "auto";
    }
  };
  const isPhone = () => {
    const mode = forced();
    if (mode === "mobile") return true;
    if (mode === "desktop") return false;
    return Math.min(window.screen?.width || innerWidth, innerWidth) < BREAKPOINT;
  };

  const ensureViewport = () => {
    let meta = document.querySelector('meta[name="viewport"]');
    if (!meta) {
      meta = document.createElement("meta");
      meta.name = "viewport";
      (document.head || root).appendChild(meta);
    }
    const content = isPhone()
      ? "width=device-width, initial-scale=1, maximum-scale=1, viewport-fit=cover"
      : "width=device-width, initial-scale=1, viewport-fit=cover";
    if (meta.content !== content) meta.content = content;
  };

  const setOpen = (name, open) => {
    document.body?.classList.toggle(`spicetify-android-${name}-open`, open);
  };
  const isOpen = (name) => !!document.body?.classList.contains(`spicetify-android-${name}-open`);

  const drawerButton = () => {
    let btn = document.getElementById("spicetify-android-drawer-toggle");
    if (btn) return btn;
    btn = document.createElement("button");
    btn.id = "spicetify-android-drawer-toggle";
    btn.type = "button";
    btn.setAttribute("aria-label", "Library");
    btn.innerHTML =
      '<svg viewBox="0 0 24 24" width="24" height="24" fill="currentColor" aria-hidden="true"><path d="M3 6h18v2H3zM3 11h18v2H3zM3 16h18v2H3z"/></svg>';
    btn.addEventListener("click", (e) => {
      e.stopPropagation();
      setOpen("drawer", !isOpen("drawer"));
    });
    return btn;
  };

  const scrim = () => {
    let el = document.getElementById("spicetify-android-scrim");
    if (el) return el;
    el = document.createElement("div");
    el.id = "spicetify-android-scrim";
    el.addEventListener("click", () => {
      setOpen("drawer", false);
      setOpen("panel", false);
    });
    return el;
  };

  const AREAS = new Set(["top-banner", "global-nav", "left-sidebar", "main-view", "right-sidebar", "now-playing-bar"]);

  const tagLayout = () => {
    const mainView = document.getElementById("main-view") || document.querySelector(".Root__main-view");
    const grid = mainView?.parentElement;
    if (!grid) return false;
    grid.classList.add("sa-grid");
    grid.closest(".Root")?.classList.add("sa-root");
    for (const child of grid.children) {
      const area = getComputedStyle(child).gridArea?.split("/")[0]?.trim();
      if (AREAS.has(area)) child.classList.add(`sa-area-${area}`);
    }

    const nav = document.getElementById("global-nav-bar") || grid.querySelector(".sa-area-global-nav");
    if (nav && !nav.querySelector("#spicetify-android-drawer-toggle")) nav.prepend(drawerButton());
    if (!document.getElementById("spicetify-android-scrim")) grid.appendChild(scrim());

    // Now playing bar: tag left / center / right sections.
    const bar = grid.querySelector(".sa-area-now-playing-bar aside, aside.sa-area-now-playing-bar");
    if (bar) {
      bar.classList.add("sa-nowplaying");
      const row = [...bar.querySelectorAll(":scope > div, :scope > footer > div")].find((d) => d.children.length === 3);
      if (row) {
        row.classList.add("sa-nowplaying-row");
        row.children[0].classList.add("sa-np-left");
        row.children[1].classList.add("sa-np-center");
        row.children[2].classList.add("sa-np-right");
      }
    }

    // Right sidebar has content (now playing view, queue, lyrics, ...)?
    const right = grid.querySelector(".sa-area-right-sidebar");
    if (right) {
      const hasPanel = right.getBoundingClientRect().width > 80 || right.querySelector("aside, section, [role=complementary]");
      right.classList.toggle("sa-has-panel", !!hasPanel);
    }
    return true;
  };

  // Tapping the track info in the compact bar opens the now playing panel.
  document.addEventListener(
    "click",
    (e) => {
      if (!root.classList.contains("spicetify-android-mobile")) return;
      const left = e.target.closest?.(".sa-np-left");
      if (left && !e.target.closest("a, button")) {
        setOpen("panel", !isOpen("panel"));
        return;
      }
      // Navigating from the drawer closes it.
      if (e.target.closest?.(".sa-area-left-sidebar a, .sa-area-left-sidebar [role=row]")) {
        setTimeout(() => setOpen("drawer", false), 150);
      }
    },
    true,
  );

  // Edge swipe from the left opens the drawer, swipe left closes it.
  let touchStart = null;
  document.addEventListener(
    "touchstart",
    (e) => {
      const t = e.touches[0];
      touchStart = t ? { x: t.clientX, y: t.clientY, time: Date.now() } : null;
    },
    { passive: true },
  );
  document.addEventListener(
    "touchend",
    (e) => {
      if (!touchStart || !root.classList.contains("spicetify-android-mobile")) return;
      const t = e.changedTouches[0];
      const dx = t.clientX - touchStart.x;
      const dy = Math.abs(t.clientY - touchStart.y);
      const fast = Date.now() - touchStart.time < 600;
      if (fast && dy < 60) {
        if (touchStart.x < 24 && dx > 60) setOpen("drawer", true);
        else if (isOpen("drawer") && dx < -60) setOpen("drawer", false);
      }
      touchStart = null;
    },
    { passive: true },
  );

  const apply = () => {
    const phone = isPhone();
    root.classList.toggle("spicetify-android-mobile", phone);
    root.classList.toggle("spicetify-android-tablet", !phone);
    ensureViewport();
    if (phone) tagLayout();
    else {
      setOpen("drawer", false);
      setOpen("panel", false);
    }
  };

  let scheduled = false;
  const schedule = () => {
    if (scheduled) return;
    scheduled = true;
    requestAnimationFrame(() => {
      scheduled = false;
      apply();
    });
  };

  window.addEventListener("resize", schedule);
  const start = () => {
    apply();
    new MutationObserver(schedule).observe(document.body, { childList: true, subtree: true });
  };
  if (document.body) start();
  else document.addEventListener("DOMContentLoaded", start, { once: true });

  window.SpicetifyAndroidLayout = {
    set(mode) {
      try {
        localStorage.setItem("spicetify-android:layout", mode);
      } catch {}
      apply();
    },
    get: forced,
    closeOverlays() {
      const open = isOpen("drawer") || isOpen("panel");
      setOpen("drawer", false);
      setOpen("panel", false);
      return open;
    },
  };
})();
