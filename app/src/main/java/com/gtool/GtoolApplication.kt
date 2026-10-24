package com.gtool

import android.app.Application
import com.gtool.core.data.AppRepository

class GtoolApplication : Application() {
    val repository: AppRepository by lazy { AppRepository(this) }
}
