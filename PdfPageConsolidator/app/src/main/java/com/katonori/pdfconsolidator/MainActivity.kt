package com.katonori.pdfconsolidator

import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.katonori.pdfconsolidator.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var inputUri: Uri? = null
    private var inputFileName: String? = null

    private data class GridPreset(val label: String, val columns: Int, val rows: Int)

    private val gridPresets = listOf(
        GridPreset("2ページ → 1枚 (縦に2分割)", 1, 2),
        GridPreset("2ページ → 1枚 (横に2分割)", 2, 1),
        GridPreset("4ページ → 1枚 (2×2)", 2, 2),
        GridPreset("6ページ → 1枚 (2×3)", 2, 3),
        GridPreset("6ページ → 1枚 (3×2)", 3, 2),
        GridPreset("9ページ → 1枚 (3×3)", 3, 3),
    )

    private data class PdfPageSize(val label: String, val widthPt: Float, val heightPt: Float)

    private val pageSizes = listOf(
        PdfPageSize("A4", 595f, 842f),
        PdfPageSize("Letter", 612f, 792f),
    )

    private val openDocumentLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) onFileSelected(uri)
        }

    private val createDocumentLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
            if (uri != null) startConsolidation(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.gridSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            gridPresets.map { it.label }
        )
        binding.pageSizeSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            pageSizes.map { it.label }
        )

        binding.selectFileButton.setOnClickListener {
            openDocumentLauncher.launch(arrayOf("application/pdf"))
        }

        binding.consolidateButton.setOnClickListener {
            val uri = inputUri
            if (uri == null) {
                Toast.makeText(this, R.string.error_select_file_first, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val baseName = inputFileName?.substringBeforeLast(".") ?: "document"
            createDocumentLauncher.launch("${baseName}_consolidated_$timestamp.pdf")
        }
    }

    private fun onFileSelected(uri: Uri) {
        inputUri = uri
        val (name, pageCount) = queryFileInfo(uri)
        inputFileName = name

        binding.selectedFileText.text = if (pageCount > 0) {
            "$name ($pageCount ページ)"
        } else {
            name
        }
        binding.consolidateButton.isEnabled = true
        binding.statusText.text = ""
    }

    private fun queryFileInfo(uri: Uri): Pair<String, Int> {
        var name = uri.lastPathSegment ?: "document.pdf"
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) {
                cursor.getString(nameIndex)?.let { name = it }
            }
        }

        var pageCount = 0
        try {
            contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                PdfRenderer(pfd).use { renderer -> pageCount = renderer.pageCount }
            }
        } catch (_: Exception) {
            // Non-fatal here; consolidate() will surface a clearer error if the file is unusable.
        }
        return name to pageCount
    }

    private fun startConsolidation(outputUri: Uri) {
        val uri = inputUri ?: return
        val preset = gridPresets[binding.gridSpinner.selectedItemPosition]
        val pageSize = pageSizes[binding.pageSizeSpinner.selectedItemPosition]
        val landscape = binding.landscapeRadio.isChecked

        val pageWidthPt = if (landscape) pageSize.heightPt else pageSize.widthPt
        val pageHeightPt = if (landscape) pageSize.widthPt else pageSize.heightPt

        setProcessing(true)
        lifecycleScope.launch {
            val result = runCatching {
                PdfConsolidator.consolidate(
                    context = applicationContext,
                    inputUri = uri,
                    outputUri = outputUri,
                    columns = preset.columns,
                    rows = preset.rows,
                    pageWidthPt = pageWidthPt,
                    pageHeightPt = pageHeightPt,
                ) { processed, total ->
                    runOnUiThread {
                        binding.progressBar.max = total
                        binding.progressBar.progress = processed
                        binding.statusText.text = getString(R.string.status_processing, processed, total)
                    }
                }
            }

            setProcessing(false)
            result.onSuccess { outputPageCount ->
                binding.statusText.text = getString(R.string.status_done, outputPageCount)
                Toast.makeText(this@MainActivity, R.string.toast_done, Toast.LENGTH_LONG).show()
            }.onFailure { e ->
                val message = e.message ?: e.toString()
                binding.statusText.text = getString(R.string.status_failed, message)
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.toast_failed, message),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun setProcessing(isProcessing: Boolean) {
        binding.selectFileButton.isEnabled = !isProcessing
        binding.consolidateButton.isEnabled = !isProcessing && inputUri != null
        binding.gridSpinner.isEnabled = !isProcessing
        binding.pageSizeSpinner.isEnabled = !isProcessing
        binding.orientationRadioGroup.isEnabled = !isProcessing
        binding.portraitRadio.isEnabled = !isProcessing
        binding.landscapeRadio.isEnabled = !isProcessing
        binding.progressBar.visibility = if (isProcessing) android.view.View.VISIBLE else android.view.View.GONE
        binding.progressBar.progress = 0
    }
}
