package io.github.aedev.flow.ui.screens.player

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.data.engagement.FeedInvalidationBus
import io.github.aedev.flow.data.engagement.VideoEngagementUseCase
import io.github.aedev.flow.data.local.*
import io.github.aedev.flow.data.localmedia.LocalMediaDetails
import io.github.aedev.flow.data.localmedia.LocalMediaIds
import io.github.aedev.flow.data.model.Comment
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.repository.SponsorBlockRepository
import io.github.aedev.flow.data.repository.YouTubeRepository
import io.github.aedev.flow.data.transcript.TranscriptRepository
import io.github.aedev.flow.data.video.AutoDownloadTrigger
import io.github.aedev.flow.data.video.VideoDownloadManager
import io.github.aedev.flow.data.video.VideoQueueStore
import io.github.aedev.flow.di.IoDispatcher
import io.github.aedev.flow.di.NetworkIoDispatcher
import io.github.aedev.flow.innertube.pages.VideoCommentSort
import io.github.aedev.flow.innertube.pages.VideoDescriptionPage
import io.github.aedev.flow.player.EnhancedMusicPlayerManager
import io.github.aedev.flow.player.EnhancedPlayerManager
import io.github.aedev.flow.player.GlobalPlayerState
import io.github.aedev.flow.player.LifecyclePlaybackPreferences
import io.github.aedev.flow.player.MiniPlayerExpansionState
import io.github.aedev.flow.player.state.EnhancedPlayerState
import io.github.aedev.flow.player.stream.PlaybackLoadResolver
import io.github.aedev.flow.player.stream.ResetYouTubeSession
import io.github.aedev.flow.player.stream.UpcomingPremiereProbe
import io.github.aedev.flow.ui.screens.player.state.*
import io.github.aedev.flow.utils.NetworkState
import io.github.aedev.flow.vot.VotAudioSyncController
import io.github.aedev.flow.vot.VotClient
import io.github.aedev.flow.vot.VotStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.schabi.newpipe.extractor.stream.*
import javax.inject.Inject

private const val QUEUE_SAVE_DEBOUNCE_MS = 1_000L

