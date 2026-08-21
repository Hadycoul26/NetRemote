package com.example.netremote

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.netremote.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val requestNotifications = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshUi() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.switchServer.setOnClickListener {
            Prefs.setEnabled(this, binding.switchServer.isChecked)
            if (binding.switchServer.isChecked) {
                askNotificationsIfNeeded()
                ServerService.start(this)
            } else {
                ServerService.stop(this)
            }
            refreshUi()
        }

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

        binding.btnNewKey.setOnClickListener {
            Prefs.regenerateToken(this)
            refreshUi()
        }

        binding.btnRefresh.setOnClickListener { refreshUi() }

        binding.btnBattery.setOnClickListener {
            openSettings(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
    }

    private fun askNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun refreshUi() {
        binding.switchServer.isChecked = Prefs.isEnabled(this)
        binding.txtUrls.text = buildUrls()
        binding.txtDiag.text = buildDiagnostics()
    }

    private fun buildUrls(): String {
        if (!Prefs.isEnabled(this)) return getString(R.string.server_off)

        ServerService.lastError?.let { return getString(R.string.server_failed, it) }

        val addresses = LocalAddresses.list()
        if (addresses.isEmpty()) return getString(R.string.server_no_address)

        val port = Prefs.port(this)
        val token = Prefs.token(this)
        return addresses.joinToString("\n") { "http://$it:$port/?k=$token" } +
            "\n\nClé : $token"
    }

    private fun buildDiagnostics(): String {
        val shizuku = when (ShizukuShell.state()) {
            ShizukuState.PRET -> "prêt"
            ShizukuState.NON_AUTORISE -> "lancé, PERMISSION À ACCORDER"
            ShizukuState.ABSENT -> "NON LANCÉ (à relancer après chaque reboot)"
        }

        return listOf(
            "Android        : " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")",
            "Serveur actif  : " + if (ServerService.isRunning) "oui" else "non",
            "Shizuku        : " + shizuku,
            "Données        : " + MobileData.describe(this)
        ).joinToString("\n")
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
