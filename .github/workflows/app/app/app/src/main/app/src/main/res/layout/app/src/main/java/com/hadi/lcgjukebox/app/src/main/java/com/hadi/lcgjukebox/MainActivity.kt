package com.hadi.lcgjukebox

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.ContentUris
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private var service: PlayerService? = null
    private var tracks: List<Track> = emptyList()
    private val handler = Handler(Looper.getMainLooper())
    private var dragging = false

    private lateinit var tvTitle: TextView
    private lateinit var tvArtist: TextView
    private lateinit var seek: SeekBar
    private lateinit var btnPlay: Button
    private lateinit var list: ListView

    private val ticker = object : Runnable {
        override fun run() {
            updateProgress()
            handler.postDelayed(this, 500)
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val s = (binder as PlayerService.LocalBinder).get()
            service = s
            s.onChange = { refreshUi() }
            if (tracks.isNotEmpty()) s.tracks = tracks
            refreshUi()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvTitle = findViewById(R.id.tvTitle)
        tvArtist = findViewById(R.id.tvArtist)
        seek = findViewById(R.id.seek)
        btnPlay = findViewById(R.id.btnPlay)
        list = findViewById(R.id.list)

        findViewById<Button>(R.id.btnPrev).setOnClickListener { service?.prev() }
        findViewById<Button>(R.id.btnNext).setOnClickListener { service?.next() }
        btnPlay.setOnClickListener { service?.toggle() }

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(sb: SeekBar?) { dragging = true }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                service?.seekTo(sb?.progress ?: 0)
                dragging = false
            }
        })

        list.setOnItemClickListener { _, _, position, _ -> service?.play(position) }

        val intent = Intent(this, PlayerService::class.java)
        startService(intent)
        bindService(intent, connection, BIND_AUTO_CREATE)

        ensurePermission()
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
        refreshUi()
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    override fun onDestroy() {
        service?.onChange = null
        unbindService(connection)
        super.onDestroy()
    }

    private fun audioPermission(): String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    private fun ensurePermission() {
        if (Build.VERSION.SDK_INT < 23 ||
            checkSelfPermission(audioPermission()) == PackageManager.PERMISSION_GRANTED
        ) {
            loadTracks()
        } else {
            val perms = if (Build.VERSION.SDK_INT >= 33)
                arrayOf(audioPermission(), Manifest.permission.POST_NOTIFICATIONS)
            else arrayOf(audioPermission())
            requestPermissions(perms, 1)
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (res.isNotEmpty() && res[0] == PackageManager.PERMISSION_GRANTED) {
            loadTracks()
        } else {
            Toast.makeText(this, "Izin membaca musik diperlukan", Toast.LENGTH_LONG).show()
        }
    }

    private fun loadTracks() {
        val base = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST
        )
        val result = mutableListOf<Track>()
        contentResolver.query(
            base, projection,
            "${MediaStore.Audio.Media.IS_MUSIC} != 0", null,
            "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE"
        )?.use { c ->
            while (c.moveToNext()) {
                val artist = c.getString(2)
                result.add(
                    Track(
                        ContentUris.withAppendedId(base, c.getLong(0)),
                        c.getString(1) ?: "Tanpa judul",
                        if (artist == null || artist == "<unknown>") "Artis tidak diketahui" else artist
                    )
                )
            }
        }
        tracks = result
        service?.tracks = tracks
        list.adapter = ArrayAdapter(
            this, android.R.layout.simple_list_item_1,
            tracks.map { "${it.title} — ${it.artist}" }
        )
        if (tracks.isEmpty()) {
            Toast.makeText(this, "Tidak ada lagu ditemukan", Toast.LENGTH_LONG).show()
        }
    }

    private fun refreshUi() {
        val s = service ?: return
        val t = s.currentTrack
        tvTitle.text = t?.title ?: "LCG Jukebox"
        tvArtist.text = t?.artist ?: "Pilih lagu dari daftar"
        btnPlay.text = if (s.isPlaying) "❚❚" else "▶"
        updateProgress()
    }

    private fun updateProgress() {
        val s = service ?: return
        if (dragging) return
        seek.max = s.duration
        seek.progress = s.position
    }
}
