package com.example.pdfbuilder

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.print.PrintManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import java.io.File
import java.io.FileOutputStream
import java.io.FileInputStream
import android.print.PrintDocumentAdapter
import android.print.PrintAttributes
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.pdfbuilder.ui.theme.PdfSplitterTheme
import com.example.pdfbuilder.ui.theme.AppTheme
import com.example.pdfbuilder.util.SafUtils
import com.example.pdfbuilder.data.ChangelogData
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val viewModel: BookletViewModel = viewModel()
            val uiState by viewModel.uiState.collectAsState()

            PdfSplitterTheme(appTheme = uiState.appTheme) {
                val context = LocalContext.current
                
                var selectedFileName by remember { mutableStateOf<String?>(null) }

                // Auto-update filename if sourceUri is restored from ViewModel
                LaunchedEffect(uiState.sourceUri) {
                    if (uiState.sourceUri != null) {
                        selectedFileName = SafUtils.getDisplayName(context, uiState.sourceUri!!) ?: uiState.sourceUri!!.lastPathSegment
                    }
                }

                val pickPdfLauncher =
                    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
                        if (uri != null) {
                            try {
                                context.contentResolver.takePersistableUriPermission(
                                    uri,
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                                )
                            } catch (_: SecurityException) {}
                            viewModel.onPdfSelected(uri)
                            // selectedFileName will be updated by LaunchedEffect
                        }
                    }

                val createDocumentLauncher =
                    rememberLauncherForActivityResult(
                        ActivityResultContracts.CreateDocument("application/pdf")
                    ) { uri: Uri? ->
                        if (uri != null) {
                            viewModel.generateBooklet(uri)
                        }
                    }

                val snackbarHostState = remember { SnackbarHostState() }

                LaunchedEffect(uiState.error, uiState.message) {
                    uiState.error?.let { 
                        snackbarHostState.showSnackbar(it) 
                        viewModel.clearError()
                    }
                    uiState.message?.let { 
                        snackbarHostState.showSnackbar(it)
                        viewModel.clearMessage()
                    }
                }

                BookletApp(
                    uiState = uiState,
                    viewModel = viewModel,
                    selectedFileName = selectedFileName,
                    snackbarHostState = snackbarHostState,
                    onSelectPdf = { pickPdfLauncher.launch(arrayOf("application/pdf")) },
                    onExport = {
                        if (selectedFileName != null) {
                            val name = "booklet_" + selectedFileName
                            createDocumentLauncher.launch(name)
                        }
                    },
                    onPrint = { option ->
                        val uri = uiState.sourceUri ?: return@BookletApp
                        doBookletPrint(
                            context = context,
                            sourceUri = uri,
                            config = uiState.config,
                            option = option
                        ) { viewModel.notifyError(it) }
                    }
                )
            }
        }
    }
}

