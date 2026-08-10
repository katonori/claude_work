chrome.runtime.onMessage.addListener((message, sender) => {
  const tabId = sender.tab?.id;
  if (tabId === undefined || !message?.action) return;

  if (message.action === "closeTab") {
    chrome.tabs.remove(tabId).catch(() => {
      // 最後の1枚のタブなど、閉じられない場合は何もしない
    });
    return;
  }

  if (message.action === "nextTab" || message.action === "previousTab") {
    focusAdjacentTab(sender.tab, message.action === "nextTab" ? 1 : -1);
  }
});

async function focusAdjacentTab(tab, offset) {
  const tabs = await chrome.tabs.query({ windowId: tab.windowId });
  if (tabs.length < 2) return;

  tabs.sort((a, b) => a.index - b.index);
  const currentIndex = tabs.findIndex((t) => t.id === tab.id);
  if (currentIndex === -1) return;

  const targetIndex = (currentIndex + offset + tabs.length) % tabs.length;
  await chrome.tabs.update(tabs[targetIndex].id, { active: true }).catch(() => {});
}
