package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.shieldStatsDataStore: DataStore<Preferences> by
  preferencesDataStore(name = "voidshield_stats")

data class PersistedShieldStats(
  val totalBlocked: Long = 0L,
)

class ShieldStatsRepository(private val context: Context) {

  private object Keys {
    val TOTAL_BLOCKED = longPreferencesKey("total_blocked")
  }

  val statsFlow: Flow<PersistedShieldStats> =
    context.shieldStatsDataStore.data.map { prefs ->
      PersistedShieldStats(
        totalBlocked = prefs[Keys.TOTAL_BLOCKED] ?: 0L,
      )
    }

  suspend fun incrementTotalBlocked(delta: Long) {
    if (delta <= 0L) return
    context.shieldStatsDataStore.edit { prefs ->
      val current = prefs[Keys.TOTAL_BLOCKED] ?: 0L
      prefs[Keys.TOTAL_BLOCKED] = current + delta
    }
  }
}
