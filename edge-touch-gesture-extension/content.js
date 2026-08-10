(() => {
  const REQUIRED_TOUCH_COUNT = 3;
  const MIN_SWIPE_DISTANCE = 80; // px
  const MAX_GESTURE_DURATION = 700; // ms

  let enabled = true;
  chrome.storage.sync.get({ enabled: true }, (result) => {
    enabled = result.enabled;
  });
  chrome.storage.onChanged.addListener((changes, area) => {
    if (area === "sync" && "enabled" in changes) {
      enabled = changes.enabled.newValue;
    }
  });

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
    // 3本指ジェスチャー中はページのスクロールを抑止する
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
      chrome.runtime.sendMessage({ action: "closeTab" });
    }
  }

  document.addEventListener("touchstart", onTouchStart, { passive: true });
  document.addEventListener("touchmove", onTouchMove, { passive: false });
  document.addEventListener("touchend", onTouchEnd, { passive: true });
  document.addEventListener("touchcancel", () => {
    tracking = false;
  });
})();
