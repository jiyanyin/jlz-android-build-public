package dev.jlz.presence.diagnostics

import android.content.Context
import android.provider.Settings
import dev.jlz.presence.data.LocalLifeStore
import dev.jlz.presence.notification.NotificationAdapter
import dev.jlz.presence.runtime.RuntimeApiClient
import dev.jlz.presence.runtime.RuntimeSettingsRepository
import dev.jlz.presence.screen.AccessibilityScreenshotCaptureAdapter
import dev.jlz.presence.screen.ScreenObservationBus
import dev.jlz.presence.screen.ScreenshotCaptureResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

enum class AcceptanceStatus {
    PASS,
    BLOCKED,
    FAIL
}

data class AcceptanceResult(
    val id: String,
    val title: String,
    val status: AcceptanceStatus,
    val detail: String
)

/**
 * V2: Health Connect and generated white-noise self-checks were removed with
 * the features themselves. This runner only verifies Runtime, notification,
 * accessibility, screenshot and overlay chains.
 */
class N0AcceptanceRunner(private val context: Context) {
    private val appContext = context.applicationContext

    suspend fun run(): List<AcceptanceResult> {
        val results = mutableListOf<AcceptanceResult>()
        val settings = RuntimeSettingsRepository(appContext).load()
        val runtimeReady =
            settings.baseUrl.isNotBlank() &&
                settings.token.isNotBlank()

        results += if (runtimeReady) {
            AcceptanceResult(
                "runtime_config",
                "Runtime 配置",
                AcceptanceStatus.PASS,
                settings.deviceId
            )
        } else {
            AcceptanceResult(
                "runtime_config",
                "Runtime 配置",
                AcceptanceStatus.BLOCKED,
                "还没有 HTTPS Runtime 地址 / Token"
            )
        }

        val api = if (runtimeReady) RuntimeApiClient(settings) else null

        if (api != null) {
            results += runCatching {
                withContext(Dispatchers.IO) {
                    api.postActivityEvent(
                        source = "native_acceptance",
                        type = "diagnostic",
                        title = "N0 自检",
                        subtitle = "Runtime round-trip",
                        metadata = JSONObject().put("device_id", settings.deviceId),
                        dedupeSeconds = 0
                    )
                }
                AcceptanceResult(
                    "runtime_roundtrip",
                    "Runtime 往返",
                    AcceptanceStatus.PASS,
                    "activity event 已接受"
                )
            }.getOrElse {
                AcceptanceResult(
                    "runtime_roundtrip",
                    "Runtime 往返",
                    AcceptanceStatus.FAIL,
                    it.message ?: it.javaClass.simpleName
                )
            }
        }

        val notification = NotificationAdapter(appContext).showMessage(
            title = "我在 · N0 自检",
            message = "这是新原生在场 App 的通知链测试。"
        )

        results += AcceptanceResult(
            "notification",
            "通知展示",
            if (notification.ok) AcceptanceStatus.PASS else AcceptanceStatus.BLOCKED,
            notification.code
        )

        results += AcceptanceResult(
            "inline_reply",
            "通知 inline reply",
            AcceptanceStatus.BLOCKED,
            if (notification.ok) {
                "通知已生成；真正的回复回传必须由一次真实通知回复完成，不能由自检伪造。"
            } else {
                "通知本身未显示，先修复通知权限。"
            }
        )

        val accessibility = ScreenObservationBus.isAvailable()

        results += AcceptanceResult(
            "accessibility",
            "无障碍 / 屏幕观察",
            if (accessibility) AcceptanceStatus.PASS else AcceptanceStatus.BLOCKED,
            if (accessibility) "service connected" else "需要启用在场无障碍服务"
        )

        when (val shot = AccessibilityScreenshotCaptureAdapter().capture()) {
            is ScreenshotCaptureResult.Captured -> {
                results += AcceptanceResult(
                    "screenshot_capture",
                    "截图捕获",
                    AcceptanceStatus.PASS,
                    shot.mimeType + " · " + shot.bytes.size + " bytes"
                )

                if (api == null) {
                    results += AcceptanceResult(
                        "screenshot_upload",
                        "截图上传",
                        AcceptanceStatus.BLOCKED,
                        "Runtime 未配置"
                    )
                } else {
                    results += runCatching {
                        val eventId = java.util.UUID.randomUUID().toString()
                        val uploaded = withContext(Dispatchers.IO) {
                            api.uploadScreenshot(
                                bytes = shot.bytes,
                                mimeType = shot.mimeType,
                                eventId = eventId
                            )
                        }
                        check(uploaded.optBoolean("ok") &&
                            uploaded.optString("event_id") == eventId) {
                            "upload_did_not_acknowledge_matching_uuid"
                        }
                        AcceptanceResult(
                            "screenshot_upload",
                            "截图上传",
                            AcceptanceStatus.PASS,
                            "Runtime 已接受同一 UUID · " + eventId
                        ).also { results += it }
                        val readback = withContext(Dispatchers.IO) {
                            api.downloadNativeCaptureBytes(eventId)
                        }
                        check(readback.contentEquals(shot.bytes)) {
                            "authenticated_image_readback_mismatch"
                        }
                        AcceptanceResult(
                            "screenshot_readback",
                            "截图原图往返",
                            AcceptanceStatus.PASS,
                            "本机与 Runtime 同 UUID 图片字节完全一致；官端视觉理解仍须人工验收。"
                        )
                    }.getOrElse {
                        AcceptanceResult(
                            "screenshot_readback",
                            "截图上传/原图回读",
                            AcceptanceStatus.FAIL,
                            it.message ?: it.javaClass.simpleName
                        )
                    }
                }
            }

            is ScreenshotCaptureResult.Unavailable -> {
                results += AcceptanceResult(
                    "screenshot_capture",
                    "截图捕获",
                    AcceptanceStatus.BLOCKED,
                    shot.reason
                )
            }
        }

        results += AcceptanceResult(
            "overlay",
            "悬浮 Presence 权限",
            if (Settings.canDrawOverlays(appContext)) AcceptanceStatus.PASS else AcceptanceStatus.BLOCKED,
            if (Settings.canDrawOverlays(appContext)) {
                "overlay permission granted"
            } else {
                "需要允许显示在其他应用上层"
            }
        )

        results += AcceptanceResult(
            "removed_health_audio",
            "已下线功能（Health Connect / 白噪音 / 陪睡）",
            AcceptanceStatus.PASS,
            "V2 已移除，不再提供自检"
        )

        val pass = results.count { it.status == AcceptanceStatus.PASS }
        val blocked = results.count { it.status == AcceptanceStatus.BLOCKED }
        val fail = results.count { it.status == AcceptanceStatus.FAIL }

        withContext(Dispatchers.IO) {
            LocalLifeStore(appContext).recordTimeline(
                type = "diagnostic",
                title = "N0 自检",
                detail = "PASS " + pass + " · BLOCKED " + blocked + " · FAIL " + fail
            )
        }

        return results
    }
}
