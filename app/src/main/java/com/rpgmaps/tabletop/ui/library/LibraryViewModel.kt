package com.rpgmaps.tabletop.ui.library

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.rpgmaps.tabletop.RpgMapsApplication
import com.rpgmaps.tabletop.data.db.MapEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class LibraryViewModel(private val app: RpgMapsApplication) : ViewModel() {

    val maps: StateFlow<List<MapEntity>> = app.repository.observeMaps()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _importProgress = MutableStateFlow<ImportProgress?>(null)
    val importProgress: StateFlow<ImportProgress?> = _importProgress.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    data class ImportProgress(val done: Int, val total: Int)

    /**
     * Imports one or more picked images. Each is handled independently so a
     * single unreadable file does not abandon the rest of a batch -- the DM
     * finds out at the end which ones failed.
     */
    fun import(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val maxDim = app.settings.flow.first().displayMaxDim
            val failures = mutableListOf<String>()

            uris.forEachIndexed { index, uri ->
                _importProgress.value = ImportProgress(index, uris.size)
                try {
                    app.repository.importMap(uri, maxDim)
                } catch (e: Exception) {
                    failures += (uri.lastPathSegment ?: "one file")
                } catch (e: OutOfMemoryError) {
                    failures += (uri.lastPathSegment ?: "one file")
                }
            }

            _importProgress.value = null
            _message.value = when {
                failures.isEmpty() && uris.size == 1 -> "Map added"
                failures.isEmpty() -> "${uris.size} maps added"
                failures.size == uris.size -> "Could not import ${failures.joinToString()}"
                else -> "Imported ${uris.size - failures.size}, failed: ${failures.joinToString()}"
            }
        }
    }

    fun rename(id: String, name: String) {
        viewModelScope.launch { app.repository.rename(id, name) }
    }

    fun delete(map: MapEntity) {
        viewModelScope.launch {
            app.repository.delete(map.id)
            _message.value = "Deleted ${map.name}"
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                LibraryViewModel(application(this))
            }
        }
    }
}

/** Pulls the [RpgMapsApplication] out of the ViewModel creation extras. */
internal fun application(extras: CreationExtras): RpgMapsApplication =
    checkNotNull(extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]) {
        "No Application in CreationExtras"
    } as RpgMapsApplication
