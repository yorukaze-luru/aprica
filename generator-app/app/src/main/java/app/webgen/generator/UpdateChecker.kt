package app.webgen.generator

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Shared GitHub Releases update check for the generator app itself.
 * Used silently at startup (MainActivity) and manually from the
 * settings page (SettingsActivity).
 *
 * The update source is baked in: releases of
 * [DEFAULT_REPO] (yorukaze-luru/aprica). There is no user-editable
 * repo field since v1.3; the same repo is also written into generated
 * apps as their template-update feed.
 */
object UpdateChecker {

    const val DEFAULT_REPO = "yorukaze-luru/aprica"

    fun feedUrl(repo: String): String =
        "https://api.github.com/repos/$repo/releases/latest"

    /**
     * Fetches the latest release on a background thread.
     * Callback parameters: (tag, release page URL, error message);
     * exactly one of tag / error is non-null. The callback runs on the
     * background thread; callers must hop to the UI thread themselves.
     */
    fun fetchLatest(
        repo: String,
        callback: (tag: String?, pageUrl: String?, error: String?) -> Unit
    ) {
        thread {
            try {
                val conn = URL(feedUrl(repo)).openConnection() as HttpURLConnection
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                val text = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                val json = JSONObject(text)
                val tag = json.optString("tag_name", "")
                val pageUrl = json.optString(
                    "html_url", "https://github.com/$repo/releases"
                )
                if (tag.isEmpty()) {
                    callback(null, null, "リリースが見つかりませんでした")
                } else {
                    callback(tag, pageUrl, null)
                }
            } catch (e: Exception) {
                callback(null, null, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /** Numeric dotted-version compare: "v1.10" > "1.2" etc. */
    fun compareVersions(a: String, b: String): Int {
        val pa = parseVersion(a)
        val pb = parseVersion(b)
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    private fun parseVersion(v: String): List<Int> {
        val core = v.trim().removePrefix("v").removePrefix("V")
            .substringBefore("-")
        return core.split(".").map { part ->
            part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
        }
    }
}
