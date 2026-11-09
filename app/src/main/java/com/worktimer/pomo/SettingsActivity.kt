package com.worktimer.pomo

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val work = findViewById<EditText>(R.id.workEdit)
        val short = findViewById<EditText>(R.id.shortEdit)
        val long = findViewById<EditText>(R.id.longEdit)
        val cycles = findViewById<EditText>(R.id.cyclesEdit)
        val autoBreak = findViewById<CompoundButton>(R.id.autoBreakSwitch)
        val autoWork = findViewById<CompoundButton>(R.id.autoWorkSwitch)

        fun fill(w: Int, s: Int, l: Int) {
            work.setText(w.toString())
            short.setText(s.toString())
            long.setText(l.toString())
        }

        fill(Config.work(this), Config.shortBreak(this), Config.longBreak(this))
        cycles.setText(Config.cycles(this).toString())
        autoBreak.isChecked = Config.autoBreak(this)
        autoWork.isChecked = Config.autoWork(this)

        findViewById<MaterialButton>(R.id.preset25).setOnClickListener { fill(25, 5, 15) }
        findViewById<MaterialButton>(R.id.preset50).setOnClickListener { fill(50, 10, 20) }
        findViewById<MaterialButton>(R.id.preset90).setOnClickListener { fill(90, 15, 30) }

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
}
