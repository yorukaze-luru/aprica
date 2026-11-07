package app.webgen.generator

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView

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

    private var appliedThemeMode = -1

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(ThemePrefs.wrap(newBase))
        appliedThemeMode = ThemePrefs.getMode(newBase)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        setupThemeSpinner()

        bindDefaultSwitch(R.id.defMic, MainActivity.KEY_DEF_MIC)
        bindDefaultSwitch(R.id.defCamera, MainActivity.KEY_DEF_CAMERA)
        bindDefaultSwitch(R.id.defLocation, MainActivity.KEY_DEF_LOCATION)
        bindDefaultSwitch(R.id.defNotifications, MainActivity.KEY_DEF_NOTIFICATIONS)

        updateStatus = findViewById(R.id.updateStatus)
        findViewById<TextView>(R.id.versionText).text =
            "現在のバージョン: アプリカ Push v${GeneratorEngine.APP_VERSION_NAME}"
        findViewById<Button>(R.id.checkUpdateButton).setOnClickListener {
            checkUpdate()
        }
        findViewById<Button>(R.id.backButton).setOnClickListener { finish() }
    }

    private fun prefs() = getSharedPreferences("settings", MODE_PRIVATE)

    private fun setupThemeSpinner() {
        val spinner = findViewById<Spinner>(R.id.themeSpinner)
        val labels = listOf("端末の設定に合わせる", "ライト", "ダーク")
        spinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, labels
        )
        var binding = true
        spinner.setSelection(ThemePrefs.getMode(this))
        binding = false
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?, view: View?, position: Int, id: Long
            ) {
                styleSpinnerText(spinner)
                if (binding) return
                if (position != ThemePrefs.getMode(this@SettingsActivity)) {
                    ThemePrefs.setMode(this@SettingsActivity, position)
                    recreate()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        spinner.post { styleSpinnerText(spinner) }
    }

    /** The spinner's closed view uses the theme's default text color,
     *  which stays dark; restyle it for the dark card background. */
    private fun styleSpinnerText(spinner: Spinner) {
        val tv = spinner.getChildAt(0) as? TextView ?: return
        tv.setTextColor(
            if (ThemePrefs.isNight(this)) 0xFFE8F4F1.toInt() else 0xFF173A3A.toInt()
        )
    }

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
        UpdateChecker.fetchLatest(UpdateChecker.DEFAULT_REPO) { tag, pageUrl, assetUrl, error ->
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
                        UpdateFlow.prompt(this, tag, pageUrl!!, assetUrl)
                    }
                    else ->
                        updateStatus.text =
                            "最新です（アプリカ Push v${GeneratorEngine.APP_VERSION_NAME}）"
                }
            }
        }
    }

}
