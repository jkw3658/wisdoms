package io.github.jkw3658.wisdoms.widget

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.jkw3658.wisdoms.data.WisdomRepository
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/** 1 日 1 回シートから再取得する（表示中の言葉は切り替えない） */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val repo = WisdomRepository.get(applicationContext)
        if (repo.settings.first().isConfigured) {
            // 通信できなくてもキャッシュで表示を続ける
            runCatching { repo.refresh() }
        }
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "sync_wisdoms"
        // 以前のバージョンの「一定間隔で切り替え」処理
        private const val LEGACY_WORK_NAME = "rotate_wisdom"

        fun schedule(context: Context) {
            val wm = WorkManager.getInstance(context)
            wm.cancelUniqueWork(LEGACY_WORK_NAME)
            val request = PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
                .build()
            wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
