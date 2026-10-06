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
        private val PREFS = ProxyPrefs.NAME
        private val KEY_SHOULD_RUN = ProxyPrefs.SHOULD_RUN
        private const val KEY_NOTIF_ASKED = "notifPermissionAsked"
        /** Verbose response payload logging (ProxyService reads the same key). */
        const val VERBOSE_LOGGING_PREF = "verbose_logging"
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
                    PROXY_PORT, metricsEnabled = true, mitmEnabled = true
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
        // Metrics + Decrypt-HTTPS are always on (no toggles by design).

        // Verbose response logging: opt-in capture of response payloads
        // into the in-memory ResponseLog ring (ProxyService reads the
        // same pref). Restored from the pref so the checkbox survives
        // restarts; the proxy picks the value up per request.
        val verboseLoggingCheckbox = findViewById<MaterialCheckBox>(R.id.verboseLoggingCheckbox)
        verboseLoggingCheckbox.isChecked = prefs().getBoolean(VERBOSE_LOGGING_PREF, false)
        verboseLoggingCheckbox.setOnCheckedChangeListener { _, isChecked ->
            prefs().edit().putBoolean(VERBOSE_LOGGING_PREF, isChecked).apply()
        }

        // Auto-start: ensure the proxy is running on app start unless the
        // user explicitly stopped it (Stop persists the opt-out).
        if (savedInstanceState == null && prefs().getBoolean(KEY_SHOULD_RUN, true)) {
            viewModel.ensureRunning(PROXY_PORT, metricsEnabled = true, mitmEnabled = true)
        }

        // The "proxy is running" notification is the only way to see the
        // port/IP without opening the app, so ask for the runtime grant
        // once. Skipped when already answered (or below API 33).
        ensureNotificationPermission()

        // Static now that there is no port field to re-render from.
        setupScriptText.text = SetupScript.build(this)
        cleanupScriptText.text = SetupScript.cleanup()

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
                viewModel.startProxy(PROXY_PORT, metricsEnabled = true, mitmEnabled = true)
            }
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
        // Scrollable rate window: the view holds its pan offset; each
        // poll re-queries the window ending there (0 = live edge).
        // While a pan gesture is active the view owns its frame — pushing
        // poll data mid-drag would snap the bars out from under the finger.
        findViewById<TokenRateView>(R.id.rateChart)?.let { chart ->
            // The view re-queries on every pan so the graph updates
            // immediately; this poll only feeds the live edge.
            if (chart.sampleProvider == null) {
                chart.sampleProvider = { offSec, endMs ->
                    ProxyMetrics.rateHistory(TokenRateView.WINDOW_SECS, endMs)
                }
            }
            if (!chart.isInteracting) {
                // snap = false: the poll keeps the 350ms ease so bars
                // settle into new data instead of jumping every 2s. The
                // view's own pan frames snap (no animator) so the drag
                // tracks the finger rather than restarting the ease on
                // every pixel. One timestamp for the window, same helper
                // the view uses, so the two can't diverge.
                chart.setSamples(
                    ProxyMetrics.rateHistory(
                        TokenRateView.WINDOW_SECS,
                        TokenRateView.windowEndMs(
                            System.currentTimeMillis(), chart.offsetSec
                        )
                    ),
                    snap = false
                )
            }
        }
        renderRateHeading()
        renderSessionsList(svc)
        renderResponseLog()
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

    /**
     * Group 1 of 2 — the rate heading. The chart below it is this group's
     * body, and the rate / average / peak numbers are stated HERE and
     * nowhere else: the token table used to repeat the rate in a footer,
     * and that footer is gone (see [renderTokensTable]).
     */
    private fun renderRateHeading() {
        val samples = ProxyMetrics.rateHistory(
            TokenRateView.WINDOW_SECS,
            TokenRateView.windowEndMs(System.currentTimeMillis(), 0)
        )
        findViewById<TextView>(R.id.rateTitleText)?.text = StatsConsolidation.rateHeading(
            ProxyMetrics.outputTokensPerSecond(),
            ProxyMetrics.outputTokensAvg(),
            samples.maxOrNull() ?: 0L,
            TokenRateView.WINDOW_SECS
        )
    }

    /**
     * Group 2 of 2 — tokens: input, output, cache read, cache write, plus
     * a derived `reuse %`. Exactly one place shows each number:
     *
     *  - `cacheR` is the only place cache reuse is counted. The old
     *    "Cache efficiency" section (percentage + bar, with its own TOTAL)
     *    restated the same tokens a second time and has been deleted; the
     *    ratio now lives in the `reuse %` column next to its inputs.
     *  - the TOTAL row is dropped when a single data row would repeat it
     *    verbatim — [StatsConsolidation.shouldShowTotal].
     *  - the host is a spanned heading above its models, full length and
     *    wrapping: as a table column it was ellipsized to `opencode.ai…`
     *    and disagreed with the (now deleted) cache section, which showed
     *    it in full. Each model row keeps its own numbers, so the row
     *    reads on its own.
     *  - the trailing rate is NOT restated here (Group 1 owns it).
     */
    private fun renderTokensTable() {
        val table = findViewById<android.widget.TableLayout>(R.id.tokensTable) ?: return
        table.removeAllViews()
        val violet = getColor(R.color.title_violet)
        val hint = getColor(R.color.hint_text)
        // Capped per host: one provider serving six models must not fill
        // the table and hide every other host (the rule the deleted cache
        // section enforced). The TOTAL row, when shown, still carries the
        // global tallies.
        val rows = ProxyMetrics.capPerHost(ProxyMetrics.tokenSummary(8), 3)
        table.visibility = View.VISIBLE
        if (rows.isEmpty()) {
            // Spanned so it can't be ellipsized: the pre-consolidation
            // empty state ("in 0 / out 0") must survive somewhere.
            table.addView(
                hostHeadingRow("no traffic yet · in 0 · out 0 · cacheR 0 · cacheW 0", hint)
            )
            return
        }
        table.addView(
            tableRow(
                listOf("host / model", "in", "out", "cacheR", "cacheW", "reuse %"),
                header = true, violet = violet
            )
        )
        table.addView(dividerRow(TOKEN_COLUMNS))
        for (group in StatsConsolidation.groupByHost(rows)) {
            table.addView(hostHeadingRow(group.host, violet))
            for (r in group.rows) {
                table.addView(
                    tableRow(
                        listOf(
                            modelCell(r.model, hint),
                            StatsFormat.humanTokens(r.inTokens),
                            StatsFormat.humanTokens(r.outTokens),
                            StatsFormat.humanTokens(r.cacheRead),
                            StatsFormat.humanTokens(r.cacheWrite),
                            StatsConsolidation.reusePct(r.inTokens, r.cacheRead)
                        )
                    )
                )
            }
        }
        if (StatsConsolidation.shouldShowTotal(rows.size)) {
            table.addView(dividerRow(TOKEN_COLUMNS))
            table.addView(
                tableRow(
                    listOf(
                        "TOTAL",
                        StatsFormat.humanTokens(ProxyMetrics.inputTokens),
                        StatsFormat.humanTokens(ProxyMetrics.outputTokens),
                        StatsFormat.humanTokens(ProxyMetrics.cacheReadTokens),
                        StatsFormat.humanTokens(ProxyMetrics.cacheWriteTokens),
                        StatsConsolidation.reusePct(
                            ProxyMetrics.inputTokens, ProxyMetrics.cacheReadTokens
                        )
                    ),
                    bold = true
                )
            )
        }
    }

    /**
     * Spanned host heading: one cell across the whole table so the full
     * hostname wraps instead of being ellipsized. Never truncated.
     */
    private fun hostHeadingRow(host: String, violet: Int): android.widget.TableRow {
        val row = android.widget.TableRow(this)
        val d = resources.displayMetrics.density
        val lp = android.widget.TableRow.LayoutParams(
            android.widget.TableRow.LayoutParams.MATCH_PARENT,
            android.widget.TableRow.LayoutParams.WRAP_CONTENT
        )
        lp.span = TOKEN_COLUMNS
        val tv = TextView(this).apply {
            text = host
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(violet)
            gravity = android.view.Gravity.START
            setPadding(0, (6 * d).toInt(), 0, (2 * d).toInt())
        }
        row.addView(tv, lp)
        return row
    }

    /** Model cell of a grouped row (the host is the heading above it). */
    private fun modelCell(model: String, hint: Int): CharSequence {
        val text = if (model.isNotBlank()) model else "— (unattributed)"
        return if (model.isNotBlank()) text else dimmed(text, hint)
    }

    /** Dimmed copy of [text] (empty-state cells, unattributed models). */
    private fun dimmed(text: String, color: Int): CharSequence =
        android.text.SpannableString(text).apply {
            setSpan(
                android.text.style.ForegroundColorSpan(color),
                0, text.length,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

    /**
     * Sessions: one row per live session, name first —
     *
     *   Oc proxy            <- client name (header / body metadata), else
     *                           the harvested id, else `(client key) @ host`
     *   Fix my json please  <- description: first user turn, capped
     *   msg_01ABC · 12.3K tok · 43s · (client key) @ zen
     *
     * The name comes from [SessionTracker.displayName], whose precedence
     * is client name → harvested remote id → `(client key) @ host`, so a
     * client that sends no name degrades to the id it DOES share with the
     * CLI agent's logs instead of the identical "(client key)" every row
     * used to show. The id is on the dim third line — available for
     * correlating with an agent run, never dominant.
     */
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
        val hint = getColor(R.color.hint_text)
        for (s in sessions) {
            val name = TextView(this).apply {
                text = s.displayName
                textSize = 13f
                typeface = android.graphics.Typeface.MONOSPACE
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            list.addView(name)
            val desc = TextView(this).apply {
                text = s.description
                textSize = 12f
                typeface = android.graphics.Typeface.MONOSPACE
                setTextColor(hint)
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            list.addView(desc)
            val meta = StringBuilder()
            // The id is echoed on this dim line ONLY when it is not already
            // the headline (unnamed sessions show the id as their name).
            if (s.clientName.isNotBlank() && s.remoteId.isNotBlank()) {
                meta.append(s.remoteId).append(" · ")
            }
            meta.append(StatsFormat.humanTokens(s.totalTokens())).append(" tok")
            meta.append(" · ").append(StatsFormat.humanAge(s.startedMs, now))
            // The model is only spelled out when the description above is
            // not already the model (describe() falls back to it).
            if (s.model.isNotBlank() && s.description != s.model) {
                meta.append(" · ").append(s.model)
            }
            val attribution = StringBuilder(s.keyLabel.ifBlank { "(client key)" })
            if (s.providerId.isNotBlank() && s.providerId != s.model) {
                attribution.append(" @ ").append(s.providerId)
            }
            meta.append(" · ").append(attribution)
            if (s.lastEvent.isNotBlank()) {
                meta.append("\n\u21b3 ${s.lastEvent} · ${StatsFormat.humanAge(s.lastEventMs, now)} ago")
            }
            val sub = TextView(this).apply {
                text = meta.toString()
                textSize = 11f
                typeface = android.graphics.Typeface.MONOSPACE
                setTextColor(hint)
            }
            list.addView(sub)
        }
        val total = all.size
        if (total > sessions.size) {
            val more = TextView(this).apply {
                text = "+${total - sessions.size} more"
                textSize = 12f
                setTextColor(hint)
            }
            list.addView(more)
        }
    }

    /**
     * Verbose response log: one tappable row per retained payload, newest
     * first — host, content type, and the 200-char preview. Hidden
     * entirely while the log is empty (toggle off, or no JSON/SSE
     * responses captured yet). Tapping a row opens the full body in a
     * scrollable dialog.
     */
    private fun renderResponseLog() {
        val list = findViewById<android.widget.LinearLayout>(R.id.responseLogList) ?: return
        list.removeAllViews()
        val entries = ResponseLog.snapshot()
        if (entries.isEmpty()) {
            list.visibility = View.GONE
            return
        }
        list.visibility = View.VISIBLE
        val hint = getColor(R.color.hint_text)
        for (e in entries) {
            val row = TextView(this).apply {
                text = buildString {
                    append(e.host)
                    if (e.contentType.isNotBlank()) append(" · ${e.contentType}")
                    append(" · ${StatsFormat.humanAge(e.timestampMs, System.currentTimeMillis())}\n")
                    append(e.preview())
                }
                textSize = 11f
                typeface = android.graphics.Typeface.MONOSPACE
                setTextColor(hint)
                isClickable = true
                setOnClickListener { showResponseBody(e) }
            }
            list.addView(row)
        }
    }

    /** Full body of one response-log entry in a scrollable dialog. */
    private fun showResponseBody(entry: ResponseLog.ResponseEntry) {
        val tv = TextView(this).apply {
            text = String(entry.body, Charsets.UTF_8)
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(48, 24, 48, 24)
        }
        val scroll = android.widget.ScrollView(this).apply { addView(tv) }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(
                entry.host.ifBlank { "response" } +
                    if (entry.contentType.isNotBlank()) " — ${entry.contentType}" else ""
            )
            .setView(scroll)
            .setPositiveButton("Close", null)
            .show()
    }

    /** Hairline rule between table sections (header / TOTAL). */
    private fun dividerRow(span: Int = TOKEN_COLUMNS): android.widget.TableRow {
        val row = android.widget.TableRow(this)
        val d = resources.displayMetrics.density
        val v = View(this)
        val lp = android.widget.TableRow.LayoutParams(
            android.widget.TableRow.LayoutParams.MATCH_PARENT,
            (1 * d).coerceAtLeast(1f).toInt()
        )
        lp.span = span
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
            portText.text = getString(R.string.port_bind_failed, PROXY_PORT)
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
            portText.text = if (lan.isNotBlank()) "127.0.0.1:$PROXY_PORT • LAN $lan:$PROXY_PORT"
                else "127.0.0.1:$PROXY_PORT"
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
/** Columns of the consolidated Tokens group (Group 2 of 2). */
private const val TOKEN_COLUMNS = 6

/**
 * Pure, JVM-testable half of the statistics consolidation. The rendering
 * (TableLayout rows, chart, session list) is Android-only and cannot be
 * unit-tested here, so the rules that decide *what* is rendered live here:
 *
 *  - [shouldShowTotal] — the TOTAL-vs-single-row dedupe rule;
 *  - [reuseRatio] / [reusePct] — the derived `reuse %` column, replacing
 *    the deleted "Cache efficiency" percentage-and-bar section;
 *  - [groupByHost] — per-(host, model) rows collected under one full,
 *    untruncated host heading;
 *  - [rateHeading] — Group 1's heading, the single place the rate is
 *    stated (the table no longer repeats it in a footer).
 */
object StatsConsolidation {
    /**
     * A TOTAL row is only worth its own line when there is more than one
     * data row: with a single row it would repeat that row's numbers
     * verbatim, which is exactly the duplication this consolidation
     * removes. Zero rows render as an explicit empty state instead.
     */
    fun shouldShowTotal(rowCount: Int): Boolean = rowCount > 1

    /**
     * Cache reuse as a share of the input a request actually paid for:
     *
     *     reuse % = cacheRead / (input + cacheRead)
     *
     * The denominator is the total prompt the provider was asked to
     * attend to — the freshly billed [input] tokens plus the ones served
     * from cache — so 100% means "nothing was re-billed". Not the old
     * `cacheRead / input` ratio, which is clamped at 1.0 and therefore
     * could not express a fully cached turn. No prompt at all → 0.
     */
    fun reuseRatio(input: Long, cacheRead: Long): Double {
        val inTokens = input.coerceAtLeast(0)
        val cached = cacheRead.coerceAtLeast(0)
        val total = inTokens + cached
        if (total <= 0L) return 0.0
        return (cached.toDouble() / total.toDouble()).coerceIn(0.0, 1.0)
    }

    /** "48.5%" label for [reuseRatio]. */
    fun reusePct(input: Long, cacheRead: Long): String =
        String.format(java.util.Locale.US, "%.1f%%", 100.0 * reuseRatio(input, cacheRead))

    /** One host and the (host, model) rows beneath it, in input order. */
    data class HostGroup(val host: String, val rows: List<ProxyMetrics.TokenRow>)

    /**
     * Group per-(host, model) rows under a single host entry, keeping
     * first-seen host order and per-host row order. The host is rendered
     * as a spanned heading rather than repeated on every row, so a host
     * with four models states its name once — in full.
     */
    fun groupByHost(rows: List<ProxyMetrics.TokenRow>): List<HostGroup> {
        val out = ArrayList<HostGroup>()
        val index = HashMap<String, Int>()
        for (r in rows) {
            val at = index[r.host]
            if (at != null) {
                out[at] = out[at].copy(rows = out[at].rows + r)
            } else {
                index[r.host] = out.size
                out.add(HostGroup(r.host, listOf(r)))
            }
        }
        return out
    }

    /**
     * Group 1's heading: the ONLY place the output rate is stated. `avg`
     * is the session average (it climbs whenever output tokens are really
     * counted, which the trailing window alone does not show once the
     * stream goes idle); `peak` is the largest trailing second in the
     * same window the chart plots.
     */
    fun rateHeading(tokPerSec: Double, avg: Double, peak: Long, windowSecs: Int): String =
        String.format(
            java.util.Locale.US,
            "Output rate · %.0f tok/s · avg %.1f · peak %d (trailing %ds)",
            tokPerSec, avg, peak, windowSecs
        )
}
