package com.example.pdfbuilder

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

import com.example.pdfbuilder.ui.theme.AppTheme
import com.example.pdfbuilder.utils.UpdateManager
import com.example.pdfbuilder.data.GithubRelease

enum class PrintType {
    SINGLE_SIDED, // Fronts then Backs
    DOUBLE_SIDED  // Interleaved
}

enum class PaperOrientation {
    LANDSCAPE,
    PORTRAIT
}

enum class BlankPagePosition {
    END,
    BEGINNING,
    CENTER // Add blanks in the middle of the booklet
}

enum class SplitMode {
    NONE,           // 1 Input Page -> 1 Logical Page
    VERTICAL,       // 1 Input Page -> 2 Logical Pages (Left, Right)
    HORIZONTAL      // 1 Input Page -> 2 Logical Pages (Top, Bottom)
}

enum class LayoutMode {
    NORMAL,         // Just sequential pages (1, 2, 3...)
    BOOKLET         // Saddle-stitch reordering (8, 1, 7, 2...)
}

enum class CoverMode {
    HAS_COVER,
    NO_COVER_ADD_BLANK
}

data class BookletConfig(
    val printType: PrintType = PrintType.DOUBLE_SIDED,
    val splitMode: SplitMode = SplitMode.VERTICAL,
    val layoutMode: LayoutMode = LayoutMode.BOOKLET,
    val coverMode: CoverMode = CoverMode.HAS_COVER,
    val paperOrientation: PaperOrientation = PaperOrientation.LANDSCAPE,
    val innerMarginMm: Float = 0f,
    val outputRotationDegrees: Int = 0,
    val blankPagePosition: BlankPagePosition = BlankPagePosition.END
)

data class BookletUiState(
    val sourceUri: Uri? = null,
    val config: BookletConfig = BookletConfig(),
    val isProcessing: Boolean = false,
    val progress: Float = 0f,
    val message: String? = null,
    val error: String? = null,
    val outputUri: Uri? = null,
    
    // Preview State
    val totalInputPages: Int = 0,
    val totalOutputSheets: Int = 0,
    
    // Preview Mode
    val isReaderPreview: Boolean = false, // false = Sheet View, true = Reader View
    
    // Sheet View State
    val previewSheetIndex: Int = 0, // 0 to totalOutputSheets - 1
    val previewShowBack: Boolean = false, // Show front or back of the sheet
    
    // Reader View State
    val readerSpreadIndex: Int = 0, // 0 to totalSheets * 2 (approx total spreads)
    val totalReaderSpreads: Int = 0,

    val previewBitmap: Bitmap? = null,
    val previewLoading: Boolean = false,
    
    val appTheme: AppTheme = AppTheme.MINIMALIST,
    
    // Update Dialog State
    val showUpdateDialog: Boolean = false,
    
    // GitHub Update State
    val updateState: UpdateManager.UpdateState = UpdateManager.UpdateState.Idle
)

class BookletViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(BookletUiState())
    val uiState: StateFlow<BookletUiState> = _uiState.asStateFlow()

    private val processor = PdfBookletProcessor(application)
    private var previewJob: kotlinx.coroutines.Job? = null
    
    private val PREFS_NAME = "pdf_booklet_prefs"
    private val KEY_LAST_URI = "last_opened_uri"
    private val KEY_APP_THEME = "app_theme"
    private val KEY_LAST_VERSION_CODE = "last_version_code"

    init {
        restoreLastUri()
        restoreAppTheme()
        // checkAppVersion() // Disable auto-show changelog on update
        checkForUpdates()
    }

    private fun checkForUpdates() {
        UpdateManager.checkForUpdate { state ->
            _uiState.value = _uiState.value.copy(updateState = state)
        }
    }
    
    fun downloadUpdate(release: GithubRelease) {
        val context = getApplication<Application>()
        UpdateManager.downloadAndInstall(
            context, 
            release,
            onProgress = { progress ->
                _uiState.value = _uiState.value.copy(
                    updateState = UpdateManager.UpdateState.Downloading(progress)
                )
            },
            onError = { error ->
                _uiState.value = _uiState.value.copy(
                    updateState = UpdateManager.UpdateState.Error(error)
                )
            }
        )
    }

    private fun checkAppVersion() {
        val prefs = getApplication<Application>().getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
        val lastVersionCode = prefs.getInt(KEY_LAST_VERSION_CODE, -1)
        val currentVersionCode = BuildConfig.VERSION_CODE

        if (currentVersionCode > lastVersionCode) {
            // _uiState.value = _uiState.value.copy(showUpdateDialog = true) // Disabled
            prefs.edit().putInt(KEY_LAST_VERSION_CODE, currentVersionCode).apply()
        }
    }
    
    fun dismissUpdateDialog() {
        _uiState.value = _uiState.value.copy(showUpdateDialog = false)
    }

    private fun restoreAppTheme() {
        val prefs = getApplication<Application>().getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
        val themeName = prefs.getString(KEY_APP_THEME, AppTheme.MINIMALIST.name)
        val theme = try {
            AppTheme.valueOf(themeName ?: AppTheme.MINIMALIST.name)
        } catch (e: Exception) {
            AppTheme.MINIMALIST
        }
        _uiState.value = _uiState.value.copy(appTheme = theme)
    }

    fun setAppTheme(theme: AppTheme) {
        _uiState.value = _uiState.value.copy(appTheme = theme)
        val prefs = getApplication<Application>().getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_APP_THEME, theme.name).apply()
    }

    private fun restoreLastUri() {
        try {
            val prefs = getApplication<Application>().getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
            val uriString = prefs.getString(KEY_LAST_URI, null)
            if (uriString != null) {
                val uri = Uri.parse(uriString)
                // Check if we still have permission and file exists
                try {
                    val takeFlags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                    getApplication<Application>().contentResolver.takePersistableUriPermission(uri, takeFlags)
                    onPdfSelected(uri)
                } catch (e: SecurityException) {
                    // Permission lost or file gone
                    prefs.edit().remove(KEY_LAST_URI).apply()
                } catch (e: Exception) {
                    // Other errors
                    prefs.edit().remove(KEY_LAST_URI).apply()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun onPdfSelected(uri: Uri) {
        // Save URI
        viewModelScope.launch {
            try {
                val prefs = getApplication<Application>().getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
                prefs.edit().putString(KEY_LAST_URI, uri.toString()).apply()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                sourceUri = uri,
                isProcessing = false,
                progress = 0f,
                message = null,
                error = null,
                outputUri = null,
                previewSheetIndex = 0,
                previewShowBack = false
            )
            loadPdfInfo(uri)
            updatePreview()
        }
    }

    private suspend fun loadPdfInfo(uri: Uri) {
        try {
            val pageCount = processor.getPageCount(uri)
            updateTotalSheets(pageCount, _uiState.value.config)
        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(error = "无法读取 PDF: ${e.message}")
        }
    }

    fun updateConfig(newConfig: BookletConfig) {
        _uiState.value = _uiState.value.copy(config = newConfig)
        // If booklet mode changes, total output sheets might change
        updateTotalSheets(_uiState.value.totalInputPages, newConfig)
        updatePreview()
    }

    private fun updateTotalSheets(inputPageCount: Int, config: BookletConfig) {
        if (inputPageCount == 0) return
        val logicalPages = when (config.splitMode) {
            SplitMode.NONE -> inputPageCount
            SplitMode.VERTICAL, SplitMode.HORIZONTAL -> inputPageCount * 2
        }
        val coverBlank = if (config.layoutMode == LayoutMode.BOOKLET && config.coverMode == CoverMode.NO_COVER_ADD_BLANK) 1 else 0
        val totalLogicalPages = logicalPages + coverBlank
        val sheets = (totalLogicalPages + 3) / 4
        
        val totalBookletPages = sheets * 4
        val totalSpreads = (totalBookletPages / 2) + 1

        _uiState.value = _uiState.value.copy(
            totalInputPages = inputPageCount,
            totalOutputSheets = sheets,
            totalReaderSpreads = totalSpreads
        )
    }

    fun togglePreviewMode(isReader: Boolean) {
        _uiState.value = _uiState.value.copy(isReaderPreview = isReader)
        updatePreview()
    }

    fun setReaderSpread(index: Int) {
        val safeIndex = index.coerceIn(0, _uiState.value.totalReaderSpreads - 1)
        _uiState.value = _uiState.value.copy(readerSpreadIndex = safeIndex)
        updatePreview()
    }

    fun togglePreviewSide() {
        _uiState.value = _uiState.value.copy(previewShowBack = !_uiState.value.previewShowBack)
        updatePreview()
    }

    fun setPreviewSheet(index: Int) {
        val safeIndex = index.coerceIn(0, _uiState.value.totalOutputSheets - 1)
        _uiState.value = _uiState.value.copy(previewSheetIndex = safeIndex)
        updatePreview()
    }

    fun setPreviewState(sheetIndex: Int, showBack: Boolean) {
        val safeIndex = sheetIndex.coerceIn(0, _uiState.value.totalOutputSheets - 1)
        _uiState.value = _uiState.value.copy(
            previewSheetIndex = safeIndex,
            previewShowBack = showBack
        )
        updatePreview()
    }

    private fun updatePreview() {
        val state = _uiState.value
        if (state.sourceUri == null || state.totalInputPages == 0) return

        // Cancel previous preview generation to avoid OOM
        previewJob?.cancel()
        
        previewJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(previewLoading = true)
            try {
                val bitmap = if (state.isReaderPreview) {
                    processor.generateReaderPreview(
                        state.sourceUri,
                        state.config,
                        state.readerSpreadIndex
                    )
                } else {
                    processor.generatePreview(
                        state.sourceUri,
                        state.config,
                        state.previewSheetIndex,
                        state.previewShowBack
                    )
                }
                
                // Safe swap: Update state with new bitmap
                // We rely on GC to collect the old bitmap since recycling it immediately might cause
                // race conditions if the UI is still rendering the previous frame.
                _uiState.value = _uiState.value.copy(previewBitmap = bitmap, previewLoading = false)
            } catch (e: Exception) {
                // Ignore preview errors or log them
                if (e !is kotlinx.coroutines.CancellationException) {
                    _uiState.value = _uiState.value.copy(previewLoading = false)
                }
            }
        }
    }

    fun generateBooklet(outputUri: Uri) {
        val state = _uiState.value
        if (state.sourceUri == null) {
            notifyError("请先选择 PDF")
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isProcessing = true,
                progress = 0f,
                message = null,
                error = null
            )

            try {
                processor.generateBookletPdf(
                    state.sourceUri,
                    outputUri,
                    state.config
                ) { progress ->
                    _uiState.value = _uiState.value.copy(progress = progress)
                }
                
                _uiState.value = _uiState.value.copy(
                    isProcessing = false,
                    message = "生成成功",
                    outputUri = outputUri
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isProcessing = false,
                    error = "生成失败: ${e.message}"
                )
            }
        }
    }

    fun notifyError(msg: String) {
        _uiState.value = _uiState.value.copy(error = msg)
    }
    
    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
    
    fun clearMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }
}
