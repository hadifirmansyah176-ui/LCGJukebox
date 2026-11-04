package com.hadi.lcgjukebox

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.*
import android.provider.MediaStore
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import kotlin.math.sin

private val NAVY = 0xFF1B2A6B.toInt()
private val PANEL = 0xFFE6EBFC.toInt()
private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

class VolumeView(context: Context, private val onLevel: (Int) -> Unit) : View(context) {
    var level = 0
        set(v) { field = v; invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(c: Canvas) {
        val n = 10
        val w = width.toFloat()
        val h = height.toFloat()
        val gap = 3f
        val bh = (h - gap * (n - 1)) / n
        for (i in 0 until n) {
            val top = h - (i + 1) * bh - i * gap
            val barW = w * (0.35f + 0.65f * (i + 1) / n)
            paint.color = if (i < level) Color.rgb(60, 200, 60) else Color.rgb(70, 80, 120)
            c.drawRect(w - barW, top, w, top + bh, paint)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_DOWN || e.action == MotionEvent.ACTION_MOVE) {
            val frac = 1f - (e.y / height).coerceIn(0f, 1f)
            val l = Math.round(frac * 10)
            level = l
            onLevel(l)
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        return true
    }
}

class WaveView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }
    private var amp = 0f
    private var phase = 0f

    fun step(active: Boolean) {
        amp = if (active) 0.2f + 0.8f * Math.random().toFloat() else amp * 0.6f
        phase += 0.8f
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val mid = height / 2f
        val path = Path()
        for (i in 0..40) {
            val x = w * i / 40f
            val y = mid + sin(phase + i * 0.9f) * (mid - 4f) * amp
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        c.drawPath(path, paint)
    }
}

class MainActivity : Activity() {

    private var service: PlayerService? = null
    private var tracks: List<Track> = emptyList()
    private val handler = Handler(Looper.getMainLooper())
    private var dragging = false
    private var shownIndex = -2

    private val audio by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    private lateinit var tvTime: TextView
    private lateinit var tvArtist: TextView
    private lateinit var tvTitle: TextView
    private lateinit var tvBitrate: TextView
    private lateinit var tvTrackNo: TextView
    private lateinit var tvNext: TextView
    private lateinit var tvVol: TextView
    private lateinit var art: ImageView
    private lateinit var wave: WaveView
    private lateinit var volView: VolumeView
    private lateinit var seek: SeekBar
    private lateinit var btnPlay: TextView

    private val ticker = object : Runnable {
        override fun run() {
            updateProgress()
            handler.postDelayed(this, 300)
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val s = (binder as PlayerService.LocalBinder).get()
            service = s
            s.onChange = { refreshUi() }
            if (tracks.isNotEmpty()) s.tracks = tracks
            shownIndex = -2
            refreshUi()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
        }
    }

    private fun px(i: Int): Int = (i * resources.displayMetrics.density).toInt()

    private fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)

