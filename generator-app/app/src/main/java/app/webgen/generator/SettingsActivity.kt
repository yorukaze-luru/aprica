package app.webgen.generator

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * Settings page (v1.3): everything that is not APK creation lives here,
 * reached from the hamburger button on the main screen.
 *
 * - Default values for the permission toggles on the creation form.
 *   Saving bumps a revision counter; MainActivity re-applies the
 *   defaults to its form when the revision changes.
 * - Generator update check against the baked-in GitHub repo
 *   (button only; there is no repo input field).
 * - Notes about installing generated APKs and Google Play Protect.
 */
class SettingsActivity : Activity() {

    private lateinit var updateStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        bindDefaultSwitch(R.id.defMic, MainActivity.KEY_DEF_MIC)
        bindDefaultSwitch(R.id.defCamera, MainActivity.KEY_DEF_CAMERA)
        bindDefaultSwitch(R.id.defLocation, MainActivity.KEY_DEF_LOCATION)
        bindDefaultSwitch(R.id.defNotifications, MainActivity.KEY_DEF_NOTIFICATIONS)

        updateStatus = findViewById(R.id.updateStatus)
        findViewById<TextView>(R.id.versionText).text =
            "現在のバージョン: アプリカ v${GeneratorEngine.APP_VERSION_NAME}"
        findViewById<Button>(R.id.checkUpdateButton).setOnClickListener {
            checkUpdate()
        }
        findViewById<Button>(R.id.backButton).setOnClickListener { finish() }
    }

    private fun prefs() = getSharedPreferences("settings", MODE_PRIVATE)

    private fun bindDefaultSwitch(id: Int, key: String) {
        val sw = findViewById<Switch>(id)
        sw.isChecked = prefs().getBoolean(key, false)
        sw.setOnCheckedChangeListener { _, checked ->
            prefs().edit()
                .putBoolean(key, checked)
                .putLong(
                    MainActivity.KEY_DEF_REV,
                    prefs().getLong(MainActivity.KEY_DEF_REV, 0) + 1
                )
                .apply()
        }
    }

    private fun checkUpdate() {
        updateStatus.text = "更新を確認しています…"
        UpdateChecker.fetchLatest(UpdateChecker.DEFAULT_REPO) { tag, pageUrl, error ->
            runOnUiThread {
                when {
                    error != null ->
                        updateStatus.text = "更新の確認に失敗しました: $error"
                    tag == null ->
                        updateStatus.text = "リリースが見つかりませんでした"
                    UpdateChecker.compareVersions(
                        tag, GeneratorEngine.APP_VERSION_NAME
                    ) > 0 -> {
                        updateStatus.text =
                            "新しいバージョン $tag があります"
                        showUpdateDialog(tag, pageUrl!!)
                    }
                    else ->
                        updateStatus.text =
                            "最新です（アプリカ v${GeneratorEngine.APP_VERSION_NAME}）"
                }
            }
        }
    }

    private fun showUpdateDialog(tag: String, pageUrl: String) {
        if (isFinishing) return
        AlertDialog.Builder(this)
            .setTitle("新しいバージョンがあります")
            .setMessage(
                "アプリカ $tag が GitHub Releases で公開されています。" +
                    "新しいAPKも同じ署名キーで署名されているため、そのまま上書きインストールできます。"
            )
            .setPositiveButton("リリースページを開く") { _, _ ->
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(pageUrl)))
                } catch (e: Exception) {
                    Toast.makeText(this, "ブラウザを開けませんでした", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("あとで", null)
            .show()
    }
}
