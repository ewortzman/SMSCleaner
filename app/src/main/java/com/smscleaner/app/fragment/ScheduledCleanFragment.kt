package com.smscleaner.app.fragment

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textview.MaterialTextView
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import com.smscleaner.app.R
import com.smscleaner.app.model.RecurrenceType
import com.smscleaner.app.model.ScheduleConfig
import com.smscleaner.app.schedule.ScheduleManager
import java.util.Calendar

class ScheduledCleanFragment : Fragment(R.layout.fragment_scheduled_clean) {

    private lateinit var scheduleManager: ScheduleManager
    private var selectedHour = 2
    private var selectedMinute = 0

    private val daysOfWeek = arrayOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")
    private val daysOfWeekValues = intArrayOf(
        Calendar.SUNDAY, Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY,
        Calendar.THURSDAY, Calendar.FRIDAY, Calendar.SATURDAY
    )
    private val frequencies = arrayOf("Weekly", "Every 2 Weeks", "Monthly")
    private val thresholds = arrayOf("6 Months", "1 Year", "18 Months", "2 Years", "3 Years", "5 Years")
    private val thresholdMonths = intArrayOf(6, 12, 18, 24, 36, 60)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        scheduleManager = ScheduleManager(requireContext())

        val switchEnabled = view.findViewById<MaterialSwitch>(R.id.switchEnabled)
        val tvStatus = view.findViewById<MaterialTextView>(R.id.tvScheduleStatus)
        val spinnerFrequency = view.findViewById<MaterialAutoCompleteTextView>(R.id.spinnerFrequency)
        val tilDayOfWeek = view.findViewById<TextInputLayout>(R.id.tilDayOfWeek)
        val spinnerDayOfWeek = view.findViewById<MaterialAutoCompleteTextView>(R.id.spinnerDayOfWeek)
        val tilDayOfMonth = view.findViewById<TextInputLayout>(R.id.tilDayOfMonth)
        val spinnerDayOfMonth = view.findViewById<MaterialAutoCompleteTextView>(R.id.spinnerDayOfMonth)
        val btnTime = view.findViewById<MaterialButton>(R.id.btnTime)
        val spinnerThreshold = view.findViewById<MaterialAutoCompleteTextView>(R.id.spinnerThreshold)
        val cbSms = view.findViewById<MaterialCheckBox>(R.id.cbSchedSms)
        val cbMmsMedia = view.findViewById<MaterialCheckBox>(R.id.cbSchedMmsMedia)
        val cbMmsGroup = view.findViewById<MaterialCheckBox>(R.id.cbSchedMmsGroup)
        val cbRcs = view.findViewById<MaterialCheckBox>(R.id.cbSchedRcs)
        val etBatchSize = view.findViewById<TextInputEditText>(R.id.etSchedBatchSize)
        val etBatchSizeMmsMedia = view.findViewById<TextInputEditText>(R.id.etSchedBatchSizeMmsMedia)
        val etBatchSizeMmsGroup = view.findViewById<TextInputEditText>(R.id.etSchedBatchSizeMmsGroup)
        val etChunkSize = view.findViewById<TextInputEditText>(R.id.etSchedChunkSize)
        val etDelay = view.findViewById<TextInputEditText>(R.id.etSchedDelay)
        val cbRequiresCharging = view.findViewById<MaterialCheckBox>(R.id.cbRequiresCharging)
        val btnSave = view.findViewById<MaterialButton>(R.id.btnSaveSchedule)

