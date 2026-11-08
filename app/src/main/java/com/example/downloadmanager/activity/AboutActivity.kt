package com.example.downloadmanager.activity

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
        
        // Set version
        binding.tvVersion.text = getString(R.string.version, BuildConfig.VERSION_NAME)
        
        // Set description
        binding.tvDescription.text = getString(R.string.app_description)
    }
    
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            onBackPressed()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
