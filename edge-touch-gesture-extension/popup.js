const checkbox = document.getElementById("enabled");

chrome.storage.sync.get({ enabled: true }, (result) => {
  checkbox.checked = result.enabled;
});

checkbox.addEventListener("change", () => {
  chrome.storage.sync.set({ enabled: checkbox.checked });
});
