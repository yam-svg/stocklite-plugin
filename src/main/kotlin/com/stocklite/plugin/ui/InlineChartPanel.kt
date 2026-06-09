package com.stocklite.plugin.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.stocklite.plugin.service.ChartDataService
import com.stocklite.plugin.state.StockliteState
import com.stocklite.plugin.util.L10n
import java.awt.*
import javax.swing.*

class InlineChartPanel : JPanel(BorderLayout()) {

    companion object {
        const val PANEL_HEIGHT = 280

        // Period constants
        const val PERIOD_INTRADAY = "INTRADAY"
        const val PERIOD_DAILY    = "DAILY"
        const val PERIOD_WEEKLY   = "WEEKLY"
        const val PERIOD_MONTHLY  = "MONTHLY"
    }

    private var browser: JBCefBrowser? = null
    private var loadId = 0

    // Current chart state for reload-on-period-change
    private var currentName      = ""
    private var currentSymbol    = ""
    private var currentChangePct = 0.0
    private var currentPrevClose = 0.0
    private var currentFetchIntraday: (() -> List<ChartDataService.ChartPoint>)? = null
    private var currentPeriod    = PERIOD_INTRADAY

    private val infoLabel     = JLabel("", SwingConstants.LEFT)
    private val closeBtn      = JButton("✕")
    private val browserHolder = JPanel(BorderLayout())

    // Period toggle buttons
    private val periodBtns = linkedMapOf(
        PERIOD_INTRADAY to JButton(),
        PERIOD_DAILY    to JButton(),
        PERIOD_WEEKLY   to JButton(),
        PERIOD_MONTHLY  to JButton()
    )

    init {
        isVisible = false
        preferredSize = Dimension(0, PANEL_HEIGHT)
        border = BorderFactory.createMatteBorder(1, 0, 0, 0, Color(0x3A3A5A))

        // ── 标题栏 ──
        val header = JPanel(BorderLayout()).apply {
            background = Color(0x252538)
            border = BorderFactory.createEmptyBorder(4, 10, 4, 4)
        }
        infoLabel.font = infoLabel.font.deriveFont(12f)
        closeBtn.apply {
            preferredSize = Dimension(26, 26)
            isBorderPainted = false; isContentAreaFilled = false; isFocusPainted = false
            font = font.deriveFont(Font.BOLD, 14f)
            toolTipText = L10n.chartCloseTip
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addActionListener { close() }
        }

        // ── 周期切换栏 ──
        val periodBar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
            background = Color(0x1e1e2e)
            border = BorderFactory.createEmptyBorder(0, 6, 0, 6)
        }
        periodBtns.forEach { (key, btn) ->
            btn.apply {
                isBorderPainted = false; isContentAreaFilled = false; isFocusPainted = false
                font = font.deriveFont(11f)
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                foreground = Color(0x888aaa)
                preferredSize = Dimension(38, 22)
                addActionListener { selectPeriod(key) }
            }
            periodBar.add(btn)
        }
        updatePeriodButtonTexts()
        highlightPeriodBtn(PERIOD_INTRADAY)

        header.add(infoLabel, BorderLayout.CENTER)
        header.add(closeBtn,  BorderLayout.EAST)

        val topSection = JPanel(BorderLayout())
        topSection.add(header,    BorderLayout.NORTH)
        topSection.add(periodBar, BorderLayout.SOUTH)

