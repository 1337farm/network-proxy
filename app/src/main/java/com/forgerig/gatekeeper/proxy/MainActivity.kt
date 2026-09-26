package com.forgerig.gatekeeper.proxy

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.textfield.TextInputEditText
class MainActivity : AppCompatActivity() {

    companion object {
        private const val PREFS = "gatekeeper"
        private const val KEY_SHOULD_RUN = "proxyShouldRun"
        private const val KEY_MITM = "mitmChecked"
        private const val KEY_NOTIF_ASKED = "notifPermissionAsked"
        /** Status-bar tok/s readout; on by default. */
        private const val KEY_STATUSBAR_RATE = "statusBarRate"
    }

    private fun prefs() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private lateinit var viewModel: ProxyViewModel
    private var setupScriptExpanded = false
    /** SAF picker target: the paste field of the open import dialog. */
    private var importPicker: ActivityResultLauncher<Intent>? = null
    private var notifPermissionLauncher: ActivityResultLauncher<String>? = null
    private var pendingImportUri: android.net.Uri? = null
    private var pendingImportName: String? = null
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

        // API 33+ needs a runtime grant before the foreground notification
        // is allowed into the drawer.
        notifPermissionLauncher = registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) {
                // Re-post so the drawer shows it immediately.
                viewModel.ensureRunning(
                    findViewById<TextInputEditText>(R.id.portInput)?.text?.toString()?.toIntOrNull() ?: 3128,
                    metricsEnabled = true, mitmEnabled = true
                )
            } else {
                Toast.makeText(
                    this,
                    "Notifications are blocked — you won't see the proxy status until allowed in Settings.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        // SAF import picker: no storage permission needed. We keep the URI
        // (not the text) so the dialog can show just the filename and the
        // blob never lands in a text field.
        importPicker = registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
        ) { res ->
            if (res.resultCode == RESULT_OK) {
                val uri = res.data?.data
                if (uri == null) {
                    Toast.makeText(this, "No file chosen", Toast.LENGTH_SHORT).show()
                    return@registerForActivityResult
                }
                try {
                    val head = contentResolver.openInputStream(uri)
                        ?.bufferedReader()?.use { it.readText() }?.trim().orEmpty()
                    if (!head.startsWith("npbk1:")) {
                        Toast.makeText(
                            this,
                            "Not a backup file (expected npbk1:…)",
                            Toast.LENGTH_LONG
                        ).show()
                        return@registerForActivityResult
                    }
                    // Stash + reopen pre-filled so the user just enters
                    // their password and hits Import.
                    pendingImportUri = uri
                    pendingImportName = displayName(uri)
                    showImportBackupDialog()
                } catch (e: Exception) {
                    Toast.makeText(this, "Read failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }

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
        // Metrics + Decrypt-HTTPS are always on (no toggles by design).

        // Auto-start: ensure the proxy is running on app start unless the
        // user explicitly stopped it (Stop persists the opt-out).
        if (savedInstanceState == null && prefs().getBoolean(KEY_SHOULD_RUN, true)) {
            val p = portInput.text.toString().toIntOrNull() ?: 3128
            viewModel.ensureRunning(p, metricsEnabled = true, mitmEnabled = true)
        }

        // The "proxy is running" notification is the only way to see the
        // port/IP without opening the app, so ask for the runtime grant
        // once. Skipped when already answered (or below API 33).
        ensureNotificationPermission()
        // Status-bar tok/s toggle (default on).
        findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.statusBarRateSwitch)?.let { sw ->
            sw.isChecked = prefs().getBoolean(KEY_STATUSBAR_RATE, true)
            sw.setOnCheckedChangeListener { _, checked ->
                prefs().edit().putBoolean(KEY_STATUSBAR_RATE, checked).apply()
                // Re-post so the change shows without waiting for a tick.
                viewModel.refreshNotification()
            }
        }

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
            val port = portInput.text.toString().toIntOrNull() ?: 3128

            if (viewModel.isRunning.value == true) {
                prefs().edit().putBoolean(KEY_SHOULD_RUN, false).apply()
                viewModel.stopProxy()
            } else {
                prefs().edit().putBoolean(KEY_SHOULD_RUN, true).apply()
                if (!MitmCa.caCertFile(this).exists()) {
                    Toast.makeText(
                        this,
                        "MITM CA unavailable — starting opaque",
                        Toast.LENGTH_LONG
                    ).show()
                }
                viewModel.startProxy(port, metricsEnabled = true, mitmEnabled = true)
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
        findViewById<MaterialButton>(R.id.addKeyButton)?.setOnClickListener { showAddKeyDialog() }
        findViewById<MaterialButton>(R.id.exportBackupButton)?.setOnClickListener { showExportBackupDialog() }
        findViewById<MaterialButton>(R.id.importBackupButton)?.setOnClickListener { showImportBackupDialog() }
        findViewById<MaterialButton>(R.id.exportCaButton)?.setOnClickListener { shareMitmCa() }
    }

    private fun refreshProvidersSummary() {
        findViewById<TextView>(R.id.providersSummaryText)?.text =
            ProviderBroker.store(this).summary()
    }

    /**
     * Ask for POST_NOTIFICATIONS (API 33+) if it has not been decided yet.
     * Without it the foreground notification exists but never reaches the
     * drawer, which reads as "the proxy is not running".
     */
    private fun ensureNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT < 33) return
        val perm = android.Manifest.permission.POST_NOTIFICATIONS
        if (checkSelfPermission(perm) == android.content.pm.PackageManager.PERMISSION_GRANTED) return
        if (prefs().getBoolean(KEY_NOTIF_ASKED, false)) return
        prefs().edit().putBoolean(KEY_NOTIF_ASKED, true).apply()
        (notifPermissionLauncher?.launch(perm)
            ?: android.util.Log.w("NetworkProxy", "notification permission launcher unavailable"))
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

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
        // Well-known providers are auto-seeded on store load, so the picker
        // is never empty — no manual Seed step.
        val store = ProviderBroker.store(this)
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

    /** Export dialog: password → save file to Downloads (+ optional share). */
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
            .setPositiveButton("Save to Downloads") { _, _ ->
                try {
                    val backup = CredentialVault.exportBackup(this, pw.text.toString())
                    val where = saveBackupToDownloads(CredentialVault.backupFilename(), backup)
                    Toast.makeText(this, "Saved: $where", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
            .setNeutralButton("Share") { _, _ ->
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

    /**
     * Write [content] as [name] into Downloads. MediaStore on API 29+
     * (no permission); legacy direct write below that (needs
     * WRITE_EXTERNAL_STORAGE). Returns the display path for the toast.
     */
    private fun saveBackupToDownloads(name: String, content: String): String {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
                put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(android.provider.MediaStore.Downloads.RELATIVE_PATH,
                    android.os.Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = contentResolver.insert(
                android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
            ) ?: throw IllegalStateException("MediaStore refused the file")
            contentResolver.openOutputStream(uri)?.use {
                it.write(content.toByteArray(Charsets.UTF_8))
            } ?: throw IllegalStateException("cannot open Downloads file")
            return "Downloads/$name"
        }
        val dir = android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_DOWNLOADS
        )
        val f = java.io.File(dir, name)
        f.writeText(content)
        return f.absolutePath
    }

    /** Import dialog: load backup file (or paste) + password → validate → seal. */
    private fun showImportBackupDialog() {
        val picked = pendingImportUri
        val pickedName = pendingImportName
        val pw = textInput("Backup password", secret = true)
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }
        // File-loaded backup: show ONLY the filename. The 2 KB+ blob used to
        // flood the field, which pushed the password box out of reach and
        // caused "Import failed" (password typed into the blob instead).
        var blob: android.widget.EditText? = null
        if (picked != null) {
            layout.addView(android.widget.TextView(this).apply {
                text = "\uD83D\uDCC1 $pickedName"
                textSize = 15f
                setPadding(0, 16, 0, 0)
            })
            layout.addView(android.widget.TextView(this).apply {
                text = "Enter the password this backup was exported with."
                textSize = 12f
                setPadding(0, 4, 0, 0)
            })
        } else {
            blob = textInput("Paste backup (npbk1:…)")
            layout.addView(blob)
        }
        layout.addView(pw)

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Import encrypted backup")
            .setView(layout)
            .setPositiveButton("Import") { _, _ ->
                try {
                    val text = blob?.text?.toString()
                        ?: contentResolver.openInputStream(picked!!)
                            ?.bufferedReader()?.use { it.readText() }
                            ?: throw IllegalStateException("cannot read $pickedName")
                    CredentialVault.importBackup(this, text, pw.text.toString())
                    ProviderBroker.invalidate()
                    refreshProvidersSummary()
                    val from = pickedName?.let { " from $it" } ?: ""
                    Toast.makeText(this, "Backup imported$from", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
            .setNeutralButton("Load file…") { _, _ ->
                // Dialog buttons dismiss on tap: the picker result
                // reopens this dialog pre-filled (see picker callback).
                openBackupPicker()
            }
            .setNegativeButton("Cancel", null)
            // Also clear on back-press / outside tap, not just Cancel:
            // otherwise the next Import dialog claims a stale file whose
            // transient URI grant may already be gone.
            .setOnDismissListener {
                pendingImportUri = null
                pendingImportName = null
            }
            .show()
    }

    /** Human filename for a picked document URI ("…-1052.txt"). */
    private fun displayName(uri: android.net.Uri): String {
        try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (i >= 0 && c.moveToFirst()) {
                    c.getString(i)?.let { return it }
                }
            }
        } catch (e: Exception) {
            // fall through to the last path segment
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "backup.txt"
    }

    /** SAF file picker for backup files (no storage permission needed). */
    private fun openBackupPicker() {
        try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "text/plain"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/plain", "*/*"))
            }
            importPicker?.launch(intent)
                ?: Toast.makeText(this, "Picker unavailable", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Picker failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
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
        // Tick the uptime next to Running (updateUI only runs on state flips).
        if (viewModel.isRunning.value == true && viewModel.lastError.value.isNullOrBlank()) {
            findViewById<TextView>(R.id.statusText)?.text =
                statusRunningLine(svc?.uptimeMs() ?: 0L)
        }
        val snap = ProxyMetrics.snapshot()
        val retries = snap.retryCounts.values.sum()
        findViewById<TextView>(R.id.requestsText)?.text = "Requests: $requests"
        findViewById<TextView>(R.id.bytesText)?.text = "Transferred: ${humanBytes(bytes)}"
        findViewById<TextView>(R.id.sessionsText)?.text =
            "Active Sessions: $active • health ${svc?.healthStatus() ?: "stopped"}"
        findViewById<TextView>(R.id.retriesText)?.text =
            if (snap.scenarioCounts.isEmpty()) "Retries: $retries"
            else "Retries: $retries (${snap.scenarioCounts.entries.joinToString { "${it.key}=${it.value}" }})"
        renderTokensTable()
        renderCacheChart()
        // Scrollable rate window: the view holds its pan offset; each
        // poll re-queries the window ending there (0 = live edge).
        // While a pan gesture is active the view owns its frame — pushing
        // poll data mid-drag would snap the bars out from under the finger.
        findViewById<TokenRateView>(R.id.rateChart)?.let { chart ->
            // The view re-queries on every pan so the graph updates
            // immediately; this poll only feeds the live edge.
            if (chart.sampleProvider == null) {
                chart.sampleProvider = { offSec ->
                    ProxyMetrics.rateHistory(
                        TokenRateView.WINDOW_SECS,
                        System.currentTimeMillis() - offSec * 1000
                    )
                }
            }
            if (!chart.isInteracting) {
                chart.setSamples(
                    ProxyMetrics.rateHistory(
                        TokenRateView.WINDOW_SECS,
                        System.currentTimeMillis() - chart.offsetSec * 1000
                    )
                )
            }
        }
        renderSessionsList(svc)
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

    /** Real table widget: header + per-host rows + TOTAL + footer line. */
    private fun renderTokensTable() {
        val table = findViewById<android.widget.TableLayout>(R.id.tokensTable) ?: return
        val footer = findViewById<TextView>(R.id.tokensFooterText)
        val rows = ProxyMetrics.tokenSummary(5)
        val tps = String.format(java.util.Locale.US, "%.1f", ProxyMetrics.outputTokensPerSecond())
        // Session average alongside the trailing window: trailing reads 0
        // whenever the stream has been idle >30s (correct but alarming);
        // avg climbs iff output tokens are actually being counted.
        val avg = String.format(java.util.Locale.US, "%.1f", ProxyMetrics.outputTokensAvg())
        table.removeAllViews()
        if (rows.isEmpty()) {
            table.visibility = View.GONE
            footer?.text = "Tokens: in 0 / out 0 @ $tps tok/s (avg $avg)"
            return
        }
        table.visibility = View.VISIBLE
        val violet = getColor(R.color.title_violet)
        val hint = getColor(R.color.hint_text)
        table.addView(tableRow(listOf("host", "in", "out", "cacheR", "cacheW"), header = true, violet = violet))
        table.addView(dividerRow())
        for (r in rows) {
            table.addView(
                tableRow(
                    listOf(
                        hostModelCell(r.host, r.model, hint),
                        StatsFormat.humanTokens(r.inTokens),
                        StatsFormat.humanTokens(r.outTokens),
                        StatsFormat.humanTokens(r.cacheRead),
                        StatsFormat.humanTokens(r.cacheWrite)
                    )
                )
            )
        }
        table.addView(dividerRow())
        table.addView(
            tableRow(
                listOf(
                    "TOTAL", StatsFormat.humanTokens(ProxyMetrics.inputTokens),
                    StatsFormat.humanTokens(ProxyMetrics.outputTokens),
                    StatsFormat.humanTokens(ProxyMetrics.cacheReadTokens),
                    StatsFormat.humanTokens(ProxyMetrics.cacheWriteTokens)
                ),
                bold = true
            )
        )
        footer?.text = "@ $tps tok/s (avg $avg)"
    }

    /** Cache-efficiency bars per host (models aggregated) plus TOTAL. */
    private fun renderCacheChart() {
        val chart = findViewById<android.widget.LinearLayout>(R.id.cacheChart) ?: return
        chart.removeAllViews()
        // Rows are per (host, model) so the model is visible, but cap the
        // rows per host: otherwise one provider serving six models would
        // fill every slot and hide every other host.
        val rows = ProxyMetrics.capPerHost(ProxyMetrics.tokenSummary(16), 2)
        if (rows.isEmpty()) {
            chart.visibility = View.GONE
            return
        }
        chart.visibility = View.VISIBLE
        val d = resources.displayMetrics.density
        val emerald = getColor(R.color.status_running)
        val track = getColor(R.color.outline)
        val violet = getColor(R.color.title_violet)
        val hint = getColor(R.color.hint_text)
        // One row per (host, model) like the table — the exact model is
        // always visible; hostnames wrap full-length, never truncated.
        for (r in rows) {
            chart.addView(chartRow(r.host, r.model, r.cacheRead, r.inTokens, d, emerald, track, violet, hint, bold = false))
        }
        chart.addView(
            chartRow(
                "TOTAL", "", ProxyMetrics.cacheReadTokens, ProxyMetrics.inputTokens,
                d, emerald, track, violet, hint, bold = true
            )
        )
    }

    private fun chartRow(
        host: String, model: String, cacheRead: Long, input: Long, d: Float,
        emerald: Int, track: Int, violet: Int, hint: Int, bold: Boolean
    ): android.widget.LinearLayout {
        val col = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(0, (4 * d).toInt(), 0, (4 * d).toInt())
        }
        // Full hostname, wraps freely — never truncated. Exact model on
        // a dimmed second line (blank for unattributed tunnels).
        val hostLabel = TextView(this).apply {
            text = host
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(violet)
        }
        col.addView(hostLabel)
        val modelLabel = TextView(this).apply {
            text = if (model.isNotBlank()) "$model  ${StatsFormat.cachePct(cacheRead, input)} cached"
            else "${StatsFormat.cachePct(cacheRead, input)} cached"
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(hint)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        col.addView(modelLabel)
        val bar = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            weightSum = 1f
        }
        val ratio = StatsFormat.cacheRatio(cacheRead, input).toFloat()
        val fill = View(this).apply {
            setBackgroundColor(emerald)
            layoutParams = android.widget.LinearLayout.LayoutParams(0, (10 * d).toInt(), ratio)
        }
        val rest = View(this).apply {
            setBackgroundColor(track)
            layoutParams = android.widget.LinearLayout.LayoutParams(0, (10 * d).toInt(), (1f - ratio).coerceAtLeast(0f))
        }
        // weights of exactly 0 drop the view; keep a hairline so the
        // track reads even at 0% / 100%.
        if (ratio <= 0f) fill.layoutParams = android.widget.LinearLayout.LayoutParams((2 * d).toInt(), (10 * d).toInt())
        if (ratio >= 1f) rest.layoutParams = android.widget.LinearLayout.LayoutParams(0, (10 * d).toInt(), 0f)
        bar.addView(fill)
        bar.addView(rest)
        col.addView(bar)
        return col
    }

    /** Key-backed sessions: title, key label @ provider/model, age,
     *  plus the latest routing event (429/roll/spill). */
    private fun renderSessionsList(svc: ProxyService?) {
        val list = findViewById<android.widget.LinearLayout>(R.id.sessionsList) ?: return
        list.removeAllViews()
        val all = svc?.sessionDetails() ?: emptyList()
        val sessions = all.take(20)
        if (sessions.isEmpty()) {
            list.visibility = View.GONE
            return
        }
        list.visibility = View.VISIBLE
        val now = System.currentTimeMillis()
        for (s in sessions) {
            val title = TextView(this).apply {
                text = s.title.ifBlank { "${s.providerId.ifBlank { s.host }}${s.model.ifBlank { "" }.let { if (it.isNotEmpty()) "/$it" else "" }}" }
                textSize = 12f
                typeface = android.graphics.Typeface.MONOSPACE
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            list.addView(title)
            val modelBit = if (s.model.isNotBlank()) "/${s.model}" else ""
            val detail = StringBuilder()
                .append("${s.keyLabel} @ ${s.providerId}$modelBit · ${StatsFormat.humanAge(s.startedMs, now)}")
            if (s.lastEvent.isNotBlank()) {
                detail.append("\n↳ ${s.lastEvent} · ${StatsFormat.humanAge(s.lastEventMs, now)} ago")
            }
            val sub = TextView(this).apply {
                text = detail.toString()
                textSize = 11f
                typeface = android.graphics.Typeface.MONOSPACE
                setTextColor(getColor(R.color.hint_text))
            }
            list.addView(sub)
        }
        val total = all.size
        if (total > sessions.size) {
            val more = TextView(this).apply {
                text = "+${total - sessions.size} more"
                textSize = 12f
                setTextColor(getColor(R.color.hint_text))
            }
            list.addView(more)
        }
    }
    /** Hairline rule between table sections (header / TOTAL). */
    private fun dividerRow(): android.widget.TableRow {
        val row = android.widget.TableRow(this)
        val d = resources.displayMetrics.density
        val v = View(this)
        val lp = android.widget.TableRow.LayoutParams(
            android.widget.TableRow.LayoutParams.MATCH_PARENT,
            (1 * d).coerceAtLeast(1f).toInt()
        )
        lp.span = 5
        v.layoutParams = lp
        v.setBackgroundColor(getColor(R.color.outline))
        val wrap = android.widget.TableRow.LayoutParams(
            android.widget.TableRow.LayoutParams.MATCH_PARENT,
            android.widget.TableRow.LayoutParams.WRAP_CONTENT
        )
        row.layoutParams = wrap
        row.setPadding(0, (3 * d).toInt(), 0, (3 * d).toInt())
        row.addView(v)
        return row
    }

    /**
     * Host cell with the ACTUAL upstream model on a dimmed second line
     * (post any spillover rewrite — never the harness-requested id).
     * Blank model renders host only (unattributed tunnels).
     */
    private fun hostModelCell(host: String, model: String, hint: Int): CharSequence {
        if (model.isBlank()) return host
        val text = "$host\n$model"
        return android.text.SpannableString(text).apply {
            setSpan(
                android.text.style.ForegroundColorSpan(hint),
                host.length + 1, text.length,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    private fun tableRow(cells: List<CharSequence>, header: Boolean = false, bold: Boolean = false, violet: Int = 0): android.widget.TableRow {
        val row = android.widget.TableRow(this)
        val d = resources.displayMetrics.density
        val vPad = (6 * d).toInt()
        cells.forEachIndexed { i, text ->
            val tv = TextView(this)
            tv.text = text
            tv.textSize = 12f
            tv.typeface = android.graphics.Typeface.MONOSPACE
            if (header || bold) tv.setTypeface(tv.typeface, android.graphics.Typeface.BOLD)
            if (header && violet != 0) tv.setTextColor(violet)
            tv.gravity = if (i == 0) android.view.Gravity.START else android.view.Gravity.END
            tv.setPadding(0, vPad, ((if (i == 0) 12 else 6) * d).toInt(), vPad)
            tv.ellipsize = android.text.TextUtils.TruncateAt.END
            tv.maxLines = 1
            row.addView(tv)
        }
        return row
    }

    private fun humanBytes(bytes: Long): String {
        var v = bytes.toDouble()
        val units = arrayOf("B", "KB", "MB", "GB")
        var u = 0
        while (v >= 1024 && u < units.size - 1) { v /= 1024; u++ }
        return if (u == 0) "${bytes} B" else String.format("%.1f %s", v, units[u])
    }

    /** "Running • up 3h12m" (uptime omitted when unknown). Pure formatting. */
    fun statusRunningLine(uptimeMs: Long): String {
        if (uptimeMs <= 0) return getString(R.string.status_running)
        val m = uptimeMs / 60_000
        val up = if (m < 60) "${m}m" else "${m / 60}h${m % 60}m"
        return "${getString(R.string.status_running)} • up $up"
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
            // Uptime ticks via refreshStats (updateUI only runs on state flips).
            statusText.text = statusRunningLine(viewModel.proxyService.value?.uptimeMs() ?: 0L)
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