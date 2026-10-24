package com.xmrip.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import com.xmrip.app.HeadsetController.Part
import com.xmrip.app.protocol.MdrSession
import com.xmrip.app.ui.Appearance
import com.xmrip.app.ui.Colors
import com.xmrip.app.ui.DevicesPage
import com.xmrip.app.ui.PagerFrame
import com.xmrip.app.ui.GlowDrawable
import com.xmrip.app.ui.HomePage
import com.xmrip.app.ui.Page
import com.xmrip.app.ui.SettingsPage
import com.xmrip.app.ui.SoundPage
import com.xmrip.app.ui.Ui
import com.xmrip.app.ui.withAlpha

/**
 * Écran principal : en-tête, quatre pages (Accueil, Son, Appareils, Réglages) et barre d'onglets flottante. Toute la
 * logique du casque est dans [HeadsetController] ; cette activité ne fait que la mise en page, les autorisations et
 * les raccourcis.
 */
class MainActivity : Activity() {

    companion object {
        private const val REQ_BLUETOOTH_CONNECT = 1001
        private const val ACTION_SET_NC_ASM = "com.xmrip.app.action.SET_NC_ASM"
        private const val EXTRA_NC_ASM_MODE = "com.xmrip.app.extra.NC_ASM_MODE"
        private const val STATE_TAB = "tab"
        private const val SCRIM_DP = 118
    }

    private lateinit var ui: Ui
    private lateinit var ctrl: HeadsetController
    private val glow = GlowDrawable()
    private val pager by lazy { PagerFrame(this) }
    private val pages = ArrayList<Page>()
    private val scrolls = ArrayList<ScrollView>()
    private val tabViews = ArrayList<Triple<LinearLayout, ImageView, TextView>>()
    private var currentTab = 0
    private lateinit var busy: ProgressBar
    private lateinit var busyText: TextView
    private lateinit var snack: TextView
    private lateinit var nav: LinearLayout
    private val main = Handler(Looper.getMainLooper())
    private val hideSnack = Runnable { snack.animate().alpha(0f).translationY(ui.dpF(12f)).setDuration(200).withEndAction { snack.visibility = View.GONE }.start() }
    private val ticker = object : Runnable {
        override fun run() {
            pages.forEach { it.tick() }
            main.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Palette et thème (fenêtre, dialogues) choisis avant toute vue : clair, sombre ou comme le téléphone.
        val light = Appearance.isLight(this)
        Colors.apply(light)
        setTheme(if (light) R.style.Theme_XmRip_Light else R.style.Theme_XmRip_Dark)
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        ctrl = HeadsetController(this)
        ctrl.onMessage = { text, error -> showMessage(text, error) }
        buildUi()
        ctrl.observe { part ->
            pages.forEach { it.changed(part) }
            if (part == Part.BUSY) renderBusy()
            if (part == Part.CONNECTION && ctrl.connected) pages[currentTab].shown()
        }
        selectTab(savedInstanceState?.getInt(STATE_TAB) ?: 0, animate = false)
        Shortcuts.publish(this)
        ctrl.pendingMode = parseMode(intent)
        if (hasPermission()) ctrl.discover() else requestBluetoothPermission()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_TAB, currentTab)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val mode = parseMode(intent) ?: return
        if (ctrl.connected) ctrl.setMode(mode) else ctrl.pendingMode = mode
    }

    override fun onResume() {
        super.onResume()
        ctrl.start()
        main.post(ticker)
        // Relance les surveillances si elles devaient tourner (elles s'arrêtent après une mise à jour ou un arrêt forcé).
        if (PcQuiet.watchNeeded(this)) runCatching { PcWatcher.start(this) }
        if (GestureActions.anyEnabled(this)) runCatching { GestureWatcher.start(this) }
        pages[currentTab].shown()
        if (ctrl.connected) ctrl.refreshStatus(silent = true)
    }

    override fun onPause() {
        super.onPause()
        ctrl.stop()
        main.removeCallbacks(ticker)
    }

    override fun onDestroy() {
        super.onDestroy()
        ctrl.release()
    }

    private val shownOnce = BooleanArray(4)
    private var backCallback: OnBackInvokedCallback? = null

