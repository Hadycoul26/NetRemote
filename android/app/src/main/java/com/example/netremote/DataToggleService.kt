package com.example.netremote

import android.accessibilityservice.AccessibilityService
import android.content.Context
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
 * Bascule les donnees mobiles en pilotant les paramETres rapides.
 *
 * C'est la seule voie qui ne demande ni root ni adb : l'utilisateur active le
 * service une fois dans les Reglages, et il survit aux redemarrages. En
 * echange, il faut que l'ecran soit allume, puisqu'on manipule reellement
 * l'interface.
 *
 * Rien n'est devine sur la disposition du systeme : on ouvre le panneau, on
 * lit l'arbre d'accessibilite, et on journalise tous les libelles rencontres.
 * Si la tuile n'est pas trouvee, le journal dit ce qu'il y avait a la place.
 */
class DataToggleService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "Service d'accessibilité connecté")
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    // --- Sequence de bascule ---------------------------------------------

    /**
     * Bloque l'appelant le temps de la manipulation. A n'appeler que depuis un
     * thread de fond : le travail lui-meme se fait sur le thread principal.
     */
    private fun runSequence(target: Boolean): ActionResult {
        val seen = mutableListOf<String>()
        val latch = CountDownLatch(1)
        var outcome = ActionResult(false, "délai dépassé")

        handler.post {
            performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
        }

        // Deux ouvertures : la premiere deroule le volet, la seconde le deploie
        // completement, ou toutes les tuiles ne sont pas visibles autrement.
        handler.postDelayed({
            performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
        }, STEP_MS)

        handler.postDelayed({
            outcome = clickDataTile(seen)
            latch.countDown()
        }, STEP_MS * 2)

        val finished = try {
            latch.await(TIMEOUT_S, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }

        handler.postDelayed({ performGlobalAction(GLOBAL_ACTION_BACK) }, 400L)

        if (!finished) {
            return ActionResult(false, "le panneau n'a pas répondu à temps")
        }
        return outcome
    }

    /** Cherche la tuile, clique, et dit ce qu'il a vu en cas d'echec. */
    private fun clickDataTile(seen: MutableList<String>): ActionResult {
        val nodes = collectNodes()
        if (nodes.isEmpty()) {
            return ActionResult(false, "panneau illisible (arbre d'accessibilité vide)")
        }

        for (node in nodes) {
            val label = labelOf(node)
            if (label.isNotBlank()) seen += label
        }

        findAndClick(nodes, DATA_KEYWORDS)?.let {
            return ActionResult(true, "tuile « $it » activée")
        }

        // Depuis Android 12 la tuile est souvent « Internet » et ouvre une
        // boite de dialogue contenant le vrai interrupteur.
        findAndClick(nodes, INTERNET_KEYWORDS)?.let { opened ->
            Thread.sleep(900)
            val inner = collectNodes()
            for (node in inner) {
                val label = labelOf(node)
                if (label.isNotBlank()) seen += label
            }
            findAndClick(inner, DATA_KEYWORDS)?.let {
                return ActionResult(true, "« $opened » puis « $it »")
            }
            return ActionResult(
                false,
                "« $opened » ouvert, mais aucun interrupteur de données dedans. Vu : " + summary(seen)
            )
        }

        return ActionResult(false, "tuile introuvable. Vu : " + summary(seen))
    }

    private fun findAndClick(nodes: List<AccessibilityNodeInfo>, keywords: List<String>): String? {
        for (keyword in keywords) {
            for (node in nodes) {
                val label = normalize(labelOf(node))
                if (label.isEmpty() || !label.contains(keyword)) continue

                val clickable = clickableFrom(node) ?: continue
                if (clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return labelOf(node)
                }
            }
        }
        return null
    }

    /** Le libelle porte souvent sur un enfant non cliquable : on remonte. */
    private fun clickableFrom(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < 6) {
            if (current.isClickable) return current
            current = current.parent
            depth++
        }
        return null
    }

    private fun collectNodes(): List<AccessibilityNodeInfo> {
        val roots = mutableListOf<AccessibilityNodeInfo>()
        rootInActiveWindow?.let { roots += it }
        try {
            for (window in windows) {
                window.root?.let { roots += it }
            }
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
        for (i in 0 until node.childCount) {
            walk(node.getChild(i), into, depth + 1)
        }
    }

    private fun labelOf(node: AccessibilityNodeInfo): String =
        node.text?.toString()?.takeIf { it.isNotBlank() }
            ?: node.contentDescription?.toString().orEmpty()

    private fun summary(seen: List<String>): String =
        seen.distinct().take(12).joinToString(", ").take(220)

    companion object {

        private const val TAG = "DataToggleService"
        private const val STEP_MS = 700L
        private const val TIMEOUT_S = 12L

        private val DATA_KEYWORDS = listOf(
            "donnees mobiles", "mobile data", "donnees cellulaires",
            "cellular data", "reseau mobile", "mobile network", "donnees"
        )
        private val INTERNET_KEYWORDS = listOf("internet", "reseau", "network")

        @Volatile
        private var instance: DataToggleService? = null

        fun isRunning(): Boolean = instance != null

        /** Le service est-il coche dans les Reglages ? Independant de son etat vivant. */
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
         * @param target etat voulu. Si les donnees y sont deja, on ne touche a rien :
         *   un clic de trop les remettrait dans l'etat inverse.
         */
        fun toggleTo(context: Context, target: Boolean): ActionResult {
            val service = instance
                ?: return ActionResult(false, "service d'accessibilité non actif sur l'appareil cible")

            if (MobileData.isEnabled(context) == target) {
                return ActionResult(true, if (target) "déjà activées" else "déjà coupées")
            }

            val result = service.runSequence(target)
            if (!result.ok) return result

            // On verifie l'effet reel plutot que de croire le clic sur parole.
            Thread.sleep(1200)
            val now = MobileData.isEnabled(context)
            return if (now == target) {
                ActionResult(true, (if (target) "données activées" else "données coupées") +
                    " (" + result.detail + ")")
            } else {
                ActionResult(false, "clic effectué (" + result.detail + ") mais l'état n'a pas changé")
            }
        }

        private fun normalize(raw: String): String {
            val withoutAccents = Normalizer.normalize(raw, Normalizer.Form.NFD)
                .replace(Regex("\\p{Mn}+"), "")
            return withoutAccents.lowercase().trim()
        }
    }
}