enum class BookletPrintOption(val label: String, val description: String) {
    DOUBLE_SIDED("双面(顺序)", "正反面顺序打印"),
    DOUBLE_SIDED_REVERSE("双面(逆序)", "正反面逆序打印"),
    SINGLE_ODD("正面(奇数)", "只打印奇数页"),
    SINGLE_ODD_REVERSE("正面(逆序)", "逆序打印奇数页"),
    SINGLE_EVEN("背面(偶数)", "只打印偶数页"),
    SINGLE_EVEN_REVERSE("背面(逆序)", "逆序打印偶数页")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookletApp(
    uiState: BookletUiState,
    viewModel: BookletViewModel,
    selectedFileName: String?,
    snackbarHostState: SnackbarHostState,
    onSelectPdf: () -> Unit,
    onExport: () -> Unit,
    onPrint: (BookletPrintOption) -> Unit
) {
    var showSettings by remember { mutableStateOf(false) }
    var showChangelog by remember { mutableStateOf(false) }

    if (showSettings) {
        SettingsDialog(
            currentTheme = uiState.appTheme,
            onThemeSelected = { viewModel.setAppTheme(it) },
            onShowChangelog = { 
                showSettings = false 
                showChangelog = true 
            },
            onDismiss = { showSettings = false }
        )
    }

    if (showChangelog) {
        ChangelogDialog(onDismiss = { showChangelog = false })
    }

    // Auto-show changelog on new version
    if (uiState.showUpdateDialog) {
        ChangelogDialog(onDismiss = { viewModel.dismissUpdateDialog() })
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        bottomBar = {
            BottomActionBar(
                onExport = onExport,
                onPrint = { onPrint(it) },
                enabled = uiState.sourceUri != null && !uiState.isProcessing
            )
        }
    ) { paddingValues ->
        // Use a Column to layout Preview at Top and Settings at Bottom
        Column(modifier = Modifier.padding(paddingValues).fillMaxSize()) {
            
            // 1. Top Fixed Preview Section
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.40f)
                    .background(MaterialTheme.colorScheme.background)
            ) {
                PreviewSectionFixed(uiState, viewModel, onSettingsClick = { showSettings = true })
            }

            // 2. Bottom Scrollable Settings Section
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.60f)
                    .shadow(8.dp, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                    .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
            ) {
                BookletSettingsContent(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    uiState = uiState,
                    viewModel = viewModel,
                    selectedFileName = selectedFileName,
                    onSelectPdf = onSelectPdf,
                    onSettingsClick = { showSettings = true }
                )
            }
        }
    }
}

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun PreviewSectionFixed(
    uiState: BookletUiState, 
    viewModel: BookletViewModel,
    onSettingsClick: () -> Unit
) {
    // 1. Unified Container Frame (Integrated Look)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(4.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            
            // 2. Top Buttons (Custom Tabs, Flush Top, No Gaps)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(32.dp) // Fixed height for tabs
            ) {
                // Tab 1: 打印排版
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(
                            if (!uiState.isReaderPreview) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                        )
                        .clickable { viewModel.togglePreviewMode(false) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "打印排版",
                        color = if (!uiState.isReaderPreview) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (!uiState.isReaderPreview) FontWeight.Bold else FontWeight.Normal
                    )
                }

                // Tab 2: 成册效果
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(
                            if (uiState.isReaderPreview) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                        )
                        .clickable { viewModel.togglePreviewMode(true) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "成册效果",
                        color = if (uiState.isReaderPreview) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (uiState.isReaderPreview) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }

            // 3. Preview Content Area (Fills remaining space, White Background)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color.White) // Paper background
            ) {
                // Content Alignment (Image)
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    if (uiState.previewLoading) {
                        CircularProgressIndicator()
                    } else if (uiState.previewBitmap != null) {
                        AnimatedContent(
                            targetState = uiState.previewBitmap,
                            transitionSpec = {
                                fadeIn(animationSpec = tween(300)) togetherWith fadeOut(animationSpec = tween(300))
                            },
                            label = "PreviewAnimation"
                        ) { bitmap ->
                            PreviewImage(bitmap = bitmap!!)
                        }
                    } else {
                        Text("请选择文件以预览", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                // Overlays: Navigation Arrows
                // Previous Button (Left)
                FilledIconButton(
                    onClick = { 
                        if (uiState.isReaderPreview) {
                            viewModel.setReaderSpread(uiState.readerSpreadIndex - 1)
                        } else {
                            viewModel.setPreviewSheet(uiState.previewSheetIndex - 1)
                        }
                    },
                    enabled = if (uiState.isReaderPreview) uiState.readerSpreadIndex > 0 else uiState.previewSheetIndex > 0,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 0.dp)
                        .size(40.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        disabledContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.3f)
                    )
                ) { Icon(Icons.Default.ArrowBack, "Prev") }

                // Next Button (Right)
                FilledIconButton(
                    onClick = { 
                         if (uiState.isReaderPreview) viewModel.setReaderSpread(uiState.readerSpreadIndex + 1)
                         else viewModel.setPreviewSheet(uiState.previewSheetIndex + 1)
                    },
                    enabled = if (uiState.isReaderPreview) uiState.readerSpreadIndex < uiState.totalReaderSpreads - 1 else uiState.previewSheetIndex < uiState.totalOutputSheets - 1,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 0.dp)
                        .size(40.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        disabledContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.3f)
                    )
                ) { Icon(Icons.Default.ArrowForward, "Next") }

                // Overlay: Bottom Info & Controls
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 8.dp) // Restore a bit of padding for aesthetics
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f), RoundedCornerShape(50)) // Lighter glass effect
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Page Info Text
                    val infoText = if (uiState.isReaderPreview) {
                        "${uiState.readerSpreadIndex + 1} / ${uiState.totalReaderSpreads}"
                    } else {
                        val isBooklet = uiState.config.layoutMode == LayoutMode.BOOKLET
                        if (isBooklet) {
                            val sheetNum = uiState.previewSheetIndex + 1
                            val totalSheets = uiState.totalOutputSheets
                            "第 $sheetNum / $totalSheets 张"
                        } else {
                            "第 ${uiState.previewSheetIndex + 1} / ${uiState.totalOutputSheets} 页"
                        }
                    }
                    
                    Text(
                        text = infoText,
                        color = MaterialTheme.colorScheme.onSurface, // High contrast text
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )

                    // Side Toggle (Only for Booklet Print Mode)
                    if (!uiState.isReaderPreview && uiState.config.layoutMode == LayoutMode.BOOKLET) {
                        // Separator
                        Box(modifier = Modifier.size(1.dp, 12.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)))
                        
                        // Toggle Button
                        Row(
                            modifier = Modifier
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null // Remove ripple for cleaner look, or keep it
                                ) { viewModel.togglePreviewSide() },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                             Icon(
                                 painter = painterResource(android.R.drawable.ic_menu_rotate),
                                 contentDescription = null,
                                 tint = MaterialTheme.colorScheme.onSurface,
                                 modifier = Modifier.size(14.dp)
                             )
                             Spacer(modifier = Modifier.width(4.dp))
                             Text(
                                 text = if (uiState.previewShowBack) "背面" else "正面",
                                 color = MaterialTheme.colorScheme.onSurface,
                                 style = MaterialTheme.typography.bodyMedium,
                                 fontWeight = FontWeight.Bold
                             )
                        }
                    }
                }
            }
        }
    }
}

