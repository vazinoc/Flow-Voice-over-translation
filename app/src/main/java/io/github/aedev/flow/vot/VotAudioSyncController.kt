package io.github.aedev.flow.vot

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import io.github.aedev.flow.player.EnhancedPlayerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlin.math.abs

data class VotSettings(
    val isEnabled: Boolean = false,
    val sourceLang: String = "en",
    val targetLang: String = "ru",
    val originalVolume: Float = 0.20f,
    val translationVolume: Float = 1.0f
)

class VotAudioSyncController(
    context: Context,
    private val playerManager: EnhancedPlayerManager,
    scope: CoroutineScope
) {
    private val votPlayer: ExoPlayer = ExoPlayer.Builder(context).build()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _settings = MutableStateFlow(VotSettings())
    val settings: StateFlow<VotSettings> = _settings

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage

    private var currentActiveVideoId: String? = null

    init {
        playerManager.playerState
            .onEach { state ->
                if (state.currentVideoId != currentActiveVideoId && _settings.value.isEnabled) {
                    disableTranslation()
                }
                if (!_settings.value.isEnabled) return@onEach

                setMainPlayerVolume(_settings.value.originalVolume)

                if (votPlayer.playbackParameters.speed != state.playbackSpeed) {
                    votPlayer.playbackParameters = PlaybackParameters(state.playbackSpeed)
                }

                // Фикс: получаем позицию безопасным методом
                val mainPosition = getMainPositionMs()
                
                if (state.isPlaying && !votPlayer.isPlaying) {
                    syncPositionIfNeeded(mainPosition)
                    votPlayer.play()
                } else if (!state.isPlaying && votPlayer.isPlaying) {
                    votPlayer.pause()
                }

                syncPositionIfNeeded(mainPosition)
            }
            .launchIn(scope)
    }

    private fun getMainPositionMs(): Long {
        try {
            for (method in playerManager.javaClass.methods) {
                if ((method.name == "getCurrentPosition" || method.name == "currentPosition") && method.parameterTypes.isEmpty()) {
                    return (method.invoke(playerManager) as? Number)?.toLong() ?: 0L
                }
            }
            for (field in playerManager.javaClass.declaredFields) {
                field.isAccessible = true
                val value = field.get(playerManager)
                if (value is Player) {
                    return value.currentPosition
                }
            }
        } catch (_: Exception) {}
        return 0L
    }

    private fun setMainPlayerVolume(volume: Float) {
        val safeVolume = volume.coerceIn(0f, 1f)
        mainHandler.post {
            try {
                val method = playerManager.javaClass.methods.firstOrNull {
                    it.name == "setVolume" && it.parameterTypes.size == 1 &&
                        (it.parameterTypes[0] == Float::class.javaPrimitiveType || it.parameterTypes[0] == Float::class.java)
                }
                if (method != null) {
                    method.invoke(playerManager, safeVolume)
                    return@post
                }

                for (field in playerManager.javaClass.declaredFields) {
                    field.isAccessible = true
                    val value = field.get(playerManager)
                    if (value is Player) {
                        value.volume = safeVolume
                        return@post
                    }
                }
            } catch (_: Exception) {}
        }
    }

    fun attachTranslationAudio(videoId: String, audioUrl: String) {
        currentActiveVideoId = videoId
        votPlayer.setMediaItem(MediaItem.fromUri(audioUrl))
        votPlayer.prepare()
        votPlayer.volume = _settings.value.translationVolume

        val state = playerManager.playerState.value
        votPlayer.playbackParameters = PlaybackParameters(state.playbackSpeed)
        
        // Фикс: используем безопасный метод
        votPlayer.seekTo(getMainPositionMs())

        _settings.value = _settings.value.copy(isEnabled = true)

        setMainPlayerVolume(_settings.value.originalVolume)

        if (state.isPlaying) {
            votPlayer.play()
        }
    }

    fun disableTranslation() {
        currentActiveVideoId = null
        _settings.value = _settings.value.copy(isEnabled = false)
        votPlayer.pause()
        votPlayer.clearMediaItems()
        setMainPlayerVolume(1.0f)
    }

    fun updateSettings(newSettings: VotSettings) {
        _settings.value = newSettings
        if (newSettings.isEnabled) {
            votPlayer.volume = newSettings.translationVolume
            setMainPlayerVolume(newSettings.originalVolume)
        } else {
            disableTranslation()
        }
    }

    fun setLoading(loading: Boolean, error: String? = null) {
        _isLoading.value = loading
        _errorMessage.value = error
    }

    private fun syncPositionIfNeeded(mainPositionMs: Long) {
        if (mainPositionMs <= 0L) return
        val drift = abs(mainPositionMs - votPlayer.currentPosition)
        if (drift > 600) {
            votPlayer.seekTo(mainPositionMs)
        }
    }

    fun release() {
        setMainPlayerVolume(1.0f)
        votPlayer.release()
    }
}
