package com.stocklite.plugin.ui.common

import javax.swing.JComponent

/**
 * JCEF 浏览器包装：com.intellij.ui.jcef.* 类型只允许出现在本类方法体内部（全限定名），
 * 绝不出现在任何 AWT/Swing 子类的字段或方法签名中。
 *
 * 原因：IDEA 2026.2（262）起 JCEF 从平台核心拆分为独立插件 com.intellij.modules.jcef，
 * 插件类加载器未依赖该模块时 JBCefBrowser 不可见。而 AWT Component 构造函数会反射
 * 枚举子类全部声明方法（checkCoalescing → getDeclaredMethods0），只要签名里引用
 * JBCefBrowser，面板一构造就抛 NoClassDefFoundError，isSupported() 兜底根本执行不到。
 * 本类的类型签名全部使用 Any/Component，首次触碰发生在方法调用时且被 try/catch 保护，
 * JCEF 缺失时安全降级为“图表不可用”提示而不是崩掉整个工具窗口。
 */
class CefChartBrowser private constructor(private val browser: Any) {

    /** 可放入 Swing 容器的浏览器组件（仅创建成功后调用） */
    val component: JComponent
        get() = (browser as com.intellij.ui.jcef.JBCefBrowser).component

    fun loadHtml(html: String, url: String) {
        try {
            (browser as com.intellij.ui.jcef.JBCefBrowser).loadHTML(html, url)
        } catch (t: Throwable) {
            // JCEF 在极端环境下可能中途不可用：吞掉错误，图表区域保持空白而不是打断 EDT
        }
    }

    fun dispose() {
        try {
            (browser as com.intellij.ui.jcef.JBCefBrowser).dispose()
        } catch (t: Throwable) {
            // 卸载阶段忽略
        }
    }

    companion object {
        /** 当前 IDE 是否支持 JCEF；类加载失败（未声明依赖或产品不内置）一律返回 false */
        fun isSupported(): Boolean = try {
            com.intellij.ui.jcef.JBCefApp.isSupported()
        } catch (t: Throwable) {
            false
        }

        /** 创建浏览器实例；JCEF 不可用时返回 null，调用方负责展示降级提示 */
        fun create(): CefChartBrowser? = try {
            CefChartBrowser(com.intellij.ui.jcef.JBCefBrowser())
        } catch (t: Throwable) {
            null
        }
    }
}
