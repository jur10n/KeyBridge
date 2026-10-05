package com.keybridge.keyboard

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 与 PC 端的连接管理。
 *
 * USB 模式：电脑端脚本先执行 `adb reverse tcp:27182 tcp:27182`，
 * 本端连接手机自己的 127.0.0.1:27182 即可经 USB 隧道到达电脑。
 * WiFi 模式：电脑端 --lan 启动，这里直接连电脑局域网 IP。
 *
 * 协议：每行一个 JSON
 *   {"t":"k","c":"A","d":1}   按键按下/抬起
 *   {"t":"x","s":"text"}      Unicode 文本注入
 *   {"t":"p"}                 心跳
 */
object KeyBridgeClient {

    const val DEFAULT_HOST = "127.0.0.1"
    const val DEFAULT_PORT = 27182

    interface Listener {
        fun onState(connected: Boolean, detail: String)
    }

    private val listeners = mutableListOf<Listener>()
    private val main = Handler(Looper.getMainLooper())
    private val queue = LinkedBlockingQueue<String>(512)

    @Volatile
    var connected: Boolean = false
        private set

    @Volatile
    var host: String = DEFAULT_HOST
        private set

    @Volatile
    var port: Int = DEFAULT_PORT
        private set

    private var prefs: SharedPreferences? = null
    private var netThread: Thread? = null

    @Volatile
    private var running = false

    @Volatile
    private var socket: Socket? = null

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext
            .getSharedPreferences("keybridge", Context.MODE_PRIVATE)
        prefs = p
        host = p.getString("host", DEFAULT_HOST) ?: DEFAULT_HOST
        port = p.getInt("port", DEFAULT_PORT)
    }

    fun addListener(l: Listener) {
        synchronized(listeners) { listeners.add(l) }
        main.post { l.onState(connected, describe()) }
    }

    fun removeListener(l: Listener) {
        synchronized(listeners) { listeners.remove(l) }
    }

    private fun describe() = "$host:$port"

    private fun setState(c: Boolean) {
        if (connected == c) return
        connected = c
        val snapshot = synchronized(listeners) { listeners.toList() }
        main.post { snapshot.forEach { it.onState(c, describe()) } }
    }

    fun setTarget(h: String, p: Int) {
        host = h.ifBlank { DEFAULT_HOST }
        port = p
        prefs?.edit()?.putString("host", host)?.putInt("port", port)?.apply()
        restart()
    }

    @Synchronized
    fun start() {
        if (running) return
        running = true
        netThread = Thread(::netLoop, "keybridge-net").apply {
            isDaemon = true
            start()
        }
    }

    @Synchronized
    fun stop() {
        running = false
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        netThread = null
        setState(false)
    }

    @Synchronized
    fun restart() {
        stop()
        start()
    }

    private fun netLoop() {
        while (running) {
            var s: Socket? = null
            try {
                s = Socket()
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(host, port), 2500)
                socket = s
                queue.clear()
                setState(true)
                val out: OutputStream = s.getOutputStream()
                while (running) {
                    // 3 秒无事件就发心跳，同时用于快速检测断线
                    val line = queue.poll(3, TimeUnit.SECONDS) ?: "{\"t\":\"p\"}"
                    out.write((line + "\n").toByteArray(Charsets.UTF_8))
                    out.flush()
                }
            } catch (_: Exception) {
            }
            try { s?.close() } catch (_: Exception) {}
            if (socket === s) socket = null
            setState(false)
            if (running) {
                try { Thread.sleep(1500) } catch (_: InterruptedException) { return }
            }
        }
    }

    fun send(line: String) {
        if (running) queue.offer(line)
    }

    fun keyDown(code: String) = send("{\"t\":\"k\",\"c\":\"$code\",\"d\":1}")

    fun keyUp(code: String) = send("{\"t\":\"k\",\"c\":\"$code\",\"d\":0}")

    fun tap(code: String) {
        keyDown(code)
        keyUp(code)
    }

    fun text(s: String) = send("{\"t\":\"x\",\"s\":\"${esc(s)}\"}")

    /** 依次按下再按相反顺序抬起一组键（组合键） */
    fun combo(codes: List<String>, delayMs: Long = 40) {
        val h = Handler(Looper.getMainLooper())
        codes.forEachIndexed { i, c -> h.postDelayed({ keyDown(c) }, i * delayMs) }
        codes.asReversed().forEachIndexed { i, c ->
            h.postDelayed({ keyUp(c) }, (codes.size + i) * delayMs)
        }
    }

    private fun esc(s: String) = buildString {
        for (c in s) when {
            c == '\\' -> append("\\\\")
            c == '"' -> append("\\\"")
            c < ' ' -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
    }
}