        add(topSection,    BorderLayout.NORTH)
        add(browserHolder, BorderLayout.CENTER)
    }

    fun showChart(
        displayName: String,
        displaySymbol: String,
        changePercent: Double,
        prevClose: Double,
        fetchData: () -> List<ChartDataService.ChartPoint>
    ) {
        closeBtn.toolTipText = L10n.chartCloseTip
        updatePeriodButtonTexts()

        currentName          = displayName
        currentSymbol        = displaySymbol
        currentChangePct     = changePercent
        currentPrevClose     = prevClose
        currentFetchIntraday = fetchData

        val scheme = StockliteState.getInstance().colorScheme
        val color  = pctHexColor(changePercent, scheme)
        val sign   = if (changePercent >= 0) "+" else ""
        val pct    = "%.2f".format(changePercent)
        infoLabel.text = "<html><b>$displayName</b>&nbsp;&nbsp;" +
            "<span style='color:#888aaa;font-size:11px'>$displaySymbol</span>&nbsp;&nbsp;" +
            "<b style='color:$color'>$sign${pct}%</b></html>"

        // Reset to intraday on new chart
        currentPeriod = PERIOD_INTRADAY
        highlightPeriodBtn(PERIOD_INTRADAY)

        isVisible = true
        revalidate(); repaint()
        loadCurrentPeriod()
    }

    fun close() {
        loadId++
        isVisible = false
        revalidate(); repaint()
    }

    fun disposeResources() { browser?.dispose(); browser = null }

    private fun selectPeriod(period: String) {
        if (currentSymbol.isEmpty()) return
        currentPeriod = period
        highlightPeriodBtn(period)
        loadCurrentPeriod()
    }

    private fun loadCurrentPeriod() {
        if (!JBCefApp.isSupported()) { showFallback(L10n.chartUnsupported); return }
        val b = ensureBrowser()
        b.loadHTML(loadingHtml(), "http://stocklite.local/")
        val id = ++loadId
        val sym = currentSymbol
        val period = currentPeriod
        val fetchIntraday = currentFetchIntraday
        val prevClose = currentPrevClose
        val changePercent = currentChangePct

        ApplicationManager.getApplication().executeOnPooledThread {
            val points = when (period) {
                PERIOD_INTRADAY -> fetchIntraday?.invoke() ?: emptyList()
                PERIOD_DAILY    -> ChartDataService.getHistoryKLine(sym, "daily",   100)
                PERIOD_WEEKLY   -> ChartDataService.getHistoryKLine(sym, "weekly",  52)
                PERIOD_MONTHLY  -> ChartDataService.getHistoryKLine(sym, "monthly", 36)
                else            -> fetchIntraday?.invoke() ?: emptyList()
            }
            val usePrevClose = period == PERIOD_INTRADAY  // only intraday uses prevClose baseline
            SwingUtilities.invokeLater {
                if (loadId != id) return@invokeLater
                b.loadHTML(
                    if (points.isEmpty()) errorHtml()
                    else buildChartHtml(points, if (usePrevClose) prevClose else 0.0, changePercent, isIntraday = period == PERIOD_INTRADAY),
                    "http://stocklite.local/"
                )
            }
        }
    }

    private fun updatePeriodButtonTexts() {
        periodBtns[PERIOD_INTRADAY]?.text = L10n.chartPeriodIntraday
        periodBtns[PERIOD_DAILY]?.text    = L10n.chartPeriodDaily
        periodBtns[PERIOD_WEEKLY]?.text   = L10n.chartPeriodWeekly
        periodBtns[PERIOD_MONTHLY]?.text  = L10n.chartPeriodMonthly
    }

    private fun highlightPeriodBtn(selected: String) {
        periodBtns.forEach { (key, btn) ->
            if (key == selected) {
                btn.foreground = Color(0xcdd6f4)
                btn.font = btn.font.deriveFont(Font.BOLD, 11f)
            } else {
                btn.foreground = Color(0x888aaa)
                btn.font = btn.font.deriveFont(Font.PLAIN, 11f)
            }
        }
    }

    private fun ensureBrowser(): JBCefBrowser {
        browser?.let { return it }
        val b = JBCefBrowser().also { browser = it }
        browserHolder.removeAll(); browserHolder.add(b.component, BorderLayout.CENTER); browserHolder.revalidate()
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
        changePercent: Double,
        isIntraday: Boolean
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

        val hasPrev = isIntraday && prevClose > 0.0
        val prevJs  = "%.6f".format(prevClose)
        // For OHLC history, ChartPoint.value is the close price
        val rawJs   = points.joinToString(",") { """{"time":${it.time},"price":${it.value}}""" }
        val prevCloseLabel = L10n.chartPrevClose.replace("'", "\\'")
        // For daily/weekly/monthly, use candlestick-style area (close only) with absolute price on Y-axis
        val timeVisible = if (isIntraday) "true" else "false"

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
    timeScale:{timeVisible:$timeVisible,secondsVisible:false,borderColor:'#3a3a5a'},
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
