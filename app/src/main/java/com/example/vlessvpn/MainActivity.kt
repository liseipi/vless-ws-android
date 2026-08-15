package com.example.vlessvpn

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.vlessvpn.databinding.ActivityMainBinding
import com.example.vlessvpn.databinding.DialogEditConfigBinding
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: ConfigAdapter
    private var connected = false

    /** Invoked with the raw scanned text once a QR scan finishes successfully. */
    private var pendingScanCallback: ((String) -> Unit)? = null

    private val uiHandler = Handler(Looper.getMainLooper())

    /** Safety net: if the service never broadcasts back (killed mid-flight, etc.),
     *  don't leave the button stuck disabled forever. */
    private val connectTimeoutRunnable = Runnable {
        connected = false
        Toast.makeText(this, "连接响应超时，请重试", Toast.LENGTH_SHORT).show()
        updateStatusUi("Disconnected")
    }

    private val vpnPrepareLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startVpn()
        } else {
            uiHandler.removeCallbacks(connectTimeoutRunnable)
            Toast.makeText(this, "需要授权 VPN 权限才能连接", Toast.LENGTH_SHORT).show()
            updateStatusUi("Disconnected")
        }
    }

    private val qrScanLauncher = registerForActivityResult(ScanContract()) { result ->
        val callback = pendingScanCallback
        pendingScanCallback = null
        val raw = result.contents
        if (raw != null && callback != null) {
            callback(raw)
        }
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val text = intent?.getStringExtra(VlessVpnService.EXTRA_STATUS_TEXT) ?: return
            uiHandler.removeCallbacks(connectTimeoutRunnable)
            connected = text.equals("Connected", ignoreCase = true)
            updateStatusUi(text)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = ConfigAdapter(
            onSelect = { config -> selectConfig(config) },
            onEdit = { config -> showEditDialog(config) },
            onDelete = { config -> confirmDelete(config) },
            onExport = { config -> showExportDialog(config) }
        )
        binding.configList.layoutManager = LinearLayoutManager(this)
        binding.configList.adapter = adapter
        binding.configList.isNestedScrollingEnabled = false

        binding.importButton.setOnClickListener { showImportDialog() }
        binding.addButton.setOnClickListener { showEditDialog(null) }
        binding.connectButton.setOnClickListener { onConnectClicked() }
        binding.scanButton.setOnClickListener { scanAndAddConfig() }

        refreshConfigList()
        updateStatusUi("Disconnected")

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
        uiHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ---- Config list ----

    private fun refreshConfigList() {
        val configs = ConfigStore.loadAll(this)
        val selectedId = ConfigStore.getSelectedId(this)
        adapter.submitList(configs, selectedId)
    }

    private fun selectConfig(config: VpnConfig) {
        ConfigStore.setSelectedId(this, config.id)
        refreshConfigList()
    }

    // ---- Connect / disconnect ----

    private fun onConnectClicked() {
        if (connected) {
            binding.connectButton.isEnabled = false
            updateStatusUi("Disconnecting…")
            uiHandler.removeCallbacks(connectTimeoutRunnable)
            uiHandler.postDelayed(connectTimeoutRunnable, DISCONNECT_TIMEOUT_MS)
            val intent = Intent(this, VlessVpnService::class.java)
            intent.action = VlessVpnService.ACTION_DISCONNECT
            startService(intent)
            return
        }

        val config = ConfigStore.load(this)
        if (!config.isValid()) {
            Toast.makeText(this, "请先添加并选择一个有效的配置", Toast.LENGTH_SHORT).show()
            return
        }

        // Immediate feedback so the button never looks unresponsive while the
        // service spins up in the background. Note: the 15s connect-timeout
        // timer is intentionally NOT started here — it starts once startVpn()
        // actually runs. VpnService.prepare() can pop a system permission
        // dialog that waits on the user, and that wait time must not count
        // against our own timeout, or the app can show "timed out" while the
        // system dialog is still just sitting there waiting for a tap.
        binding.connectButton.isEnabled = false
        updateStatusUi("Connecting…")

        val prepareIntent = try {
            VpnService.prepare(this)
        } catch (e: Exception) {
            // Seen on some devices after repeated reinstalls: the system's VPN
            // consent state gets left pointing at a stale UID from a previous
            // install and prepare() throws instead of just returning an intent.
            Toast.makeText(this, "系统 VPN 授权检查异常，请重启该应用后重试；如果反复出现，重启手机通常能清掉系统缓存的授权状态", Toast.LENGTH_LONG).show()
            updateStatusUi("Disconnected")
            return
        }
        if (prepareIntent != null) {
            vpnPrepareLauncher.launch(prepareIntent)
        } else {
            startVpn()
        }
    }

    private fun startVpn() {
        uiHandler.removeCallbacks(connectTimeoutRunnable)
        uiHandler.postDelayed(connectTimeoutRunnable, CONNECT_TIMEOUT_MS)
        val intent = Intent(this, VlessVpnService::class.java)
        startService(intent)
    }

    private fun updateStatusUi(rawText: String) {
        val symbol = if (connected) "\u25CF" else "\u25CB" // filled / hollow circle
        binding.statusText.text = "$symbol $rawText"
        binding.statusText.setTextColor(
            ContextCompat.getColor(this, if (connected) R.color.accent_blue else R.color.text_secondary)
        )
        binding.connectButton.text = if (connected) "Disconnect" else "Connect"
        // "…" marks a transient state (Connecting…/Disconnecting…) — keep the button
        // disabled during those, and re-enable for any terminal state (Connected,
        // Disconnected, or an error message), so the UI never gets stuck looking
        // unresponsive after a broadcast comes back.
        binding.connectButton.isEnabled = !rawText.endsWith("\u2026")
    }

    // ---- QR scanning ----

    private fun launchScanner(onResult: (String) -> Unit) {
        pendingScanCallback = onResult
        val options = ScanOptions()
        options.setDesiredBarcodeFormats(ScanOptions.QR_CODE)
        options.setPrompt("将二维码放入框内扫描")
        options.setBeepEnabled(false)
        options.setOrientationLocked(true)
        qrScanLauncher.launch(options)
    }

    private fun scanAndAddConfig() {
        launchScanner { raw ->
            val parsed = VlessLink.parse(raw)
            if (parsed == null) {
                Toast.makeText(this, "未识别到有效的 vless:// 二维码", Toast.LENGTH_SHORT).show()
                return@launchScanner
            }
            val configs = ConfigStore.loadAll(this)
            configs.add(parsed)
            ConfigStore.saveAll(this, configs)
            if (ConfigStore.getSelectedId(this) == null) {
                ConfigStore.setSelectedId(this, parsed.id)
            }
            refreshConfigList()
            Toast.makeText(this, "已导入: ${parsed.name}", Toast.LENGTH_SHORT).show()
        }
    }

    // ---- Import (paste vless:// links) ----

    private fun showImportDialog() {
        val input = EditText(this).apply {
            hint = "粘贴一个或多个 vless:// 链接，每行一个"
            minLines = 4
            maxLines = 8
            setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(this)
            .setTitle("导入 VLESS 链接")
            .setView(input)
            .setPositiveButton("导入") { _, _ ->
                val lines = input.text.toString()
                    .split("\n", "\r")
                    .map { it.trim() }
                    .filter { it.isNotBlank() }

                val configs = ConfigStore.loadAll(this)
                var importedCount = 0
                var firstImportedId: String? = null
                lines.forEach { line ->
                    val parsed = VlessLink.parse(line)
                    if (parsed != null) {
                        configs.add(parsed)
                        if (firstImportedId == null) firstImportedId = parsed.id
                        importedCount++
                    }
                }

                if (importedCount > 0) {
                    ConfigStore.saveAll(this, configs)
                    if (ConfigStore.getSelectedId(this) == null) {
                        ConfigStore.setSelectedId(this, firstImportedId!!)
                    }
                    refreshConfigList()
                    Toast.makeText(this, "成功导入 $importedCount 个配置", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "未找到有效的 vless:// 链接", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---- Add / edit config ----

    private fun showEditDialog(existing: VpnConfig?) {
        val dialogBinding = DialogEditConfigBinding.inflate(LayoutInflater.from(this))
        val current = existing ?: VpnConfig()

        dialogBinding.nameInput.setText(current.name)
        dialogBinding.hostInput.setText(current.host)
        dialogBinding.portInput.setText(current.port)
        dialogBinding.pathInput.setText(current.path)
        dialogBinding.uuidInput.setText(current.uuid)
        dialogBinding.tokenInput.setText(current.token)
        dialogBinding.sniInput.setText(current.sni)
        dialogBinding.wsHostInput.setText(current.wsHost)
        dialogBinding.tlsSwitch.isChecked = current.useTls

        fun applyParsedLink(raw: String) {
            val parsed = VlessLink.parse(raw)
            if (parsed == null) {
                Toast.makeText(this, "无法解析该链接，请检查格式", Toast.LENGTH_SHORT).show()
                return
            }
            dialogBinding.nameInput.setText(parsed.name)
            dialogBinding.hostInput.setText(parsed.host)
            dialogBinding.portInput.setText(parsed.port)
            dialogBinding.pathInput.setText(parsed.path)
            dialogBinding.uuidInput.setText(parsed.uuid)
            dialogBinding.tokenInput.setText(parsed.token)
            dialogBinding.sniInput.setText(parsed.sni)
            dialogBinding.wsHostInput.setText(parsed.wsHost)
            dialogBinding.tlsSwitch.isChecked = parsed.useTls
            Toast.makeText(this, "已自动填充，可继续编辑后保存", Toast.LENGTH_SHORT).show()
        }

        dialogBinding.parseLinkButton.setOnClickListener {
            applyParsedLink(dialogBinding.linkInput.text.toString().trim())
        }
        dialogBinding.scanLinkButton.setOnClickListener {
            launchScanner { raw ->
                dialogBinding.linkInput.setText(raw)
                applyParsedLink(raw)
            }
        }

        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "添加配置" else "编辑配置")
            .setView(dialogBinding.root)
            .setPositiveButton("保存") { _, _ ->
                val newConfig = current.copy(
                    name = dialogBinding.nameInput.text.toString().trim(),
                    host = dialogBinding.hostInput.text.toString().trim(),
                    port = dialogBinding.portInput.text.toString().trim().ifBlank { "443" },
                    path = dialogBinding.pathInput.text.toString().trim().ifBlank { "/" },
                    uuid = dialogBinding.uuidInput.text.toString().trim(),
                    token = dialogBinding.tokenInput.text.toString().trim(),
                    sni = dialogBinding.sniInput.text.toString().trim(),
                    wsHost = dialogBinding.wsHostInput.text.toString().trim(),
                    useTls = dialogBinding.tlsSwitch.isChecked
                )
                if (!newConfig.isValid()) {
                    Toast.makeText(this, "服务器地址和 UUID 不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (newConfig.name.isBlank()) newConfig.name = newConfig.host

                ConfigStore.upsert(this, newConfig)
                if (ConfigStore.getSelectedId(this) == null) {
                    ConfigStore.setSelectedId(this, newConfig.id)
                }
                refreshConfigList()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmDelete(config: VpnConfig) {
        AlertDialog.Builder(this)
            .setTitle("删除配置")
            .setMessage("确定要删除 \"${config.name.ifBlank { config.host }}\" 吗？")
            .setPositiveButton("删除") { _, _ ->
                ConfigStore.delete(this, config.id)
                refreshConfigList()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---- Export / share (link + QR code) ----

    private fun showExportDialog(config: VpnConfig) {
        val link = VlessLink.build(config)
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_qr_code, null)
        val qrImage = dialogView.findViewById<ImageView>(R.id.qrImage)
        val qrCaption = dialogView.findViewById<TextView>(R.id.qrCaption)
        qrCaption.text = config.name.ifBlank { config.host }

        val bitmap = QrCodeGenerator.generate(link)
        if (bitmap != null) {
            qrImage.setImageBitmap(bitmap)
        } else {
            Toast.makeText(this, "二维码生成失败", Toast.LENGTH_SHORT).show()
        }

        AlertDialog.Builder(this)
            .setTitle("分享配置")
            .setView(dialogView)
            .setPositiveButton("分享二维码") { _, _ ->
                if (bitmap != null) {
                    shareQrBitmap(bitmap, config.name.ifBlank { config.host })
                }
            }
            .setNegativeButton("复制链接") { _, _ ->
                copyToClipboard(link)
            }
            .setNeutralButton("关闭", null)
            .show()
    }

    private fun shareQrBitmap(bitmap: Bitmap, name: String) {
        try {
            val cacheDirForQr = File(cacheDir, "qrcodes")
            cacheDirForQr.mkdirs()
            val file = File(cacheDirForQr, "vless_qr_${System.currentTimeMillis()}.png")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "分享二维码 - $name"))
        } catch (e: Exception) {
            Toast.makeText(this, "分享失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun copyToClipboard(link: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("vless link", link))
        Toast.makeText(this, "链接已复制到剪贴板", Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 15000L
        private const val DISCONNECT_TIMEOUT_MS = 8000L
    }
}
