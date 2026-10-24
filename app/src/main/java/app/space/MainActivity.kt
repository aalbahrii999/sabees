package app.space

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Message
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import app.space.databinding.ActivityMainBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var store: Store
    private val tabs = mutableListOf<Tab>()
    private var index = 0
    private var desktopMode = false
    private var mobileUa: String? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingDownload: DownloadReq? = null

    private val ink by lazy { ContextCompat.getColor(this, R.color.ink) }
    private val muted by lazy { ContextCompat.getColor(this, R.color.muted) }
    private val teal by lazy { ContextCompat.getColor(this, R.color.teal) }

    private val pickFile = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val callback = fileCallback
        fileCallback = null
        val uris = if (result.resultCode == RESULT_OK) {
            WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        } else {
            null
        }
        callback?.onReceiveValue(uris)
    }

    private val storagePerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val pending = pendingDownload
        pendingDownload = null
        if (granted && pending != null) enqueue(pending)
        else if (!granted) toast(R.string.download_failed)
    }

    override fun attachBaseContext(newBase: Context) {
        val locale = Locale("ar")
        Locale.setDefault(locale)
        val config = Configuration(newBase.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        store = Store(this)
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = onBackAction(fromSystem = true)
            },
        )
        wire()
        restore()
        present()
    }

    override fun onPause() {
        persist()
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onDestroy() {
        tabs.forEach { tab ->
            tab.web?.let { web ->
                (web.parent as? ViewGroup)?.removeView(web)
                web.destroy()
            }
        }
        super.onDestroy()
    }

    private fun wire() {
        binding.btnBack.setOnClickListener { onBackAction(fromSystem = false) }
        binding.btnForward.setOnClickListener {
            val web = current().web
            if (!current().atHome && web?.canGoForward() == true) web.goForward()
        }
        binding.btnReload.setOnClickListener {
            val tab = current()
            if (tab.atHome) return@setOnClickListener
            if (tab.loading) tab.web?.stopLoading() else if (tab.url.startsWith("http")) tab.web?.loadUrl(tab.url)
        }
        binding.btnMenu.setOnClickListener { showMenu() }
        binding.btnStar.setOnClickListener { toggleStar() }
        binding.scrim.setOnClickListener { closeSheet() }
        binding.sheet.setOnClickListener { }
        binding.address.setOnEditorActionListener { _, actionId, event ->
            val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_SEARCH || enter) {
                go(binding.address.text.toString())
                true
            } else {
                false
            }
        }
        binding.homeField.setOnEditorActionListener { _, actionId, event ->
            val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_SEARCH || enter) {
                go(binding.homeField.text.toString())
                true
            } else {
                false
            }
        }
        binding.address.setOnFocusChangeListener { _, focused ->
            if (focused) binding.address.post { binding.address.selectAll() }
        }
    }

    private fun restore() {
        val (saved, savedIndex, desktop) = store.session()
        desktopMode = desktop
        if (saved.isEmpty()) {
            tabs.add(Tab())
        } else {
            saved.forEach { (url, home) ->
                tabs.add(
                    Tab().apply {
                        this.url = url
                        atHome = home || url.isBlank()
                        title = if (url.isBlank()) getString(R.string.app_name) else Urls.host(url)
                    },
                )
            }
            index = savedIndex.coerceIn(0, tabs.lastIndex)
        }
    }

    private fun persist() {
        if (!::store.isInitialized || tabs.isEmpty()) return
        store.saveSession(
            tabs.map { it.url },
            tabs.map { it.atHome },
            index,
            desktopMode,
        )
    }

    private fun current(): Tab = tabs[index]

    private fun present() {
        val tab = current()
        if (tab.atHome) {
            binding.homePanel.isVisible = true
        } else {
            binding.homePanel.isVisible = false
            val web = ensureWeb(tab)
            attach(web)
            if (tab.url.startsWith("http") && (web.url == null || web.url == "about:blank")) {
                web.loadUrl(tab.url)
            }
        }
        updateChrome()
        renderTabs()
        renderHomeMarks()
    }

    private fun go(raw: String) {
        val url = Urls.resolve(raw) ?: return
        val tab = current()
        tab.url = url
        tab.atHome = false
        tab.title = Urls.host(url)
        val web = ensureWeb(tab)
        attach(web)
        binding.homePanel.isVisible = false
        web.loadUrl(url)
        hideIme()
        binding.address.clearFocus()
        binding.homeField.setText("")
        closeSheet()
        updateChrome()
        renderTabs()
    }

    private fun showHome() {
        current().atHome = true
        binding.homePanel.isVisible = true
        binding.homeField.setText("")
        hideIme()
        binding.address.clearFocus()
        updateChrome()
        renderHomeMarks()
        renderTabs()
    }

    private fun onBackAction(fromSystem: Boolean) {
        if (binding.sheet.isVisible) {
            closeSheet()
            return
        }
        val tab = current()
        val web = tab.web
        if (!tab.atHome && web?.canGoBack() == true) {
            web.goBack()
            return
        }
        if (!tab.atHome) {
            showHome()
            return
        }
        if (!fromSystem && tab.url.startsWith("http")) {
            tab.atHome = false
            present()
            return
        }
        if (fromSystem) finish()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureWeb(tab: Tab): WebView {
        tab.web?.let { return it }
        val web = WebView(this)
        web.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        web.setBackgroundColor(ContextCompat.getColor(this, R.color.night))
        val settings = web.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.loadsImagesAutomatically = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.mediaPlaybackRequiresUserGesture = true
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        settings.setSupportMultipleWindows(true)
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.allowFileAccess = false
        settings.allowContentAccess = true
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        try {
            settings.safeBrowsingEnabled = true
        } catch (_: Throwable) {
        }
        if (mobileUa == null) mobileUa = settings.userAgentString
        settings.userAgentString = if (desktopMode) DESKTOP_UA else mobileUa
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.webViewClient = clientFor(tab)
        web.webChromeClient = chromeFor(tab)
        web.setDownloadListener { url, agent, disposition, mime, _ ->
            startDownload(url, agent, disposition, mime)
        }
        tab.web = web
        return web
    }

    private fun attach(web: WebView) {
        if (web.parent === binding.webContainer) return
        (web.parent as? ViewGroup)?.removeView(web)
        binding.webContainer.removeAllViews()
        binding.webContainer.addView(web)
    }

    private fun clientFor(tab: Tab) = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url ?: return false
            val scheme = uri.scheme?.lowercase().orEmpty()
            if (scheme == "http" || scheme == "https" || scheme == "about" || scheme == "blob" || scheme == "data") {
                return false
            }
            if (scheme == "intent") {
                try {
                    val intent = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
                    val fallback = intent.getStringExtra("browser_fallback_url")
                    try {
                        startActivity(intent)
                    } catch (_: ActivityNotFoundException) {
                        if (!fallback.isNullOrBlank()) view.loadUrl(fallback)
                    }
                } catch (_: Exception) {
                }
                return true
            }
            try {
                startActivity(Intent(Intent.ACTION_VIEW, uri))
            } catch (_: ActivityNotFoundException) {
            }
            return true
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            if (url.startsWith("data:")) return
            tab.loading = true
            if (url.startsWith("http")) {
                tab.url = url
                tab.atHome = false
            }
            if (view === current().web) {
                binding.homePanel.isVisible = false
                updateChrome()
            }
        }

        override fun onPageFinished(view: WebView, url: String) {
            tab.loading = false
            if (url.startsWith("http") && url == view.url) {
                tab.url = url
                val title = view.title?.takeIf { it.isNotBlank() && !it.startsWith("http") } ?: Urls.host(url)
                tab.title = title
                store.addHistory(title, url)
            }
            if (view === current().web) updateChrome()
            renderTabs()
            renderHomeMarks()
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (!request.isForMainFrame) return
            tab.loading = false
            view.loadDataWithBaseURL(null, errorPage(), "text/html", "utf-8", null)
            if (view === current().web) updateChrome()
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) {
            handler.cancel()
            tab.loading = false
            view.loadDataWithBaseURL(null, errorPage(), "text/html", "utf-8", null)
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            (view.parent as? ViewGroup)?.removeView(view)
            if (tab.web === view) tab.web = null
            view.destroy()
            if (tabs.getOrNull(index)?.let { it === tab } == true && !tab.atHome) {
                val web = ensureWeb(tab)
                attach(web)
                if (tab.url.startsWith("http")) web.loadUrl(tab.url)
            }
            return true
        }
    }

    private fun chromeFor(tab: Tab) = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            if (view !== current().web) return
            val busy = newProgress in 1..99
            tab.loading = busy
            binding.progress.isVisible = busy
            binding.progress.progress = newProgress
            updateReloadIcon()
        }

        override fun onReceivedTitle(view: WebView, title: String?) {
            if (!title.isNullOrBlank() && !title.startsWith("http")) {
                tab.title = title
                if (tab.url.startsWith("http")) store.addHistory(title, tab.url)
            }
            if (view === current().web) renderTabs()
        }

        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
            val temp = WebView(this@MainActivity)
            temp.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                    val target = request.url?.toString().orEmpty()
                    if (target.startsWith("http")) go(target)
                    v.destroy()
                    return true
                }
            }
            transport.webView = temp
            resultMsg.sendToTarget()
            return true
        }

        override fun onShowFileChooser(
            webView: WebView?,
            filePathCallback: ValueCallback<Array<Uri>>?,
            fileChooserParams: FileChooserParams?,
        ): Boolean {
            fileCallback?.onReceiveValue(null)
            fileCallback = filePathCallback
            return try {
                val intent = fileChooserParams?.createIntent()
                    ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                        type = "*/*"
                        addCategory(Intent.CATEGORY_OPENABLE)
                    }
                pickFile.launch(intent)
                true
            } catch (_: Exception) {
                fileCallback = null
                filePathCallback?.onReceiveValue(null)
                false
            }
        }

        override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
            callback?.invoke(origin, false, false)
        }
    }

    private fun updateChrome() {
        val tab = current()
        if (!binding.address.hasFocus()) {
            binding.address.setText(if (tab.atHome) "" else tab.url)
        }
        val canBack = !tab.atHome || tab.url.startsWith("http")
        binding.btnBack.isEnabled = canBack
        binding.btnBack.alpha = if (canBack) 1f else 0.35f
        val canForward = !tab.atHome && tab.web?.canGoForward() == true
        binding.btnForward.isEnabled = canForward
        binding.btnForward.alpha = if (canForward) 1f else 0.35f
        binding.btnReload.isEnabled = !tab.atHome
        binding.btnReload.alpha = if (tab.atHome) 0.35f else 1f
        val showStar = !tab.atHome && tab.url.startsWith("http")
        binding.btnStar.isVisible = showStar
        if (showStar) {
            val on = store.isBookmarked(tab.url)
            binding.btnStar.setImageResource(if (on) R.drawable.ic_star_on else R.drawable.ic_star)
            binding.btnStar.imageTintList = android.content.res.ColorStateList.valueOf(if (on) teal else muted)
        }
        updateReloadIcon()
        if (!tab.loading) binding.progress.isVisible = false
    }

    private fun updateReloadIcon() {
        val loading = !current().atHome && current().loading
        binding.btnReload.setImageResource(if (loading) R.drawable.ic_close else R.drawable.ic_refresh)
        binding.btnReload.contentDescription = getString(if (loading) R.string.stop else R.string.reload)
    }

    private fun renderTabs() {
        val strip = binding.tabStrip
        strip.removeAllViews()
        if (tabs.size < 2) {
            binding.tabStripScroll.isVisible = false
            return
        }
        binding.tabStripScroll.isVisible = true
        tabs.forEachIndexed { i, tab ->
            val chip = TextView(this)
            chip.text = if (tab.atHome || tab.title.isBlank()) getString(R.string.app_name) else tab.title
            chip.setTextColor(if (i == index) ink else muted)
            chip.textSize = 13f
            chip.maxLines = 1
            chip.setPadding(dp(14), dp(8), dp(14), dp(8))
            if (i == index) chip.setBackgroundResource(R.drawable.chip)
            chip.setOnClickListener {
                index = i
                present()
            }
            chip.setOnLongClickListener {
                closeTab(i)
                true
            }
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            params.marginEnd = dp(6)
            strip.addView(chip, params)
        }
    }

    private fun renderHomeMarks() {
        val row = binding.homeMarks
        row.removeAllViews()
        val marks = store.bookmarks().take(12)
        if (marks.isEmpty()) {
            row.addView(TextView(this).apply {
                setText(R.string.empty_marks)
                setTextColor(muted)
                textSize = 13f
            })
            return
        }
        marks.forEach { entry ->
            val chip = TextView(this)
            chip.text = entry.title.ifBlank { Urls.host(entry.url) }
            chip.setTextColor(ink)
            chip.textSize = 13f
            chip.maxLines = 1
            chip.setPadding(dp(14), dp(8), dp(14), dp(8))
            chip.setBackgroundResource(R.drawable.chip)
            chip.setOnClickListener { go(entry.url) }
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            params.marginEnd = dp(8)
            row.addView(chip, params)
        }
    }

    private fun showMenu() {
        showSheet(getString(R.string.app_name), tall = false) { body ->
            val tab = current()
            val starred = tab.url.startsWith("http") && store.isBookmarked(tab.url)
            addMenuRow(body, getString(if (starred) R.string.unbookmark else R.string.bookmark)) {
                if (!tab.atHome) toggleStar()
            }
            addMenuRow(body, getString(R.string.bookmarks)) { showBookmarks() }
            addMenuRow(body, getString(R.string.history)) { showHistory() }
            addMenuRow(body, getString(R.string.new_tab)) { newTab() }
            addMenuRow(body, getString(R.string.tabs)) { showTabs() }
            addMenuRow(body, getString(R.string.home)) { closeSheet(); showHome() }
            addMenuRow(body, getString(R.string.copy)) { copyUrl() }
            addMenuRow(body, getString(R.string.share)) { shareUrl() }
            addMenuRow(body, getString(if (desktopMode) R.string.use_mobile else R.string.use_desktop)) {
                toggleDesktop()
            }
            addMenuRow(body, getString(R.string.clear)) { confirmClear() }
        }
    }

    private fun showBookmarks() {
        showSheet(getString(R.string.bookmarks), tall = true) { body ->
            val items = store.bookmarks()
            if (items.isEmpty()) {
                addEmpty(body, R.string.empty_marks)
            } else {
                items.forEach { entry ->
                    addEntry(body, entry.title.ifBlank { Urls.host(entry.url) }, Urls.host(entry.url), {
                        go(entry.url)
                    }) {
                        store.removeBookmark(entry.url)
                        showBookmarks()
                        renderHomeMarks()
                        updateChrome()
                    }
                }
            }
        }
    }

    private fun showHistory() {
        showSheet(getString(R.string.history), tall = true) { body ->
            val items = store.history()
            if (items.isEmpty()) {
                addEmpty(body, R.string.empty_history)
            } else {
                items.forEach { entry ->
                    addEntry(body, entry.title.ifBlank { Urls.host(entry.url) }, Urls.host(entry.url), {
                        go(entry.url)
                    }) {
                        store.removeHistory(entry.url, entry.time)
                        showHistory()
                    }
                }
            }
        }
    }

    private fun showTabs() {
        showSheet(getString(R.string.tabs), tall = true) { body ->
            addMenuRow(body, getString(R.string.new_tab)) { newTab() }
            tabs.forEachIndexed { i, tab ->
                val title = if (tab.atHome || tab.title.isBlank()) getString(R.string.app_name) else tab.title
                addEntry(body, title, if (tab.url.startsWith("http")) Urls.host(tab.url) else "", {
                    index = i
                    closeSheet()
                    present()
                }) {
                    closeTab(i)
                    showTabs()
                }
            }
        }
    }

    private fun newTab() {
        if (tabs.size >= 12) {
            toast(R.string.tab_limit)
            return
        }
        tabs.add(Tab())
        index = tabs.lastIndex
        closeSheet()
        present()
    }

    private fun closeTab(i: Int) {
        if (tabs.size == 1) {
            val only = tabs[0]
            only.web?.let { web ->
                (web.parent as? ViewGroup)?.removeView(web)
                web.destroy()
            }
            only.web = null
            only.url = ""
            only.title = getString(R.string.app_name)
            only.atHome = true
            only.loading = false
            index = 0
            present()
            return
        }
        val removed = tabs.removeAt(i)
        removed.web?.let { web ->
            (web.parent as? ViewGroup)?.removeView(web)
            web.destroy()
        }
        if (index > i) index -= 1
        else if (index >= tabs.size) index = tabs.lastIndex
        present()
    }

    private fun toggleStar() {
        val tab = current()
        if (!tab.url.startsWith("http")) return
        val saved = store.toggleBookmark(tab.title, tab.url)
        toast(if (saved) R.string.saved else R.string.removed)
        updateChrome()
        renderHomeMarks()
    }

    private fun copyUrl() {
        val url = current().url
        if (!url.startsWith("http")) return
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("url", url))
        closeSheet()
        toast(R.string.copied)
    }

    private fun shareUrl() {
        val url = current().url
        if (!url.startsWith("http")) return
        closeSheet()
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
        }
        startActivity(Intent.createChooser(send, null))
    }

    private fun toggleDesktop() {
        desktopMode = !desktopMode
        tabs.forEach { tab ->
            tab.web?.let { web ->
                web.settings.userAgentString = if (desktopMode) DESKTOP_UA else mobileUa
            }
        }
        closeSheet()
        val tab = current()
        if (!tab.atHome && tab.url.startsWith("http")) tab.web?.loadUrl(tab.url)
        toast(if (desktopMode) R.string.use_desktop else R.string.use_mobile)
    }

    private fun confirmClear() {
        closeSheet()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.clear_title)
            .setMessage(R.string.clear_body)
            .setPositiveButton(R.string.clear_ok) { _, _ -> wipeData() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun wipeData() {
        store.clearHistory()
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
        tabs.forEach { tab ->
            tab.web?.apply {
                clearCache(true)
                clearHistory()
                clearFormData()
            }
        }
        toast(R.string.cleared)
    }

    private fun showSheet(title: String, tall: Boolean, fill: (LinearLayout) -> Unit) {
        binding.sheetTitle.text = title
        binding.sheetBody.removeAllViews()
        fill(binding.sheetBody)
        val params = binding.sheetScroll.layoutParams
        params.height = if (tall) (resources.displayMetrics.heightPixels * 0.58f).toInt()
        else ViewGroup.LayoutParams.WRAP_CONTENT
        binding.sheetScroll.layoutParams = params
        binding.scrim.isVisible = true
        binding.sheet.isVisible = true
    }

    private fun closeSheet() {
        binding.scrim.isVisible = false
        binding.sheet.isVisible = false
        binding.sheetBody.removeAllViews()
    }

    private fun addMenuRow(parent: LinearLayout, label: String, action: () -> Unit) {
        val row = TextView(this)
        row.text = label
        row.setTextColor(ink)
        row.textSize = 16f
        row.setPadding(dp(20), dp(14), dp(20), dp(14))
        row.setBackgroundResource(selectableBackground())
        row.setOnClickListener { action() }
        parent.addView(
            row,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
    }

    private fun addEntry(
        parent: LinearLayout,
        title: String,
        sub: String,
        onOpen: () -> Unit,
        onDelete: () -> Unit,
    ) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(20), dp(8), dp(8), dp(8))
        row.setBackgroundResource(selectableBackground())
        val text = LinearLayout(this)
        text.orientation = LinearLayout.VERTICAL
        text.addView(TextView(this).apply {
            this.text = title
            setTextColor(ink)
            textSize = 15f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        if (sub.isNotBlank()) {
            text.addView(TextView(this).apply {
                this.text = sub
                setTextColor(muted)
                textSize = 12f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
        }
        row.addView(
            text,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        row.setOnClickListener { onOpen() }
        val delete = ImageButton(this)
        delete.setImageResource(R.drawable.ic_close)
        delete.imageTintList = android.content.res.ColorStateList.valueOf(muted)
        delete.setBackgroundResource(R.drawable.icon_ripple)
        delete.contentDescription = getString(R.string.delete)
        delete.setOnClickListener { onDelete() }
        row.addView(delete, LinearLayout.LayoutParams(dp(44), dp(44)))
        parent.addView(
            row,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
    }

    private fun addEmpty(parent: LinearLayout, text: Int) {
        parent.addView(TextView(this).apply {
            setText(text)
            setTextColor(muted)
            textSize = 14f
            setPadding(dp(20), dp(18), dp(20), dp(18))
        })
    }

    private fun selectableBackground(): Int {
        val value = android.util.TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)
        return value.resourceId
    }

    private fun startDownload(url: String, agent: String, disposition: String, mime: String) {
        val request = DownloadReq(url, agent, disposition, mime)
        if (Build.VERSION.SDK_INT <= 28 &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            pendingDownload = request
            storagePerm.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        enqueue(request)
    }

    private fun enqueue(request: DownloadReq) {
        try {
            val name = URLUtil.guessFileName(request.url, request.disposition, request.mime)
            val download = DownloadManager.Request(Uri.parse(request.url))
                .setMimeType(request.mime)
                .addRequestHeader("User-Agent", request.agent)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                .setTitle(name)
            getSystemService(DownloadManager::class.java).enqueue(download)
            toast(R.string.download_started)
        } catch (_: Exception) {
            toast(R.string.download_failed)
        }
    }

    private fun hideIme() {
        val imm = getSystemService(InputMethodManager::class.java)
        imm.hideSoftInputFromWindow(binding.address.windowToken, 0)
    }

    private fun toast(text: Int) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun errorPage(): String = """
        <!DOCTYPE html>
        <html lang="ar" dir="rtl">
        <head>
        <meta charset="utf-8"/>
        <meta name="viewport" content="width=device-width, initial-scale=1"/>
        <style>
          html,body{margin:0;height:100%;background:#0E1418;color:#E7E1D6;font-family:sans-serif}
          body{display:flex;align-items:center;justify-content:center;text-align:center}
          p{color:#9AA39C}
          h1{font-weight:500;font-size:20px}
        </style>
        </head>
        <body><div><h1>تعذر فتح الصفحة</h1><p>تحقق من الاتصال ثم حدّث.</p></div></body>
        </html>
    """.trimIndent()

    private class Tab {
        var title: String = "سبيس"
        var url: String = ""
        var atHome: Boolean = true
        var loading: Boolean = false
        var web: WebView? = null
    }

    private data class DownloadReq(
        val url: String,
        val agent: String,
        val disposition: String,
        val mime: String,
    )

    companion object {
        private const val DESKTOP_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
    }
}
