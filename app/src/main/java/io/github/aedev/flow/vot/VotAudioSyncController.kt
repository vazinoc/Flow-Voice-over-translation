package com.flow.vot

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs

data class VotSettings(
    val isEnabled: Boolean = false,
    val sourceLang: String = "en",
    val targetLang: String = "ru",
    val originalVolume: Float = 0.15f, // 15% громкости оригинала (Audio Ducking)
    val translationVolume: Float = 1.0f // 100% громкости перевода
)

class VotAudioSyncController(
    context: Context,
    private val mainPlayer: Player
) {
    private val votPlayer: ExoPlayer = ExoPlayer.Builder(context).build()

    private val _settings = MutableStateFlow(VotSettings())
    val settings: StateFlow<VotSettings> = _settings

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage

    private val mainPlayerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!_settings.value.isEnabled) return
            if (isPlaying) {
                syncPositionIfNeeded()
                votPlayer.play()
            } else {
                votPlayer.pause()
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            if (_settings.value.isEnabled) {
                votPlayer.seekTo(newPosition.positionMs)
            }
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            // Синхронизируем скорость воспроизведения (1.25x, 1.5x, 2x и т.д.)
            votPlayer.playbackParameters = playbackParameters
        }
    }

    init {
        mainPlayer.addListener(mainPlayerListener)
    }

    fun attachTranslationAudio(audioUrl: String) {
        votPlayer.setMediaItem(MediaItem.fromUri(audioUrl))
        votPlayer.prepare()
        votPlayer.volume = _settings.value.translationVolume
        votPlayer.playbackParameters = mainPlayer.playbackParameters
        votPlayer.seekTo(mainPlayer.currentPosition)

        _settings.value = _settings.value.copy(isEnabled = true)
        mainPlayer.volume = _settings.value.originalVolume

        if (mainPlayer.isPlaying) {
            votPlayer.play()
        }
    }

    fun disableTranslation() {
        _settings.value = _settings.value.copy(isEnabled = false)
        votPlayer.pause()
        votPlayer.clearMediaItems()
        mainPlayer.volume = 1.0f // Возвращаем 100% громкости оригинальному видео
    }

    fun updateSettings(newSettings: VotSettings) {
        _settings.value = newSettings
        if (newSettings.isEnabled) {
            mainPlayer.volume = newSettings.originalVolume
            votPlayer.volume = newSettings.translationVolume
        } else {
            mainPlayer.volume = 1.0f
        }
    }

    fun setLoading(loading: Boolean, error: String? = null) {
        _isLoading.value = loading
        _errorMessage.value = error
    }

    private fun syncPositionIfNeeded() {
        val drift = abs(mainPlayer.currentPosition - votPlayer.currentPosition)
        if (drift > 300) { // Если рассинхрон больше 300 мс — подравниваем
            votPlayer.seekTo(mainPlayer.currentPosition)
        }
    }

    fun release() {
        mainPlayer.removeListener(mainPlayerListener)
        votPlayer.release()
    }
}
