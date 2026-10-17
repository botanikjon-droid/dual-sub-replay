package com.kienhoang.dualsubreplay.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.kienhoang.dualsubreplay.data.LessonLibraryStore
import com.kienhoang.dualsubreplay.data.PrintBlock
import com.kienhoang.dualsubreplay.data.PrintableLesson
import com.kienhoang.dualsubreplay.data.notesPrintBlocks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * The lesson notes as one A4 PDF (pictures, konspekt, terms, quiz and answers), drawn with Android's
 * own PdfDocument, then saved where the doctor chooses or sent to Android's print dialog (which can
 * also "Save as PDF"). Nothing leaves the phone.
 */

private const val PAGE_WIDTH = 595
private const val PAGE_HEIGHT = 842
private const val MARGIN = 42f
private const val CONTENT_WIDTH = PAGE_WIDTH - 2 * MARGIN.toInt()
private const val MAX_PICTURE_HEIGHT = 330
private const val MAX_DECODED_SIDE = 1_400
private const val GAP = 6f

/** Draws [blocks] into an A4 PDF at [target]; pictures come from the notes folders. */
internal fun writeNotesPdf(
    blocks: List<PrintBlock>,
    target: File,
) {
    val document = PdfDocument()
    try {
        PdfPager(document).use { pager -> blocks.forEach { pager.draw(it) } }
        FileOutputStream(target).use(document::writeTo)
    } finally {
        document.close()
    }
}

private class PdfPager(
    private val document: PdfDocument,
) : AutoCloseable {
    private var page: PdfDocument.Page? = null
    private var number = 0
    private var y = MARGIN

    private val body = textPaint(11f, Typeface.NORMAL, Color.BLACK)
    private val heading = textPaint(13f, Typeface.BOLD, Color.rgb(0, 90, 110))
    private val lessonTitle = textPaint(17f, Typeface.BOLD, Color.BLACK)
    private val bookTitle = textPaint(24f, Typeface.BOLD, Color.BLACK)
    private val small = textPaint(9f, Typeface.NORMAL, Color.DKGRAY)

    fun draw(block: PrintBlock) {
        when (block) {
            is PrintBlock.BookTitle -> {
                newPage()
                y = PAGE_HEIGHT / 3f
                text(block.text, bookTitle, Layout.Alignment.ALIGN_CENTER)
                text(block.subtitle, small, Layout.Alignment.ALIGN_CENTER)
            }
            is PrintBlock.LessonTitle -> {
                newPage()
                text(block.text, lessonTitle)
                text(block.detail, small)
                y += GAP
            }
            is PrintBlock.Heading -> {
                y += GAP
                text(block.text, heading, keepWithNext = true)
            }
            is PrintBlock.Paragraph -> text(if (block.bullet) "•  ${block.text}" else block.text, body)
            is PrintBlock.Picture -> picture(block)
        }
    }

    private fun text(
        value: String,
        paint: TextPaint,
        alignment: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL,
        keepWithNext: Boolean = false,
    ) {
        val layout =
            StaticLayout.Builder
                .obtain(value, 0, value.length, paint, CONTENT_WIDTH)
                .setAlignment(alignment)
                .setLineSpacing(0f, 1.15f)
                .build()
        val needed = layout.height + if (keepWithNext) paint.textSize * 3 else 0f
        ensureRoom(needed)
        val canvas = canvas()
        canvas.save()
        canvas.translate(MARGIN, y)
        layout.draw(canvas)
        canvas.restore()
        y += layout.height + GAP / 2
    }

    private fun picture(block: PrintBlock.Picture) {
        val bitmap = LessonLibraryStore.frameFile(block.videoId, block.image)?.let(::decodeScaled) ?: return
        try {
            val scale = minOf(CONTENT_WIDTH.toFloat() / bitmap.width, MAX_PICTURE_HEIGHT.toFloat() / bitmap.height)
            val width = (bitmap.width * scale).toInt()
            val height = (bitmap.height * scale).toInt()
            ensureRoom(height + small.textSize * 3)
            val left = MARGIN.toInt() + (CONTENT_WIDTH - width) / 2
            canvas().drawBitmap(bitmap, null, Rect(left, y.toInt(), left + width, y.toInt() + height), null)
            y += height + GAP / 2
            text(block.caption, small, Layout.Alignment.ALIGN_CENTER)
            y += GAP
        } finally {
            bitmap.recycle()
        }
    }

    private fun ensureRoom(height: Float) {
        if (page == null || y + height > PAGE_HEIGHT - MARGIN) newPage()
    }

    private fun canvas(): Canvas = (page ?: newPage()).canvas

    private fun newPage(): PdfDocument.Page {
        finishPage()
        number++
        val next = document.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, number).create())
        page = next
        y = MARGIN
        return next
    }

    private fun finishPage() {
        val current = page ?: return
        if (number > 1) current.canvas.drawText("$number", PAGE_WIDTH / 2f, PAGE_HEIGHT - MARGIN / 2, small)
        document.finishPage(current)
        page = null
    }

    override fun close() = finishPage()
}

