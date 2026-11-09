package com.worktimer.pomo

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper

/**
 * Звуки напоминаний. Играет само приложение (а не система уведомлений),
 * поэтому звук можно выбрать любой и он не путается с другими уведомлениями.
 *
 * Формат выбора: "raw:<id>" — встроенный звук, "uri:<адрес>" — звук телефона
 * или свой файл, "none" — без звука.
 */
object Sound {
    class Builtin(val id: String, val title: String, val res: Int)

    val BUILTIN = listOf(
        Builtin("bowl", "Поющая чаша", R.raw.bowl),
        Builtin("birds", "Птички", R.raw.birds),
        Builtin("bell", "Колокольчик", R.raw.bell),
        Builtin("chime", "Двойной звон", R.raw.chime),
        Builtin("trill", "Трель", R.raw.trill),
        Builtin("gong", "Гонг", R.raw.gong),
        Builtin("wood", "Деревянные щелчки", R.raw.wood),
    )

    const val DEFAULT_WORK_END = "raw:bowl"
    const val DEFAULT_BREAK_END = "raw:chime"

    private val handler = Handler(Looper.getMainLooper())
    private var current: MediaPlayer? = null
    private var currentDone: (() -> Unit)? = null
    private val stopTask = Runnable { stop() }

    val isPlaying: Boolean get() = current != null

    fun uriFor(ctx: Context, spec: String): Uri? = when {
        spec.startsWith("raw:") -> {
            val id = spec.removePrefix("raw:")
            BUILTIN.firstOrNull { it.id == id }
                ?.let { Uri.parse("android.resource://${ctx.packageName}/${it.res}") }
        }
        spec.startsWith("uri:") -> Uri.parse(spec.removePrefix("uri:"))
        else -> null
    }

    /**
     * Проиграть звук (не дольше maxMs). onDone вызывается, когда звук закончится
     * или будет остановлен — но только если воспроизведение началось (тогда вернётся true).
     */
    fun play(
        ctx: Context,
        spec: String,
        maxMs: Long = 8_000L,
        useAlarm: Boolean = Config.alarmVolume(ctx),
        onDone: () -> Unit = {},
    ): Boolean {
        stop()
        val app = ctx.applicationContext
        val uri = uriFor(app, spec) ?: return false
        val usage = if (useAlarm) AudioAttributes.USAGE_ALARM
        else AudioAttributes.USAGE_NOTIFICATION_EVENT
        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(usage)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            mp.setDataSource(app, uri)
            mp.prepare()
        } catch (e: Exception) {
            mp.release()
            return false
        }
        current = mp
        currentDone = onDone
        mp.setOnCompletionListener { if (current === mp) stop() }
        mp.setOnErrorListener { _, _, _ ->
            if (current === mp) stop()
            true
        }
        try {
            mp.start()
        } catch (e: Exception) {
            stop()
            return true // onDone уже вызван в stop()
        }
        handler.postDelayed(stopTask, maxMs)
        return true
    }

    fun stop() {
        handler.removeCallbacks(stopTask)
        val mp = current
        current = null
        if (mp != null) {
            runCatching { mp.stop() }
            mp.release()
        }
        val done = currentDone
        currentDone = null
        done?.invoke()
    }
}