    /**
     * Retour sur un autre onglet que l'Accueil = revenir à l'Accueil. Depuis Android 13 (et toujours depuis Android 16)
     * le système n'appelle plus [onBackPressed] : il faut un rappel enregistré, présent seulement hors de l'Accueil.
     */
    private fun updateBackHandling() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val dispatcher = onBackInvokedDispatcher
        val current = backCallback
        if (currentTab != 0 && current == null) {
            val cb = OnBackInvokedCallback { selectTab(0) }
            dispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb)
            backCallback = cb
        } else if (currentTab == 0 && current != null) {
            dispatcher.unregisterOnBackInvokedCallback(current)
            backCallback = null
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (currentTab != 0) selectTab(0) else @Suppress("DEPRECATION") super.onBackPressed()
    }

    private fun parseMode(intent: Intent?): Int? {
        if (intent?.action != ACTION_SET_NC_ASM) return null
        return when (intent.getStringExtra(EXTRA_NC_ASM_MODE)) {
            "noise_cancelling" -> MdrSession.MODE_NC
            "ambient" -> MdrSession.MODE_AMBIENT
            "off" -> MdrSession.MODE_OFF
            else -> null
        }
    }

    // ---------------------------------------------------------------------------------------
    // Mise en page
    // ---------------------------------------------------------------------------------------

    @Suppress("DEPRECATION")
    private fun buildUi() {
        // Dessin sous les barres système (bord à bord) sur toutes les versions ; les marges suivent les encarts.
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        val root = FrameLayout(this).apply { background = glow }

        val column = ui.column()
        column.addView(buildHeader())
        val stack = pager
        stack.onPageSelected = { selectTab(it, fromSwipe = true) }
        pages.add(HomePage(this, ui, ctrl))
        pages.add(SoundPage(this, ui, ctrl))
        pages.add(DevicesPage(this, ui, ctrl))
        pages.add(SettingsPage(this, ui, ctrl))
        for (page in pages) {
            val scroll = ScrollView(this).apply {
                isVerticalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                clipToPadding = false
                setPadding(ui.dp(16), ui.dp(6), ui.dp(16), ui.dp(120))
                addView(page.view)
                visibility = View.GONE
            }
            scrolls.add(scroll)
            stack.addView(scroll, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        column.addView(stack, ui.lp(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(column, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        // Fondu sous la barre d'onglets : le contenu qui défile dessous s'efface au lieu d'apparaître à travers.
        val scrim = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(Colors.BG, withAlpha(Colors.BG, 0xE6), withAlpha(Colors.BG, 0)),
            )
        }
        root.addView(scrim, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, ui.dp(SCRIM_DP), Gravity.BOTTOM))

        nav = buildNav()
        root.addView(nav, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, ui.dp(68), Gravity.BOTTOM).apply {
            leftMargin = ui.dp(16)
            rightMargin = ui.dp(16)
            bottomMargin = ui.dp(14)
        })

        snack = ui.text("", 13.5f, Colors.TEXT, ui.medium).apply {
            setPadding(ui.dp(16), ui.dp(13), ui.dp(16), ui.dp(13))
            visibility = View.GONE
            elevation = ui.dpF(8f)
            setOnClickListener { main.removeCallbacks(hideSnack); hideSnack.run() }
        }
        root.addView(snack, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
            leftMargin = ui.dp(20)
            rightMargin = ui.dp(20)
            bottomMargin = ui.dp(96)
        })

        root.setOnApplyWindowInsetsListener { _, insets ->
            val top: Int
            val bottom: Int
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                top = bars.top
                bottom = bars.bottom
                column.setPadding(bars.left, 0, bars.right, 0)
            } else {
                top = insets.systemWindowInsetTop
                bottom = insets.systemWindowInsetBottom
            }
            column.setPadding(column.paddingLeft, top, column.paddingRight, 0)
            (nav.layoutParams as FrameLayout.LayoutParams).bottomMargin = ui.dp(14) + bottom
            (snack.layoutParams as FrameLayout.LayoutParams).bottomMargin = ui.dp(96) + bottom
            scrim.layoutParams.height = ui.dp(SCRIM_DP) + bottom
            scrim.requestLayout()
            nav.requestLayout()
            scrolls.forEach { it.setPadding(it.paddingLeft, it.paddingTop, it.paddingRight, ui.dp(120) + bottom) }
            insets
        }
        setContentView(root)
        applyBarAppearance()
    }

    /** Icônes de la barre d'état et de navigation : sombres sur le thème clair, claires sur le sombre. */
    @Suppress("DEPRECATION")
    private fun applyBarAppearance() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val mask = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (Colors.light) mask else 0, mask)
        } else if (Colors.light) {
            var flags = window.decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) flags = flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            window.decorView.systemUiVisibility = flags
        }
    }

    /** Vues qui gardent leurs gestes horizontaux (le glissement entre pages ne démarre pas dessus). */
    fun keepHorizontalGestures(view: View) {
        pager.noSwipe.add(view)
    }

    private fun buildHeader(): View {
        val header = ui.row().apply { setPadding(ui.dp(20), ui.dp(12), ui.dp(12), ui.dp(8)) }
        val brand = ui.text("XMRIP", 13f, Colors.TEXT, ui.bold).apply { letterSpacing = 0.32f }
        header.addView(brand)
        busy = ProgressBar(this, null, android.R.attr.progressBarStyleSmall).apply {
            indeterminateTintList = ColorStateList.valueOf(Colors.ACCENT)
            visibility = View.INVISIBLE
        }
        busyText = ui.text("", 12f, Colors.TEXT_DIM).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.END
        }
        header.addView(busyText, ui.lp(0, weight = 1f).apply {
            leftMargin = ui.dp(12)
            rightMargin = ui.dp(8)
        })
        header.addView(busy, ui.lp(ui.dp(18), ui.dp(18)).apply { rightMargin = ui.dp(10) })
        val refresh = ui.iconButton(R.drawable.ic_ui_refresh, "Actualiser", 42, Colors.SURFACE_HI).apply {
            setOnClickListener { ctrl.refreshAll() }
        }
        header.addView(refresh)
        return header
    }

    private fun buildNav(): LinearLayout {
        val bar = ui.row().apply {
            background = ui.rounded(Colors.SURFACE_HI, 26, Colors.STROKE)
            elevation = ui.dpF(12f)
            setPadding(ui.dp(6), ui.dp(6), ui.dp(6), ui.dp(6))
        }
        listOf(
            "Accueil" to R.drawable.ic_ui_home,
            "Son" to R.drawable.ic_ui_wave,
            "Appareils" to R.drawable.ic_ui_phone,
            "Réglages" to R.drawable.ic_ui_tune,
        ).forEachIndexed { index, (label, icon) ->
            val item = ui.column().apply {
                gravity = Gravity.CENTER
                isClickable = true
                setOnClickListener { selectTab(index) }
            }
            val iv = ui.icon(icon, Colors.TEXT_FAINT, 22)
            val tv = ui.text(label, 11f, Colors.TEXT_FAINT, ui.medium).apply { gravity = Gravity.CENTER }
            item.addView(iv)
            item.addView(tv, ui.matchLp(top = 4))
            tabViews.add(Triple(item, iv, tv))
            bar.addView(item, ui.lp(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        }
        return bar
    }

    /** Affiche la page [index] ; [fromSwipe] = la page est déjà en place (glissement du doigt), on ne fait que suivre. */
    private fun selectTab(index: Int, animate: Boolean = true, fromSwipe: Boolean = false) {
        // Toucher l'onglet déjà affiché remonte en haut de la page.
        if (!fromSwipe && animate && index == currentTab && pager.current == index) {
            scrolls[index].smoothScrollTo(0, 0)
            return
        }
        currentTab = index
        updateBackHandling()
        if (!fromSwipe) pager.showPage(index)
        val s = scrolls[index]
        if (!shownOnce[index]) {
            shownOnce[index] = true
            s.scrollTo(0, 0)
        }
        if (animate && !fromSwipe) {
            s.alpha = 0f
            s.translationY = ui.dpF(14f)
            s.animate().alpha(1f).translationY(0f).setDuration(200).start()
        }
        tabViews.forEachIndexed { i, (item, icon, text) ->
            val on = i == index
            val color = if (!on) Colors.TEXT_FAINT else if (Colors.light) Colors.ACCENT else Color.WHITE
            icon.imageTintList = ColorStateList.valueOf(color)
            text.setTextColor(color)
            val (a1, a2) = if (Colors.light) 0x24 to 0x18 else 0x55 to 0x40
            item.background = if (on) {
                GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(withAlpha(Colors.ACCENT, a1), withAlpha(Colors.ACCENT_2, a2))).apply {
                    cornerRadius = ui.dpF(20f)
                }
            } else {
                null
            }
        }
        pages[index].shown()
    }

    private fun renderBusy() {
        val label = ctrl.busy
        busy.visibility = if (label != null) View.VISIBLE else View.INVISIBLE
        busyText.text = label ?: ""
    }

    /** Fond de l'écran : la lueur prend la couleur du mode de bruit. */
    fun setGlow(color: Int) = glow.setGlow(color)

    /** Message court en bas de l'écran ; [error] le colore en rouge et le garde plus longtemps. */
    fun showMessage(text: String, error: Boolean) {
        main.removeCallbacks(hideSnack)
        snack.text = text
        snack.setTextColor(if (error) Colors.DANGER else Colors.TEXT)
        snack.background = ui.rounded(if (error) Colors.ERROR_BG else Colors.SURFACE_TOP, 18, if (error) withAlpha(Colors.DANGER, 0x55) else Colors.STROKE)
        if (snack.visibility != View.VISIBLE) {
            snack.alpha = 0f
            snack.translationY = ui.dpF(12f)
            snack.visibility = View.VISIBLE
        }
        snack.animate().alpha(1f).translationY(0f).setDuration(200).start()
        main.postDelayed(hideSnack, if (error) 6_000 else 3_000)
    }

    // ---------------------------------------------------------------------------------------
    // Autorisations
    // ---------------------------------------------------------------------------------------

    private fun hasPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    fun requestBluetoothPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQ_BLUETOOTH_CONNECT)
        }
    }

    fun openBluetoothSettings() {
        runCatching { startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)) }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_BLUETOOTH_CONNECT) return
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) ctrl.discover() else ctrl.permissionMissing()
    }
}
