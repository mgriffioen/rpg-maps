package com.rpgmaps.tabletop.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** App-wide preferences. Per-map settings live on [com.rpgmaps.tabletop.data.db.MapEntity]. */
class AppSettings(private val context: Context) {

    data class Snapshot(
        val displayMaxDim: Int = ImageImporter.DEFAULT_DISPLAY_MAX_DIM,
        /** Physical diagonal of the table TV, used by scale-to-life mode. */
        val tvDiagonalInches: Float = 43f,
        val brushRadiusMapPx: Float = 90f,
        val brushSoftness: Float = 0.35f,
        val blankText: String = "",
        val serverPort: Int = DEFAULT_PORT,
    )

    val flow: Flow<Snapshot> = context.settingsStore.data.map { p ->
        Snapshot(
            displayMaxDim = p[KEY_DISPLAY_MAX_DIM] ?: ImageImporter.DEFAULT_DISPLAY_MAX_DIM,
            tvDiagonalInches = p[KEY_TV_DIAGONAL] ?: 43f,
            brushRadiusMapPx = p[KEY_BRUSH_RADIUS] ?: 90f,
            brushSoftness = p[KEY_BRUSH_SOFTNESS] ?: 0.35f,
            blankText = p[KEY_BLANK_TEXT] ?: "",
            serverPort = p[KEY_SERVER_PORT] ?: DEFAULT_PORT,
        )
    }

    suspend fun setDisplayMaxDim(value: Int) = put(KEY_DISPLAY_MAX_DIM, value)
    suspend fun setTvDiagonalInches(value: Float) = put(KEY_TV_DIAGONAL, value)
    suspend fun setBrushRadiusMapPx(value: Float) = put(KEY_BRUSH_RADIUS, value)
    suspend fun setBrushSoftness(value: Float) = put(KEY_BRUSH_SOFTNESS, value)
    suspend fun setBlankText(value: String) = put(KEY_BLANK_TEXT, value)
    suspend fun setServerPort(value: Int) = put(KEY_SERVER_PORT, value)

    private suspend fun <T> put(key: Preferences.Key<T>, value: T) {
        context.settingsStore.edit { it[key] = value }
    }

    companion object {
        /** Well above 1024 so no root privileges are needed, and easy to type. */
        const val DEFAULT_PORT = 8770

        private val KEY_DISPLAY_MAX_DIM = intPreferencesKey("display_max_dim")
        private val KEY_TV_DIAGONAL = floatPreferencesKey("tv_diagonal_inches")
        private val KEY_BRUSH_RADIUS = floatPreferencesKey("brush_radius_map_px")
        private val KEY_BRUSH_SOFTNESS = floatPreferencesKey("brush_softness")
        private val KEY_BLANK_TEXT = stringPreferencesKey("blank_text")
        private val KEY_SERVER_PORT = intPreferencesKey("server_port")
    }
}
