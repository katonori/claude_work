// ==UserScript==
// @name         3本指スワイプでタブを閉じる
// @namespace    https://github.com/katonori/claude_work
// @version      1.0.0
// @description  ページ上で3本指を素早く上下にスワイプすると、現在のタブを閉じます（Edge for Android / Tampermonkey 用）
// @author       -
// @match        *://*/*
// @run-at       document-idle
// @grant        window.close
// @grant        GM_getValue
// @grant        GM_setValue
// @grant        GM_registerMenuCommand
// @grant        GM_unregisterMenuCommand
// ==/UserScript==

(() => {
  "use strict";

  const REQUIRED_TOUCH_COUNT = 3;
  const MIN_SWIPE_DISTANCE = 80; // px
  const MAX_GESTURE_DURATION = 700; // ms

  let enabled = GM_getValue("enabled", true);
  let menuCommandId = null;

  function registerMenu() {
    if (menuCommandId !== null && typeof GM_unregisterMenuCommand === "function") {
      GM_unregisterMenuCommand(menuCommandId);
    }
    const label = enabled ? "ジェスチャーを無効にする" : "ジェスチャーを有効にする";
    menuCommandId = GM_registerMenuCommand(label, () => {
      enabled = !enabled;
      GM_setValue("enabled", enabled);
      registerMenu();
    });
  }
  registerMenu();

  let tracking = false;
  let startY = 0;
  let startTime = 0;

  function averageY(touches) {
    let sum = 0;
    for (const t of touches) sum += t.clientY;
    return sum / touches.length;
  }

  function showFeedback() {
    const el = document.createElement("div");
    el.textContent = "タブを閉じています…";
    Object.assign(el.style, {
      position: "fixed",
      top: "16px",
      left: "50%",
      transform: "translateX(-50%)",
      padding: "8px 16px",
      background: "rgba(0,0,0,0.75)",
      color: "#fff",
      borderRadius: "8px",
      fontSize: "14px",
      zIndex: "2147483647",
      pointerEvents: "none",
    });
    document.documentElement.appendChild(el);
    setTimeout(() => el.remove(), 800);
  }

  function onTouchStart(e) {
    if (!enabled) return;
    if (e.touches.length === REQUIRED_TOUCH_COUNT) {
      tracking = true;
      startY = averageY(e.touches);
      startTime = Date.now();
    } else {
      tracking = false;
    }
  }

  function onTouchMove(e) {
    if (!tracking) return;
    if (e.touches.length !== REQUIRED_TOUCH_COUNT) {
      tracking = false;
      return;
    }
    e.preventDefault();
  }

  function onTouchEnd(e) {
    if (!tracking) return;
    tracking = false;

    const elapsed = Date.now() - startTime;
    if (elapsed > MAX_GESTURE_DURATION) return;

    const endTouches = e.changedTouches;
    if (!endTouches || endTouches.length === 0) return;
    const endY = averageY(endTouches);
    const deltaY = endY - startY;

    if (Math.abs(deltaY) >= MIN_SWIPE_DISTANCE) {
      showFeedback();
      setTimeout(() => window.close(), 200);
    }
  }

  document.addEventListener("touchstart", onTouchStart, { passive: true });
  document.addEventListener("touchmove", onTouchMove, { passive: false });
  document.addEventListener("touchend", onTouchEnd, { passive: true });
  document.addEventListener("touchcancel", () => {
    tracking = false;
  });
})();
