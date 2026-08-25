package com.example.netremote

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.netremote.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Un seul ecran, deux roles :
 *
 *  - PARTAGER : ce telephone heberge le point d'acces et obeit aux commandes.
 *  - CONTROLER : ce telephone est connecte au point d'acces d'un autre et le pilote.
 *
 * Le meme APK sur les deux appareils, sans configuration a saisir.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val worker = Executors.newSingleThreadExecutor()
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private var target: String? = null

    private val requestNotifications = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshUi() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.switchServe.setOnClickListener {
            Prefs.setServing(this, binding.switchServe.isChecked)
            if (binding.switchServe.isChecked) {
                askNotificationsIfNeeded()
                ServerService.start(this)
            } else {
                ServerService.stop(this)
            }
            refreshUi()
        }

        binding.btnScan.setOnClickListener { findTarget() }
        binding.btnDataOn.setOnClickListener { send(true) }
        binding.btnDataOff.setOnClickListener { send(false) }

        binding.btnShizuku.setOnClickListener {
            when (ShizukuShell.state()) {
                ShizukuState.PRET ->
                    Toast.makeText(this, R.string.shizuku_ready, Toast.LENGTH_LONG).show()
                ShizukuState.NON_AUTORISE ->
                    if (!ShizukuShell.requestPermission(SHIZUKU_REQUEST)) {
                        Toast.makeText(this, R.string.shizuku_absent, Toast.LENGTH_LONG).show()
                    }
                ShizukuState.ABSENT ->
                    Toast.makeText(this, R.string.shizuku_absent, Toast.LENGTH_LONG).show()
            }
            refreshUi()
        }

        binding.btnAccessibility.setOnClickListener {
            openSettings(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        binding.btnSelfTest.setOnClickListener { runSelfTest() }

        binding.btnGuided.setOnClickListener { startGuided() }

        binding.btnLearn.setOnClickListener { toggleLearning() }

        binding.btnForgetRecipe.setOnClickListener {
            Recipe.clear(this)
            refreshUi()
        }

        binding.btnBattery.setOnClickListener {
            openSettings(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
        findTarget()
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    // --- Role CONTROLER --------------------------------------------------

    /** Cherche l'appareil qui partage : c'est la passerelle de ce telephone. */
    private fun findTarget() {
        val gateway = Gateway.find(this)
        target = gateway

        if (gateway == null) {
            binding.txtTarget.setText(R.string.no_gateway)
            setCommandsEnabled(false)
            return
        }

        binding.txtTarget.text = getString(R.string.looking_for, gateway)
        setCommandsEnabled(false)

        val port = Prefs.port(this)
        worker.execute {
            val state = RemoteClient.state(gateway, port)
            runOnUiThread {
                if (!state.reachable) {
                    binding.txtTarget.text = getString(R.string.target_unreachable, gateway)
                    setCommandsEnabled(false)
                    return@runOnUiThread
                }

                binding.txtTarget.text = buildString {
                    append(state.device).append("  (").append(gateway).append(")\n")
                    append(if (state.connected) "Données : ACTIVÉES" else "Données : COUPÉES")
                    append("\n").append(state.detail)
                    if (state.warning.isNotBlank()) append("\n⚠ ").append(state.warning)
                }
                setCommandsEnabled(true)
                binding.btnDataOn.isEnabled = !state.connected
                binding.btnDataOff.isEnabled = state.connected
            }
        }
    }

    private fun send(on: Boolean) {
        val host = target ?: return
        val port = Prefs.port(this)

        setCommandsEnabled(false)
        log(if (on) "Activation des données…" else "Coupure des données…")

        worker.execute {
            val result = RemoteClient.set(host, port, on)
            runOnUiThread {
                log((if (result.ok) "OK — " else "ÉCHEC — ") + result.detail)
                // Le basculement n'est pas instantane cote systeme.
                binding.txtTarget.postDelayed({ findTarget() }, 1200)
            }
        }
    }

    private fun setCommandsEnabled(enabled: Boolean) {
        binding.btnDataOn.isEnabled = enabled
        binding.btnDataOff.isEnabled = enabled
    }

    private fun log(message: String) {
        binding.txtLog.text = clock.format(Date()) + "  " + message
    }

    // --- Test sur place --------------------------------------------------

    /**
     * Execute exactement le chemin qu'emprunte une commande distante, mais
     * depuis le telephone lui-meme.
     *
     * Interet : deboguer sans dependre du PC ni du point d'acces. Le message
     * affiche est celui que le client distant recevrait, mot pour mot.
     */
    private fun runSelfTest() {
        val target = MobileData.isEnabled(this) != true
        val what = getString(if (target) R.string.self_test_on else R.string.self_test_off)

        binding.btnSelfTest.isEnabled = false
        binding.txtSelfTest.text = getString(R.string.self_test_running, what)

        worker.execute {
            val result = MobileData.set(this, target)
            runOnUiThread {
                binding.btnSelfTest.isEnabled = true
                binding.txtSelfTest.text =
                    (if (result.ok) "OK — " else "ÉCHEC — ") + result.detail
                refreshUi()
            }
        }
    }

    // --- Apprentissage de la bascule -------------------------------------

    /**
     * L'app ne devine plus ou appuyer : l'utilisateur le montre une fois.
     * Les libelles et la disposition des parametres rapides varient trop d'un
     * appareil a l'autre pour etre codes en dur.
     */
    /**
     * Enregistre un PARCOURS, pas un appui unique : atteindre les donnees
     * demande souvent de traverser plusieurs ecrans quand la tuile des
     * parametres rapides ne repond pas.
     */
    /**
     * L'app montre ce qu'elle voit, l'utilisateur designe.
     *
     * Prefere a l'enregistrement libre : celui-ci ne capte que ce qui emet un
     * evenement de clic, et on ne decouvre ce qu'il a rate qu'au premier echec.
     * Ici, ce qui est affiche est exactement ce que l'app saura retrouver.
     *
     * Le panneau est une fenetre du service d'accessibilite, pas de l'activite :
     * pendant l'apprentissage, l'ecran affiche les parametres rapides ou les
     * Reglages, et une app en arriere-plan ne peut rien afficher.
     */
    private fun startGuided() {
        if (!DataToggleService.isRunning()) {
            Toast.makeText(this, R.string.learn_needs_service, Toast.LENGTH_LONG).show()
            return
        }
        DataToggleService.startGuided()
        log(getString(R.string.guided_started))
        refreshUi()
    }

    private fun toggleLearning() {
        if (DataToggleService.isRecording()) {
            val steps = DataToggleService.stopRecording(this)
            log(
                if (steps.isEmpty()) getString(R.string.learn_none)
                else getString(
                    R.string.learn_saved,
                    steps.size,
                    steps.joinToString(" → ") { it.describe() }
                )
            )
            refreshUi()
            return
        }

        if (!DataToggleService.isRunning()) {
            Toast.makeText(this, R.string.learn_needs_service, Toast.LENGTH_LONG).show()
            return
        }

        DataToggleService.startRecording()
        AlertDialog.Builder(this)
            .setTitle(R.string.learn_start)
            .setMessage(R.string.learn_instructions)
            .setPositiveButton(android.R.string.ok, null)
            .show()
        refreshUi()
    }

    // --- Role PARTAGER ---------------------------------------------------

    private fun askNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // --- UI --------------------------------------------------------------

    private fun refreshUi() {
        binding.switchServe.isChecked = Prefs.isServing(this)

        binding.txtServeInfo.text = if (!Prefs.isServing(this)) {
            getString(R.string.serve_off)
        } else {
            ServerService.lastError?.let { getString(R.string.serve_failed, it) }
                ?: getString(R.string.serve_on, Prefs.port(this), MobileData.describe(this))
        }

        val shizuku = when (ShizukuShell.state()) {
            ShizukuState.PRET -> "prêt"
            ShizukuState.NON_AUTORISE -> "lancé, PERMISSION À ACCORDER"
            ShizukuState.ABSENT -> "NON LANCÉ"
        }

        val accessibility = when {
            DataToggleService.isRunning() -> "actif"
            DataToggleService.isEnabledInSettings(this) -> "coché, pas encore démarré"
            else -> "NON ACTIVÉ"
        }

        if (DataToggleService.isRecording()) {
            binding.btnLearn.text =
                getString(R.string.learn_stop, DataToggleService.recordedCount())
        } else {
            binding.btnLearn.setText(R.string.learn_start)
        }

        binding.btnGuided.isEnabled = !DataToggleService.isGuiding()
        binding.txtRecipe.text = Recipe.describe(this)
        binding.btnForgetRecipe.visibility =
            if (Recipe.exists(this)) View.VISIBLE else View.GONE

        binding.txtDiag.text = listOf(
            "Android       : " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")",
            "Serveur       : " + if (ServerService.isRunning) "actif" else "arrêté",
            "Shizuku       : " + shizuku,
            "Accessibilité : " + accessibility,
            "Mes données   : " + MobileData.describe(this)
        ).joinToString("\n")

        // Shizuku n'est necessaire que pour obeir, pas pour commander.
        binding.txtShizukuHint.visibility =
            if (Prefs.isServing(this)) View.VISIBLE else View.GONE
    }

    private fun openSettings(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null)
                )
            )
        }
    }

    private companion object {
        const val SHIZUKU_REQUEST = 42
    }
}
