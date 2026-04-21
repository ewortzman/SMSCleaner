package com.smscleaner.app

import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.navigation.NavigationView
import com.smscleaner.app.fragment.ManualCleanFragment
import com.smscleaner.app.fragment.ScheduledCleanFragment
import com.smscleaner.app.fragment.TestMessagesFragment

class MainActivity : AppCompatActivity() {

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var navigationView: NavigationView
    private lateinit var toolbar: MaterialToolbar

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (!results.values.all { it }) {
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

        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)

        drawerLayout = findViewById(R.id.drawerLayout)
        navigationView = findViewById(R.id.navigationView)

        val toggle = ActionBarDrawerToggle(
            this, drawerLayout, toolbar,
            R.string.drawer_open, R.string.drawer_close
        )
        drawerLayout.addDrawerListener(toggle)
        toggle.syncState()

        navigationView.setNavigationItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_manual_clean -> showFragment(ManualCleanFragment(), "Manual Clean")
                R.id.nav_scheduled_clean -> showFragment(ScheduledCleanFragment(), "Scheduled Clean")
                R.id.nav_test_messages -> showFragment(TestMessagesFragment(), "Test Messages")
            }
            drawerLayout.closeDrawers()
            true
        }

        // Default fragment
        if (savedInstanceState == null) {
            showFragment(ManualCleanFragment(), "Manual Clean")
            navigationView.setCheckedItem(R.id.nav_manual_clean)
        }

        requestPermissions()
    }

    private fun showFragment(fragment: Fragment, title: String) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .commit()
        toolbar.title = title
    }

    private fun requestPermissions() {
        val needed = mutableListOf<String>()
        val perms = arrayOf(
            android.Manifest.permission.READ_SMS,
            android.Manifest.permission.RECEIVE_SMS,
            android.Manifest.permission.SEND_SMS,
            android.Manifest.permission.READ_CONTACTS,
            android.Manifest.permission.RECEIVE_MMS,
            android.Manifest.permission.RECEIVE_WAP_PUSH,
            android.Manifest.permission.POST_NOTIFICATIONS
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
