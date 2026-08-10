chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
  if (message?.action !== "closeTab") return;

  const tabId = sender.tab?.id;
  if (tabId === undefined) return;

  chrome.tabs.remove(tabId).catch(() => {
    // 最後の1枚のタブなど、閉じられない場合は何もしない
  });
});
