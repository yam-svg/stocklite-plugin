package com.stocklite.plugin.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.stocklite.plugin.service.ChartDataService
import com.stocklite.plugin.state.StockliteState
import com.stocklite.plugin.ui.common.Fmt
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
        // 隐藏时高度归零——BorderLayout 在计算首选尺寸时不会区分可见性，
        // 固定高度会导致父容器即使图表未打开也一直预留这块空间（出现空白区域）。
        preferredSize = Dimension(0, 0)
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
        fetchData: (() -> List<ChartDataService.ChartPoint>)? = null
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

        // 无日内数据时默认显示日K，并禁用日内按钮
        val hasIntraday = fetchData != null
        periodBtns[PERIOD_INTRADAY]?.isEnabled = hasIntraday
        periodBtns[PERIOD_INTRADAY]?.foreground = if (hasIntraday) Color(0x888aaa) else Color(0x555568)
        val defaultPeriod = if (hasIntraday) PERIOD_INTRADAY else PERIOD_DAILY
        currentPeriod = defaultPeriod
        highlightPeriodBtn(defaultPeriod)

        preferredSize = Dimension(0, PANEL_HEIGHT)
        isVisible = true
        revalidate(); repaint()
        (parent as? JComponent)?.revalidate()
        loadCurrentPeriod()
    }

    /**
     * 直接显示预计算好的盈亏折线图（不走网络）。
     * 用于持仓历史盈亏场景，隐藏周期切换栏（只显示折线）。
     */
    fun showPnlChart(
        displayName: String,
        points: List<ChartDataService.ChartPoint>,
        totalPnl: Double
    ) {
        closeBtn.toolTipText = L10n.chartCloseTip
        updatePeriodButtonTexts()

        currentName      = displayName
        currentSymbol    = "__pnl__"
        currentChangePct = 0.0
        currentPrevClose = 0.0
        currentFetchIntraday = null

        val scheme = StockliteState.getInstance().colorScheme
        val color  = pctHexColor(totalPnl, scheme)
        val sign   = if (totalPnl >= 0) "+" else ""
        infoLabel.text = "<html><b>$displayName</b>&nbsp;&nbsp;" +
            "<b style='color:$color'>$sign${Fmt.value(totalPnl)}</b></html>"

        // 隐藏周期切换栏
        periodBtns.values.forEach { it.isEnabled = false; it.foreground = Color(0x555568) }

        preferredSize = Dimension(0, PANEL_HEIGHT)
        isVisible = true
        revalidate(); repaint()
        (parent as? JComponent)?.revalidate()

        if (!JBCefApp.isSupported()) { showFallback(L10n.chartUnsupported); return }
        val b = ensureBrowser()
        val id = ++loadId
        SwingUtilities.invokeLater {
            if (loadId != id) return@invokeLater
            b.loadHTML(
                if (points.isEmpty()) errorHtml()
                else buildChartHtml(points, 0.0, 0.0, isIntraday = false, period = PERIOD_DAILY),
                "http://stocklite.local/"
            )
        }
    }

    fun close() {
        loadId++
        isVisible = false
        preferredSize = Dimension(0, 0)
        revalidate(); repaint()
        (parent as? JComponent)?.revalidate()
    }

    fun disposeResources() { browser?.dispose(); browser = null }

    private fun selectPeriod(period: String) {
        if (currentSymbol.isEmpty()) return
        if (period == PERIOD_INTRADAY && currentFetchIntraday == null) return
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
                PERIOD_DAILY    -> ChartDataService.getHistoryKLine(sym, "daily",   2000)
                PERIOD_WEEKLY   -> ChartDataService.getHistoryKLine(sym, "weekly",  1000)
                PERIOD_MONTHLY  -> ChartDataService.getHistoryKLine(sym, "monthly", 300)
                else            -> fetchIntraday?.invoke() ?: emptyList()
            }
            val usePrevClose = period == PERIOD_INTRADAY  // only intraday uses prevClose baseline
            SwingUtilities.invokeLater {
                if (loadId != id) return@invokeLater
                b.loadHTML(
                    if (points.isEmpty()) errorHtml()
                    else buildChartHtml(points, if (usePrevClose) prevClose else 0.0, changePercent, isIntraday = period == PERIOD_INTRADAY, period = period),
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
                // 禁用的按钮保持灰暗色，不恢复为普通灰色
                if (btn.isEnabled) btn.foreground = Color(0x888aaa)
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
        isIntraday: Boolean,
        period: String = PERIOD_INTRADAY
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
        // 蜡烛图仅用于日/周/月K线；日内走势保持折线/面积图。
        // 且只有当绝大多数数据点都带真实开高低收时才画蜡烛图，否则保留折线/面积图（不伪造K线）
        val ohlcCount = points.count { it.hasOhlc }
        val hasOhlc = !isIntraday && points.isNotEmpty() && ohlcCount >= points.size * 0.8
        fun num(d: Double) = if (d.isFinite()) "%.6f".format(d) else "null"
        val rawJs = points.joinToString(",") {
            """{"time":${it.time},"price":${it.value},"open":${num(it.open)},"high":${num(it.high)},"low":${num(it.low)}}"""
        }
        val prevCloseLabel = L10n.chartPrevClose.replace("'", "\\'")
        val lblOpen  = L10n.chartOpen.replace("'", "\\'")
        val lblHigh  = L10n.chartHigh.replace("'", "\\'")
        val lblLow   = L10n.chartLow.replace("'", "\\'")
        val lblClose = L10n.chartClose.replace("'", "\\'")
        val timeVisible = if (isIntraday) "true" else "false"
        // 初始可见 K 线根数（滚动查看历史；日K显示半年，周K显示2年，月K显示5年）
        val initBars = when (period) {
            PERIOD_DAILY   -> 120
            PERIOD_WEEKLY  -> 104
            PERIOD_MONTHLY -> 60
            else           -> 0   // 日内：fitContent
        }

        return """<!DOCTYPE html><html><head><meta charset="UTF-8">
<style>
*{margin:0;padding:0;box-sizing:border-box;}
html,body{height:100%;}
body{background:#1e1e2e;overflow:hidden;display:flex;flex-direction:column;}
#infobar{height:22px;flex-shrink:0;padding:0 8px;
  font:11px/22px system-ui,sans-serif;white-space:nowrap;overflow:hidden;
  color:#888aaa;border-bottom:1px solid #252538;}
#chart{width:100%;flex:1;min-height:0;position:relative;}
.hl-lbl{position:absolute;font:10px/1.4 system-ui,sans-serif;white-space:nowrap;
  padding:1px 4px;border-radius:2px;pointer-events:none;z-index:5;display:none;}
</style>
</head><body>
<div id="infobar"></div><div id="chart"><div id="hi" class="hl-lbl"></div><div id="lo" class="hl-lbl"></div></div>
<script src="https://unpkg.com/lightweight-charts@4.2.0/dist/lightweight-charts.standalone.production.js"></script>
<script>
(function(){
  var PREV=$prevJs, HAS=${if (hasPrev) "true" else "false"}, HAS_OHLC=${if (hasOhlc) "true" else "false"};
  var ISINTRADAY=${if (isIntraday) "true" else "false"};
  var UP='${pal.upL}', DN='${pal.dnL}';
  var raw=[$rawJs];
  var priceMap={};raw.forEach(function(d){priceMap[d.time]=d.price;});
  // 每根K线相对上一根收盘价的涨跌幅，用于蜡烛图悬浮提示
  var pctMap={};
  for(var pi=1;pi<raw.length;pi++){
    var prevC=raw[pi-1].price;
    if(prevC>0) pctMap[raw[pi].time]=(raw[pi].price-prevC)/prevC*100;
  }

  var el=document.getElementById('chart'), tip=document.getElementById('infobar');
  var chart=LightweightCharts.createChart(el,{
    width:el.clientWidth, height:el.clientHeight,
    layout:{background:{color:'#1e1e2e'},textColor:'#cdd6f4'},
    grid:{vertLines:{color:'#252538'},horzLines:{color:'#252538'}},
    crosshair:{mode:LightweightCharts.CrosshairMode.Normal},
    timeScale:{timeVisible:$timeVisible,secondsVisible:false,borderColor:'#3a3a5a',
      tickMarkFormatter:function(t,type){
        var d=new Date(t*1000);
        var TM=LightweightCharts.TickMarkType;
        if(type===TM.Year) return d.getFullYear()+'年';
        if(type===TM.Month) return (d.getMonth()+1)+'月';
        if(type===TM.DayOfMonth) return (d.getMonth()+1)+'月'+d.getDate()+'日';
        return String(d.getHours()).padStart(2,'0')+':'+String(d.getMinutes()).padStart(2,'0');
      }},
    rightPriceScale:{borderColor:'#3a3a5a'},
    localization:{
      priceFormatter:(HAS&&!HAS_OHLC)
        ?function(p){return(p>=0?'+':'')+p.toFixed(2)+'%';}
        :function(p){return p.toFixed(3);},
      timeFormatter:function(t){
        var d=new Date(t*1000);
        if(ISINTRADAY) return d.getFullYear()+'/'+(d.getMonth()+1)+'/'+d.getDate()
          +' '+String(d.getHours()).padStart(2,'0')+':'+String(d.getMinutes()).padStart(2,'0');
        return d.getFullYear()+'/'+(d.getMonth()+1)+'/'+d.getDate();
      }},
  });

  var series;
  if(HAS_OHLC){
    // 真实K线：蜡烛图，绝对价格坐标（不做涨跌幅换算，避免失真）
    // K线固定“涨红跌绿”，不随涨跌颜色方案切换（该方案仅影响普通折线/面积图）
    var CU='#ef5350', CD='#26a69a';
    series=chart.addCandlestickSeries({
      upColor:CU, downColor:CD, borderUpColor:CU, borderDownColor:CD,
      wickUpColor:CU, wickDownColor:CD, priceLineVisible:false, lastValueVisible:true,
    });
    series.setData(raw.map(function(d,i){
      var o=d.open!=null?d.open:d.price, h=d.high!=null?d.high:d.price, l=d.low!=null?d.low:d.price;
      var c=d.price, bc;
      if(c>o) bc=CU; else if(c<o) bc=CD;
      else{ var pct=i>0?(c-raw[i-1].price)/raw[i-1].price:0; bc=pct>=0?CU:CD; }
      return{time:d.time,open:o,high:Math.max(h,o,c),low:Math.min(l,o,c),close:c,
             color:bc,borderColor:bc,wickColor:bc};
    }));
    if(HAS){
      series.createPriceLine({price:PREV,color:'#666688',lineWidth:1,
        lineStyle:LightweightCharts.LineStyle.Dashed,axisLabelVisible:true,title:'$prevCloseLabel'});
    }
    // 均线 MA5 / MA10 / MA20（由设置控制）
    var MA_DEFS=${if (StockliteState.getInstance().enableChartMA) "[{n:5,color:'#f5c518'},{n:10,color:'#2196f3'},{n:20,color:'#e040fb'}]" else "[]"};
    var maSeries=MA_DEFS.map(function(def){
      var s=chart.addLineSeries({color:def.color,lineWidth:1,priceLineVisible:false,lastValueVisible:false,crosshairMarkerVisible:false});
      var maData=[];
      for(var mi=def.n-1;mi<raw.length;mi++){
        var sum=0;
        for(var mj=mi-def.n+1;mj<=mi;mj++) sum+=raw[mj].price;
        maData.push({time:raw[mi].time,value:sum/def.n});
      }
      s.setData(maData);
      return{s:s,n:def.n,color:def.color,data:maData};
    });
  } else if(HAS){
    var data=raw.map(function(d){return{time:d.time,value:(d.price-PREV)/PREV*100};});
    series=chart.addBaselineSeries({
      baseValue:{type:'price',price:0},
      topLineColor:UP, topFillColor1:'${pal.upF1}', topFillColor2:'${pal.upF2}',
      bottomLineColor:DN, bottomFillColor1:'${pal.dnF1}', bottomFillColor2:'${pal.dnF2}',
      lineWidth:2, priceLineVisible:false, lastValueVisible:true,
    });
    series.createPriceLine({price:0,color:'#666688',lineWidth:1,
      lineStyle:LightweightCharts.LineStyle.Dashed,axisLabelVisible:true,title:'$prevCloseLabel'});
    series.setData(data);
  } else {
    var data=raw.map(function(d){return{time:d.time,value:d.price};});
    series=chart.addAreaSeries({lineColor:UP,topColor:'${pal.upF1}',bottomColor:'${pal.upF2}',
      lineWidth:2,priceLineVisible:false,lastValueVisible:true});
    series.setData(data);
  }

  (function(){
    var INIT_BARS=$initBars;
    if(INIT_BARS>0&&raw.length>INIT_BARS){
      chart.timeScale().setVisibleRange({
        from:raw[raw.length-INIT_BARS].time,
        to:raw[raw.length-1].time
      });
    } else {
      chart.timeScale().fitContent();
    }
  })();

  chart.subscribeCrosshairMove(function(p){
    if(!p.point||!p.time){tip.innerHTML='';return;}
    var sd=p.seriesData&&p.seriesData.get(series);
    if(!sd){tip.innerHTML='';return;}
    var dt=new Date(p.time*1000);
    var ts=ISINTRADAY
      ?(String(dt.getHours()).padStart(2,'0')+':'+String(dt.getMinutes()).padStart(2,'0'))
      :(dt.getFullYear()+'-'+String(dt.getMonth()+1).padStart(2,'0')+'-'+String(dt.getDate()).padStart(2,'0'));
    if(HAS_OHLC){
      var pct=pctMap[p.time];
      var col=sd.close>sd.open?CU:(sd.close<sd.open?CD:(pct!=null&&pct<0?CD:CU));
      var pctTxt=(pct!=null)?('&nbsp;&nbsp;'+(pct>=0?'+':'')+pct.toFixed(2)+'%'):'';
      var maTxt='';
      if(typeof maSeries!=='undefined'){
        maSeries.forEach(function(ma){
          var idx=-1;
          for(var mi=0;mi<ma.data.length;mi++){if(ma.data[mi].time===p.time){idx=mi;break;}}
          if(idx>=0) maTxt+='&nbsp;&nbsp;<span style="color:'+ma.color+'">MA'+ma.n+' '+ma.data[idx].value.toFixed(3)+'</span>';
        });
      }
      tip.innerHTML='<span style="color:#888aaa">'+ts+'</span>'
        +'&nbsp;&nbsp;<b style="color:'+col+'">'+pctTxt.trim()+'</b>'
        +'&nbsp;&nbsp;<span style="color:#cdd6f4">$lblOpen <b style="color:'+col+'">'+sd.open.toFixed(3)+'</b>'
        +'&nbsp;$lblHigh <b style="color:'+col+'">'+sd.high.toFixed(3)+'</b>'
        +'&nbsp;$lblLow <b style="color:'+col+'">'+sd.low.toFixed(3)+'</b>'
        +'&nbsp;$lblClose <b style="color:'+col+'">'+sd.close.toFixed(3)+'</b></span>'
        +maTxt;
      return;
    }
    var val=sd.value!==undefined?sd.value:(sd.lowerValue!==undefined?sd.lowerValue:null);
    if(val===null){tip.innerHTML='';return;}
    var price=priceMap[p.time];
    var pct=pctMap[p.time];
    // 日内 baseline：val 已是相对昨收的涨跌幅；其他折线图：用 pctMap 计算逐 bar 涨跌幅
    var chgPct=HAS?val:pct;
    var col=chgPct!=null?(chgPct>=0?UP:DN):'#cdd6f4';
    var sign=chgPct!=null&&chgPct>=0?'+':'';
    var priceTxt=price!=null?(HAS_OHLC||HAS?price.toFixed(3):((price>=0?'+':'')+price.toFixed(2))):null;
    tip.innerHTML='<span style="color:#888aaa">'+ts+'</span>'
      +(chgPct!=null?'&nbsp;&nbsp;<b style="color:'+col+'">'+sign+chgPct.toFixed(2)+'%</b>':'')
      +(priceTxt!=null?'&nbsp;&nbsp;<b style="color:'+col+'">'+priceTxt+'</b>':'');
  });

  // ── 可见区间最高/最低价标注 ──────────────────────────────────────────
  var hiEl=document.getElementById('hi'), loEl=document.getElementById('lo');

  function updateHiLo(){
    var lr=chart.timeScale().getVisibleLogicalRange();
    if(!lr) return;
    var from=Math.max(0,Math.floor(lr.from)), to=Math.min(raw.length-1,Math.ceil(lr.to));
    var hiVal=-Infinity, loVal=Infinity, hiTime=0, loTime=0;
    for(var i=from;i<=to;i++){
      var d=raw[i];
      var h=d.high!=null?d.high:d.price, l=d.low!=null?d.low:d.price;
      if(HAS_OHLC){ if(h>hiVal){hiVal=h;hiTime=d.time;} if(l<loVal){loVal=l;loTime=d.time;} }
      else{ if(d.price>hiVal){hiVal=d.price;hiTime=d.time;} if(d.price<loVal){loVal=d.price;loTime=d.time;} }
    }
    if(!isFinite(hiVal)||!isFinite(loVal)||hiVal===loVal){hiEl.style.display='none';loEl.style.display='none';return;}

    function place(labelEl, val, time, isHi){
      // baseline series 用百分比坐标，area/candle 用绝对价格坐标
      var coordVal=(HAS&&!HAS_OHLC)?((val-PREV)/PREV*100):val;
      var y=series.priceToCoordinate(coordVal);
      var x=chart.timeScale().timeToCoordinate(time);
      if(y==null||x==null){labelEl.style.display='none';return;}
      var col=isHi?'#ef5350':'#26a69a';
      labelEl.textContent=val.toFixed(3);
      labelEl.style.color=col;
      labelEl.style.background='rgba(20,20,36,.85)';
      labelEl.style.border='1px solid '+col;
      labelEl.style.display='block';
      var lw=labelEl.offsetWidth||44, lh=labelEl.offsetHeight||16;
      var priceAxisW=62;
      var cx=Math.min(Math.max(x-lw/2, 2), el.clientWidth-priceAxisW-lw-2);
      labelEl.style.left=cx+'px';
      labelEl.style.top=(isHi?y-lh-4:y+4)+'px';
    }
    place(hiEl, hiVal, hiTime, true);
    place(loEl, loVal, loTime, false);
  }

  chart.timeScale().subscribeVisibleLogicalRangeChange(updateHiLo);
  updateHiLo();

  new ResizeObserver(function(){
    chart.applyOptions({width:el.clientWidth,height:el.clientHeight});
    updateHiLo();
  }).observe(el);
})();
</script></body></html>"""
    }
}
