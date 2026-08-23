package com.example.netremote

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.text.Normalizer

/**
 * Bascule les donnees mobiles en rejouant un parcours appris.
 *
 * Seule voie sans root ni adb : l'utilisateur active le service une fois dans
 * les Reglages, et il survit aux redemarrages. En echange, l'ecran doit etre
 * allume, puisqu'on manipule reellement l'interface.
 *
 * L'apprentissage enregistre une SUITE d'appuis, pas un seul : atteindre les
 * donnees mobiles demande souvent de traverser plusieurs ecrans
 * (Parametres -> Reseau -> Donnees -> interrupteur) quand la tuile des
 * parametres rapides ne repond pas.
 */
class DataToggleService : AccessibilityService() {

    internal val handler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "Service d'accessibilité connecté")
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onInterrupt() = Unit

    // --- Apprentissage ----------------------------------------------------

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!recording || event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_VIEW_CLICKED) return

        val pkg = event.packageName?.toString().orEmpty()

        // Les appuis dans notre propre app sont les boutons Demarrer et Arreter :
        // les enregistrer polluerait la sequence.
        if (pkg == packageName) return

        val node = event.source ?: return
        val bounds = Rect().also { node.getBoundsInScreen(it) }

        val step = Step(
            viewId = node.viewIdResourceName.orEmpty(),
            label = (node.text?.toString() ?: node.contentDescription?.toString()).orEmpty(),
            x = bounds.centerX(),
            y = bounds.centerY(),
            pkg = pkg
        )

        if (step.viewId.isBlank() && step.label.isBlank() && step.x <= 0) return

        recorded += step
        Log.i(TAG, "Appui ${recorded.size} enregistré : ${step.describe()} [$pkg]")
    }

    // --- Rejeu ------------------------------------------------------------

    internal fun openQuickSettings() {
        performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
        handler.postDelayed({ performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS) }, STEP_MS)
    }

    /**
     * Tourne sur le thread appelant (une requete HTTP), jamais sur le thread
     * principal : les attentes entre etapes le bloqueraient plusieurs secondes,
     * assez pour declencher un ANR.
     */
    internal fun replayLearned(steps: List<Step>): ActionResult {
        if (steps.isEmpty()) return ActionResult(false, "aucune séquence apprise")

        if (!bringUp(steps.first().pkg)) {
            return ActionResult(false, "impossible d'ouvrir « ${steps.first().pkg} »")
        }
        pause(START_MS)

        val done = mutableListOf<String>()
        for ((index, step) in steps.withIndex()) {
            if (!playStep(step)) {
                closePanel()
                return ActionResult(
                    false,
                    "étape ${index + 1}/${steps.size} (${step.describe()}) introuvable" +
                        if (done.isEmpty()) "" else ", après : " + done.joinToString(" → ")
                )
            }
            done += step.describe()
            // Ouvrir un ecran de Reglages prend nettement plus qu'un appui sur
            // une tuile : on laisse le temps a la fenetre suivante d'arriver.
            pause(BETWEEN_MS)
        }

        closePanel()
        return ActionResult(true, "parcours rejoué : " + done.joinToString(" → "))
    }

    /**
     * Se replacer au point de depart du parcours.
     *
     * Sans ca, un parcours qui commence dans les Reglages echouerait des la
     * premiere etape si le telephone affiche autre chose.
     */
    private fun bringUp(pkg: String): Boolean {
        if (pkg.isBlank()) return true

        if (pkg.contains("systemui", ignoreCase = true)) {
            handler.post { openQuickSettings() }
            return true
        }

        return try {
            val intent = packageManager.getLaunchIntentForPackage(pkg)
                ?: Intent(Settings.ACTION_SETTINGS).takeIf { pkg.contains("settings") }
                ?: return false
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            startActivity(intent)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Ouverture de $pkg impossible", e)
            false
        }
    }

    /**
     * On localise l'element, puis on y envoie un VRAI toucher plutot qu'un
     * ACTION_CLICK.
     *
     * La distinction est capitale : sur les tuiles des parametres rapides,
     * l'action d'accessibilite est souvent cablee sur « ouvrir les reglages
     * detailles », alors qu'un appui reel bascule. Observe sur l'appareil.
     *
     * On reessaie pendant quelques secondes : l'ecran precedent peut encore
     * etre en train de ceder la place.
     */
    private fun playStep(step: Step): Boolean {
        val deadline = System.currentTimeMillis() + LOCATE_MS

        while (System.currentTimeMillis() < deadline) {
            val node = locate(step, collectNodes())
            if (node != null) {
                val bounds = Rect().also { node.getBoundsInScreen(it) }
                if (bounds.width() > 0 && bounds.height() > 0 &&
                    tapAt(bounds.centerX(), bounds.centerY())
                ) {
                    return true
                }
                // Dernier recours : peut ouvrir les reglages au lieu de basculer.
                if (clickNode(node)) return true
            }
            pause(300)
        }

        // L'element reste introuvable : on tente la position d'origine.
        return step.x > 0 && step.y > 0 && tapAt(step.x, step.y)
    }

    private fun locate(step: Step, nodes: List<AccessibilityNodeInfo>): AccessibilityNodeInfo? {
        if (step.viewId.isNotBlank()) {
            nodes.firstOrNull { it.viewIdResourceName == step.viewId }?.let { return it }
        }
        if (step.label.isNotBlank()) {
            val wanted = normalize(step.label)
            nodes.firstOrNull { normalize(labelOf(it)) == wanted }?.let { return it }
        }
        return null
    }

    private fun tapAt(x: Int, y: Int): Boolean = try {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        dispatchGesture(gesture, null, null)
    } catch (e: Exception) {
        Log.w(TAG, "Geste impossible", e)
        false
    }

    // --- Rejeu par mots-cles (quand rien n'a ete appris) -------------------

    internal fun searchByKeywords(): ActionResult {
        handler.post { openQuickSettings() }
        pause(START_MS)

        val outcome = clickDataTile()
        closePanel()
        return outcome
    }

    private fun clickDataTile(): ActionResult {
        val seen = mutableListOf<String>()
        val nodes = collectNodes()
        if (nodes.isEmpty()) {
            return ActionResult(false, "panneau illisible (arbre d'accessibilité vide)")
        }
        nodes.forEach { labelOf(it).takeIf { l -> l.isNotBlank() }?.let { l -> seen += l } }

        findAndClick(nodes, DATA_KEYWORDS)?.let {
            return ActionResult(true, "tuile « $it »")
        }

        findAndClick(nodes, INTERNET_KEYWORDS)?.let { opened ->
            pause(900)
            val inner = collectNodes()
            inner.forEach { labelOf(it).takeIf { l -> l.isNotBlank() }?.let { l -> seen += l } }
            findAndClick(inner, DATA_KEYWORDS)?.let {
                return ActionResult(true, "« $opened » puis « $it »")
            }
            return ActionResult(
                false,
                "« $opened » ouvert, aucun interrupteur de données dedans. Vu : " + summary(seen)
            )
        }

        return ActionResult(false, "tuile introuvable. Vu : " + summary(seen))
    }

    /** Meme principe que playStep : vrai toucher d'abord, ACTION_CLICK ensuite. */
    private fun findAndClick(nodes: List<AccessibilityNodeInfo>, keywords: List<String>): String? {
        for (keyword in keywords) {
            for (node in nodes) {
                val label = normalize(labelOf(node))
                if (label.isEmpty() || !label.contains(keyword)) continue

                val bounds = Rect().also { node.getBoundsInScreen(it) }
                if (bounds.width() > 0 && bounds.height() > 0 &&
                    tapAt(bounds.centerX(), bounds.centerY())
                ) {
                    return labelOf(node)
                }
                if (clickNode(node)) return labelOf(node)
            }
        }
        return null
    }

    // --- Outils -----------------------------------------------------------

    private fun pause(millis: Long) {
        try {
            Thread.sleep(millis)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun closePanel() {
        handler.postDelayed({ performGlobalAction(GLOBAL_ACTION_BACK) }, 400L)
    }

    /** Le libelle porte souvent sur un enfant non cliquable : on remonte. */
    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < 6) {
            if (current.isClickable &&
                current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            ) {
                return true
            }
            current = current.parent
            depth++
        }
        return false
    }

    private fun collectNodes(): List<AccessibilityNodeInfo> {
        val roots = mutableListOf<AccessibilityNodeInfo>()
        rootInActiveWindow?.let { roots += it }
        try {
            for (window in windows) window.root?.let { roots += it }
        } catch (e: Exception) {
            // Certaines surcouches refusent l'enumeration des fenetres.
        }

        val out = mutableListOf<AccessibilityNodeInfo>()
        for (root in roots) walk(root, out, 0)
        return out
    }

    private fun walk(
        node: AccessibilityNodeInfo?,
        into: MutableList<AccessibilityNodeInfo>,
        depth: Int
    ) {
        if (node == null || depth > 25 || into.size > 800) return
        into += node
        for (i in 0 until node.childCount) walk(node.getChild(i), into, depth + 1)
    }

    private fun labelOf(node: AccessibilityNodeInfo): String =
        node.text?.toString()?.takeIf { it.isNotBlank() }
            ?: node.contentDescription?.toString().orEmpty()

    private fun summary(seen: List<String>): String =
        seen.distinct().take(12).joinToString(", ").take(220)

    companion object {

        private const val TAG = "DataToggleService"
        private const val STEP_MS = 700L
        private const val START_MS = 1800L
        private const val BETWEEN_MS = 1000L
        private const val LOCATE_MS = 6000L

        private val DATA_KEYWORDS = listOf(
            "donnees mobiles", "mobile data", "donnees cellulaires",
            "cellular data", "reseau mobile", "mobile network", "donnees"
        )
        private val INTERNET_KEYWORDS = listOf("internet", "reseau", "network")

        @Volatile
        private var instance: DataToggleService? = null

        @Volatile
        private var recording = false
        private val recorded = mutableListOf<Step>()

        fun isRunning(): Boolean = instance != null

        fun isRecording(): Boolean = recording

        fun recordedCount(): Int = recorded.size

        fun isEnabledInSettings(context: Context): Boolean = try {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()
            enabled.contains(context.packageName + "/" + DataToggleService::class.java.name) ||
                enabled.contains(context.packageName + "/.DataToggleService")
        } catch (e: Exception) {
            false
        }

        /**
         * Enregistre tous les appuis jusqu'a l'arret, dans n'importe quelle app.
         *
         * @return false si le service d'accessibilite n'est pas actif.
         */
        fun startRecording(): Boolean {
            if (instance == null) return false
            recorded.clear()
            recording = true
            Log.i(TAG, "Enregistrement démarré")
            return true
        }

        /** @return la suite d'appuis captes, enregistree si elle n'est pas vide. */
        fun stopRecording(context: Context): List<Step> {
            recording = false
            val steps = recorded.toList()
            if (steps.isNotEmpty()) Recipe.save(context, steps)
            Log.i(TAG, "Enregistrement arrêté : ${steps.size} appui(s)")
            return steps
        }

        /**
         * @param target etat voulu. Si les donnees y sont deja, on ne touche a
         *   rien : un appui de trop les remettrait dans l'etat inverse.
         */
        fun toggleTo(context: Context, target: Boolean): ActionResult {
            val service = instance
                ?: return ActionResult(false, "service d'accessibilité non actif sur l'appareil cible")

            if (MobileData.isEnabled(context) == target) {
                return ActionResult(true, if (target) "déjà activées" else "déjà coupées")
            }

            val learned = Recipe.load(context)
            val result = if (learned.isNotEmpty()) {
                service.replayLearned(learned)
            } else {
                service.searchByKeywords()
            }
            if (!result.ok) return result

            // On verifie l'effet reel plutot que de croire l'appui sur parole.
            service.pause(1500)
            return if (MobileData.isEnabled(context) == target) {
                ActionResult(
                    true,
                    (if (target) "données activées" else "données coupées") + " (" + result.detail + ")"
                )
            } else {
                ActionResult(false, "parcours exécuté (" + result.detail + ") mais l'état n'a pas changé")
            }
        }

        private fun normalize(raw: String): String {
            val withoutAccents = Normalizer.normalize(raw, Normalizer.Form.NFD)
                .replace(Regex("\\p{Mn}+"), "")
            return withoutAccents.lowercase().trim()
        }
    }
}