@OptIn(FlowPreview::class)
@HiltViewModel
class VideoPlayerViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val repository: YouTubeRepository,
        private val transcriptRepository: TranscriptRepository,
        private val viewHistory: ViewHistory,
        private val engagement: VideoEngagementUseCase,
        private val playlistRepository: io.github.aedev.flow.data.local.PlaylistRepository,
        private val playerPreferences: PlayerPreferences,
        private val videoDownloadManager: VideoDownloadManager,
        private val videoQueueStore: VideoQueueStore,
        private val watchLaterCleanup: WatchLaterCleanup,
        private val offlineSubtitleStore: io.github.aedev.flow.data.video.OfflineSubtitleStore,
        private val localSubtitles: io.github.aedev.flow.data.localmedia.LocalSubtitles,
        private val sponsorBlockRepository: SponsorBlockRepository,
        private val liveChatRepository: io.github.aedev.flow.data.repository.LiveChatRepository,
        private val homeFeedCacheRepository: HomeFeedCacheRepository,
        private val playerManager: EnhancedPlayerManager,
        private val upcomingPremiereProbe: UpcomingPremiereProbe,
        private val playbackResolver: PlaybackLoadResolver,
        notesRepository: io.github.aedev.flow.data.notes.NotesRepository,
        private val videoStats: io.github.aedev.flow.data.stats.VideoStatsRecorder,
        private val localMediaDetails: LocalMediaDetails,
        private val lifecyclePlayback: LifecyclePlaybackPreferences,
        private val autoDownload: AutoDownloadTrigger,
        private val resetYouTubeSession: ResetYouTubeSession,
        @NetworkIoDispatcher private val networkDispatcher: CoroutineDispatcher,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(VideoPlayerUiState())
        val uiState: StateFlow<VideoPlayerUiState> = _uiState.asStateFlow()

        // --- Яндекс Закадровый перевод (VOT) ---
        val votClient = VotClient()
        val votController = VotAudioSyncController(context, playerManager, viewModelScope)

        fun toggleVot() {
            val videoId = _uiState.value.cachedVideo?.id
                ?: playerManager.playerState.value.currentVideoId
                ?: return
            val durationMs = playerManager.playerState.value.duration
            val durationSec = if (durationMs > 0) durationMs / 1000.0 else 300.0

            val currentSettings = votController.settings.value
            if (currentSettings.isEnabled) {
                votController.disableTranslation()
                return
            }

            viewModelScope.launch {
                votController.setLoading(true)
                val result = votClient.getTranslation(
                    videoId = videoId,
                    durationSec = durationSec,
                    sourceLang = currentSettings.sourceLang,
                    targetLang = currentSettings.targetLang
                )
                if (result.status == VotStatus.SUCCESS && result.audioUrl != null) {
                    votController.setLoading(false)
                    votController.attachTranslationAudio(videoId, result.audioUrl)
                } else {
                    votController.setLoading(false, error = result.message ?: "Не удалось перевести видео")
                }
            }
        }
        // ----------------------------------------

        private val notes = PlayerNotes(notesRepository, playerPreferences, viewModelScope)

        val videoNote: StateFlow<String?> = notes.note
        val videoNotesEnabled: StateFlow<Boolean> = notes.enabled

        fun saveVideoNote(
            videoId: String,
            text: String,
        ) = notes.save(videoId, text, _uiState.value.cachedVideo)

        private val collaborators: PlayerCollaborators =
            PlayerCollaborators(
                context = context,
                repository = repository,
                transcriptRepository = transcriptRepository,
                viewHistory = viewHistory,
                engagement = engagement,
                playerPreferences = playerPreferences,
                videoDownloadManager = videoDownloadManager,
                offlineSubtitleStore = offlineSubtitleStore,
                localSubtitles = localSubtitles,
                sponsorBlockRepository = sponsorBlockRepository,
                liveChatRepository = liveChatRepository,
                homeFeedCacheRepository = homeFeedCacheRepository,
                playerManager = playerManager,
                upcomingPremiereProbe = upcomingPremiereProbe,
                videoStats = videoStats,
                uiState = _uiState,
                scope = viewModelScope,
                networkDispatcher = networkDispatcher,
                ioDispatcher = ioDispatcher,
                isLoadCurrent = { loads.isCurrent(it) },
                currentLoadToken = { loads.token },
                shortsEnabled = { loads.shortsEnabled },
                originalTitles = { loads.originalTitles },
                exclusions = { loads.exclusions },
            )

        private val comments = collaborators.comments
        private val descriptions = collaborators.descriptions
        private val transcripts = collaborators.transcripts
        private val watchSessions = collaborators.watchSessions
        private val liveChat = collaborators.liveChat
        private val engagementState = collaborators.engagementState
        private val upcomingPremiere = collaborators.upcomingPremiere
        private val sessionApplier = collaborators.sessionApplier

        val commentsState: StateFlow<List<Comment>> = comments.comments
        val isLoadingComments: StateFlow<Boolean> = comments.isLoading
        val hasMoreComments: StateFlow<Boolean> = comments.hasMore
        val isLoadingMoreComments: StateFlow<Boolean> = comments.isLoadingMore
        val commentSortOptions: StateFlow<List<VideoCommentSort>> = comments.sortOptions
        val commentTotalText: StateFlow<String?> = comments.totalText
        val descriptionState: StateFlow<VideoDescriptionPage?> = descriptions.description
        val transcriptState: StateFlow<TranscriptState> = transcripts.state

        private var clearedUnplayableVideoId: String? = null

        private val recovery =
            PlaybackRecoveryController(
                context = context,
                uiState = _uiState,
                playerManager = playerManager,
                playerPreferences = playerPreferences,
                scope = viewModelScope,
                cancelLoad = { loads.cancel(invalidateToken = true) },
            )

        private val settings =
            PlaybackSettingsController(
                uiState = _uiState,
                playerManager = playerManager,
                playerPreferences = playerPreferences,
                scope = viewModelScope,
            )

        private val presence =
            PlaybackPresenceController(
                uiState = _uiState,
                playerManager = playerManager,
                playerPreferences = playerPreferences,
                viewHistory = viewHistory,
                scope = viewModelScope,
                ioDispatcher = ioDispatcher,
                resumePlayback = ::playVideo,
                savedQueue = videoQueueStore::load,
                resumeQueue = { videos, index, title -> playPlaylist(videos, index, title) },
            )

        private val loads: PlaybackLoadController =
            PlaybackLoadController(
                context = context,
                uiState = _uiState,
                resolver = playbackResolver,
                playerManager = playerManager,
                playerPreferences = playerPreferences,
                viewHistory = viewHistory,
                localMediaDetails = localMediaDetails,
                collaborators = collaborators,
                notes = notes,
                recovery = recovery,
                presence = presence,
                autoDownload = autoDownload,
                scope = viewModelScope,
                networkDispatcher = networkDispatcher,
                ioDispatcher = ioDispatcher,
            )

        val canGoPrevious: StateFlow<Boolean> = loads.canGoPrevious

        private val _expandPlayerRequest = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val expandPlayerRequest: SharedFlow<Unit> = _expandPlayerRequest.asSharedFlow()

        private fun isLocalMediaId(id: String?): Boolean = LocalMediaIds.isLocal(id)

        fun maybeStartLiveChat(videoId: String) = liveChat.start(videoId)

        fun stopLiveChat() = liveChat.stop()

        fun setLiveChatPanelVisible(visible: Boolean) = liveChat.setPanelVisible(visible)

        fun setAutoplayHold(hold: Boolean) = playerManager.setAutoplayHold(hold)

        override fun onCleared() {
            super.onCleared()
            votController.release()
            watchSessions.finalizeActiveSession()
            stopLiveChat()
        }

        val downloadedVideoIds =
            videoDownloadManager.downloadedVideos
                .map { list -> list.map { it.video.id }.toSet() }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

        fun isVideoSavedToAnyPlaylist(videoId: String): Flow<Boolean> = playlistRepository.isVideoSavedToAnyPlaylistFlow(videoId)

        private fun detectIsWifi(): Boolean = NetworkState.isOnWifi(context)

        init {
            loads.start()

            combine(playerManager.queueVideos, playerManager.currentQueueIndexState, ::Pair)
                .drop(1)
                .debounce(QUEUE_SAVE_DEBOUNCE_MS)
                .onEach { (videos, index) -> videoQueueStore.save(videos, index, playerManager.playerState.value.queueTitle) }
                .launchIn(viewModelScope)

            playerPreferences.liveChatEnabled.onEach(liveChat::setEnabled).launchIn(viewModelScope)

            combine(liveChat.messages, liveChat.isLoading, liveChat.isAvailable, ::Triple)
                .onEach { (messages, isLoading, isAvailable) ->
                    _uiState.update { it.applyLiveChat(messages, isLoading, isAvailable) }
                }.launchIn(viewModelScope)

            recovery.collectPlayerEvents()

            playerManager.playerState
                .onEach(::onPlayerStateChanged)
                .launchIn(viewModelScope)

            playerManager.playbackCompletedEvent
                .onEach { completion ->
                    watchSessions.markCompleted(completion)
                    watchLaterCleanup.onFinished(completion.videoId)
                }.launchIn(viewModelScope)

            presence.restoreLastWatchedSession()

            FeedInvalidationBus.events
                .onEach { event -> _uiState.update { it.applyFeedInvalidation(event) } }
                .launchIn(viewModelScope)

            settings.collectAutoplayPreference()
            upcomingPremiere.collectReminderState()
        }

        private suspend fun onPlayerStateChanged(playerState: EnhancedPlayerState) {
            _uiState.update { it.mirrorPlayerState(playerState) }

            playerState.currentVideoId
                ?.takeIf { playerState.isPrepared && it != clearedUnplayableVideoId }
                ?.let { preparedVideoId ->
                    clearedUnplayableVideoId = preparedVideoId
                    playerPreferences.clearVideoUnplayable(preparedVideoId)
                }

            val videoId = _uiState.value.foreignVideoIdNeedingLoad(playerState) ?: return
            GlobalPlayerState.currentVideo.value?.takeIf { it.id == videoId }?.let { currentVideo ->
                _uiState.update { it.resetForVideo(currentVideo) }
                presence.armNotificationFor(currentVideo)
                watchSessions.saveHistoryEntry(currentVideo)
            }
            loadVideoInfo(videoId, isWifi = detectIsWifi(), forceRefresh = true)
        }

        fun resumeRestoredSession(stayMini: Boolean = false) = presence.resumeRestoredSession(stayMini)

        fun dismissContinueWatching() = presence.dismissContinueWatching()

        fun ensureNotificationServiceRunning() = presence.ensureNotificationServiceRunning()

        fun clearResumedInMiniPlayer() = presence.clearResumedInMiniPlayer()

        fun toggleUpcomingReminder() = upcomingPremiere.toggleReminder()

        fun syncWithCurrentPlayerVideo(video: Video) = loads.syncWith(video)

        fun playVideo(
            video: Video,
            userOpened: Boolean = true,
            startPositionMs: Long? = null,
        ) {
            val isMiniPlayerCollapsed =
                GlobalPlayerState.miniPlayerExpansionState.value == MiniPlayerExpansionState.COLLAPSED
            if (_uiState.value.shouldReopenInsteadOfPlaying(video.id, playerManager.playerState.value, isMiniPlayerCollapsed)) {
                startPositionMs?.let(playerManager::seekTo)
                presence.showVideoPlayer()
                _expandPlayerRequest.tryEmit(Unit)
                return
            }

            loads.nextToken()
            takeOverPlayback()
            armStartPaused(video.id, userOpened)
            autoDownload.onOpened(video, userOpened)

            _uiState.value = _uiState.value.startPlaybackOf(video)
            GlobalPlayerState.setCurrentVideo(video)
            GlobalPlayerState.setExplicitBackgroundPlaybackActive(false)
            watchSessions.saveHistoryEntry(video)
            presence.armNotificationFor(video)
            if (upcomingPremiere.applyCountdown(video)) {
                return
            }
            loadVideoInfo(video.id, isWifi = detectIsWifi(), forceRefresh = true, resumePositionOverrideMs = startPositionMs)
        }

        fun playLocalVideo(
            video: Video,
            contentUri: String,
        ) {
            takeOverPlayback()
            armStartPaused(video.id, userOpened = true)
            loads.prepareDeviceFile(video, contentUri)
        }

        private fun armStartPaused(
            videoId: String,
            userOpened: Boolean,
        ) {
            playerManager.armStartPaused(videoId.takeIf { userOpened && lifecyclePlayback.settings.startVideosPaused })
        }

        private fun takeOverPlayback() {
            votController.disableTranslation()
            loads.cancel()
            recovery.onPlaybackRequested()
            playerManager.pause()
            playerManager.clearAll()
            EnhancedMusicPlayerManager.stop()
            EnhancedMusicPlayerManager.clearCurrentTrack()
        }

        fun clearVideo() {
            votController.disableTranslation()
            loads.nextToken()
            loads.cancel()
            recovery.onPlaybackRequested()
            playerManager.stop()
            playerManager.stopBackgroundService()
            playerManager.clearAll()
            GlobalPlayerState.setCurrentVideo(null)
            GlobalPlayerState.setExplicitBackgroundPlaybackActive(false)
            GlobalPlayerState.hideMiniPlayer()

            _uiState.update { it.clearedForNoVideo() }

            loads.clearHistory()

            comments.clear()
            descriptions.clear()
            transcripts.clear()
        }

        fun startBackgroundPlayback() = presence.startBackgroundPlayback()

        fun resetDismissState() = presence.resetDismissState()

        fun showVideoPlayer() = presence.showVideoPlayer()

        fun resetSessionAndRetry() =
            viewModelScope.launch {
                resetYouTubeSession()
                retryLoadVideo()
            }

        fun retryLoadVideo() {
            val videoId = _uiState.value.cachedVideo?.id ?: return
            Log.d("VideoPlayerViewModel", "Retrying video load for $videoId")
            if (upcomingPremiere.applyCountdown(_uiState.value.cachedVideo ?: return)) {
                return
            }
            val deviceFileUri = LocalMediaIds.videoUri(videoId)
            if (deviceFileUri != null) {
                playLocalVideo(_uiState.value.cachedVideo ?: return, deviceFileUri.toString())
                return
            }
            recovery.onPlaybackRequested()
            playerManager.clearCurrentVideo()
            _uiState.update { it.copy(error = null, errorHint = null, errorOffersSessionReset = false, isLoading = true) }
            loadVideoInfo(videoId, isWifi = detectIsWifi(), forceRefresh = true)
        }

        fun ensurePlaybackPrepared(videoId: String) = loads.ensurePrepared(videoId)

        fun playPlaylist(
            videos: List<Video>,
            startIndex: Int,
            title: String? = null,
            shuffle: Boolean? = null,
        ) {
            if (videos.isEmpty()) return
            val startVideo = videos.getOrNull(startIndex) ?: videos.first()

            EnhancedMusicPlayerManager.stop()
            EnhancedMusicPlayerManager.clearCurrentTrack()

            playerManager.armStartPaused(null)
            playerManager.setQueue(videos, startIndex, title, shuffle)

            _uiState.update { it.resetForVideo(startVideo).copy(queueTitle = title) }
            watchSessions.saveHistoryEntry(startVideo)
            presence.armNotificationFor(startVideo)
            if (upcomingPremiere.applyCountdown(startVideo, preserveQueueTitle = title)) {
                return
            }
            loadVideoInfo(startVideo.id, isWifi = detectIsWifi(), forceRefresh = true)
        }

        fun playNext() {
            val handledByPlayer = playerManager.playNext(loadStreamsInPlayer = false)
            if (!handledByPlayer) {
                _uiState.value.relatedVideos.firstOrNull()?.let { nextVideo ->
                    playVideo(nextVideo, userOpened = false)
                    io.github.aedev.flow.player.GlobalPlayerState
                        .setCurrentVideo(nextVideo)
                }
            }
        }

        fun playPrevious() {
            val handledByPlayer = playerManager.playPrevious(loadStreamsInPlayer = false)
            if (!handledByPlayer) {
                loads.previousVideoId()?.let { prevId ->
                    val prevVideo = blankVideo(prevId, cached = null)
                    playVideo(prevVideo, userOpened = false)
                    GlobalPlayerState.setCurrentVideo(prevVideo)
                }
            }
        }

        fun loadVideoInfo(
            videoId: String,
            isWifi: Boolean = true,
            forceRefresh: Boolean = false,
            escalateToSabr: Boolean = false,
            resumePositionOverrideMs: Long? = null,
        ) = loads.load(videoId, isWifi, forceRefresh, escalateToSabr, resumePositionOverrideMs)

        fun switchQuality(quality: VideoQuality) = settings.switchQuality(quality)

        fun savePlaybackPosition(
            videoId: String,
            position: Long,
            duration: Long,
            title: String,
            thumbnailUrl: String,
            channelName: String = "",
            channelId: String = "",
            isShort: Boolean = false,
        ) {
            if (!positionBelongsTo(videoId, playerManager.playerState.value.currentVideoId)) return
            watchSessions.savePlaybackPosition(
                videoId = videoId,
                positionMs = position,
                durationMs = duration,
                title = title,
                thumbnailUrl = thumbnailUrl,
                channelName = channelName,
                channelId = channelId,
                isShort = isShort,
                isLocal = isLocalMediaId(videoId),
            )
            viewModelScope.launch { watchLaterCleanup.onProgress(videoId, position, duration) }
        }

        fun checkpointWatchSession() = watchSessions.checkpoint()

        fun trackLivePlayback(
            video: Video,
            position: Long,
        ) = watchSessions.trackLive(video, position)

        fun onSponsorSegmentSkipped(
            category: String,
            skippedMs: Long,
        ) = videoStats.onSponsorSkip(category, skippedMs)

        fun reloadSponsorSegments(videoId: String) = playerManager.reloadSponsorSegments(videoId)

        fun toggleSubscription(
            channelId: String,
            channelName: String,
            channelThumbnail: String,
        ) = engagementState.toggleSubscription(channelId, channelName, channelThumbnail)

        fun setNotificationEnabled(
            channelId: String,
            enabled: Boolean,
        ) = engagementState.setNotificationEnabled(channelId, enabled)

        fun likeVideo(
            videoId: String,
            title: String,
            thumbnail: String,
            channelName: String,
            channelId: String = "",
        ) = engagementState.like(videoId, title, thumbnail, channelName, channelId)

        fun dislikeVideo(videoId: String) = engagementState.dislike(videoId)

        fun removeLikeState(videoId: String) = engagementState.removeLike(videoId)

        fun loadSubscriptionAndLikeState(
            channelId: String,
            videoId: String,
        ) = engagementState.observe(channelId, videoId)

        fun toggleAutoplay(enabled: Boolean) = settings.toggleAutoplay(enabled)

        suspend fun addSubtitleFile(uri: android.net.Uri): Boolean = sessionApplier.addSubtitleFile(uri)

        suspend fun subtitleFolder(): android.net.Uri? = sessionApplier.subtitleFolder()

        fun setSubtitleOffset(offsetMs: Long) {
            viewModelScope.launch { sessionApplier.setSubtitleOffset(offsetMs) }
        }

        fun toggleLoop(enabled: Boolean) = settings.toggleLoop(enabled)

        fun loadTranscript(trackUrl: String?) = transcripts.load(trackUrl)

        fun loadDescription(videoId: String) {
            if (isLocalMediaId(videoId)) {
                descriptions.clear()
                return
            }
            descriptions.load(videoId)
        }

        fun loadComments(videoId: String) {
            if (isLocalMediaId(videoId)) {
                comments.clear()
                return
            }
            comments.load(videoId)
        }

        fun loadMoreComments(videoId: String) = comments.loadMore(videoId)

        fun selectCommentSort(
            videoId: String,
            sort: VideoCommentSort,
        ) = comments.selectSort(videoId, sort)

        fun loadCommentReplies(comment: Comment) {
            val videoId = _uiState.value.cachedVideo?.id ?: return
            comments.loadReplies(videoId, comment)
        }

        fun loadMoreCommentReplies(comment: Comment) {
            val videoId = _uiState.value.cachedVideo?.id ?: return
            comments.loadMoreReplies(videoId, comment)
        }

        fun toggleSkipSilence(isEnabled: Boolean) = settings.toggleSkipSilence(isEnabled)

        fun toggleVolumeNormalization(isEnabled: Boolean) = settings.toggleVolumeNormalization(isEnabled)
    }
