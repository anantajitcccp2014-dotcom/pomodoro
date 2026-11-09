package com.worktimer.pomo

import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class SettingsActivity : AppCompatActivity() {

    /** Для какого события сейчас выбирается звук: "work" или "break". */
    private var pickingFor = "work"

    private lateinit var alarmVolume: CompoundButton

    private val ringtonePicker =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            if (r.resultCode != RESULT_OK) return@registerForActivityResult
            val data = r.data ?: return@registerForActivityResult
            val uri = IntentCompat.getParcelableExtra(
                data, RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java
            )
            if (uri == null) {
                Config.setSound(this, pickingFor, "none", null)
            } else {
                val title = runCatching { RingtoneManager.getRingtone(this, uri)?.getTitle(this) }
                    .getOrNull() ?: "Звук телефона"
                Config.setSound(this, pickingFor, "uri:$uri", title)
            }
            renderSounds()
        }

    private val filePicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            Config.setSound(this, pickingFor, "uri:$uri", displayName(uri) ?: "Свой файл")
            renderSounds()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val work = findViewById<EditText>(R.id.workEdit)
        val short = findViewById<EditText>(R.id.shortEdit)
        val long = findViewById<EditText>(R.id.longEdit)
        val cycles = findViewById<EditText>(R.id.cyclesEdit)
        val autoBreak = findViewById<CompoundButton>(R.id.autoBreakSwitch)
        val autoWork = findViewById<CompoundButton>(R.id.autoWorkSwitch)
        val keepScreen = findViewById<CompoundButton>(R.id.keepScreenSwitch)
        alarmVolume = findViewById(R.id.alarmVolumeSwitch)

        fun fill(w: Int, s: Int, l: Int) {
            work.setText(w.toString())
            short.setText(s.toString())
            long.setText(l.toString())
        }

        fill(Config.work(this), Config.shortBreak(this), Config.longBreak(this))
        cycles.setText(Config.cycles(this).toString())
        autoBreak.isChecked = Config.autoBreak(this)
        autoWork.isChecked = Config.autoWork(this)
        keepScreen.isChecked = Config.keepScreen(this)
        alarmVolume.isChecked = Config.alarmVolume(this)

        findViewById<MaterialButton>(R.id.preset25).setOnClickListener { fill(25, 5, 15) }
        findViewById<MaterialButton>(R.id.preset50).setOnClickListener { fill(50, 10, 20) }
        findViewById<MaterialButton>(R.id.preset90).setOnClickListener { fill(90, 15, 30) }

        // ----- звуки -----
        findViewById<MaterialButton>(R.id.workSoundPlay).setOnClickListener { preview("work") }
        findViewById<MaterialButton>(R.id.breakSoundPlay).setOnClickListener { preview("break") }
        findViewById<MaterialButton>(R.id.workSoundChange).setOnClickListener { chooseSound("work") }
        findViewById<MaterialButton>(R.id.breakSoundChange).setOnClickListener { chooseSound("break") }
        renderSounds()

        fun num(e: EditText, def: Int, max: Int): Int =
            e.text.toString().trim().toIntOrNull()?.coerceIn(1, max) ?: def

        findViewById<MaterialButton>(R.id.saveButton).setOnClickListener {
            Config.save(
                this,
                work = num(work, 25, 240),
                short = num(short, 5, 120),
                long = num(long, 15, 180),
                cycles = num(cycles, 4, 12),
                autoBreak = autoBreak.isChecked,
                autoWork = autoWork.isChecked,
                keepScreen = keepScreen.isChecked,
                alarmVolume = alarmVolume.isChecked,
            )
            Toast.makeText(this, "Сохранено", Toast.LENGTH_SHORT).show()
            finish()
        }
        findViewById<MaterialButton>(R.id.backButton).setOnClickListener { finish() }

        findViewById<MaterialButton>(R.id.notifButton).setOnClickListener {
            runCatching {
                startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                )
            }
        }
        findViewById<MaterialButton>(R.id.appInfoButton).setOnClickListener {
            runCatching {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                )
            }
        }
    }

    override fun onStop() {
        super.onStop()
        Sound.stop()
    }

    private fun renderSounds() {
        findViewById<TextView>(R.id.workSoundName).text = Config.soundName(this, "work")
        findViewById<TextView>(R.id.breakSoundName).text = Config.soundName(this, "break")
    }

    private fun preview(key: String) {
        if (Sound.isPlaying) {
            Sound.stop()
            return
        }
        val spec = Config.sound(this, key)
        if (spec == "none") {
            Toast.makeText(this, "Без звука", Toast.LENGTH_SHORT).show()
            return
        }
        if (!Sound.play(this, spec, useAlarm = alarmVolume.isChecked)) {
            Toast.makeText(this, "Не удалось проиграть этот звук", Toast.LENGTH_SHORT).show()
        }
    }

    private fun chooseSound(key: String) {
        pickingFor = key
        val builtin = Sound.BUILTIN
        val items = builtin.map { it.title } +
            listOf("Звуки телефона…", "Свой аудиофайл…", "Без звука")
        val title = if (key == "work") "Звук: конец работы" else "Звук: конец перерыва"

        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setItems(items.toTypedArray()) { _, which ->
                when {
                    which < builtin.size -> {
                        val b = builtin[which]
                        Config.setSound(this, key, "raw:${b.id}", b.title)
                        renderSounds()
                        Sound.play(this, "raw:${b.id}", useAlarm = alarmVolume.isChecked)
                    }
                    which == builtin.size -> openRingtonePicker(key)
                    which == builtin.size + 1 -> runCatching { filePicker.launch(arrayOf("audio/*")) }
                    else -> {
                        Config.setSound(this, key, "none", null)
                        renderSounds()
                    }
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun openRingtonePicker(key: String) {
        val current = Config.sound(this, key)
        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALL)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Выбери звук")
            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, false)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
        if (current.startsWith("uri:")) {
            intent.putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(current.removePrefix("uri:")))
        }
        try {
            ringtonePicker.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "На этом телефоне нет выбора звуков — выбери свой файл", Toast.LENGTH_LONG).show()
        }
    }

    private fun displayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.substringBeforeLast('.') else null
        }
    }.getOrNull()
}
