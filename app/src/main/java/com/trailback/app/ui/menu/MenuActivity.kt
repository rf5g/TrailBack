package com.trailback.app.ui.menu

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
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
            }
        )

        binding.menuList.layoutManager = LinearLayoutManager(this)
        binding.menuList.adapter = MenuAdapter(items)
    }
}

data class MenuItem(val title: String, val onClick: () -> Unit)