        // Setup dropdowns
        spinnerFrequency.setAdapter(ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, frequencies))
        spinnerDayOfWeek.setAdapter(ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, daysOfWeek))
        spinnerDayOfMonth.setAdapter(ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, (1..28).map { it.toString() }.toTypedArray()))
        spinnerThreshold.setAdapter(ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, thresholds))

        // Frequency change shows/hides day selectors
        spinnerFrequency.setOnItemClickListener { _, _, position, _ ->
            when (position) {
                0, 1 -> { tilDayOfWeek.visibility = View.VISIBLE; tilDayOfMonth.visibility = View.GONE }
                2 -> { tilDayOfWeek.visibility = View.GONE; tilDayOfMonth.visibility = View.VISIBLE }
            }
        }

        // Time picker
        btnTime.setOnClickListener {
            val picker = MaterialTimePicker.Builder()
                .setTimeFormat(TimeFormat.CLOCK_12H)
                .setHour(selectedHour)
                .setMinute(selectedMinute)
                .setTitleText("Notification Time")
                .build()
            picker.addOnPositiveButtonClickListener {
                selectedHour = picker.hour
                selectedMinute = picker.minute
                btnTime.text = "Time: ${formatTime(selectedHour, selectedMinute)}"
            }
            picker.show(childFragmentManager, "time_picker")
        }

        // Load existing config
        val config = scheduleManager.getConfig()
        switchEnabled.isChecked = config.enabled
        selectedHour = config.hour
        selectedMinute = config.minute
        btnTime.text = "Time: ${formatTime(config.hour, config.minute)}"

        when (config.recurrenceType) {
            RecurrenceType.WEEKLY -> {
                spinnerFrequency.setText(frequencies[0], false)
                tilDayOfWeek.visibility = View.VISIBLE; tilDayOfMonth.visibility = View.GONE
            }
            RecurrenceType.BIWEEKLY -> {
                spinnerFrequency.setText(frequencies[1], false)
                tilDayOfWeek.visibility = View.VISIBLE; tilDayOfMonth.visibility = View.GONE
            }
            RecurrenceType.MONTHLY -> {
                spinnerFrequency.setText(frequencies[2], false)
                tilDayOfWeek.visibility = View.GONE; tilDayOfMonth.visibility = View.VISIBLE
            }
        }

        val dayIdx = daysOfWeekValues.indexOf(config.dayOfWeek).coerceAtLeast(0)
        spinnerDayOfWeek.setText(daysOfWeek[dayIdx], false)
        spinnerDayOfMonth.setText(config.dayOfMonth.toString(), false)

        val threshIdx = thresholdMonths.indexOf(config.deleteOlderThanMonths).let { if (it >= 0) it else 3 }
        spinnerThreshold.setText(thresholds[threshIdx], false)

        cbSms.isChecked = config.includeSms
        cbMmsMedia.isChecked = config.includeMmsMedia
        cbMmsGroup.isChecked = config.includeMmsGroup
        cbRcs.isChecked = config.includeRcs
        etBatchSize.setText(config.batchSize.toString())
        etBatchSizeMmsMedia.setText(config.batchSizeMmsMedia.toString())
        etBatchSizeMmsGroup.setText(config.batchSizeMmsGroup.toString())
        etChunkSize.setText(config.deleteChunkSize.toString())
        etDelay.setText(config.delayMs.toString())
        cbRequiresCharging.isChecked = config.requiresCharging

        updateStatus(tvStatus)

        // Save
        btnSave.setOnClickListener {
            val freqIdx = frequencies.indexOf(spinnerFrequency.text.toString()).coerceAtLeast(0)
            val recurrence = when (freqIdx) {
                0 -> RecurrenceType.WEEKLY
                1 -> RecurrenceType.BIWEEKLY
                else -> RecurrenceType.MONTHLY
            }

            val dayOfWeekIdx = daysOfWeek.indexOf(spinnerDayOfWeek.text.toString()).coerceAtLeast(0)
            val dayOfMonth = spinnerDayOfMonth.text.toString().toIntOrNull() ?: 1
            val thresholdIdx = thresholds.indexOf(spinnerThreshold.text.toString()).let { if (it >= 0) it else 3 }

            val newConfig = ScheduleConfig(
                enabled = switchEnabled.isChecked,
                recurrenceType = recurrence,
                dayOfWeek = daysOfWeekValues[dayOfWeekIdx],
                dayOfMonth = dayOfMonth,
                hour = selectedHour,
                minute = selectedMinute,
                deleteOlderThanMonths = thresholdMonths[thresholdIdx],
                includeSms = cbSms.isChecked,
                includeMmsMedia = cbMmsMedia.isChecked,
                includeMmsGroup = cbMmsGroup.isChecked,
                includeRcs = cbRcs.isChecked,
                batchSize = etBatchSize.text.toString().toIntOrNull() ?: 1000,
                batchSizeMmsMedia = etBatchSizeMmsMedia.text.toString().toIntOrNull() ?: 100,
                batchSizeMmsGroup = etBatchSizeMmsGroup.text.toString().toIntOrNull() ?: 500,
                deleteChunkSize = etChunkSize.text.toString().toIntOrNull() ?: 50,
                delayMs = etDelay.text.toString().toLongOrNull() ?: 100,
                requiresCharging = cbRequiresCharging.isChecked
            )

            if (newConfig.enabled) {
                scheduleManager.enableSchedule(newConfig)
            } else {
                scheduleManager.disableSchedule()
            }

            updateStatus(tvStatus)
            Toast.makeText(requireContext(), "Schedule saved", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateStatus(tvStatus: MaterialTextView) {
        val nextRun = scheduleManager.getNextRunDescription()
        tvStatus.text = if (nextRun != null) "Next run: $nextRun" else "Schedule disabled"
    }

    private fun formatTime(hour: Int, minute: Int): String {
        val amPm = if (hour < 12) "AM" else "PM"
        val h = if (hour == 0) 12 else if (hour > 12) hour - 12 else hour
        return "%d:%02d %s".format(h, minute, amPm)
    }
}
