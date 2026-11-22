package com.musikH.lcgjukebos

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.AudioManager
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import android.os.*
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationCompat.MediaStyle
import kotlin.random.Random

data class Track(val uri: Uri, val title: String, val artist: String)

class PlayerService : Service() {

    inner class LocalBinder : Binder() {
        fun get(): PlayerService = this@PlayerService
    }

    private val binder = LocalBinder()
    private var player: MediaPlayer? = null
    private val history = ArrayList<Int>()
    private val shufflePlayed = mutableSetOf<Int>()
    private var randomNext = -1
    private var enhancer: LoudnessEnhancer? = null
    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var audioManager: AudioManager

    var tracks: List<Track> = emptyList()
        set(value) {
            field = value
            history.clear()
            shufflePlayed.clear()
            randomNext = -1
            updateMetadata()
        }

    var index = -1
        private set

    var shuffle = false
        set(value) {
            field = value
            history.clear()
            shufflePlayed.clear()

            if (value && index in tracks.indices) {
                shufflePlayed.add(index)
            }

            randomNext = -1
            onChange?.invoke()
        }

    var boost = false
        set(value) {
            field = value
            applyBoost()
            onChange?.invoke()
        }

    var onChange: (() -> Unit)? = null

    val currentTrack: Track?
        get() = tracks.getOrNull(index)

    val isPlaying: Boolean
        get() = try {
            player?.isPlaying == true
        } catch (_: IllegalStateException) {
            false
        }

    val duration: Int
        get() = try {
            player?.duration ?: 0
        } catch (_: IllegalStateException) {
            0
        }

    val position: Int
        get() = try {
            player?.currentPosition ?: 0
        } catch (_: IllegalStateException) {
            0
        }

