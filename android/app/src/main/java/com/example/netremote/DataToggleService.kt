package com.example.netremote

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.text.Normalizer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Bascule les donnees mobiles en pilotant les parametres rapides.
 *
 * Seule voie sans root ni adb : l'utilisateur active le service une fois dans
 * les Reglages, et il survit aux redemarrages. En echange, l'ecran doit etre
 * allume, puisqu'on manipule reellement l'interface.
 *
 * Deux modes :
 *
 *  - APPRENTISSAGE : l'utilisateur montre une fois ou appuyer, on enregistre.
 *  - REJEU : on refait la sequence apprise, ou a defaut on cherche la tuile
 *    par mots-cles.
 *
 * L'apprentissage existe parce que les libelles et la disposition changent
 * selon le constructeur, la version et la langue : deviner revient a parier
 * sur un appareil qu'on n'a pas.
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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    // --- Apprentissage : capture du panneau ------------------------------

    /**
     * Ouvre les parametres rapides, releve tout ce qui s'y trouve, referme.
     *
     * On ne guette plus les clics de l'utilisateur : les tuiles n'emettent pas
     * d'evenement exploitable. On lit le panneau et on laisse l'utilisateur
     * designer la bonne entree, ce qui ne depend d'aucun evenement.
     */
    internal fun captureCandidates(onDone: (List<Step>) -> Unit) {
        handler.post { openQuickSettings() }

        handler.postDelayed({
            val clickable = mutableListOf<Step>()
            val others = mutableListOf<Step>()

            for (node in collectNodes()) {
                val label = labelOf(node)
                val id = node.viewIdResourceName.orEmpty()
                if (label.isBlank() && id.isBlank()) continue

                val bounds = Rect().also { node.getBoundsInScreen(it) }
                if (bounds.width() <= 0 || bounds.height() <= 0) continue

                val step = Step(id, label, bounds.centerX(), bounds.centerY())
                if (node.isClickable || node.parent?.isClickable == true) {
                    clickable += step
                } else {
                    others += step
                }
            }

            // Les elements cliquables d'abord : ce sont les candidats plausibles.
            val candidates = (clickable + others)
                .distinctBy { it.viewId + "|" + it.label }
                .take(MAX_CANDIDATES)

            performGlobalAction(GLOBAL_ACTION_BACK)
            handler.post { onDone(candidates) }
        }, STEP_MS * 3)
    }

    // --- Rejeu -----------------------------------------------------------

    internal fun openQuickSettings() {
        performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
        handler.postDelayed({ performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS) }, STEP_MS)
    }

    internal fun replayLearned(steps: List<Step>): ActionResult {
        val latch = CountDownLatch(1)
        var outcome = ActionResult(false, "délai dépassé")

        handler.post { openQuickSettings() }

        handler.postDelayed({
            val done = mutableListOf<String>()
            for ((index, step) in steps.withIndex()) {
                if (!playStep(step)) {
                    outcome = ActionResult(
                        false,
                        "étape ${index + 1} (${step.describe()}) irrejouable"
                    )
                    latch.countDown()
                    return@postDelayed
                }
                done += step.describe()
                Thread.sleep(700)
            }
            outcome = ActionResult(true, "séquence rejouée : " + done.joinToString(" → "))
            latch.countDown()
        }, STEP_MS * 2)

        awaitAndClose(latch)
        return outcome
    }

    /** Identifiant de ressource, puis libelle, puis coordonnees. */
    private fun playStep(step: Step): Boolean {
        val nodes = collectNodes()

        if (step.viewId.isNotBlank()) {
            nodes.firstOrNull { it.viewIdResourceName == step.viewId }
                ?.let { if (clickNode(it)) return true }
        }

        if (step.label.isNotBlank()) {
            val wanted = normalize(step.label)
            nodes.firstOrNull { normalize(labelOf(it)) == wanted }
                ?.let { if (clickNode(it)) return true }
        }

        return if (step.x > 0 && step.y > 0) tapAt(step.x, step.y) else false
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

    // --- Rejeu par mots-cles (quand rien n'a ete appris) ------------------

    internal fun searchByKeywords(): ActionResult {
        val latch = CountDownLatch(1)
        var outcome = ActionResult(false, "délai dépassé")

        handler.post { openQuickSettings() }
        handler.postDelayed({
            outcome = clickDataTile()
            latch.countDown()
        }, STEP_MS * 2)

        awaitAndClose(latch)
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

        // Depuis Android 12 la tuile s'appelle souvent « Internet » et ouvre une
        // boite de dialogue contenant le vrai interrupteur.
        findAndClick(nodes, INTERNET_KEYWORDS)?.let { opened ->
            Thread.sleep(900)
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

    private fun findAndClick(nodes: List<AccessibilityNodeInfo>, keywords: List<String>): String? {
        for (keyword in keywords) {
            for (node in nodes) {
                val label = normalize(labelOf(node))
                if (label.isEmpty() || !label.contains(keyword)) continue
                if (clickNode(node)) return labelOf(node)
            }
        }
        return null
    }

    // --- Outils ----------------------------------------------------------

    private fun awaitAndClose(latch: CountDownLatch) {
        try {
            latch.await(TIMEOUT_S, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
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

    private fun walk(node: AccessibilityNodeInfo?, into: MutableList<AccessibilityNodeInfo>, depth: Int) {
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
        private const val TIMEOUT_S = 15L
        private const val MAX_CANDIDATES = 60

        private val DATA_KEYWORDS = listOf(
            "donnees mobiles", "mobile data", "donnees cellulaires",
            "cellular data", "reseau mobile", "mobile network", "donnees"
        )
        private val INTERNET_KEYWORDS = listOf("internet", "reseau", "network")

        @Volatile
        private var instance: DataToggleService? = null

        fun isRunning(): Boolean = instance != null

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

        /** @return false si le service d'accessibilite n'est pas actif. */
        fun capture(onDone: (List<Step>) -> Unit): Boolean {
            val service = instance ?: return false
            service.captureCandidates(onDone)
            return true
        }

        // --- Bascule -----------------------------------------------------

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
            Thread.sleep(1200)
            return if (MobileData.isEnabled(context) == target) {
                ActionResult(
                    true,
                    (if (target) "données activées" else "données coupées") + " (" + result.detail + ")"
                )
            } else {
                ActionResult(false, "appui effectué (" + result.detail + ") mais l'état n'a pas changé")
            }
        }

        private fun normalize(raw: String): String {
            val withoutAccents = Normalizer.normalize(raw, Normalizer.Form.NFD)
                .replace(Regex("\\p{Mn}+"), "")
            return withoutAccents.lowercase().trim()
        }
    }
}
