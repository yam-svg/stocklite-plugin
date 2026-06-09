package com.stocklite.plugin.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.stocklite.plugin.service.ChartDataService
import com.stocklite.plugin.state.StockliteState
import com.stocklite.plugin.util.L10n
import java.awt.*
import javax.swing.*

/**
 * 内嵌走势图面板（嵌入各行情面板底部，替代弹窗方案）。
 *
 * 初始不可见；调用 [showChart] 展开，点击 ✕ 收起。
 * 复用同一个 [JBCefBrowser] 实例，避免重复创建。
 */
class InlineChartPanel : JPanel(BorderLayout()) {

    companion object {
        /** 展开后固定高度（px） */
        const val PANEL_HEIGHT = 260
    }

    private var browser: JBCefBrowser? = null
    private var loadId = 0                     // 取消过时请求

    private val infoLabel = JLabel("", SwingConstants.LEFT)
    private val closeBtn  = JButton("✕")
    private val browserHolder = JPanel(BorderLayout())

    init {
        isVisible = false
        preferredSize = Dimension(0, PANEL_HEIGHT)
        border = BorderFactory.createMatteBorder(1, 0, 0, 0, Color(0x3A3A5A))

        /* ── 标题栏 ── */
        val header = JPanel(BorderLayout()).apply {
            background = Color(0x252538)
            border = BorderFactory.createEmptyBorder(4, 10, 4, 4)
        }
        infoLabel.font = infoLabel.font.deriveFont(12f)
        closeBtn.apply {
            preferredSize = Dimension(26, 26)
            isBorderPainted    = false
            isContentAreaFilled = false
            isFocusPainted     = false
            font = font.deriveFont(Font.BOLD, 14f)
            toolTipText = L10n.chartCloseTip
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addActionListener { close() }
        }
        header.add(infoLabel, BorderLayout.CENTER)
        header.add(closeBtn,  BorderLayout.EAST)

        add(header,        BorderLayout.NORTH)
        add(browserHolder, BorderLayout.CENTER)
    }

    // ── 公开 API ────────────────────────────────────────────────────────

    /**
     * 展开并加载指定标的的日内走势图。
     * @param displayName   显示名称，如 "贵州茅台"
     * @param displaySymbol 显示代码，如 "sh600519"
     * @param changePercent 当前涨跌幅（用于标题着色）
     * @param prevClose     昨收价；> 0 时 Y 轴显示涨跌幅 %
     * @param fetchData     后台线程调用的数据获取函数
     */
    fun showChart(
        displayName: String,
        displaySymbol: String,
        changePercent: Double,
        prevClose: Double,
        fetchData: () -> List<ChartDataService.ChartPoint>
    ) {
        // 更新关闭按钮 tooltip（语言可能已切换）
        closeBtn.toolTipText = L10n.chartCloseTip

        val scheme = StockliteState.getInstance().colorScheme
        val color  = pctHexColor(changePercent, scheme)
        val sign   = if (changePercent >= 0) "+" else ""
        val pct    = "%.2f".format(changePercent)
        infoLabel.text = "<html><b>$displayName</b>&nbsp;&nbsp;" +
            "<span style='color:#888aaa;font-size:11px'>$displaySymbol</span>&nbsp;&nbsp;" +
            "<b style='color:$color'>$sign${pct}%</b></html>"

        isVisible = true
        revalidate(); repaint()

        val id = ++loadId

        if (!JBCefApp.isSupported()) {
            showFallback(L10n.chartUnsupported)
            return
        }

        val b = ensureBrowser()
        b.loadHTML(loadingHtml(), "http://stocklite.local/")

        ApplicationManager.getApplication().executeOnPooledThread {
            val points = fetchData()
            SwingUtilities.invokeLater {
                if (loadId != id) return@invokeLater   // 已被更新的请求覆盖
                b.loadHTML(
                    if (points.isEmpty()) errorHtml()
                    else buildChartHtml(points, prevClose, changePercent),
                    "http://stocklite.local/"
                )
            }
        }
    }

    fun close() {
        loadId++         // 让任何进行中的加载失效
        isVisible = false
        revalidate(); repaint()
    }

    /** 在父组件销毁时调用，释放 JBCefBrowser 资源 */
    fun disposeResources() {
        browser?.dispose()
        browser = null
    }

    // ── 私有工具 ────────────────────────────────────────────────────────

    private fun ensureBrowser(): JBCefBrowser {
        browser?.let { return it }
        val b = JBCefBrowser().also { browser = it }
        browserHolder.removeAll()
        browserHolder.add(b.component, BorderLayout.CENTER)
        browserHolder.revalidate()
        return b
    }

    private fun showFallback(msg: String) {
        browserHolder.removeAll()
        browserHolder.add(JLabel(msg, SwingConstants.CENTER), BorderLayout.CENTER)
        browserHolder.revalidate()
    }

    private fun pctHexColor(pct: Double, scheme: String) = when {
        scheme == "NONE" -> "#888888"
        pct >= 0 -> if (scheme == "RED_DOWN") "#26a69a" else "#ef5350"
        else     -> if (scheme == "RED_DOWN") "#ef5350" else "#26a69a"
    }

    // ── HTML 生成 ───────────────────────────────────────────────────────

    private fun loadingHtml() = minPage("<div>${L10n.chartLoading}</div>", "#cdd6f4")
    private fun errorHtml()   = minPage("<div>${L10n.chartNoData}</div>",  "#f38ba8")

    private fun minPage(body: String, color: String) = """
        <!DOCTYPE html><html><head><meta charset="UTF-8">
        <style>body{margin:0;display:flex;align-items:center;justify-content:center;
        height:100vh;background:#1e1e2e;color:$color;font:13px sans-serif;}</style>
        </head><body>$body</body></html>""".trimIndent()

