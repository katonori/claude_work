package com.katonori.pdfconsolidator

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Renders every page of a source PDF and lays several of them out on each page of a new
 * output PDF (an "N-up" layout), so a long document can be printed/read using fewer sheets.
 */
object PdfConsolidator {

    private const val MARGIN_PT = 24f
    private const val GAP_PT = 10f

    // Bitmap pixels per PDF point used when rasterizing each source page; higher = sharper
    // output but slower and more memory.
    private const val RENDER_SCALE = 2f

    class ConsolidationException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /**
     * @return the number of pages written to [outputUri].
     */
    suspend fun consolidate(
        context: Context,
        inputUri: Uri,
        outputUri: Uri,
        columns: Int,
        rows: Int,
        pageWidthPt: Float,
        pageHeightPt: Float,
        onProgress: (processed: Int, total: Int) -> Unit,
    ): Int = withContext(Dispatchers.IO) {
        require(columns > 0 && rows > 0) { "columns and rows must be positive" }
        val pagesPerSheet = columns * rows

        val resolver = context.contentResolver
        val inputPfd = try {
            resolver.openFileDescriptor(inputUri, "r")
        } catch (e: SecurityException) {
            throw ConsolidationException("入力ファイルへのアクセス権がありません", e)
        } ?: throw ConsolidationException("PDFファイルを開けませんでした")

        var outputPageCount = 0

        inputPfd.use { pfd ->
            val renderer = try {
                PdfRenderer(pfd)
            } catch (e: SecurityException) {
                throw ConsolidationException("パスワード保護されたPDFは処理できません", e)
            } catch (e: Exception) {
                throw ConsolidationException("PDFファイルを読み込めませんでした (壊れている可能性があります)", e)
            }

            renderer.use {
                val totalPages = renderer.pageCount
                if (totalPages == 0) throw ConsolidationException("このPDFにはページがありません")

                val cellWidth = (pageWidthPt - MARGIN_PT * 2 - GAP_PT * (columns - 1)) / columns
                val cellHeight = (pageHeightPt - MARGIN_PT * 2 - GAP_PT * (rows - 1)) / rows
                if (cellWidth <= 0f || cellHeight <= 0f) {
                    throw ConsolidationException("選択したレイアウトがページサイズに対して大きすぎます")
                }

                val outputDocument = PdfDocument()
                try {
                    val borderPaint = Paint().apply {
                        color = Color.LTGRAY
                        style = Paint.Style.STROKE
                        strokeWidth = 1f
                    }

                    var sourceIndex = 0
                    var outputPageIndex = 0
                    while (sourceIndex < totalPages) {
                        outputPageIndex++
                        val pageInfo = PdfDocument.PageInfo.Builder(
                            pageWidthPt.toInt().coerceAtLeast(1),
                            pageHeightPt.toInt().coerceAtLeast(1),
                            outputPageIndex
                        ).create()

                        val page = outputDocument.startPage(pageInfo)
                        val canvas = page.canvas
                        canvas.drawColor(Color.WHITE)

                        val groupEnd = minOf(sourceIndex + pagesPerSheet, totalPages)
                        for (i in sourceIndex until groupEnd) {
                            val slot = i - sourceIndex
                            val col = slot % columns
                            val row = slot / columns
                            val cellLeft = MARGIN_PT + col * (cellWidth + GAP_PT)
                            val cellTop = MARGIN_PT + row * (cellHeight + GAP_PT)
                            val cellRect = RectF(cellLeft, cellTop, cellLeft + cellWidth, cellTop + cellHeight)

                            renderSourcePageIntoCell(renderer, i, canvas, cellRect, borderPaint)
                            onProgress(i + 1, totalPages)
                        }

                        outputDocument.finishPage(page)
                        outputPageCount++
                        sourceIndex = groupEnd
                    }

                    val outStream = try {
                        resolver.openOutputStream(outputUri)
                    } catch (e: SecurityException) {
                        throw ConsolidationException("保存先へのアクセス権がありません", e)
                    } ?: throw ConsolidationException("保存先を開けませんでした")

                    outStream.use { outputDocument.writeTo(it) }
                } finally {
                    outputDocument.close()
                }
            }
        }

        outputPageCount
    }

    private fun renderSourcePageIntoCell(
        renderer: PdfRenderer,
        pageIndex: Int,
        canvas: Canvas,
        cellRect: RectF,
        borderPaint: Paint,
    ) {
        val page = renderer.openPage(pageIndex)
        try {
            val sourceAspect = page.width.toFloat() / page.height.toFloat()
            val cellAspect = cellRect.width() / cellRect.height()

            val fittedWidth: Float
            val fittedHeight: Float
            if (sourceAspect > cellAspect) {
                fittedWidth = cellRect.width()
                fittedHeight = fittedWidth / sourceAspect
            } else {
                fittedHeight = cellRect.height()
                fittedWidth = fittedHeight * sourceAspect
            }

            val destLeft = cellRect.left + (cellRect.width() - fittedWidth) / 2f
            val destTop = cellRect.top + (cellRect.height() - fittedHeight) / 2f
            val destRect = RectF(destLeft, destTop, destLeft + fittedWidth, destTop + fittedHeight)

            val bitmapWidth = (fittedWidth * RENDER_SCALE).toInt().coerceAtLeast(1)
            val bitmapHeight = (fittedHeight * RENDER_SCALE).toInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                canvas.drawBitmap(bitmap, null, destRect, null)
            } finally {
                bitmap.recycle()
            }

            canvas.drawRect(cellRect, borderPaint)
        } finally {
            page.close()
        }
    }
}
