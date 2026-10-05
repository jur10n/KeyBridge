package com.keybridge.keyboard

/**
 * 按键布局与键名定义。
 * code 必须与 pc/keybridge_server.py 里的 SCAN / VK 表对应。
 */
object KeyDefs {

    /** code = null 表示空槽位（游戏键盘网格占位用）；slotTag 供排列模式定位视图 */
    data class Key(
        val label: String,
        val code: String?,
        val weight: Float = 1f,
        val slotTag: String? = null
    )
    data class Row(val heightDp: Int, val keys: List<Key>)
    data class Combo(val label: String, val codes: List<String>)

    /** 修饰键：按下不高亮自动重复，只跟踪按住状态 */
    val MODIFIERS = setOf("CTRL_L", "CTRL_R", "SHIFT_L", "SHIFT_R", "ALT_L", "ALT_R", "WIN")

    /** 不做长按自动重复的键 */
    val NO_REPEAT = MODIFIERS + setOf("ESC", "CAPS", "PRTSC", "MENU")

    // ---------------- 全功能键盘 ----------------

    val FULL: List<Row> = listOf(
        Row(44, listOf(
            Key("ESC", "ESC"), Key("F1", "F1"), Key("F2", "F2"), Key("F3", "F3"),
            Key("F4", "F4"), Key("F5", "F5"), Key("F6", "F6"), Key("F7", "F7"),
            Key("F8", "F8"), Key("F9", "F9"), Key("F10", "F10"), Key("F11", "F11"),
            Key("F12", "F12"), Key("PrtSc", "PRTSC")
        )),
        Row(50, listOf(
            Key("`", "GRAVE"), Key("1", "1"), Key("2", "2"), Key("3", "3"), Key("4", "4"),
            Key("5", "5"), Key("6", "6"), Key("7", "7"), Key("8", "8"), Key("9", "9"),
            Key("0", "0"), Key("-", "MINUS"), Key("=", "EQUALS"), Key("⌫", "BKSP", 1.6f)
        )),
        Row(50, listOf(
            Key("TAB", "TAB", 1.5f),
            Key("Q", "Q"), Key("W", "W"), Key("E", "E"), Key("R", "R"), Key("T", "T"),
            Key("Y", "Y"), Key("U", "U"), Key("I", "I"), Key("O", "O"), Key("P", "P"),
            Key("[", "LBRACKET"), Key("]", "RBRACKET"), Key("\\", "BACKSLASH")
        )),
        Row(50, listOf(
            Key("CAPS", "CAPS", 1.7f),
            Key("A", "A"), Key("S", "S"), Key("D", "D"), Key("F", "F"), Key("G", "G"),
            Key("H", "H"), Key("J", "J"), Key("K", "K"), Key("L", "L"),
            Key(";", "SEMICOLON"), Key("'", "QUOTE"), Key("⏎", "ENTER", 1.7f)
        )),
        Row(50, listOf(
            Key("Shift", "SHIFT_L", 2.0f),
            Key("Z", "Z"), Key("X", "X"), Key("C", "C"), Key("V", "V"), Key("B", "B"),
            Key("N", "N"), Key("M", "M"),
            Key(",", "COMMA"), Key(".", "PERIOD"), Key("/", "SLASH", 1.2f),
            Key("Shift", "SHIFT_R", 1.7f)
        )),
        Row(50, listOf(
            Key("Ctrl", "CTRL_L", 1.4f), Key("Win", "WIN", 1.1f), Key("Alt", "ALT_L", 1.1f),
            Key("Space", "SPACE", 4.2f),
            Key("Alt", "ALT_R", 1.1f), Key("Ctrl", "CTRL_R", 1.4f)
        )),
        Row(44, listOf(
            Key("Home", "HOME"), Key("Ins", "INS"), Key("Del", "DEL"),
            Key("←", "LEFT"), Key("↑", "UP"), Key("↓", "DOWN"), Key("→", "RIGHT"),
            Key("PgUp", "PGUP"), Key("PgDn", "PGDN")
        ))
    )

