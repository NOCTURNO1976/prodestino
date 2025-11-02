// app/src/main/java/com/prodestino/manaus/bg/EnsureServiceWorker.kt
package com.prodestino.manaus.bg

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class EnsureServiceWorker(appContext: Context, params: WorkerParameters)
    : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val it = Intent(applicationContext, LocationForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            applicationContext.startForegroundService(it)
        } else {
            applicationContext.startService(it)
        }
        return Result.success()
    }
}
