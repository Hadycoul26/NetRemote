package com.example.netremote

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.util.Log
import android.view.Display
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Le pilotage a distance, sans scenario.
 *
 * Tout ce qui precede essayait de deviner l'interface du telephone : ou est la
 * tuile, comment elle s'appelle, ce qu'elle expose, dans quel ecran elle vit.
 * Chaque appareil repondait differemment, et chaque reponse demandait une
 * version de plus.
 *
 * Ici, l'app ne comprend plus rien a ce qu'elle montre — et c'est le but. Elle
 * envoie l'image de l'ecran, elle recoit des coordonnees, elle joue le geste.
 * C'est l'utilisateur qui reconnait sa tuile et qui appuie dessus. Rien a
 * apprendre, rien a rejouer, rien qui puisse etre appris de travers.
 *
 * Les deux capacites necessaires viennent du service d'accessibilite deja en
 * place : takeScreenshot depuis Android 11, et dispatchGesture, dont le banc
 * d'essai a confirme qu'il joue reellement le geste demande.
 */
object RemoteControl {

    private const val TAG = "RemoteControl"

    /**
     * Le systeme refuse une capture demandee moins d'une seconde apres la
     * precedente, et repond par une erreur, pas par une attente. On sert donc
     * la derniere image en dessous de cet intervalle plutot que de la reclamer.
     */
    private const val MIN_INTERVAL_MS = 1100L
    private const val SHOT_TIMEOUT_MS = 5000L

    private val shotExecutor = Executors.newSingleThreadExecutor()

    @Volatile
    private var lastShot: ByteArray? = null

    @Volatile
    private var lastShotAt = 0L

    /** Le service d'accessibilite porte tout : sans lui, rien n'est possible. */
    fun ready(): Boolean = DataToggleService.current() != null

    /**
     * La version d'Android ne suffit pas : la capture demande une capacite
     * declaree, `canTakeScreenshot`, distincte de la lecture de contenu et des
     * gestes. Sans elle, le systeme repond par une SecurityException plutot que
     * par un echec ordinaire — on demande donc au systeme, pas au calendrier.
     */
    fun canCapture(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val info = DataToggleService.current()?.serviceInfo ?: return false
        return info.capabilities and
            AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT != 0
    }

    fun screenSize(): Pair<Int, Int> {
        val service = DataToggleService.current() ?: return 0 to 0
        val metrics = service.resources.displayMetrics
        return metrics.widthPixels to metrics.heightPixels
    }

    // --- Gestes ------------------------------------------------------------

    fun tap(x: Int, y: Int): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        return play(path, 90L)
    }

    fun longPress(x: Int, y: Int): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        return play(path, 700L)
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean {
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        return play(path, durationMs.coerceIn(60L, 3000L))
    }

    private fun play(path: Path, durationMs: Long): Boolean {
        val service = DataToggleService.current() ?: return false
        return try {
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
                .build()
            service.dispatchGesture(gesture, null, null)
        } catch (e: Exception) {
            Log.w(TAG, "Geste impossible", e)
            false
        }
    }

    // --- Touches systeme ---------------------------------------------------

    /**
     * Les boutons que l'ecran ne montre pas : Retour, Accueil, et surtout le
     * volet des parametres rapides — d'ou l'on coupe les donnees a la main.
     */
    fun key(name: String): Boolean {
        val service = DataToggleService.current() ?: return false
        val action = when (name.lowercase()) {
            "back" -> AccessibilityService.GLOBAL_ACTION_BACK
            "home" -> AccessibilityService.GLOBAL_ACTION_HOME
            "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "quicksettings" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            "power" -> AccessibilityService.GLOBAL_ACTION_POWER_DIALOG
            "lock" ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
                } else {
                    return false
                }
            else -> return false
        }
        return service.performGlobalAction(action)
    }

    // --- Image de l'ecran --------------------------------------------------

    /**
     * @param maxWidth largeur maximale de l'image renvoyee. Reduire l'image
     *   divise le poids par dix sans gener la reconnaissance d'une tuile, et
     *   c'est ce qui rend le rafraichissement supportable sur un point d'acces.
     * @return JPEG, ou null si la capture a echoue.
     */
    fun screenshot(maxWidth: Int): ByteArray? {
        val service = DataToggleService.current()
        if (service == null) {
            Log.w(TAG, "Capture : service d'accessibilité absent")
            return null
        }
        if (!canCapture()) {
            Log.w(TAG, "Capture : capacité canTakeScreenshot absente")
            return null
        }

        val now = System.currentTimeMillis()
        val cached = lastShot
        if (cached != null && now - lastShotAt < MIN_INTERVAL_MS) return cached

        val latch = CountDownLatch(1)
        var encoded: ByteArray? = null

        try {
            service.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                shotExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        try {
                            val raw = Bitmap.wrapHardwareBuffer(
                                result.hardwareBuffer, result.colorSpace
                            )
                            if (raw != null) encoded = encode(raw, maxWidth)
                        } catch (e: Exception) {
                            Log.w(TAG, "Capture illisible", e)
                        } finally {
                            result.hardwareBuffer.close()
                            latch.countDown()
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.w(TAG, "Capture refusée, code " + errorCode)
                        latch.countDown()
                    }
                }
            )
        } catch (e: Exception) {
            Log.w(TAG, "Capture impossible", e)
            return cached
        }

        if (!latch.await(SHOT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            Log.w(TAG, "Capture : aucune réponse après " + SHOT_TIMEOUT_MS + " ms")
        }

        val fresh = encoded
        if (fresh == null && cached == null) {
            Log.w(TAG, "Capture : échec, et aucune image précédente à servir")
        }
        if (fresh != null) {
            lastShot = fresh
            lastShotAt = System.currentTimeMillis()
            return fresh
        }
        // Mieux vaut la derniere image connue qu'un ecran noir : l'utilisateur
        // voit que ca ne bouge plus, au lieu de croire que tout a disparu.
        return cached
    }

    private fun encode(source: Bitmap, maxWidth: Int): ByteArray {
        // wrapHardwareBuffer rend une image materielle : on la ramene en memoire
        // avant de la redimensionner ou de la compresser.
        val soft = source.copy(Bitmap.Config.ARGB_8888, false) ?: source

        val scaled = if (soft.width > maxWidth && maxWidth > 0) {
            val height = (soft.height.toLong() * maxWidth / soft.width).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(soft, maxWidth, height, true)
        } else {
            soft
        }

        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 65, out)
        return out.toByteArray()
    }
}
