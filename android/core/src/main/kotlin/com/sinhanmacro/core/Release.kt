package com.sinhanmacro.core

import org.json.JSONObject

data class ReleaseInfo(
    val versionCode: Int,
    val tag: String,
    val title: String,
    val apkName: String,
    val apkUrl: String,
    val size: Long,
    val sha256: String?,
    val notes: String,
)

/**
 * GitHub Releases API(`/releases/latest`) 응답 → 업데이트 정보.
 * 태그는 `v<빌드번호>` (예: v12), 빌드번호 = 앱 versionCode.
 */
object ReleaseParser {
    private val TAG = Regex("^v(\\d+)$")

    fun parse(json: String): ReleaseInfo? {
        val o = try {
            JSONObject(json)
        } catch (_: Exception) {
            return null
        }
        if (o.optBoolean("draft") || o.optBoolean("prerelease")) return null
        val code = TAG.matchEntire(o.optString("tag_name"))?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val assets = o.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            val name = a.optString("name")
            val url = a.optString("browser_download_url")
            if (!name.endsWith(".apk") || url.isEmpty()) continue
            val digest = a.optString("digest", "").removePrefix("sha256:").lowercase()
            return ReleaseInfo(
                versionCode = code,
                tag = o.getString("tag_name"),
                title = o.optString("name", o.getString("tag_name")),
                apkName = name,
                apkUrl = url,
                size = a.optLong("size", -1),
                sha256 = digest.takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } },
                notes = o.optString("body", ""),
            )
        }
        return null
    }

    fun isNewer(info: ReleaseInfo, currentVersionCode: Int): Boolean = info.versionCode > currentVersionCode
}
