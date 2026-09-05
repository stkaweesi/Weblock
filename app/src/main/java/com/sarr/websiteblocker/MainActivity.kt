package com.sarr.websiteblocker

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.Switch
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var domainInput: EditText
    private lateinit var protectionSwitch: Switch
    private lateinit var adapter: ArrayAdapter<String>
    private val domains = mutableListOf<String>()

    private val vpnPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                startVpn()
            } else {
                protectionSwitch.isChecked = false
                Toast.makeText(this, "VPN permission is required to block sites", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        domainInput = findViewById(R.id.domainInput)
        protectionSwitch = findViewById(R.id.protectionSwitch)
        val domainListView = findViewById<ListView>(R.id.domainListView)
        val addButton = findViewById<Button>(R.id.addButton)

        domains.addAll(BlocklistStore.getBlockedDomains(this).sorted())
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, domains)
        domainListView.adapter = adapter

        addButton.setOnClickListener {
            val raw = domainInput.text.toString()
            if (raw.isNotBlank()) {
                val normalized = BlocklistStore.normalize(raw)
                if (normalized.isNotEmpty() && !domains.contains(normalized)) {
                    domains.add(normalized)
                    domains.sort()
                    BlocklistStore.saveBlockedDomains(this, domains.toSet())
                    adapter.notifyDataSetChanged()
                    domainInput.text.clear()
                    if (protectionSwitch.isChecked) restartVpn()
                }
            }
        }

        domainListView.setOnItemLongClickListener { _, _, position, _ ->
            val removed = domains.removeAt(position)
            BlocklistStore.saveBlockedDomains(this, domains.toSet())
            adapter.notifyDataSetChanged()
            Toast.makeText(this, "Removed $removed", Toast.LENGTH_SHORT).show()
            if (protectionSwitch.isChecked) restartVpn()
            true
        }

        protectionSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) requestVpnPermission() else stopVpn()
        }
    }

    private fun requestVpnPermission() {
        val intent = VpnService.prepare(this)
        if (intent != null) vpnPermissionLauncher.launch(intent) else startVpn()
    }

    private fun startVpn() {
        startService(Intent(this, BlockerVpnService::class.java).apply {
            action = BlockerVpnService.ACTION_START
        })
    }

    private fun stopVpn() {
        startService(Intent(this, BlockerVpnService::class.java).apply {
            action = BlockerVpnService.ACTION_STOP
        })
    }

    private fun restartVpn() {
        stopVpn()
        startVpn()
    }
}
