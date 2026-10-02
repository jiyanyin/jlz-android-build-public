package dev.jlz.presence.screen

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.view.Display
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

object AccessibilityScreenshotGateway {
    @Volatile
    private var service: AccessibilityService? = null

    internal fun bind(accessibilityService: AccessibilityService) {
        service = accessibilityService
    }

    internal fun unbind(accessibilityService: AccessibilityService) {
        if (service === accessibilityService) {
            service = null
        }
    }

    suspend fun capture(automatic: Boolean = false): ScreenshotCaptureResult {
        if (Build.VERSION.SDK_INT < 30) {
            return ScreenshotCaptureResult.Unavailable(
                "Accessibility screenshot requires Android 11 or newer."
            )
        }

        val current = service
            ?: return ScreenshotCaptureResult.Unavailable(
                "Accessibility service is not connected."
            )

        return suspendCancellableCoroutine { continuation ->
            current.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                current.mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(
                        screenshot: AccessibilityService.ScreenshotResult
                    ) {
                        val hardwareBuffer = screenshot.hardwareBuffer
                        try {
                            val hardwareBitmap = Bitmap.wrapHardwareBuffer(
                                hardwareBuffer,
                                screenshot.colorSpace
                            )
                            val bitmap = hardwareBitmap?.copy(
                                Bitmap.Config.ARGB_8888,
                                false
                            )
                            if (bitmap == null) {
                                if (continuation.isActive) {
                                    continuation.resume(
                                        ScreenshotCaptureResult.Unavailable(
                                            "Unable to convert screenshot buffer."
                                        )
                                    )
                                }
                                return
                            }

                            // Screenshot pixels are evidence, not source art.
                            // JPEG keeps text/UI fully readable for review while
                            // cutting upload latency dramatically versus full-screen PNG.
                            val mimeType = "image/jpeg"
                            val output = ByteArrayOutputStream()
                            val edge = maxOf(bitmap.width, bitmap.height)
                            val scaled = if (automatic && edge > 1080) {
                                val ratio = 1080.0 / edge
                                Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).toInt().coerceAtLeast(1),
                                    (bitmap.height * ratio).toInt().coerceAtLeast(1), true)
                            } else bitmap
                            check(scaled.compress(Bitmap.CompressFormat.JPEG, if (automatic) 75 else 86, output))
                            if (scaled !== bitmap) scaled.recycle()
                            hardwareBitmap?.recycle()
                            bitmap.recycle()
                            if (continuation.isActive) {
                                continuation.resume(
                                    ScreenshotCaptureResult.Captured(
                                        bytes = output.toByteArray(),
                                        mimeType = mimeType
                                    )
                                )
                            }
                        } catch (t: Throwable) {
                            if (continuation.isActive) {
                                continuation.resume(
                                    ScreenshotCaptureResult.Unavailable(
                                        t.message ?: t.javaClass.simpleName
                                    )
                                )
                            }
                        } finally {
                            hardwareBuffer.close()
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        if (continuation.isActive) {
                            continuation.resume(
                                ScreenshotCaptureResult.Unavailable(
                                    "Accessibility screenshot failed: " + errorCode
                                )
                            )
                        }
                    }
                }
            )
        }
    }
}

class AccessibilityScreenshotCaptureAdapter : ScreenshotCaptureAdapter {
    override suspend fun capture(): ScreenshotCaptureResult =
        AccessibilityScreenshotGateway.capture()
}
