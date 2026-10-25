package io.github.aedev.flow.vot

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
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

    private val _settings = MutableStateFlow(VotSettings())
    val settings: StateFlow<VotSettings> = _settings

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage

    private var currentActiveVideoId: String? = null

    init {
        // Автоматическая синхронизация с состоянием основного плеера Flow
        playerManager.playerState
            .onEach { state ->
                // Если переключили видео — сбрасываем старый перевод
                if (state.currentVideoId != currentActiveVideoId && _settings.value.isEnabled) {
                    disableTranslation()
                }
                if (!_settings.value.isEnabled) return@onEach

                // Синхронизация скорости
                if (votPlayer.playbackParameters.speed != state.playbackSpeed) {
                    votPlayer.playbackParameters = PlaybackParameters(state.playbackSpeed)
                }

                // Синхронизация паузы / воспроизведения
                if (state.isPlaying && !votPlayer.isPlaying) {
                    syncPositionIfNeeded(state.currentPosition)
                    votPlayer.play()
                } else if (!state.isPlaying && votPlayer.isPlaying) {
                    votPlayer.pause()
                }

                // Синхронизация перемотки
                syncPositionIfNeeded(state.currentPosition)
            }
            .launchIn(scope)
    }

    fun attachTranslationAudio(videoId: String, audioUrl: String) {
        currentActiveVideoId = videoId
        votPlayer.setMediaItem(MediaItem.fromUri(audioUrl))
        votPlayer.prepare()
        votPlayer.volume = _settings.value.translationVolume

        val state = playerManager.playerState.value
        votPlayer.playbackParameters = PlaybackParameters(state.playbackSpeed)
        votPlayer.seekTo(state.currentPosition)

        _settings.value = _settings.value.copy(isEnabled = true)

        if (state.isPlaying) {
            votPlayer.play()
        }
    }

    fun disableTranslation() {
        currentActiveVideoId = null
        _settings.value = _settings.value.copy(isEnabled = false)
        votPlayer.pause()
        votPlayer.clearMediaItems()
    }

    fun updateSettings(newSettings: VotSettings) {
        _settings.value = newSettings
        if (newSettings.isEnabled) {
            votPlayer.volume = newSettings.translationVolume
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
        votPlayer.release()
    }
}
