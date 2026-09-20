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
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.checkbox.MaterialCheckBox

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
        val setupScriptScrollView = findViewById<android.widget.HorizontalScrollView>(R.id.setupScriptScrollView)
        val setupScriptExpandIcon = findViewById<ImageView>(R.id.setupScriptExpandIcon)
        val copyCleanupButton = findViewById<MaterialButton>(R.id.copyCleanupButton)
        val cleanupScriptText = findViewById<TextView>(R.id.cleanupScriptText)
        val cleanupScriptScrollView = findViewById<android.widget.HorizontalScrollView>(R.id.cleanupScriptScrollView)
        val portInput = findViewById<TextInputEditText>(R.id.portInput)
        val metricsCheck = findViewById<MaterialCheckBox>(R.id.metricsCheck)

        val refreshScript = {
            val p = portInput.text.toString().toIntOrNull() ?: 3128
            setupScriptText.text = SetupScript.build(this, p)
            cleanupScriptText.text = SetupScript.cleanup()
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

        // Header click expands/collapses
        val headerLayout = setupScriptExpandIcon.parent as? android.view.ViewGroup
        headerLayout?.setOnClickListener { toggleExpand() }
        setupScriptExpandIcon.setOnClickListener { toggleExpand() }

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

            if (viewModel.isRunning.value == true) {
                viewModel.stopProxy()
            } else {
                viewModel.startProxy(port, metricsEnabled)
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
            refreshStats()
            Toast.makeText(this, "Metrics cleared", Toast.LENGTH_SHORT).show()
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
        val portText = findViewById<TextView>(R.id.portText)
        val upstreamText = findViewById<TextView>(R.id.upstreamText)
        val portInput = findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.portInput)

        if (!error.isNullOrBlank()) {
            startStopButton.text = getString(R.string.start_proxy)
            startStopButton.icon = getDrawable(android.R.drawable.ic_media_play)
            statusText.text = error
            statusText.setTextColor(android.graphics.Color.YELLOW)
            portText.text = "Port ${portInput.text} — tap Start to retry"
        } else if (isRunning) {
            startStopButton.text = getString(R.string.stop_proxy)
            startStopButton.icon = getDrawable(android.R.drawable.ic_media_pause)
            statusText.text = getString(R.string.status_running)
            statusText.setTextColor(android.graphics.Color.GREEN)
        } else {
            startStopButton.text = getString(R.string.start_proxy)
            startStopButton.icon = getDrawable(android.R.drawable.ic_media_play)
            statusText.text = getString(R.string.status_stopped)
            statusText.setTextColor(android.graphics.Color.RED)
        }
    }
}