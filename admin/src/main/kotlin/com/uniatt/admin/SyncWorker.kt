package com.uniatt.admin

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit

/** مزامنة دورية في الخلفية كلما توفّر الإنترنت (حتى والتطبيق مغلق). */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): ListenableWorker.Result {
        val r = AdminSync.run(AdminStore(applicationContext))
        return if (r.offline) ListenableWorker.Result.retry() else ListenableWorker.Result.success()
    }

    companion object {
        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("uniatt-sync", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
