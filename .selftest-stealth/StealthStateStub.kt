package com.stocklite.plugin.state

/** 无 IDE 环境替身：仅满足 StealthName 对 State 的读取 */
class StockliteState {
    var stealthMode: String = "OFF"
    var stealthUiText: Boolean = false
    companion object {
        private val inst = StockliteState()
        @JvmStatic fun getInstance(): StockliteState = inst
    }
}
