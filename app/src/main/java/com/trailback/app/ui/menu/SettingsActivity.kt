package com.trailback.app.ui.menu
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.trailback.app.BuildConfig
import com.trailback.app.R
import com.trailback.app.TrailBackApp
import com.trailback.app.data.repository.NorthMode
import com.trailback.app.databinding.ActivitySettingsBinding
/**
 * Единый экран настроек с секциями (см. п.6.3 ТЗ, пункты 4–8).
 * Показывается только запрошенная секция — остальные view.GONE,
 * чтобы не плодить 5 отдельных Activity под каждый простой пункт меню.
 */
class SettingsActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_SECTION = "extra_section"
        const val SECTION_COMPASS = "compass"
        const val SECTION_MAPS = "maps"
        const val SECTION_CALIBRATION = "calibration"
        const val SECTION_INFO = "info"
        const val SECTION_LANGUAGE = "language"
    }
    private lateinit var binding: ActivitySettingsBinding
    private val pickMapsFolder = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            val app = application as TrailBackApp
            app.settingsStore.offlineMapsUri = uri.toString()
            updateMapsSectionText()
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        val app = application as TrailBackApp
        val section = intent.getStringExtra(EXTRA_SECTION)
        binding.compassSection.visibility = visibleIf(section == SECTION_COMPASS)
        binding.mapsSection.visibility = visibleIf(section == SECTION_MAPS)
        binding.calibrationSection.visibility = visibleIf(section == SECTION_CALIBRATION)
        binding.infoSection.visibility = visibleIf(section == SECTION_INFO)
        binding.languageSection.visibility = visibleIf(section == SECTION_LANGUAGE)
        setupCompassSection(app)
        setupMapsSection()
        setupCalibrationSection()
        setupInfoSection()
        setupLanguageSection(app)
    }
    private fun setupCompassSection(app: TrailBackApp) {
        val isTrueNorth = app.settingsStore.northMode == NorthMode.TRUE
        binding.northModeSwitch.isChecked = isTrueNorth
        binding.northModeSwitch.text = if (isTrueNorth) {
            getString(R.string.north_mode_true)
        } else {
            getString(R.string.north_mode_magnetic)
        }
        binding.northModeSwitch.setOnCheckedChangeListener { _, checked ->
            app.settingsStore.northMode = if (checked) NorthMode.TRUE else NorthMode.MAGNETIC
            binding.northModeSwitch.text = if (checked) {
                getString(R.string.north_mode_true)
            } else {
                getString(R.string.north_mode_magnetic)
            }
        }
    }
    private fun setupMapsSection() {
        updateMapsSectionText()
        binding.chooseMapsFolderButton.setOnClickListener {
            pickMapsFolder.launch(null)
        }
    }
    private fun updateMapsSectionText() {
        val app = application as TrailBackApp
        val uri = app.settingsStore.offlineMapsUri
        binding.mapsFolderText.text = uri ?: getString(R.string.menu_offline_maps)
    }
    private fun setupCalibrationSection() {
        binding.calibrationTitle.text = getString(R.string.calibration_instructions_title)
        binding.calibrationText.text = getString(R.string.calibration_instructions_text)
        // Программного сброса калибровки магнитометра в Android API нет —
        // кнопка только повторно показывает инструкцию (см. решение по ТЗ).
    }
    private fun setupInfoSection() {
        binding.versionText.text = "v${BuildConfig.VERSION_NAME}"
    }
    private fun setupLanguageSection(app: TrailBackApp) {
        binding.languageRadioGroup.check(
            if (app.settingsStore.language == "ru") R.id.languageRu else R.id.languageEn
        )
        binding.languageRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            app.settingsStore.language = if (checkedId == R.id.languageRu) "ru" else "en"
            applyLocaleAndRestart(app.settingsStore.language)
        }
    }
    private fun applyLocaleAndRestart(languageCode: String) {
        val locale = java.util.Locale(languageCode)
        java.util.Locale.setDefault(locale)
        val config = resources.configuration
        config.setLocale(locale)
        resources.updateConfiguration(config, resources.displayMetrics)
        recreate()
    }
    private fun visibleIf(condition: Boolean) =
        if (condition) android.view.View.VISIBLE else android.view.View.GONE
}
