package com.smscleaner.app

import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.CompoundButton
import android.widget.ScrollView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textview.MaterialTextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class MainActivity : AppCompatActivity() {

    private lateinit var viewModel: CleanerViewModel

    private lateinit var cardDefaultSms: MaterialCardView
    private lateinit var btnSetDefault: MaterialButton
    private lateinit var btnStartDate: MaterialButton
    private lateinit var btnEndDate: MaterialButton
    private lateinit var btnClearStartDate: MaterialButton
    private lateinit var btnClearEndDate: MaterialButton
    private lateinit var cbSms: MaterialCheckBox
    private lateinit var cbMmsMedia: MaterialCheckBox
    private lateinit var cbMmsGroup: MaterialCheckBox
    private lateinit var cbRcs: MaterialCheckBox
    private lateinit var etBatchSize: TextInputEditText
    private lateinit var cbPerTypeBatch: MaterialCheckBox
    private lateinit var layoutPerTypeBatch: android.widget.LinearLayout
    private lateinit var etBatchSizeMmsMedia: TextInputEditText
    private lateinit var etBatchSizeMmsGroup: TextInputEditText
    private lateinit var etDeleteChunkSize: TextInputEditText
    private lateinit var etBatchDelay: TextInputEditText
    private lateinit var toggleDeleteOrder: com.google.android.material.button.MaterialButtonToggleGroup
    private lateinit var btnDryRun: MaterialButton
    private lateinit var btnRun: MaterialButton
    private lateinit var btnStop: MaterialButton
    private lateinit var btnTestActivity: MaterialButton
    private lateinit var progressBar: LinearProgressIndicator
    private lateinit var tvLog: MaterialTextView
    private lateinit var scrollLog: ScrollView

    private var startDateMs: Long? = null
    private var endDateMs: Long? = null
    private var suppressSettingsChange = false

    private val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    private val defaultSmsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        checkDefaultSmsStatus()
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            checkDefaultSmsStatus()
        } else {
            Toast.makeText(this, "Permissions required for SMS access", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.apply {
            statusBarColor = android.graphics.Color.BLACK
            navigationBarColor = android.graphics.Color.BLACK
        }
        setContentView(R.layout.activity_main)

        viewModel = ViewModelProvider(this)[CleanerViewModel::class.java]
        bindViews()
        setupListeners()
        observeViewModel()
        requestPermissions()

        @Suppress("ClickableViewAccessibility")
        scrollLog.setOnTouchListener { v, _ ->
            v.parent.requestDisallowInterceptTouchEvent(true)
            false
        }
    }

    override fun onResume() {
        super.onResume()
        checkDefaultSmsStatus()
    }

    private fun bindViews() {
        cardDefaultSms = findViewById(R.id.cardDefaultSms)
        btnSetDefault = findViewById(R.id.btnSetDefault)
        btnStartDate = findViewById(R.id.btnStartDate)
        btnEndDate = findViewById(R.id.btnEndDate)
        btnClearStartDate = findViewById(R.id.btnClearStartDate)
        btnClearEndDate = findViewById(R.id.btnClearEndDate)
        cbSms = findViewById(R.id.cbSms)
        cbMmsMedia = findViewById(R.id.cbMmsMedia)
        cbMmsGroup = findViewById(R.id.cbMmsGroup)
        cbRcs = findViewById(R.id.cbRcs)
        etBatchSize = findViewById(R.id.etBatchSize)
        cbPerTypeBatch = findViewById(R.id.cbPerTypeBatch)
        layoutPerTypeBatch = findViewById(R.id.layoutPerTypeBatch)
        etBatchSizeMmsMedia = findViewById(R.id.etBatchSizeMmsMedia)
        etBatchSizeMmsGroup = findViewById(R.id.etBatchSizeMmsGroup)
        etDeleteChunkSize = findViewById(R.id.etDeleteChunkSize)
        etBatchDelay = findViewById(R.id.etBatchDelay)
        toggleDeleteOrder = findViewById(R.id.toggleDeleteOrder)
        btnDryRun = findViewById(R.id.btnDryRun)
        btnRun = findViewById(R.id.btnRun)
        btnStop = findViewById(R.id.btnStop)
        btnTestActivity = findViewById(R.id.btnTestActivity)
        progressBar = findViewById(R.id.progressBar)
        tvLog = findViewById(R.id.tvLog)
        scrollLog = findViewById(R.id.scrollLog)
    }

    private fun setupListeners() {
        btnSetDefault.setOnClickListener { requestDefaultSms() }

        btnStartDate.setOnClickListener { showDatePicker(isStart = true) }
        btnEndDate.setOnClickListener { showDatePicker(isStart = false) }

        btnClearStartDate.setOnClickListener {
            startDateMs = null
            btnStartDate.text = getString(R.string.start_date)
            btnClearStartDate.visibility = View.GONE
            onSettingsChanged()
        }

        btnClearEndDate.setOnClickListener {
            endDateMs = null
            btnEndDate.text = getString(R.string.end_date)
            btnClearEndDate.visibility = View.GONE
            onSettingsChanged()
        }

        val checkListener = CompoundButton.OnCheckedChangeListener { _, _ ->
            if (!suppressSettingsChange) onSettingsChanged()
        }
        cbSms.setOnCheckedChangeListener(checkListener)
        cbMmsMedia.setOnCheckedChangeListener(checkListener)
        cbMmsGroup.setOnCheckedChangeListener(checkListener)
        cbRcs.setOnCheckedChangeListener(checkListener)

        cbPerTypeBatch.setOnCheckedChangeListener { _, checked ->
            layoutPerTypeBatch.visibility = if (checked) View.VISIBLE else View.GONE
        }

        toggleDeleteOrder.addOnButtonCheckedListener { _, _, _ ->
            if (!suppressSettingsChange) onSettingsChanged()
        }

        btnDryRun.setOnClickListener {
            val config = buildConfig() ?: return@setOnClickListener
            viewModel.startDryRun(config)
        }

        btnRun.setOnClickListener {
            val config = buildConfig() ?: return@setOnClickListener
            viewModel.startClean(config)
        }

        btnStop.setOnClickListener { viewModel.stop() }

        btnTestActivity.setOnClickListener {
            startActivity(Intent(this, TestActivity::class.java))
        }
    }

    private fun observeViewModel() {
        viewModel.logText.observe(this) { text ->
            tvLog.text = text
            scrollLog.post {
                scrollLog.scrollTo(0, tvLog.bottom)
            }
        }

        viewModel.isRunning.observe(this) { running ->
            btnDryRun.isEnabled = !running
            btnStop.isEnabled = running
            progressBar.visibility = if (running) View.VISIBLE else View.GONE
            if (!running) {
                btnRun.isEnabled = viewModel.isDryRunComplete.value == true
            } else {
                btnRun.isEnabled = false
            }
        }

        viewModel.isRunButtonEnabled.observe(this) { enabled ->
            if (viewModel.isRunning.value != true) {
                btnRun.isEnabled = enabled
            }
        }
    }

    private fun onSettingsChanged() {
        viewModel.onSettingsChanged()
    }

    private fun buildConfig(): CleanerConfig? {
        if (startDateMs == null && endDateMs == null) {
            Toast.makeText(this, getString(R.string.date_range_required), Toast.LENGTH_SHORT).show()
            return null
        }

        if (!cbSms.isChecked && !cbMmsMedia.isChecked && !cbMmsGroup.isChecked && !cbRcs.isChecked) {
            Toast.makeText(this, getString(R.string.type_required), Toast.LENGTH_SHORT).show()
            return null
        }

        val batchSize = etBatchSize.text.toString().toIntOrNull() ?: 1000
        val batchSizeMmsMedia = if (cbPerTypeBatch.isChecked)
            etBatchSizeMmsMedia.text.toString().toIntOrNull() ?: 100 else batchSize
        val batchSizeMmsGroup = if (cbPerTypeBatch.isChecked)
            etBatchSizeMmsGroup.text.toString().toIntOrNull() ?: 500 else batchSize
        val deleteChunkSize = etDeleteChunkSize.text.toString().toIntOrNull() ?: 50
        val delayMs = etBatchDelay.text.toString().toLongOrNull() ?: 100L
        val deleteOrder = if (toggleDeleteOrder.checkedButtonId == R.id.btnNewestFirst)
            DeleteOrder.NEWEST_FIRST else DeleteOrder.OLDEST_FIRST

        return CleanerConfig(
            startDateMs = startDateMs,
            endDateMs = endDateMs,
            includeSms = cbSms.isChecked,
            includeMmsMedia = cbMmsMedia.isChecked,
            includeMmsGroup = cbMmsGroup.isChecked,
            includeRcs = cbRcs.isChecked,
            batchSize = batchSize.coerceAtLeast(1),
            batchSizeMmsMedia = batchSizeMmsMedia.coerceAtLeast(1),
            batchSizeMmsGroup = batchSizeMmsGroup.coerceAtLeast(1),
            deleteChunkSize = deleteChunkSize.coerceAtLeast(1),
            delayMs = delayMs.coerceAtLeast(0),
            dryRun = true,
            deleteOrder = deleteOrder
        )
    }

    private fun showDatePicker(isStart: Boolean) {
        val title = if (isStart) getString(R.string.start_date) else getString(R.string.end_date)
        val picker = MaterialDatePicker.Builder.datePicker()
            .setTitleText(title)
            .apply {
                val current = if (isStart) startDateMs else endDateMs
                if (current != null) setSelection(current)
            }
            .build()

        picker.addOnPositiveButtonClickListener { selection ->
            if (isStart) {
                startDateMs = selection
                btnStartDate.text = "Start: ${dateFormat.format(Date(selection))}"
                btnClearStartDate.visibility = View.VISIBLE
            } else {
                endDateMs = selection + (24 * 60 * 60 * 1000 - 1)
                btnEndDate.text = "End: ${dateFormat.format(Date(selection))}"
                btnClearEndDate.visibility = View.VISIBLE
            }
            onSettingsChanged()
        }

        picker.show(supportFragmentManager, "date_picker")
    }

    private fun checkDefaultSmsStatus() {
        val roleManager = getSystemService(RoleManager::class.java)
        val isDefault = roleManager.isRoleHeld(RoleManager.ROLE_SMS)
        cardDefaultSms.visibility = if (isDefault) View.GONE else View.VISIBLE
        btnDryRun.isEnabled = isDefault
    }

    private fun requestDefaultSms() {
        val roleManager = getSystemService(RoleManager::class.java)
        val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_SMS)
        defaultSmsLauncher.launch(intent)
    }

    private fun requestPermissions() {
        val needed = mutableListOf<String>()
        val perms = arrayOf(
            android.Manifest.permission.READ_SMS,
            android.Manifest.permission.RECEIVE_SMS,
            android.Manifest.permission.SEND_SMS,
            android.Manifest.permission.READ_CONTACTS,
            android.Manifest.permission.RECEIVE_MMS,
            android.Manifest.permission.RECEIVE_WAP_PUSH
        )
        for (p in perms) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                needed.add(p)
            }
        }
        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }
}
