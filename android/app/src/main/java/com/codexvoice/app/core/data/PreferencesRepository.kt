package com.codexvoice.app.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.codexvoice.app.core.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

interface PreferencesRepository {
    val preferences: Flow<UserPreferences>
    suspend fun setTheme(mode: ThemeMode)
    suspend fun setCalling(completion: Boolean, failure: Boolean, approval: Boolean)
    suspend fun setReduceMotion(value: Boolean)
}

class DataStorePreferencesRepository(private val store: DataStore<Preferences>) : PreferencesRepository {
    private val theme = stringPreferencesKey("theme")
    private val completion = booleanPreferencesKey("completion")
    private val failure = booleanPreferencesKey("failure")
    private val approval = booleanPreferencesKey("approval")
    private val motion = booleanPreferencesKey("reduce_motion")
    override val preferences = store.data.catch { error ->
        if (error is IOException) emit(emptyPreferences()) else throw error
    }.map { p -> UserPreferences(
        ThemeMode.entries.firstOrNull { it.name == p[theme] } ?: ThemeMode.DARK,
        p[completion] ?: true, p[failure] ?: true, p[approval] ?: true, p[motion] ?: false,
    ) }
    override suspend fun setTheme(mode: ThemeMode) { store.edit { it[theme] = mode.name } }
    override suspend fun setCalling(completion: Boolean, failure: Boolean, approval: Boolean) {
        store.edit { it[this.completion] = completion; it[this.failure] = failure; it[this.approval] = approval }
    }
    override suspend fun setReduceMotion(value: Boolean) { store.edit { it[motion] = value } }
}

