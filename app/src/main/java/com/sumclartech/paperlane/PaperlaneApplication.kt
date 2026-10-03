package com.sumclartech.paperlane

import android.app.Application
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class PaperlaneApplication : Application() {
    override fun onCreate() { super.onCreate(); PDFBoxResourceLoader.init(this) }
}
