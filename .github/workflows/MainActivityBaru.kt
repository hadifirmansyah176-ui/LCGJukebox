package com.hadi.lcgjukebox

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
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
import android.net.Uri
import android.os.*
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import kotlin.math.sin

private val NAVY = 0xFF1B2A6B.toInt()
private val PANEL = 0xFFE6EBFC.toInt()
private val RED = 0xFFD32F2F.toInt()
private val BLUE = 0xFF1E5BFF.toInt()
private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
private val AUDIO_EXT = setOf("mp3", "m4a", "aac", "wav", "ogg", "flac", "wma", "opus", "amr")
private const val REQ_FILES = 11
private const val REQ_DIR = 12

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
    private var metaIndex = -1
    private var metaArtist = ""
    private var metaTitle = ""

    private val audio by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private val prefs by lazy { getSharedPreferences("jukebox", Context.MODE_PRIVATE) }

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
    private lateinit var btnShuffle: TextView
    private lateinit var dotShuffle: View
    private lateinit var tvCount: TextView
    private lateinit var list: ListView
    private val adapter = TrackAdapter()
    private var listIndex = -2

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
            val wantShuffle = prefs.getBoolean("shuffle", false)
            if (s.shuffle != wantShuffle) s.shuffle = wantShuffle
            shownIndex = -2
            refreshUi()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
        }
    }

    private inner class TrackAdapter : BaseAdapter() {
        override fun getCount(): Int = tracks.size
        override fun getItem(position: Int): Any = tracks[position]
        override fun getItemId(position: Int): Long = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val tv = (convertView as? TextView) ?: TextView(this@MainActivity).apply {
                textSize = 14f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(px(8), px(10), px(8), px(10))
            }
            val t = tracks[position]
            val extra = if (t.artist == "Tidak diketahui" || t.artist.isEmpty()) "" else " — ${t.artist}"
            tv.text = "${position + 1}. ${t.title}$extra"
            val current = service?.index == position
            tv.setTextColor(if (current) Color.WHITE else NAVY)
            tv.setBackgroundColor(if (current) NAVY else Color.TRANSPARENT)
            return tv
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

        // Baris atas: waktu + sampul (kiri), gelombang + volume + ACAK (kanan)
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tvTime = label("-0:00", 16f, bold = true).apply {
            gravity = Gravity.CENTER
            background = box(PANEL, NAVY)
            setPadding(px(8), px(3), px(8), px(3))
        }
        left.addView(tvTime, lp(MATCH, WRAP))
        art = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = box(0xFF5A6BB5.toInt(), NAVY, 6f)
            setPadding(px(2), px(2), px(2), px(2))
        }
        left.addView(art, lp(px(88), px(88)).apply { topMargin = px(6) })

        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        wave = WaveView(this).apply { background = box(0xFF2A3A8A.toInt(), NAVY) }
        right.addView(wave, lp(MATCH, px(36)))
        val volRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val volCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        tvVol = label("0", 20f, bold = true).apply { gravity = Gravity.CENTER }
        volCol.addView(tvVol, lp(WRAP, WRAP))

        val shuffleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        btnShuffle = label("ACAK", 12f, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(px(10), px(5), px(10), px(5))
            setOnClickListener {
                val s = service
                if (s != null) {
                    s.shuffle = !s.shuffle
                    prefs.edit().putBoolean("shuffle", s.shuffle).apply()
                }
            }
        }
        dotShuffle = View(this)
        shuffleRow.addView(btnShuffle, lp(WRAP, WRAP))
        shuffleRow.addView(dotShuffle, lp(px(16), px(16)).apply { leftMargin = px(8) })
        volCol.addView(shuffleRow, lp(WRAP, WRAP).apply { topMargin = px(6) })
        updateShuffleUi(false)

        volRow.addView(volCol, lp(0, WRAP, 1f))
        volView = VolumeView(this) { l -> setVolume(l) }
        volRow.addView(volView, lp(px(52), px(76)))
        right.addView(volRow, lp(MATCH, WRAP).apply { topMargin = px(6) })

        top.addView(left, lp(0, WRAP, 1f))
        top.addView(right, lp(0, WRAP, 1f).apply { leftMargin = px(10) })
        body.addView(top, lp(MATCH, WRAP))

        // Panel info lagu
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = box(PANEL, NAVY)
            setPadding(px(10), px(4), px(10), px(4))
        }
        tvBitrate = label("", 10f).apply { gravity = Gravity.END }
        info.addView(tvBitrate, lp(MATCH, WRAP))
        val artistRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        artistRow.addView(label("Artist: ", 12f, italic = true), lp(WRAP, WRAP))
        tvArtist = label("-", 14f, bold = true)
        artistRow.addView(tvArtist, lp(0, WRAP, 1f))
        info.addView(artistRow, lp(MATCH, WRAP))
        val titleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        titleRow.addView(label("Title: ", 12f, italic = true), lp(WRAP, WRAP))
        tvTitle = label("-", 14f, bold = true)
        titleRow.addView(tvTitle, lp(0, WRAP, 1f))
        info.addView(titleRow, lp(MATCH, WRAP))
        body.addView(info, lp(MATCH, WRAP).apply { topMargin = px(8) })

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
        body.addView(seek, lp(MATCH, WRAP).apply { topMargin = px(8) })

        // Panel lagu berikutnya
        val next = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = box(PANEL, NAVY)
            setPadding(px(10), px(4), px(10), px(4))
        }
        val nextRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        nextRow.addView(label("Next:", 12f, italic = true), lp(WRAP, WRAP))
        tvTrackNo = label("Track: 0/0", 12f).apply { gravity = Gravity.END }
        nextRow.addView(tvTrackNo, lp(0, WRAP, 1f))
        next.addView(nextRow, lp(MATCH, WRAP))
        tvNext = label("-", 14f, bold = true)
        next.addView(tvNext, lp(MATCH, WRAP))
        body.addView(next, lp(MATCH, WRAP).apply { topMargin = px(8) })

        // Daftar lagu (tampilan depan)
        tvCount = label("Daftar lagu (0)", 13f, bold = true)
        body.addView(tvCount, lp(MATCH, WRAP).apply { topMargin = px(8); bottomMargin = px(4) })
        list = ListView(this).apply {
            background = box(PANEL, NAVY, 6f)
            setPadding(px(2), px(2), px(2), px(2))
            adapter = this@MainActivity.adapter
            setOnItemClickListener { _, _, pos, _ -> service?.play(pos) }
        }
        body.addView(list, lp(MATCH, 0, 1f))

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

    // ---------- Menu ----------

    private fun showMenu(anchor: View) {
        val pm = PopupMenu(this, anchor)
        pm.menu.add(0, 1, 0, "Tambah lagu / folder")
        pm.menu.add(0, 2, 1, "Semua lagu di HP")
        pm.menu.add(0, 3, 2, "Tentang")
        pm.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> showAddDialog()
                2 -> {
                    saveSources(emptySet())
                    loadTracks(fresh = true)
                }
                3 -> showAbout()
            }
            true
        }
        pm.show()
    }

    private fun showAbout() {
        AlertDialog.Builder(this)
            .setTitle("Tentang")
            .setMessage("Aplikasi ini hanya untuk hiburan pribadi tidak dijual belikan.\n\nby.Anonim 11/2026")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun updateShuffleUi(on: Boolean) {
        btnShuffle.background = if (on) box(RED, 0xFF8E1B1B.toInt(), 6f) else box(PANEL, NAVY, 6f)
        btnShuffle.setTextColor(if (on) Color.WHITE else NAVY)
        dotShuffle.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (on) RED else BLUE)
            setStroke(px(2), NAVY)
        }
    }

    private fun refreshList() {
        tvCount.text = "Daftar lagu (${tracks.size})"
        adapter.notifyDataSetChanged()
    }

    private fun showAddDialog() {
        AlertDialog.Builder(this)
            .setTitle("Tambah lagu / folder")
            .setItems(arrayOf("Pilih lagu (file)", "Pilih folder")) { _, which ->
                if (which == 0) pickFiles() else pickFolder()
            }
            .show()
    }

    private fun pickFiles() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        launchPicker(i, REQ_FILES)
    }

    private fun pickFolder() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        launchPicker(i, REQ_DIR)
    }

    private fun launchPicker(i: Intent, code: Int) {
        try {
            startActivityForResult(i, code)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Aplikasi pemilih file tidak ditemukan", Toast.LENGTH_LONG).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        val added = mutableSetOf<String>()
        if (requestCode == REQ_DIR) {
            val u = data.data ?: return
            keepAccess(u)
            added.add("D|$u")
        } else if (requestCode == REQ_FILES) {
            val uris = mutableListOf<Uri>()
            val cd = data.clipData
            if (cd != null) {
                for (i in 0 until cd.itemCount) uris.add(cd.getItemAt(i).uri)
            }
            val single = data.data
            if (single != null && !uris.contains(single)) uris.add(single)
            for (u in uris) {
                keepAccess(u)
                added.add("F|$u")
            }
        } else {
            return
        }
        if (added.isEmpty()) return
        val all = HashSet(sources())
        all.addAll(added)
        saveSources(all)
        loadTracks(fresh = true)
    }

    private fun keepAccess(u: Uri) {
        try {
            contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Exception) {
        }
    }

    // ---------- Volume ----------

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

    // ---------- Izin & daftar lagu ----------

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
        if (res.isEmpty() || res[0] != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Tanpa izin, gunakan Menu → Tambah lagu / folder", Toast.LENGTH_LONG).show()
        }
        loadTracks()
    }

    private fun sources(): Set<String> = prefs.getStringSet("sources", null) ?: emptySet()

    private fun saveSources(s: Set<String>) {
        prefs.edit().putStringSet("sources", HashSet(s)).apply()
    }

    private fun isAudio(name: String, mime: String): Boolean =
        mime.startsWith("audio/") || name.substringAfterLast('.', "").lowercase() in AUDIO_EXT

    private fun queryMediaStore(): List<Track> {
        val base = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST
        )
        val result = mutableListOf<Track>()
        try {
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
                            if (artist == null || artist == "<unknown>") "Tidak diketahui" else artist
                        )
                    )
                }
            }
        } catch (e: Exception) {
        }
        return result
    }

    private fun nameOf(uri: Uri): String {
        var n = ""
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) n = c.getString(0) ?: ""
            }
        } catch (e: Exception) {
        }
        return n
    }

    private fun collectTree(tree: Uri, docId: String, out: MutableList<Track>) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val subdirs = mutableListOf<String>()
        contentResolver.query(
            children,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            ),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0)
                val name = c.getString(1) ?: ""
                val mime = c.getString(2) ?: ""
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    subdirs.add(id)
                } else if (isAudio(name, mime)) {
                    out.add(
                        Track(
                            DocumentsContract.buildDocumentUriUsingTree(tree, id),
                            name.substringBeforeLast('.'),
                            "Tidak diketahui"
                        )
                    )
                }
            }
        }
        for (d in subdirs) collectTree(tree, d, out)
    }

    private fun buildTracks(): List<Track> {
        val src = sources()
        if (src.isEmpty()) return queryMediaStore()
        val out = mutableListOf<Track>()
        for (s in src) {
            val uri = Uri.parse(s.substring(2))
            try {
                if (s.startsWith("D|")) {
                    collectTree(uri, DocumentsContract.getTreeDocumentId(uri), out)
                } else {
                    val title = nameOf(uri).substringBeforeLast('.').ifEmpty { "Lagu" }
                    out.add(Track(uri, title, "Tidak diketahui"))
                }
            } catch (e: Exception) {
            }
        }
        return out.distinctBy { it.uri.toString() }.sortedBy { it.title.lowercase() }
    }

    private fun loadTracks(fresh: Boolean = false) {
        Toast.makeText(this, "Memuat daftar lagu…", Toast.LENGTH_SHORT).show()
        Thread {
            val result: List<Track> = try {
                buildTracks()
            } catch (e: Exception) {
                emptyList()
            }
            runOnUiThread {
                tracks = result
                val s = service
                if (s != null) {
                    s.tracks = result
                    if (fresh) {
                        shownIndex = -2
                        if (s.index >= 0 && result.isNotEmpty()) s.play(0)
                    }
                }
                if (result.isEmpty()) {
                    Toast.makeText(this, "Tidak ada lagu ditemukan", Toast.LENGTH_LONG).show()
                }
                listIndex = -2
                refreshList()
                refreshUi()
            }
        }.start()
    }

    // ---------- Info lagu ----------

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
            var mArtist = ""
            var mTitle = ""
            try {
                val r = MediaMetadataRetriever()
                r.setDataSource(ctx, t.uri)
                val pic = r.embeddedPicture
                if (pic != null) bmp = decode(pic)
                val br = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()
                if (br != null) kbps = "${br / 1000}kbps"
                mArtist = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: ""
                mTitle = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: ""
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
                    metaIndex = index
                    metaArtist = mArtist
                    metaTitle = mTitle
                    refreshUi()
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
        val hasMeta = t != null && metaIndex == s.index
        tvTitle.text = if (hasMeta && metaTitle.isNotEmpty()) metaTitle else (t?.title ?: "-")
        tvArtist.text = if (hasMeta && metaArtist.isNotEmpty()) metaArtist
        else if (t == null || t.artist == "Tidak diketahui") "-" else t.artist
        btnPlay.text = if (s.isPlaying) "❚❚" else "▶"
        tvTrackNo.text = "Track: ${if (t != null) s.index + 1 else 0}/$n"
        val ni = s.nextIndex()
        tvNext.text = if (t != null && ni in 0 until n) s.tracks[ni].title else "-"
        updateShuffleUi(s.shuffle)
        if (s.index != listIndex) {
            listIndex = s.index
            adapter.notifyDataSetChanged()
            val i = s.index
            if (i >= 0 && (i < list.firstVisiblePosition || i > list.lastVisiblePosition)) list.setSelection(i)
        }
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
