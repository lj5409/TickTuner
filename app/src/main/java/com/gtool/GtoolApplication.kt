package com.gtool

import android.app.Application
import com.gtool.core.data.AcousticRepository
import com.gtool.core.data.AppRepository

class GtoolApplication : Application() {
    val repository: AppRepository by lazy { AppRepository(this) }
    val acousticRepository: AcousticRepository by lazy { AcousticRepository(this) }
}