    private fun box(fill: Int, stroke: Int, radius: Float = 10f): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = radius * resources.displayMetrics.density
            setStroke(px(2), stroke)
        }

    private fun label(
        text: String, size: Float, bold: Boolean = false,
        italic: Boolean = false, color: Int = NAVY
    ): TextView = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        setTypeface(
            null, when {
                bold && italic -> Typeface.BOLD_ITALIC
                bold -> Typeface.BOLD
                italic -> Typeface.ITALIC
                else -> Typeface.NORMAL
            }
        )
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(NAVY)
        }

        root.addView(
            label("LCG Jukebox", 16f, bold = true, color = Color.WHITE).apply {
                gravity = Gravity.CENTER
                setPadding(0, px(8), 0, px(8))
            }, lp(MATCH, WRAP)
        )

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xFFC9D2F5.toInt(), 0xFF8FA3E6.toInt())
            )
            setPadding(px(10), px(10), px(10), px(10))
        }
        root.addView(body, lp(MATCH, 0, 1f))

        // Baris atas: waktu + sampul (kiri), gelombang + volume (kanan)
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tvTime = label("-0:00", 18f, bold = true).apply {
            gravity = Gravity.CENTER
            background = box(PANEL, NAVY)
            setPadding(px(8), px(4), px(8), px(4))
        }
        left.addView(tvTime, lp(MATCH, WRAP))
        art = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = box(0xFF5A6BB5.toInt(), NAVY, 6f)
            setPadding(px(2), px(2), px(2), px(2))
        }
        left.addView(art, lp(px(130), px(130)).apply { topMargin = px(8) })

        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        wave = WaveView(this).apply { background = box(0xFF2A3A8A.toInt(), NAVY) }
        right.addView(wave, lp(MATCH, px(48)))
        val volRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        tvVol = label("0", 20f, bold = true).apply { gravity = Gravity.CENTER }
        volRow.addView(tvVol, lp(0, WRAP, 1f))
        volView = VolumeView(this) { l -> setVolume(l) }
        volRow.addView(volView, lp(px(56), px(110)))
        right.addView(volRow, lp(MATCH, WRAP).apply { topMargin = px(8) })

        top.addView(left, lp(0, WRAP, 1f))
        top.addView(right, lp(0, WRAP, 1f).apply { leftMargin = px(10) })
        body.addView(top, lp(MATCH, WRAP))

        // Panel info lagu
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = box(PANEL, NAVY)
            setPadding(px(10), px(6), px(10), px(6))
        }
        tvBitrate = label("", 11f).apply { gravity = Gravity.END }
        info.addView(tvBitrate, lp(MATCH, WRAP))
        val artistRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        artistRow.addView(label("Artist: ", 12f, italic = true), lp(WRAP, WRAP))
        tvArtist = label("-", 15f, bold = true)
        artistRow.addView(tvArtist, lp(0, WRAP, 1f))
        info.addView(artistRow, lp(MATCH, WRAP))
        val titleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        titleRow.addView(label("Title: ", 12f, italic = true), lp(WRAP, WRAP))
        tvTitle = label("-", 15f, bold = true)
        titleRow.addView(tvTitle, lp(0, WRAP, 1f))
        info.addView(titleRow, lp(MATCH, WRAP))
        body.addView(info, lp(MATCH, WRAP).apply { topMargin = px(10) })

        // Slider lagu
        seek = SeekBar(this).apply {
            progressTintList = ColorStateList.valueOf(Color.rgb(60, 200, 60))
            thumbTintList = ColorStateList.valueOf(0xFFF2C230.toInt())
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(sb: SeekBar?) { dragging = true }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                service?.seekTo(sb?.progress ?: 0)
                dragging = false
            }
        })
        body.addView(seek, lp(MATCH, WRAP).apply { topMargin = px(14) })

        // Panel lagu berikutnya
        val next = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = box(PANEL, NAVY)
            setPadding(px(10), px(6), px(10), px(6))
        }
        val nextRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        nextRow.addView(label("Next:", 12f, italic = true), lp(WRAP, WRAP))
        tvTrackNo = label("Track: 0/0", 12f).apply { gravity = Gravity.END }
        nextRow.addView(tvTrackNo, lp(0, WRAP, 1f))
        next.addView(nextRow, lp(MATCH, WRAP))
        tvNext = label("-", 14f, bold = true)
        next.addView(tvNext, lp(MATCH, WRAP))
        body.addView(next, lp(MATCH, WRAP).apply { topMargin = px(10) })

        // Bar bawah
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(NAVY)
        }
        fun key(text: String, onClick: (View) -> Unit): TextView =
            label(text, 16f, bold = true, color = Color.WHITE).apply {
                gravity = Gravity.CENTER
                setPadding(0, px(12), 0, px(12))
                setOnClickListener { onClick(it) }
            }
        bar.addView(key("Menu") { v -> showMenu(v) }, lp(0, WRAP, 1f))
        bar.addView(key("|◀") { service?.prev() }, lp(0, WRAP, 1f))
        btnPlay = key("▶") { service?.toggle() }
        bar.addView(btnPlay, lp(0, WRAP, 1f))
        bar.addView(key("▶|") { service?.next() }, lp(0, WRAP, 1f))
        bar.addView(key("Exit") {
            stopService(Intent(this, PlayerService::class.java))
            finish()
        }, lp(0, WRAP, 1f))
        root.addView(bar, lp(MATCH, WRAP))

        return root
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        volumeControlStream = AudioManager.STREAM_MUSIC
        setContentView(buildUi())

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

    private fun showMenu(anchor: View) {
        val pm = PopupMenu(this, anchor)
        pm.menu.add(0, 1, 0, "Daftar lagu")
        pm.menu.add(0, 2, 1, "Muat ulang lagu")
        pm.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> showPlaylist()
                2 -> ensurePermission()
            }
            true
        }
        pm.show()
    }

    private fun showPlaylist() {
        if (tracks.isEmpty()) {
            Toast.makeText(this, "Tidak ada lagu", Toast.LENGTH_SHORT).show()
            return
        }
        val names = tracks.map { "${it.title} — ${it.artist}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Daftar lagu")
            .setItems(names) { _, which -> service?.play(which) }
            .show()
    }

    private fun setVolume(level: Int) {
        try {
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(level * max / 10f), 0)
        } catch (e: Exception) {
        }
        syncVolume()
    }

    private fun syncVolume() {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val cur = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (max > 0) {
            volView.level = Math.round(cur * 10f / max)
            tvVol.text = Math.round(cur * 100f / max).toString()
        }
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
        if (tracks.isEmpty()) {
            Toast.makeText(this, "Tidak ada lagu ditemukan", Toast.LENGTH_LONG).show()
        }
        refreshUi()
    }

    private fun decode(data: ByteArray): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, o)
        var s = 1
        while (o.outWidth / s > 600) s *= 2
        return BitmapFactory.decodeByteArray(
            data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = s }
        )
    }

    private fun loadInfo(index: Int, t: Track) {
        art.setImageDrawable(null)
        tvBitrate.text = ""
        val ctx = applicationContext
        Thread {
            var bmp: Bitmap? = null
            var kbps = ""
            var khz = ""
            try {
                val r = MediaMetadataRetriever()
                r.setDataSource(ctx, t.uri)
                val pic = r.embeddedPicture
                if (pic != null) bmp = decode(pic)
                val br = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()
                if (br != null) kbps = "${br / 1000}kbps"
                r.release()
            } catch (e: Exception) {
            }
            try {
                val ex = MediaExtractor()
                ex.setDataSource(ctx, t.uri, null)
                if (ex.trackCount > 0) {
                    val f = ex.getTrackFormat(0)
                    if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        khz = "${f.getInteger(MediaFormat.KEY_SAMPLE_RATE) / 1000}KHz"
                    }
                }
                ex.release()
            } catch (e: Exception) {
            }
            val text = listOf(kbps, khz).filter { it.isNotEmpty() }.joinToString("/")
            runOnUiThread {
                if (shownIndex == index) {
                    art.setImageBitmap(bmp)
                    tvBitrate.text = text
                }
            }
        }.start()
    }

    private fun fmt(ms: Int): String {
        val s = ms / 1000
        return "${s / 60}:${"%02d".format(s % 60)}"
    }

    private fun refreshUi() {
        val s = service ?: return
        val t = s.currentTrack
        val n = s.tracks.size
        tvTitle.text = t?.title ?: "-"
        tvArtist.text = t?.artist ?: "-"
        btnPlay.text = if (s.isPlaying) "❚❚" else "▶"
        tvTrackNo.text = "Track: ${if (t != null) s.index + 1 else 0}/$n"
        tvNext.text = if (t != null && n > 0) {
            val nx = s.tracks[(s.index + 1) % n]
            "${nx.title} / ${nx.artist}"
        } else "-"
        if (t != null && s.index != shownIndex) {
            shownIndex = s.index
            loadInfo(s.index, t)
        }
        updateProgress()
    }

    private fun updateProgress() {
        val s = service
        if (s != null) {
            if (!dragging) {
                seek.max = s.duration
                seek.progress = s.position
            }
            tvTime.text = "-" + fmt(maxOf(0, s.duration - s.position))
            wave.step(s.isPlaying)
        } else {
            wave.step(false)
        }
        syncVolume()
    }
}
