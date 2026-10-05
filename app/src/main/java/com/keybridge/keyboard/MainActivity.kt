package com.keybridge.keyboard

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

import android.util.TypedValue
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : Activity(), KeyBridgeClient.Listener {

    // ---- 主题色 ----
    private val colBg = Color.parseColor("#101216")
    private val keyBg = Color.parseColor("#262B33")
    private val keyBgActive = Color.parseColor("#39424F")
    private val keyBgPressed = Color.parseColor("#3D4757")
    private val keyStroke = Color.parseColor("#3A4250")
    private val keyFg = Color.parseColor("#E8EAED")
    private val textDim = Color.parseColor("#8A93A1")
    private val colOk = Color.parseColor("#4CAF50")
    private val colBad = Color.parseColor("#EF5350")
    private val selColor = Color.parseColor("#2E5B9E")

    // 游戏键盘画布设计尺寸（渲染时拉伸铺满实际区域）
    private val DESIGN_W = 880
    private val DESIGN_H = 360

    private val handler = Handler(Looper.getMainLooper())
    private val activeKeys = mutableSetOf<String>()

    // 自适应缩放：记录每个按键的设计高度与基准字号
    private val scaledKeys = mutableListOf<Triple<Button, Int, Float>>()
    private var designTotalDp = 0
    private var rowCount = 0
    private var lastFillHeight = -1

    // 游戏键盘自由布局：每个键独立 x/y/w/h（设计坐标 880×360，渲染时拉伸铺满屏幕）
    private var arrangeMode = false
    private val placedKeys = mutableListOf<KeyItem>()
    private val keyButtons = LinkedHashMap<String, Button>()
    private var scaleX = 1f
    private var scaleY = 1f

    data class KeyItem(
        val code: String,
        var x: Int, var y: Int, var w: Int, var h: Int
    )

    private lateinit var statusDot: TextView
    private lateinit var statusText: TextView
    private lateinit var container: FrameLayout
    private val tabButtons = mutableListOf<Button>()
    private var currentTab = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        KeyBridgeClient.init(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(buildUi())
        KeyBridgeClient.addListener(this)
        KeyBridgeClient.start()
        selectTab(0)
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseAll()
        KeyBridgeClient.removeListener(this)
        KeyBridgeClient.stop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideBars()
    }

    private fun hideBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN
        }
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()

    // ================= UI 构建 =================

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colBg)
        }

        // 顶部状态栏
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(10.dp(), 8.dp(), 10.dp(), 4.dp())
        }
        statusDot = TextView(this).apply {
            text = "●"
            textSize = 14f
            setTextColor(colBad)
        }
        statusText = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#B9C0CB"))
            setSingleLine(true)
            setPadding(6.dp(), 0, 0, 0)
        }
        val btnRe = smallBtn("重连")
        btnRe.setOnClickListener { KeyBridgeClient.restart() }
        val btnSet = smallBtn("设置")
        btnSet.setOnClickListener { showSettings() }
        header.addView(statusDot)
        header.addView(
            statusText,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        header.addView(btnRe)
        header.addView(btnSet)
        root.addView(header)

        // 模式切换
        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(8.dp(), 2.dp(), 8.dp(), 6.dp())
        }
        listOf("全键盘", "游戏", "快捷键", "输入").forEachIndexed { i, name ->
            val b = smallBtn(name)
            b.layoutParams = LinearLayout.LayoutParams(0, 34.dp(), 1f).apply {
                setMargins(4.dp(), 0, 4.dp(), 0)
            }
            b.setOnClickListener { selectTab(i) }
            tabButtons.add(b)
            tabs.addView(b)
        }
        root.addView(tabs)

        container = FrameLayout(this)
        root.addView(
            container,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        )
        return root
    }

    private fun smallBtn(text: String): Button {
        val b = Button(this)
        b.text = text
        b.isAllCaps = false
        b.textSize = 12f
        b.setTextColor(keyFg)
        b.setSingleLine(true)
        b.setPadding(10.dp(), 0, 10.dp(), 0)
        b.minimumHeight = 0
        b.minimumWidth = 0
        b.stateListAnimator = null
        b.background = keyDrawable(8f)
        return b
    }

    private fun keyDrawable(radius: Float): GradientDrawable {
        val d = GradientDrawable()
        d.cornerRadius = radius * resources.displayMetrics.density
        d.setColor(keyBg)
        d.setStroke(1, keyStroke)
        return d
    }

    private fun selectTab(i: Int) {
        releaseAll()
        if (i != 1) arrangeMode = false
        currentTab = i
        tabButtons.forEachIndexed { idx, b ->
            (b.background as GradientDrawable).setColor(
                if (idx == i) keyBgActive else keyBg
            )
        }
        container.removeAllViews()
        container.addView(
            when (i) {
                0 -> keyboardView(KeyDefs.FULL, game = false)
                1 -> gameView()
                2 -> hotkeysView()
                else -> textInputView()
            },
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
    }

    private fun keyboardView(rows: List<KeyDefs.Row>, game: Boolean): View {
        scaledKeys.clear()
        designTotalDp = rows.sumOf { it.heightDp }
        rowCount = rows.size
        lastFillHeight = -1
        val scroll = ScrollView(this)
        scroll.isVerticalScrollBarEnabled = false
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(5.dp(), 6.dp(), 5.dp(), 10.dp())
        }
        rows.forEach { row ->
            val rl = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.keys.forEach { k -> rl.addView(makeKey(k, row.heightDp, game)) }
            col.addView(
                rl,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        scroll.addView(
            col,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        // 自适应：按容器实际高度等比缩放键高与字号，让键盘铺满整个屏幕
        scroll.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            val h = v.height
            if (h > 0 && h != lastFillHeight) {
                lastFillHeight = h
                adjustFill(h, col)
            }
        }
        return scroll
    }

    /** 依据可用高度/设计高度的比例，缩放每个按键的高度和字号 */
    private fun adjustFill(availablePx: Int, col: LinearLayout) {
        if (designTotalDp <= 0 || scaledKeys.isEmpty()) return
        val density = resources.displayMetrics.density
        val avail = availablePx - col.paddingTop - col.paddingBottom
        // 每行上下各 3dp 边距，不参与缩放，单独留出
        val designPx = (designTotalDp + rowCount * 6) * density
        val scale = (avail.toFloat() / designPx).coerceIn(0.55f, 2.6f)
        scaledKeys.forEach { (b, designDp, baseText) ->
            val lp = b.layoutParams as? LinearLayout.LayoutParams ?: return@forEach
            lp.height = (designDp * density * scale).toInt()
            b.textSize = (baseText * scale).coerceIn(9f, 30f)
        }
        col.requestLayout()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun makeKey(k: KeyDefs.Key, heightDp: Int, game: Boolean): Button {
        val b = Button(this)
        b.isAllCaps = false
        val baseText = if (game) 15f else 11.5f
        b.textSize = baseText
        b.typeface = Typeface.DEFAULT_BOLD
        b.setSingleLine(true)
        b.setPadding(0, 0, 0, 0)
        b.minimumHeight = 0
        b.minimumWidth = 0
        b.stateListAnimator = null
        if (k.code == null) {
            // 空槽位：只负责占位对齐，普通模式下不响应触摸
            b.text = ""
            val gd = GradientDrawable()
            gd.cornerRadius = 10f * resources.displayMetrics.density
            gd.setColor(Color.parseColor("#141821"))
            gd.setStroke(1, Color.parseColor("#1E232C"))
            b.background = gd
            b.isClickable = false
        } else {
            b.text = k.label
            b.setTextColor(keyFg)
            val d = keyDrawable(if (game) 10f else 8f)
            b.background = d
            b.setOnTouchListener(keyTouch(k.code, d))
        }
        if (game) b.tag = k.slotTag
        b.layoutParams = LinearLayout.LayoutParams(0, heightDp.dp(), k.weight).apply {
            setMargins(3.dp(), 3.dp(), 3.dp(), 3.dp())
        }
        scaledKeys.add(Triple(b, heightDp, baseText))
        return b
    }

    /**
     * 按键触摸逻辑：按住多久就 down 多久，松手立即 up，绝不自动锁定。
     * 普通键按住 450ms 后按 45ms 间隔自动重复（等同物理键盘，仅对可重复键生效）。
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun keyTouch(code: String, d: GradientDrawable): View.OnTouchListener {
        var down = false

        val repeat = object : Runnable {
            override fun run() {
                if (down && code !in KeyDefs.NO_REPEAT) {
                    KeyBridgeClient.keyDown(code)
                    handler.postDelayed(this, 45)
                }
            }
        }
        fun release() {
            down = false
            KeyBridgeClient.keyUp(code)
            activeKeys.remove(code)
            d.setColor(keyBg)
        }

        return View.OnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    down = true
                    activeKeys.add(code)
                    KeyBridgeClient.keyDown(code)
                    d.setColor(keyBgPressed)
                    handler.postDelayed(repeat, 450)
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(repeat)
                    if (down) release()
                    true
                }
                else -> false
            }
        }
    }

    private fun releaseAll() {
        val snapshot = activeKeys.toList()
        activeKeys.clear()
        snapshot.forEach { KeyBridgeClient.keyUp(it) }
    }

    // ================= 游戏键盘（可自定义） =================

    private fun gameView(): View {
        placedKeys.clear()
        placedKeys.addAll(loadLayout())
        keyButtons.clear()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8.dp(), 4.dp(), 8.dp(), 0)
        }
        val hint = TextView(this).apply {
            textSize = 12f
            setTextColor(if (arrangeMode) Color.parseColor("#4C8DFF") else textDim)
            text = if (arrangeMode) "编辑：拖键移动 · 拖右下角改大小 · 长按收起 · 点上方＋键新增" else ""
            setPadding(6.dp(), 0, 6.dp(), 0)
            setSingleLine(true)
        }
        bar.addView(
            hint,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        val btnKeys = smallBtn("✎ 选键")
        btnKeys.setOnClickListener { showGameKeyEditor() }
        bar.addView(btnKeys)
        val btnArrange = smallBtn(if (arrangeMode) "✓ 完成" else "⇄ 调位置")
        btnArrange.setOnClickListener {
            arrangeMode = !arrangeMode
            selectTab(1)
        }
        bar.addView(
            btnArrange,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = 6.dp() }
        )
        root.addView(bar)

        // 待放置托盘：勾选了但还没摆出来的键
        val tray = currentGameTray()
        if (arrangeMode && tray.isNotEmpty()) {
            val hs = HorizontalScrollView(this)
            hs.isHorizontalScrollBarEnabled = false
            val tl = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(8.dp(), 4.dp(), 8.dp(), 2.dp())
            }
            tray.forEach { code ->
                val tb = smallBtn("＋" + gameLabel(code))
                tb.layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, 34.dp()
                ).apply { rightMargin = 6.dp() }
                tb.setOnClickListener { addFromTray(code) }
                tl.addView(tb)
            }
            hs.addView(tl)
            root.addView(hs)
        }

        // 自由布局画布：键按设计坐标绝对定位
        val canvas = FrameLayout(this).apply { setBackgroundColor(colBg) }
        placedKeys.forEach { item ->
            val b = makeFreeKey(item)
            keyButtons[item.code] = b
            canvas.addView(b)
        }
        canvas.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            if (v.width > 0 && v.height > 0) {
                scaleX = v.width.toFloat() / DESIGN_W
                scaleY = v.height.toFloat() / DESIGN_H
                relayoutChildren()
            }
        }
        root.addView(
            canvas,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        )
        return root
    }

    private fun makeFreeKey(item: KeyItem): Button {
        val b = Button(this)
        b.isAllCaps = false
        b.text = gameLabel(item.code)
        b.setTextColor(keyFg)
        b.typeface = Typeface.DEFAULT_BOLD
        b.setSingleLine(true)
        b.setPadding(0, 0, 0, 0)
        b.minimumHeight = 0
        b.minimumWidth = 0
        b.stateListAnimator = null
        val d = keyDrawable(10f)
        b.background = d
        b.tag = item.code
        b.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        if (arrangeMode) {
            b.setOnTouchListener(editTouchListener(item, b))
        } else {
            b.setOnTouchListener(keyTouch(item.code, d))
        }
        return b
    }

    /** 按当前缩放比例把键摆到设计坐标对应的位置 */
    private fun layoutChild(b: Button, item: KeyItem) {
        val lp = b.layoutParams as FrameLayout.LayoutParams
        lp.width = (item.w * scaleX).toInt()
        lp.height = (item.h * scaleY).toInt()
        lp.leftMargin = (item.x * scaleX).toInt()
        lp.topMargin = (item.y * scaleY).toInt()
        val textPx = (Math.min(item.w, item.h) * Math.min(scaleX, scaleY) * 0.26f)
            .coerceIn(30f, 90f)
        b.setTextSize(TypedValue.COMPLEX_UNIT_PX, textPx)
        b.layoutParams = lp
    }

    private fun relayoutChildren() {
        placedKeys.forEach { item ->
            keyButtons[item.code]?.let { layoutChild(it, item) }
        }
    }

    /** 编辑模式触摸：拖动移动，拖右下角缩放，未移动长按 550ms 收起到托盘 */
    private fun editTouchListener(item: KeyItem, b: Button): View.OnTouchListener {
        val corner = 40.dp()
        var mode = 0
        var startX = 0f
        var startY = 0f
        var ox = 0
        var oy = 0
        var ow = 0
        var oh = 0
        var moved = false
        val longPress = Runnable {
            if (!moved) removeToTray(item.code)
        }
        return View.OnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = ev.rawX
                    startY = ev.rawY
                    ox = item.x; oy = item.y; ow = item.w; oh = item.h
                    moved = false
                    mode = if (ev.x > v.width - corner && ev.y > v.height - corner) 2 else 1
                    handler.postDelayed(longPress, 550)
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - startX) / scaleX
                    val dy = (ev.rawY - startY) / scaleY
                    if (Math.abs(dx) > 3 || Math.abs(dy) > 3) {
                        moved = true
                        handler.removeCallbacks(longPress)
                    }
                    if (moved) {
                        if (mode == 1) {
                            item.x = (ox + dx.toInt()).coerceIn(0, DESIGN_W - item.w)
                            item.y = (oy + dy.toInt()).coerceIn(0, DESIGN_H - item.h)
                        } else {
                            item.w = (ow + dx.toInt()).coerceIn(56, DESIGN_W - item.x)
                            item.h = (oh + dy.toInt()).coerceIn(56, DESIGN_H - item.y)
                        }
                        layoutChild(b, item)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPress)
                    if (moved) saveLayout()
                    true
                }
                else -> false
            }
        }
    }

    private fun addFromTray(code: String) {
        placedKeys.add(KeyItem(code, DESIGN_W / 2 - 65, DESIGN_H / 2 - 50, 130, 100))
        saveLayout()
        selectTab(1)
    }

    private fun removeToTray(code: String) {
        placedKeys.removeAll { it.code == code }
        saveLayout()
        selectTab(1)
        Toast.makeText(this, "已收起到托盘，点上方＋键可重新添加", Toast.LENGTH_SHORT).show()
    }

    private fun gameLabel(code: String): String =
        KeyDefs.GAME_POOL.associate { it.code to it.label }[code] ?: code

    private fun loadLayout(): MutableList<KeyItem> {
        val raw = getSharedPreferences("keybridge", MODE_PRIVATE)
            .getString("game_layout_v2", null) ?: return defaultLayout()
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<KeyItem>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val code = o.optString("c", "")
                if (code.isEmpty()) continue
                list.add(KeyItem(
                    code,
                    o.optInt("x", 16).coerceIn(0, DESIGN_W - 56),
                    o.optInt("y", 20).coerceIn(0, DESIGN_H - 56),
                    o.optInt("w", 130).coerceIn(56, DESIGN_W),
                    o.optInt("h", 100).coerceIn(56, DESIGN_H)
                ))
            }
            if (list.isEmpty()) defaultLayout() else list
        } catch (_: Exception) {
            defaultLayout()
        }
    }

    /** 默认布局：WASD + Ctrl/Shift/空格/Alt/回车 + ESC，W 在 S 正上方，右侧留白放技能键 */
    private fun defaultLayout(): MutableList<KeyItem> = mutableListOf(
        KeyItem("ESC", 16, 20, 130, 100),
        KeyItem("W", 156, 20, 130, 100),
        KeyItem("A", 18, 128, 130, 100),
        KeyItem("S", 156, 128, 130, 100),
        KeyItem("D", 294, 128, 130, 100),
        KeyItem("CTRL_L", 18, 236, 130, 100),
        KeyItem("SHIFT_L", 156, 236, 130, 100),
        KeyItem("SPACE", 294, 236, 190, 100),
        KeyItem("ALT_L", 492, 236, 120, 100),
        KeyItem("ENTER", 620, 236, 120, 100)
    )

    private fun saveLayout() {
        val arr = JSONArray()
        placedKeys.forEach { k ->
            val o = JSONObject()
            o.put("c", k.code)
            o.put("x", k.x)
            o.put("y", k.y)
            o.put("w", k.w)
            o.put("h", k.h)
            arr.put(o)
        }
        getSharedPreferences("keybridge", MODE_PRIVATE).edit()
            .putString("game_layout_v2", arr.toString())
            .apply()
    }

    private fun currentGamePalette(): Set<String> {
        val csv = getSharedPreferences("keybridge", MODE_PRIVATE)
            .getString("game_palette_v2", null)
        return if (csv != null) {
            csv.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        } else {
            defaultLayout().map { it.code }.toSet()
        }
    }

    /** 托盘 = 勾选了但还没摆出来的键 */
    private fun currentGameTray(): List<String> {
        val placedCodes = placedKeys.map { it.code }.toSet()
        return KeyDefs.GAME_POOL.map { it.code }
            .filter { it in currentGamePalette() && it !in placedCodes }
    }

    private fun showGameKeyEditor() {
        val palette = currentGamePalette()
        val scroll = ScrollView(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp(), 10.dp(), 20.dp(), 0)
        }
        val hint = TextView(this).apply {
            text = "勾选 = 该键可用（没摆出来的出现在托盘）。\n" +
                "新增的键放在右侧空白区，用「⇄ 调位置」拖到想要的位置。\n" +
                "取消勾选并保存 = 从布局中移除该键。"
            textSize = 12f
            setTextColor(textDim)
        }
        col.addView(hint)
        val boxes = mutableListOf<Pair<CheckBox, String>>()
        KeyDefs.GAME_POOL.forEach { pk ->
            val cb = CheckBox(this).apply {
                text = pk.label
                isChecked = pk.code in palette
                textSize = 14f
                setTextColor(keyFg)
            }
            boxes.add(cb to pk.code)
            col.addView(cb)
        }
        scroll.addView(col)
        AlertDialog.Builder(this)
            .setTitle("自定义游戏按键")
            .setView(scroll)
            .setPositiveButton("保存") { _, _ ->
                val picked = boxes.filter { it.first.isChecked }
                    .map { it.second }.toSet()
                // 移除未勾选的
                placedKeys.removeAll { it.code !in picked }
                // 新勾选的级联放到右侧空白区
                val missing = KeyDefs.GAME_POOL.map { it.code }.filter { code ->
                    code in picked && placedKeys.none { it.code == code }
                }
                missing.forEachIndexed { i, code ->
                    placedKeys.add(KeyItem(
                        code,
                        (470 + (i % 3) * 140).coerceIn(0, DESIGN_W - 130),
                        (16 + (i / 3) * 110).coerceIn(0, DESIGN_H - 100),
                        130, 100
                    ))
                }
                saveLayout()
                getSharedPreferences("keybridge", MODE_PRIVATE).edit()
                    .putString("game_palette_v2", picked.joinToString(","))
                    .apply()
                selectTab(1)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ================= 快捷键页 =================

    private fun hotkeysView(): View {
        val scroll = ScrollView(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8.dp(), 10.dp(), 8.dp(), 14.dp())
        }
        val hint = TextView(this).apply {
            text = "点按即发送组合键；音量/播放为多媒体键"
            textSize = 12f
            setTextColor(textDim)
            setPadding(6.dp(), 0, 6.dp(), 8.dp())
        }
        col.addView(hint)

        KeyDefs.HOTKEYS.chunked(3).forEach { chunk ->
            val rl = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            chunk.forEach { c ->
                val b = smallBtn(c.label)
                b.textSize = 13f
                b.layoutParams = LinearLayout.LayoutParams(0, 56.dp(), 1f).apply {
                    setMargins(4.dp(), 4.dp(), 4.dp(), 4.dp())
                }
                val d = b.background as GradientDrawable
                b.setOnClickListener {
                    KeyBridgeClient.combo(c.codes)
                    d.setColor(keyBgPressed)
                    handler.postDelayed({ d.setColor(keyBg) }, 150)
                }
                rl.addView(b)
            }
            repeat(3 - chunk.size) {
                val pad = View(this)
                pad.layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
                rl.addView(pad)
            }
            col.addView(
                rl,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        scroll.addView(
            col,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        return scroll
    }

    // ================= 文本输入页 =================

    private fun textInputView(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dp(), 16.dp(), 16.dp(), 14.dp())
        }
        val hintView = TextView(this).apply {
            text = "在下方输入，内容实时打到电脑（回车/退格同步转发）。\n" +
                "建议用英文输入法；中文请切到「全键盘」用电脑端输入法打。"
            textSize = 13f
            setTextColor(textDim)
        }
        col.addView(hintView)

        val et = EditText(this).apply {
            hint = "在这里打字…"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
            textSize = 15f
            setTextColor(keyFg)
            setHintTextColor(Color.parseColor("#5A6472"))
            val d = GradientDrawable()
            d.cornerRadius = 10f * resources.displayMetrics.density
            d.setColor(Color.parseColor("#1A1E24"))
            d.setStroke(1, keyStroke)
            background = d
            setPadding(12.dp(), 12.dp(), 12.dp(), 12.dp())
        }

        var prev = ""
        var suppress = false
        et.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable) {
                val newText = s.toString()
                if (suppress) {
                    suppress = false
                    prev = newText
                    return
                }
                if (newText == prev) return
                // 计算公共前缀/后缀，取中间差量
                var p = 0
                val maxP = minOf(prev.length, newText.length)
                while (p < maxP && prev[p] == newText[p]) p++
                var sfx = 0
                val maxS = minOf(prev.length - p, newText.length - p)
                while (sfx < maxS &&
                    prev[prev.length - 1 - sfx] == newText[newText.length - 1 - sfx]
                ) sfx++
                val removed = prev.length - p - sfx
                val added =
                    if (newText.length - sfx > p) newText.substring(p, newText.length - sfx)
                    else ""
                prev = newText
                repeat(removed) { KeyBridgeClient.tap("BKSP") }
                if (added.isNotEmpty()) KeyBridgeClient.text(added)
            }
        })
        et.setOnEditorActionListener { _, actionId, event ->
            if (event?.keyCode == KeyEvent.KEYCODE_ENTER ||
                actionId == EditorInfo.IME_ACTION_DONE ||
                actionId == EditorInfo.IME_ACTION_UNSPECIFIED
            ) {
                KeyBridgeClient.tap("ENTER")
                true
            } else {
                false
            }
        }
        col.addView(
            et,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 12.dp() }
        )

        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val clear = smallBtn("清空输入框")
        clear.setOnClickListener {
            suppress = true
            et.setText("")
        }
        btnRow.addView(
            clear,
            LinearLayout.LayoutParams(0, 44.dp(), 1f).apply {
                setMargins(4.dp(), 12.dp(), 4.dp(), 0)
            }
        )
        col.addView(btnRow)
        return col
    }

    // ================= 设置 =================

    private fun showSettings() {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.dp(), 16.dp(), 24.dp(), 0)
        }
        fun mkLabel(t: String) = TextView(this).apply {
            text = t
            textSize = 12f
            setTextColor(textDim)
        }
        col.addView(
            mkLabel("主机地址（USB/adb 模式填 127.0.0.1；WiFi 模式填电脑局域网 IP）")
        )
        val hostEt = EditText(this).apply {
            setSingleLine(true)
            setText(KeyBridgeClient.host)
            textSize = 14f
        }
        col.addView(hostEt)
        col.addView(mkLabel("端口（默认 ${KeyBridgeClient.DEFAULT_PORT}，与电脑端一致）"))
        val portEt = EditText(this).apply {
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(KeyBridgeClient.port.toString())
            textSize = 14f
        }
        col.addView(portEt)

        val sv = ScrollView(this)
        sv.addView(col)
        AlertDialog.Builder(this)
            .setTitle("连接设置")
            .setView(sv)
            .setPositiveButton("保存") { _, _ ->
                val h = hostEt.text.toString().trim().ifBlank { KeyBridgeClient.DEFAULT_HOST }
                val p = portEt.text.toString().toIntOrNull() ?: KeyBridgeClient.DEFAULT_PORT
                KeyBridgeClient.setTarget(h, p)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ================= 连接状态回调 =================

    override fun onState(connected: Boolean, detail: String) {
        statusDot.setTextColor(if (connected) colOk else colBad)
        statusText.text = if (connected) {
            "已连接 $detail"
        } else {
            "未连接 $detail · 电脑先运行 pc/keybridge_server.py"
        }
    }
}
