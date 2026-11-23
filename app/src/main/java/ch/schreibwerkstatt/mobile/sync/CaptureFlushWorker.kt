package ch.schreibwerkstatt.mobile.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ch.schreibwerkstatt.mobile.data.repo.FlushOutcome
import ch.schreibwerkstatt.mobile.data.repo.ResearchOutbox
import ch.schreibwerkstatt.mobile.locator
import java.util.concurrent.TimeUnit

/**
 * Nachversand des offline Erfassten: neue Ideen und Ideen-Änderungen
 * ([ch.schreibwerkstatt.mobile.data.repo.IdeenRepository]) sowie Recherche-Fundstücke
 * ([ResearchOutbox]). Einmal-Job mit `NetworkType.CONNECTED`: WorkManager startet ihn,
 * sobald Netz da ist — auch wenn die App inzwischen geschlossen oder das Gerät neu
 * gestartet wurde. Gerade der spontane Einfall oder der geteilte Link im Funkloch soll
 * ankommen, ohne dass die App wieder geöffnet wird.
 *
 * Unabhängig vom Settings-Flag `backgroundSync`: das schaltet nur den periodischen
 * Pull ab, nicht die Zustellung dessen, was der Nutzer selbst angelegt hat.
 */
class CaptureFlushWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val locator = applicationContext.locator
        // Ohne Token wartet die Queue aufs Koppeln (App-Start plant dann neu).
        if (locator.tokenStore.token() == null) return Result.success()
        val ideen = locator.ideenRepository.flushPending()
        val research = locator.researchOutbox.flush()
        return when {
            ideen is FlushOutcome.Unauthorized || research == ResearchOutbox.FlushStop.UNAUTHORIZED -> Result.success()
            ideen is FlushOutcome.RetryLater || research == ResearchOutbox.FlushStop.OFFLINE -> Result.retry()
            else -> Result.success()
        }
    }

    companion object {
        private const val UNIQUE_NAME = "capture-flush"

        /**
         * Nachversand planen. APPEND_OR_REPLACE: läuft gerade ein Durchgang, kommt ein
         * weiterer dahinter — so verpasst ein Lauf, der kurz vor dem Einreihen seine
         * Liste gelesen hat, den neuen Eintrag nicht.
         */
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<CaptureFlushWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request,
            )
        }
    }
}
