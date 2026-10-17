package com.familyledger.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AppearancePreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("appearance", Context.MODE_PRIVATE)
    private val selected = MutableStateFlow(AppTheme.fromStored(preferences.all["theme"] as? String))
    val theme: StateFlow<AppTheme> = selected.asStateFlow()

    fun select(theme: AppTheme) {
        preferences.edit().putString("theme", theme.storageId).apply()
        selected.value = theme
    }
}
