package com.airnudge.app

import android.os.Bundle
import android.content.Intent
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.ComponentActivity

class SettingsActivity : ComponentActivity() {
    private val actions = ActionType.entries.toTypedArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val settings = SettingsStore.load(this)
        setupSensitivity(settings)
        setupCooldown(settings)
        setupCursor(settings)
        setupMapping(R.id.openPalmAction, GestureType.OPEN_PALM)
        setupMapping(R.id.closedFistAction, GestureType.CLOSED_FIST)
        setupMapping(R.id.pinchAction, GestureType.PINCH)
        setupMapping(R.id.swipeUpAction, GestureType.SWIPE_UP)
        setupMapping(R.id.swipeDownAction, GestureType.SWIPE_DOWN)
        setupMapping(R.id.swipeLeftAction, GestureType.SWIPE_LEFT)
        setupMapping(R.id.swipeRightAction, GestureType.SWIPE_RIGHT)
        findViewById<android.widget.Button>(R.id.calibrationButton).setOnClickListener {
            startActivity(Intent(this, CalibrationActivity::class.java))
        }
        findViewById<android.widget.Button>(R.id.resetCalibrationButton).setOnClickListener {
            SettingsStore.clearCalibration(this)
            android.widget.Toast.makeText(this, R.string.calibration_reset, android.widget.Toast.LENGTH_SHORT).show()
        }
        findViewById<android.widget.Button>(R.id.settingsTutorialButton).setOnClickListener {
            startActivity(Intent(this, GestureTutorialActivity::class.java))
        }
        findViewById<android.widget.Button>(R.id.privacySetupButton).setOnClickListener {
            startActivity(
                Intent(this, OnboardingActivity::class.java)
                    .putExtra(OnboardingActivity.EXTRA_REVIEW, true)
            )
        }
    }

    private fun setupSensitivity(settings: AppSettings) {
        val spinner = findViewById<Spinner>(R.id.sensitivitySpinner)
        val values = Sensitivity.entries.toTypedArray()
        spinner.adapter = enumAdapter(values)
        spinner.setSelection(values.indexOf(settings.sensitivity))
        spinner.onItemSelectedListener = itemSelected { position ->
            SettingsStore.setSensitivity(this, values[position])
        }
    }

    private fun setupCooldown(settings: AppSettings) {
        val label = findViewById<TextView>(R.id.cooldownValue)
        setupSeekBar(
            id = R.id.cooldownSeekBar,
            progress = ((settings.cooldownMs - 250) / 50).toInt(),
            label = label,
            format = { "${250 + it * 50} ms" },
            save = { SettingsStore.setCooldown(this, 250 + it * 50) }
        )
    }

    private fun setupCursor(settings: AppSettings) {
        findViewById<android.widget.Switch>(R.id.airCursorSwitch).apply {
            isChecked = settings.airCursorEnabled
            setOnCheckedChangeListener { _, enabled -> SettingsStore.setAirCursorEnabled(this@SettingsActivity, enabled) }
        }
        setupSeekBar(
            R.id.cursorSensitivitySeekBar,
            settings.cursorSensitivity - 60,
            findViewById(R.id.cursorSensitivityValue),
            { "${60 + it}%" },
            { SettingsStore.setCursorSensitivity(this, 60 + it) }
        )
        setupSeekBar(
            R.id.smoothingSeekBar,
            settings.cursorSmoothing,
            findViewById(R.id.smoothingValue),
            { "$it%" },
            { SettingsStore.setCursorSmoothing(this, it) }
        )
        setupSeekBar(
            R.id.pointerSizeSeekBar,
            settings.pointerSizeDp - 12,
            findViewById(R.id.pointerSizeValue),
            { "${12 + it} dp" },
            { SettingsStore.setPointerSize(this, 12 + it) }
        )
    }

    private fun setupMapping(spinnerId: Int, gesture: GestureType) {
        val spinner = findViewById<Spinner>(spinnerId)
        spinner.adapter = enumAdapter(actions)
        spinner.setSelection(actions.indexOf(SettingsStore.actionFor(this, gesture)))
        spinner.onItemSelectedListener = itemSelected { position ->
            SettingsStore.setAction(this, gesture, actions[position])
        }
    }

    private fun <T : Enum<T>> enumAdapter(values: Array<T>) = ArrayAdapter(
        this,
        android.R.layout.simple_spinner_item,
        values.map { it.name.replace('_', ' ').lowercase().replaceFirstChar(Char::uppercase) }
    ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

    private fun itemSelected(action: (Int) -> Unit) = object : android.widget.AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
            action(position)
        }

        override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
    }

    private fun setupSeekBar(
        id: Int,
        progress: Int,
        label: TextView,
        format: (Int) -> String,
        save: (Int) -> Unit
    ) {
        findViewById<SeekBar>(id).apply {
            this.progress = progress
            label.text = format(progress)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) {
                    label.text = format(value)
                    if (fromUser) save(value)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
    }
}
