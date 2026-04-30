package com.smscleaner.app.fragment

import android.app.role.RoleManager
import android.os.Bundle
import android.view.View
import android.widget.CompoundButton
import android.widget.ScrollView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textview.MaterialTextView
import com.smscleaner.app.CleanerConfig
import com.smscleaner.app.CleanerViewModel
import com.smscleaner.app.DeleteOrder
import com.smscleaner.app.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class ManualCleanFragment : Fragment(R.layout.fragment_manual_clean) {

    private val viewModel: CleanerViewModel by viewModels()

    private var startDateMs: Long? = null
    private var endDateMs: Long? = null

    private val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    private val defaultSmsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val v = view ?: return@registerForActivityResult
        val roleManager = requireContext().getSystemService(RoleManager::class.java)
        val isDefault = roleManager.isRoleHeld(RoleManager.ROLE_SMS)
        v.findViewById<MaterialCardView>(R.id.cardDefaultSms)?.visibility = if (isDefault) View.GONE else View.VISIBLE
        v.findViewById<MaterialButton>(R.id.btnDryRun)?.isEnabled = isDefault
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val cardDefaultSms = view.findViewById<MaterialCardView>(R.id.cardDefaultSms)
        val btnSetDefault = view.findViewById<MaterialButton>(R.id.btnSetDefault)
        val btnStartDate = view.findViewById<MaterialButton>(R.id.btnStartDate)
        val btnEndDate = view.findViewById<MaterialButton>(R.id.btnEndDate)
        val btnClearStartDate = view.findViewById<MaterialButton>(R.id.btnClearStartDate)
        val btnClearEndDate = view.findViewById<MaterialButton>(R.id.btnClearEndDate)
        val cbSms = view.findViewById<MaterialCheckBox>(R.id.cbSms)
        val cbMmsMedia = view.findViewById<MaterialCheckBox>(R.id.cbMmsMedia)
        val cbMmsGroup = view.findViewById<MaterialCheckBox>(R.id.cbMmsGroup)
        val cbRcs = view.findViewById<MaterialCheckBox>(R.id.cbRcs)
        val etBatchSize = view.findViewById<TextInputEditText>(R.id.etBatchSize)
        val cbPerTypeBatch = view.findViewById<MaterialCheckBox>(R.id.cbPerTypeBatch)
        val layoutPerTypeBatch = view.findViewById<android.widget.LinearLayout>(R.id.layoutPerTypeBatch)
        val etBatchSizeMmsMedia = view.findViewById<TextInputEditText>(R.id.etBatchSizeMmsMedia)
        val etBatchSizeMmsGroup = view.findViewById<TextInputEditText>(R.id.etBatchSizeMmsGroup)
        val etDeleteChunkSize = view.findViewById<TextInputEditText>(R.id.etDeleteChunkSize)
        val etBatchDelay = view.findViewById<TextInputEditText>(R.id.etBatchDelay)
        val toggleDeleteOrder = view.findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(R.id.toggleDeleteOrder)
        val cbDebugLogging = view.findViewById<MaterialCheckBox>(R.id.cbDebugLogging)
        val btnDryRun = view.findViewById<MaterialButton>(R.id.btnDryRun)
        val btnRun = view.findViewById<MaterialButton>(R.id.btnRun)
        val btnStop = view.findViewById<MaterialButton>(R.id.btnStop)
        val progressBar = view.findViewById<LinearProgressIndicator>(R.id.progressBar)
        val tvLog = view.findViewById<MaterialTextView>(R.id.tvLog)
        val scrollLog = view.findViewById<ScrollView>(R.id.scrollLog)

        // Default SMS check
        fun checkDefault() {
            val roleManager = requireContext().getSystemService(RoleManager::class.java)
            val isDefault = roleManager.isRoleHeld(RoleManager.ROLE_SMS)
            cardDefaultSms.visibility = if (isDefault) View.GONE else View.VISIBLE
            btnDryRun.isEnabled = isDefault
        }

        btnSetDefault.setOnClickListener {
            val roleManager = requireContext().getSystemService(RoleManager::class.java)
            defaultSmsLauncher.launch(roleManager.createRequestRoleIntent(RoleManager.ROLE_SMS))
        }

        // Date pickers
        fun showDatePicker(isStart: Boolean) {
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
                viewModel.onSettingsChanged()
            }
            picker.show(childFragmentManager, "date_picker")
        }

        btnStartDate.setOnClickListener { showDatePicker(true) }
        btnEndDate.setOnClickListener { showDatePicker(false) }
        btnClearStartDate.setOnClickListener {
            startDateMs = null
            btnStartDate.text = getString(R.string.start_date)
            btnClearStartDate.visibility = View.GONE
            viewModel.onSettingsChanged()
        }
        btnClearEndDate.setOnClickListener {
            endDateMs = null
            btnEndDate.text = getString(R.string.end_date)
            btnClearEndDate.visibility = View.GONE
            viewModel.onSettingsChanged()
        }

        // Settings change listeners (invalidate dry run)
        val checkListener = CompoundButton.OnCheckedChangeListener { _, _ ->
            viewModel.onSettingsChanged()
        }
        cbSms.setOnCheckedChangeListener(checkListener)
        cbMmsMedia.setOnCheckedChangeListener(checkListener)
        cbMmsGroup.setOnCheckedChangeListener(checkListener)
        cbRcs.setOnCheckedChangeListener(checkListener)
        toggleDeleteOrder.addOnButtonCheckedListener { _, _, _ -> viewModel.onSettingsChanged() }

        cbPerTypeBatch.setOnCheckedChangeListener { _, checked ->
            layoutPerTypeBatch.visibility = if (checked) View.VISIBLE else View.GONE
        }

        // Build config
        fun buildConfig(): CleanerConfig? {
            if (startDateMs == null && endDateMs == null) {
                Toast.makeText(requireContext(), getString(R.string.date_range_required), Toast.LENGTH_SHORT).show()
                return null
            }
            if (!cbSms.isChecked && !cbMmsMedia.isChecked && !cbMmsGroup.isChecked && !cbRcs.isChecked) {
                Toast.makeText(requireContext(), getString(R.string.type_required), Toast.LENGTH_SHORT).show()
                return null
            }
            val batchSize = etBatchSize.text.toString().toIntOrNull() ?: 1000
            val batchSizeMmsMedia = if (cbPerTypeBatch.isChecked) etBatchSizeMmsMedia.text.toString().toIntOrNull() ?: 100 else batchSize
            val batchSizeMmsGroup = if (cbPerTypeBatch.isChecked) etBatchSizeMmsGroup.text.toString().toIntOrNull() ?: 500 else batchSize
            val deleteChunkSize = etDeleteChunkSize.text.toString().toIntOrNull() ?: 50
            val delayMs = etBatchDelay.text.toString().toLongOrNull() ?: 100L
            val deleteOrder = if (toggleDeleteOrder.checkedButtonId == R.id.btnNewestFirst) DeleteOrder.NEWEST_FIRST else DeleteOrder.OLDEST_FIRST

            return CleanerConfig(
                startDateMs = startDateMs, endDateMs = endDateMs,
                includeSms = cbSms.isChecked, includeMmsMedia = cbMmsMedia.isChecked,
                includeMmsGroup = cbMmsGroup.isChecked, includeRcs = cbRcs.isChecked,
                batchSize = batchSize.coerceAtLeast(1),
                batchSizeMmsMedia = batchSizeMmsMedia.coerceAtLeast(1),
                batchSizeMmsGroup = batchSizeMmsGroup.coerceAtLeast(1),
                deleteChunkSize = deleteChunkSize.coerceAtLeast(1),
                delayMs = delayMs.coerceAtLeast(0),
                dryRun = true, deleteOrder = deleteOrder,
                debugLogging = cbDebugLogging.isChecked
            )
        }

        btnDryRun.setOnClickListener { buildConfig()?.let { viewModel.startDryRun(it) } }
        btnRun.setOnClickListener {
            val config = buildConfig() ?: return@setOnClickListener
            val count = viewModel.lastDryRunCount.value ?: 0
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.confirm_delete_title)
                .setMessage(getString(R.string.confirm_delete_message, count))
                .setPositiveButton(R.string.confirm_delete_positive) { _, _ ->
                    viewModel.startClean(config)
                }
                .setNegativeButton(R.string.confirm_delete_negative, null)
                .show()
        }
        btnStop.setOnClickListener { viewModel.stop() }

        // Observe ViewModel
        viewModel.logText.observe(viewLifecycleOwner) { text ->
            tvLog.text = text
            scrollLog.post { scrollLog.scrollTo(0, tvLog.bottom) }
        }

        viewModel.isRunning.observe(viewLifecycleOwner) { running ->
            btnDryRun.isEnabled = !running && run {
                val rm = requireContext().getSystemService(RoleManager::class.java)
                rm.isRoleHeld(RoleManager.ROLE_SMS)
            }
            btnStop.isEnabled = running
            progressBar.visibility = if (running) View.VISIBLE else View.GONE
            btnRun.isEnabled = !running && viewModel.isDryRunComplete.value == true
        }

        viewModel.isRunButtonEnabled.observe(viewLifecycleOwner) { enabled ->
            if (viewModel.isRunning.value != true) btnRun.isEnabled = enabled
        }

        @Suppress("ClickableViewAccessibility")
        scrollLog.setOnTouchListener { v, _ ->
            v.parent.requestDisallowInterceptTouchEvent(true)
            false
        }

        checkDefault()
    }

    override fun onResume() {
        super.onResume()
        val cardDefaultSms = view?.findViewById<MaterialCardView>(R.id.cardDefaultSms)
        val btnDryRun = view?.findViewById<MaterialButton>(R.id.btnDryRun)
        val roleManager = requireContext().getSystemService(RoleManager::class.java)
        val isDefault = roleManager.isRoleHeld(RoleManager.ROLE_SMS)
        cardDefaultSms?.visibility = if (isDefault) View.GONE else View.VISIBLE
        if (viewModel.isRunning.value != true) btnDryRun?.isEnabled = isDefault
    }
}
