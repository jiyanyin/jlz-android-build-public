package dev.jlz.presence.signals

import android.content.Context
import dev.jlz.presence.data.LocalLifeStore

class LifeSignalCollector(private val context: Context) {
    fun recordScreenOn() { LocalLifeStore(context).recordTimeline("life_signal", "\u5c4f\u5e55\u4eae\u8d77") }
    fun recordEnvironmentBucket(bucket: String) {
        LocalLifeStore(context).recordTimeline("life_signal", when(bucket) { "dark" -> "\u73af\u5883\u53d8\u6697\u4e86"; "bright" -> "\u73af\u5883\u5f88\u4eae"; else -> "\u73af\u5883\u5149\u7ebf\u6b63\u5e38" })
    }
    fun recordAccessoryConnected(type: String) { LocalLifeStore(context).recordTimeline("life_signal", "$type \u5df2\u8fde\u63a5") }
}
