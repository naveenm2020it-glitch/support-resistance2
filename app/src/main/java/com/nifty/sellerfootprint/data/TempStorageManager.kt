package com.nifty.sellerfootprint.data

import com.nifty.sellerfootprint.model.TempStorageStats
import java.io.File
import java.time.LocalDate

/**
 * Manages temporary local NSE EOD report storage strictly inside app-private cache storage.
 * Never touches user preferences, APK files, or external storage, and requires zero permissions.
 */
class TempStorageManager(private val cacheRootDir: File) {

    companion object {
        const val TEMP_FOLDER_NAME = "nse_temp_reports"
        const val BHAVCOPY_FILE = "udiff_fo_bhavcopy.csv"
        const val PARTICIPANT_OI_FILE = "fao_participant_oi.csv"
        const val PARTICIPANT_VOL_FILE = "fao_participant_vol.csv"
        const val INDEX_CLOSE_FILE = "ind_close_all.csv"
        const val FOVOLT_FILE = "fovolt.csv"
    }

    private val baseDir: File
        get() = File(cacheRootDir, TEMP_FOLDER_NAME).apply {
            if (!exists()) mkdirs()
        }

    fun getSessionDirectory(tradeDate: LocalDate): File {
        return File(baseDir, tradeDate.toString()).apply {
            if (!exists()) mkdirs()
        }
    }

    fun saveSessionReport(tradeDate: LocalDate, fileName: String, content: String): File {
        val target = File(getSessionDirectory(tradeDate), fileName)
        target.writeText(content, Charsets.UTF_8)
        return target
    }

    fun readSessionReportIfCached(tradeDate: LocalDate, fileName: String): String? {
        val target = File(File(baseDir, tradeDate.toString()), fileName)
        return if (target.exists() && target.isFile && target.length() > 0L) {
            target.readText(Charsets.UTF_8)
        } else {
            null
        }
    }

    fun getStorageStats(): TempStorageStats {
        val dir = baseDir
        if (!dir.exists()) {
            return TempStorageStats(
                fileCount = 0,
                totalBytes = 0L,
                sessionFoldersCount = 0,
                storagePathDisplay = dir.absolutePath
            )
        }
        var count = 0
        var bytes = 0L
        var folders = 0
        dir.listFiles()?.forEach { child ->
            if (child.isDirectory) {
                folders++
                child.listFiles()?.forEach { file ->
                    if (file.isFile) {
                        count++
                        bytes += file.length()
                    }
                }
            } else if (child.isFile) {
                count++
                bytes += child.length()
            }
        }
        return TempStorageStats(
            fileCount = count,
            totalBytes = bytes,
            sessionFoldersCount = folders,
            storagePathDisplay = "App Cache / $TEMP_FOLDER_NAME"
        )
    }

    data class DeletionOutcome(
        val deletedFiles: Int,
        val freedBytes: Long,
        val success: Boolean,
        val message: String
    )

    /**
     * Deletes ONLY temporary NSE report files inside [TEMP_FOLDER_NAME].
     */
    fun deleteAllTemporaryReports(): DeletionOutcome {
        return try {
            val dir = baseDir
            val statsBefore = getStorageStats()
            if (!dir.exists() || statsBefore.fileCount == 0) {
                return DeletionOutcome(
                    deletedFiles = 0,
                    freedBytes = 0L,
                    success = true,
                    message = "No temporary NSE reports were stored."
                )
            }

            var deletedCount = 0
            var freed = 0L
            dir.listFiles()?.forEach { child ->
                if (child.isDirectory) {
                    child.listFiles()?.forEach { file ->
                        if (file.isFile) {
                            val size = file.length()
                            if (file.delete()) {
                                deletedCount++
                                freed += size
                            }
                        }
                    }
                    child.delete()
                } else if (child.isFile) {
                    val size = child.length()
                    if (child.delete()) {
                        deletedCount++
                        freed += size
                    }
                }
            }

            DeletionOutcome(
                deletedFiles = deletedCount,
                freedBytes = freed,
                success = true,
                message = "Deleted $deletedCount temporary NSE report file(s)."
            )
        } catch (e: Exception) {
            DeletionOutcome(
                deletedFiles = 0,
                freedBytes = 0L,
                success = false,
                message = "Failed to delete temporary reports: ${e.message}"
            )
        }
    }
}
