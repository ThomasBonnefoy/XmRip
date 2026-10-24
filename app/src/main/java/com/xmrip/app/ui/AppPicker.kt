package com.xmrip.app.ui

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import com.xmrip.app.NotificationReader
import java.text.Normalizer

/**
 * Liste des applications du téléphone avec leur icône, une recherche, et les applications déjà choisies en tête.
 * Choix multiple (lecture des notifications) ou unique (application à ouvrir par un geste).
 */
object AppPicker {

    class App(val pkg: String, val label: String, val icon: Drawable?)

    /** Applications du lanceur avec leur icône ; à appeler hors du thread d'interface (lecture des icônes). */
    fun load(context: Context): List<App> {
        val pm = context.packageManager
        return NotificationReader.launchableApps(context).map { (pkg, label) ->
            App(pkg, label, runCatching { pm.getApplicationIcon(pkg) }.getOrNull())
        }
    }

    /**
     * Ouvre le choix. [multi] : cases à cocher et bouton Valider, [onDone] reçoit l'ensemble choisi ; sinon un appui
     * choisit et ferme, [onDone] reçoit un seul paquet.
     */
    fun show(activity: Activity, ui: Ui, title: String, apps: List<App>, selected: Set<String>, multi: Boolean, onDone: (Set<String>) -> Unit) {
        val chosen = selected.toMutableSet()
        // Les applications déjà choisies d'abord, puis l'ordre alphabétique.
        val sorted = apps.sortedWith(compareBy<App>({ it.pkg !in selected }, { it.label.lowercase() }))
        var shown = sorted

        val root = ui.column().apply { setPadding(ui.dp(20), ui.dp(4), ui.dp(20), 0) }
        val search = EditText(activity).apply {
            hint = "Rechercher une application"
            setSingleLine()
            textSize = 15f
            setTextColor(Colors.TEXT)
            setHintTextColor(Colors.TEXT_FAINT)
            background = ui.rounded(Colors.SURFACE_TOP, 14)
            setPadding(ui.dp(14), ui.dp(11), ui.dp(14), ui.dp(11))
        }
        root.addView(search, ui.matchLp(bottom = 8))
        val count = ui.dim("", 12.5f)
        if (multi) root.addView(count, ui.matchLp(bottom = 6))
        fun updateCount() {
            count.text = if (chosen.isEmpty()) "Aucune application choisie" else "${chosen.size} application(s) choisie(s)"
        }
        updateCount()

        var dialog: android.app.AlertDialog? = null
        val adapter = object : BaseAdapter() {
            override fun getCount() = shown.size
            override fun getItem(position: Int) = shown[position]
            override fun getItemId(position: Int) = position.toLong()

            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val row = (convertView as? LinearLayout) ?: buildRow(ui)
                val app = shown[position]
                (row.getChildAt(0) as ImageView).setImageDrawable(app.icon)
                (row.getChildAt(1) as android.widget.TextView).text = app.label
                val mark = row.getChildAt(2) as ImageView
                val on = app.pkg in chosen
                mark.background = if (on) ui.gradient(Colors.ACCENT, Colors.ACCENT_2, if (multi) 7 else 11)
                else ui.rounded(0, if (multi) 7 else 11, Colors.TEXT_FAINT, 2)
                mark.setImageResource(if (on) com.xmrip.app.R.drawable.ic_ui_check else 0)
                return row
            }
        }
        val list = ListView(activity).apply {
            divider = null
            selector = ui.pressable(ui.rounded(0, 14), 14)
            this.adapter = adapter
            setOnItemClickListener { _, _, position, _ ->
                val pkg = shown[position].pkg
                if (multi) {
                    if (!chosen.add(pkg)) chosen.remove(pkg)
                    updateCount()
                    adapter.notifyDataSetChanged()
                } else {
                    dialog?.dismiss()
                    onDone(setOf(pkg))
                }
            }
        }
        val height = (activity.resources.displayMetrics.heightPixels * 0.55f).toInt()
        root.addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, height))

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = fold(s?.toString().orEmpty().trim())
                shown = if (q.isEmpty()) sorted else sorted.filter { fold(it.label).contains(q) || it.pkg.contains(q) }
                adapter.notifyDataSetChanged()
            }
        })

        val builder = ui.dialog().setTitle(title).setView(root).setNegativeButton("Annuler", null)
        if (multi) builder.setPositiveButton("Valider") { _, _ -> onDone(chosen) }
        dialog = builder.show()
    }

    /** Minuscules sans accents, pour que « reseaux » trouve « Réseaux ». */
    private fun fold(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

    private fun buildRow(ui: Ui): LinearLayout = ui.row().apply {
        setPadding(ui.dp(6), ui.dp(9), ui.dp(6), ui.dp(9))
        val icon = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        addView(icon, ui.lp(ui.dp(38), ui.dp(38)))
        val label = ui.text("", 15f, Colors.TEXT, ui.medium).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        addView(label, ui.lp(0, weight = 1f).apply {
            leftMargin = ui.dp(14)
            rightMargin = ui.dp(10)
        })
        val mark = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER
            imageTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt())
        }
        addView(mark, ui.lp(ui.dp(22), ui.dp(22)))
        gravity = Gravity.CENTER_VERTICAL
    }
}
