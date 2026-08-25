package com.example.netremote

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.text.Normalizer
import java.util.concurrent.Executors

/** Resultat d'un rejeu, avec l'etat reellement lu sur l'interrupteur. */
internal data class Replay(val ok: Boolean, val detail: String, val observed: Boolean?)

/** Un element visible a l'ecran, tel que NetRemote le voit. */
internal data class Option(
    val label: String,
    val viewId: String,
    val x: Int,
    val y: Int,
    val pkg: String,
    /** Etat lu si l'element est un interrupteur, null s'il n'en est pas un. */
    val state: Boolean?
) {
    fun toStep(role: String) = Step(viewId, label, x, y, pkg, role)
}

internal fun normalizeText(raw: String): String {
    val withoutAccents = Normalizer.normalize(raw, Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
    return withoutAccents.lowercase().trim()
}

/**
 * Lit l'etat d'un interrupteur a l'ecran.
 *
 * C'est la piece qui manquait. Un interrupteur n'est pas un bouton : il porte
 * un etat, et « active-le » ne veut pas dire « appuie dessus ». Sans cette
 * lecture, un rejeu bascule a l'aveugle et coupe les donnees une fois sur deux.
 */
internal object ToggleState {

    // Compares comme des MOTS entiers, jamais en sous-chaine : « desactive »
    // contient « active », et « connexion » contient « on ».
    private val OFF = setOf(
        "desactive", "desactivee", "desactives", "desactivees",
        "off", "eteint", "eteinte", "inactif", "inactive",
        "coupe", "coupee", "arrete", "arretee", "disabled"
    )
    private val ON = setOf(
        "active", "activee", "actives", "activees",
        "on", "allume", "allumee", "actif", "enabled", "connecte", "connectee"
    )

    fun of(node: AccessibilityNodeInfo?): Boolean? {
        if (node == null) return null

        // 1. L'etat declare par la vue : le plus fiable quand il existe.
        if (node.isCheckable) return node.isChecked

        // 2. Le texte d'etat, que les tuiles utilisent presque toutes.
        texts(node).forEach { text -> fromText(text)?.let { return it } }

        // 3. Le libelle porte souvent sur un conteneur, l'etat sur un enfant.
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (child.isCheckable) return child.isChecked
            texts(child).forEach { text -> fromText(text)?.let { return it } }
        }

        // 4. Ou l'inverse : l'etat sur le conteneur, le libelle sur l'enfant.
        var parent = node.parent
        var depth = 0
        while (parent != null && depth < 3) {
            if (parent.isCheckable) return parent.isChecked
            texts(parent).forEach { text -> fromText(text)?.let { return it } }
            parent = parent.parent
            depth++
        }

        return null
    }

    fun fromText(raw: String): Boolean? {
        val words = normalizeText(raw).split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
        if (words.any { it in OFF }) return false
        if (words.any { it in ON }) return true
        return null
    }

    private fun texts(node: AccessibilityNodeInfo): List<String> {
        val out = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            node.stateDescription?.toString()?.let { out += it }
        }
        node.contentDescription?.toString()?.let { out += it }
        return out
    }
}

/**
 * Bascule les donnees mobiles en rejouant un parcours appris.
 *
 * Seule voie sans root ni adb : l'utilisateur active le service une fois dans
 * les Reglages, et il survit aux redemarrages. En echange, l'ecran doit etre
 * allume, puisqu'on manipule reellement l'interface.
 *
 * Deux facons d'apprendre le parcours :
 *
 *  - GUIDEE : l'app affiche ce qu'elle voit a l'ecran et l'utilisateur designe
 *    l'element, ecran par ecran. Rien n'est devine, et on sait des
 *    l'apprentissage si l'element est lisible, au lieu de le decouvrir au
 *    premier echec.
 *  - LIBRE : l'utilisateur bascule les donnees comme d'habitude et l'app
 *    enregistre ses appuis. Plus rapide, mais elle ne voit que ce qui emet un
 *    evenement de clic.
 *
 * Dans les deux cas, le dernier pas est l'INTERRUPTEUR : il est lu avant
 * d'etre touche, et n'est touche que si son etat differe de celui demande.
 */
class DataToggleService : AccessibilityService() {

    internal val handler = Handler(Looper.getMainLooper())

