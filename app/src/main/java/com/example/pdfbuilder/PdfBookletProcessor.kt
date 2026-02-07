package com.example.pdfbuilder

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.OutputStream
import kotlin.math.roundToInt

enum class PrintSubset {
    ALL,
    FRONTS_ONLY,
    BACKS_ONLY
}

class PdfBookletProcessor(private val context: Context) {

    private fun normalizeRotationDegrees(degrees: Int): Int {
        val d = ((degrees % 360) + 360) % 360
        return when (d) {
            0, 90, 180, 270 -> d
            else -> 0
        }
    }

    private fun rotatedPaperOrientation(config: BookletConfig): PaperOrientation {
        val rot = normalizeRotationDegrees(config.outputRotationDegrees)
        if (rot == 90 || rot == 270) {
            return if (config.paperOrientation == PaperOrientation.LANDSCAPE) {
                PaperOrientation.PORTRAIT
            } else {
                PaperOrientation.LANDSCAPE
            }
        }
        return config.paperOrientation
    }

    suspend fun getPageCount(uri: Uri): Int = withContext(Dispatchers.IO) {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                renderer.pageCount
            }
        } ?: 0
    }

    suspend fun generatePreview(
        sourceUri: Uri,
        config: BookletConfig,
        sheetIndex: Int,
        isBack: Boolean
    ): Bitmap = withContext(Dispatchers.IO) {
        context.contentResolver.openFileDescriptor(sourceUri, "r")?.use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                renderSheetToBitmap(renderer, config, sheetIndex, isBack, previewWidth = 2000)
            }
        } ?: throw IOException("Cannot open PDF")
    }

    suspend fun generateReaderPreview(
        sourceUri: Uri,
        config: BookletConfig,
        spreadIndex: Int
    ): Bitmap = withContext(Dispatchers.IO) {
        context.contentResolver.openFileDescriptor(sourceUri, "r")?.use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                renderReaderSpreadToBitmap(renderer, config, spreadIndex, previewWidth = 2000)
            }
        } ?: throw IOException("Cannot open PDF")
    }

    private fun renderReaderSpreadToBitmap(
        renderer: PdfRenderer,
        config: BookletConfig,
        spreadIndex: Int,
        previewWidth: Int
    ): Bitmap {
        val inputPageCount = renderer.pageCount
        val baseLogicalContentPages = when (config.splitMode) {
            SplitMode.NONE -> inputPageCount
            SplitMode.VERTICAL, SplitMode.HORIZONTAL -> inputPageCount * 2
        }
        val coverBlankCount = if (config.layoutMode == LayoutMode.BOOKLET && config.coverMode == CoverMode.NO_COVER_ADD_BLANK) 1 else 0
        val totalLogicalContentPages = baseLogicalContentPages + coverBlankCount
        val totalSheets = (totalLogicalContentPages + 3) / 4
        val totalBookletPages = totalSheets * 4
        val blankPageCount = totalBookletPages - totalLogicalContentPages

        val totalSpreads = if (config.layoutMode == LayoutMode.BOOKLET) {
            (totalBookletPages / 2) + 1
        } else {
            (totalBookletPages + 1) / 2
        }
        val safeSpreadIndex = spreadIndex.coerceIn(0, totalSpreads - 1)

        val pagesToShow = if (config.layoutMode == LayoutMode.BOOKLET) {
            when (safeSpreadIndex) {
                0 -> listOf(0)
                totalBookletPages / 2 -> listOf(totalBookletPages - 1)
                else -> listOf(2 * safeSpreadIndex - 1, 2 * safeSpreadIndex)
            }
        } else {
            // Normal layout: spread 0 is page 0,1, spread 1 is page 2,3...
            // If user wants cover-like first page for normal layout?
            // Let's assume standard 2-up for normal layout to match "Reader View"
            val start = safeSpreadIndex * 2
            if (start + 1 < totalBookletPages) listOf(start, start + 1) else listOf(start)
        }

        val width = previewWidth
        val isHorizontalLayout = config.splitMode == SplitMode.HORIZONTAL && config.layoutMode != LayoutMode.BOOKLET
        val baseReaderOrientation = if (config.splitMode == SplitMode.HORIZONTAL) {
            PaperOrientation.PORTRAIT
        } else {
            config.paperOrientation
        }
        val readerOrientation = run {
            val rot = normalizeRotationDegrees(config.outputRotationDegrees)
            if (rot == 90 || rot == 270) {
                if (baseReaderOrientation == PaperOrientation.LANDSCAPE) PaperOrientation.PORTRAIT else PaperOrientation.LANDSCAPE
            } else {
                baseReaderOrientation
            }
        }
        val height = if (readerOrientation == PaperOrientation.LANDSCAPE) (width / 1.414f).roundToInt() else (width * 1.414f).roundToInt()
        // Note: For reader view we just use standard ratio for now, or we could also use config.paperSize logic but reader view is conceptual.


        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.LTGRAY)

        val paperPaint = Paint().apply { color = Color.WHITE }
        val linePaint = Paint().apply {
            color = Color.DKGRAY
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }

        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paperPaint)

        val rotation = normalizeRotationDegrees(config.outputRotationDegrees)
        val targetW = width.toFloat()
        val targetH = height.toFloat()
        val baseW = if (rotation == 90 || rotation == 270) targetH else targetW
        val baseH = if (rotation == 90 || rotation == 270) targetW else targetH
        canvas.save()
        when (rotation) {
            90 -> {
                canvas.translate(targetW, 0f)
                canvas.rotate(90f)
            }
            180 -> {
                canvas.translate(targetW, targetH)
                canvas.rotate(180f)
            }
            270 -> {
                canvas.translate(0f, targetH)
                canvas.rotate(270f)
            }
        }

        if (config.splitMode == SplitMode.HORIZONTAL) {
            val halfHeight = baseH / 2f
            if (pagesToShow.size == 1) {
                val destRect = if (config.layoutMode == LayoutMode.BOOKLET && safeSpreadIndex == 0) {
                    RectF(0f, halfHeight, baseW, baseH)
                } else {
                    RectF(0f, 0f, baseW, halfHeight)
                }
                drawPage(
                    canvas,
                    renderer,
                    pagesToShow[0],
                    destRect,
                    config,
                    isLeft = false,
                    // isBack is not used in drawPage logic anymore, so value doesn't matter
                    // but we removed the parameter from signature
                    blankPageCount = blankPageCount,
                    totalLogicalContentPages = totalLogicalContentPages
                )
                canvas.drawLine(0f, halfHeight, baseW, halfHeight, linePaint)
            } else {
                drawPage(
                    canvas,
                    renderer,
                    pagesToShow[0],
                    RectF(0f, 0f, baseW, halfHeight),
                    config,
                    isLeft = true,
                    blankPageCount = blankPageCount,
                    totalLogicalContentPages = totalLogicalContentPages
                )
                drawPage(
                    canvas,
                    renderer,
                    pagesToShow[1],
                    RectF(0f, halfHeight, baseW, baseH),
                    config,
                    isLeft = false,
                    blankPageCount = blankPageCount,
                    totalLogicalContentPages = totalLogicalContentPages
                )
                canvas.drawLine(0f, halfHeight, baseW, halfHeight, linePaint)
            }
        } else {
            val halfWidth = baseW / 2f
            if (pagesToShow.size == 1) {
                val destRect = if (config.layoutMode == LayoutMode.BOOKLET && safeSpreadIndex == 0) {
                    RectF(halfWidth, 0f, baseW, baseH)
                } else {
                    RectF(0f, 0f, halfWidth, baseH)
                }
                drawPage(
                    canvas,
                    renderer,
                    pagesToShow[0],
                    destRect,
                    config,
                    isLeft = false,
                    blankPageCount = blankPageCount,
                    totalLogicalContentPages = totalLogicalContentPages
                )
            } else {
                drawPage(
                    canvas,
                    renderer,
                    pagesToShow[0],
                    RectF(0f, 0f, halfWidth, baseH),
                    config,
                    isLeft = true,
                    blankPageCount = blankPageCount,
                    totalLogicalContentPages = totalLogicalContentPages
                )
                drawPage(
                    canvas,
                    renderer,
                    pagesToShow[1],
                    RectF(halfWidth, 0f, baseW, baseH),
                    config,
                    isLeft = false,
                    blankPageCount = blankPageCount,
                    totalLogicalContentPages = totalLogicalContentPages
                )
                canvas.drawLine(halfWidth, 0f, halfWidth, baseH, linePaint)
            }
        }

        canvas.restore()

        return bitmap
    }

    suspend fun generateBookletPdf(
        sourceUri: Uri,
        outputUri: Uri,
        config: BookletConfig,
        onProgress: (Float) -> Unit
    ) = withContext(Dispatchers.IO) {
        val inputPfd = context.contentResolver.openFileDescriptor(sourceUri, "r")
            ?: throw IOException("Cannot open input PDF")
        val outputStream = context.contentResolver.openOutputStream(outputUri)
            ?: throw IOException("Cannot open output PDF")

        val document = PdfDocument()
        
        try {
            val renderer = PdfRenderer(inputPfd)
            val inputPageCount = renderer.pageCount
            
            val baseLogicalPages = when (config.splitMode) {
                SplitMode.NONE -> inputPageCount
                SplitMode.VERTICAL, SplitMode.HORIZONTAL -> inputPageCount * 2
            }
            val coverBlankCount = if (config.layoutMode == LayoutMode.BOOKLET && config.coverMode == CoverMode.NO_COVER_ADD_BLANK) 1 else 0
            val totalLogicalPages = baseLogicalPages + coverBlankCount
            
            val totalSheets = (totalLogicalPages + 3) / 4
            
            val pointsPerMm = 72f / 25.4f
            
            // Standard A4
            val a4WidthMm = 210f
            val a4HeightMm = 297f
            
            val widthPoints = (a4WidthMm * pointsPerMm).roundToInt()
            val heightPoints = (a4HeightMm * pointsPerMm).roundToInt()

            val basePageWidth = if (config.paperOrientation == PaperOrientation.LANDSCAPE) heightPoints else widthPoints
            val basePageHeight = if (config.paperOrientation == PaperOrientation.LANDSCAPE) widthPoints else heightPoints
            
            val rotation = normalizeRotationDegrees(config.outputRotationDegrees)
            val pageWidth = if (rotation == 90 || rotation == 270) basePageHeight else basePageWidth
            val pageHeight = if (rotation == 90 || rotation == 270) basePageWidth else basePageHeight
            
            val sheetsToProcess = when (config.printType) {
                PrintType.DOUBLE_SIDED -> {
                    // Sequence: S0F, S0B, S1F, S1B...
                    (0 until totalSheets).flatMap { i -> 
                        listOf(
                            Triple(i, false, "Sheet ${i+1} Front"),
                            Triple(i, true, "Sheet ${i+1} Back")
                        ) 
                    }
                }
                PrintType.SINGLE_SIDED -> {
                    // Sequence: Fronts (0..N), then Backs (0..N)
                    val fronts = (0 until totalSheets).map { i -> Triple(i, false, "Sheet ${i+1} Front") }
                    val backs = (0 until totalSheets).map { i -> Triple(i, true, "Sheet ${i+1} Back") }
                    fronts + backs
                }
            }
            
            val totalSteps = sheetsToProcess.size
            
            sheetsToProcess.forEachIndexed { index, (sheetIdx, isBack, _) ->
                val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, index + 1).create()
                val page = document.startPage(pageInfo)
                val canvas = page.canvas
                
                // Draw white background
                canvas.drawColor(Color.WHITE)
                
                drawSheetOnCanvas(
                    canvas, 
                    renderer, 
                    config, 
                    sheetIdx, 
                    isBack, 
                    pageWidth.toFloat(), 
                    pageHeight.toFloat(),
                    renderDensity = 4.5f // High quality for export
                )
                
                document.finishPage(page)
                onProgress((index + 1).toFloat() / totalSteps)
            }
            
            document.writeTo(outputStream)
            renderer.close()
        } finally {
            document.close()
            outputStream.close()
            inputPfd.close()
        }
    }

    suspend fun writeBookletPdfToStream(
        sourceUri: Uri,
        outputStream: OutputStream,
        config: BookletConfig,
        subset: PrintSubset,
        reverseOrder: Boolean = false,
        renderDensity: Float = 2.0f
    ) = withContext(Dispatchers.IO) {
        val inputPfd = context.contentResolver.openFileDescriptor(sourceUri, "r")
            ?: throw IOException("Cannot open input PDF")

        val document = PdfDocument()

        try {
            val renderer = PdfRenderer(inputPfd)
            val inputPageCount = renderer.pageCount

            val baseLogicalPages = when (config.splitMode) {
                SplitMode.NONE -> inputPageCount
                SplitMode.VERTICAL, SplitMode.HORIZONTAL -> inputPageCount * 2
            }
            val coverBlankCount = if (config.layoutMode == LayoutMode.BOOKLET && config.coverMode == CoverMode.NO_COVER_ADD_BLANK) 1 else 0
            val totalLogicalPages = baseLogicalPages + coverBlankCount

            val totalSheets = (totalLogicalPages + 3) / 4

            // Standard A4 Landscape size in points (1/72 inch)
            // A4: 595 x 842 points. Landscape: 842 x 595.
            val basePageWidth = if (config.paperOrientation == PaperOrientation.LANDSCAPE) 842 else 595
            val basePageHeight = if (config.paperOrientation == PaperOrientation.LANDSCAPE) 595 else 842
            val rotation = normalizeRotationDegrees(config.outputRotationDegrees)
            val pageWidth = if (rotation == 90 || rotation == 270) basePageHeight else basePageWidth
            val pageHeight = if (rotation == 90 || rotation == 270) basePageWidth else basePageHeight

            var pageNumber = 1
            val sheetRange = if (reverseOrder) (totalSheets - 1 downTo 0) else (0 until totalSheets)
            
            for (sheetIdx in sheetRange) {
                // When reversing sheet order, we still print Front then Back of each sheet
                val sides = listOf(false, true)
                for (isBack in sides) {
                    val include = when (subset) {
                        PrintSubset.ALL -> true
                        PrintSubset.FRONTS_ONLY -> !isBack
                        PrintSubset.BACKS_ONLY -> isBack
                    }
                    if (!include) continue

                    val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
                    val page = document.startPage(pageInfo)
                    val canvas = page.canvas
                    canvas.drawColor(Color.WHITE)

                    drawSheetOnCanvas(
                        canvas,
                        renderer,
                        config,
                        sheetIdx,
                        isBack,
                        pageWidth.toFloat(),
                        pageHeight.toFloat(),
                        renderDensity = renderDensity
                    )

                    document.finishPage(page)
                    pageNumber++
                }
            }

            document.writeTo(outputStream)
            outputStream.flush()
            renderer.close()
        } finally {
            document.close()
            inputPfd.close()
        }
    }

    private fun renderSheetToBitmap(
        renderer: PdfRenderer,
        config: BookletConfig,
        sheetIndex: Int,
        isBack: Boolean,
        previewWidth: Int
    ): Bitmap {
        // Aspect ratio of A4 Landscape is sqrt(2) ~ 1.414
        val width = previewWidth
        val previewOrientation = rotatedPaperOrientation(config)
        val height = if (previewOrientation == PaperOrientation.LANDSCAPE)
            (width / 1.414f).roundToInt() 
        else 
            (width * 1.414f).roundToInt()
            
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.LTGRAY) // Background for preview
        
        // Draw a white "paper" rect
        val paperPaint = Paint().apply { color = Color.WHITE }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paperPaint)
        
        drawSheetOnCanvas(
            canvas,
            renderer,
            config,
            sheetIndex,
            isBack,
            width.toFloat(),
            height.toFloat()
        )
        
        return bitmap
    }

    private fun drawSheetOnCanvas(
        canvas: Canvas,
        renderer: PdfRenderer,
        config: BookletConfig,
        sheetIndex: Int,
        isBack: Boolean,
        sheetWidth: Float,
        sheetHeight: Float,
        renderDensity: Float = 2.0f
    ) {
        val rotation = normalizeRotationDegrees(config.outputRotationDegrees)
        val targetW = sheetWidth
        val targetH = sheetHeight
        val baseW = if (rotation == 90 || rotation == 270) targetH else targetW
        val baseH = if (rotation == 90 || rotation == 270) targetW else targetH

        canvas.save()
        when (rotation) {
            90 -> {
                canvas.translate(targetW, 0f)
                canvas.rotate(90f)
            }
            180 -> {
                canvas.translate(targetW, targetH)
                canvas.rotate(180f)
            }
            270 -> {
                canvas.translate(0f, targetH)
                canvas.rotate(270f)
            }
        }

        drawSheetOnCanvasUnrotated(canvas, renderer, config, sheetIndex, isBack, baseW, baseH, renderDensity)
        canvas.restore()
    }

    private fun drawSheetOnCanvasUnrotated(
        canvas: Canvas,
        renderer: PdfRenderer,
        config: BookletConfig,
        sheetIndex: Int,
        isBack: Boolean,
        sheetWidth: Float,
        sheetHeight: Float,
        renderDensity: Float
    ) {
        val inputPageCount = renderer.pageCount
        val baseLogicalContentPages = when (config.splitMode) {
            SplitMode.NONE -> inputPageCount
            SplitMode.VERTICAL, SplitMode.HORIZONTAL -> inputPageCount * 2
        }
        val coverBlankCount = if (config.layoutMode == LayoutMode.BOOKLET && config.coverMode == CoverMode.NO_COVER_ADD_BLANK) 1 else 0
        val totalLogicalContentPages = baseLogicalContentPages + coverBlankCount

        val totalSheets = (totalLogicalContentPages + 3) / 4
        val totalBookletPages = totalSheets * 4
        val blankPageCount = totalBookletPages - totalLogicalContentPages

        val (leftIdx, rightIdx) = if (config.layoutMode == LayoutMode.BOOKLET) {
            // Saddle-stitch reordering
            val N = totalBookletPages
            val i = sheetIndex + 1
            if (!isBack) {
                // Front: Left = N + 1 - (2i - 1), Right = 2i - 1
                (N + 1 - (2 * i - 1) - 1) to (2 * i - 1 - 1)
            } else {
                // Back: Left = 2i, Right = N + 1 - 2i
                (2 * i - 1) to (N + 1 - 2 * i - 1)
            }
        } else {
            // Normal sequential reordering
            // Sheet 0 Front: 0, 1; Sheet 0 Back: 2, 3...
            val base = sheetIndex * 4
            if (!isBack) {
                base to (base + 1)
            } else {
                (base + 2) to (base + 3)
            }
        }
        
        if (config.splitMode == SplitMode.HORIZONTAL) {
            val halfHeight = sheetHeight / 2f
            
            // Determine Top and Bottom pages
            // For Booklet Front side, we swap so Page 1 is on Top, Page N is on Bottom.
            // For Booklet Back side, we keep Page 2 on Top, Page N-1 on Bottom.
            // For Normal mode, sequence is Top -> Bottom.
            
            val (topPageIdx, bottomPageIdx) = if (config.layoutMode == LayoutMode.BOOKLET && !isBack) {
                rightIdx to leftIdx // Swap for Front
            } else {
                leftIdx to rightIdx // Normal for Back or Non-Booklet
            }

            drawPage(
                canvas,
                renderer,
                topPageIdx,
                RectF(0f, 0f, sheetWidth, halfHeight),
                config,
                isLeft = true,
                blankPageCount = blankPageCount,
                totalLogicalContentPages = totalLogicalContentPages,
                renderDensity = renderDensity
            )

            drawPage(
                canvas,
                renderer,
                bottomPageIdx,
                RectF(0f, halfHeight, sheetWidth, sheetHeight),
                config,
                isLeft = false,
                blankPageCount = blankPageCount,
                totalLogicalContentPages = totalLogicalContentPages,
                renderDensity = renderDensity
            )
        } else {
            val halfWidth = sheetWidth / 2f

            drawPage(
                canvas,
                renderer,
                leftIdx,
                RectF(0f, 0f, halfWidth, sheetHeight),
                config,
                isLeft = true,
                blankPageCount = blankPageCount,
                totalLogicalContentPages = totalLogicalContentPages,
                renderDensity = renderDensity
            )

            drawPage(
                canvas,
                renderer,
                rightIdx,
                RectF(halfWidth, 0f, sheetWidth, sheetHeight),
                config,
                isLeft = false,
                blankPageCount = blankPageCount,
                totalLogicalContentPages = totalLogicalContentPages,
                renderDensity = renderDensity
            )
        }
    }

    private fun isLikelySpreadPdf(renderer: PdfRenderer): Boolean {
        if (renderer.pageCount <= 0) return false
        val page = renderer.openPage(0)
        val w = page.width
        val h = page.height
        page.close()
        return w > h
    }

    private fun drawPage(
        canvas: Canvas,
        renderer: PdfRenderer,
        logicalPageIndex: Int,
        destRect: RectF,
        config: BookletConfig,
        isLeft: Boolean,
        blankPageCount: Int,
        totalLogicalContentPages: Int,
        renderDensity: Float = 2.0f
    ) {
        if (config.coverMode == CoverMode.NO_COVER_ADD_BLANK && logicalPageIndex == 0) {
            return
        }

        val totalSheets = (totalLogicalContentPages + 3) / 4
        val totalBookletPages = totalSheets * 4

        val realLogicalIndex = if (config.layoutMode == LayoutMode.BOOKLET) {
            // New logic: "BEFORE_BACK_COVER"
            // We want to preserve the Last Logical Page (Back Cover) at the very end of the booklet.
            // All blank pages should be inserted immediately before the Back Cover.
            
            // Total available slots in booklet: 0 to totalBookletPages - 1
            // Total content pages to place: totalLogicalContentPages
            // Content indices: 0 to totalLogicalContentPages - 1
            // Blank count: blankPageCount
            
            // Mapping:
            // Content[0] -> Logical[0]
            // ...
            // Content[totalLogicalContentPages - 2] -> Logical[totalLogicalContentPages - 2]
            // BLANKS -> Logical[totalLogicalContentPages - 1] to Logical[totalLogicalContentPages - 1 + blankPageCount - 1]
            // Content[totalLogicalContentPages - 1] (Back Cover) -> Logical[totalBookletPages - 1]
            
            if (logicalPageIndex < totalLogicalContentPages - 1) {
                // Front part of content
                logicalPageIndex
            } else if (logicalPageIndex == totalBookletPages - 1) {
                // The very last page of the booklet -> The very last page of content
                totalLogicalContentPages - 1
            } else {
                // In between -> Blank
                -1
            }
        } else {
            // Normal layout: simple sequential
            logicalPageIndex
        }

        if (realLogicalIndex < 0 || realLogicalIndex >= totalLogicalContentPages) {
            // Blank page
            return
        }

        // Adjust index for source mapping if we added a blank cover
        val adjustedLogicalIndex = if (config.layoutMode == LayoutMode.BOOKLET && config.coverMode == CoverMode.NO_COVER_ADD_BLANK) {
            realLogicalIndex - 1
        } else {
            realLogicalIndex
        }

        // Map logical content page to source page and crop area
        val (sourcePageIndex, cropRectFunc) = when (config.splitMode) {
            SplitMode.NONE -> {
                // In "No Split" mode, we always use 1-to-1 mapping.
                adjustedLogicalIndex to { w: Int, h: Int -> Rect(0, 0, w, h) }
            }
            SplitMode.VERTICAL -> {
                val contentPageNumber = adjustedLogicalIndex + 1
                val contentPageCount = totalLogicalContentPages - (if (config.layoutMode == LayoutMode.BOOKLET && config.coverMode == CoverMode.NO_COVER_ADD_BLANK) 1 else 0)

                if (config.layoutMode == LayoutMode.BOOKLET && config.coverMode == CoverMode.HAS_COVER) {
                    val sourceIdx = when (contentPageNumber) {
                        1, contentPageCount -> 0
                        else -> 1 + (contentPageNumber - 2) / 2
                    }

                    val useLeftHalf = when (contentPageNumber) {
                        1 -> false
                        contentPageCount -> true
                        else -> contentPageNumber % 2 == 0
                    }

                    sourceIdx to { w: Int, h: Int ->
                        val splitX = (w * 0.5f).roundToInt().coerceIn(1, w - 1)
                        if (useLeftHalf) Rect(0, 0, splitX, h) else Rect(splitX, 0, w, h)
                    }
                } else {
                    val sourceIdx = adjustedLogicalIndex / 2
                    val useLeftHalf = adjustedLogicalIndex % 2 == 0
                    sourceIdx to { w: Int, h: Int ->
                        val splitX = (w * 0.5f).roundToInt().coerceIn(1, w - 1)
                        if (useLeftHalf) Rect(0, 0, splitX, h) else Rect(splitX, 0, w, h)
                    }
                }
            }
            SplitMode.HORIZONTAL -> {
                val contentPageNumber = adjustedLogicalIndex + 1
                val contentPageCount = totalLogicalContentPages - (if (config.layoutMode == LayoutMode.BOOKLET && config.coverMode == CoverMode.NO_COVER_ADD_BLANK) 1 else 0)

                if (config.layoutMode == LayoutMode.BOOKLET && config.coverMode == CoverMode.HAS_COVER) {
                    val sourceIdx = when (contentPageNumber) {
                        1, contentPageCount -> 0
                        else -> 1 + (contentPageNumber - 2) / 2
                    }

                    val useTopHalf = when (contentPageNumber) {
                        1 -> false
                        contentPageCount -> true
                        else -> contentPageNumber % 2 == 0
                    }

                    sourceIdx to { w: Int, h: Int ->
                        val splitY = (h * 0.5f).roundToInt().coerceIn(1, h - 1)
                        if (useTopHalf) Rect(0, 0, w, splitY) else Rect(0, splitY, w, h)
                    }
                } else {
                    val sourceIdx = adjustedLogicalIndex / 2
                    val useTopHalf = adjustedLogicalIndex % 2 == 0
                    sourceIdx to { w: Int, h: Int ->
                        val splitY = (h * 0.5f).roundToInt().coerceIn(1, h - 1)
                        if (useTopHalf) Rect(0, 0, w, splitY) else Rect(0, splitY, w, h)
                    }
                }
            }
        }

        if (sourcePageIndex < 0 || sourcePageIndex >= renderer.pageCount) return

        val page = renderer.openPage(sourcePageIndex)
        
        // Calculate margins
        val sheetWidthMm = if (config.paperOrientation == PaperOrientation.LANDSCAPE) 297f else 210f
        val sheetHeightMm = if (config.paperOrientation == PaperOrientation.LANDSCAPE) 210f else 297f
        val halfSheetMm = sheetWidthMm / 2f
        
        val pointsPerMm = if (config.splitMode == SplitMode.HORIZONTAL) {
            val halfSheetHeightMm = sheetHeightMm / 2f
            destRect.height() / halfSheetHeightMm
        } else {
            destRect.width() / halfSheetMm
        }
        
        val innerM = config.innerMarginMm * pointsPerMm
        val outerM = 0f
        
        val contentRect = RectF(destRect)

        if (config.splitMode == SplitMode.HORIZONTAL) {
            if (isLeft) {
                contentRect.bottom -= innerM
            } else {
                contentRect.top += innerM
            }
        } else {
            if (isLeft) {
                // Left Page: Binding (Inner) is on Right. Cutting (Outer) is on Left.
                contentRect.right -= innerM
                contentRect.left += outerM
                contentRect.top += outerM
                contentRect.bottom -= outerM
            } else {
                // Right Page: Binding (Inner) is on Left. Cutting (Outer) is on Right.
                contentRect.left += innerM
                contentRect.right -= outerM
                contentRect.top += outerM
                contentRect.bottom -= outerM
            }
        }
        
        val density = renderDensity // Scale factor for quality
        
        val bitmapW = (contentRect.width() * density).toInt().coerceAtLeast(1)
        val bitmapH = (contentRect.height() * density).toInt().coerceAtLeast(1)
        
        val bitmap = Bitmap.createBitmap(bitmapW, bitmapH, Bitmap.Config.ARGB_8888)
        val bitmapCanvas = Canvas(bitmap)
        bitmapCanvas.drawColor(Color.WHITE)
        
        // Get Crop Rect
        val cropRect = cropRectFunc(page.width, page.height)
        
        // Render PDF page into this bitmap
        val cropWidth = cropRect.width()
        val cropHeight = cropRect.height()

        // To avoid overlap and gaps, we must ensure the content perfectly fills the split dimension.
        // For vertical split, width must match. For horizontal split, height must match.
        // UPDATE: User requested "No Crop" + "Seamless Split".
        // Strategy: Scale to fit (maintain aspect ratio) and align content to the split line.
        
        val scaleX = bitmapW.toFloat() / cropWidth
        val scaleY = bitmapH.toFloat() / cropHeight
        
        // Optimize scale strategy:
        // Always use "Fit Inside" (min scale) to ensure NO content is cropped.
        // The alignment logic (tx/ty) below ensures that if there is extra space,
        // the content is pushed towards the binding edge (split line) to minimize the gap in the middle.
        val scale = scaleX.coerceAtMost(scaleY)

        val tx: Float
        val ty: Float

        if (config.splitMode == SplitMode.HORIZONTAL) {
            // Horizontal Split (Top/Bottom)
            // isLeft=true maps to Top Half (needs Bottom Align to hit split line)
            // isLeft=false maps to Bottom Half (needs Top Align to hit split line)
            
            // Horizontal alignment: Center
            tx = (bitmapW - cropWidth * scale) / 2f
            
            if (isLeft) {
                // Top Half: Align Bottom (ty > 0)
                ty = bitmapH - cropHeight * scale
            } else {
                // Bottom Half: Align Top (ty = 0)
                ty = 0f
            }
        } else if (config.splitMode == SplitMode.VERTICAL) {
            // Vertical Split (Left/Right)
            // isLeft=true maps to Left Half (needs Right Align to hit split line)
            // isLeft=false maps to Right Half (needs Left Align to hit split line)
            
            // Vertical alignment: Center
            ty = (bitmapH - cropHeight * scale) / 2f
            
            if (isLeft) {
                // Left Half: Align Right (tx > 0)
                tx = bitmapW - cropWidth * scale
            } else {
                // Right Half: Align Left (tx = 0)
                tx = 0f
            }
        } else {
            // Normal mode, use standard fit center
            tx = (bitmapW - cropWidth * scale) / 2f
            ty = (bitmapH - cropHeight * scale) / 2f
        }

        val matrix = Matrix()
        matrix.postTranslate(-cropRect.left.toFloat(), -cropRect.top.toFloat())
        matrix.postScale(scale, scale)
        matrix.postTranslate(tx, ty)

        page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
        page.close()

        // Use a Paint with filtering to avoid jagged edges
        val paint = Paint().apply {
            isAntiAlias = true
            isFilterBitmap = true
        }

        canvas.drawBitmap(bitmap, null, contentRect, paint)
        bitmap.recycle()
    }
}
