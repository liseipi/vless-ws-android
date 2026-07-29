package com.example.vlessvpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.vlessvpn.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var connected = false

    private val vpnPrepareLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startVpn()
        } else {
            Toast.makeText(this, "需要授权 VPN 权限才能连接", Toast.LENGTH_SHORT).show()
        }
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val text = intent?.getStringExtra(VlessVpnService.EXTRA_STATUS_TEXT) ?: return
            binding.statusText.text = text
            connected = text == "已连接"
            updateButtonLabel()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val config = ConfigStore.load(this)
        binding.hostInput.setText(config.host)
        binding.portInput.setText(config.port)
        binding.pathInput.setText(config.path)
        binding.uuidInput.setText(config.uuid)
        binding.tokenInput.setText(config.token)
        binding.sniInput.setText(config.sni)
        binding.tlsSwitch.isChecked = config.useTls

        binding.saveButton.setOnClickListener { saveConfig() }
        binding.connectButton.setOnClickListener { onConnectClicked() }

        val filter = IntentFilter(VlessVpnService.ACTION_STATUS_UPDATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(statusReceiver, filter)
        }
    }

    override fun onDestroy() {
        unregisterReceiver(statusReceiver)
        super.onDestroy()
    }

    private fun currentConfigFromForm(): VpnConfig = VpnConfig(
        host = binding.hostInput.text.toString().trim(),
        port = binding.portInput.text.toString().trim().ifBlank { "443" },
        path = binding.pathInput.text.toString().trim().ifBlank { "/api" },
        useTls = binding.tlsSwitch.isChecked,
        sni = binding.sniInput.text.toString().trim(),
        uuid = binding.uuidInput.text.toString().trim(),
        token = binding.tokenInput.text.toString().trim()
    )

    private fun saveConfig() {
        val config = currentConfigFromForm()
        if (!config.isValid()) {
            Toast.makeText(this, "服务器地址和 UUID 不能为空", Toast.LENGTH_SHORT).show()
            return
        }
        ConfigStore.save(this, config)
        Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
    }

    private fun onConnectClicked() {
        if (connected) {
            val intent = Intent(this, VlessVpnService::class.java)
            intent.action = VlessVpnService.ACTION_DISCONNECT
            startService(intent)
            return
        }

        saveConfig()
        val config = ConfigStore.load(this)
        if (!config.isValid()) return

        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            vpnPrepareLauncher.launch(prepareIntent)
        } else {
            startVpn()
        }
    }

    private fun startVpn() {
        val intent = Intent(this, VlessVpnService::class.java)
        startService(intent)
    }

    private fun updateButtonLabel() {
        binding.connectButton.text = if (connected) "断开" else "连接"
    }
}
