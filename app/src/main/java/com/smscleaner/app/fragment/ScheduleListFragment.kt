package com.smscleaner.app.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textview.MaterialTextView
import com.smscleaner.app.R
import com.smscleaner.app.model.ScheduleConfig
import com.smscleaner.app.schedule.ScheduleManager

class ScheduleListFragment : Fragment(R.layout.fragment_schedule_list) {

    private lateinit var scheduleManager: ScheduleManager
    private var schedules: List<ScheduleConfig> = emptyList()
    private lateinit var adapter: Adapter
    private lateinit var emptyView: View
    private lateinit var recycler: RecyclerView

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        scheduleManager = ScheduleManager(requireContext())

        recycler = view.findViewById(R.id.rvSchedules)
        emptyView = view.findViewById<MaterialTextView>(R.id.tvNoSchedules)
        val fab = view.findViewById<ExtendedFloatingActionButton>(R.id.fabAddSchedule)

        adapter = Adapter()
        recycler.adapter = adapter
        recycler.layoutManager = LinearLayoutManager(requireContext())

        fab.setOnClickListener { openEditor(null) }

        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        schedules = scheduleManager.getAll()
        adapter.notifyDataSetChanged()
        emptyView.visibility = if (schedules.isEmpty()) View.VISIBLE else View.GONE
        recycler.visibility = if (schedules.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun openEditor(scheduleId: String?) {
        val fragment = ScheduledCleanFragment().apply {
            arguments = Bundle().apply {
                if (scheduleId != null) putString(ScheduledCleanFragment.ARG_SCHEDULE_ID, scheduleId)
            }
        }
        parentFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .addToBackStack(null)
            .commit()
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.Holder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            return Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_schedule, parent, false))
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val s = schedules[position]
            holder.tvName.text = s.name
            holder.tvSummary.text = scheduleManager.getNextRunDescription(s) ?: "Disabled"
            holder.switchEnabled.setOnCheckedChangeListener(null)
            holder.switchEnabled.isChecked = s.enabled
            holder.switchEnabled.setOnCheckedChangeListener { _, checked ->
                scheduleManager.upsert(s.copy(enabled = checked))
                refresh()
            }
            holder.itemView.setOnClickListener { openEditor(s.id) }
        }

        override fun getItemCount(): Int = schedules.size

        inner class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val tvName: MaterialTextView = itemView.findViewById(R.id.tvScheduleName)
            val tvSummary: MaterialTextView = itemView.findViewById(R.id.tvScheduleSummary)
            val switchEnabled: MaterialSwitch = itemView.findViewById(R.id.switchItemEnabled)
        }
    }
}