    // ---------------- 游戏键盘 ----------------
    // 自由布局：每个键独立位置与大小（设计坐标 880×360，定义在 MainActivity），
    // 拖动移动、拖右下角缩放，不依赖网格。默认布局 W 与 S 同列对齐（W 在 S 正上方）。

    data class PoolKey(val code: String, val label: String)

    val GAME_POOL: List<PoolKey> = listOf(
        PoolKey("W", "W"), PoolKey("A", "A"), PoolKey("S", "S"), PoolKey("D", "D"),
        PoolKey("SPACE", "空格"), PoolKey("CTRL_L", "Ctrl 左"), PoolKey("CTRL_R", "Ctrl 右"),
        PoolKey("SHIFT_L", "Shift 左"), PoolKey("SHIFT_R", "Shift 右"),
        PoolKey("ALT_L", "Alt 左"), PoolKey("ALT_R", "Alt 右"), PoolKey("WIN", "Win"),
        PoolKey("ESC", "ESC"), PoolKey("TAB", "TAB"), PoolKey("ENTER", "回车"),
        PoolKey("Q", "Q"), PoolKey("E", "E"), PoolKey("R", "R"), PoolKey("F", "F"),
        PoolKey("G", "G"), PoolKey("B", "B"), PoolKey("X", "X"), PoolKey("C", "C"),
        PoolKey("V", "V"), PoolKey("T", "T"), PoolKey("Z", "Z"), PoolKey("H", "H"),
        PoolKey("1", "1"), PoolKey("2", "2"), PoolKey("3", "3"), PoolKey("4", "4"),
        PoolKey("5", "5"), PoolKey("6", "6"), PoolKey("7", "7"), PoolKey("8", "8"),
        PoolKey("9", "9"), PoolKey("0", "0"),
        PoolKey("MINUS", "-"), PoolKey("EQUALS", "="),
        PoolKey("INS", "Ins"), PoolKey("DEL", "Del"),
        PoolKey("UP", "↑ 上"), PoolKey("DOWN", "↓ 下"),
        PoolKey("LEFT", "← 左"), PoolKey("RIGHT", "→ 右")
    )

    // ---------------- 快捷键 ----------------

    val HOTKEYS: List<Combo> = listOf(
        Combo("Ctrl+C 复制", listOf("CTRL_L", "C")),
        Combo("Ctrl+V 粘贴", listOf("CTRL_L", "V")),
        Combo("Ctrl+X 剪切", listOf("CTRL_L", "X")),
        Combo("Ctrl+Z 撤销", listOf("CTRL_L", "Z")),
        Combo("Ctrl+Y 重做", listOf("CTRL_L", "Y")),
        Combo("Ctrl+A 全选", listOf("CTRL_L", "A")),
        Combo("Ctrl+S 保存", listOf("CTRL_L", "S")),
        Combo("Ctrl+F 查找", listOf("CTRL_L", "F")),
        Combo("Ctrl+W 关标签", listOf("CTRL_L", "W")),
        Combo("Ctrl+T 新标签", listOf("CTRL_L", "T")),
        Combo("Alt+Tab 切窗", listOf("ALT_L", "TAB")),
        Combo("Alt+F4 关闭", listOf("ALT_L", "F4")),
        Combo("Win+D 桌面", listOf("WIN", "D")),
        Combo("Win+E 资源管理器", listOf("WIN", "E")),
        Combo("Win+R 运行", listOf("WIN", "R")),
        Combo("Win+L 锁定", listOf("WIN", "L")),
        Combo("任务管理器", listOf("CTRL_L", "SHIFT_L", "ESC")),
        Combo("PrtSc 截屏", listOf("PRTSC")),
        Combo("音量+", listOf("VOL_PLUS")),
        Combo("音量-", listOf("VOL_MINUS")),
        Combo("静音", listOf("MUTE")),
        Combo("播放/暂停", listOf("MEDIA_PLAY")),
        Combo("上一曲", listOf("MEDIA_PREV")),
        Combo("下一曲", listOf("MEDIA_NEXT"))
    )
}
