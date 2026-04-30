package com.smscleaner.app.fragment

import android.app.Activity
import android.content.Context
import android.provider.ContactsContract
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textview.MaterialTextView
import com.smscleaner.app.R
import com.smscleaner.app.schedule.ExclusionPreferences

class ExclusionsDialog(
    private val context: Context,
    private val prefs: ExclusionPreferences,
    private val onPickContact: (() -> Unit)? = null
) {

    private var items = mutableListOf<String>()
    private lateinit var adapter: Adapter
    private lateinit var emptyText: View
    private lateinit var recycler: RecyclerView

    fun show() {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_exclusions, null)
        val etNumber = view.findViewById<TextInputEditText>(R.id.etExcludeNumber)
        val btnAdd = view.findViewById<MaterialButton>(R.id.btnAddExclusion)
        val btnPickContact = view.findViewById<MaterialButton>(R.id.btnPickContact)
        recycler = view.findViewById(R.id.rvExclusions)
        emptyText = view.findViewById<MaterialTextView>(R.id.tvNoExclusions)

        items = prefs.load().sorted().toMutableList()
        adapter = Adapter()
        recycler.adapter = adapter
        recycler.layoutManager = LinearLayoutManager(context)
        refreshEmpty()

        btnAdd.setOnClickListener {
            val text = etNumber.text?.toString()?.trim() ?: return@setOnClickListener
            if (text.isNotEmpty()) {
                prefs.add(text)
                items = prefs.load().sorted().toMutableList()
                adapter.notifyDataSetChanged()
                etNumber.text?.clear()
                refreshEmpty()
            }
        }

        btnPickContact.setOnClickListener { onPickContact?.invoke() }

        AlertDialog.Builder(context)
            .setTitle(R.string.exclusions_title)
            .setView(view)
            .setPositiveButton(R.string.close, null)
            .show()
    }

    fun addNumber(number: String) {
        prefs.add(number)
        items = prefs.load().sorted().toMutableList()
        if (::adapter.isInitialized) adapter.notifyDataSetChanged()
        if (::recycler.isInitialized) refreshEmpty()
    }

    private fun refreshEmpty() {
        emptyText.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        recycler.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.Holder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_exclusion, parent, false)
            return Holder(view)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val num = items[position]
            holder.tvNumber.text = num
            holder.btnRemove.setOnClickListener {
                prefs.remove(num)
                items = prefs.load().sorted().toMutableList()
                notifyDataSetChanged()
                refreshEmpty()
            }
        }

        override fun getItemCount(): Int = items.size

        inner class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val tvNumber: MaterialTextView = itemView.findViewById(R.id.tvNumber)
            val btnRemove: MaterialButton = itemView.findViewById(R.id.btnRemove)
        }
    }

    companion object {
        const val CONTACT_PICK_REQUEST = 1001
    }
}
