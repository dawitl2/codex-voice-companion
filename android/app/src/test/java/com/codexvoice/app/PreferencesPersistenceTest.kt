package com.codexvoice.app
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.codexvoice.app.core.data.DataStorePreferencesRepository
import com.codexvoice.app.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PreferencesPersistenceTest {
    @get:Rule val folder = TemporaryFolder()
    private fun repository(scope: CoroutineScope, name: String) = DataStorePreferencesRepository(
        PreferenceDataStoreFactory.create(scope = scope, produceFile = { java.io.File(folder.root, "$name.preferences_pb") }))
    @Test fun missingFileUsesDarkSafeDefaults() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try { assertEquals(UserPreferences(), repository(scope, "defaults").preferences.first()) }
        finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
    }
    @Test fun writesSurviveRepositoryRestart() = runTest {
        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val first = repository(firstScope, "persist")
        first.setTheme(ThemeMode.SYSTEM); first.setCalling(false, true, false); first.setReduceMotion(true)
        firstScope.coroutineContext[Job]!!.cancelAndJoin()
        val nextScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try { assertEquals(UserPreferences(ThemeMode.SYSTEM, false, true, false, true), repository(nextScope, "persist").preferences.first()) }
        finally { nextScope.coroutineContext[Job]!!.cancelAndJoin() }
    }
    @Test fun changingThemePreservesCallingPreferences() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val r = repository(scope, "independent"); r.setCalling(false, false, false); r.setTheme(ThemeMode.LIGHT)
            assertEquals(UserPreferences(ThemeMode.LIGHT, false, false, false), r.preferences.first())
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
    }
}

