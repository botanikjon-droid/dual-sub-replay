package com.kienhoang.dualsubreplay.data

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Runs the SHOKALON subtitle harvest on the phone: the catalog's lectures one after another, English captions
 * through the app's own caption client, each kept as an untouched .srt in filesDir/shokalon-subtitles. Progress
 * is saved after every lecture, so closing the app loses nothing; the next run skips what is already saved.
 */
internal object SubtitleHarvester {
    internal data class State(
        val running: Boolean = false,
        val counts: HarvestCounts = HarvestCounts(0, 0, 0, 0),
        val current: String? = null,
        val message: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var job: Job? = null

    /** Reads what earlier runs saved, so the card shows the counts before anything starts. */
    suspend fun refresh(context: Context) {
        if (_state.value.running) return
        val (items, records) = load(context.applicationContext)
        _state.value = _state.value.copy(counts = harvestCounts(items, records))
    }

    fun start(
        context: Context,
        retryNoCaptions: Boolean,
    ) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        job =
            scope.launch {
                _state.value = _state.value.copy(running = true, message = null)
                try {
                    harvest(app, retryNoCaptions)
                } catch (cancel: CancellationException) {
                    _state.value = _state.value.copy(message = "To‘xtatildi. Qayta bosganda shu joydan davom etadi.")
                    throw cancel
                } catch (error: IOException) {
                    Log.w(TAG, "Harvest stopped", error)
                    _state.value = _state.value.copy(message = "Saqlashda xato: ${error.message}")
                } finally {
                    _state.value = _state.value.copy(running = false, current = null)
                }
            }
    }

    fun cancel() {
        job?.cancel()
    }

    /** Packs the saved subtitles, the manifest and a README into the ZIP at [uri]; returns the file count. */
    suspend fun writeZip(
        context: Context,
        uri: Uri,
    ): Int =
        withContext(Dispatchers.IO) {
            val app = context.applicationContext
            val (items, records) = load(app)
            val output = app.contentResolver.openOutputStream(uri) ?: throw IOException("Faylni yozib bo‘lmadi.")
            val counts = harvestCounts(items, records)
            writeHarvestZip(output, items, records, readme(counts)) { name ->
                File(filesFolder(app), name).takeIf { it.isFile }?.readBytes()
            }
        }

    /** The file name offered when the ZIP is saved. */
    fun zipName(): String = "SHOKALON_03_SUBTITLES_ORIGINAL_${SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())}.zip"

    private suspend fun harvest(
        app: Context,
        retryNoCaptions: Boolean,
    ) {
        val (items, saved) = load(app)
        val records = saved.toMutableMap()
        val provider = YouTubeCaptionProvider()
        val folder = filesFolder(app).apply { mkdirs() }
        val recordings = RecordedCaptionStore(File(app.filesDir, RecordedCaptionStore.FOLDER))
        _state.value = _state.value.copy(counts = harvestCounts(items, records))
        items
            .filter { item ->
                val record = records[item.videoId]
                needsHarvest(record, retryNoCaptions) ||
                    (record?.status != HarvestStatus.OK && recordings.completeCues(item.videoId) != null)
            }.forEach { item ->
                _state.value = _state.value.copy(current = item.title.ifBlank { item.videoId })
                records[item.videoId] = fetchOne(provider, folder, item).let { recordedFallback(recordings, folder, item, it) }
                lock.withLock { saveRecords(app, records) }
                _state.value = _state.value.copy(counts = harvestCounts(items, records))
                delay(PAUSE_BETWEEN_LECTURES_MS)
            }
        val counts = harvestCounts(items, records)
        _state.value =
            _state.value.copy(
                message =
                    "Tayyor: ${counts.ok} ta subtitr saqlandi, ${counts.noCaptions} tasida subtitr yo‘q, " +
                        "${counts.failed} tasida xato. Endi «ZIP saqlash» ni bosing.",
            )
    }

