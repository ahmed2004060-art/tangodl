package com.tangodl

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebSettings
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.googlecode.mp4parser.FileDataSourceImpl
import com.googlecode.mp4parser.authoring.Movie
import com.googlecode.mp4parser.authoring.Track
import com.googlecode.mp4parser.authoring.builder.DefaultMp4Builder
import com.googlecode.mp4parser.authoring.container.mp4.MovieCreator
import com.googlecode.mp4parser.authoring.tracks.AACTrackImpl
import com.googlecode.mp4parser.authoring.tracks.AppendTrack
import com.googlecode.mp4parser.authoring.tracks.h264.H264TrackImpl
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class MainActivity : Activity() {

    private val HOME = "https://www.tango.me/soultna"
    private val DESKTOP_UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private val ui = Handler(Looper.getMainLooper())
    private val found = LinkedHashSet<String>()
    private val queue = Executors.newFixedThreadPool(2)
    private val pages = java.util.concurrent.ConcurrentHashMap<WebView, String>()
    private val tabs = ArrayList<Tab>()
    private var cur: Tab? = null
    private var page = 0

    @Volatile private var adBlock = true
    @Volatile private var curUa = ""

    private var dark = true
    private var cBg = 0
    private var cBar = 0
    private var cCard = 0
    private var cText = 0
    private var cMuted = 0
    private var cLine = 0
    private var cAccent = 0
    private var cAccentSoft = 0

    private lateinit var root: LinearLayout
    private lateinit var pageHost: FrameLayout
    private val pagesList = ArrayList<View>()
    private val navItems = ArrayList<Pair<ImageView, TextView>>()
    private lateinit var container: FrameLayout
    private lateinit var tabsRow: LinearLayout
    private lateinit var plusChip: TextView
    private lateinit var urlBox: EditText
    private lateinit var loadBar: ProgressBar
    private lateinit var pct: TextView
    private lateinit var reloadBtn: ImageView
    private lateinit var badge: TextView
    private lateinit var list: LinearLayout
    private lateinit var emptyTv: TextView
    private lateinit var manualBox: EditText

    // تنبيهات التطبيق
    private class AlertItem(val time: Long, val kind: String, val msg: String, val detail: String)
    private val alerts = ArrayList<AlertItem>()
    private lateinit var alertList: LinearLayout
    private lateinit var alertEmpty: TextView
    private lateinit var tabBtn: TextView
    private lateinit var activeBox: LinearLayout
    private lateinit var activeTitle: TextView

    // بدء تحميل كل رابط ملتقط وهيدرزه (لأزرار الفيديو داخل الصفحة)
    private val foundStart = HashMap<String, () -> Unit>()
    private val foundHdr = HashMap<String, Map<String, String>>()
    private val imgPool = Executors.newFixedThreadPool(2)
    private class Variant(val label: String, val url: String)

    private class Ctl {
        @Volatile var cancelled = false
        @Volatile var paused = false
        val bytes = java.util.concurrent.atomic.AtomicLong(0)
    }
    private class CancelledException : Exception("أُلغي التحميل")
    private class Row(val bar: ProgressBar, val msg: TextView, val btn: View, val pause: TextView, val cancel: TextView, val ctl: Ctl, val view: View)

    // يوقف الخيط أثناء الإيقاف المؤقت ويرمي استثناء عند الإلغاء
    private fun gate(c: Ctl) {
        while (c.paused && !c.cancelled) Thread.sleep(200)
        if (c.cancelled) throw CancelledException()
    }

    private fun fmtSize(b: Double): String = when {
        b >= 1048576.0 -> String.format(java.util.Locale.US, "%.1f MB", b / 1048576.0)
        b >= 1024.0 -> String.format(java.util.Locale.US, "%.0f KB", b / 1024.0)
        else -> b.toLong().toString() + " B"
    }

    private fun fmtTime(sec: Long): String {
        val m = sec / 60
        val x = sec % 60
        return if (m >= 60) String.format(java.util.Locale.US, "%d:%02d:%02d", m / 60, m % 60, x)
        else String.format(java.util.Locale.US, "%02d:%02d", m, x)
    }

    // ينقل صف التحميل إلى قسم "جارٍ التحميل" أثناء النزول ثم يعيده للقائمة
    private fun setActive(r: Row, on: Boolean) {
        (r.view.parent as? ViewGroup)?.removeView(r.view)
        if (on) activeBox.addView(r.view, 0, lp(MATCH, WRAP, 0f, 0, 0, 0, 10))
        else list.addView(r.view, 0, lp(MATCH, WRAP, 0f, 0, 0, 0, 10))
        activeTitle.visibility = if (activeBox.childCount > 0) View.VISIBLE else View.GONE
        updateCounts()
    }

    private fun saver() = prefs().getBoolean("saver", false)
    private fun lowQ() = saver() || prefs().getString("quality", "high") == "low"
    private fun baseCache() = if (saver()) WebSettings.LOAD_CACHE_ELSE_NETWORK else WebSettings.LOAD_DEFAULT

    // الصفحات المغلقة مؤخراً (حالة محفوظة على القرص)
    private class Closed(val id: String, val title: String, val url: String)
    private val closed = ArrayList<Closed>()

    private val adHosts = listOf(
        "applovin", "doubleclick", "googlesyndication", "googleadservices", "adservice.google",
        "admob", "adnxs", "pangle", "unityads", "mopub", "inmobi", "taboola", "outbrain",
        "criteo", "adcolony", "vungle", "chartboost", "ironsrc", "adjust.com", "appsflyer"
    )

    inner class Tab(val web: WebView) {
        lateinit var chip: LinearLayout
        lateinit var label: TextView
        lateinit var close: ImageView
        var progress = 0
        var loading = false
        var title = ""
        var restoring = false
        var injected = false
    }

    // ---------- أدوات مساعدة للواجهة ----------

    private fun dp(n: Int): Int = (n * resources.displayMetrics.density + 0.5f).toInt()

    private fun lp(w: Int, h: Int, wt: Float = 0f, l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(w, h, wt)
        p.setMargins(dp(l), dp(t), dp(r), dp(b))
        return p
    }

    private fun bg(color: Int, r: Int, stroke: Int = 0): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = dp(r).toFloat()
        if (stroke != 0) g.setStroke(dp(1), stroke)
        return g
    }

    private fun tv(t: String, size: Float = 15f, color: Int = cText, bold: Boolean = false): TextView {
        val v = TextView(this)
        v.text = t
        v.textSize = size
        v.setTextColor(color)
        if (bold) v.setTypeface(null, Typeface.BOLD)
        return v
    }

    private fun styleChip(t: TextView, on: Boolean) {
        t.setTextColor(if (on) Color.WHITE else cAccent)
        t.background = if (on) bg(cAccent, 18) else bg(Color.TRANSPARENT, 18, cAccent)
    }

    private fun pill(text: String, filled: Boolean, onClick: () -> Unit): TextView {
        val t = tv(text, 13f, Color.WHITE, true)
        t.gravity = Gravity.CENTER
        t.setPadding(dp(14), dp(8), dp(14), dp(8))
        styleChip(t, filled)
        t.setOnClickListener { onClick() }
        return t
    }

    private fun iconBtn(res: Int, tint: Int = cText, onClick: () -> Unit): ImageView {
        val i = ImageView(this)
        i.setImageResource(res)
        i.setColorFilter(tint)
        i.setPadding(dp(8), dp(8), dp(8), dp(8))
        i.setOnClickListener { onClick() }
        return i
    }

    private fun toast(s: String) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    }

    private fun copyText(s: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("url", s))
        toast("تم النسخ")
    }

    private fun hideKb() {
        val im = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        im.hideSoftInputFromWindow(root.windowToken, 0)
    }

    private fun initColors() {
        if (dark) {
            cBg = Color.parseColor("#0F1318")
            cBar = Color.parseColor("#171C23")
            cCard = Color.parseColor("#1E252E")
            cText = Color.parseColor("#ECEFF4")
            cMuted = Color.parseColor("#93A0B1")
            cLine = Color.parseColor("#2A323D")
            cAccent = Color.parseColor("#5B9BFF")
            cAccentSoft = Color.parseColor("#27415F")
        } else {
            cBg = Color.parseColor("#F3F5F8")
            cBar = Color.parseColor("#FFFFFF")
            cCard = Color.parseColor("#FFFFFF")
            cText = Color.parseColor("#1B1F24")
            cMuted = Color.parseColor("#5B6470")
            cLine = Color.parseColor("#D8DCE2")
            cAccent = Color.parseColor("#0B6BCB")
            cAccentSoft = Color.parseColor("#D6E6FA")
        }
    }

    private fun prefs() = getSharedPreferences("tdl", Context.MODE_PRIVATE)

    // ---------- دورة الحياة ----------

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val prevH = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { th, e ->
            try {
                val line = listOf(
                    System.currentTimeMillis().toString(), "انهيار",
                    "توقف التطبيق بسبب خطأ غير متوقع (" + th.name + ")",
                    android.util.Log.getStackTraceString(e).lines().take(14).joinToString("␤")
                ).joinToString("\t")
                File(filesDir, "alerts.tsv").appendText(line + "\n")
            } catch (x: Throwable) {
            }
            prevH?.uncaughtException(th, e)
        }
        dark = prefs().getBoolean("dark", true)
        adBlock = prefs().getBoolean("adblock", true)
        initColors()
        window.statusBarColor = cBar
        window.navigationBarColor = cBar
        if (!dark) {
            var f = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            if (Build.VERSION.SDK_INT >= 26) f = f or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            window.decorView.systemUiVisibility = f
        }

        root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(cBg)
        root.layoutDirection = View.LAYOUT_DIRECTION_RTL

        pageHost = FrameLayout(this)
        pagesList.add(buildBrowser())
        pagesList.add(buildDownloads())
        pagesList.add(buildTools())
        pagesList.add(buildSettings())
        pagesList.add(buildAlerts())
        for (p in pagesList) pageHost.addView(p, FrameLayout.LayoutParams(MATCH, MATCH))

        root.addView(pageHost, lp(MATCH, 0, 1f))
        root.addView(buildNav(), lp(MATCH, WRAP))
        setContentView(root)
        loadAlerts()
        loadClosed()
        cleanOldWork()

        if (Build.VERSION.SDK_INT in 23..28 &&
            checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
        }

        showPage(0)
        val saved = b?.getStringArrayList("urls")
        if (saved != null && saved.isNotEmpty()) {
            for (u in saved) newTab(u)
            val ci = b?.getInt("cur", 0) ?: 0
            if (ci in tabs.indices) switchTo(tabs[ci])
        } else {
            newTab(prefs().getString("home", HOME) ?: HOME)
        }
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        val urls = ArrayList<String>()
        for (t in tabs) urls.add(t.web.url ?: "")
        out.putStringArrayList("urls", urls)
        out.putInt("cur", tabs.indexOf(cur))
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush() // يحفظ تسجيل الدخول
    }

    override fun onBackPressed() {
        if (page != 0) {
            showPage(0)
            return
        }
        val t = cur
        if (t != null && t.web.canGoBack()) t.web.goBack() else super.onBackPressed()
    }

    // ---------- البناء: الشريط السفلي والصفحات ----------

    private fun buildNav(): View {
        val wrap = LinearLayout(this)
        wrap.orientation = LinearLayout.VERTICAL
        val line = View(this)
        line.setBackgroundColor(cLine)
        wrap.addView(line, lp(MATCH, 1))
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.setBackgroundColor(cBar)
        val defs = listOf(
            Triple(R.drawable.ic_home, "المتصفح", 0),
            Triple(R.drawable.ic_download, "التحميلات", 1),
            Triple(R.drawable.ic_tools, "الأدوات", 2),
            Triple(R.drawable.ic_settings, "الإعدادات", 3),
            Triple(R.drawable.ic_alert, "التنبيهات", 4)
        )
        for ((ic, label, idx) in defs) {
            val item = LinearLayout(this)
            item.orientation = LinearLayout.VERTICAL
            item.gravity = Gravity.CENTER_HORIZONTAL
            item.setPadding(0, dp(7), 0, dp(7))
            val im = ImageView(this)
            im.setImageResource(ic)
            val t = tv(label, 11f, cMuted)
            item.addView(im, lp(dp(24), dp(24)))
            item.addView(t, lp(WRAP, WRAP, 0f, 0, 2, 0, 0))
            item.setOnClickListener { showPage(idx) }
            row.addView(item, lp(0, WRAP, 1f))
            navItems.add(Pair(im, t))
        }
        wrap.addView(row, lp(MATCH, WRAP))
        return wrap
    }

    private fun showPage(i: Int) {
        page = i
        hideKb()
        for (k in pagesList.indices) pagesList[k].visibility = if (k == i) View.VISIBLE else View.GONE
        for (k in navItems.indices) {
            val c = if (k == i) cAccent else cMuted
            navItems[k].first.setColorFilter(c)
            navItems[k].second.setTextColor(c)
        }
        updateCounts()
    }

    private fun buildBrowser(): View {
        val frame = FrameLayout(this)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL

        // شريط العنوان
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setBackgroundColor(cBar)
        bar.setPadding(dp(6), dp(6), dp(6), dp(6))
        bar.addView(iconBtn(R.drawable.ic_back) { cur?.web?.let { if (it.canGoBack()) it.goBack() } }, lp(dp(38), dp(38)))
        bar.addView(iconBtn(R.drawable.ic_forward) { cur?.web?.let { if (it.canGoForward()) it.goForward() } }, lp(dp(38), dp(38)))

        urlBox = EditText(this)
        urlBox.setTextColor(cText)
        urlBox.setHintTextColor(cMuted)
        urlBox.hint = "ابحث أو اكتب رابطاً"
        urlBox.textSize = 14f
        urlBox.background = bg(cCard, 20, cLine)
        urlBox.setPadding(dp(14), dp(8), dp(14), dp(8))
        urlBox.layoutDirection = View.LAYOUT_DIRECTION_LTR
        urlBox.gravity = Gravity.CENTER_VERTICAL or Gravity.START
        urlBox.setSelectAllOnFocus(true)
        urlBox.imeOptions = EditorInfo.IME_ACTION_GO
        urlBox.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        urlBox.setOnEditorActionListener { _, id, ev ->
            if (id == EditorInfo.IME_ACTION_GO || (ev != null && ev.keyCode == KeyEvent.KEYCODE_ENTER)) {
                load(urlBox.text.toString())
                true
            } else false
        }
        bar.addView(urlBox, lp(0, dp(40), 1f, 4, 0, 4, 0))

        // زر التبويبات بجانب الرابط (يعرض العدد ويفتح قائمة التبويبات)
        tabBtn = tv("1", 14f, cText, true)
        tabBtn.gravity = Gravity.CENTER
        tabBtn.background = bg(cBar, 10, cText)
        tabBtn.setOnClickListener { showTabs() }
        bar.addView(tabBtn, lp(dp(32), dp(32), 0f, 0, 0, 4, 0))

        pct = tv("", 11f, cAccent, true)
        pct.visibility = View.GONE
        bar.addView(pct, lp(WRAP, WRAP, 0f, 0, 0, 2, 0))

        reloadBtn = iconBtn(R.drawable.ic_refresh) {
            val t = cur
            if (t != null) {
                if (t.loading && t.progress < 100) {
                    t.web.stopLoading()
                    t.loading = false
                    updateLoadUi()
                } else {
                    t.web.reload()
                }
            }
        }
        bar.addView(reloadBtn, lp(dp(38), dp(38)))
        col.addView(bar, lp(MATCH, WRAP))

        // شريط تقدّم تحميل الصفحة
        loadBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        loadBar.max = 100
        loadBar.progressTintList = ColorStateList.valueOf(cAccent)
        loadBar.progressBackgroundTintList = ColorStateList.valueOf(cLine)
        loadBar.visibility = View.INVISIBLE
        col.addView(loadBar, lp(MATCH, dp(4)))

        // التبويبات
        val tabsScroll = HorizontalScrollView(this)
        tabsScroll.setBackgroundColor(cBar)
        tabsScroll.isHorizontalScrollBarEnabled = false
        tabsRow = LinearLayout(this)
        tabsRow.orientation = LinearLayout.HORIZONTAL
        tabsRow.setPadding(dp(4), dp(2), dp(4), dp(4))
        plusChip = tv("+", 16f, cAccent, true)
        plusChip.gravity = Gravity.CENTER
        plusChip.background = bg(cCard, 16, cLine)
        plusChip.setPadding(dp(14), dp(4), dp(14), dp(4))
        plusChip.setOnClickListener { newTab(prefs().getString("home", HOME) ?: HOME) }
        tabsRow.addView(plusChip, lp(WRAP, WRAP, 0f, 3, 3, 3, 3))
        tabsScroll.addView(tabsRow)
        // شريط التبويبات القديم لم يعد يُعرض؛ التبويبات من الزر بجانب الرابط

        // منطقة الصفحة: تملأ كل المساحة المتبقية
        container = FrameLayout(this)
        col.addView(container, lp(MATCH, 0, 1f))

        frame.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))

        badge = tv("", 13f, Color.WHITE, true)
        badge.background = bg(cAccent, 22)
        badge.setPadding(dp(16), dp(10), dp(16), dp(10))
        badge.visibility = View.GONE
        badge.setOnClickListener { showPage(1) }
        val bp = FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.END)
        bp.setMargins(dp(14), dp(14), dp(14), dp(14))
        frame.addView(badge, bp)
        return frame
    }

    private fun buildDownloads(): View {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(14), dp(14), dp(14), dp(24))
        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.addView(tv("الفيديوهات الملتقطة", 20f, cText, true), lp(0, WRAP, 1f))
        head.addView(pill("مسح القائمة", false) { clearFound() })
        col.addView(head, lp(MATCH, WRAP, 0f, 0, 0, 0, 6))
        col.addView(tv("شغّل الفيديو داخل المتصفح وستظهر روابطه هنا ثم اضغط تحميل. الملفات تُحفظ في مجلد التحميلات.", 12f, cMuted), lp(MATCH, WRAP, 0f, 0, 0, 0, 12))
        activeTitle = tv("جارٍ التحميل الآن", 15f, cAccent, true)
        activeTitle.visibility = View.GONE
        col.addView(activeTitle, lp(MATCH, WRAP, 0f, 0, 0, 0, 6))
        activeBox = LinearLayout(this)
        activeBox.orientation = LinearLayout.VERTICAL
        col.addView(activeBox, lp(MATCH, WRAP))
        emptyTv = tv("لا توجد روابط ملتقطة بعد", 14f, cMuted)
        emptyTv.gravity = Gravity.CENTER
        emptyTv.setPadding(0, dp(40), 0, dp(40))
        col.addView(emptyTv, lp(MATCH, WRAP))
        list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        col.addView(list, lp(MATCH, WRAP))
        val sv = ScrollView(this)
        sv.setBackgroundColor(cBg)
        sv.addView(col)
        return sv
    }

    private fun toolCard(res: Int, label: String, onClick: () -> Unit): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.gravity = Gravity.CENTER
        c.background = bg(cCard, 14, cLine)
        c.setPadding(dp(8), dp(16), dp(8), dp(16))
        val i = ImageView(this)
        i.setImageResource(res)
        i.setColorFilter(cAccent)
        c.addView(i, lp(dp(28), dp(28)))
        val t = tv(label, 13f, cText)
        t.gravity = Gravity.CENTER
        c.addView(t, lp(WRAP, WRAP, 0f, 0, 8, 0, 0))
        c.setOnClickListener { onClick() }
        return c
    }

    private fun grid(parent: LinearLayout, cards: List<View>) {
        var i = 0
        while (i < cards.size) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            for (k in 0..1) {
                val v = cards.getOrNull(i + k)
                if (v != null) row.addView(v, lp(0, WRAP, 1f, 4, 4, 4, 4))
                else row.addView(View(this), lp(0, 1, 1f, 4, 4, 4, 4))
            }
            parent.addView(row, lp(MATCH, WRAP))
            i += 2
        }
    }

    private fun buildTools(): View {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(14), dp(14), dp(14), dp(24))
        col.addView(tv("الأدوات", 20f, cText, true), lp(MATCH, WRAP, 0f, 0, 0, 0, 12))

        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.background = bg(cCard, 14, cLine)
        card.setPadding(dp(12), dp(12), dp(12), dp(12))
        card.addView(tv("إضافة رابط يدوياً (m3u8 / mp4)", 14f, cText, true))
        manualBox = EditText(this)
        manualBox.setTextColor(cText)
        manualBox.setHintTextColor(cMuted)
        manualBox.hint = "https://..."
        manualBox.textSize = 13f
        manualBox.background = bg(cBg, 10, cLine)
        manualBox.setPadding(dp(12), dp(8), dp(12), dp(8))
        manualBox.layoutDirection = View.LAYOUT_DIRECTION_LTR
        manualBox.gravity = Gravity.CENTER_VERTICAL or Gravity.START
        manualBox.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        card.addView(manualBox, lp(MATCH, WRAP, 0f, 0, 8, 0, 0))
        card.addView(pill("إضافة للتحميلات", true) { addManual() }, lp(MATCH, WRAP, 0f, 0, 8, 0, 0))
        col.addView(card, lp(MATCH, WRAP, 0f, 0, 0, 0, 10))

        grid(col, listOf(
            toolCard(R.drawable.ic_add, "تبويب جديد") { newTab(prefs().getString("home", HOME) ?: HOME) },
            toolCard(R.drawable.ic_star, "حفظ الصفحة في المفضلة") { addBookmark() },
            toolCard(R.drawable.ic_bookmark, "المفضلة") { showBookmarks() },
            toolCard(R.drawable.ic_copy, "نسخ رابط الصفحة") {
                val u = cur?.web?.url
                if (u != null) copyText(u)
            },
            toolCard(R.drawable.ic_share, "مشاركة الصفحة") {
                val u = cur?.web?.url
                if (u != null) {
                    val i = Intent(Intent.ACTION_SEND)
                    i.type = "text/plain"
                    i.putExtra(Intent.EXTRA_TEXT, u)
                    startActivity(Intent.createChooser(i, "مشاركة"))
                }
            },
            toolCard(R.drawable.ic_folder, "فتح مجلد التحميلات") {
                try {
                    val i = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(i)
                } catch (e: Exception) {
                    toast("تعذر فتح مجلد التحميلات")
                }
            },
            toolCard(R.drawable.ic_delete, "مسح الكاش والسجل") {
                for (t in tabs) {
                    t.web.clearCache(true)
                    t.web.clearHistory()
                }
                toast("تم مسح الكاش والسجل")
            },
            toolCard(R.drawable.ic_delete, "مسح الكوكيز") {
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
                toast("تم مسح الكوكيز، سيُطلب تسجيل الدخول من جديد")
            },
            toolCard(R.drawable.ic_delete, "مسح قائمة التحميلات") { clearFound() },
            toolCard(R.drawable.ic_tools, "سكربتات JavaScript") { showScripts() },
            toolCard(R.drawable.ic_refresh, "الصفحات المغلقة مؤخراً") { showClosed() },
            toolCard(R.drawable.ic_close, "إغلاق التبويبات الأخرى") { closeOthers() }
        ))
        val sv = ScrollView(this)
        sv.setBackgroundColor(cBg)
        sv.addView(col)
        return sv
    }

    private fun settingsCard(parent: LinearLayout): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.background = bg(cCard, 14, cLine)
        c.setPadding(dp(14), dp(10), dp(14), dp(10))
        parent.addView(c, lp(MATCH, WRAP, 0f, 0, 0, 0, 10))
        return c
    }

    private fun switchRow(parent: LinearLayout, title: String, sub: String, key: String, def: Boolean, onChange: (Boolean) -> Unit) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, dp(8), 0, dp(8))
        val txt = LinearLayout(this)
        txt.orientation = LinearLayout.VERTICAL
        txt.addView(tv(title, 15f, cText, true))
        txt.addView(tv(sub, 12f, cMuted))
        val sw = Switch(this)
        sw.isChecked = prefs().getBoolean(key, def)
        sw.setOnCheckedChangeListener { _, on ->
            prefs().edit().putBoolean(key, on).apply()
            onChange(on)
        }
        row.addView(txt, lp(0, WRAP, 1f))
        row.addView(sw)
        parent.addView(row, lp(MATCH, WRAP))
    }

    private fun applyWebSettings(w: WebView) {
        val s = w.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.blockNetworkImage = saver()
        s.cacheMode = baseCache()
        s.mediaPlaybackRequiresUserGesture = !(prefs().getBoolean("autoplay", true) && !saver())
        val desk = prefs().getBoolean("desktop", false)
        s.userAgentString = if (desk) DESKTOP_UA else ""
        s.useWideViewPort = true
        s.loadWithOverviewMode = desk
        s.builtInZoomControls = true
        s.displayZoomControls = false
        curUa = s.userAgentString ?: ""
    }

    private fun buildSettings(): View {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(14), dp(14), dp(14), dp(24))
        col.addView(tv("الإعدادات", 20f, cText, true), lp(MATCH, WRAP, 0f, 0, 0, 0, 12))

        // المتصفح
        col.addView(tv("المتصفح", 13f, cMuted, true), lp(MATCH, WRAP, 0f, 4, 0, 0, 6))
        val c1 = settingsCard(col)
        switchRow(c1, "حظر الإعلانات", "يمنع الطلبات المعروفة لشبكات الإعلانات", "adblock", true) { adBlock = it }
        switchRow(c1, "تشغيل الفيديو تلقائياً", "تشغيل الفيديو بدون لمس الشاشة لالتقاط رابطه", "autoplay", true) {
            for (t in tabs) applyWebSettings(t.web)
        }
        switchRow(c1, "توفير البيانات", "يمنع تحميل الصور، يفضّل الكاش، يوقف التشغيل التلقائي، ويختار أقل جودة عند التحميل (أعد تحميل الصفحة)", "saver", false) {
            for (t in tabs) applyWebSettings(t.web)
            toast("أعد تحميل الصفحة ليسري التغيير")
        }
        switchRow(c1, "أزرار التحميل على الفيديو والصور", "أزرار تحميل ونسخ وشريط تقديم فوق كل فيديو وصورة (تُطبّق عند فتح الصفحة)", "overlay", true) { }
        switchRow(c1, "وضع سطح المكتب", "يفتح المواقع بنسختها الكاملة", "desktop", false) {
            for (t in tabs) applyWebSettings(t.web)
            cur?.web?.reload()
        }
        val homeBox = EditText(this)
        homeBox.setText(prefs().getString("home", HOME))
        homeBox.setTextColor(cText)
        homeBox.textSize = 13f
        homeBox.background = bg(cBg, 10, cLine)
        homeBox.setPadding(dp(12), dp(8), dp(12), dp(8))
        homeBox.layoutDirection = View.LAYOUT_DIRECTION_LTR
        homeBox.gravity = Gravity.CENTER_VERTICAL or Gravity.START
        homeBox.setSingleLine()
        c1.addView(tv("الصفحة الرئيسية", 15f, cText, true), lp(MATCH, WRAP, 0f, 0, 8, 0, 4))
        c1.addView(homeBox, lp(MATCH, WRAP))
        c1.addView(pill("حفظ الصفحة الرئيسية", false) {
            val v = homeBox.text.toString().trim()
            prefs().edit().putString("home", if (v.isEmpty()) HOME else fix(v)).apply()
            toast("تم الحفظ")
        }, lp(MATCH, WRAP, 0f, 0, 8, 0, 4))

        // التحميل
        col.addView(tv("التحميل", 13f, cMuted, true), lp(MATCH, WRAP, 0f, 4, 6, 0, 6))
        val c2 = settingsCard(col)
        val cnt = tv("" + prefs().getInt("threads", 3), 16f, cText, true)
        cnt.gravity = Gravity.CENTER
        cnt.minWidth = dp(34)
        fun setThr(n: Int) {
            val v = n.coerceIn(1, 6)
            prefs().edit().putInt("threads", v).apply()
            cnt.text = "" + v
        }
        val thrRow = LinearLayout(this)
        thrRow.orientation = LinearLayout.HORIZONTAL
        thrRow.gravity = Gravity.CENTER_VERTICAL
        thrRow.setPadding(0, dp(8), 0, dp(8))
        val thrTxt = LinearLayout(this)
        thrTxt.orientation = LinearLayout.VERTICAL
        thrTxt.addView(tv("عدد الأجزاء المتوازية", 15f, cText, true))
        thrTxt.addView(tv("أجزاء HLS التي تُحمّل في نفس الوقت (1 - 6)", 12f, cMuted))
        thrRow.addView(thrTxt, lp(0, WRAP, 1f))
        thrRow.addView(pill("+", false) { setThr(prefs().getInt("threads", 3) + 1) })
        thrRow.addView(cnt)
        thrRow.addView(pill("−", false) { setThr(prefs().getInt("threads", 3) - 1) })
        c2.addView(thrRow, lp(MATCH, WRAP))

        val qRow = LinearLayout(this)
        qRow.orientation = LinearLayout.HORIZONTAL
        qRow.gravity = Gravity.CENTER_VERTICAL
        qRow.setPadding(0, dp(8), 0, dp(8))
        val qTxt = LinearLayout(this)
        qTxt.orientation = LinearLayout.VERTICAL
        qTxt.addView(tv("جودة التحميل", 15f, cText, true))
        qTxt.addView(tv("الجودة المختارة من القائمة الرئيسية", 12f, cMuted))
        val hi = pill("الأعلى", true) { }
        val lo = pill("الأدنى", false) { }
        fun paintQ() {
            val q = prefs().getString("quality", "high")
            styleChip(hi, q == "high")
            styleChip(lo, q == "low")
        }
        hi.setOnClickListener { prefs().edit().putString("quality", "high").apply(); paintQ() }
        lo.setOnClickListener { prefs().edit().putString("quality", "low").apply(); paintQ() }
        paintQ()
        qRow.addView(qTxt, lp(0, WRAP, 1f))
        qRow.addView(hi, lp(WRAP, WRAP, 0f, 0, 0, 6, 0))
        qRow.addView(lo)
        c2.addView(qRow, lp(MATCH, WRAP))

        // المظهر
        col.addView(tv("المظهر", 13f, cMuted, true), lp(MATCH, WRAP, 0f, 4, 6, 0, 6))
        val c3 = settingsCard(col)
        switchRow(c3, "الوضع الداكن", "تغيير المظهر يعيد تشغيل الواجهة مع الحفاظ على التبويبات", "dark", true) { recreate() }

        // حول
        col.addView(tv("حول التطبيق", 13f, cMuted, true), lp(MATCH, WRAP, 0f, 4, 6, 0, 6))
        val c4 = settingsCard(col)
        c4.gravity = Gravity.CENTER_HORIZONTAL
        val logo = ImageView(this)
        logo.setImageResource(R.drawable.logo)
        c4.addView(logo, lp(dp(72), dp(72), 0f, 0, 8, 0, 8))
        val ver = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "" } catch (e: Exception) { "" }
        val name = tv("Tango DL", 18f, cText, true)
        name.gravity = Gravity.CENTER
        c4.addView(name)
        val vt = tv("الإصدار $ver", 12f, cMuted)
        vt.gravity = Gravity.CENTER
        c4.addView(vt)
        val desc = tv("متصفح يلتقط روابط الفيديو (m3u8 / mp4) ويحمّلها ويدمج أجزاءها في ملف واحد داخل مجلد التحميلات.", 12f, cMuted)
        desc.gravity = Gravity.CENTER
        c4.addView(desc, lp(MATCH, WRAP, 0f, 0, 6, 0, 6))

        val sv = ScrollView(this)
        sv.setBackgroundColor(cBg)
        sv.addView(col)
        return sv
    }

    // ---------- المتصفح والتبويبات ----------

    private fun hasTab(v: WebView): Tab? = tabs.firstOrNull { it.web === v }

    private fun updateLoadUi() {
        val t = cur ?: return
        if (t.loading && t.progress < 100) {
            loadBar.visibility = View.VISIBLE
            loadBar.progress = maxOf(t.progress, 5)
            pct.text = "" + t.progress + "%"
            pct.visibility = View.VISIBLE
            reloadBtn.setImageResource(R.drawable.ic_close)
        } else {
            loadBar.progress = 100
            pct.visibility = View.GONE
            reloadBtn.setImageResource(R.drawable.ic_refresh)
            ui.postDelayed({
                val c = cur
                if (c == null || !c.loading || c.progress >= 100) loadBar.visibility = View.INVISIBLE
            }, 600)
        }
    }

    private fun makeWeb(): WebView {
        val w = WebView(this)
        applyWebSettings(w)
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
        w.addJavascriptInterface(Bridge(), "TDL")
        w.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(v: WebView, p: Int) {
                val t = hasTab(v) ?: return
                t.progress = p
                if (p >= 20 && !t.injected) {
                    t.injected = true
                    if (prefs().getBoolean("overlay", true)) v.evaluateJavascript(OVERLAY_JS, null)
                }
                if (cur === t) updateLoadUi()
            }

            override fun onReceivedTitle(v: WebView, title: String?) {
                val t = hasTab(v) ?: return
                t.title = title ?: ""
                relabel()
            }
        }
        w.webViewClient = object : WebViewClient() {
            // يعمل على خيط الشبكة: لا نلمس WebView هنا، كل شيء من نفس الطلب
            override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse? {
                val u = r.url.toString()
                val low = u.lowercase()
                val h = HashMap<String, String>()
                h["User-Agent"] = hdr(r.requestHeaders, "User-Agent") ?: curUa
                h["Referer"] = hdr(r.requestHeaders, "Referer") ?: pages[v] ?: ""
                h["Cookie"] = CookieManager.getInstance().getCookie(u) ?: hdr(r.requestHeaders, "Cookie") ?: ""
                val origin = hdr(r.requestHeaders, "Origin")
                if (origin != null) h["Origin"] = origin
                val accept = (hdr(r.requestHeaders, "Accept") ?: "").lowercase()
                if (low.contains(".m3u8") || low.contains(".mp4") || low.contains("mpegurl") || accept.contains("mpegurl")) {
                    ui.post { addFound(u, h) }
                    return null
                }
                if (adBlock) {
                    val host = r.url.host?.lowercase() ?: ""
                    for (a in adHosts) {
                        if (host.contains(a)) {
                            return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                        }
                    }
                }
                // نوع المحتوى mpegurl: فحص رأس الرد للطلبات التي لا تبدو ملفات ثابتة
                if (r.method == "GET" && !r.isForMainFrame && (r.url.scheme == "http" || r.url.scheme == "https") &&
                    staticExt.none { low.substringBefore('?').endsWith(it) }) {
                    probe(u, h)
                }
                return null
            }

            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                val s = r.url.scheme ?: ""
                return !(s == "http" || s == "https")
            }

            override fun onPageStarted(v: WebView, url: String, f: android.graphics.Bitmap?) {
                pages[v] = url
                val t = hasTab(v) ?: return
                t.loading = true
                t.injected = false
                t.progress = 5
                if (cur === t) {
                    urlBox.setText(url)
                    updateLoadUi()
                }
            }

            override fun onPageFinished(v: WebView, url: String) {
                val t = hasTab(v) ?: return
                t.loading = false
                t.progress = 100
                t.title = v.title ?: t.title
                relabel()
                if (cur === t) updateLoadUi()
                CookieManager.getInstance().flush()
                if (t.restoring) {
                    t.restoring = false
                    v.settings.cacheMode = baseCache()
                }
                if (prefs().getBoolean("overlay", true)) v.evaluateJavascript(OVERLAY_JS, null)
                runScripts(v, url)
            }

            override fun onReceivedError(v: WebView, r: WebResourceRequest, e: WebResourceError) {
                if (r.isForMainFrame) {
                    val t = hasTab(v)
                    if (t != null) {
                        t.loading = false
                        if (cur === t) updateLoadUi()
                    }
                    toast("تعذر تحميل الصفحة: " + e.description)
                    logErr("صفحة", "تعذر تحميل " + r.url + ": " + e.description)
                }
            }

            override fun onReceivedHttpError(v: WebView, r: WebResourceRequest, resp: WebResourceResponse) {
                if (r.isForMainFrame && resp.statusCode >= 400) logErr("صفحة", "خطأ HTTP " + resp.statusCode + " في " + r.url)
            }

            override fun onRenderProcessGone(v: WebView, d: RenderProcessGoneDetail): Boolean {
                val t = hasTab(v)
                val u = pages[v] ?: HOME
                logErr("WebView", "تعطلت عملية عرض الصفحة " + (if (d.didCrash()) "(انهيار)" else "(نفاد الذاكرة)") + ": " + u)
                if (t != null) {
                    ui.post {
                        if (tabs.size <= 1) {
                            removeTab(t)
                            newTab(u)
                        } else {
                            closeTab(t)
                        }
                    }
                }
                return true
            }
        }
        return w
    }

    private fun buildChip(t: Tab) {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.HORIZONTAL
        c.gravity = Gravity.CENTER_VERTICAL
        c.setPadding(dp(12), dp(4), dp(4), dp(4))
        val l = tv("", 12f, cText)
        l.maxLines = 1
        l.ellipsize = TextUtils.TruncateAt.END
        l.maxWidth = dp(110)
        val x = ImageView(this)
        x.setImageResource(R.drawable.ic_close)
        x.setColorFilter(cMuted)
        x.setPadding(dp(5), dp(5), dp(5), dp(5))
        x.setOnClickListener { closeTab(t) }
        c.addView(l, lp(WRAP, WRAP))
        c.addView(x, lp(dp(24), dp(24)))
        c.setOnClickListener {
            switchTo(t)
            showPage(0)
        }
        c.layoutParams = lp(WRAP, WRAP, 0f, 3, 3, 3, 3)
        t.chip = c
        t.label = l
        t.close = x
    }

    private fun newTab(url: String) {
        val t = Tab(makeWeb())
        buildChip(t)
        tabs.add(t)
        container.addView(t.web, FrameLayout.LayoutParams(MATCH, MATCH))
        tabsRow.addView(t.chip, tabsRow.childCount - 1)
        relabel()
        switchTo(t)
        showPage(0)
        if (url.isNotBlank()) t.web.loadUrl(fix(url))
    }

    private fun removeTab(t: Tab) {
        snapshot(t)
        tabs.remove(t)
        container.removeView(t.web)
        tabsRow.removeView(t.chip)
        t.web.destroy()
    }

    private fun closeTab(t: Tab) {
        if (tabs.size <= 1) {
            toast("هذا آخر تبويب")
            return
        }
        val i = tabs.indexOf(t)
        val wasCur = cur === t
        removeTab(t)
        relabel()
        if (wasCur) switchTo(tabs[if (i > 0) i - 1 else 0])
    }

    private fun showTabs() {
        val items = Array(tabs.size) { i ->
            val t = tabs[i]
            (if (t === cur) "● " else "○ ") + (if (t.title.isNotBlank()) t.title else (t.web.url ?: ("تبويب " + (i + 1)))).take(50)
        }
        val dlg = AlertDialog.Builder(this)
            .setTitle("التبويبات (" + tabs.size + ")")
            .setItems(items) { _, i ->
                switchTo(tabs[i])
                showPage(0)
            }
            .setPositiveButton("تبويب جديد") { _, _ -> newTab(prefs().getString("home", HOME) ?: HOME) }
            .setNeutralButton("إغلاق الحالي") { _, _ -> cur?.let { closeTab(it) } }
            .setNegativeButton("رجوع", null)
            .show()
        dlg.listView.setOnItemLongClickListener { _, _, i, _ ->
            if (i in tabs.indices) closeTab(tabs[i])
            dlg.dismiss()
            true
        }
    }

    private fun closeOthers() {
        val keep = cur ?: return
        for (t in ArrayList(tabs)) if (t !== keep) removeTab(t)
        relabel()
        switchTo(keep)
        toast("تم إغلاق باقي التبويبات")
    }

    private fun relabel() {
        tabBtn.text = "" + tabs.size
        for (i in tabs.indices) {
            val t = tabs[i]
            t.label.text = if (t.title.isNotBlank()) t.title else "تبويب " + (i + 1)
            t.close.visibility = if (tabs.size > 1) View.VISIBLE else View.GONE
        }
    }

    private fun switchTo(t: Tab) {
        cur = t
        for (x in tabs) {
            x.web.visibility = if (x === t) View.VISIBLE else View.GONE
            x.chip.background = bg(if (x === t) cAccentSoft else cCard, 16, if (x === t) cAccent else cLine)
        }
        urlBox.setText(t.web.url ?: "")
        updateLoadUi()
    }

    private fun fix(u: String): String {
        val s = u.trim()
        if (s.startsWith("http://") || s.startsWith("https://")) return s
        if (s.contains(" ") || !s.contains(".")) return "https://www.google.com/search?q=" + URLEncoder.encode(s, "UTF-8")
        return "https://$s"
    }

    private fun load(u: String) {
        hideKb()
        cur?.web?.loadUrl(fix(u))
        showPage(0)
    }

    // ---------- المفضلة ----------

    private fun addBookmark() {
        val u = cur?.web?.url ?: return
        val set = HashSet<String>(prefs().getStringSet("bm", HashSet<String>()) ?: HashSet<String>())
        set.add(u)
        prefs().edit().putStringSet("bm", set).apply()
        toast("تم الحفظ في المفضلة")
    }

    private fun showBookmarks() {
        val items = (prefs().getStringSet("bm", HashSet<String>()) ?: HashSet<String>()).toList().sorted()
        if (items.isEmpty()) {
            toast("المفضلة فاضية")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("المفضلة")
            .setItems(items.toTypedArray()) { _, i -> load(items[i]) }
            .setNegativeButton("مسح الكل") { _, _ -> prefs().edit().remove("bm").apply() }
            .show()
    }

    // ---------- تنبيهات التطبيق ----------

    private fun buildAlerts(): View {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(14), dp(14), dp(14), dp(24))
        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.addView(tv("تنبيهات التطبيق", 20f, cText, true), lp(0, WRAP, 1f))
        head.addView(pill("نسخ الكل", false) {
            copyText(alerts.joinToString("\n\n") { it.kind + " | " + it.msg + "\n" + it.detail })
        }, lp(WRAP, WRAP, 0f, 0, 0, 6, 0))
        head.addView(pill("مسح", false) { clearAlerts() })
        col.addView(head, lp(MATCH, WRAP, 0f, 0, 0, 0, 6))
        col.addView(tv("أي خطأ في التحميل أو الدمج أو الصفحات أو التطبيق يظهر هنا. اضغط على التنبيه لعرض التفاصيل.", 12f, cMuted), lp(MATCH, WRAP, 0f, 0, 0, 0, 12))
        alertEmpty = tv("لا توجد أخطاء", 14f, cMuted)
        alertEmpty.gravity = Gravity.CENTER
        alertEmpty.setPadding(0, dp(40), 0, dp(40))
        col.addView(alertEmpty, lp(MATCH, WRAP))
        alertList = LinearLayout(this)
        alertList.orientation = LinearLayout.VERTICAL
        col.addView(alertList, lp(MATCH, WRAP))
        val sv = ScrollView(this)
        sv.setBackgroundColor(cBg)
        sv.addView(col)
        return sv
    }

    // يمكن استدعاؤها من أي خيط
    private fun logErr(kind: String, msg: String, t: Throwable? = null) {
        val detail = if (t != null) android.util.Log.getStackTraceString(t).lines().take(14).joinToString("\n") else ""
        val a = AlertItem(System.currentTimeMillis(), kind, msg, detail)
        ui.post { addAlert(a, true) }
    }

    private fun addAlert(a: AlertItem, persist: Boolean) {
        alerts.add(0, a)
        if (alerts.size > 200) {
            alerts.removeAt(alerts.size - 1)
            if (alertList.childCount > 0) alertList.removeViewAt(alertList.childCount - 1)
        }
        val row = LinearLayout(this)
        row.orientation = LinearLayout.VERTICAL
        row.background = bg(cCard, 14, cLine)
        row.setPadding(dp(12), dp(12), dp(12), dp(12))
        val time = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date(a.time))
        row.addView(tv(a.kind + "  •  " + time, 12f, cMuted))
        row.addView(tv(a.msg, 14f, cText, true))
        if (a.detail.isNotBlank()) {
            val d = tv(a.detail, 11f, cMuted)
            d.layoutDirection = View.LAYOUT_DIRECTION_LTR
            d.visibility = View.GONE
            row.addView(d, lp(MATCH, WRAP, 0f, 0, 6, 0, 0))
            row.setOnClickListener { d.visibility = if (d.visibility == View.GONE) View.VISIBLE else View.GONE }
        }
        row.addView(pill("نسخ", false) { copyText(a.kind + " | " + a.msg + "\n" + a.detail) }, lp(WRAP, WRAP, 0f, 0, 8, 0, 0))
        alertList.addView(row, 0, lp(MATCH, WRAP, 0f, 0, 0, 0, 10))
        if (persist) persistAlert(a)
        updateCounts()
    }

    private fun persistAlert(a: AlertItem) {
        try {
            val f = File(filesDir, "alerts.tsv")
            if (f.length() > 200000) f.delete()
            val line = listOf(a.time.toString(), a.kind, a.msg, a.detail).joinToString("\t") { it.replace("\t", " ").replace("\n", "␤") }
            f.appendText(line + "\n")
        } catch (e: Exception) {
        }
    }

    private fun loadAlerts() {
        try {
            val f = File(filesDir, "alerts.tsv")
            if (!f.exists()) return
            for (l in f.readLines().takeLast(100)) {
                val p = l.split("\t")
                if (p.size < 4) continue
                addAlert(AlertItem(p[0].toLongOrNull() ?: 0L, p[1], p[2], p[3].replace("␤", "\n")), false)
            }
        } catch (e: Exception) {
        }
    }

    private fun clearAlerts() {
        alerts.clear()
        alertList.removeAllViews()
        try { File(filesDir, "alerts.tsv").delete() } catch (e: Exception) { }
        updateCounts()
    }

    // ---------- الصفحات المغلقة (استرجاع من الكاش بدون تحميل جديد) ----------

    // يحذف مجلدات التحميل غير المكتملة الأقدم من 3 أيام
    private fun cleanOldWork() {
        try {
            val base = getExternalFilesDir(null) ?: filesDir
            val lim = System.currentTimeMillis() - 3L * 24 * 3600 * 1000
            base.listFiles()?.forEach {
                if (it.isDirectory && it.name.startsWith("work_") && it.lastModified() < lim) it.deleteRecursively()
            }
        } catch (e: Exception) {
        }
    }

    private fun stateFile(id: String) = File(filesDir, "closed_$id.bin")

    private fun loadClosed() {
        val s = prefs().getString("closed", "") ?: ""
        for (l in s.split("\n")) {
            val p = l.split("\t")
            if (p.size >= 3 && stateFile(p[0]).exists()) closed.add(Closed(p[0], p[1], p[2]))
        }
    }

    private fun saveClosedPrefs() {
        prefs().edit().putString("closed", closed.joinToString("\n") { it.id + "\t" + it.title + "\t" + it.url }).apply()
    }

    private fun snapshot(t: Tab) {
        try {
            val url = t.web.url ?: return
            if (!url.startsWith("http")) return
            val b = Bundle()
            if (t.web.saveState(b) == null) return
            val id = System.currentTimeMillis().toString()
            val p = android.os.Parcel.obtain()
            try {
                p.writeBundle(b)
                stateFile(id).writeBytes(p.marshall())
            } finally {
                p.recycle()
            }
            for (x in ArrayList(closed)) if (x.url == url) {
                stateFile(x.id).delete()
                closed.remove(x)
            }
            val title = (if (t.title.isNotBlank()) t.title else url).replace("\t", " ").replace("\n", " ")
            closed.add(0, Closed(id, title, url))
            while (closed.size > 15) {
                val x = closed.removeAt(closed.size - 1)
                stateFile(x.id).delete()
            }
            saveClosedPrefs()
        } catch (e: Throwable) {
            logErr("كاش", "تعذر حفظ حالة الصفحة المغلقة", e)
        }
    }

    private fun reopen(c: Closed) {
        try {
            val d = stateFile(c.id).readBytes()
            var b: Bundle? = null
            val p = android.os.Parcel.obtain()
            try {
                p.unmarshall(d, 0, d.size)
                p.setDataPosition(0)
                b = p.readBundle(javaClass.classLoader)
            } finally {
                p.recycle()
            }
            newTab("")
            val t = cur ?: return
            t.restoring = true
            t.web.settings.cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK
            if (b == null || t.web.restoreState(b) == null) t.web.loadUrl(c.url)
        } catch (e: Throwable) {
            logErr("كاش", "تعذر استرجاع الصفحة، فُتحت من جديد", e)
            newTab(c.url)
        }
    }

    private fun showClosed() {
        if (closed.isEmpty()) {
            toast("لا توجد صفحات مغلقة محفوظة")
            return
        }
        val items = closed.map { (if (it.title.isNotBlank()) it.title else it.url).take(60) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("الصفحات المغلقة مؤخراً")
            .setItems(items) { _, i -> reopen(closed[i]) }
            .setNegativeButton("مسح الكل") { _, _ ->
                for (x in closed) stateFile(x.id).delete()
                closed.clear()
                saveClosedPrefs()
            }
            .show()
    }

    // ---------- سكربتات JavaScript ----------

    private fun loadScripts(): org.json.JSONArray =
        try { org.json.JSONArray(prefs().getString("scripts", "[]")) } catch (e: Exception) { org.json.JSONArray() }

    private fun saveScripts(a: org.json.JSONArray) {
        prefs().edit().putString("scripts", a.toString()).apply()
    }

    private fun showScripts() {
        val a = loadScripts()
        if (a.length() == 0) {
            toast("لا توجد سكربتات بعد، أضف أول سكربت")
            editScript(-1)
            return
        }
        val names = Array(a.length()) { i ->
            val o = a.getJSONObject(i)
            (if (o.optBoolean("on", true)) "✔ " else "✖ ") + o.optString("name", "بدون اسم")
        }
        AlertDialog.Builder(this)
            .setTitle("سكربتات JavaScript")
            .setItems(names) { _, i -> editScript(i) }
            .setPositiveButton("إضافة") { _, _ -> editScript(-1) }
            .setNegativeButton("إغلاق", null)
            .show()
    }

    private fun editScript(idx: Int) {
        val a = loadScripts()
        val o = if (idx >= 0) a.getJSONObject(idx) else org.json.JSONObject()
        val lay = LinearLayout(this)
        lay.orientation = LinearLayout.VERTICAL
        lay.setPadding(dp(16), dp(8), dp(16), dp(8))
        val nameE = EditText(this)
        nameE.hint = "اسم السكربت"
        nameE.setText(o.optString("name", ""))
        val matchE = EditText(this)
        matchE.hint = "المواقع (اختياري) مثل https://*.tango.me/*"
        matchE.setText(o.optString("match", ""))
        matchE.layoutDirection = View.LAYOUT_DIRECTION_LTR
        matchE.textSize = 13f
        val codeE = EditText(this)
        codeE.hint = "كود JavaScript"
        codeE.setText(o.optString("code", ""))
        codeE.layoutDirection = View.LAYOUT_DIRECTION_LTR
        codeE.typeface = Typeface.MONOSPACE
        codeE.textSize = 12f
        codeE.minLines = 8
        codeE.gravity = Gravity.TOP or Gravity.START
        codeE.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        lay.addView(nameE)
        lay.addView(matchE)
        lay.addView(codeE)
        val sv = ScrollView(this)
        sv.addView(lay)
        val on = o.optBoolean("on", true)
        val b = AlertDialog.Builder(this)
            .setTitle(if (idx >= 0) "تعديل السكربت" else "سكربت جديد")
            .setView(sv)
            .setPositiveButton("حفظ") { _, _ ->
                val code = codeE.text.toString()
                if (code.isBlank()) {
                    toast("الكود فاضي")
                } else {
                    val n = org.json.JSONObject()
                    n.put("name", nameE.text.toString().ifBlank { "سكربت" })
                    n.put("match", matchE.text.toString().trim())
                    n.put("code", code)
                    n.put("on", on)
                    if (idx >= 0) a.put(idx, n) else a.put(n)
                    saveScripts(a)
                    toast("تم حفظ السكربت، يعمل عند فتح الصفحة")
                }
            }
        if (idx >= 0) {
            b.setNeutralButton(if (on) "تعطيل" else "تفعيل") { _, _ ->
                o.put("on", !on)
                a.put(idx, o)
                saveScripts(a)
            }
            b.setNegativeButton("حذف") { _, _ ->
                val n = org.json.JSONArray()
                for (i in 0 until a.length()) if (i != idx) n.put(a.get(i))
                saveScripts(n)
            }
        } else {
            b.setNeutralButton("لصق من الحافظة", null)
            b.setNegativeButton("إلغاء", null)
        }
        val dlg = b.show()
        if (idx < 0) {
            dlg.getButton(android.content.DialogInterface.BUTTON_NEUTRAL).setOnClickListener {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val t = cm.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                if (t.isBlank()) toast("الحافظة فاضية") else codeE.setText(t)
            }
        }
    }

    private fun scriptMatches(match: String, code: String, url: String): Boolean {
        val pats = ArrayList<String>()
        if (match.isNotBlank()) {
            for (x in match.split(Regex("[\\s,]+"))) if (x.isNotBlank()) pats.add(x)
        } else {
            val re = Regex("^\\s*//\\s*@(?:match|include)\\s+(\\S+)", RegexOption.MULTILINE)
            for (m in re.findAll(code)) pats.add(m.groupValues[1])
        }
        if (pats.isEmpty()) return true
        for (pt in pats) {
            if (pt == "*" || pt == "<all_urls>") return true
            val rx = pt.split("*").joinToString(".*") { Regex.escape(it) }
            try {
                if (Regex(rx).matches(url)) return true
            } catch (e: Exception) {
            }
        }
        return false
    }

    private fun runScripts(v: WebView, url: String) {
        try {
            val a = loadScripts()
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                if (!o.optBoolean("on", true)) continue
                val code = o.optString("code", "")
                if (code.isBlank() || !scriptMatches(o.optString("match", ""), code, url)) continue
                val nm = o.optString("name", "")
                // new Function يجعل أخطاء الصياغة قابلة للالتقاط وتظهر في تبويب التنبيهات
                val js = "(function(){var n=" + org.json.JSONObject.quote(nm) + ";try{(new Function(" +
                    org.json.JSONObject.quote(code) + "))();}catch(e){try{TDL.err('سكربت '+n+': '+e);}catch(x){}}})();"
                v.evaluateJavascript(js, null)
            }
        } catch (e: Throwable) {
            logErr("سكربت", "تعذر تشغيل السكربتات: " + (e.message ?: e.javaClass.simpleName), e)
        }
    }

    // ---------- أزرار الفيديو والصور داخل الصفحة ----------

    inner class Bridge {
        @JavascriptInterface
        fun video(src: String, page: String) {
            ui.post { handleVideo(src, page) }
        }

        @JavascriptInterface
        fun copyVideo(src: String) {
            ui.post {
                val u = if (src.startsWith("http")) src else found.lastOrNull()
                if (u == null) toast("شغّل الفيديو لحظة ثم اضغط مرة أخرى") else copyText(u)
            }
        }

        @JavascriptInterface
        fun image(src: String, page: String, act: String) {
            ui.post { handleImage(src, page, act) }
        }

        @JavascriptInterface
        fun err(msg: String) {
            logErr("فيديو", msg.take(250))
        }
    }

    private fun hdrFor(u: String, page: String): Map<String, String> {
        val h = HashMap<String, String>()
        h["User-Agent"] = curUa
        h["Referer"] = page
        h["Cookie"] = CookieManager.getInstance().getCookie(u) ?: ""
        return h
    }

    private fun handleVideo(src: String, page: String) {
        // فيديو blob: لا يُحمَّل مباشرة، فنستعمل آخر رابط التقطه التطبيق
        val u = if (src.startsWith("http")) src else found.lastOrNull()
        if (u == null) {
            toast("شغّل الفيديو لحظة ثم اضغط مرة أخرى")
            return
        }
        addFound(u, foundHdr[u] ?: hdrFor(u, page))
        foundStart[u]?.invoke()
        toast("تابع التحميل من تبويب التحميلات")
    }

    private fun imgExt(ct: String, src: String): String {
        if (ct.contains("png")) return "png"
        if (ct.contains("webp")) return "webp"
        if (ct.contains("gif")) return "gif"
        if (ct.contains("jpeg") || ct.contains("jpg")) return "jpg"
        val e = src.substringBefore('?').substringAfterLast('.', "").lowercase()
        return if (Regex("[a-z0-9]{2,4}").matches(e)) e else "jpg"
    }

    private fun handleImage(src: String, page: String, act: String) {
        if (act == "copy") {
            if (src.isBlank()) toast("لا يوجد رابط للصورة") else copyText(src)
            return
        }
        if (!src.startsWith("http")) {
            toast("هذه الصورة لا يمكن تحميلها مباشرة، جرّب نسخ الرابط")
            return
        }
        val h = hdrFor(src, page)
        toast("جاري تحميل الصورة...")
        imgPool.execute {
            try {
                val c = conn(src, h)
                val ext = imgExt((c.contentType ?: "").lowercase(), src)
                val f = File(cacheDir, "tango_img_" + System.currentTimeMillis() + "." + ext)
                c.inputStream.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                val mime = if (ext == "jpg") "image/jpeg" else "image/" + ext
                val r = saveToDownloads(f, mime)
                ui.post { toast("تم حفظ الصورة: " + r) }
            } catch (e: Throwable) {
                logErr("صورة", "فشل تحميل الصورة: " + (e.message ?: e.javaClass.simpleName), e)
                ui.post { toast("فشل تحميل الصورة، راجع التنبيهات") }
            }
        }
    }

    // ---------- بدء التحميل مع اختيار الجودة ----------

    private fun startDl(u: String, h: Map<String, String>, r: Row) {
        r.btn.isEnabled = false
        r.btn.alpha = 0.5f
        r.bar.visibility = View.VISIBLE
        r.msg.text = "في الانتظار..."
        val low = u.lowercase()
        if (!(low.contains(".m3u8") || low.contains("mpegurl"))) {
            enqueue(u, h, r)
            return
        }
        r.msg.text = "جاري قراءة الجودات..."
        prober.execute {
            var vs: List<Variant> = emptyList()
            try {
                vs = variants(u, h)
            } catch (e: Exception) {
                logErr("تحميل", "تعذر قراءة الجودات: " + (e.message ?: e.javaClass.simpleName), e)
            }
            ui.post {
                if (vs.size <= 1) enqueue(u, h, r) else chooseQuality(vs, u, h, r)
            }
        }
    }

    private fun variants(u: String, h: Map<String, String>): List<Variant> {
        val pl = text(u, h)
        if (!pl.contains("#EXT-X-STREAM-INF")) return emptyList()
        val out = ArrayList<Pair<Long, Variant>>()
        val ls = pl.lines()
        for (i in ls.indices) {
            val l = ls[i]
            if (!l.startsWith("#EXT-X-STREAM-INF")) continue
            val bw = Regex("BANDWIDTH=(\\d+)").find(l)?.groupValues?.get(1)?.toLong() ?: 0L
            val res = Regex("RESOLUTION=\\d+x(\\d+)").find(l)?.groupValues?.get(1)
            val nx = ls.drop(i + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") } ?: continue
            val label = (if (res != null) res + "p" else "جودة") + " • " + (bw / 1000) + " kbps"
            out.add(Pair(bw, Variant(label, absUrl(u, nx.trim()))))
        }
        return out.sortedByDescending { it.first }.map { it.second }
    }

    private fun chooseQuality(vs: List<Variant>, u: String, h: Map<String, String>, r: Row) {
        val items = ArrayList<String>()
        items.add("الأعلى جودة (تلقائي)")
        for (v in vs) items.add(v.label)
        AlertDialog.Builder(this)
            .setTitle("اختر الجودة")
            .setItems(items.toTypedArray()) { _, i -> enqueue(if (i == 0) u else vs[i - 1].url, h, r) }
            .setOnCancelListener {
                r.msg.text = ""
                r.btn.isEnabled = true
                r.btn.alpha = 1f
                r.bar.visibility = View.GONE
            }
            .show()
    }

    private val OVERLAY_JS = """(function(){
if(window.__tdl){return;}
window.__tdl=1;
var map=new Map();
var MAXN=60;
function has(){return typeof TDL!=='undefined';}
function mk(txt,fn){
  var b=document.createElement('div');
  b.textContent=txt;
  b.setAttribute('style','width:26px;height:26px;line-height:26px;text-align:center;font:14px sans-serif;color:#fff;background:rgba(0,0,0,.65);border-radius:13px;margin:2px;cursor:pointer;-webkit-user-select:none;user-select:none;');
  b.addEventListener('click',function(e){e.stopPropagation();e.preventDefault();fn();},true);
  return b;
}
function srcOf(v){
  var s=v.currentSrc||v.src||'';
  if(!s){var so=v.querySelector('source');if(so){s=so.src||'';}}
  return s;
}
function imgSrc(el){
  return el.currentSrc||el.src||el.getAttribute('data-src')||el.getAttribute('data-original')||'';
}
function fmt(t){
  if(!isFinite(t)){return '0:00';}
  t=Math.floor(t);
  var m=Math.floor(t/60),s=t%60;
  return m+':'+(s<10?'0':'')+s;
}
function setup(el){
  var isV=el.tagName==='VIDEO';
  var st={box:null,isV:isV,ok:false,stalled:false,wt:null,drag:false,errSent:false,sl:null,tm:null,rt:null,lastTxt:''};
  var box=document.createElement('div');
  box.className='__tdl';
  box.setAttribute('style','position:fixed;left:0;top:0;z-index:2147483647;display:none;flex-direction:column;align-items:flex-start;pointer-events:none;direction:ltr;will-change:transform;');
  var row=document.createElement('div');
  row.setAttribute('style','display:flex;pointer-events:auto;');
  var dl=mk('\u2B07',function(){
    if(!has()){return;}
    if(isV){TDL.video(srcOf(el),location.href);}
    else{TDL.image(imgSrc(el),location.href,'dl');}
  });
  var cp=mk('\uD83D\uDD17',function(){
    if(!has()){return;}
    if(isV){TDL.copyVideo(srcOf(el));}
    else{TDL.image(imgSrc(el),location.href,'copy');}
  });
  row.appendChild(dl);
  row.appendChild(cp);
  if(isV){
    var rt=mk('\u27F3',function(){
      var t=el.currentTime;
      if(el.error||el.networkState===3){
        try{el.load();}catch(e){}
        var f=function(){
          el.removeEventListener('loadedmetadata',f);
          try{el.currentTime=t;}catch(e){}
          try{el.play();}catch(e){}
        };
        el.addEventListener('loadedmetadata',f);
      }else{
        try{el.play();}catch(e){}
      }
      st.stalled=false;st.errSent=false;
    });
    rt.style.display='none';
    row.appendChild(rt);
    st.rt=rt;
    var sl=document.createElement('input');
    sl.type='range';sl.min=0;sl.max=1000;sl.value=0;
    sl.setAttribute('style','pointer-events:auto;width:200px;height:22px;margin:2px;display:none;');
    var stop=function(e){e.stopPropagation();};
    ['touchstart','touchmove','touchend','mousedown','mouseup','click'].forEach(function(n){sl.addEventListener(n,stop,false);});
    sl.addEventListener('input',function(){
      st.drag=true;
      if(isFinite(el.duration)&&el.duration>0){
        try{el.currentTime=el.duration*sl.value/1000;}catch(e){}
      }
    });
    sl.addEventListener('change',function(){st.drag=false;});
    var tm=document.createElement('div');
    tm.setAttribute('style','font:11px sans-serif;color:#fff;background:rgba(0,0,0,.65);border-radius:8px;padding:1px 6px;margin:0 2px;display:none;');
    box.appendChild(row);
    box.appendChild(sl);
    box.appendChild(tm);
    st.sl=sl;st.tm=tm;
    var clr=function(){if(st.wt){clearTimeout(st.wt);st.wt=null;}st.stalled=false;st.errSent=false;};
    el.addEventListener('waiting',function(){
      if(!st.wt){st.wt=setTimeout(function(){st.stalled=true;st.wt=null;},6000);}
    });
    el.addEventListener('playing',clr);
    el.addEventListener('canplay',clr);
    el.addEventListener('timeupdate',function(){if(st.wt||st.stalled){clr();}});
    el.addEventListener('error',function(){
      st.stalled=true;
      if(!st.errSent&&has()){
        st.errSent=true;
        var c=el.error?el.error.code:0;
        TDL.err('توقف الفيديو (رمز '+c+'): '+srcOf(el).slice(0,150));
      }
    });
  }else{
    box.appendChild(row);
  }
  document.body.appendChild(box);
  st.box=box;
  map.set(el,st);
}
// خفيف: يُستدعى أثناء التمرير وكل 300 مللي ثانية، يحرّك الأزرار مع العنصر
function upd(){
  var vw=window.innerWidth,vh=window.innerHeight;
  map.forEach(function(st,el){
    if(!st.ok){if(st.box.style.display!=='none'){st.box.style.display='none';}return;}
    var r=el.getBoundingClientRect();
    if(r.width<1||r.bottom<0||r.top>vh||r.right<0||r.left>vw){
      if(st.box.style.display!=='none'){st.box.style.display='none';}
      return;
    }
    if(st.box.style.display!=='flex'){st.box.style.display='flex';}
    var x=Math.max(r.left,0)+4,y=Math.max(r.top,0)+4;
    st.box.style.transform='translate('+x+'px,'+y+'px)';
    if(st.isV){
      var d=el.duration;
      var ok=isFinite(d)&&d>0;
      var dsp=ok?'block':'none';
      if(st.sl.style.display!==dsp){st.sl.style.display=dsp;st.tm.style.display=dsp;}
      if(ok){
        st.sl.style.width=Math.max(80,Math.min(r.width-12,240))+'px';
        if(!st.drag){st.sl.value=Math.round(el.currentTime/d*1000);}
        var tx=fmt(el.currentTime)+' / '+fmt(d);
        if(tx!==st.lastTxt){st.lastTxt=tx;st.tm.textContent=tx;}
      }
      var rs=(st.stalled||el.error)?'block':'none';
      if(st.rt.style.display!==rs){st.rt.style.display=rs;}
    }
  });
}
function scan(){
  var list=document.querySelectorAll('video,img');
  for(var i=0;i<list.length;i++){
    var el=list[i];
    if(!map.has(el)&&map.size<MAXN){
      var r=el.getBoundingClientRect();
      if(el.tagName==='VIDEO'){setup(el);}
      else if(r.width>=100&&r.height>=100){setup(el);}
    }
  }
  map.forEach(function(st,el){
    if(!document.contains(el)){
      if(st.box&&st.box.parentNode){st.box.parentNode.removeChild(st.box);}
      map.delete(el);
    }else{
      var r2=el.getBoundingClientRect();
      var cs=window.getComputedStyle(el);
      var big=st.isV?(r2.width>=80&&r2.height>=60):(r2.width>=100&&r2.height>=100);
      st.ok=big&&cs.display!=='none'&&cs.visibility!=='hidden';
    }
  });
  upd();
}
var tick=false;
function sched(){
  if(tick){return;}
  tick=true;
  requestAnimationFrame(function(){tick=false;upd();});
}
window.addEventListener('scroll',sched,{capture:true,passive:true});
window.addEventListener('touchmove',sched,{capture:true,passive:true});
window.addEventListener('resize',sched,false);
setInterval(upd,300);
setInterval(scan,1500);
scan();
})();
"""

    // ---------- قائمة الروابط الملتقطة ----------

    private fun updateCounts() {
        val n = found.size
        badge.visibility = if (n > 0 && page == 0) View.VISIBLE else View.GONE
        badge.text = "تحميل ($n)"
        val act = activeBox.childCount
        navItems[1].second.text = if (act > 0) "جارٍ ($act)" else if (n > 0) "التحميلات ($n)" else "التحميلات"
        emptyTv.visibility = if (n > 0) View.GONE else View.VISIBLE
        if (navItems.size > 4) navItems[4].second.text = if (alerts.isNotEmpty()) "التنبيهات (" + alerts.size + ")" else "التنبيهات"
        alertEmpty.visibility = if (alerts.isNotEmpty()) View.GONE else View.VISIBLE
    }

    private fun clearFound() {
        list.removeAllViews()
        found.clear()
        foundStart.clear()
        foundHdr.clear()
        updateCounts()
    }

    private fun addManual() {
        val u = manualBox.text.toString().trim()
        if (!(u.startsWith("http://") || u.startsWith("https://"))) {
            toast("أدخل رابطاً صحيحاً يبدأ بـ http")
            return
        }
        val h = HashMap<String, String>()
        h["User-Agent"] = curUa
        h["Referer"] = cur?.web?.url ?: ""
        h["Cookie"] = CookieManager.getInstance().getCookie(u) ?: ""
        addFound(u, h)
        manualBox.setText("")
        showPage(1)
    }

    private fun addFound(u: String, h: Map<String, String>) {
        if (found.contains(u)) return
        found.add(u)

        val row = LinearLayout(this)
        row.orientation = LinearLayout.VERTICAL
        row.background = bg(cCard, 14, cLine)
        row.setPadding(dp(12), dp(12), dp(12), dp(12))

        val name = u.substringAfterLast('/').substringBefore('?').take(60)
        val title = tv(if (name.isBlank()) "فيديو" else name, 14f, cText, true)
        val host = tv(Uri.parse(u).host ?: "", 12f, cMuted)
        val msg = tv("", 12f, cMuted)
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        bar.max = 100
        bar.progressTintList = ColorStateList.valueOf(cAccent)
        bar.progressBackgroundTintList = ColorStateList.valueOf(cLine)
        bar.visibility = View.GONE

        val btn = pill("تحميل", true) { }
        val ctl = Ctl()
        val pauseB = pill("إيقاف مؤقت", false) { }
        val cancelB = pill("إلغاء", false) { }
        pauseB.visibility = View.GONE
        cancelB.visibility = View.GONE
        pauseB.setOnClickListener {
            ctl.paused = !ctl.paused
            pauseB.text = if (ctl.paused) "استئناف" else "إيقاف مؤقت"
            msg.text = if (ctl.paused) "متوقف مؤقتاً" else "جاري التحميل..."
        }
        cancelB.setOnClickListener {
            ctl.cancelled = true
            msg.text = "جاري الإلغاء..."
        }
        val rw = Row(bar, msg, btn, pauseB, cancelB, ctl, row)
        val go: () -> Unit = {
            if (!btn.isEnabled) toast("قيد التحميل أو تم تحميله")
            else startDl(u, h, rw)
        }
        btn.setOnClickListener { go() }
        foundStart[u] = go
        foundHdr[u] = h
        val copy = pill("نسخ الرابط", false) { copyText(u) }
        val btns = LinearLayout(this)
        btns.orientation = LinearLayout.HORIZONTAL
        btns.addView(btn, lp(WRAP, WRAP, 0f, 0, 0, 8, 0))
        btns.addView(copy)
        btns.addView(pauseB, lp(WRAP, WRAP, 0f, 8, 0, 0, 0))
        btns.addView(cancelB, lp(WRAP, WRAP, 0f, 8, 0, 0, 0))

        row.addView(title)
        row.addView(host)
        row.addView(btns, lp(MATCH, WRAP, 0f, 0, 8, 0, 0))
        row.addView(bar, lp(MATCH, dp(8), 0f, 0, 8, 0, 0))
        row.addView(msg, lp(MATCH, WRAP, 0f, 0, 4, 0, 0))
        list.addView(row, 0, lp(MATCH, WRAP, 0f, 0, 0, 0, 10))
        updateCounts()
    }

    private fun enqueue(u: String, h: Map<String, String>, r: Row) {
        val ctl = r.ctl
        ctl.cancelled = false
        ctl.paused = false
        r.pause.text = "إيقاف مؤقت"
        r.pause.visibility = View.VISIBLE
        r.cancel.visibility = View.VISIBLE
        ctl.bytes.set(0)
        setActive(r, true)
        val t0 = System.currentTimeMillis()
        var lastT = t0
        var lastB = 0L
        var speed = 0.0
        val progress: (Int, Int) -> Unit = { done, total ->
            ui.post {
                r.bar.max = if (total > 0) total else 100
                r.bar.progress = done
                if (!ctl.paused && !ctl.cancelled) {
                    val now = System.currentTimeMillis()
                    val b = ctl.bytes.get()
                    if (now - lastT >= 800) {
                        val inst = (b - lastB) * 1000.0 / (now - lastT)
                        speed = if (speed == 0.0) inst else speed * 0.6 + inst * 0.4
                        lastT = now
                        lastB = b
                    }
                    val frac = if (total > 0) done.toDouble() / total else 0.0
                    val el = (now - t0) / 1000
                    val pc = (frac * 100).toInt()
                    val sb = StringBuilder()
                    if (total > 0) sb.append(pc).append("%  •  ")
                    sb.append(fmtSize(b.toDouble()))
                    if (speed > 0) sb.append("  •  ").append(fmtSize(speed)).append("/ث")
                    sb.append("\n").append("مضى ").append(fmtTime(el))
                    if (frac > 0.02) sb.append("  •  متبقي ").append(fmtTime((el * (1 - frac) / frac).toLong()))
                    r.msg.text = sb.toString()
                    if (page == 0) {
                        badge.visibility = View.VISIBLE
                        badge.text = "⬇ " + pc + "%"
                    }
                }
            }
        }
        queue.execute {
            // مجلد ثابت لكل رابط: عند الفشل تبقى الأجزاء المكتملة ويكمل من حيث توقف
            val work = File(getExternalFilesDir(null) ?: filesDir, "work_" + Integer.toHexString(u.hashCode()))
            var keep = false
            try {
                work.mkdirs()
                val name = "tango_" + System.currentTimeMillis()
                val low = u.lowercase()
                val result: String
                if (low.contains(".m3u8") || low.contains("mpegurl")) {
                    val job = hls(u, h, work, ctl, progress)
                    ui.post { r.msg.text = "جاري دمج الأجزاء..." }
                    val out = merge(job, work, name)
                    result = saveToDownloads(out, "video/mp4")
                } else {
                    val mp4 = File(work, "$name.mp4")
                    direct(u, h, mp4, work, ctl, progress)
                    result = saveToDownloads(mp4, "video/mp4")
                }
                ui.post { r.msg.text = "تم: $result"; r.bar.progress = r.bar.max }
            } catch (e: CancelledException) {
                ui.post {
                    r.msg.text = "أُلغي التحميل"
                    r.btn.isEnabled = true
                    r.btn.alpha = 1f
                    r.bar.visibility = View.GONE
                }
            } catch (e: Throwable) {
                keep = true
                logErr("تحميل", "فشل تحميل " + u.substringAfterLast('/').substringBefore('?').take(40) + ": " + (e.message ?: e.javaClass.simpleName), e)
                ui.post {
                    r.msg.text = "فشل: " + (e.message ?: e.javaClass.simpleName) + " (اضغط تحميل للإكمال من حيث توقف)"
                    r.btn.isEnabled = true
                    r.btn.alpha = 1f
                }
            } finally {
                if (!keep) work.deleteRecursively()
                ui.post {
                    r.pause.visibility = View.GONE
                    r.cancel.visibility = View.GONE
                    setActive(r, false)
                }
            }
        }
    }

    // ---------- الشبكة ----------

    private val skipHeaders = setOf(
        "range", "accept-encoding", "host", "connection", "content-length", "if-none-match", "if-modified-since"
    )

    private val probed = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val prober = Executors.newFixedThreadPool(2)
    private val staticExt = listOf(
        ".js", ".css", ".png", ".jpg", ".jpeg", ".gif", ".svg", ".webp", ".woff", ".woff2", ".ttf",
        ".ico", ".html", ".ts", ".m4s", ".aac", ".mp3", ".webm", ".map"
    )

    private fun probe(u: String, h: Map<String, String>) {
        if (probed.size > 5000 || !probed.add(u)) return
        prober.execute {
            try {
                val c = URL(u).openConnection() as HttpURLConnection
                c.requestMethod = "HEAD"
                c.connectTimeout = 5000
                c.readTimeout = 5000
                for ((k, v) in h) if (v.isNotEmpty()) c.setRequestProperty(k, v)
                val t = (c.contentType ?: "").lowercase()
                c.disconnect()
                if (t.contains("mpegurl")) ui.post { addFound(u, h) }
            } catch (e: Exception) {
            }
        }
    }

    private fun hdr(m: Map<String, String>, n: String): String? =
        m.entries.firstOrNull { it.key.equals(n, true) }?.value

    private fun <T> retry(n: Int, f: () -> T): T {
        var last: Exception? = null
        for (i in 0 until n) {
            try {
                return f()
            } catch (e: CancelledException) {
                throw e
            } catch (e: Exception) {
                last = e
                try { Thread.sleep(500L * (i + 1)) } catch (ie: InterruptedException) { throw e }
            }
        }
        throw last!!
    }

    // نفس الهيدرز (User-Agent / Referer / Cookie ...) لكل طلب، مع متابعة التحويلات يدوياً
    private fun conn(u0: String, h: Map<String, String>, range: String? = null): HttpURLConnection {
        var u = u0
        for (hop in 0..5) {
            val c = URL(u).openConnection() as HttpURLConnection
            c.connectTimeout = 30000
            c.readTimeout = 60000
            c.instanceFollowRedirects = false
            for ((k, v) in h) {
                if (v.isNotEmpty() && k.lowercase() !in skipHeaders) c.setRequestProperty(k, v)
            }
            c.setRequestProperty("Accept-Encoding", "identity")
            if (range != null) c.setRequestProperty("Range", range)
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location")
                c.disconnect()
                if (loc == null) throw Exception("HTTP $code")
                u = URL(URL(u), loc).toString()
                continue
            }
            if (code >= 400) {
                c.disconnect()
                throw Exception("HTTP $code")
            }
            return c
        }
        throw Exception("تحويلات كثيرة")
    }

    private fun text(u: String, h: Map<String, String>): String =
        conn(u, h).inputStream.bufferedReader().use { it.readText() }

    private fun bytes(u: String, h: Map<String, String>): ByteArray =
        conn(u, h).inputStream.use { it.readBytes() }

    private fun absUrl(base: String, ref: String): String = URL(URL(base), ref).toString()

    private fun direct(u: String, h: Map<String, String>, out: File, work: File, ctl: Ctl, progress: (Int, Int) -> Unit) {
        gate(ctl)
        var total = -1L
        var ranges = false
        try {
            val c = conn(u, h, "bytes=0-0")
            if (c.responseCode == 206) {
                val t = (c.getHeaderField("Content-Range") ?: "").substringAfterLast('/', "").trim().toLongOrNull()
                if (t != null && t > 0) {
                    total = t
                    ranges = true
                }
            } else {
                total = c.contentLengthLong
            }
            c.disconnect()
        } catch (e: Exception) {
        }

        // الخادم لا يدعم التجزئة أو الملف صغير: تحميل عادي
        if (!ranges || total < 2000000L) {
            val c = conn(u, h)
            val tl = c.contentLengthLong
            var done = 0L
            c.inputStream.use { i ->
                out.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        gate(ctl)
                        val n = i.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                        done += n
                        ctl.bytes.addAndGet(n.toLong())
                        if (tl > 0) progress((done * 100 / tl).toInt(), 100) else progress(0, 0)
                    }
                }
            }
            return
        }

        // تحميل متوازٍ: كل جزء من الملف في اتصال مستقل، وكل جزء قابل للاستئناف
        val n = prefs().getInt("threads", 3).coerceIn(2, 6)
        val size = (total + n - 1) / n
        val got = java.util.concurrent.atomic.AtomicLong(0)
        val files = ArrayList<File>()
        for (k in 0 until n) files.add(File(work, "chunk_" + k + ".part"))
        for (f in files) got.addAndGet(f.length())
        val pool = Executors.newFixedThreadPool(n)
        try {
            val fs = (0 until n).map { k ->
                pool.submit(Callable<Unit> {
                    val start = k * size
                    val end = minOf(total - 1, start + size - 1)
                    val f = files[k]
                    var tries = 0
                    while (true) {
                        gate(ctl)
                        val have = f.length()
                        if (start + have > end) break
                        try {
                            val c = conn(u, h, "bytes=" + (start + have) + "-" + end)
                            if (c.responseCode != 206) throw Exception("الخادم لم يدعم التحميل المتجزئ")
                            c.inputStream.use { i ->
                                java.io.FileOutputStream(f, true).use { o ->
                                    val buf = ByteArray(64 * 1024)
                                    while (true) {
                                        gate(ctl)
                                        val m = i.read(buf)
                                        if (m < 0) break
                                        o.write(buf, 0, m)
                                        val g = got.addAndGet(m.toLong())
                                        ctl.bytes.addAndGet(m.toLong())
                                        progress((g * 100 / total).toInt(), 100)
                                    }
                                }
                            }
                        } catch (e: CancelledException) {
                            throw e
                        } catch (e: Exception) {
                            tries++
                            if (tries > 6) throw e
                            Thread.sleep(600L * tries)
                        }
                        if (f.length() == have) {
                            tries++
                            if (tries > 6) throw Exception("توقف وصول البيانات من الخادم")
                            Thread.sleep(600L * tries)
                        }
                    }
                })
            }
            for (f in fs) {
                try {
                    f.get()
                } catch (e: ExecutionException) {
                    val c = e.cause
                    if (c is CancelledException) throw c
                    throw Exception(c?.message ?: "فشل تحميل جزء من الملف")
                }
            }
        } finally {
            pool.shutdownNow()
        }
        out.outputStream().use { o ->
            for (f in files) f.inputStream().use { it.copyTo(o, 256 * 1024) }
        }
        for (f in files) f.delete()
    }

    private class Seg(val index: Int, val url: String, val key: ByteArray?, val iv: ByteArray)
    private class HlsJob(val parts: List<File>, val init: File?, val mp4Family: Boolean)

    private fun makeIv(hex: String?, n: Int): ByteArray {
        if (hex != null) {
            val b = hex.padStart(32, '0').chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            return if (b.size > 16) b.copyOfRange(b.size - 16, b.size) else b
        }
        val iv = ByteArray(16)
        for (k in 0..3) iv[15 - k] = (n shr (8 * k)).toByte()
        return iv
    }

    // 4 + 5 + 6 + 7: اختيار الجودة، قراءة القائمة، التحميل المتوازي، فك التشفير
    private fun hls(u0: String, h: Map<String, String>, work: File, ctl: Ctl, progress: (Int, Int) -> Unit): HlsJob {
        var url = u0
        var pl = text(url, h)

        // قائمة رئيسية: اختر أعلى جودة (أعلى BANDWIDTH) وافتح قائمتها
        if (pl.contains("#EXT-X-STREAM-INF")) {
            var best = ""
            var bw = -1L
            val ls = pl.lines()
            for (i in ls.indices) {
                if (ls[i].startsWith("#EXT-X-STREAM-INF")) {
                    val bwv = Regex("BANDWIDTH=(\\d+)").find(ls[i])?.groupValues?.get(1)?.toLong() ?: 0L
                    val nx = ls.drop(i + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") } ?: continue
                    if (bw < 0 || (if (lowQ()) bwv < bw else bwv > bw)) {
                        bw = bwv
                        best = nx
                    }
                }
            }
            if (best.isBlank()) throw Exception("لم يتم العثور على جودة في القائمة الرئيسية")
            url = absUrl(url, best.trim())
            pl = text(url, h)
        }

        // قراءة القائمة: كل سطر لا يبدأ بـ # هو جزء، والنسبي يُضاف لعنوان القائمة
        val seq = Regex("#EXT-X-MEDIA-SEQUENCE:(\\d+)").find(pl)?.groupValues?.get(1)?.toInt() ?: 0
        var curKey: ByteArray? = null
        var curIv: String? = null
        var initUrl: String? = null
        val keyCache = HashMap<String, ByteArray>()
        val segs = ArrayList<Seg>()
        for (raw in pl.lines()) {
            val l = raw.trim()
            if (l.isEmpty()) continue
            if (l.startsWith("#EXT-X-KEY")) {
                val method = Regex("METHOD=([A-Z0-9-]+)").find(l)?.groupValues?.get(1) ?: "NONE"
                if (method == "NONE") {
                    curKey = null
                    curIv = null
                } else if (method == "AES-128") {
                    val ku = Regex("URI=\"([^\"]+)\"").find(l)?.groupValues?.get(1)
                        ?: throw Exception("المفتاح بدون رابط")
                    val ka = absUrl(url, ku)
                    curKey = keyCache.getOrPut(ka) { retry(3) { bytes(ka, h) } }
                    curIv = Regex("IV=0x([0-9A-Fa-f]+)").find(l)?.groupValues?.get(1)
                } else {
                    throw Exception("نوع تشفير غير مدعوم: $method")
                }
            } else if (l.startsWith("#EXT-X-MAP")) {
                val mu = Regex("URI=\"([^\"]+)\"").find(l)?.groupValues?.get(1)
                if (mu != null) initUrl = absUrl(url, mu)
            } else if (!l.startsWith("#")) {
                val i = segs.size
                segs.add(Seg(i, absUrl(url, l), curKey, makeIv(curIv, seq + i)))
            }
        }
        if (segs.isEmpty()) throw Exception("القائمة فاضية")

        // كل جزء يُحفظ في ملف برقمه (part_00000.seg ...) لضمان الترتيب وعدم ملء الذاكرة
        val parts = ArrayList<File>()
        for (s in segs) parts.add(File(work, "part_%05d.seg".format(s.index)))
        val done = AtomicInteger(0)
        val pool = Executors.newFixedThreadPool(prefs().getInt("threads", 3).coerceIn(1, 6))
        try {
            val futures = segs.map { s ->
                pool.submit(Callable<Unit> {
                    gate(ctl)
                    val part = parts[s.index]
                    // جزء مكتمل من محاولة سابقة: لا يُعاد تحميله
                    if (!(part.exists() && part.length() > 0)) {
                        var d = retry(5) { gate(ctl); bytes(s.url, h) }
                        ctl.bytes.addAndGet(d.size.toLong())
                        if (s.key != null) {
                            val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
                            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(s.key, "AES"), IvParameterSpec(s.iv))
                            d = c.doFinal(d)
                        }
                        val tmp = File(work, "part_%05d.tmp".format(s.index))
                        tmp.writeBytes(d)
                        if (!tmp.renameTo(part)) {
                            part.writeBytes(d)
                            tmp.delete()
                        }
                    }
                    progress(done.incrementAndGet(), segs.size)
                })
            }
            for (f in futures) {
                try {
                    f.get()
                } catch (e: ExecutionException) {
                    val c = e.cause
                    if (c is CancelledException) throw c
                    throw Exception(c?.message ?: "فشل تحميل جزء")
                }
            }
        } finally {
            pool.shutdownNow()
        }

        val initFile = initUrl?.let { iu ->
            val f = File(work, "init.mp4")
            f.writeBytes(retry(3) { bytes(iu, h) })
            f
        }
        val ext = segs[0].url.substringBefore('?').substringAfterLast('.').lowercase()
        val mp4Family = initFile != null || ext in setOf("mp4", "m4s", "m4v", "m4a", "cmfv", "cmfa")
        return HlsJob(parts, initFile, mp4Family)
    }

    // 8: ترتيب الأجزاء ودمجها في ملف واحد: mp4parser أولاً، وMediaMuxer كبديل (يشمل HEVC)
    private fun merge(job: HlsJob, work: File, name: String): File {
        val out = File(work, "$name.mp4")
        val ini = job.init
        // الملف المتسلسل بترتيب الأجزاء (TS كامل، أو fMP4 مع ملف التهيئة) ليستعمله MediaMuxer عند الحاجة
        val whole: File? = if (!job.mp4Family) File(work, "whole.ts") else if (ini != null) File(work, "whole.mp4") else null
        if (whole != null) {
            whole.outputStream().buffered().use { o ->
                if (ini != null) ini.inputStream().use { it.copyTo(o) }
                for (p in job.parts) p.inputStream().use { it.copyTo(o) }
            }
        }
        try {
            val movie: Movie
            if (!job.mp4Family) {
                // TS: تُفكّ الحزم إلى مسار H.264 ومسار AAC ثم يبنيهما mp4parser
                val v = File(work, "video.h264")
                val a = File(work, "audio.aac")
                val used = demuxTs(job.parts, v, a)
                movie = Movie()
                if (used.first) movie.addTrack(H264TrackImpl(FileDataSourceImpl(v)))
                if (used.second) movie.addTrack(AACTrackImpl(FileDataSourceImpl(a)))
                if (movie.tracks.isEmpty()) throw Exception("لا يوجد فيديو H.264 أو صوت AAC داخل الأجزاء")
            } else if (whole != null) {
                movie = MovieCreator.build(whole.absolutePath)
            } else {
                val video = ArrayList<Track>()
                val audio = ArrayList<Track>()
                for (p in job.parts) {
                    for (t in MovieCreator.build(p.absolutePath).tracks) {
                        if (t.handler == "vide") video.add(t) else if (t.handler == "soun") audio.add(t)
                    }
                }
                movie = Movie()
                if (video.isNotEmpty()) movie.addTrack(AppendTrack(*video.toTypedArray()))
                if (audio.isNotEmpty()) movie.addTrack(AppendTrack(*audio.toTypedArray()))
            }
            val c = DefaultMp4Builder().build(movie)
            FileOutputStream(out).use { fo -> c.writeContainer(fo.channel) }
            return out
        } catch (e: Throwable) {
            // بديل: MediaMuxer (يدعم HEVC وأي ترميز يقرأه النظام) على الملف المتسلسل
            out.delete()
            logErr("دمج", "فشل mp4parser، جاري تجربة MediaMuxer: " + (e.message ?: e.javaClass.simpleName), e)
            if (whole != null && remux(whole, out)) return out
            if (whole == null && remuxParts(job.parts, out)) return out
            out.delete()
            throw Exception("فشل دمج الأجزاء: " + (e.message ?: e.javaClass.simpleName))
        }
    }

    // يحوّل الملف المتسلسل إلى mp4 بدون إعادة ترميز (MediaExtractor + MediaMuxer)
    private fun remux(src: File, mp4: File): Boolean {
        val ex = MediaExtractor()
        var mux: MediaMuxer? = null
        var started = false
        try {
            ex.setDataSource(src.absolutePath)
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

    // بديل MediaMuxer لأجزاء MP4 المستقلة: يقرأ كل جزء بالترتيب ويكتب عيّناته في ملف واحد مع إزاحة الزمن
    private fun remuxParts(parts: List<File>, mp4: File): Boolean {
        var mux: MediaMuxer? = null
        var started = false
        try {
            mux = MediaMuxer(mp4.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val kindToTrack = HashMap<String, Int>()
            val buf = ByteBuffer.allocate(4 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            var offset = 0L
            for (pf in parts) {
                val ex = MediaExtractor()
                try {
                    ex.setDataSource(pf.absolutePath)
                    val map = HashMap<Int, Int>()
                    for (i in 0 until ex.trackCount) {
                        val f = ex.getTrackFormat(i)
                        val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                        val kind = if (mime.startsWith("video/")) "video" else if (mime.startsWith("audio/")) "audio" else continue
                        if (!started && !kindToTrack.containsKey(kind)) kindToTrack[kind] = mux.addTrack(f)
                        val mt = kindToTrack[kind] ?: continue
                        map[i] = mt
                        ex.selectTrack(i)
                    }
                    if (!started) {
                        if (kindToTrack.isEmpty()) return false
                        mux.start()
                        started = true
                    }
                    var maxPts = 0L
                    while (true) {
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) break
                        val t = map[ex.sampleTrackIndex]
                        val pts = ex.sampleTime
                        if (t != null) {
                            info.offset = 0
                            info.size = n
                            info.presentationTimeUs = pts + offset
                            info.flags = if ((ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                            mux.writeSampleData(t, buf, info)
                            if (pts > maxPts) maxPts = pts
                        }
                        ex.advance()
                    }
                    offset += maxPts + 33000L
                } finally {
                    try { ex.release() } catch (e: Exception) { }
                }
            }
            mux.stop()
            started = false
            return true
        } catch (e: Exception) {
            return false
        } finally {
            try { if (started) mux?.stop() } catch (e: Exception) { }
            try { mux?.release() } catch (e: Exception) { }
        }
    }

    // يفكّ حزم MPEG-TS (188 بايت) بترتيب الأجزاء: PAT ثم PMT ثم PES، ويكتب مسار H.264 ومسار AAC(ADTS)
    private fun demuxTs(parts: List<File>, vFile: File, aFile: File): Pair<Boolean, Boolean> {
        var pmtPid = -1
        var vPid = -1
        var aPid = -1
        var hevc = false
        var vUsed = false
        var aUsed = false
        val vo = vFile.outputStream().buffered()
        val ao = aFile.outputStream().buffered()
        try {
            for (part in parts) {
                val d = part.readBytes()
                var i = 0
                while (i + 188 <= d.size) {
                    if (d[i] != 0x47.toByte()) { i++; continue }
                    val pid = ((d[i + 1].toInt() and 0x1F) shl 8) or (d[i + 2].toInt() and 0xFF)
                    val pusi = (d[i + 1].toInt() and 0x40) != 0
                    val afc = (d[i + 3].toInt() shr 4) and 3
                    var off = i + 4
                    if ((afc and 2) != 0) off += 1 + (d[i + 4].toInt() and 0xFF)
                    val end = i + 188
                    if ((afc and 1) != 0 && off < end) {
                        try {
                            if (pid == 0 && pusi) {
                                val s0 = off + 1 + (d[off].toInt() and 0xFF)
                                val secLen = ((d[s0 + 1].toInt() and 0x0F) shl 8) or (d[s0 + 2].toInt() and 0xFF)
                                var q = s0 + 8
                                val qend = minOf(s0 + 3 + secLen - 4, end)
                                while (q + 4 <= qend) {
                                    val prog = ((d[q].toInt() and 0xFF) shl 8) or (d[q + 1].toInt() and 0xFF)
                                    val pp = ((d[q + 2].toInt() and 0x1F) shl 8) or (d[q + 3].toInt() and 0xFF)
                                    if (prog != 0) { pmtPid = pp; break }
                                    q += 4
                                }
                            } else if (pid == pmtPid && pusi) {
                                val s0 = off + 1 + (d[off].toInt() and 0xFF)
                                val secLen = ((d[s0 + 1].toInt() and 0x0F) shl 8) or (d[s0 + 2].toInt() and 0xFF)
                                val piLen = ((d[s0 + 10].toInt() and 0x0F) shl 8) or (d[s0 + 11].toInt() and 0xFF)
                                var q = s0 + 12 + piLen
                                val qend = minOf(s0 + 3 + secLen - 4, end)
                                while (q + 5 <= qend) {
                                    val st = d[q].toInt() and 0xFF
                                    val ep = ((d[q + 1].toInt() and 0x1F) shl 8) or (d[q + 2].toInt() and 0xFF)
                                    val esLen = ((d[q + 3].toInt() and 0x0F) shl 8) or (d[q + 4].toInt() and 0xFF)
                                    if (st == 0x1B && vPid < 0) vPid = ep
                                    else if (st == 0x0F && aPid < 0) aPid = ep
                                    else if (st == 0x24) hevc = true
                                    q += 5 + esLen
                                }
                            } else if (pid == vPid || pid == aPid) {
                                var ps = off
                                if (pusi && ps + 9 <= end && d[ps].toInt() == 0 && d[ps + 1].toInt() == 0 && d[ps + 2].toInt() == 1) {
                                    ps += 9 + (d[ps + 8].toInt() and 0xFF)
                                }
                                if (ps < end) {
                                    if (pid == vPid) { vo.write(d, ps, end - ps); vUsed = true }
                                    else { ao.write(d, ps, end - ps); aUsed = true }
                                }
                            }
                        } catch (e: IndexOutOfBoundsException) {
                        }
                    }
                    i += 188
                }
            }
        } finally {
            vo.close()
            ao.close()
        }
        if (hevc && !vUsed) throw Exception("HEVC: يتولاه MediaMuxer")
        return Pair(vUsed, aUsed)
    }

    // ينقل الملف النهائي إلى مجلد التحميلات (Download)
    private fun saveToDownloads(f: File, mime: String): String {
        if (Build.VERSION.SDK_INT >= 29) {
            val v = ContentValues()
            v.put(MediaStore.Downloads.DISPLAY_NAME, f.name)
            v.put(MediaStore.Downloads.MIME_TYPE, mime)
            v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            v.put(MediaStore.Downloads.IS_PENDING, 1)
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)
                ?: throw Exception("تعذر إنشاء الملف في مجلد التحميلات")
            contentResolver.openOutputStream(uri)!!.use { o ->
                f.inputStream().use { it.copyTo(o) }
            }
            val done = ContentValues()
            done.put(MediaStore.Downloads.IS_PENDING, 0)
            contentResolver.update(uri, done, null, null)
            f.delete()
            return "Download/" + f.name
        }
        val d = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        d.mkdirs()
        val out = File(d, f.name)
        f.copyTo(out, true)
        f.delete()
        return out.absolutePath
    }
}
