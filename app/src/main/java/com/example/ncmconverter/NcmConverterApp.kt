package com.example.ncmconverter

import android.app.Application

/**
 * Application class for NCM Converter.
 * 
 * Responsibilities:
 * - Application lifecycle management
 * - Global singleton initialization (if needed)
 */
class NcmConverterApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Future: Initialize any global singletons here
    }
}