    private suspend fun fetchOne(
        provider: YouTubeCaptionProvider,
        folder: File,
        item: HarvestItem,
    ): HarvestRecord =
        try {
            val track = provider.fetch(item.videoId, listOf("en"))
            if (track.cues.isEmpty()) {
                HarvestRecord(item.videoId, HarvestStatus.NO_CAPTIONS, note = "bo‘sh subtitr")
            } else {
                val name = harvestFileName(item, subtitleVariant(track.languageCode, track.isGenerated))
                File(folder, name).writeText(formatSrt(track.cues.toSegments()) + "\n", Charsets.UTF_8)
                HarvestRecord(item.videoId, HarvestStatus.OK, name, track.languageCode, track.isGenerated, track.cues.size)
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: CaptionUnavailableException) {
            val category = captionFailureCategory(error.message)
            val status = if (category == "no-tracks") HarvestStatus.NO_CAPTIONS else HarvestStatus.FAILED
            HarvestRecord(item.videoId, status, note = category)
        } catch (error: IOException) {
            HarvestRecord(item.videoId, HarvestStatus.FAILED, note = "fayl: ${error.javaClass.simpleName}")
        }

    /**
     * A lecture YouTube would not give captions for, but whose captions the user recorded in the app while watching
     * (and chose to use), is saved from that recording; the manifest note says so.
     */
    private fun recordedFallback(
        recordings: RecordedCaptionStore,
        folder: File,
        item: HarvestItem,
        result: HarvestRecord,
    ): HarvestRecord {
        if (result.status == HarvestStatus.OK) return result
        val (language, cues) = recordings.completeCues(item.videoId) ?: return result
        val code = language ?: "en"
        val name = harvestFileName(item, subtitleVariant(code, generated = true))
        File(folder, name).writeText(formatSrt(cues.toSegments()) + "\n", Charsets.UTF_8)
        return HarvestRecord(item.videoId, HarvestStatus.OK, name, code, true, cues.size, note = RECORDED_NOTE)
    }

    private suspend fun load(app: Context): Pair<List<HarvestItem>, Map<String, HarvestRecord>> {
        UziCatalogStore.load(app)
        return withContext(Dispatchers.IO) {
            val ids = app.assets.open(SHOKALON_IDS_ASSET).use { parseShokalonIds(it.readBytes().toString(Charsets.UTF_8)) }
            val items = harvestItems(UziCatalogStore.sections.value.orEmpty(), ids)
            val manifest = File(rootFolder(app), RECORDS_FILE)
            val records = if (manifest.isFile) parseHarvestRecords(manifest.readText(Charsets.UTF_8)) else emptyMap()
            items to records
        }
    }

    private fun saveRecords(
        app: Context,
        records: Map<String, HarvestRecord>,
    ) {
        val root = rootFolder(app).apply { mkdirs() }
        val temporary = File(root, "$RECORDS_FILE.tmp")
        temporary.writeText(encodeHarvestRecords(records.values), Charsets.UTF_8)
        val target = File(root, RECORDS_FILE)
        if (!temporary.renameTo(target)) {
            target.writeText(encodeHarvestRecords(records.values), Charsets.UTF_8)
            temporary.delete()
        }
    }

    private fun readme(counts: HarvestCounts): String =
        """
        SHOKALON — asl inglizcha subtitrlar (03_SUBTITLES_ORIGINAL)
        Yig‘ilgan sana: ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(Date())}

        Mundarijadagi darslar: ${counts.total}
        Subtitr saqlangan: ${counts.ok}
        Subtitr yo‘q: ${counts.noCaptions}
        Xato (keyingi safar qayta uriniladi): ${counts.failed}

        Fayl nomi: SHK_{mavzu kodi nuqtasiz}_{Video ID}_{variant}.srt
          ORIGINAL — muallif yozgan inglizcha subtitr
          ASR-EN   — YouTube avtomatik yaratgan inglizcha subtitr
        manifest.tsv izohida "$RECORDED_NOTE" — YouTube yuklab berishga ruxsat bermagan, video ko‘rilayotganda ekrandan
          ilovada yozib olingan subtitr (vaqtlar taxminan 0,5 soniya aniqlikda).
        Subtitrlar YouTube’dan o‘zgartirilmasdan olingan. manifest.tsv — har bir dars, fayli yoki subtitr yo‘qligi sababi.
        Faqat shaxsiy o‘qish va tarjima uchun; boshqalarga tarqatilmaydi.
        """.trimIndent() + "\n"

    private fun rootFolder(app: Context) = File(app.filesDir, ROOT_FOLDER)

    private fun filesFolder(app: Context) = File(rootFolder(app), "files")

    private const val ROOT_FOLDER = "shokalon-subtitles"
    private const val RECORDS_FILE = "records.tsv"
    private const val RECORDED_NOTE = "ekrandan yozilgan"

    /** A pause between lectures so the run looks like a person opening videos, not a burst. */
    private const val PAUSE_BETWEEN_LECTURES_MS = 1_500L
    private const val TAG = "SubtitleHarvest"
}
