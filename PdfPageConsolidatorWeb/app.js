(function () {
  "use strict";

  var Core = window.PdfConsolidatorCore;
  var PDFLib = window.PDFLib;

  var fileInput = document.getElementById("fileInput");
  var fileInfo = document.getElementById("fileInfo");
  var gridSelect = document.getElementById("gridSelect");
  var pageSizeSelect = document.getElementById("pageSizeSelect");
  var consolidateButton = document.getElementById("consolidateButton");
  var progressBar = document.getElementById("progressBar");
  var statusText = document.getElementById("statusText");
  var downloadArea = document.getElementById("downloadArea");
  var downloadLink = document.getElementById("downloadLink");

  var selectedBytes = null;
  var selectedBaseName = "document";
  var lastObjectUrl = null;

  Core.GRID_PRESETS.forEach(function (preset) {
    var opt = document.createElement("option");
    opt.value = preset.id;
    opt.textContent = preset.label;
    gridSelect.appendChild(opt);
  });

  Core.PAGE_SIZES.forEach(function (size) {
    var opt = document.createElement("option");
    opt.value = size.id;
    opt.textContent = size.label;
    pageSizeSelect.appendChild(opt);
  });

  function setStatus(message, kind) {
    statusText.textContent = message || "";
    statusText.classList.remove("error", "success");
    if (kind) statusText.classList.add(kind);
  }

  function setProcessing(isProcessing) {
    consolidateButton.disabled = isProcessing || !selectedBytes;
    fileInput.disabled = isProcessing;
    gridSelect.disabled = isProcessing;
    pageSizeSelect.disabled = isProcessing;
    document
      .querySelectorAll('input[name="orientation"]')
      .forEach(function (el) {
        el.disabled = isProcessing;
      });
    progressBar.style.display = isProcessing ? "block" : "none";
    progressBar.value = 0;
  }

  fileInput.addEventListener("change", function () {
    var file = fileInput.files && fileInput.files[0];
    downloadArea.style.display = "none";
    setStatus("");

    if (!file) {
      selectedBytes = null;
      consolidateButton.disabled = true;
      fileInfo.textContent = "ファイルが選択されていません";
      return;
    }

    selectedBaseName = file.name.replace(/\.pdf$/i, "") || "document";
    fileInfo.textContent = `読み込み中... (${file.name})`;

    file
      .arrayBuffer()
      .then(function (buffer) {
        selectedBytes = new Uint8Array(buffer);
        return PDFLib.PDFDocument.load(selectedBytes);
      })
      .then(function (doc) {
        var pageCount = doc.getPageCount();
        fileInfo.textContent = `${file.name} (${pageCount} ページ)`;
        consolidateButton.disabled = false;
      })
      .catch(function (e) {
        selectedBytes = null;
        consolidateButton.disabled = true;
        var message = /encrypted/i.test(String(e && e.message))
          ? "パスワード保護されたPDFは処理できません"
          : "PDFファイルを読み込めませんでした(壊れている可能性があります)";
        fileInfo.textContent = `${file.name} — 読み込みエラー`;
        setStatus(message, "error");
      });
  });

  consolidateButton.addEventListener("click", function () {
    if (!selectedBytes) return;

    var preset = Core.GRID_PRESETS.find(function (p) {
      return p.id === gridSelect.value;
    });
    var pageSize = Core.PAGE_SIZES.find(function (s) {
      return s.id === pageSizeSelect.value;
    });
    var landscape =
      document.querySelector('input[name="orientation"]:checked').value ===
      "landscape";

    var pageWidthPt = landscape ? pageSize.heightPt : pageSize.widthPt;
    var pageHeightPt = landscape ? pageSize.widthPt : pageSize.heightPt;

    setProcessing(true);
    setStatus("処理中...");
    downloadArea.style.display = "none";

    var lastYield = performance.now();

    Core.consolidate(
      PDFLib,
      selectedBytes,
      {
        columns: preset.columns,
        rows: preset.rows,
        pageWidthPt: pageWidthPt,
        pageHeightPt: pageHeightPt,
      },
      function (processed, total) {
        progressBar.max = total;
        progressBar.value = processed;
        setStatus(`処理中... (${processed} / ${total} ページ)`);

        // Yield to the browser periodically so the UI can repaint during
        // large documents, without adding latency for small ones.
        var now = performance.now();
        if (now - lastYield > 50) {
          lastYield = now;
          return new Promise(function (resolve) {
            requestAnimationFrame(function () {
              resolve();
            });
          });
        }
      }
    )
      .then(function (outputBytes) {
        var blob = new Blob([outputBytes], { type: "application/pdf" });
        if (lastObjectUrl) URL.revokeObjectURL(lastObjectUrl);
        lastObjectUrl = URL.createObjectURL(blob);

        var timestamp = new Date()
          .toISOString()
          .replace(/[-:]/g, "")
          .replace(/\..+/, "")
          .replace("T", "_");
        var outName = `${selectedBaseName}_consolidated_${timestamp}.pdf`;

        downloadLink.href = lastObjectUrl;
        downloadLink.download = outName;
        downloadArea.style.display = "block";

        var outDoc = null;
        return PDFLib.PDFDocument.load(outputBytes).then(function (doc) {
          outDoc = doc;
          setStatus(`完了しました(出力 ${outDoc.getPageCount()} ページ)`, "success");
        });
      })
      .catch(function (e) {
        var message =
          e && e.message ? e.message : "変換に失敗しました";
        setStatus(`エラー: ${message}`, "error");
      })
      .finally(function () {
        setProcessing(false);
      });
  });

  if ("serviceWorker" in navigator) {
    window.addEventListener("load", function () {
      navigator.serviceWorker.register("sw.js").catch(function () {
        // Offline caching is a progressive enhancement; ignore failures
        // (e.g. when opened directly from the filesystem without a server).
      });
    });
  }
})();