    private fun buildChartHtml(
        points: List<ChartDataService.ChartPoint>,
        prevClose: Double,
        changePercent: Double
    ): String {
        val scheme = StockliteState.getInstance().colorScheme

        data class Pal(
            val upL: String, val upF1: String, val upF2: String,
            val dnL: String, val dnF1: String, val dnF2: String
        )
        val pal = when (scheme) {
            "RED_DOWN" -> Pal("#26a69a","rgba(38,166,154,.30)","rgba(38,166,154,.05)",
                              "#ef5350","rgba(239,83,80,.05)","rgba(239,83,80,.30)")
            "NONE"     -> Pal("#4e9af1","rgba(78,154,241,.30)","rgba(78,154,241,.05)",
                              "#4e9af1","rgba(78,154,241,.05)","rgba(78,154,241,.30)")
            else       -> Pal("#ef5350","rgba(239,83,80,.30)","rgba(239,83,80,.05)",
                              "#26a69a","rgba(38,166,154,.05)","rgba(38,166,154,.30)")
        }

        val hasPrev = prevClose > 0.0
        val prevJs  = "%.6f".format(prevClose)
        val rawJs   = points.joinToString(",") { """{"time":${it.time},"price":${it.value}}""" }
        // "昨收" / "Prev Close" label used in the chart baseline annotation
        val prevCloseLabel = L10n.chartPrevClose.replace("'", "\\'")

        return """<!DOCTYPE html><html><head><meta charset="UTF-8">
<style>
*{margin:0;padding:0;box-sizing:border-box;}
body{background:#1e1e2e;overflow:hidden;}
#chart{width:100vw;height:100vh;position:relative;}
#tip{position:absolute;top:6px;left:10px;z-index:10;
  background:rgba(20,20,36,.92);border:1px solid #3a3a5a;
  border-radius:5px;padding:4px 8px;font:11px/1.6 system-ui,sans-serif;
  pointer-events:none;display:none;color:#cdd6f4;}
</style>
</head><body>
<div id="chart"><div id="tip"></div></div>
<script src="https://unpkg.com/lightweight-charts@4.2.0/dist/lightweight-charts.standalone.production.js"></script>
<script>
(function(){
  var PREV=$prevJs, HAS=${if (hasPrev) "true" else "false"};
  var UP='${pal.upL}', DN='${pal.dnL}';
  var raw=[$rawJs];
  var data=raw.map(function(d){
    return{time:d.time,value:HAS?((d.price-PREV)/PREV*100):d.price,price:d.price};
  });
  var priceMap={};raw.forEach(function(d){priceMap[d.time]=d.price;});

  var el=document.getElementById('chart'), tip=document.getElementById('tip');
  var chart=LightweightCharts.createChart(el,{
    width:el.clientWidth, height:el.clientHeight,
    layout:{background:{color:'#1e1e2e'},textColor:'#cdd6f4'},
    grid:{vertLines:{color:'#252538'},horzLines:{color:'#252538'}},
    crosshair:{mode:LightweightCharts.CrosshairMode.Normal},
    timeScale:{timeVisible:true,secondsVisible:false,borderColor:'#3a3a5a'},
    rightPriceScale:{borderColor:'#3a3a5a'},
    localization:{priceFormatter:HAS
      ?function(p){return(p>=0?'+':'')+p.toFixed(2)+'%';}
      :function(p){return p.toFixed(3);}},
  });

  var series;
  if(HAS){
    series=chart.addBaselineSeries({
      baseValue:{type:'price',price:0},
      topLineColor:UP, topFillColor1:'${pal.upF1}', topFillColor2:'${pal.upF2}',
      bottomLineColor:DN, bottomFillColor1:'${pal.dnF1}', bottomFillColor2:'${pal.dnF2}',
      lineWidth:2, priceLineVisible:false, lastValueVisible:true,
    });
    series.createPriceLine({price:0,color:'#666688',lineWidth:1,
      lineStyle:LightweightCharts.LineStyle.Dashed,axisLabelVisible:true,title:'$prevCloseLabel'});
  } else {
    series=chart.addAreaSeries({lineColor:UP,topColor:'${pal.upF1}',bottomColor:'${pal.upF2}',
      lineWidth:2,priceLineVisible:false,lastValueVisible:true});
  }

  series.setData(data.map(function(d){return{time:d.time,value:d.value};}));
  chart.timeScale().fitContent();

  chart.subscribeCrosshairMove(function(p){
    if(!p.point||!p.time){tip.style.display='none';return;}
    var sd=p.seriesData&&p.seriesData.get(series);
    if(!sd){tip.style.display='none';return;}
    var val=sd.value!==undefined?sd.value:(sd.lowerValue!==undefined?sd.lowerValue:null);
    if(val===null){tip.style.display='none';return;}
    var price=priceMap[p.time];
    var col=HAS?(val>=0?UP:DN):'#cdd6f4';
    var sign=val>=0?'+':'';
    var dt=new Date(p.time*1000);
    var ts=String(dt.getHours()).padStart(2,'0')+':'+String(dt.getMinutes()).padStart(2,'0');
    tip.innerHTML='<span style="color:#888aaa">'+ts+'</span>'
      +(HAS?'&nbsp;&nbsp;<b style="color:'+col+'">'+sign+val.toFixed(2)+'%</b>':'')
      +(price!=null?'&nbsp;&nbsp;<span>'+price.toFixed(3)+'</span>':'');
    tip.style.display='block';
  });

  new ResizeObserver(function(){
    chart.applyOptions({width:el.clientWidth,height:el.clientHeight});
  }).observe(el);
})();
</script></body></html>"""
    }
}