// ... imports

@Composable
fun BookletSettingsContent(
    modifier: Modifier = Modifier,
    uiState: BookletUiState,
    viewModel: BookletViewModel,
    selectedFileName: String?,
    onSelectPdf: () -> Unit,
    onSettingsClick: () -> Unit // Add this callback
) {
    // Remove local showChangelog since we use the main activity one via settings or a dedicated button
    
    val widthMm = if (uiState.config.paperOrientation == PaperOrientation.LANDSCAPE) 297f else 210f
    val heightMm = if (uiState.config.paperOrientation == PaperOrientation.LANDSCAPE) 210f else 297f
    val maxMarginMm = if (widthMm < heightMm) widthMm / 4f else heightMm / 4f

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(modifier = Modifier.height(8.dp))
        
        // 1. File Selection
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("源文件", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = selectedFileName ?: "未选择文件",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Button(
                        onClick = onSelectPdf,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.tertiary,
                            contentColor = MaterialTheme.colorScheme.onTertiary
                        )
                    ) {
                        Text("选择文件")
                    }
                }
            }
        }

        // 2. Compact Settings Card (Consolidated)
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Row 1: Layout Mode & Split Mode
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Layout Mode
                    Column(modifier = Modifier.weight(1f)) {
                        Text("排版模式", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CompactChip(
                                selected = uiState.config.layoutMode == LayoutMode.BOOKLET,
                                onClick = { viewModel.updateConfig(uiState.config.copy(layoutMode = LayoutMode.BOOKLET)) },
                                label = "小册子",
                                modifier = Modifier.weight(1f)
                            )
                            CompactChip(
                                selected = uiState.config.layoutMode == LayoutMode.NORMAL,
                                onClick = { viewModel.updateConfig(uiState.config.copy(layoutMode = LayoutMode.NORMAL)) },
                                label = "无",
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    
                    // Split Mode
                    Column(modifier = Modifier.weight(1f)) {
                        Text("页面分割", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CompactChip(
                                selected = uiState.config.splitMode == SplitMode.VERTICAL,
                                onClick = { viewModel.updateConfig(uiState.config.copy(splitMode = SplitMode.VERTICAL)) },
                                label = "左右",
                                modifier = Modifier.weight(1f)
                            )
                            CompactChip(
                                selected = uiState.config.splitMode == SplitMode.HORIZONTAL,
                                onClick = { viewModel.updateConfig(uiState.config.copy(splitMode = SplitMode.HORIZONTAL)) },
                                label = "上下",
                                modifier = Modifier.weight(1f)
                            )
                            CompactChip(
                                selected = uiState.config.splitMode == SplitMode.NONE,
                                onClick = { viewModel.updateConfig(uiState.config.copy(splitMode = SplitMode.NONE)) },
                                label = "无",
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
                
                Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                // Row 2: Paper Orientation & Cover Mode
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Paper Orientation
                    Column(modifier = Modifier.weight(1f)) {
                        Text("纸张方向", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CompactChip(
                                selected = uiState.config.paperOrientation == PaperOrientation.LANDSCAPE,
                                onClick = { viewModel.updateConfig(uiState.config.copy(paperOrientation = PaperOrientation.LANDSCAPE)) },
                                label = "横向",
                                modifier = Modifier.weight(1f)
                            )
                            CompactChip(
                                selected = uiState.config.paperOrientation == PaperOrientation.PORTRAIT,
                                onClick = { viewModel.updateConfig(uiState.config.copy(paperOrientation = PaperOrientation.PORTRAIT)) },
                                label = "纵向",
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    // Cover Mode (Conditional)
                    if (uiState.config.layoutMode == LayoutMode.BOOKLET) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("封面设置", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                CompactChip(
                                    selected = uiState.config.coverMode == CoverMode.HAS_COVER,
                                    onClick = { viewModel.updateConfig(uiState.config.copy(coverMode = CoverMode.HAS_COVER)) },
                                    label = "保留",
                                    modifier = Modifier.weight(1f)
                                )
                                CompactChip(
                                    selected = uiState.config.coverMode == CoverMode.NO_COVER_ADD_BLANK,
                                    onClick = { viewModel.updateConfig(uiState.config.copy(coverMode = CoverMode.NO_COVER_ADD_BLANK)) },
                                    label = "补白",
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    } else {
                         // Placeholder to keep alignment if not booklet mode
                         Spacer(modifier = Modifier.weight(1f))
                    }
                }
                
                Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                // Row 3: Inner Margin
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("内边距 (中缝留白)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                        Text("${String.format("%.1f", uiState.config.innerMarginMm)} mm", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                    }
                    Slider(
                        value = uiState.config.innerMarginMm,
                        onValueChange = { viewModel.updateConfig(uiState.config.copy(innerMarginMm = it)) },
                        valueRange = 0f..maxMarginMm,
                        modifier = Modifier.fillMaxWidth().height(32.dp) // Compact slider
                    )
                }
            }
        }
        
        Divider(color = MaterialTheme.colorScheme.outlineVariant)
        
        // 0. Settings & Updates Row (Moved to Bottom)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center, // Centered
            verticalAlignment = Alignment.CenterVertically
        ) {
             TextButton(onClick = onSettingsClick) {
                Icon(Icons.Outlined.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("设置 & 更新日志")
            }
        }

        // Spacer for BottomBar
        Spacer(modifier = Modifier.height(80.dp))
    }
}

@Composable
fun ChangelogDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("更新日志", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                ChangelogData.versions.forEach { version ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                "v${version.version}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                version.date,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        version.changes.forEach { change ->
                            Row(verticalAlignment = Alignment.Top) {
                                Text("• ", color = MaterialTheme.colorScheme.secondary)
                                Text(change, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) { Text("关闭") }
        }
    )
}

@Composable
fun BottomActionBar(
    onExport: () -> Unit,
    onPrint: (BookletPrintOption) -> Unit,
    enabled: Boolean
) {
    var showPrintDialog by remember { mutableStateOf(false) }
    var selectedPrintOption by remember { mutableStateOf(BookletPrintOption.DOUBLE_SIDED) }

    Surface(
        tonalElevation = 8.dp,
        shadowElevation = 8.dp,
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Button(
                onClick = onExport,
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp),
                enabled = enabled,
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.tertiary,
                    contentColor = MaterialTheme.colorScheme.onTertiary
                )
            ) {
                Icon(painterResource(id = android.R.drawable.ic_menu_save), contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("生成 PDF", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            Button(
                onClick = { showPrintDialog = true },
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp),
                enabled = enabled,
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.tertiary,
                    contentColor = MaterialTheme.colorScheme.onTertiary
                )
            ) {
                Icon(painterResource(id = android.R.drawable.ic_menu_send), contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("打印", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
    }

    if (showPrintDialog) {
        PrintOptionDialog(
            selectedOption = selectedPrintOption,
            onOptionSelected = { selectedPrintOption = it },
            onConfirm = {
                showPrintDialog = false
                onPrint(selectedPrintOption)
            },
            onDismiss = { showPrintDialog = false }
        )
    }
}

@Composable
fun PrintOptionDialog(
    selectedOption: BookletPrintOption,
    onOptionSelected: (BookletPrintOption) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("打印选项", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                PrintOptionGroup("双面打印", listOf(BookletPrintOption.DOUBLE_SIDED, BookletPrintOption.DOUBLE_SIDED_REVERSE), selectedOption, onOptionSelected)
                Divider()
                PrintOptionGroup("单面：奇数页（正面）", listOf(BookletPrintOption.SINGLE_ODD, BookletPrintOption.SINGLE_ODD_REVERSE), selectedOption, onOptionSelected)
                Divider()
                PrintOptionGroup("单面：偶数页（背面）", listOf(BookletPrintOption.SINGLE_EVEN, BookletPrintOption.SINGLE_EVEN_REVERSE), selectedOption, onOptionSelected)
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) { Text("开始打印") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrintOptionGroup(
    title: String,
    options: List<BookletPrintOption>,
    selected: BookletPrintOption,
    onSelect: (BookletPrintOption) -> Unit
) {
    Column {
        Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { option ->
                ChipOption(
                    selected = selected == option,
                    onClick = { onSelect(option) },
                    label = option.label.substringAfter("(").substringBefore(")"),
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
fun OptionGroupLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompactChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.height(32.dp) // Reduced height for compactness
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall, // Smaller text
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChipOption(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.height(36.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun SettingCard(
    title: String,
    content: @Composable () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            content()
        }
    }
}

// Custom simplified SegmentedButton implementation as Material3 might be experimental/complex to setup perfectly without checking version
@Composable
fun SingleChoiceSegmentedButtonRow(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        content()
    }
}

@Composable
fun RowScope.SegmentedButton(
    selected: Boolean,
    onClick: () -> Unit,
    shape: androidx.compose.ui.graphics.Shape,
    colors: SegmentedButtonColors,
    content: @Composable () -> Unit
) {
    val containerColor = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
    val contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
    val borderColor = MaterialTheme.colorScheme.outline
    val borderWidth = if (selected) 0.dp else 1.dp

    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .clip(shape)
            .border(borderWidth, if (selected) Color.Transparent else borderColor, shape)
            .background(containerColor)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            ProvideTextStyle(value = MaterialTheme.typography.labelLarge) {
                content()
            }
        }
    }
}

data class SegmentedButtonColors(
    val activeContainerColor: Color,
    val activeContentColor: Color
)

object SegmentedButtonDefaults {
    @Composable
    fun colors(
        activeContainerColor: Color,
        activeContentColor: Color
    ) = SegmentedButtonColors(activeContainerColor, activeContentColor)
}

// Keep helper functions for printing logic
// Remove custom shadow modifier to avoid conflict with androidx.compose.ui.draw.shadow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDialog(
    currentTheme: AppTheme,
    onThemeSelected: (AppTheme) -> Unit,
    onShowChangelog: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设置") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // Theme Selection
                Column {
                    Text("颜色风格", style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppTheme.values().forEach { theme ->
                            val selected = theme == currentTheme
                            FilterChip(
                                selected = selected,
                                onClick = { onThemeSelected(theme) },
                                label = { 
                                    Text(when(theme) {
                                        AppTheme.RETRO_PAPER -> "复古纸张"
                                        AppTheme.TECH_DARK -> "科技深色"
                                        AppTheme.MINIMALIST -> "简约风格"
                                    })
                                }
                            )
                        }
                    }
                }
                
                // Update Log
                Button(
                    onClick = onShowChangelog,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("查看更新日志")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

@Composable
fun PreviewImage(bitmap: android.graphics.Bitmap) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(0.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize()
        )
    }
}

fun doBookletPrint(
    context: Context,
    sourceUri: Uri,
    config: BookletConfig,
    option: BookletPrintOption,
    onError: (String) -> Unit
) {
    val coroutineScope = CoroutineScope(Dispatchers.Main)
    coroutineScope.launch {
        try {
            // Map BookletPrintOption to PrintSubset and reverse flag
            val (subset, reverse) = when (option) {
                BookletPrintOption.DOUBLE_SIDED -> PrintSubset.ALL to false
                BookletPrintOption.DOUBLE_SIDED_REVERSE -> PrintSubset.ALL to true
                BookletPrintOption.SINGLE_ODD -> PrintSubset.FRONTS_ONLY to false
                BookletPrintOption.SINGLE_ODD_REVERSE -> PrintSubset.FRONTS_ONLY to true
                BookletPrintOption.SINGLE_EVEN -> PrintSubset.BACKS_ONLY to false
                BookletPrintOption.SINGLE_EVEN_REVERSE -> PrintSubset.BACKS_ONLY to true
            }

            // Create temp file
            val outputDir = context.cacheDir
            val outputFile = File(outputDir, "print_temp.pdf")
            val outputUri = Uri.fromFile(outputFile)
            
            // Generate PDF
            val processor = PdfBookletProcessor(context)
            withContext(Dispatchers.IO) {
                FileOutputStream(outputFile).use { outputStream ->
                    processor.writeBookletPdfToStream(
                        sourceUri = sourceUri,
                        outputStream = outputStream,
                        config = config,
                        subset = subset,
                        reverseOrder = reverse
                    )
                }
            }

            // Print
            val printManager = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
            val printAdapter = object : PrintDocumentAdapter() {
                override fun onLayout(
                    oldAttributes: PrintAttributes?,
                    newAttributes: PrintAttributes?,
                    cancellationSignal: CancellationSignal?,
                    callback: LayoutResultCallback?,
                    extras: Bundle?
                ) {
                    if (cancellationSignal?.isCanceled == true) {
                        callback?.onLayoutCancelled()
                        return
                    }
                    val info = android.print.PrintDocumentInfo.Builder("print_job.pdf")
                        .setContentType(android.print.PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                        .setPageCount(android.print.PrintDocumentInfo.PAGE_COUNT_UNKNOWN)
                        .build()
                    callback?.onLayoutFinished(info, true)
                }

                override fun onWrite(
                    pages: Array<out android.print.PageRange>?,
                    destination: ParcelFileDescriptor?,
                    cancellationSignal: CancellationSignal?,
                    callback: WriteResultCallback?
                ) {
                    if (cancellationSignal?.isCanceled == true) {
                        callback?.onWriteCancelled()
                        return
                    }
                    try {
                        FileInputStream(outputFile).use { input ->
                            FileOutputStream(destination?.fileDescriptor).use { output ->
                                input.copyTo(output)
                            }
                        }
                        callback?.onWriteFinished(arrayOf(android.print.PageRange.ALL_PAGES))
                    } catch (e: Exception) {
                        callback?.onWriteFailed(e.message)
                    }
                }
            }

            printManager.print("PDF Booklet Print", printAdapter, null)

        } catch (e: Exception) {
            onError(e.message ?: "Print failed")
        }
    }
}

