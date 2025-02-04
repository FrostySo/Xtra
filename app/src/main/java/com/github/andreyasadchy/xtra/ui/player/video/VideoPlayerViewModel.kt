package com.github.andreyasadchy.xtra.ui.player.video

import android.app.Application
import androidx.core.net.toUri
import androidx.lifecycle.viewModelScope
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.model.VideoDownloadInfo
import com.github.andreyasadchy.xtra.model.VideoPosition
import com.github.andreyasadchy.xtra.model.kraken.video.Video
import com.github.andreyasadchy.xtra.player.lowlatency.HlsManifest
import com.github.andreyasadchy.xtra.player.lowlatency.HlsMediaSource
import com.github.andreyasadchy.xtra.repository.PlayerRepository
import com.github.andreyasadchy.xtra.repository.TwitchService
import com.github.andreyasadchy.xtra.ui.player.AudioPlayerService
import com.github.andreyasadchy.xtra.ui.player.HlsPlayerViewModel
import com.github.andreyasadchy.xtra.ui.player.PlayerMode
import com.github.andreyasadchy.xtra.util.RemoteConfigParams
import com.github.andreyasadchy.xtra.util.toast
import com.google.firebase.ktx.Firebase
import com.google.firebase.remoteconfig.ktx.remoteConfig
import kotlinx.coroutines.launch
import javax.inject.Inject


class VideoPlayerViewModel @Inject constructor(
        context: Application,
        private val playerRepository: PlayerRepository,
        repository: TwitchService) : HlsPlayerViewModel(context, repository) {

    private lateinit var video: Video
    val videoInfo: VideoDownloadInfo?
        get() {
            val playlist = (player.currentManifest as? HlsManifest)?.mediaPlaylist ?: return null
            val segments = playlist.segments
            val size = segments.size
            val relativeTimes = ArrayList<Long>(size)
            val durations = ArrayList<Long>(size)
            for (i in 0 until size) {
                val segment = segments[i]
                relativeTimes.add(segment.relativeStartTimeUs / 1000L)
                durations.add(segment.durationUs / 1000L)
            }
            return VideoDownloadInfo(video, helper.urls, relativeTimes, durations, playlist.durationUs / 1000L, playlist.targetDurationUs / 1000L, player.currentPosition)
        }

    override val channelInfo: Pair<String, String>
        get() = video.channel.id to video.channel.displayName

    fun setVideo(video: Video, offset: Double) {
        if (!this::video.isInitialized) {
            this.video = video
            val remoteConfig = Firebase.remoteConfig
            remoteConfig.fetchAndActivate()
                    .addOnCompleteListener {
                        viewModelScope.launch {
                            try {
                                val clientId = remoteConfig.getString(RemoteConfigParams.TWITCH_CLIENT_ID_KEY)
                                val tokenList = remoteConfig.getString(RemoteConfigParams.TWITCH_TOKEN_LIST_KEY)
                                val response = playerRepository.loadVideoPlaylist(video.id, clientId, tokenList)
                                if (response.isSuccessful) {
                                    mediaSource = HlsMediaSource.Factory(dataSourceFactory).createMediaSource(response.raw().request().url().toString().toUri())
                                    play()
                                    if (offset > 0) {
                                        player.seekTo(offset.toLong())
                                    }
                                } else if (response.code() == 403) {
                                    val context = getApplication<Application>()
                                    context.toast(R.string.video_subscribers_only)
                                }
                            } catch (e: Exception) {

                            }
                        }
                    }
        }
    }

    override fun changeQuality(index: Int) {
        previousQuality = qualityIndex
        super.changeQuality(index)
        when {
            index < qualities.lastIndex -> {
                val audioOnly = playerMode.value == PlayerMode.AUDIO_ONLY
                if (audioOnly) {
                    playbackPosition = currentPlayer.value!!.currentPosition
                }
                setVideoQuality(index)
                if (audioOnly) {
                    player.seekTo(playbackPosition)
                }
            }
            else -> {
                (player.currentManifest as? HlsManifest)?.let {
                    startBackgroundAudio(helper.urls.values.last(), video.channel.displayName, video.channel.status, video.channel.logo, true, AudioPlayerService.TYPE_VIDEO, video.id.substring(1).toLong())
                    _playerMode.value = PlayerMode.AUDIO_ONLY
                }
            }
        }
    }

    override fun onResume() {
        isResumed = true
        if (playerMode.value == PlayerMode.NORMAL) {
            super.onResume()
        } else if (playerMode.value == PlayerMode.AUDIO_ONLY) {
            hideBackgroundAudio()
        }
        if (playerMode.value != PlayerMode.AUDIO_ONLY) {
            player.seekTo(playbackPosition)
        }
    }

    override fun onPause() {
        if (playerMode.value != PlayerMode.AUDIO_ONLY) {
            playbackPosition = player.currentPosition
        }
        super.onPause()
    }

    override fun onCleared() {
        if (playerMode.value == PlayerMode.NORMAL && this::video.isInitialized) { //TODO
            playerRepository.saveVideoPosition(VideoPosition(video.id.substring(1).toLong(), player.currentPosition))
        }
        super.onCleared()
    }
}