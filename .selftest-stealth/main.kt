import com.stocklite.plugin.state.StockliteState
import com.stocklite.plugin.util.StealthChrome
import com.stocklite.plugin.util.StealthName
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.table.DefaultTableModel

var passed = 0
var failed = 0
fun check(name: String, expected: Any?, actual: Any?) {
    if (expected == actual) { passed++ }
    else { failed++; println("FAIL $name expected=$expected actual=$actual") }
}

fun main() {
    val st = StockliteState.getInstance()

    // ── uiText 门控 ──
    st.stealthMode = "FULL"; st.stealthUiText = false
    check("uiText 未开子开关", "股票", StealthName.uiText("股票"))
    st.stealthMode = "OFF"; st.stealthUiText = true
    check("uiText 档位关闭", "股票", StealthName.uiText("股票"))
    st.stealthMode = "FULL"; st.stealthUiText = true
    check("uiText 全拼", "GuPiao", StealthName.uiText("股票"))
    st.stealthMode = "ABBR"
    check("uiText 首字母", "GP", StealthName.uiText("股票"))
    check("uiText 空串", "", StealthName.uiText(""))
    check("uiText null", "", StealthName.uiText(null))

    // ── StealthChrome 遍历器 ──
    val btn  = JButton("刷新")
    val lbl  = JLabel("涨跌幅")
    val model = DefaultTableModel(arrayOf(arrayOf("600519")), arrayOf("名称", "代码"))
    val table = JTable(model)
    val root = JPanel().apply { add(btn); add(lbl); add(table) }

    StealthChrome.refresh(root)
    check("按钮转拼音", "ShuaXin", btn.text)
    check("标签转拼音", "ZhangFuDie", lbl.text)
    check("表头0转拼音", "MingCheng", table.columnModel.getColumn(0).headerValue)
    check("getColumnName 保持原文（点击逻辑依赖）", "名称", table.getColumnName(0))

    // 关档后还原
    st.stealthMode = "OFF"
    StealthChrome.refresh(root)
    check("关档还原按钮", "刷新", btn.text)
    check("关档还原表头", "名称", table.columnModel.getColumn(0).headerValue)

    // 子开关关闭时遍历保持原文
    st.stealthMode = "ABBR"; st.stealthUiText = false
    StealthChrome.refresh(root)
    check("子开关关闭不转", "刷新", btn.text)

    // 业务更新文本后按新原文收养（不叠加、不残留旧词）
    st.stealthUiText = true
    StealthChrome.refresh(root)
    btn.text = "管理分组"                       // 业务改写
    StealthChrome.refresh(root)
    check("新文本收养转换", "GLFZ", btn.text)

    // 幂等：对已转换文本再遍历不变
    val before = lbl.text
    StealthChrome.refresh(root)
    check("重复遍历幂等", before, lbl.text)

    // 嵌套容器递归
    val inner = JButton("批量导入")
    val nested = JPanel().apply { add(JPanel().apply { add(inner) }) }
    StealthChrome.refresh(nested)
    check("嵌套容器", "PLDR", inner.text)

    // ABBR→FULL 切档：已有 raw 直接重转
    st.stealthMode = "FULL"
    StealthChrome.refresh(root)
    check("切档重转", "GuanLiFenZu", btn.text)

    println("result: passed=$passed failed=$failed")
}
