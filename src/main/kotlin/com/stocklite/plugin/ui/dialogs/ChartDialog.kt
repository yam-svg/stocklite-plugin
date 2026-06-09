package com.stocklite.plugin.ui.dialogs

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.stocklite.plugin.service.ChartDataService
import com.stocklite.plugin.state.StockliteState
import java.awt.Dimension
import javax.swing.*

/**
 * 日内走势图弹窗
 *
 * 使用 JBCefBrowser（JCEF）渲染 lightweight-charts 分时折线图。
 * 若当前 IDE 不支持 JCEF，则显示友好提示。
 *
 * @param displayName    标题显示名称，如 "贵州茅台"
 * @param displaySymbol  标题显示代码，如 "sh600519"
 * @param changePercent  当前涨跌幅（用于标题颜色）
 * @param prevClose      昨收价；> 0 时绘制基准线 + BaselineSeries；否则绘制普通 AreaSeries
 * @param fetchData      在后台线程调用的数据获取函数
 */
class ChartDialog(
    private val displayName: String,
    private val displaySymbol: String,
    private val changePercent: Double,
    private val prevClose: Double,
    private val fetchData: () -> List<ChartDataService.ChartPoint>
) : DialogWrapper(null, true) {

    private var browser: JBCefBrowser? = null

    init {
        title = "$displayName  ($displaySymbol)  —  日内走势"
        init()
    }

    override fun getPreferredSize(): Dimension = Dimension(960, 580)

    override fun createCenterPanel(): JComponent {
        if (!JBCefApp.isSupported()) {
            return JLabel(
                "<html><center>当前 IDE 不支持内嵌浏览器（JCEF），无法显示图表。<br/>" +
                    "请确认 JetBrains IDE 版本 ≥ 2023.3，且未以无头模式启动。</center></html>",
                SwingConstants.CENTER
            ).also { it.preferredSize = Dimension(760, 480) }
        }

        val b = JBCefBrowser().also { browser = it }
        b.loadHTML(loadingHtml(), "http://stocklite.local/")

        // 在后台线程获取数据，回到 EDT 更新图表
        ApplicationManager.getApplication().executeOnPooledThread {
            val points = fetchData()
            SwingUtilities.invokeLater {
                if (points.isEmpty()) b.loadHTML(errorHtml(), "http://stocklite.local/")
                else b.loadHTML(buildChartHtml(points), "http://stocklite.local/")
            }
        }

        return b.component.also { it.preferredSize = Dimension(960, 540) }
    }

    // 只保留关闭按钮
    override fun createActions(): Array<Action> = arrayOf(okAction)

    override fun dispose() {
        browser?.dispose()
        super.dispose()
    }

    // ── HTML 生成 ───────────────────────────────────────────────────────

    private fun loadingHtml() = minimalPage("<div>数据加载中…</div>", "#cdd6f4")
    private fun errorHtml()   = minimalPage("<div>暂无当日数据</div>", "#f38ba8")

    private fun minimalPage(content: String, color: String) = """
        <!DOCTYPE html><html><head><meta charset="UTF-8">
        <style>body{margin:0;display:flex;align-items:center;justify-content:center;
        height:100vh;background:#1e1e2e;color:$color;font:16px/1.5 sans-serif;}</style>
        </head><body>$content</body></html>
    """.trimIndent()

    private fun buildChartHtml(points: List<ChartDataService.ChartPoint>): String {
        val scheme = StockliteState.getInstance().colorScheme

        data class Palette(
            val upLine: String, val upF1: String, val upF2: String,
            val dnLine: String, val dnF1: String, val dnF2: String
        )
        val pal = when (scheme) {
            "RED_DOWN" -> Palette(
                "#26a69a", "rgba(38,166,154,0.30)", "rgba(38,166,154,0.05)",
                "#ef5350", "rgba(239,83,80,0.05)",  "rgba(239,83,80,0.30)"
            )
            "NONE"     -> Palette(
                "#4e9af1", "rgba(78,154,241,0.30)", "rgba(78,154,241,0.05)",
                "#4e9af1", "rgba(78,154,241,0.05)", "rgba(78,154,241,0.30)"
            )
            else       -> Palette(   // RED_UP（默认，中国习惯）
                "#ef5350", "rgba(239,83,80,0.30)",  "rgba(239,83,80,0.05)",
                "#26a69a", "rgba(38,166,154,0.05)", "rgba(38,166,154,0.30)"
            )
        }

        val titleColor = when {
            scheme == "NONE"   -> "#c0c0c0"
            changePercent >= 0 -> pal.upLine
            else               -> pal.dnLine
        }
        val pctStr = (if (changePercent >= 0) "+" else "") + "%.2f".format(changePercent) + "%"

        // rawData 包含原始价格，供 tooltip 展示
        val rawDataJs = points.joinToString(",") { """{"time":${it.time},"price":${it.value}}""" }

        // prevClose > 0 时：Y 轴显示相对昨收的涨跌幅 %；否则显示绝对价格
        val hasPrev = prevClose > 0.0
        val prevCloseJs = "%.6f".format(prevClose)

        return """<!DOCTYPE html>
<html>
<head>
<meta charset="UTF-8">
<style>
* { margin:0; padding:0; box-sizing:border-box; }
body { background:#1e1e2e; color:#cdd6f4; font-family:system-ui,sans-serif; overflow:hidden; }
#hdr { padding:6px 14px; display:flex; align-items:baseline; gap:12px; user-select:none; }
#hdr .nm  { font-size:14px; font-weight:600; }
#hdr .sym { font-size:12px; color:#888aaa; }
#hdr .pct { font-size:15px; font-weight:700; color:$titleColor; margin-left:auto; }
#chart { width:100vw; height:calc(100vh - 32px); position:relative; }
#tip {
  position:absolute; top:8px; left:14px; z-index:10;
  background:rgba(30,30,46,0.88); border:1px solid #3a3a5a;
  border-radius:6px; padding:6px 10px; font-size:12px; line-height:1.7;
  pointer-events:none; display:none;
}
</style>
</head>
<body>
<div id="hdr">
  <span class="nm">$displayName</span>
  <span class="sym">$displaySymbol</span>
  <span class="pct">$pctStr</span>
</div>
<div id="chart"><div id="tip"></div></div>

<script src="https://unpkg.com/lightweight-charts@4.2.0/dist/lightweight-charts.standalone.production.js"></script>
<script>
(function() {
  const PREV_CLOSE = $prevCloseJs;
  const HAS_PREV   = ${ if (hasPrev) "true" else "false" };
  const UP_COLOR   = '${pal.upLine}';
  const DN_COLOR   = '${pal.dnLine}';

  // 原始数据（用于 tooltip 显示价格）
  const rawData = [$rawDataJs];
  // 价格 → 涨跌幅转换（有昨收时）；否则直接用价格
  const chartData = rawData.map(function(d) {
    return {
      time:  d.time,
      value: HAS_PREV ? ((d.price - PREV_CLOSE) / PREV_CLOSE * 100) : d.price,
      price: d.price
    };
  });

  const el = document.getElementById('chart');
  const tip = document.getElementById('tip');

  const chart = LightweightCharts.createChart(el, {
    width:  el.clientWidth,
    height: el.clientHeight,
    layout: { background: { color: '#1e1e2e' }, textColor: '#cdd6f4' },
    grid:   { vertLines: { color: '#252538' }, horzLines: { color: '#252538' } },
    crosshair: { mode: LightweightCharts.CrosshairMode.Normal },
    timeScale: { timeVisible: true, secondsVisible: false, borderColor: '#3a3a5a' },
    rightPriceScale: {
      borderColor: '#3a3a5a',
      // Y 轴标签：有昨收则显示 ±x.xx%，否则显示价格
      scaleMargins: { top: 0.12, bottom: 0.08 },
    },
    localization: {
      priceFormatter: HAS_PREV
        ? function(p) { return (p >= 0 ? '+' : '') + p.toFixed(2) + '%'; }
        : function(p) { return p.toFixed(3); }
    },
  });

  // BaselineSeries：基准线在 0（即昨收）或绝对价格的均值
  var series;
  if (HAS_PREV) {
    series = chart.addBaselineSeries({
      baseValue:       { type: 'price', price: 0 },
      topLineColor:    UP_COLOR,
      topFillColor1:   '${pal.upF1}',
      topFillColor2:   '${pal.upF2}',
      bottomLineColor: DN_COLOR,
      bottomFillColor1:'${pal.dnF1}',
      bottomFillColor2:'${pal.dnF2}',
      lineWidth: 2,
      priceLineVisible: false,
      lastValueVisible: true,
    });
    // 昨收基准虚线（始终在 0%）
    series.createPriceLine({
      price: 0, color: '#666688', lineWidth: 1,
      lineStyle: LightweightCharts.LineStyle.Dashed,
      axisLabelVisible: true, title: '昨收',
    });
  } else {
    series = chart.addAreaSeries({
      lineColor:   UP_COLOR,
      topColor:    '${pal.upF1}',
      bottomColor: '${pal.upF2}',
      lineWidth: 2,
      priceLineVisible: false,
      lastValueVisible: true,
    });
  }

  series.setData(chartData.map(function(d) { return {time: d.time, value: d.value}; }));
  chart.timeScale().fitContent();

  // 用时间戳快速查找原始价格
  var priceByTime = {};
  chartData.forEach(function(d) { priceByTime[d.time] = d.price; });

  // 悬浮 Tooltip：时间 + 涨跌幅 + 价格
  chart.subscribeCrosshairMove(function(param) {
    if (!param.point || !param.time || !param.seriesData || !param.seriesData.size) {
      tip.style.display = 'none';
      return;
    }
    var sd = param.seriesData.get(series);
    if (!sd) { tip.style.display = 'none'; return; }
    var val   = sd.value !== undefined ? sd.value : (sd.close !== undefined ? sd.close : null);
    if (val === null) { tip.style.display = 'none'; return; }

    var price = priceByTime[param.time];
    var color = HAS_PREV ? (val >= 0 ? UP_COLOR : DN_COLOR) : '#cdd6f4';
    var sign  = val >= 0 ? '+' : '';

    // 时间标签（转换为本地 HH:MM）
    var dt  = new Date(param.time * 1000);
    var hh  = String(dt.getHours()).padStart(2, '0');
    var mm  = String(dt.getMinutes()).padStart(2, '0');

    var pctLine = HAS_PREV
      ? '<div style="font-size:15px;font-weight:700;color:' + color + '">' + sign + val.toFixed(2) + '%</div>'
      : '';
    var priceLine = price != null
      ? '<div style="font-size:13px;color:#cdd6f4">' + (HAS_PREV ? '价格 ' : '') + price.toFixed(3) + '</div>'
      : '';

    tip.innerHTML =
      '<div style="color:#888aaa;font-size:11px">' + hh + ':' + mm + '</div>' +
      pctLine + priceLine;
    tip.style.display = 'block';
  });

  new ResizeObserver(function() {
    chart.applyOptions({ width: el.clientWidth, height: el.clientHeight });
  }).observe(el);
})();
</script>
</body>
</html>"""
    }
}
