package com.stocklite.plugin.ui.dialogs

import com.intellij.openapi.ui.DialogWrapper
import com.stocklite.plugin.util.L10n
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Image
import java.awt.RenderingHints
import javax.imageio.ImageIO
import javax.swing.BorderFactory
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTabbedPane

/**
 * “支持作者”打赏对话框：Tab 切换展示微信 / 支付宝赞赏码。
 * 收款码图片位于 resources 的 stocklite/donate/{wechat,alipay}.png，
 * 当前仓库内为占位图，替换为真实收款码后重新打包即生效。
 */
class DonateDialog : DialogWrapper(null, true) {

    init {
        title = L10n.settingsDonate
        init()
    }

    // 只保留 OK（关闭）按钮；不写显式返回类型，由 okAction 推断平台 Action 类型
    override fun createActions() = arrayOf(okAction)

    private fun imagePanel(path: String): JComponent {
        val img: Image? = try {
            javaClass.getResourceAsStream(path)?.use { ImageIO.read(it) }
        } catch (_: Exception) { null }
        if (img == null) {
            return JLabel(L10n.donateMissing).apply {
                border = BorderFactory.createEmptyBorder(40, 32, 40, 32)
            }
        }
        return object : JPanel() {
            override fun paintComponent(g: Graphics) {
                super.paintComponent(g)
                val g2 = g as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
                // 不设“不放大”上限：小图也放大到同一目标框，保证两张码切换时视觉尺寸一致
                val iw = img.getWidth(null); val ih = img.getHeight(null)
                val sc = minOf(360.0 / iw, 420.0 / ih)
                val w = (iw * sc).toInt(); val h = (ih * sc).toInt()
                g2.drawImage(img, (width - w) / 2, (height - h) / 2, w, h, null)
            }
        }.apply { preferredSize = Dimension(400, 460) }
    }

    override fun createCenterPanel(): JComponent {
        val tabs = JTabbedPane()
        tabs.addTab(L10n.donateWechat, imagePanel("/stocklite/donate/wechat.png"))
        tabs.addTab(L10n.donateAlipay, imagePanel("/stocklite/donate/alipay.png"))
        val root = JPanel(BorderLayout())
        val hint = JLabel("<html><div style='width:340px'>${L10n.donateHint}</div></html>").apply {
            border = BorderFactory.createEmptyBorder(4, 10, 8, 10)
        }
        root.add(tabs, BorderLayout.CENTER)
        root.add(hint, BorderLayout.SOUTH)
        return root
    }
}
