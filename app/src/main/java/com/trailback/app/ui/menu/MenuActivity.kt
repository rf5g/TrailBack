package com.trailback.app.ui.menu

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.trailback.app.TrailBackApp
import com.trailback.app.data.repository.TrackingMode
import com.trailback.app.databinding.ActivityMenuBinding
import com.trailback.app.ui.compass.CompassActivity

class MenuActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMenuBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMenuBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val items = listOf(
            MenuItem(getString(com.trailback.app.R.string.menu_entry_points)) {
                startActivity(Intent(this, EntryPointsActivity::class.java))
            },
            MenuItem(getString(com.trailback.app.R.string.menu_marked_places)) {
                startActivity(Intent(this, MarkedPlacesActivity::class.java))
            },
            MenuItem(getString(com.trailback.app.R.string.menu_compass)) {
                startActivity(Intent(this, CompassActivity::class.java))
            },
            MenuItem(getString(com.trailback.app.R.string.menu_compass_settings)) {
                startActivity(Intent(this, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_SECTION, SettingsActivity.SECTION_COMPASS))
            },
            MenuItem(getString(com.trailback.app.R.string.menu_offline_maps)) {
                startActivity(Intent(this, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_SECTION, SettingsActivity.SECTION_MAPS))
            },
            MenuItem(getString(com.trailback.app.R.string.menu_calibration)) {
                startActivity(Intent(this, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_SECTION, SettingsActivity.SECTION_CALIBRATION))
            },
            MenuItem(getString(com.trailback.app.R.string.menu_info)) {
                startActivity(Intent(this, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_SECTION, SettingsActivity.SECTION_INFO))
            },
            MenuItem(getString(com.trailback.app.R.string.menu_language)) {
                startActivity(Intent(this, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_SECTION, SettingsActivity.SECTION_LANGUAGE))
            },
            MenuItem(getString(com.trailback.app.R.string.menu_exit)) {
                onExitTapped()
            }
        )

        binding.menuList.layoutManager = LinearLayoutManager(this)
        binding.menuList.adapter = MenuAdapter(items)
    }

    /** Выход заблокирован в активном режиме "Домой" (см. решение по ТЗ). */
    private fun onExitTapped() {
        val app = application as TrailBackApp
        if (app.trackingStateStore.mode == TrackingMode.RETURNING) {
            Toast.makeText(this, com.trailback.app.R.string.exit_locked_in_returning, Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setMessage(com.trailback.app.R.string.exit_confirm_message)
            .setPositiveButton(com.trailback.app.R.string.arrived_dialog_yes) { _, _ ->
                finishAffinity()
            }
            .setNegativeButton(com.trailback.app.R.string.arrived_dialog_no, null)
            .show()
    }
}

data class MenuItem(val title: String, val onClick: () -> Unit)
