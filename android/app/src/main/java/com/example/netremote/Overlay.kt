package com.example.netremote

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Le panneau que NetRemote affiche par-dessus l'ecran pour montrer ce qu'il voit.
 *
 * Pourquoi une fenetre du service et pas une boite de dialogue de l'app :
 * pendant l'apprentissage, l'ecran affiche les parametres rapides ou les
 * Reglages, pas NetRemote. Une app en arriere-plan ne peut rien afficher. Un
 * service d'accessibilite, si — c'est le seul type de fenetre qui ne demande
 * ni SYSTEM_ALERT_WINDOW ni autorisation a cocher.
 *
 * Le panneau occupe le bas de l'ecran : les tuiles et les interrupteurs se
 * trouvent en haut, on ne masque pas ce dont on parle.
 */
class Overlay(private val service: AccessibilityService) {

    /** Une ligne cliquable du panneau. */
    data class Entry(val text: String, val subtitle: String = "", val onClick: () -> Unit)

    private val windows = service.getSystemService(WindowManager::class.java)
    private var view: View? = null

    val isShown: Boolean get() = view != null

    /** A appeler sur le thread principal. */
    fun show(title: String, subtitle: String, entries: List<Entry>) {
        hide()

        val root = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BACKGROUND)
            setPadding(pad(16), pad(12), pad(16), pad(12))
        }

        root.addView(label(title, 17f, TITLE, bold = true))
        if (subtitle.isNotBlank()) root.addView(label(subtitle, 13f, DIM))

        val list = LinearLayout(service).apply { orientation = LinearLayout.VERTICAL }
        entries.forEach { list.addView(row(it)) }

        val scroll = ScrollView(service).apply {
            addView(list)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        root.addView(scroll)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            height(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // Non focalisable : le clavier et la touche Retour continuent d'aller
            // a l'ecran du dessous, on ne detourne que nos propres appuis.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM }

        try {
            windows.addView(root, params)
            view = root
        } catch (e: Exception) {
            Log.w(TAG, "Panneau impossible a afficher", e)
        }
    }

    fun hide() {
        val current = view ?: return
        view = null
        try {
            windows.removeView(current)
        } catch (e: Exception) {
            Log.w(TAG, "Panneau deja retire", e)
        }
    }

    // --- Construction des vues -------------------------------------------

    private fun row(entry: Entry): View {
        val holder = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ROW)
            setPadding(pad(12), pad(10), pad(12), pad(10))
            isClickable = true
            setOnClickListener { entry.onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, pad(6), 0, 0) }
        }
        holder.addView(label(entry.text, 15f, TITLE))
        if (entry.subtitle.isNotBlank()) holder.addView(label(entry.subtitle, 12f, DIM))
        return holder
    }

    private fun label(text: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(service).apply {
            this.text = text
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

    private fun pad(dp: Int): Int =
        (dp * service.resources.displayMetrics.density).toInt()

    /** Un peu plus de la moitie de l'ecran : assez pour lire, sans tout cacher. */
    private fun height(): Int =
        (service.resources.displayMetrics.heightPixels * 0.58f).toInt()

    private companion object {
        const val TAG = "Overlay"
        val BACKGROUND = Color.parseColor("#EE101418")
        val ROW = Color.parseColor("#FF1D2733")
        val TITLE = Color.parseColor("#FFECF2F8")
        val DIM = Color.parseColor("#FF9DB0C4")
    }
}
