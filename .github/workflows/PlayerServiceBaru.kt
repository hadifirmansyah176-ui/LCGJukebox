package com.hadi.lcgjukebox

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaPlayer
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import kotlin.random.Random

data class Track(val uri: Uri, val title: String, val artist: String)

class PlayerService : Service() {

    inner class LocalBinder : Binder() {
        fun get(): PlayerService = this@PlayerService
    }

    private val binder = LocalBinder()
    private var player: MediaPlayer? = null
    private val history = ArrayList<Int>()
    private var randomNext = -1

    var tracks: List<Track> = emptyList()
        set(v) {
            field = v
            history.clear()
            randomNext = -1
        }
    var index = -1
        private set
    var shuffle = false
        set(v) {
            field = v
            history.clear()
            randomNext = -1
            onChange?.invoke()
        }
    var onChange: (() -> Unit)? = null

    val currentTrack: Track? get() = tracks.getOrNull(index)

    val isPlaying: Boolean
        get() = try { player?.isPlaying == true } catch (e: IllegalStateException) { false }
    val duration: Int
        get() = try { player?.duration ?: 0 } catch (e: IllegalStateException) { 0 }
    val position: Int
        get() = try { player?.currentPosition ?: 0 } catch (e: IllegalStateException) { 0 }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL, "Pemutar musik", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    fun play(i: Int) {
        val track = tracks.getOrNull(i) ?: return
        index = i
        randomNext = -1
        player?.release()
        player = null
        val mp = MediaPlayer()
        try {
            mp.setDataSource(applicationContext, track.uri)
            mp.setOnCompletionListener { next() }
            mp.setOnErrorListener { _, _, _ -> true }
            mp.prepare()
            mp.start()
            player = mp
            goForeground()
        } catch (e: Exception) {
            mp.release()
        }
        onChange?.invoke()
    }

    @Suppress("DEPRECATION")
    fun toggle() {
        val p = player
        if (p == null) {
            if (tracks.isNotEmpty()) play(if (index >= 0) index else 0)
            return
        }
        if (p.isPlaying) {
            p.pause()
            stopForeground(false)
        } else {
            p.start()
            goForeground()
        }
        onChange?.invoke()
    }

    /** Indeks lagu berikutnya (acak bila mode ACAK aktif). */
    fun nextIndex(): Int {
        val n = tracks.size
        if (n == 0) return -1
        if (!shuffle) return (index + 1) % n
        if (randomNext !in 0 until n || (n > 1 && randomNext == index)) {
            var r = Random.nextInt(n)
            if (n > 1) {
                while (r == index) r = Random.nextInt(n)
            }
            randomNext = r
        }
        return randomNext
    }

    fun next() {
        if (tracks.isEmpty()) return
        val target = nextIndex()
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

    fun seekTo(ms: Int) {
        try { player?.seekTo(ms) } catch (e: IllegalStateException) { }
    }

    private fun goForeground() {
        val t = currentTrack ?: return
        val flags = if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            flags
        )
        @Suppress("DEPRECATION")
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL)
        else Notification.Builder(this)
        val n = builder
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(t.title)
            .setContentText(t.artist)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(1, n)
            }
        } catch (e: Exception) {
            // Jika sistem menolak notifikasi, musik tetap diputar di aplikasi.
        }
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "player"
    }
}