    /** L'apprentissage guide attend entre les etapes : jamais sur le thread principal. */
    private val work = Executors.newSingleThreadExecutor()

    private var overlay: Overlay? = null
    private val guided = mutableListOf<Step>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        overlay = Overlay(this)
        Log.i(TAG, "Service d'accessibilité connecté")
    }

    override fun onDestroy() {
        instance = null
        guiding = false
        handler.post { overlay?.hide() }
        work.shutdown()
        super.onDestroy()
    }

    override fun onInterrupt() = Unit

    // --- Apprentissage libre ---------------------------------------------

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!recording || guiding || event == null) return
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
        Log.i(TAG, "Appui " + recorded.size + " enregistré : " + step.describe() + " [" + pkg + "]")
    }

    // --- Apprentissage guide ---------------------------------------------

    /** Ouvre les parametres rapides et montre ce qui s'y trouve. */
    internal fun beginGuided() {
        if (guiding) return
        guiding = true
        guided.clear()
        work.execute {
            ensureQuickSettings()
            pause(PANEL_SETTLE_MS)
            presentOptions()
        }
    }

    /** Sur le thread de travail : lit l'ecran, puis affiche le panneau. */
    private fun presentOptions() {
        if (!guiding) return
        val options = snapshot()
        val index = guided.size + 1
        val trail =
            if (guided.isEmpty()) "Voici ce que NetRemote voit sur cet écran."
            else "Déjà : " + guided.joinToString(" → ") { it.describe() }

        handler.post {
            val panel = overlay ?: return@post
            if (options.isEmpty()) {
                panel.show(
                    "Étape " + index + " — écran illisible",
                    "Aucun élément lisible ici. Ouvre l'écran voulu, puis relis.",
                    listOf(
                        Overlay.Entry("Relire l'écran") { work.execute { presentOptions() } },
                        Overlay.Entry("Annuler") { cancelGuided() }
                    )
                )
                return@post
            }

            val entries = options.map { option ->
                Overlay.Entry(option.label, describeOption(option)) { askRole(option) }
            } + listOf(
                Overlay.Entry("Ouvrir les Réglages", "pour désigner l'interrupteur là-bas") {
                    openSettingsForGuided()
                },
                Overlay.Entry("Relire l'écran", "si l'écran a changé depuis") {
                    work.execute { presentOptions() }
                },
                Overlay.Entry("Annuler", "ne rien enregistrer") { cancelGuided() }
            )

            panel.show("Étape " + index + " — que faut-il toucher ?", trail, entries)
        }
    }

    private fun describeOption(option: Option): String = when (option.state) {
        true -> "interrupteur — actuellement activé"
        false -> "interrupteur — actuellement coupé"
        null -> option.pkg
    }

    /**
     * La seule question posee a l'utilisateur, et elle est necessaire : rien
     * dans l'arbre d'accessibilite ne distingue de facon fiable une tuile qui
     * bascule d'une entree qui ouvre un ecran.
     */
    private fun askRole(option: Option) {
        overlay?.show(
            "« " + option.label + " »",
            "Qu'est-ce que c'est ?",
            listOf(
                Overlay.Entry(
                    "C'est l'interrupteur des données",
                    "NetRemote lira son état et ne l'appuiera que si besoin"
                ) { finishGuided(option) },
                Overlay.Entry(
                    "Ça ouvre un autre écran",
                    "NetRemote l'appuiera, puis te montrera l'écran suivant"
                ) { navigateGuided(option) },
                Overlay.Entry("Revenir à la liste") { work.execute { presentOptions() } }
            )
        )
    }

    /**
     * Le volet des parametres rapides n'est pas la seule voie, et c'est la
     * moins solide : ses tuiles publient rarement leur etat. L'ecran des
     * Reglages porte un vrai interrupteur — lisible, et rejouable par un simple
     * lancement d'activite.
     */
    private fun openSettingsForGuided() {
        work.execute {
            handler.post { overlay?.hide() }
            pause(HIDE_MS)
            try {
                val intent = Intent(Settings.ACTION_SETTINGS)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                startActivity(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Reglages inaccessibles", e)
            }
            pause(NAVIGATE_MS)
            presentOptions()
        }
    }

    private fun navigateGuided(option: Option) {
        guided += option.toStep(Step.ROLE_TAP)
        work.execute {
            // Sans ca, le geste atterrit sur notre propre panneau.
            handler.post { overlay?.hide() }
            pause(HIDE_MS)
            tapAt(option.x, option.y)
            pause(NAVIGATE_MS)
            presentOptions()
        }
    }

    private fun finishGuided(option: Option) {
        guided += option.toStep(Step.ROLE_TOGGLE)
        Recipe.save(this, guided.toList())
        guiding = false

        val summary = guided.mapIndexed { i, s -> (i + 1).toString() + ". " + s.describe() }
            .joinToString("\n")

        overlay?.show(
            "Parcours enregistré",
            summary,
            listOf(
                Overlay.Entry("Revenir à NetRemote", "pour tester la bascule") {
                    overlay?.hide()
                    openApp()
                },
                Overlay.Entry("Fermer") { overlay?.hide() }
            )
        )
    }

    private fun cancelGuided() {
        guiding = false
        guided.clear()
        overlay?.hide()
    }

    private fun openApp() {
        try {
            val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Retour a l'app impossible", e)
        }
    }

    /** Ce que l'ecran offre : un libelle, une position, et un etat s'il y en a un. */
    internal fun snapshot(): List<Option> {
        val out = LinkedHashMap<String, Option>()

        for (node in collectNodes()) {
            // Notre propre panneau fait partie de l'arbre : l'exclure.
            if (node.packageName?.toString() == packageName) continue

            val label = labelOf(node)
            if (label.isBlank() || label.length > 70) continue
            if (!node.isClickable && !node.isCheckable && !hasClickableAncestor(node)) continue

            val bounds = Rect().also { node.getBoundsInScreen(it) }
            if (bounds.width() <= 0 || bounds.height() <= 0) continue

            val key = normalizeText(label)
            if (out.containsKey(key)) continue

            out[key] = Option(
                label = label,
                viewId = node.viewIdResourceName.orEmpty(),
                x = bounds.centerX(),
                y = bounds.centerY(),
                pkg = node.packageName?.toString().orEmpty(),
                state = ToggleState.of(node)
            )
            if (out.size >= MAX_OPTIONS) break
        }

        return out.values.toList()
    }

    private fun hasClickableAncestor(node: AccessibilityNodeInfo): Boolean {
        var parent = node.parent
        var depth = 0
        while (parent != null && depth < 4) {
            if (parent.isClickable) return true
            parent = parent.parent
            depth++
        }
        return false
    }

    // --- Rejeu ------------------------------------------------------------

    /**
     * Ouvre le volet des parametres rapides ET verifie qu'il est ouvert.
     *
     * L'ancienne version envoyait l'action deux fois de suite, en pariant que
     * la premiere ouvrait le volet des notifications et la seconde le depliait.
     * Sur Android 15 la premiere ouvre deja les parametres rapides : la seconde
     * les refermait. Le rejeu appuyait alors sur un ecran qui n'etait plus la.
     *
     * On agit donc sur constat, pas sur pari : on demande, on regarde, on
     * redemande si besoin.
     */
    internal fun ensureQuickSettings(): Boolean {
        repeat(PANEL_TRIES) {
            if (quickSettingsOpen()) return true
            handler.post { performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS) }
            pause(PANEL_WAIT_MS)
        }
        return quickSettingsOpen()
    }

    /**
     * Volet ouvert = SystemUI occupe une grande part de l'ecran. Replie, il ne
     * garde que la barre d'etat et la barre de navigation.
     */
    private fun quickSettingsOpen(): Boolean {
        val metrics = resources.displayMetrics
        val screen = metrics.widthPixels.toLong() * metrics.heightPixels.toLong()
        if (screen <= 0L) return false

        return collectNodes().any { node ->
            val pkg = node.packageName?.toString().orEmpty()
            if (!pkg.contains("systemui", ignoreCase = true) || !node.isVisibleToUser) {
                false
            } else {
                val bounds = Rect().also { node.getBoundsInScreen(it) }
                bounds.width().toLong() * bounds.height().toLong() > screen / 4
            }
        }
    }

    private fun activeWindowPackage(): String =
        rootInActiveWindow?.packageName?.toString().orEmpty().ifBlank { "inconnu" }

    /**
     * Tourne sur le thread appelant (une requete HTTP), jamais sur le thread
     * principal : les attentes entre etapes le bloqueraient plusieurs secondes,
     * assez pour declencher un ANR.
     */
    internal fun replayLearned(steps: List<Step>, target: Boolean): Replay {
        if (steps.isEmpty()) return Replay(false, "aucune séquence apprise", null)

        if (!bringUp(steps.first().pkg)) {
            val what = if (steps.first().pkg.contains("systemui", ignoreCase = true)) {
                "le volet des paramètres rapides ne s'est pas ouvert"
            } else {
                "impossible d'ouvrir « " + steps.first().pkg + " »"
            }
            return Replay(false, what + " (écran actif : " + activeWindowPackage() + ")", null)
        }
        pause(START_MS)

        val done = mutableListOf<String>()
        for ((index, step) in steps.withIndex()) {
            val position = "étape " + (index + 1) + "/" + steps.size + " (" + step.describe() + ")"
            val node = awaitNode(step, LOCATE_MS)

            if (step.isToggle) {
                if (node == null) {
                    closePanel()
                    return Replay(
                        false,
                        position + " pas visible à l'écran (écran actif : " +
                            activeWindowPackage() + ")" + after(done),
                        null
                    )
                }
                val outcome = applyToggle(node, target, step.describe()) {
                    awaitNode(step, RELOCATE_MS)
                }
                closePanel()
                return Replay(outcome.ok, before(done) + outcome.detail, outcome.observed)
            }

            // Plus d'appui a l'aveugle sur des coordonnees memorisees : si
            // l'element n'est pas la, le toucher ne peut rien atteindre.
            if (node == null || !tapNode(node)) {
                closePanel()
                return Replay(
                    false,
                    position + " pas visible à l'écran (écran actif : " +
                        activeWindowPackage() + ")" + after(done),
                    null
                )
            }

            done += step.describe()
            // Ouvrir un ecran de Reglages prend nettement plus qu'un appui sur
            // une tuile : on laisse le temps a la fenetre suivante d'arriver.
            pause(BETWEEN_MS)
        }

        closePanel()
        return Replay(true, "parcours rejoué : " + done.joinToString(" → "), null)
    }

    /**
     * Le coeur de la correction : on LIT avant d'appuyer.
     *
     * Un interrupteur deja dans l'etat voulu ne doit pas etre touche, l'appui
     * l'en sortirait. Et apres l'appui on relit : c'est l'interrupteur lui-meme
     * qui dit si ca a pris, pas notre optimisme.
     */
    private fun applyToggle(
        node: AccessibilityNodeInfo,
        target: Boolean,
        what: String,
        relocate: () -> AccessibilityNodeInfo?
    ): Replay {
        // Certaines tuiles ne publient pas leur etat — celle de l'operateur,
        // typiquement. Le reglage systeme repond alors a leur place.
        val before = ToggleState.of(node) ?: MobileData.isEnabled(this)

        if (before == target) {
            return Replay(true, what + " était déjà " + onOff(target) + " : rien touché", target)
        }

        if (!tapNode(node)) return Replay(false, what + " : appui impossible", before)
        pause(SETTLE_MS)

        val after = ToggleState.of(relocate())
        return when {
            after == target -> Replay(true, what + " : passé à " + onOff(target), target)
            after == null -> Replay(true, what + " : appui envoyé, état de l'interrupteur illisible", null)
            else -> Replay(false, what + " : appuyé, mais resté " + onOff(after), after)
        }
    }

    private fun onOff(state: Boolean) = if (state) "activé" else "coupé"

    private fun before(done: List<String>) =
        if (done.isEmpty()) "" else done.joinToString(" → ") + " → "

    private fun after(done: List<String>) =
        if (done.isEmpty()) "" else ", après : " + done.joinToString(" → ")

    /**
     * Se replacer au point de depart du parcours.
     *
     * Sans ca, un parcours qui commence dans les Reglages echouerait des la
     * premiere etape si le telephone affiche autre chose.
     */
    private fun bringUp(pkg: String): Boolean {
        if (pkg.isBlank()) return true

        if (pkg.contains("systemui", ignoreCase = true)) {
            return ensureQuickSettings()
        }

        return try {
            val intent = packageManager.getLaunchIntentForPackage(pkg)
                ?: Intent(Settings.ACTION_SETTINGS).takeIf { pkg.contains("settings") }
                ?: return false
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            startActivity(intent)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Ouverture de " + pkg + " impossible", e)
            false
        }
    }

    /**
     * On reessaie tant que le noeud n'est pas REELLEMENT a l'ecran.
     *
     * L'arbre d'accessibilite contient les fenetres repliees : la tuile des
     * parametres rapides s'y trouve meme volet ferme. La retrouver ne prouve
     * donc rien, et lui envoyer un geste revient a toucher un endroit ou elle
     * n'est pas. C'est ce qui produisait « appui envoyé » suivi de « l'état n'a
     * pas changé » : les deux etaient vrais, et l'appui tombait dans le vide.
     */
    private fun awaitNode(step: Step, budgetMs: Long): AccessibilityNodeInfo? {
        val deadline = System.currentTimeMillis() + budgetMs
        while (true) {
            val node = locate(step, collectNodes())
            if (node != null && isOnScreen(node)) return node
            if (System.currentTimeMillis() >= deadline) return null
            pause(300)
        }
    }

    private fun isOnScreen(node: AccessibilityNodeInfo): Boolean {
        if (!node.isVisibleToUser) return false
        val metrics = resources.displayMetrics
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        return bounds.width() > 0 && bounds.height() > 0 &&
            bounds.left >= 0 && bounds.top >= 0 &&
            bounds.right <= metrics.widthPixels && bounds.bottom <= metrics.heightPixels
    }

    private fun locate(step: Step, nodes: List<AccessibilityNodeInfo>): AccessibilityNodeInfo? {
        if (step.viewId.isNotBlank()) {
            nodes.firstOrNull { it.viewIdResourceName == step.viewId }?.let { return it }
        }
        if (step.label.isNotBlank()) {
            val wanted = normalizeText(step.label)
            nodes.firstOrNull { normalizeText(labelOf(it)) == wanted }?.let { return it }
        }
        return null
    }

    /**
     * Vrai toucher d'abord, ACTION_CLICK en dernier recours.
     *
     * La distinction est capitale : sur les tuiles des parametres rapides,
     * l'action d'accessibilite est souvent cablee sur « ouvrir les reglages
     * detailles », alors qu'un appui reel bascule. Observe sur l'appareil.
     */
    private fun tapNode(node: AccessibilityNodeInfo): Boolean {
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        if (bounds.width() > 0 && bounds.height() > 0 &&
            tapAt(bounds.centerX(), bounds.centerY())
        ) {
            return true
        }
        return clickNode(node)
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

    internal fun searchByKeywords(target: Boolean): Replay {
        if (!ensureQuickSettings()) {
            return Replay(
                false,
                "le volet des paramètres rapides ne s'est pas ouvert (écran actif : " +
                    activeWindowPackage() + ")",
                null
            )
        }
        pause(PANEL_SETTLE_MS)

        val outcome = keywordToggle(target)
        closePanel()
        return outcome
    }

    private fun keywordToggle(target: Boolean): Replay {
        val seen = mutableListOf<String>()
        val nodes = collectNodes()
        if (nodes.isEmpty()) {
            return Replay(false, "panneau illisible (arbre d'accessibilité vide)", null)
        }
        nodes.forEach { labelOf(it).takeIf { l -> l.isNotBlank() }?.let { l -> seen += l } }

        findByKeywords(nodes, DATA_KEYWORDS)?.let { tile ->
            return applyToggle(tile, target, "tuile « " + labelOf(tile) + " »") {
                findByKeywords(collectNodes(), DATA_KEYWORDS)
            }
        }

        findByKeywords(nodes, INTERNET_KEYWORDS)?.let { entry ->
            val opened = labelOf(entry)
            if (!tapNode(entry)) return Replay(false, "« " + opened + " » : appui impossible", null)
            pause(INNER_MS)

            val inner = collectNodes()
            inner.forEach { labelOf(it).takeIf { l -> l.isNotBlank() }?.let { l -> seen += l } }

            findByKeywords(inner, DATA_KEYWORDS)?.let { switch ->
                val what = "« " + opened + " » puis « " + labelOf(switch) + " »"
                return applyToggle(switch, target, what) {
                    findByKeywords(collectNodes(), DATA_KEYWORDS)
                }
            }
            return Replay(
                false,
                "« " + opened + " » ouvert, aucun interrupteur de données dedans. Vu : " + summary(seen),
                null
            )
        }

        return Replay(false, "tuile introuvable. Vu : " + summary(seen), null)
    }

    private fun findByKeywords(
        nodes: List<AccessibilityNodeInfo>,
        keywords: List<String>
    ): AccessibilityNodeInfo? {
        for (keyword in keywords) {
            for (node in nodes) {
                if (!isOnScreen(node)) continue
                val label = normalizeText(labelOf(node))
                if (label.isNotEmpty() && label.contains(keyword)) return node
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

    /**
     * Ne referme que si le volet est ouvert : un RETOUR envoye a l'aveugle
     * quitte l'application au premier plan — c'est ce qui renvoyait le
     * telephone a l'accueil au milieu d'un test.
     */
    private fun closePanel() {
        if (!quickSettingsOpen()) return
        handler.post { performGlobalAction(GLOBAL_ACTION_BACK) }
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
        private const val START_MS = 1800L
        private const val PANEL_TRIES = 3
        private const val PANEL_WAIT_MS = 1200L
        private const val PANEL_SETTLE_MS = 600L
        private const val BETWEEN_MS = 1000L
        private const val LOCATE_MS = 6000L
        private const val RELOCATE_MS = 1500L
        private const val SETTLE_MS = 900L
        private const val INNER_MS = 900L
        private const val HIDE_MS = 250L
        private const val NAVIGATE_MS = 1400L
        private const val VERIFY_MS = 1500L
        private const val MAX_OPTIONS = 40

        private val DATA_KEYWORDS = listOf(
            "donnees mobiles", "mobile data", "donnees cellulaires",
            "cellular data", "reseau mobile", "mobile network", "donnees"
        )
        private val INTERNET_KEYWORDS = listOf("internet", "reseau", "network")

        @Volatile
        private var instance: DataToggleService? = null

        @Volatile
        private var recording = false

        @Volatile
        private var guiding = false

        private val recorded = mutableListOf<Step>()

        fun isRunning(): Boolean = instance != null

        fun isRecording(): Boolean = recording

        fun isGuiding(): Boolean = guiding

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

        /** @return false si le service d'accessibilite n'est pas actif. */
        fun startGuided(): Boolean {
            val service = instance ?: return false
            service.beginGuided()
            return true
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
            Log.i(TAG, "Enregistrement arrêté : " + steps.size + " appui(s)")
            return steps
        }

        /**
         * @param target etat voulu, pas un basculement. La difference est tout
         *   le sujet : on lit l'interrupteur, et on ne le touche que s'il n'y
         *   est pas deja.
         */
        fun toggleTo(context: Context, target: Boolean): ActionResult {
            val service = instance
                ?: return ActionResult(false, "service d'accessibilité non actif sur l'appareil cible")

            if (MobileData.isEnabled(context) == target) {
                return ActionResult(true, if (target) "déjà activées" else "déjà coupées")
            }

            val learned = Recipe.load(context)
            val replay = if (learned.isNotEmpty()) {
                service.replayLearned(learned, target)
            } else {
                service.searchByKeywords(target)
            }
            if (!replay.ok) return ActionResult(false, replay.detail)

            // On verifie l'effet reel plutot que de croire l'appui sur parole.
            service.pause(VERIFY_MS)
            val done = if (target) "données activées" else "données coupées"
            val system = MobileData.isEnabled(context)

            return when {
                system == target ->
                    ActionResult(true, done + " (" + replay.detail + ")")

                // L'interrupteur lui-meme est un temoin plus direct que le
                // reglage systeme, que certains appareils ne publient pas.
                replay.observed == target ->
                    ActionResult(true, done + " d'après l'interrupteur (" + replay.detail + ")")

                system == null ->
                    ActionResult(true, replay.detail + " — état invérifiable sur cet appareil")

                else ->
                    ActionResult(
                        false,
                        "parcours exécuté (" + replay.detail + ") mais l'état n'a pas changé"
                    )
            }
        }
    }
}
