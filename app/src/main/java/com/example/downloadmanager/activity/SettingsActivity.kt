package com.example.downloadmanager.activity

import android.os.Bundle
import android.os.Environment
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.example.downloadmanager.BuildConfig
import com.example.downloadmanager.R

class SettingsActivity : AppCompatActivity() {
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.settings)
        supportActionBar?.subtitle = "Luca v${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})"
        
        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commit()
        }
    }
    
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            onBackPressedDispatcher.onBackPressed()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
    
    class SettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)

            findPreference<Preference>("pref_app_version")?.summary =
                "Luca Download Manager v${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})"

            // Populate default save location if empty
            val savePathPref = findPreference<EditTextPreference>("default_save_path")
            if (savePathPref != null && savePathPref.text.isNullOrBlank()) {
                val defaultDir = requireContext().getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.absolutePath
                    ?: Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath
                savePathPref.text = defaultDir
            }

            // Apply Dark Mode immediately when toggled
            val darkModePref = findPreference<SwitchPreferenceCompat>("dark_mode")
            darkModePref?.setOnPreferenceChangeListener { _, newValue ->
                val enabled = newValue as? Boolean ?: false
                AppCompatDelegate.setDefaultNightMode(
                    if (enabled) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
                )
                true
            }

            // Apply app language immediately when changed
            val languagePref = findPreference<androidx.preference.ListPreference>("language")
            languagePref?.setOnPreferenceChangeListener { _, newValue ->
                val langCode = (newValue as? String)?.trim().orEmpty()
                val locales = if (langCode.isEmpty() || langCode == "system") {
                    androidx.core.os.LocaleListCompat.getEmptyLocaleList()
                } else {
                    androidx.core.os.LocaleListCompat.forLanguageTags(langCode)
                }
                AppCompatDelegate.setApplicationLocales(locales)
                true
            }
        }
    }
}
