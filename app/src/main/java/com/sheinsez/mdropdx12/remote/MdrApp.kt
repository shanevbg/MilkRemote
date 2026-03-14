package com.sheinsez.mdropdx12.remote

import android.app.Application
import com.sheinsez.mdropdx12.remote.service.ConnectionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

class MdrApp : Application() {
    val applicationScope = CoroutineScope(SupervisorJob())
    val connectionManager by lazy { ConnectionManager(applicationScope) }
}