    override fun onCreate() {
        super.onCreate()

        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager

        mediaSession = MediaSessionCompat(this, "LCGJukeboxSession").apply {
            isActive = true
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = toggle()
                override fun onPause() = toggle()
                override fun onSkipToNext() = next()
                override fun onSkipToPrevious() = prev()
                override fun onSeekTo(pos: Long) = seekTo(pos.toInt())
                override fun onStop() = clearAll()
            })
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL,
                "Pemutar musik",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Kontrol pemutaran LCG Jukebox"
                setSound(null, null)
                lockscreenVisibility =
                    Notification.VISIBILITY_PUBLIC
            }

            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }

        val focusRequest = AudioManager.OnAudioFocusChangeListener { focus ->
            when (focus) {
                AudioManager.AUDIOFOCUS_LOSS,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    if (isPlaying) toggle()
                }
            }
        }

        audioManager.requestAudioFocus(
            focusRequest,
            AudioManager.STREAM_MUSIC,
            AudioManager.AUDIOFOCUS_GAIN
        )
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> toggle()
            ACTION_NEXT -> next()
            ACTION_PREV -> prev()
        }

        return START_STICKY
    }

    fun play(i: Int) {
        val track = tracks.getOrNull(i) ?: return

        if (shuffle && shufflePlayed.size >= tracks.size) {
            shufflePlayed.clear()
        }

        index = i

        if (shuffle) {
            shufflePlayed.add(i)
        }

        randomNext = -1
        releaseEnhancer()

        player?.release()
        player = null

        val mp = MediaPlayer()

        try {
            mp.setDataSource(applicationContext, track.uri)

            mp.setOnCompletionListener {
                if (player !== mp) return@setOnCompletionListener

                if (shuffle) {
                    if (shufflePlayed.size >= tracks.size) {
                        stopAtPlaylistEnd(mp)
                    } else {
                        next()
                    }
                } else if (index >= tracks.lastIndex) {
                    stopAtPlaylistEnd(mp)
                } else {
                    next()
                }
            }

            mp.setOnErrorListener { _, _, _ -> true }

            mp.prepare()
            attachEnhancer(mp)
            mp.start()

            player = mp
            updateMetadata()
            updatePlaybackState()
            goForeground()
        } catch (_: Exception) {
            mp.release()
        }

        onChange?.invoke()
    }

    fun toggle() {
        val mp = player

        if (mp == null) {
            if (tracks.isNotEmpty()) {
                play(if (index >= 0) index else 0)
            }
            return
        }

        if (mp.isPlaying) {
            mp.pause()
            stopForeground(false)
        } else {
            mp.start()
            goForeground()
        }

        updatePlaybackState()
        updateMetadata()
        onChange?.invoke()
    }

    fun clearAll() {
        releaseEnhancer()
        player?.release()
        player = null
        index = -1
        tracks = emptyList()

        mediaSession.isActive = false

        try {
            stopForeground(true)
        } catch (_: Exception) {
        }

        onChange?.invoke()
    }

    fun nextIndex(): Int {
        val n = tracks.size
        if (n == 0) return -1
        if (!shuffle) return (index + 1) % n

        val available = tracks.indices.filter {
            it !in shufflePlayed
        }

        if (available.isEmpty()) return -1

        if (randomNext !in available) {
            randomNext = available[Random.nextInt(available.size)]
        }

        return randomNext
    }

    fun next() {
        if (tracks.isEmpty()) return

        val target = nextIndex()

        if (target !in tracks.indices) {
            player?.let { stopAtPlaylistEnd(it) }
            return
        }

        if (shuffle && index >= 0) {
            history.add(index)
            if (history.size > 100) history.removeAt(0)
        }

        play(target)
    }

    fun prev() {
        if (tracks.isEmpty()) return

        if (shuffle && history.isNotEmpty()) {
            play(history.removeAt(history.size - 1))
            return
        }

        play(if (index <= 0) tracks.size - 1 else index - 1)
    }

    private fun stopAtPlaylistEnd(completedPlayer: MediaPlayer) {
        if (player !== completedPlayer) return

        player = null
        releaseEnhancer()

        try {
            completedPlayer.release()
        } catch (_: Exception) {
        }

        try {
            stopForeground(true)
        } catch (_: Exception) {
        }

        updatePlaybackState()
        onChange?.invoke()
    }

    private fun attachEnhancer(mp: MediaPlayer) {
        try {
            enhancer = LoudnessEnhancer(mp.audioSessionId)
            applyBoost()
        } catch (_: Exception) {
            enhancer = null
        }
    }

    private fun applyBoost() {
        try {
            enhancer?.apply {
                setTargetGain(if (boost) 1200 else 0)
                setEnabled(boost)
            }
        } catch (_: Exception) {
        }
    }

    private fun releaseEnhancer() {
        try {
            enhancer?.release()
        } catch (_: Exception) {
        }

        enhancer = null
    }

    fun seekTo(ms: Int) {
        try {
            player?.seekTo(ms)
            updatePlaybackState()
        } catch (_: IllegalStateException) {
        }
    }

    private fun updateMetadata() {
        val track = currentTrack ?: return
        var bitmap: Bitmap? = null

        try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(applicationContext, track.uri)

            retriever.embeddedPicture?.let { bytes ->
                bitmap = android.graphics.BitmapFactory.decodeByteArray(
                    bytes,
                    0,
                    bytes.size
                )
            }

            retriever.release()
        } catch (_: Exception) {
        }

        val artist = if (
            track.artist == "Tidak diketahui"
        ) "" else track.artist

        val metadata = MediaMetadataCompat.Builder()
            .putString(
                MediaMetadataCompat.METADATA_KEY_TITLE,
                track.title
            )
            .putString(
                MediaMetadataCompat.METADATA_KEY_ARTIST,
                artist
            )
            .putLong(
                MediaMetadataCompat.METADATA_KEY_DURATION,
                duration.toLong()
            )

        if (bitmap != null) {
            metadata.putBitmap(
                MediaMetadataCompat.METADATA_KEY_ALBUM_ART,
                bitmap
            )
        }

        mediaSession.setMetadata(metadata.build())
    }

    private fun updatePlaybackState() {
        val state = if (isPlaying) {
            PlaybackStateCompat.STATE_PLAYING
        } else {
            PlaybackStateCompat.STATE_PAUSED
        }

        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY
                        or PlaybackStateCompat.ACTION_PAUSE
                        or PlaybackStateCompat.ACTION_PLAY_PAUSE
                        or PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                        or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                        or PlaybackStateCompat.ACTION_SEEK_TO
                        or PlaybackStateCompat.ACTION_STOP
                )
                .setState(
                    state,
                    position.toLong(),
                    if (isPlaying) 1.0f else 0.0f
                )
                .build()
        )
    }

    private fun pendingAction(
        requestCode: Int,
        action: String
    ): PendingIntent {
        val flags =
            PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 23)
                    PendingIntent.FLAG_IMMUTABLE
                else 0)

        return PendingIntent.getService(
            this,
            requestCode,
            Intent(this, PlayerService::class.java)
                .setAction(action),
            flags
        )
    }

    private fun goForeground() {
        val track = currentTrack ?: return
        mediaSession.isActive = true

        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 23)
                    PendingIntent.FLAG_IMMUTABLE
                else 0)
        )

        val notification = NotificationCompat.Builder(
            this,
            CHANNEL
        )
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(track.title)
            .setContentText(track.artist)
            .setContentIntent(openIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setStyle(
                MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .addAction(
                NotificationCompat.Action(
                    android.R.drawable.ic_media_previous,
                    "Sebelumnya",
                    pendingAction(11, ACTION_PREV)
                )
            )
            .addAction(
                NotificationCompat.Action(
                    if (isPlaying)
                        android.R.drawable.ic_media_pause
                    else
                        android.R.drawable.ic_media_play,
                    if (isPlaying) "Jeda" else "Putar",
                    pendingAction(10, ACTION_PLAY_PAUSE)
                )
            )
            .addAction(
                NotificationCompat.Action(
                    android.R.drawable.ic_media_next,
                    "Berikutnya",
                    pendingAction(12, ACTION_NEXT)
                )
            )
            .build()

        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        releaseEnhancer()
        player?.release()
        player = null
        mediaSession.release()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "player"
        private const val NOTIFICATION_ID = 1

        const val ACTION_PLAY_PAUSE =
            "com.musikH.lcgjukebos.PLAY_PAUSE"

        const val ACTION_NEXT =
            "com.musikH.lcgjukebos.NEXT"

        const val ACTION_PREV =
            "com.musikH.lcgjukebos.PREV"
    }
}
