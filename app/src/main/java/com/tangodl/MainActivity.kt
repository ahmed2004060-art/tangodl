package com.tangodl

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Context
import android.graphics.Color
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class MainActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())
    private val found = LinkedHashSet<String>()
    private val queue = Executors.newSingleThreadExecutor()

    private val tabs = ArrayList<Tab>()
    private var cur: Tab? = null

    private lateinit var container: FrameLayout
    private lateinit var tabsRow: LinearLayout
    private lateinit var plusBtn: Button
    private lateinit var list: LinearLayout
    private lateinit var urlBox: EditText

    private val adHosts = listOf(
        "applovin", "doubleclick", "googlesyndication", "googleadservices", "adservice.google",
        "admob", "adnxs", "pangle", "unityads", "mopub", "inmobi", "taboola", "outbrain",
        "criteo", "adcolony", "vungle", "chartboost", "ironsrc", "adjust.com", "appsflyer"
    )

    inner class Tab(val web: WebView) {
        lateinit var btn: Button
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL

        // شريط العنوان
        val bar = LinearLayout(this)
        urlBox = EditText(this)
        urlBox.setSingleLine()
        urlBox.setText("https://www.tango.me/soultna")
        val go = Button(this)
        go.text = "فتح"
        go.setOnClickListener { load(urlBox.text.toString()) }
        bar.addView(urlBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(go)

        // أدوات: مفضلة / مسح القائمة
        val tools = LinearLayout(this)
        val star = Button(this)
        star.text = "★ حفظ"
        star.setOnClickListener { addBookmark() }
        val bm = Button(this)
        bm.text = "المفضلة"
        bm.setOnClickListener { showBookmarks() }
        val clear = Button(this)
        clear.text = "مسح القائمة"
        clear.setOnClickListener { list.removeAllViews(); found.clear() }
        for (v in listOf(star, bm, clear)) {
            tools.addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }

        // التبويبات
        val tabsScroll = HorizontalScrollView(this)
        tabsRow = LinearLayout(this)
        plusBtn = Button(this)
        plusBtn.text = "+"
        plusBtn.setOnClickListener { newTab("https://www.tango.me") }
        tabsRow.addView(plusBtn)
        tabsScroll.addView(tabsRow)

        container = FrameLayout(this)

        val sv = ScrollView(this)
        list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        sv.addView(list)

        root.addView(bar)
        root.addView(tools)
        root.addView(tabsScroll)
        root.addView(container, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 3f))
        root.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.6f))
        setContentView(root)

        newTab(urlBox.text.toString())
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush() // يحفظ تسجيل الدخول
    }

    override fun onBackPressed() {
        val t = cur
        if (t != null && t.web.canGoBack()) t.web.goBack() else super.onBackPressed()
    }

    // ---------- المتصفح والتبويبات ----------

    private fun makeWeb(): WebView {
        val w = WebView(this)
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.settings.mediaPlaybackRequiresUserGesture = false
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
        w.webChromeClient = WebChromeClient()
        w.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse? {
                val u = r.url.toString()
                val low = u.lowercase()
                if (low.contains(".m3u8") || low.contains(".mp4") || low.contains("mpegurl")) {
                    val h = HashMap<String, String>(r.requestHeaders)
                    h["Cookie"] = CookieManager.getInstance().getCookie(u) ?: ""
                    if (h["Referer"] == null) h["Referer"] = v.url ?: ""
                    h["User-Agent"] = v.settings.userAgentString
                    ui.post { addFound(u, h) }
                    return null
                }
                val host = r.url.host?.lowercase() ?: ""
                for (a in adHosts) {
                    if (host.contains(a)) {
                        return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                    }
                }
                return null
            }

            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                val s = r.url.scheme ?: ""
                return !(s == "http" || s == "https")
            }

            override fun onPageStarted(v: WebView, url: String, f: android.graphics.Bitmap?) {
                if (cur?.web === v) urlBox.setText(url)
            }
        }
        return w
    }

    private fun newTab(url: String) {
        val t = Tab(makeWeb())
        val btn = Button(this)
        btn.setOnClickListener { switchTo(t) }
        btn.setOnLongClickListener { closeTab(t); true }
        t.btn = btn
        tabs.add(t)
        container.addView(t.web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        tabsRow.addView(btn, tabsRow.childCount - 1)
        relabel()
        switchTo(t)
        t.web.loadUrl(fix(url))
    }

    private fun closeTab(t: Tab) {
        if (tabs.size <= 1) return
        val i = tabs.indexOf(t)
        tabs.remove(t)
        container.removeView(t.web)
        tabsRow.removeView(t.btn)
        t.web.destroy()
        relabel()
        switchTo(tabs[if (i > 0) i - 1 else 0])
    }

    private fun relabel() {
        for (i in tabs.indices) tabs[i].btn.text = "تبويب " + (i + 1)
    }

    private fun switchTo(t: Tab) {
        cur = t
        for (x in tabs) {
            x.web.visibility = if (x === t) View.VISIBLE else View.GONE
            x.btn.alpha = if (x === t) 1f else 0.55f
        }
        urlBox.setText(t.web.url ?: "")
    }

    private fun fix(u: String): String {
        val s = u.trim()
        return if (s.startsWith("http://") || s.startsWith("https://")) s else "https://$s"
    }

    private fun load(u: String) {
        cur?.web?.loadUrl(fix(u))
    }

    // ---------- المفضلة ----------

    private fun prefs() = getSharedPreferences("tdl", Context.MODE_PRIVATE)

    private fun addBookmark() {
        val u = cur?.web?.url ?: return
        val set = HashSet<String>(prefs().getStringSet("bm", HashSet<String>()) ?: HashSet<String>())
        set.add(u)
        prefs().edit().putStringSet("bm", set).apply()
        android.widget.Toast.makeText(this, "تم الحفظ", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun showBookmarks() {
        val items = (prefs().getStringSet("bm", HashSet<String>()) ?: HashSet<String>()).toList().sorted()
        if (items.isEmpty()) {
            android.widget.Toast.makeText(this, "المفضلة فاضية", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("المفضلة")
            .setItems(items.toTypedArray()) { _, i -> load(items[i]) }
            .setNegativeButton("مسح الكل") { _, _ -> prefs().edit().remove("bm").apply() }
            .show()
    }

    // ---------- قائمة الروابط الملتقطة ----------

    private fun addFound(u: String, h: Map<String, String>) {
        if (found.contains(u)) return
        found.add(u)

        val row = LinearLayout(this)
        row.orientation = LinearLayout.VERTICAL
        row.setPadding(8, 8, 8, 8)

        val title = TextView(this)
        title.text = u.substringAfterLast('/').substringBefore('?').take(50)
        val msg = TextView(this)
        msg.setTextColor(Color.GRAY)
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        bar.max = 100
        bar.visibility = View.GONE
        val btn = Button(this)
        btn.text = "تحميل"
        btn.setOnClickListener {
            btn.isEnabled = false
            bar.visibility = View.VISIBLE
            msg.text = "في الانتظار..."
            enqueue(u, h, bar, msg, btn)
        }
        row.addView(title)
        row.addView(btn)
        row.addView(bar)
        row.addView(msg)
        list.addView(row, 0)
    }

    private fun enqueue(u: String, h: Map<String, String>, bar: ProgressBar, msg: TextView, btn: Button) {
        val progress = { done: Int, total: Int ->
            ui.post {
                bar.max = if (total > 0) total else 100
                bar.progress = done
                msg.text = if (total > 0) "$done / $total" else "جاري التحميل..."
            }
        }
        queue.execute {
            try {
                val dir = getExternalFilesDir("Download")!!
                dir.mkdirs()
                val name = "tango_" + System.currentTimeMillis()
                val result: String
                if (u.lowercase().contains(".m3u8")) {
                    val ts = File(dir, "$name.ts")
                    hls(u, h, ts, progress)
                    ui.post { msg.text = "جاري التحويل إلى mp4..." }
                    val mp4 = File(dir, "$name.mp4")
                    if (remux(ts, mp4)) {
                        ts.delete()
                        result = publish(mp4, "video/mp4")
                    } else {
                        mp4.delete()
                        result = publish(ts, "video/mp2t")
                    }
                } else {
                    val mp4 = File(dir, "$name.mp4")
                    direct(u, h, mp4, progress)
                    result = publish(mp4, "video/mp4")
                }
                ui.post { msg.text = "تم: $result"; bar.progress = bar.max }
            } catch (e: Exception) {
                ui.post { msg.text = "فشل: " + (e.message ?: e.javaClass.simpleName); btn.isEnabled = true }
            }
        }
    }

    // ---------- الشبكة ----------

    private fun open(u: String, h: Map<String, String>): HttpURLConnection {
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 30000
        for ((k, v) in h) {
            if (v.isNotEmpty() && k != "Range") c.setRequestProperty(k, v)
        }
        return c
    }

    private fun text(u: String, h: Map<String, String>): String =
        open(u, h).inputStream.bufferedReader().use { it.readText() }

    private fun bytes(u: String, h: Map<String, String>): ByteArray =
        open(u, h).inputStream.use { it.readBytes() }

    private fun abs(base: String, ref: String): String = URL(URL(base), ref).toString()

    private fun direct(u: String, h: Map<String, String>, out: File, progress: (Int, Int) -> Unit) {
        val c = open(u, h)
        val total = c.contentLength
        var done = 0L
        c.inputStream.use { i ->
            out.outputStream().use { o ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = i.read(buf)
                    if (n < 0) break
                    o.write(buf, 0, n)
                    done += n
                    if (total > 0) progress((done * 100 / total).toInt(), 100) else progress(0, 0)
                }
            }
        }
    }

    private fun hls(u0: String, h: Map<String, String>, out: File, progress: (Int, Int) -> Unit) {
        var url = u0
        var pl = text(url, h)

        if (pl.contains("#EXT-X-STREAM-INF")) {
            var best = ""
            var bw = -1L
            val ls = pl.lines()
            for (i in ls.indices) {
                if (ls[i].startsWith("#EXT-X-STREAM-INF")) {
                    val bwv = Regex("BANDWIDTH=(\\d+)").find(ls[i])?.groupValues?.get(1)?.toLong() ?: 0L
                    val nx = ls.drop(i + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") } ?: continue
                    if (bwv > bw) {
                        bw = bwv
                        best = nx
                    }
                }
            }
            url = abs(url, best.trim())
            pl = text(url, h)
        }

        var keyBytes: ByteArray? = null
        var ivHex: String? = null
        val segs = ArrayList<String>()
        for (l in pl.lines()) {
            if (l.startsWith("#EXT-X-KEY") && l.contains("AES-128")) {
                val ku = Regex("URI=\"([^\"]+)\"").find(l)!!.groupValues[1]
                keyBytes = bytes(abs(url, ku), h)
                ivHex = Regex("IV=0x([0-9A-Fa-f]+)").find(l)?.groupValues?.get(1)
            } else if (l.isNotBlank() && !l.startsWith("#")) {
                segs.add(abs(url, l.trim()))
            }
        }
        if (segs.isEmpty()) throw Exception("القائمة فاضية")

        val seq = Regex("#EXT-X-MEDIA-SEQUENCE:(\\d+)").find(pl)?.groupValues?.get(1)?.toInt() ?: 0
        val key = keyBytes
        val ivh = ivHex
        val pool = Executors.newFixedThreadPool(3)
        try {
            val futures = segs.mapIndexed { i, s ->
                pool.submit(Callable<ByteArray> {
                    var d = bytes(s, h)
                    if (key != null) {
                        val iv: ByteArray
                        if (ivh != null) {
                            iv = ivh.padStart(32, '0').chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                        } else {
                            iv = ByteArray(16)
                            val n = seq + i
                            for (k in 0..3) iv[15 - k] = (n shr (8 * k)).toByte()
                        }
                        val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
                        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
                        d = c.doFinal(d)
                    }
                    d
                })
            }
            out.outputStream().use { o ->
                for (i in futures.indices) {
                    o.write(futures[i].get())
                    progress(i + 1, futures.size)
                }
            }
        } finally {
            pool.shutdownNow()
        }
    }

    // ---------- التحويل والحفظ ----------

    // يحوّل ts إلى mp4 بدون إعادة ترميز
    private fun remux(ts: File, mp4: File): Boolean {
        val ex = MediaExtractor()
        var mux: MediaMuxer? = null
        var started = false
        try {
            ex.setDataSource(ts.absolutePath)
            mux = MediaMuxer(mp4.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val map = HashMap<Int, Int>()
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    map[i] = mux.addTrack(f)
                    ex.selectTrack(i)
                }
            }
            if (map.isEmpty()) return false
            mux.start()
            started = true
            val buf = ByteBuffer.allocate(4 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            while (true) {
                val n = ex.readSampleData(buf, 0)
                if (n < 0) break
                info.offset = 0
                info.size = n
                info.presentationTimeUs = ex.sampleTime
                info.flags = if ((ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                val t = map[ex.sampleTrackIndex]
                if (t != null) mux.writeSampleData(t, buf, info)
                ex.advance()
            }
            mux.stop()
            started = false
            return true
        } catch (e: Exception) {
            return false
        } finally {
            try { ex.release() } catch (e: Exception) { }
            try { if (started) mux?.stop() } catch (e: Exception) { }
            try { mux?.release() } catch (e: Exception) { }
        }
    }

    // ينقل الملف لمعرض الفيديو (Movies/TangoDL) في أندرويد 10+
    private fun publish(f: File, mime: String): String {
        if (Build.VERSION.SDK_INT < 29) return f.absolutePath
        return try {
            val v = ContentValues()
            v.put(MediaStore.Video.Media.DISPLAY_NAME, f.name)
            v.put(MediaStore.Video.Media.MIME_TYPE, mime)
            v.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/TangoDL")
            val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v)
                ?: return f.absolutePath
            contentResolver.openOutputStream(uri)!!.use { o ->
                f.inputStream().use { it.copyTo(o) }
            }
            f.delete()
            "Movies/TangoDL/" + f.name
        } catch (e: Exception) {
            f.absolutePath
        }
    }
}
