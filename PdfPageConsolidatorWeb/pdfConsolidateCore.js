/**
 * Core N-up PDF consolidation logic, shared between the browser app (app.js)
 * and any Node-based tests. Depends only on the pdf-lib API passed in as
 * `PDFLib`, so it has no bundler-specific import/export syntax.
 */
(function (root, factory) {
  if (typeof module === "object" && module.exports) {
    module.exports = factory();
  } else {
    root.PdfConsolidatorCore = factory();
  }
})(typeof self !== "undefined" ? self : this, function () {
  "use strict";

  var MARGIN_PT = 24;
  var GAP_PT = 10;

  var GRID_PRESETS = [
    { id: "2-v", label: "2ページ → 1枚 (縦に2分割)", columns: 1, rows: 2 },
    { id: "2-h", label: "2ページ → 1枚 (横に2分割)", columns: 2, rows: 1 },
    { id: "4", label: "4ページ → 1枚 (2×2)", columns: 2, rows: 2 },
    { id: "6-23", label: "6ページ → 1枚 (2×3)", columns: 2, rows: 3 },
    { id: "6-32", label: "6ページ → 1枚 (3×2)", columns: 3, rows: 2 },
    { id: "9", label: "9ページ → 1枚 (3×3)", columns: 3, rows: 3 },
  ];

  var PAGE_SIZES = [
    { id: "a4", label: "A4", widthPt: 595, heightPt: 842 },
    { id: "letter", label: "Letter", widthPt: 612, heightPt: 792 },
  ];

  function ConsolidationError(message, cause) {
    this.name = "ConsolidationError";
    this.message = message;
    this.cause = cause;
  }
  ConsolidationError.prototype = Object.create(Error.prototype);

  /**
   * @param {object} PDFLib - the pdf-lib module/namespace (PDFDocument, rgb, ...)
   * @param {Uint8Array|ArrayBuffer} inputBytes - source PDF bytes
   * @param {object} options
   * @param {number} options.columns
   * @param {number} options.rows
   * @param {number} options.pageWidthPt
   * @param {number} options.pageHeightPt
   * @param {function(processed:number, total:number):(void|Promise<void>)} [onProgress]
   * @returns {Promise<Uint8Array>} bytes of the consolidated output PDF
   */
  async function consolidate(PDFLib, inputBytes, options, onProgress) {
    var PDFDocument = PDFLib.PDFDocument;
    var rgb = PDFLib.rgb;

    var columns = options.columns;
    var rows = options.rows;
    var pageWidthPt = options.pageWidthPt;
    var pageHeightPt = options.pageHeightPt;

    if (!(columns > 0) || !(rows > 0)) {
      throw new ConsolidationError("columns/rows must be positive");
    }

    var srcDoc;
    try {
      srcDoc = await PDFDocument.load(inputBytes);
    } catch (e) {
      if (e && /encrypted/i.test(String(e.message))) {
        throw new ConsolidationError(
          "パスワード保護されたPDFは処理できません",
          e
        );
      }
      throw new ConsolidationError(
        "PDFファイルを読み込めませんでした(壊れている可能性があります)",
        e
      );
    }

    var totalPages = srcDoc.getPageCount();
    if (totalPages === 0) {
      throw new ConsolidationError("このPDFにはページがありません");
    }

    var cellWidth = (pageWidthPt - MARGIN_PT * 2 - GAP_PT * (columns - 1)) / columns;
    var cellHeight = (pageHeightPt - MARGIN_PT * 2 - GAP_PT * (rows - 1)) / rows;
    if (cellWidth <= 0 || cellHeight <= 0) {
      throw new ConsolidationError(
        "選択したレイアウトがページサイズに対して大きすぎます"
      );
    }

    var outDoc = await PDFDocument.create();
    var embeddedPages = await outDoc.embedPages(srcDoc.getPages());

    var pagesPerSheet = columns * rows;
    var borderColor = rgb(0.78, 0.78, 0.78);

    var sourceIndex = 0;
    while (sourceIndex < totalPages) {
      var page = outDoc.addPage([pageWidthPt, pageHeightPt]);
      var groupEnd = Math.min(sourceIndex + pagesPerSheet, totalPages);

      for (var i = sourceIndex; i < groupEnd; i++) {
        var slot = i - sourceIndex;
        var col = slot % columns;
        var row = Math.floor(slot / columns);

        var cellLeft = MARGIN_PT + col * (cellWidth + GAP_PT);
        var cellTopFromTop = MARGIN_PT + row * (cellHeight + GAP_PT);
        var cellBottomY = pageHeightPt - cellTopFromTop - cellHeight;

        var embedded = embeddedPages[i];
        var srcAspect = embedded.width / embedded.height;
        var cellAspect = cellWidth / cellHeight;

        var fittedWidth, fittedHeight;
        if (srcAspect > cellAspect) {
          fittedWidth = cellWidth;
          fittedHeight = fittedWidth / srcAspect;
        } else {
          fittedHeight = cellHeight;
          fittedWidth = fittedHeight * srcAspect;
        }

        var drawX = cellLeft + (cellWidth - fittedWidth) / 2;
        var drawY = cellBottomY + (cellHeight - fittedHeight) / 2;

        page.drawPage(embedded, {
          x: drawX,
          y: drawY,
          width: fittedWidth,
          height: fittedHeight,
        });

        page.drawRectangle({
          x: cellLeft,
          y: cellBottomY,
          width: cellWidth,
          height: cellHeight,
          borderColor: borderColor,
          borderWidth: 1,
        });

        if (onProgress) {
          await onProgress(i + 1, totalPages);
        }
      }

      sourceIndex = groupEnd;
    }

    return outDoc.save();
  }

  return {
    consolidate: consolidate,
    ConsolidationError: ConsolidationError,
    GRID_PRESETS: GRID_PRESETS,
    PAGE_SIZES: PAGE_SIZES,
  };
});
