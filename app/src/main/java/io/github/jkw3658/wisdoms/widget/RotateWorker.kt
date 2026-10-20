package io.github.jkw3658.wisdoms.widget

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.jkw3658.wisdoms.data.WisdomRepository
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/** 一定間隔でウィジェットの格言を切り替え、1 日 1 回シートから再取得する */
class RotateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val repo = WisdomRepository.get(applicationContext)
        val settings = repo.settings.first()
        if (settings.isConfigured &&
            System.currentTimeMillis() - settings.lastFetchedAt > TimeUnit.HOURS.toMillis(24)
        ) {
            // 通信できなくてもキャッシュで表示を続ける
            runCatching { repo.refresh() }
        }
        WisdomWidget.showNext(applicationContext)
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "rotate_wisdom"

        suspend fun schedule(context: Context) {
            val minutes = WisdomRepository.get(context).settings.first().intervalMinutes
            schedule(context, minutes)
        }

        fun schedule(context: Context, minutes: Int) {
            // WorkManager の周期実行は最短 15 分
            val request = PeriodicWorkRequestBuilder<RotateWorker>(
                minutes.coerceAtLeast(15).toLong(), TimeUnit.MINUTES,
            ).setInitialDelay(minutes.coerceAtLeast(15).toLong(), TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request,
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
