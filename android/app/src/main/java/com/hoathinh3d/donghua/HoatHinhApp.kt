package com.hoathinh3d.donghua

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import com.bumptech.glide.Glide
import com.bumptech.glide.MemoryCategory
import com.bumptech.glide.load.model.GlideUrl
import com.hoathinh3d.donghua.core.AppDiagnostics
import com.hoathinh3d.donghua.core.DeviceProfileResolver
import com.hoathinh3d.donghua.core.GlideOkHttpUrlLoader
import com.hoathinh3d.donghua.core.HttpClientProvider
import com.hoathinh3d.donghua.core.PrefsStore
import com.hoathinh3d.donghua.player.HlsCacheProvider
import java.io.InputStream

class HoatHinhApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setCompatVectorFromResourcesEnabled(true)
        installCrashRecorder()
        PrefsStore.init(this)
        HttpClientProvider.init(this)

        runCatching {
            val glide = Glide.get(this)
            if (DeviceProfileResolver.isLowRamDevice(this)) {
                glide.setMemoryCategory(MemoryCategory.LOW)
            }
            glide.registry.replace(
                GlideUrl::class.java,
                InputStream::class.java,
                GlideOkHttpUrlLoader.Factory(HttpClientProvider.okHttpClient)
            )
        }.onFailure { err ->
            AppDiagnostics.log("Glide", "Init error: ${err.message}")
        }
    }

    private fun installCrashRecorder() {
        val prefs = getSharedPreferences(CRASH_PREFS, Context.MODE_PRIVATE)
        val prev = prefs.getString(KEY_LAST_CRASH, null)
        if (!prev.isNullOrBlank()) {
            AppDiagnostics.log("PrevCrash", prev)
            prefs.edit().remove(KEY_LAST_CRASH).apply()
        }
        val prevHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val root = throwable.cause ?: throwable
                val topFrame = root.stackTrace.firstOrNull()?.let {
                    "${it.className.substringAfterLast('.')}:${it.lineNumber}"
                }.orEmpty()
                val summary = "${throwable.javaClass.simpleName}: ${throwable.message.orEmpty().take(120)} @ $topFrame"
                prefs.edit().putString(KEY_LAST_CRASH, summary).commit()
            }
            prevHandler?.uncaughtException(thread, throwable)
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        runCatching {
            Glide.get(this).trimMemory(level)
            if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
                Glide.get(this).clearMemory()
            }
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        runCatching { Glide.get(this).clearMemory() }
    }

    override fun onTerminate() {
        super.onTerminate()
        HlsCacheProvider.release()
    }

    companion object {
        private const val CRASH_PREFS = "hh3d_diag_crash"
        private const val KEY_LAST_CRASH = "last_crash_summary"
    }
}
