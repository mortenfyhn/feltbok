package io.github.mortenfyhn.feltbok

import android.app.Application

/** Hands the build facts to the shared UI ([AppInfo]) as the process starts - before any activity,
 *  and also under instrumented tests, which build the view model without one. */
class FeltbokApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppInfo.gitVersion = BuildConfig.GIT_VERSION
        AppInfo.versionName = BuildConfig.VERSION_NAME
        AppInfo.versionCode = BuildConfig.VERSION_CODE
        AppInfo.dev = BuildConfig.DEV
    }
}
