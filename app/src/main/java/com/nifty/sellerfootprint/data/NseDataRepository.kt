package com.nifty.sellerfootprint.data

import com.nifty.sellerfootprint.model.SessionMarketData
import com.nifty.sellerfootprint.model.TempStorageStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

class NseDataRepository(
    private val networkClient: NseReportFetcher,
    private val storageManager: TempStorageManager
) {

    data class TwentyTwoSessionDataset(
        val sessions: List<SessionMarketData>,
        val cachedSessionsUsed: Int,
        val freshlyDownloadedSessions: Int,
        val skippedNonTradingDates: List<LocalDate>
    )

    fun getStorageStats(): TempStorageStats = storageManager.getStorageStats()

    fun deleteTemporaryFiles(): TempStorageManager.DeletionOutcome =
        storageManager.deleteAllTemporaryReports()

    suspend fun fetchTwentyTwoSessionDataset(
        selectedTradingDate: LocalDate,
        onProgress: (String) -> Unit
    ): Result<TwentyTwoSessionDataset> = withContext(Dispatchers.IO) {
        onProgress("Validating target trading date $selectedTradingDate...")
        val targetValidation = TradingCalendarEngine.validateTradingDate(selectedTradingDate)
        if (targetValidation is TradingCalendarEngine.DateValidationResult.Invalid) {
            return@withContext Result.failure(IllegalArgumentException(targetValidation.reason))
        }

        onProgress("Fetching Session 22 (6-stream NSE EOD reports) for $selectedTradingDate...")
        val wasTargetCached = isSessionCached(selectedTradingDate)
        val targetSessionResult = loadOrFetchSingleSession(selectedTradingDate, sessionIndex = 22)
        if (targetSessionResult.isFailure) {
            val reason = targetSessionResult.exceptionOrNull()?.message
                ?: "Required NSE F&O Bhavcopy is missing for $selectedTradingDate."
            return@withContext Result.failure(
                IllegalStateException(
                    "Cannot analyse $selectedTradingDate: $reason " +
                        "(Verify the date is not a market holiday and EOD reports have been published by NSE after 6:00 PM IST)."
                )
            )
        }

        val candidates = TradingCalendarEngine.generateCandidateBackwardSessions(
            endDate = selectedTradingDate.minusDays(1),
            targetCount = 34
        ).reversed()

        val collectedPrior = mutableListOf<SessionMarketData>()
        val skippedDates = mutableListOf<LocalDate>()
        var cachedCount = if (wasTargetCached) 1 else 0
        var downloadedCount = 1 - cachedCount

        for (candidateDate in candidates) {
            if (collectedPrior.size >= 21) break
            onProgress("Loading historical session ${21 - collectedPrior.size}/21 ($candidateDate)...")
            val wasCached = isSessionCached(candidateDate)
            val res = loadOrFetchSingleSession(candidateDate, sessionIndex = 0)
            if (res.isSuccess) {
                collectedPrior.add(res.getOrThrow())
                if (wasCached) cachedCount++ else downloadedCount++
            } else {
                skippedDates.add(candidateDate)
            }
        }

        if (collectedPrior.size < 21) {
            return@withContext Result.failure(
                IllegalStateException(
                    "Incomplete 22-session historical window: retrieved ${collectedPrior.size + 1} of 22 required sessions. " +
                        "Check your internet connection or verify NSE archive availability."
                )
            )
        }

        val chronological = (collectedPrior.reversed() + targetSessionResult.getOrThrow())
            .mapIndexed { idx, session -> session.copy(sessionIndex = idx + 1) }

        Result.success(
            TwentyTwoSessionDataset(
                sessions = chronological,
                cachedSessionsUsed = cachedCount,
                freshlyDownloadedSessions = downloadedCount,
                skippedNonTradingDates = skippedDates
            )
        )
    }

    private fun isSessionCached(date: LocalDate): Boolean {
        return storageManager.readSessionReportIfCached(date, TempStorageManager.BHAVCOPY_FILE) != null
    }

    private fun loadOrFetchSingleSession(
        tradeDate: LocalDate,
        sessionIndex: Int
    ): Result<SessionMarketData> {
        val missingOptional = mutableListOf<String>()

        // Stream 1 & Stream 6: Options + Futures from UDiFF/Legacy F&O Bhavcopy
        val bhavCsv = storageManager.readSessionReportIfCached(tradeDate, TempStorageManager.BHAVCOPY_FILE)
            ?: run {
                val dl = networkClient.downloadUdiffBhavcopyCsv(tradeDate)
                if (dl.isFailure) {
                    return Result.failure(dl.exceptionOrNull()!!)
                }
                val text = dl.getOrThrow()
                storageManager.saveSessionReport(tradeDate, TempStorageManager.BHAVCOPY_FILE, text)
                text
            }

        val bundleResult = NseReportParsers.parseFoBhavcopyBundleCsv(bhavCsv, tradeDate)
        if (bundleResult.isFailure) {
            return Result.failure(bundleResult.exceptionOrNull()!!)
        }
        val bundle = bundleResult.getOrThrow()
        val contracts = bundle.options
        val futures = bundle.futures
        if (futures.isEmpty()) {
            missingOptional.add("NIFTY Futures (Bhavcopy IDF)")
        }

        // Stream 2: Participant-Wise Open Interest
        val participantCsv = storageManager.readSessionReportIfCached(tradeDate, TempStorageManager.PARTICIPANT_OI_FILE)
            ?: networkClient.downloadParticipantOiCsv(tradeDate).getOrNull()?.also {
                storageManager.saveSessionReport(tradeDate, TempStorageManager.PARTICIPANT_OI_FILE, it)
            }
        val participantRecords = if (participantCsv != null) {
            NseReportParsers.parseParticipantOiCsv(participantCsv, tradeDate)
        } else {
            missingOptional.add("Participant-Wise OI")
            emptyList()
        }

        // Stream 5: Participant-Wise Trading Volume
        val participantVolCsv = storageManager.readSessionReportIfCached(tradeDate, TempStorageManager.PARTICIPANT_VOL_FILE)
            ?: networkClient.downloadParticipantVolumeCsv(tradeDate).getOrNull()?.also {
                storageManager.saveSessionReport(tradeDate, TempStorageManager.PARTICIPANT_VOL_FILE, it)
            }
        val participantVolRecords = if (participantVolCsv != null) {
            NseReportParsers.parseParticipantVolumeCsv(participantVolCsv, tradeDate)
        } else {
            missingOptional.add("Participant-Wise Trading Volume")
            emptyList()
        }

        // Stream 3: Daily Index Snapshot
        val indexCsv = storageManager.readSessionReportIfCached(tradeDate, TempStorageManager.INDEX_CLOSE_FILE)
            ?: networkClient.downloadIndexSnapshotCsv(tradeDate).getOrNull()?.also {
                storageManager.saveSessionReport(tradeDate, TempStorageManager.INDEX_CLOSE_FILE, it)
            }
        val indexSnapshot = if (indexCsv != null) {
            NseReportParsers.parseIndexSnapshotCsv(indexCsv, tradeDate)
        } else {
            missingOptional.add("Daily Index Snapshot")
            null
        }

        // Stream 4: F&O Daily Volatility
        val voltCsv = storageManager.readSessionReportIfCached(tradeDate, TempStorageManager.FOVOLT_FILE)
            ?: networkClient.downloadFoVolatilityCsv(tradeDate).getOrNull()?.also {
                storageManager.saveSessionReport(tradeDate, TempStorageManager.FOVOLT_FILE, it)
            }
        val voltRecord = if (voltCsv != null) {
            NseReportParsers.parseFoVolatilityCsv(voltCsv, tradeDate)
        } else {
            missingOptional.add("F&O Daily Volatility")
            null
        }

        val session = SessionMarketData(
            sessionIndex = sessionIndex,
            tradeDate = tradeDate,
            contracts = contracts,
            participantOi = participantRecords,
            indexSnapshot = indexSnapshot,
            volatilityRecord = voltRecord,
            missingOptionalReports = missingOptional,
            participantVolume = participantVolRecords,
            futuresContracts = futures
        )

        if (session.underlyingClose <= 0.0) {
            return Result.failure(
                IllegalStateException("NIFTY closing price missing in Index Snapshot, Options Bhavcopy, and Futures Bhavcopy for $tradeDate.")
            )
        }

        return Result.success(session)
    }
}
