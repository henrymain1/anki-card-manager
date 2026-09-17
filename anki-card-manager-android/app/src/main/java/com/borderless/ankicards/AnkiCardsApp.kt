package com.borderless.ankicards

import android.app.Application
import com.borderless.ankicards.di.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class AnkiCardsApp : Application() {

    /**
     * Lives as long as the process. Use for work that must outlive the screen
     * the user kicked it off from — e.g. background card generation triggered
     * from a share intent.
     */
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(applicationContext, applicationScope)
    }
}
