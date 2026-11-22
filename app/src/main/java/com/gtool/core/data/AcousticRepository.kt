package com.gtool.core.data

import android.content.Context
import com.gtool.core.acoustic.AcousticAnalyzer
import com.gtool.core.acoustic.AcousticRecord
import com.gtool.core.acoustic.AcousticRecordSummary
import com.gtool.core.acoustic.GuitarBodyShape
import com.gtool.core.acoustic.ModalId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 吉他声学分析记录本地离线仓库：
 * - `filesDir/acoustic/index.json`：轻量索引列表（保证主页秒开）
 * - `filesDir/acoustic/records/{id}.json`：单次分析完整高分辨率 FFT 原始数据与报告
 * - 独立于 DataStore，避免大量频谱数组膨胀设置文件，且覆盖安装时完整保留数据。
 */
class AcousticRepository(context: Context) {

    private val rootDir = File(context.applicationContext.filesDir, "acoustic")
    private val recordsDir = File(rootDir, "records")
    private val indexFile = File(rootDir, "index.json")

    private val mutex = Mutex()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    private val prettyJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    private val _summaries = MutableStateFlow<List<AcousticRecordSummary>>(emptyList())
    val summaries: StateFlow<List<AcousticRecordSummary>> = _summaries.asStateFlow()

    suspend fun refreshIndex() {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                ensureDirs()
                _summaries.value = readIndexUnlocked()
            }
        }
    }

    suspend fun saveRecord(record: AcousticRecord) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                ensureDirs()
                val recFile = recordFile(record.id)
                atomicWrite(recFile, json.encodeToString(AcousticRecord.serializer(), record))

                val current = readIndexUnlocked().filterNot { it.id == record.id }.toMutableList()
                current.add(record.toSummary())
                val sorted = current.sortedByDescending { it.createdAtEpochMs }
                writeIndexUnlocked(sorted)
                _summaries.value = sorted
            }
        }
    }

    suspend fun loadRecord(id: String): AcousticRecord? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val file = recordFile(id)
            if (!file.exists()) return@withLock null
            runCatching {
                json.decodeFromString(AcousticRecord.serializer(), file.readText())
            }.getOrNull()
        }
    }

    suspend fun deleteRecord(id: String) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                ensureDirs()
                val file = recordFile(id)
                if (file.exists()) file.delete()
                val updated = readIndexUnlocked().filterNot { it.id == id }
                writeIndexUnlocked(updated)
                _summaries.value = updated
            }
        }
    }

    suspend fun updateMetadata(
        id: String,
        guitarName: String,
        bodyShape: GuitarBodyShape,
        notes: String,
        a4Hz: Double,
        useFlats: Boolean
    ): AcousticRecord? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val file = recordFile(id)
            if (!file.exists()) return@withLock null
            val existing = runCatching {
                json.decodeFromString(AcousticRecord.serializer(), file.readText())
            }.getOrNull() ?: return@withLock null

            val cleanName = guitarName.trim().ifEmpty { existing.guitarName }
            val recomputed = AcousticAnalyzer.analyze(
                spectra = existing.spectra,
                bodyShape = bodyShape,
                manualOverrides = existing.manualPeakOverrides,
                a4Hz = a4Hz,
                useFlats = useFlats
            )
            val updated = existing.copy(
                guitarName = cleanName,
                bodyShape = bodyShape,
                notes = notes.trim(),
                report = recomputed
            )
            atomicWrite(file, json.encodeToString(AcousticRecord.serializer(), updated))
            val list = readIndexUnlocked().filterNot { it.id == id } + updated.toSummary()
            val sorted = list.sortedByDescending { it.createdAtEpochMs }
            writeIndexUnlocked(sorted)
            _summaries.value = sorted
            updated
        }
    }

    suspend fun updateManualPeakOverrides(
        id: String,
        overrides: Map<ModalId, Double>,
        a4Hz: Double,
        useFlats: Boolean
    ): AcousticRecord? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val file = recordFile(id)
            if (!file.exists()) return@withLock null
            val existing = runCatching {
                json.decodeFromString(AcousticRecord.serializer(), file.readText())
            }.getOrNull() ?: return@withLock null

            val recomputed = AcousticAnalyzer.analyze(
                spectra = existing.spectra,
                bodyShape = existing.bodyShape,
                manualOverrides = overrides,
                a4Hz = a4Hz,
                useFlats = useFlats
            )
            val updated = existing.copy(
                manualPeakOverrides = overrides,
                a4Hz = a4Hz,
                report = recomputed
            )
            atomicWrite(file, json.encodeToString(AcousticRecord.serializer(), updated))
            val list = readIndexUnlocked().filterNot { it.id == id } + updated.toSummary()
            val sorted = list.sortedByDescending { it.createdAtEpochMs }
            writeIndexUnlocked(sorted)
            _summaries.value = sorted
            updated
        }
    }

    fun encodePrettyJson(record: AcousticRecord): String =
        prettyJson.encodeToString(AcousticRecord.serializer(), record)

    private fun ensureDirs() {
        if (!rootDir.exists()) rootDir.mkdirs()
        if (!recordsDir.exists()) recordsDir.mkdirs()
    }

    private fun recordFile(id: String): File = File(recordsDir, "$id.json")

    private fun readIndexUnlocked(): List<AcousticRecordSummary> {
        if (!indexFile.exists()) return emptyList()
        return runCatching {
            json.decodeFromString(SummaryListSerializer, indexFile.readText())
                .sortedByDescending { it.createdAtEpochMs }
        }.getOrElse { emptyList() }
    }

    private fun writeIndexUnlocked(list: List<AcousticRecordSummary>) {
        atomicWrite(indexFile, json.encodeToString(SummaryListSerializer, list))
    }

    private fun atomicWrite(target: File, content: String) {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeText(content, Charsets.UTF_8)
        if (!tmp.renameTo(target)) {
            target.writeText(content, Charsets.UTF_8)
            tmp.delete()
        }
    }

    private companion object {
        val SummaryListSerializer = ListSerializer(AcousticRecordSummary.serializer())
    }
}
