package com.example.downloadmanager.activity

import android.app.AlertDialog
import android.os.Bundle
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import com.example.downloadmanager.BuildConfig
import com.example.downloadmanager.R
import com.example.downloadmanager.databinding.ActivityAboutBinding

class AboutActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityAboutBinding
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.about)
        
        binding.tvVersion.text = getString(R.string.version, BuildConfig.VERSION_NAME)
        binding.tvDescription.text = getString(R.string.app_description)

        binding.btnPrivacyPolicy.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.privacy_policy)
                .setMessage(
                    "Universal Download Manager processes all URLs, music streams, videos, and files locally on your device.\n\n" +
                    "• No personal data, browsing history, or download history is shared with external servers.\n" +
                    "• Downloaded files are stored directly in your selected device storage folder."
                )
                .setPositiveButton(R.string.ok, null)
                .show()
        }

        binding.btnOpenSourceLicenses.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.open_source_licenses)
                .setMessage(
                    "• AndroidX Jetpack (Core, AppCompat, Room, Lifecycle, WorkManager, Preference) — Apache License 2.0\n" +
                    "• Material Components for Android — Apache License 2.0\n" +
                    "• Square OkHttp & Retrofit — Apache License 2.0\n" +
                    "• Kotlin Coroutines — Apache License 2.0"
                )
                .setPositiveButton(R.string.ok, null)
                .show()
        }
    }
    
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            onBackPressedDispatcher.onBackPressed()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
