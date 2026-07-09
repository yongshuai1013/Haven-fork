package sh.haven.app.backup

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import sh.haven.core.data.backup.BackupSyncManager
import sh.haven.core.data.db.entities.ConnectionLog
import sh.haven.core.data.preferences.UserPreferencesRepository
import sh.haven.core.data.repository.ConnectionLogRepository
import sh.haven.core.data.repository.ConnectionRepository
import sh.haven.core.data.repository.SshKeyRepository
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Automatic backup push to the configured remote (#359, layers on #323's
 * manual Push/Pull).
 *
 * Push-only by design: automatic *pull* could silently overwrite local
 * changes (the remote copy is last-write-wins), so restore stays a manual,
 * eyes-open action. The passphrase that encrypts each push is the one the
 * user stored when enabling auto-sync (see
 * [UserPreferencesRepository.setBackupAutoSync]).
 *
 * Debounce comes free from WorkManager: each config change enqueues unique
 * work with a fresh [DEBOUNCE_MINUTES] initial delay and REPLACE policy, so
 * a burst of edits collapses into one push after the burst ends. A daily
 * periodic push catches anything the change triggers miss.
 */
@HiltWorker
class BackupAutoSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val preferencesRepository: UserPreferencesRepository,
    private val backupSyncManager: BackupSyncManager,
    private val connectionLogRepository: ConnectionLogRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        // Re-check everything at run time — the toggle or destination may have
        // changed between enqueue and execution. Missing pieces = benign no-op.
        if (!preferencesRepository.backupAutoSyncEnabled.first()) return Result.success()
        val profileId = preferencesRepository.backupSyncProfileId.first() ?: return Result.success()
        val passphrase = preferencesRepository.backupSyncPassphrase() ?: return Result.success()
        val path = preferencesRepository.backupSyncPath.first()
        return try {
            val started = System.currentTimeMillis()
            backupSyncManager.push(profileId, path, passphrase)
            connectionLogRepository.logEvent(
                profileId, ConnectionLog.Status.SYNC_OK,
                durationMs = System.currentTimeMillis() - started,
                details = "Auto-push backup → $path",
            )
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "auto-push failed (attempt $runAttemptCount)", e)
            if (runAttemptCount >= MAX_ATTEMPTS) {
                // Surface the give-up in the audit log instead of failing silently;
                // the daily periodic push will try again with a fresh attempt count.
                connectionLogRepository.logEvent(
                    profileId, ConnectionLog.Status.SYNC_FAILED,
                    details = "Auto-push backup failed: ${e.message ?: e.javaClass.simpleName}",
                )
                Result.failure()
            } else Result.retry()
        }
    }

    companion object {
        private const val TAG = "BackupAutoSync"
        private const val UNIQUE_PUSH = "backup-auto-sync-push"
        private const val UNIQUE_PERIODIC = "backup-auto-sync-periodic"
        private const val DEBOUNCE_MINUTES = 2L
        private const val MAX_ATTEMPTS = 5

        private val networked = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        /** Debounced change-push: REPLACE restarts the delay on every call. */
        fun enqueueDebounced(context: Context) {
            val request = OneTimeWorkRequestBuilder<BackupAutoSyncWorker>()
                .setInitialDelay(DEBOUNCE_MINUTES, TimeUnit.MINUTES)
                .setConstraints(networked)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_PUSH, ExistingWorkPolicy.REPLACE, request)
        }

        /** Daily catch-up push. Idempotent (KEEP). */
        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<BackupAutoSyncWorker>(24, TimeUnit.HOURS)
                .setConstraints(networked)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancelAll(context: Context) {
            WorkManager.getInstance(context).apply {
                cancelUniqueWork(UNIQUE_PUSH)
                cancelUniqueWork(UNIQUE_PERIODIC)
            }
        }
    }
}

/**
 * Watches for config changes while auto-sync is on and enqueues the debounced
 * push. Change sources are the two Room tables plus the preferences DataStore
 * — together they cover the bulk of what [sh.haven.core.data.backup.BackupService]
 * exports; the daily periodic push covers any remainder. Started once from
 * `HavenApp.onCreate`; does nothing until the user enables auto-sync.
 */
@Singleton
class BackupAutoSyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferencesRepository: UserPreferencesRepository,
    private val connectionRepository: ConnectionRepository,
    private val sshKeyRepository: SshKeyRepository,
) {
    fun start(scope: CoroutineScope) {
        scope.launch {
            var previous: Boolean? = null
            preferencesRepository.backupAutoSyncEnabled.distinctUntilChanged().collectLatest { enabled ->
                val justEnabled = previous == false && enabled
                previous = enabled
                if (!enabled) {
                    BackupAutoSyncWorker.cancelAll(context)
                    return@collectLatest
                }
                BackupAutoSyncWorker.schedulePeriodic(context)
                // Baseline push right after the user turns the switch on, so the
                // remote is current from the start (not only after the next edit).
                if (justEnabled) BackupAutoSyncWorker.enqueueDebounced(context)
                // drop(1) skips each flow's replay of current state; anything
                // after that is a real change. Cancelled by collectLatest when
                // the toggle flips off.
                merge(
                    connectionRepository.observeAll().drop(1).map { },
                    sshKeyRepository.observeAll().drop(1).map { },
                    preferencesRepository.preferenceChanges.drop(1),
                ).collect { BackupAutoSyncWorker.enqueueDebounced(context) }
            }
        }
    }
}
