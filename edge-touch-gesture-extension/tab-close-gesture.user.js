// ==UserScript==
// @name         1本指でL字を描いてタブを閉じる
// @namespace    https://github.com/katonori/claude_work
// @version      3.0.0
// @description  ページ上で1本指でL字（直角に近い2辺）の軌跡を描くと、現在のタブを閉じます（Edge for Android / Tampermonkey 用）。↑→・↑←によるタブ切り替えは、Tampermonkeyにタブ操作APIが無いため非対応です。
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

  const MIN_LEG = 70; // px, 各辺の最低の長さ
  const STRAIGHTNESS_TOLERANCE = 1.35; // 実際の指の軌跡長 / 直線距離 の許容比率
  const AXIS_ALIGN_RATIO = 1.8; // 辺が横方向/縦方向とみなすための比率
  const MAX_GESTURE_DURATION = 2000; // ms
  const MIN_POINT_DISTANCE = 4; // px, 記録する点の間引き間隔

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
  let points = [];
  let startTime = 0;

  function showFeedback(text) {
    const el = document.createElement("div");
    el.textContent = text;
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

  function distance(a, b) {
    return Math.hypot(b.x - a.x, b.y - a.y);
  }

  function perpendicularDistance(p, a, b) {
    const dx = b.x - a.x;
    const dy = b.y - a.y;
    const lineLenSq = dx * dx + dy * dy;
    if (lineLenSq === 0) return distance(p, a);
    const t = ((p.x - a.x) * dx + (p.y - a.y) * dy) / lineLenSq;
    const projX = a.x + t * dx;
    const projY = a.y + t * dy;
    return distance(p, { x: projX, y: projY });
  }

  function pathLength(pts, from, to) {
    let len = 0;
    for (let i = from + 1; i <= to; i++) {
      len += distance(pts[i - 1], pts[i]);
    }
    return len;
  }

  function findCornerIndex(pts) {
    const start = pts[0];
    const end = pts[pts.length - 1];
    let maxDist = -1;
    let cornerIdx = -1;
    for (let i = 1; i < pts.length - 1; i++) {
      const d = perpendicularDistance(pts[i], start, end);
      if (d > maxDist) {
        maxDist = d;
        cornerIdx = i;
      }
    }
    return cornerIdx;
  }

  // 指の軌跡を分類する:
  // - 上に動いてから右に曲がる(↑→) -> nextTab (Tampermonkeyでは操作手段が無いため未対応)
  // - 上に動いてから左に曲がる(↑←) -> previousTab (同上)
  // - それ以外の直角に近いL字(L/Γ/J/⌐) -> closeTab
  // - L字とみなせない場合は null
  function classifyGesture(pts) {
    if (pts.length < 4) return null;

    const start = pts[0];
    const end = pts[pts.length - 1];
    const cornerIdx = findCornerIndex(pts);
    if (cornerIdx <= 0 || cornerIdx >= pts.length - 1) return null;
    const corner = pts[cornerIdx];

    const leg1Straight = distance(start, corner);
    const leg2Straight = distance(corner, end);
    if (leg1Straight < MIN_LEG || leg2Straight < MIN_LEG) return null;

    const leg1Path = pathLength(pts, 0, cornerIdx);
    const leg2Path = pathLength(pts, cornerIdx, pts.length - 1);
    if (leg1Path / leg1Straight > STRAIGHTNESS_TOLERANCE) return null;
    if (leg2Path / leg2Straight > STRAIGHTNESS_TOLERANCE) return null;

    const vx1 = corner.x - start.x;
    const vy1 = corner.y - start.y;
    const vx2 = end.x - corner.x;
    const vy2 = end.y - corner.y;
    const dx1 = Math.abs(vx1);
    const dy1 = Math.abs(vy1);
    const dx2 = Math.abs(vx2);
    const dy2 = Math.abs(vy2);

    const leg1Horizontal = dx1 > dy1 * AXIS_ALIGN_RATIO;
    const leg1Vertical = dy1 > dx1 * AXIS_ALIGN_RATIO;
    const leg2Horizontal = dx2 > dy2 * AXIS_ALIGN_RATIO;
    const leg2Vertical = dy2 > dx2 * AXIS_ALIGN_RATIO;

    if (!(leg1Horizontal || leg1Vertical)) return null;
    if (!(leg2Horizontal || leg2Vertical)) return null;
    const isPerpendicular = (leg1Horizontal && leg2Vertical) || (leg1Vertical && leg2Horizontal);
    if (!isPerpendicular) return null;

    const leg1Dir = leg1Horizontal ? (vx1 > 0 ? "right" : "left") : vy1 > 0 ? "down" : "up";
    const leg2Dir = leg2Horizontal ? (vx2 > 0 ? "right" : "left") : vy2 > 0 ? "down" : "up";

    if (leg1Dir === "up" && leg2Dir === "right") return "nextTab";
    if (leg1Dir === "up" && leg2Dir === "left") return "previousTab";
    return "closeTab";
  }

  function onTouchStart(e) {
    if (!enabled) return;
    if (e.touches.length !== 1) {
      tracking = false;
      points = [];
      return;
    }
    tracking = true;
    startTime = Date.now();
    const t = e.touches[0];
    points = [{ x: t.clientX, y: t.clientY }];
  }

  function onTouchMove(e) {
    if (!tracking) return;
    if (e.touches.length !== 1) {
      tracking = false;
      points = [];
      return;
    }
    // preventDefault は呼ばない: 通常のスクロール操作を妨げないようにする
    const t = e.touches[0];
    const last = points[points.length - 1];
    const p = { x: t.clientX, y: t.clientY };
    if (distance(last, p) >= MIN_POINT_DISTANCE) {
      points.push(p);
    }
  }

  function onTouchEnd() {
    if (!tracking) return;
    tracking = false;

    const elapsed = Date.now() - startTime;
    if (elapsed <= MAX_GESTURE_DURATION) {
      const gesture = classifyGesture(points);
      if (gesture === "closeTab") {
        showFeedback("タブを閉じています…");
        setTimeout(() => window.close(), 200);
      } else if (gesture === "nextTab" || gesture === "previousTab") {
        showFeedback("この操作はブラウザ拡張機能版でのみ対応しています");
      }
    }
    points = [];
  }

  document.addEventListener("touchstart", onTouchStart, { passive: true });
  document.addEventListener("touchmove", onTouchMove, { passive: true });
  document.addEventListener("touchend", onTouchEnd, { passive: true });
  document.addEventListener("touchcancel", () => {
    tracking = false;
    points = [];
  });
})();