private fun textPaint(
    size: Float,
    style: Int,
    color: Int,
) = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
    textSize = size
    typeface = Typeface.create(Typeface.SANS_SERIF, style)
    this.color = color
}

/** Decodes a frame no larger than needed for print, so a book of many pictures fits in memory. */
private fun decodeScaled(file: File): Bitmap? {
    if (!file.isFile) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_DECODED_SIDE) sample *= 2
    return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
}

/** Builds the PDF of one lesson ([videoId]) or of every lesson with notes (null) in the app's cache. */
internal suspend fun buildNotesPdf(
    context: Context,
    videoId: String?,
): Pair<File, Int> =
    withContext(Dispatchers.IO) {
        val lessons: List<PrintableLesson> =
            LessonLibraryStore.allNotes().filter { videoId == null || it.videoId == videoId }
        check(lessons.isNotEmpty()) { "Konspekt yo‘q. Avval “Konspekt va test” ni yozdiring." }
        val day = SimpleDateFormat("dd.MM.yyyy", Locale.ROOT).format(Date())
        val subtitle = "${lessons.size} ta dars · $day · ${appBrand().name}"
        val folder = File(context.cacheDir, "notes-pdf").apply { mkdirs() }
        val name = if (videoId == null) "UZI-konspektlar.pdf" else "UZI-konspekt-$videoId.pdf"
        val file = File(folder, name)
        writeNotesPdf(notesPrintBlocks(lessons, subtitle), file)
        file to lessons.size
    }

/** Sends the finished PDF to Android's print dialog (printers, or "Save as PDF"). */
internal fun printPdf(
    context: Context,
    file: File,
) {
    val manager = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
    manager.print(
        file.nameWithoutExtension,
        PdfFileAdapter(file),
        PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4).build(),
    )
}

private class PdfFileAdapter(
    private val file: File,
) : PrintDocumentAdapter() {
    override fun onLayout(
        oldAttributes: PrintAttributes?,
        newAttributes: PrintAttributes?,
        cancellationSignal: CancellationSignal?,
        callback: LayoutResultCallback,
        extras: Bundle?,
    ) {
        if (cancellationSignal?.isCanceled == true) {
            callback.onLayoutCancelled()
            return
        }
        val info = PrintDocumentInfo.Builder(file.name).setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).build()
        callback.onLayoutFinished(info, oldAttributes != newAttributes)
    }

    override fun onWrite(
        pages: Array<out PageRange>?,
        destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal?,
        callback: WriteResultCallback,
    ) {
        try {
            FileInputStream(file).use { input -> FileOutputStream(destination.fileDescriptor).use { input.copyTo(it) } }
            callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
        } catch (error: IOException) {
            callback.onWriteFailed(error.message)
        }
    }
}

/** "PDF saqlash" and "Chop etish", for the open lesson or for every lesson's notes together. */
@Composable
internal fun NotesPdfButtons(activeVideoId: String?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<File?>(null) }
    val saver =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
            val file = pending
            pending = null
            if (uri == null || file == null) return@rememberLauncherForActivityResult
            scope.launch {
                message =
                    try {
                        withContext(Dispatchers.IO) {
                            context.contentResolver.openOutputStream(uri, "w")?.use { out -> file.inputStream().use { it.copyTo(out) } }
                        }
                        "PDF saqlandi: ${file.name}"
                    } catch (error: IOException) {
                        "PDF saqlanmadi: ${error.message}"
                    }
            }
        }

    fun run(
        videoId: String?,
        print: Boolean,
    ) {
        message = "PDF tayyorlanmoqda…"
        scope.launch {
            message =
                try {
                    val (file, count) = buildNotesPdf(context, videoId)
                    if (print) {
                        printPdf(context, file)
                    } else {
                        pending = file
                        saver.launch(file.name)
                    }
                    "PDF tayyor: $count ta dars."
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: IllegalStateException) {
                    error.message
                } catch (error: IOException) {
                    "PDF tayyorlanmadi: ${error.message}"
                }
        }
    }

    Text("PDF va chop etish")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(enabled = activeVideoId != null, onClick = { run(activeVideoId, print = false) }) { Text("Shu dars: PDF") }
        OutlinedButton(enabled = activeVideoId != null, onClick = { run(activeVideoId, print = true) }) { Text("Shu dars: chop etish") }
        OutlinedButton(
            modifier = Modifier.testTag("notes_pdf_all"),
            onClick = { run(null, print = false) },
        ) { Text("Barcha konspektlar: PDF") }
        OutlinedButton(modifier = Modifier.testTag("notes_print_all"), onClick = {
            run(null, print = true)
        }) { Text("Barcha konspektlar: chop etish") }
    }
    message?.let { Hint(it) }
}
