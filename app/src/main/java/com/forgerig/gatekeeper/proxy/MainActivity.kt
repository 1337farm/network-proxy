package com.forgerig.gatekeeper.proxy

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.textfield.TextInputEditText
class MainActivity : AppCompatActivity() {

    private lateinit var viewModel: ProxyViewModel
    private var setupScriptExpanded = false
    private val statsHandler = Handler(Looper.getMainLooper())
    private val statsPoller = object : Runnable {
        override fun run() {
            refreshStats()
            statsHandler.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        viewModel = ViewModelProvider(this)[ProxyViewModel::class.java]
        viewModel.attach(this)

        val startStopButton = findViewById<MaterialButton>(R.id.startStopButton)
        val exportButton = findViewById<MaterialButton>(R.id.exportButton)
        val copyScriptButton = findViewById<MaterialButton>(R.id.copyScriptButton)
        val setupScriptText = findViewById<TextView>(R.id.setupScriptText)
        val setupScriptScrollView = findViewById<android.widget.ScrollView>(R.id.setupScriptScrollView)
        val setupScriptExpandIcon = findViewById<ImageView>(R.id.setupScriptExpandIcon)
        val setupScriptHeader = findViewById<android.view.View>(R.id.setupScriptHeader)
        val copyCleanupButton = findViewById<MaterialButton>(R.id.copyCleanupButton)
        val cleanupScriptText = findViewById<TextView>(R.id.cleanupScriptText)
        val cleanupScriptScrollView = findViewById<android.widget.ScrollView>(R.id.cleanupScriptScrollView)
        val cleanupScriptExpandIcon = findViewById<ImageView>(R.id.cleanupScriptExpandIcon)
        val cleanupScriptHeader = findViewById<android.view.View>(R.id.cleanupScriptHeader)
        var cleanupScriptExpanded = false
        val portInput = findViewById<TextInputEditText>(R.id.portInput)
        val metricsCheck = findViewById<MaterialCheckBox>(R.id.metricsCheck)
        val mitmCheck = findViewById<MaterialCheckBox>(R.id.mitmCheck)

        val refreshScript = {
            val p = portInput.text.toString().toIntOrNull() ?: 3128
            setupScriptText.text = SetupScript.build(this, p)
            cleanupScriptText.text = SetupScript.cleanup(p)
        }
        portInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) = refreshScript()
        })
        refreshScript()

        val toggleExpand = {
            setupScriptExpanded = !setupScriptExpanded
            setupScriptScrollView.visibility = if (setupScriptExpanded) View.VISIBLE else View.GONE
            setupScriptExpandIcon.setImageResource(
                if (setupScriptExpanded) android.R.drawable.arrow_up_float else android.R.drawable.arrow_down_float
            )
        }

        val toggleCleanupExpand = {
            cleanupScriptExpanded = !cleanupScriptExpanded
            cleanupScriptScrollView.visibility = if (cleanupScriptExpanded) View.VISIBLE else View.GONE
            cleanupScriptExpandIcon.setImageResource(
                if (cleanupScriptExpanded) android.R.drawable.arrow_up_float else android.R.drawable.arrow_down_float
            )
        }

        // Header click expands/collapses
        setupScriptHeader.setOnClickListener { toggleExpand() }
        setupScriptExpandIcon.setOnClickListener { toggleExpand() }
        cleanupScriptHeader.setOnClickListener { toggleCleanupExpand() }
        cleanupScriptExpandIcon.setOnClickListener { toggleCleanupExpand() }

        // Observe running state (survives rotation via ViewModel).
        // isRunning is service-authoritative via the binder state callback.
        viewModel.isRunning.observe(this) { running ->
            updateUI(running, viewModel.lastError.value)
        }
        viewModel.lastError.observe(this) { error ->
            updateUI(viewModel.isRunning.value == true, error)
        }

        startStopButton.setOnClickListener {
            val port = portInput.text.toString().toIntOrNull() ?: 8080
            val metricsEnabled = metricsCheck.isChecked
            val mitmEnabled = mitmCheck.isChecked

            if (viewModel.isRunning.value == true) {
                viewModel.stopProxy()
            } else {
                if (mitmEnabled && MitmCa.caPem(this) == null) {
                    Toast.makeText(
                        this,
                        "MITM CA unavailable — starting opaque (install CA via Export below)",
                        Toast.LENGTH_LONG
                    ).show()
                }
                viewModel.startProxy(port, metricsEnabled, mitmEnabled)
            }
            refreshScript()
        }

        copyScriptButton.setOnClickListener {
            val script = setupScriptText.text.toString()
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("opencode-proxy-setup", script))
            Toast.makeText(this, "Setup script copied — paste it into your terminal", Toast.LENGTH_LONG).show()
        }

        copyCleanupButton.setOnClickListener {
            val script = cleanupScriptText.text.toString()
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("opencode-proxy-cleanup", script))
            Toast.makeText(this, "Cleanup script copied — paste it into your terminal", Toast.LENGTH_LONG).show()
        }

        exportButton.setOnClickListener {
            try {
                val file = viewModel.exportMetrics(this)
                val uri = FileProvider.getUriForFile(
                    this,
                    "${applicationContext.packageName}.fileprovider",
                    file
                )
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "application/json"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(share, "Share proxy metrics"))
                Toast.makeText(this, "Metrics saved: ${file.name}", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }

        findViewById<MaterialButton>(R.id.clearButton)?.setOnClickListener {
            viewModel.clearMetrics()
            ProxyMetrics.clearEvents()
            refreshStats()
            Toast.makeText(this, "Metrics cleared", Toast.LENGTH_SHORT).show()
        }

        refreshProvidersSummary()
        findViewById<MaterialButton>(R.id.seedProvidersButton)?.setOnClickListener {
            val store = ProviderBroker.store(this)
            var added = 0
            for (p in ProviderStore.wellKnown()) {
                if (!store.providers.containsKey(p.id)) {
                    store.providers[p.id] = p
                    added++
                }
            }
            ProviderBroker.save(this, store)
            refreshProvidersSummary()
            Toast.makeText(this, "Seeded $added providers — add keys next", Toast.LENGTH_SHORT).show()
        }
        findViewById<MaterialButton>(R.id.addKeyButton)?.setOnClickListener { showAddKeyDialog() }
        findViewById<MaterialButton>(R.id.exportBackupButton)?.setOnClickListener { showExportBackupDialog() }
        findViewById<MaterialButton>(R.id.importBackupButton)?.setOnClickListener { showImportBackupDialog() }
        findViewById<MaterialButton>(R.id.exportCaButton)?.setOnClickListener { shareMitmCa() }
    }

    private fun refreshProvidersSummary() {
        findViewById<TextView>(R.id.providersSummaryText)?.text =
            ProviderBroker.store(this).summary()
    }

    private fun textInput(hint: String, secret: Boolean = false): android.widget.EditText {
        return android.widget.EditText(this).apply {
            this.hint = hint
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 12 }
            if (secret) inputType =
                android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
    }

    /** Add-key dialog: provider picker + label + secret → sealed vault. */
    private fun showAddKeyDialog() {
        val store = ProviderBroker.store(this)
        if (store.providers.isEmpty()) {
            Toast.makeText(this, "Seed providers first", Toast.LENGTH_SHORT).show()
            return
        }
        val ids = store.providers.keys.sorted().toTypedArray()
        val spinner = android.widget.Spinner(this).apply {
            adapter = android.widget.ArrayAdapter(
                this@MainActivity, android.R.layout.simple_spinner_dropdown_item, ids
            )
        }
        val label = textInput("Label (e.g. claude-personal)")
        val secret = textInput("API key (sk-…)", secret = true)
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
            addView(spinner); addView(label); addView(secret)
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Add provider key")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val pid = ids[spinner.selectedItemPosition]
                val sec = secret.text.toString().trim()
                if (sec.isEmpty()) {
                    Toast.makeText(this, "Empty key — not saved", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val p = store.providers[pid]!!
                p.keys.add(
                    ProviderStore.ApiKey(
                        id = java.util.UUID.randomUUID().toString(),
                        label = label.text.toString().trim().ifEmpty { "key-${p.keys.size + 1}" },
                        secret = sec
                    )
                )
                ProviderBroker.save(this, store)
                refreshProvidersSummary()
                Toast.makeText(this, "Key sealed for $pid", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Export dialog: password → share password-wrapped backup text. */
    private fun showExportBackupDialog() {
        if (!CredentialVault.exists(this)) {
            Toast.makeText(this, "Vault empty — nothing to export", Toast.LENGTH_SHORT).show()
            return
        }
        val pw = textInput("Backup password", secret = true)
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
            addView(pw)
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Export encrypted backup")
            .setView(layout)
            .setPositiveButton("Share") { _, _ ->
                try {
                    val backup = CredentialVault.exportBackup(this, pw.text.toString())
                    val share = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, backup)
                    }
                    startActivity(Intent.createChooser(share, "Share vault backup"))
                } catch (e: Exception) {
                    Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Import dialog: paste backup + password → validate → seal. */
    private fun showImportBackupDialog() {
        val pw = textInput("Backup password", secret = true)
        val blob = textInput("Paste backup (npbk1:…)")
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
            addView(blob); addView(pw)
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Import encrypted backup")
            .setView(layout)
            .setPositiveButton("Import") { _, _ ->
                try {
                    CredentialVault.importBackup(this, blob.text.toString(), pw.text.toString())
                    ProviderBroker.invalidate()
                    refreshProvidersSummary()
                    Toast.makeText(this, "Backup imported", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Share the MITM CA cert so the setup script can trust it. */
    private fun shareMitmCa() {
        val pem = MitmCa.caPem(this)
        if (pem == null) {
            Toast.makeText(this, "CA unavailable", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val file = java.io.File(cacheDir, "network-proxy-ca.pem")
            file.writeText(pem)
            val uri = FileProvider.getUriForFile(
                this, "${applicationContext.packageName}.fileprovider", file
            )
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "application/x-pem-file"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(share, "Share MITM CA (install in terminal)"))
        } catch (e: Exception) {
            Toast.makeText(this, "Share failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        statsHandler.post(statsPoller)
    }

    override fun onPause() {
        statsHandler.removeCallbacks(statsPoller)
        super.onPause()
    }

    private fun refreshStats() {
        val svc = viewModel.proxyService.value
        val (requests, bytes, active) = svc?.getStats() ?: Triple(0L, 0L, 0)
        val snap = ProxyMetrics.snapshot()
        val retries = snap.retryCounts.values.sum()
        findViewById<TextView>(R.id.requestsText)?.text = "Requests: $requests"
        findViewById<TextView>(R.id.bytesText)?.text = "Transferred: ${humanBytes(bytes)}"
        findViewById<TextView>(R.id.sessionsText)?.text = "Active Sessions: $active"
        findViewById<TextView>(R.id.retriesText)?.text =
            if (snap.scenarioCounts.isEmpty()) "Retries: $retries"
            else "Retries: $retries (${snap.scenarioCounts.entries.joinToString { "${it.key}=${it.value}" }})"
        findViewById<TextView>(R.id.tokensText)?.text =
            "Tokens: in ${ProxyMetrics.inputTokens} / out ${ProxyMetrics.outputTokens} " +
                "(cache r ${ProxyMetrics.cacheReadTokens} / w ${ProxyMetrics.cacheWriteTokens})"
        val hosts = ProxyMetrics.hostSummary(3)
        findViewById<TextView>(R.id.hostsText)?.text =
            if (hosts.isEmpty()) ""
            else hosts.joinToString("\n") { (h, up, down) -> "↕ $h ↑${humanBytes(up)} ↓${humanBytes(down)}" }
        val events = ProxyMetrics.recentEvents(15)
        findViewById<TextView>(R.id.eventsText)?.text =
            if (events.isEmpty()) "No events yet — start the proxy." else events.joinToString("\n")
        // Auto-scroll the feed to the newest entry (top after reverse).
        findViewById<android.widget.ScrollView>(R.id.eventsScrollView)?.post {
            findViewById<android.widget.ScrollView>(R.id.eventsScrollView)?.fullScroll(View.FOCUS_UP)
        }
    }

    private fun humanBytes(bytes: Long): String {
        var v = bytes.toDouble()
        val units = arrayOf("B", "KB", "MB", "GB")
        var u = 0
        while (v >= 1024 && u < units.size - 1) { v /= 1024; u++ }
        return if (u == 0) "${bytes} B" else String.format("%.1f %s", v, units[u])
    }

    private fun updateUI(isRunning: Boolean, error: String? = null) {
        val startStopButton = findViewById<MaterialButton>(R.id.startStopButton)
        val statusText = findViewById<TextView>(R.id.statusText)
        val statusDot = findViewById<View>(R.id.statusDot)
        val portText = findViewById<TextView>(R.id.portText)
        val upstreamText = findViewById<TextView>(R.id.upstreamText)
        val portInput = findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.portInput)

        // Status hues come from the theme system (desaturated emerald /
        // soft red / amber) so Running / Stopped / warning sit with the
        // violet chrome instead of clashing neon.
        val running = getColor(R.color.status_running)
        val stopped = getColor(R.color.status_stopped)
        val warning = getColor(R.color.status_warning)

        if (!error.isNullOrBlank()) {
            startStopButton.text = getString(R.string.start_proxy)
            startStopButton.icon = getDrawable(android.R.drawable.ic_media_play)
            statusText.text = error
            statusText.setTextColor(warning)
            statusDot.backgroundTintList = android.content.res.ColorStateList.valueOf(warning)
            portText.text = "Port ${portInput.text} — tap Start to retry"
            portText.visibility = View.VISIBLE
            upstreamText.visibility = View.GONE
        } else if (isRunning) {
            startStopButton.text = getString(R.string.stop_proxy)
            startStopButton.icon = getDrawable(android.R.drawable.ic_media_pause)
            statusText.text = getString(R.string.status_running)
            statusText.setTextColor(running)
            statusDot.backgroundTintList = android.content.res.ColorStateList.valueOf(running)
            val lan = SetupScript.lanIp(this)
            portText.text = if (lan.isNotBlank()) "127.0.0.1:${portInput.text} • LAN $lan:${portInput.text}"
                else "127.0.0.1:${portInput.text}"
            portText.visibility = View.VISIBLE
            upstreamText.visibility = View.GONE
        } else {
            startStopButton.text = getString(R.string.start_proxy)
            startStopButton.icon = getDrawable(android.R.drawable.ic_media_play)
            statusText.text = getString(R.string.status_stopped)
            statusText.setTextColor(stopped)
            statusDot.backgroundTintList = android.content.res.ColorStateList.valueOf(stopped)
            portText.visibility = View.GONE
            upstreamText.visibility = View.GONE
        }
    }
}