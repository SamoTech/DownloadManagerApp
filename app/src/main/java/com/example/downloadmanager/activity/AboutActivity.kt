package com.example.downloadmanager.activity

import android.app.AlertDialog
import android.os.Bundle
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import com.example.downloadmanager.BuildConfig
import com.example.downloadmanager.R
import com.example.downloadmanager.databinding.ActivityAboutBinding
import com.example.downloadmanager.utils.EdgeToEdgeHelper

class AboutActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityAboutBinding
    
    override fun onCreate(savedInstanceState: Bundle?) {
        EdgeToEdgeHelper.enableWithDarkHeader(this)
        super.onCreate(savedInstanceState)
        
        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)

        EdgeToEdgeHelper.applyTopHeaderInsets(binding.toolbar)
        EdgeToEdgeHelper.applyScrollingBottomInsets(binding.scrollAboutContainer)
        
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.about)
        supportActionBar?.subtitle = null
        binding.toolbar.subtitle = null
        
        binding.tvVersion.text = getString(R.string.version, BuildConfig.VERSION_NAME)
        binding.tvDescription.text = getString(R.string.app_description)

        binding.btnPrivacyPolicy.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.privacy_policy)
                .setMessage(
                    "Luca Download Manager saves files directly to your selected device storage and collects no personal data, account credentials, or analytics.\n\n" +
                    "• Direct HTTP/HTTPS, HLS/DASH, and BitTorrent/Magnet connections are initiated directly from your device to the URLs, trackers, and peers you provide.\n" +
                    "• Optional public metadata resolvers, public torrent metadata caches, and alternate public audio lookup are user-controlled in Settings and can be toggled off at any time.\n" +
                    "• Luca Download Manager does not host, index, or distribute any media files."
                )
                .setPositiveButton(R.string.ok, null)
                .show()
        }

        binding.btnCopyrightNotice.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.copyright_notice)
                .setMessage(
                    "Luca Download Manager is an independent, general-purpose public download manager and is not affiliated with, endorsed by, or sponsored by any third-party website, media platform, or streaming service.\n\n" +
                    "• Intended Use: Download only public-domain, Creative Commons, open-access, or self-owned files, or content for which you hold explicit legal permission from the rights holder.\n" +
                    "• Copyright & DRM Compliance: Downloading DRM-protected, paywalled, or unauthorized copyrighted material is strictly prohibited and not supported.\n" +
                    "• User Responsibility: You are solely responsible for complying with applicable copyright laws and the terms of service of any website you access."
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
