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
                    "Luca Download Manager downloads files directly to your selected device storage.\n\n" +
                    "• Direct HTTP/HTTPS, HLS, and BitTorrent connections are made directly from your device to the target hosts, trackers, and peers.\n" +
                    "• When 'Allow Third-Party Social Video Resolvers' is enabled in Settings, social media video URLs (TikTok, X/Twitter, Instagram) may be queried against public resolver services (tikwm.com, vxtwitter.com, fxtwitter.com, ddinstagram.com) if direct page extraction is restricted.\n" +
                    "• When 'Allow Alternate-Source Lookup (YouTube)' is explicitly enabled by you, track metadata (title and artist) may be searched on YouTube to locate a matching stream.\n" +
                    "• You can disable third-party resolvers and alternate-source lookups at any time in Settings."
                )
                .setPositiveButton(R.string.ok, null)
                .show()
        }

        binding.btnOpenSourceLicenses.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.open_source_licenses)
                .setMessage(
                    "• AndroidX Jetpack (Core, AppCompat, Room, Lifecycle, WorkManager, Preference, Media3 ExoPlayer) — Apache License 2.0\n" +
                    "• Material Components for Android — Apache License 2.0\n" +
                    "• Square OkHttp — Apache License 2.0\n" +
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
