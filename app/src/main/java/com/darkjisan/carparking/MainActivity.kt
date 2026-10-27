package com.darkjisan.carparking

import android.app.Activity
import android.content.Context
import android.graphics.*
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

class MainActivity : Activity() {
    private var gameView: GameView? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
            gameView = GameView(this)
            setContentView(gameView)
        } catch (t: Throwable) {
            val tv = android.widget.TextView(this)
            tv.text = "Error: ${t.message}"
            tv.setTextColor(Color.WHITE)
            tv.setBackgroundColor(Color.BLACK)
            setContentView(tv)
        }
    }
    override fun onPause() { super.onPause(); gameView?.pauseGame() }
    override fun onResume() { super.onResume(); gameView?.resumeGame() }
    override fun onDestroy() { super.onDestroy(); gameView?.release() }
}

data class CarSkin(val name: String, val body: Int, val dark: Int)
data class ParkedCar(var x: Float, var y: Float, var angle: Float, var color: Int)

class GameView(context: Context) : View(context), View.OnTouchListener, SensorEventListener {
    companion object {
        const val MENU = 0; const val LEVELS = 1; const val PLAY = 2; const val MAX_LEVEL = 100
        const val ROLE_NONE = 0; const val ROLE_LEFT = 1; const val ROLE_RIGHT = 2
        const val ROLE_GAS = 3; const val ROLE_REV = 4; const val ROLE_BRAKE = 5
    }
    private val prefs = context.getSharedPreferences("royal_parking_pro", Context.MODE_PRIVATE)
    private var state = MENU; private var showDotsMenu = false
    private var w = 0f; private var h = 0f; private var ready = false
    private val cars = listOf(
        CarSkin("Red", Color.parseColor("#e74c3c"), Color.parseColor("#2c3e50")),
        CarSkin("Blue", Color.parseColor("#3498db"), Color.parseColor("#2c3e50")),
        CarSkin("Green", Color.parseColor("#1abc9c"), Color.parseColor("#2c3e50")),
        CarSkin("Gold", Color.parseColor("#f1c40f"), Color.parseColor("#6c5ce7")),
        CarSkin("Purple", Color.parseColor("#9b59b6"), Color.parseColor("#2c3e50")),
        CarSkin("Orange", Color.parseColor("#e67e22"), Color.parseColor("#2c3e50"))
    )
    private var selectedCar = 0; private var lockCar = true
    private var score = 0; private var totalStars = 0; private var crashCount = 0
    private var winCount = 0; private var maxLevelReached = 1
    private var steerSens = 0; private var gyroOn = false; private var soundOn = true
    private var carX = 200f; private var carY = 400f; private var carAngle = 0f; private var speed = 0f
    private val maxSpeed = 6.5f; private val accel = 0.18f; private val friction = 0.1f; private val brakeForce = 0.35f
    private val carLength = 100f; private val carWidth = 52f
    private val pointerRole = HashMap<Int, Int>(); private var gyroSteer = 0f
    private var level = 1; private var parked = false; private var crashed = false
    private var message = ""; private var stars = 0; private var levelStartMs = 0L
    private var blastTimer = 0; private var blastX = 0f; private var blastY = 0f
    private var slotX = 0f; private var slotY = 0f; private var slotW = 90f; private var slotH = 152f; private var slotAngle = 0f
    private val walls = mutableListOf<RectF>(); private val barriers = mutableListOf<RectF>()
    private val parkedCars = mutableListOf<ParkedCar>(); private var levelPage = 0
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val vibrator: Vibrator? = try {
        if (Build.VERSION.SDK_INT >= 31) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    } catch (_: Throwable) { null }

    private val btnLeft = RectF(); private val btnRight = RectF()
    private val btnGas = RectF(); private val btnRev = RectF(); private val btnBrake = RectF()
    private val touchLeft = RectF(); private val touchRight = RectF()
    private val touchGas = RectF(); private val touchRev = RectF(); private val touchBrake = RectF()
    private val btnNext = RectF(); private val btnHome = RectF(); private val btnRetry = RectF()
    private val btnStart = RectF(); private val btnDots = RectF(); private val dotsPanel = RectF()
    private val btnLevels = RectF(); private val btnGyro = RectF(); private val btnSens = RectF()
    private val btnSound = RectF(); private val btnLockCar = RectF()
    private val carCards = Array(6) { RectF() }; private val levelCells = Array(20) { RectF() }
    private val btnPagePrev = RectF(); private val btnPageNext = RectF(); private val btnBack = RectF()

    private val skyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val groundShade = Paint(Paint.ANTI_ALIAS_FLAG)
    private val asphalt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#3d4450") }
    private val asphaltDark = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#2a3038") }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#ffeaa7"); strokeWidth = 3.5f; style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(22f, 14f), 0f)
    }
    private val slotStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#00cec9"); style = Paint.Style.STROKE; strokeWidth = 9f }
    private val slotFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#00cec9"); alpha = 70 }
    private val wallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#636e72") }
    private val wallTop = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#dfe6e9") }
    private val barrierPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#e17055") }
    private val carBody = Paint(Paint.ANTI_ALIAS_FLAG); private val carDark = Paint(Paint.ANTI_ALIAS_FLAG)
    private val carGlass = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#74b9ff"); alpha = 210 }
    private val carLight = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#ffeaa7") }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; alpha = 90 }
    private val rearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#ff7675") }
    private val uiBg = Paint().apply { color = Color.parseColor("#0a0a12"); alpha = 210 }
    private val menuBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#0f0c29") }
    private val cardBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1a1a2e") }
    private val cardSel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#a29bfe"); style = Paint.Style.STROKE; strokeWidth = 5f }
    private val panelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1e1e30"); alpha = 245 }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 26f; typeface = Typeface.DEFAULT_BOLD }
    private val smallText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#dfe6e9"); textSize = 18f }
    private val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#a29bfe"); textSize = 26f; typeface = Typeface.DEFAULT_BOLD }
    private val btnFace = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#2d3436") }
    private val btnOn = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#6c5ce7") }
    private val gasFace = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#00b894") }
    private val gasOn = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#55efc4") }
    private val revFace = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#d63031") }
    private val revOn = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#ff7675") }
    private val brakeFace = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#fdcb6e") }
    private val brakeOn = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#ffeaa7") }
    private val startPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#00b894") }
    private val homePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#e17055") }
    private val lockPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#636e72") }
    private val openPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#6c5ce7") }
    private val blastPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#fdcb6e"); textSize = 64f; textAlign = Paint.Align.CENTER }

    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private val loop = object : Runnable {
        override fun run() {
            if (!running) return
            if (ready && state == PLAY) update()
            if (blastTimer > 0) blastTimer--
            invalidate()
            handler.postDelayed(this, 16L)
        }
    }

    init { setOnTouchListener(this); setBackgroundColor(Color.parseColor("#0f0c29")); loadStats() }

    private fun loadStats() {
        score = prefs.getInt("score", 0); totalStars = prefs.getInt("stars", 0)
        crashCount = prefs.getInt("crashes", 0); winCount = prefs.getInt("wins", 0)
        maxLevelReached = prefs.getInt("maxLevel", 1).coerceIn(1, MAX_LEVEL)
        steerSens = prefs.getInt("sens", 0); gyroOn = prefs.getBoolean("gyro", false)
        soundOn = prefs.getBoolean("sound", true); lockCar = prefs.getBoolean("lockCar", true)
        selectedCar = prefs.getInt("car", 0).coerceIn(0, cars.lastIndex)
    }
    private fun saveStats() {
        prefs.edit().putInt("score", score).putInt("stars", totalStars).putInt("crashes", crashCount)
            .putInt("wins", winCount).putInt("maxLevel", maxLevelReached).putInt("sens", steerSens)
            .putBoolean("gyro", gyroOn).putBoolean("sound", soundOn).putBoolean("lockCar", lockCar)
            .putInt("car", selectedCar).apply()
    }

    fun resumeGame() { if (!running) { running = true; handler.post(loop) }; updateGyroRegistration() }
    fun pauseGame() { running = false; handler.removeCallbacks(loop); sensorManager.unregisterListener(this); saveStats() }
    fun release() { sensorManager.unregisterListener(this) }

    private fun updateGyroRegistration() {
        sensorManager.unregisterListener(this)
        if (gyroOn && rotationSensor != null && state == PLAY)
            sensorManager.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_GAME)
    }
    override fun onSensorChanged(event: SensorEvent?) {
        if (!gyroOn || event == null || state != PLAY) return
        gyroSteer = (-event.values[1] * 0.25f).coerceIn(-0.9f, 0.9f)
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun soundVolume(): Int = (45 + (level.coerceIn(1, 100) * 55) / 100).coerceIn(45, 100)
    private fun beep(type: Int, ms: Int = 120) {
        if (!soundOn) return
        try {
            val tg = ToneGenerator(AudioManager.STREAM_MUSIC, soundVolume())
            tg.startTone(type, ms)
            handler.postDelayed({ try { tg.release() } catch (_: Throwable) {} }, (ms + 80).toLong())
        } catch (_: Throwable) {}
    }
    private fun sfxClick() = beep(ToneGenerator.TONE_PROP_BEEP, 55)
    private fun sfxStart() = beep(ToneGenerator.TONE_DTMF_1, 110)
    private fun sfxWin() = beep(ToneGenerator.TONE_PROP_ACK, 200)
    private fun sfxCrash() = beep(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 240)
    private fun sfxLock() = beep(ToneGenerator.TONE_CDMA_SOFT_ERROR_LITE, 150)
    private fun sfxNext() = beep(ToneGenerator.TONE_DTMF_5, 100)
    private fun sfxHome() = beep(ToneGenerator.TONE_DTMF_0, 90)

    private fun vibrate(ms: Long = 90L) {
        try {
            val v = vibrator ?: return
            if (!v.hasVibrator()) return
            val dur = (ms + level.coerceAtMost(50)).coerceAtMost(220)
            if (Build.VERSION.SDK_INT >= 26) {
                val amp = (140 + level.coerceAtMost(90)).coerceAtMost(255)
                v.vibrate(VibrationEffect.createOneShot(dur, amp))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(dur)
            }
        } catch (_: Throwable) {}
    }
    private fun vibrateCrash() {
        try {
            val v = vibrator ?: return
            if (!v.hasVibrator()) return
            val pattern = longArrayOf(0, 70, 50, 90, 50, 120)
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createWaveform(pattern, -1))
            else {
                @Suppress("DEPRECATION")
                v.vibrate(pattern, -1)
            }
        } catch (_: Throwable) {}
    }

    override fun onSizeChanged(width: Int, height: Int, oldw: Int, oldh: Int) {
        if (width <= 0 || height <= 0) return
        w = width.toFloat(); h = height.toFloat()
        skyPaint.shader = LinearGradient(0f, 0f, 0f, h * 0.22f, Color.parseColor("#1a1035"), Color.parseColor("#3d2b6b"), Shader.TileMode.CLAMP)
        groundShade.shader = LinearGradient(0f, h * 0.2f, 0f, h, Color.parseColor("#2a3038"), Color.parseColor("#1a1e24"), Shader.TileMode.CLAMP)
        layoutAll(); ready = true
    }

    private fun layoutAll() {
        val s = min(w, h) * 0.125f; val pad = 12f
        btnLeft.set(pad, h - s * 2.05f - pad, pad + s, h - s - pad)
        btnRight.set(pad + s + 10f, h - s * 2.05f - pad, pad + s * 2 + 10f, h - s - pad)
        btnGas.set(w - pad - s, h - s * 3.15f - pad, w - pad, h - s * 2.1f - pad)
        btnBrake.set(w - pad - s, h - s * 2.05f - pad, w - pad, h - s - pad)
        btnRev.set(w - pad - s, h - s - pad + 2f, w - pad, h - pad)
        val g = 16f
        touchLeft.set(btnLeft.left - g, btnLeft.top - g, btnLeft.right + g, btnLeft.bottom + g)
        touchRight.set(btnRight.left - g, btnRight.top - g, btnRight.right + g, btnRight.bottom + g)
        touchGas.set(btnGas.left - g, btnGas.top - g, btnGas.right + g, btnGas.bottom + g)
        touchBrake.set(btnBrake.left - g, btnBrake.top - g, btnBrake.right + g, btnBrake.bottom + g)
        touchRev.set(btnRev.left - g, btnRev.top - g, btnRev.right + g, btnRev.bottom + g)
        btnNext.set(w / 2 - 120f, h * 0.42f, w / 2 + 120f, h * 0.42f + 58f)
        btnHome.set(w / 2 - 120f, h * 0.42f + 68f, w / 2 + 120f, h * 0.42f + 126f)
        btnRetry.set(12f, 8f, 110f, 44f); btnDots.set(w - 64f, 8f, w - 10f, 44f)
        dotsPanel.set(w - 300f, 50f, w - 10f, 300f)
        btnStart.set(w / 2 - 140f, h * 0.80f, w / 2 + 140f, h * 0.80f + 60f)
        btnLevels.set(w / 2 - 140f, h * 0.70f, w / 2 + 140f, h * 0.70f + 52f)
        val cardW = min(w * 0.12f, 100f); val cardH = cardW * 1.12f
        val total = cardW * 6 + 8f * 5; var x = (w - total) / 2f; val y = h * 0.34f
        for (i in 0 until 6) { carCards[i].set(x, y, x + cardW, y + cardH); x += cardW + 8f }
        val cellW = (w - 80f) / 5f; val cellH = (h - 140f) / 4f; var idx = 0
        for (row in 0 until 4) for (col in 0 until 5) {
            val lx = 40f + col * cellW + 6f; val ly = 70f + row * cellH + 6f
            levelCells[idx].set(lx, ly, lx + cellW - 12f, ly + cellH - 12f); idx++
        }
        btnPagePrev.set(40f, h - 52f, 150f, h - 14f); btnPageNext.set(w - 150f, h - 52f, w - 40f, h - 14f)
        btnBack.set(w / 2 - 60f, h - 52f, w / 2 + 60f, h - 14f)
        btnGyro.set(w * 0.06f, h * 0.56f, w * 0.26f, h * 0.62f)
        btnSens.set(w * 0.28f, h * 0.56f, w * 0.50f, h * 0.62f)
        btnSound.set(w * 0.52f, h * 0.56f, w * 0.72f, h * 0.62f)
        btnLockCar.set(w * 0.74f, h * 0.56f, w * 0.94f, h * 0.62f)
    }

    private fun turnRate() = when (steerSens) { 0 -> 1.6f; 2 -> 3.4f; else -> 2.4f }
    private fun applySkin() { val c = cars[selectedCar]; carBody.color = c.body; carDark.color = c.dark }

    private fun setupLevel(lv: Int) {
        if (w < 10f) return
        level = lv.coerceIn(1, MAX_LEVEL)
        parked = false; crashed = false; speed = 0f; stars = 0
        walls.clear(); barriers.clear(); parkedCars.clear(); pointerRole.clear()
        gyroSteer = 0f; blastTimer = 0; levelStartMs = SystemClock.elapsedRealtime()
        if (!lockCar) selectedCar = (level - 1) % cars.size
        applySkin()
        val t = 1f - (level - 1) * 0.002f
        slotW = (92f * t).coerceAtLeast(72f); slotH = (154f * t).coerceAtLeast(124f)
        walls.add(RectF(0f, h * 0.1f, 16f, h)); walls.add(RectF(w - 16f, h * 0.1f, w, h))
        walls.add(RectF(0f, h * 0.1f, w, h * 0.1f + 12f))
        val pattern = (level - 1) % 12; val band = (level - 1) / 10
        carX = w * 0.14f; carY = h * 0.55f; carAngle = 0f
        when (pattern) {
            0 -> { slotX = w * (0.72f + (level % 5) * 0.02f); slotY = h * (0.4f + (level % 4) * 0.05f); slotAngle = 0f }
            1 -> { carY = h * 0.72f; slotX = w * 0.75f; slotY = h * (0.28f + band * 0.02f); slotAngle = 90f; walls.add(RectF(w * 0.36f, h * 0.18f, w * 0.42f, h * 0.88f)) }
            2 -> { carX = w * 0.18f; carY = h * 0.22f; carAngle = 90f; slotX = w * (0.65f + (level % 3) * 0.04f); slotY = h * 0.65f; slotAngle = -20f - (level % 5); walls.add(RectF(w * 0.4f, h * 0.32f, w * 0.55f, h * 0.4f)) }
            3 -> { slotX = w * 0.78f; slotY = h * (0.45f + (level % 3) * 0.06f); slotAngle = 0f; walls.add(RectF(w * 0.32f, h * 0.18f, w * 0.38f, h * 0.42f)); walls.add(RectF(w * 0.32f, h * 0.58f, w * 0.38f, h * 0.9f)) }
            4 -> { carX = w * 0.82f; carY = h * 0.55f; carAngle = 180f; slotX = w * 0.22f; slotY = h * (0.5f + (level % 2) * 0.08f); slotAngle = 180f }
            5 -> { carY = h * 0.78f; slotX = w * 0.72f; slotY = h * 0.26f; slotAngle = 90f; walls.add(RectF(w * 0.28f, h * 0.42f, w * 0.68f, h * 0.5f)) }
            6 -> { slotX = w * 0.76f; slotY = h * 0.58f; slotAngle = 15f; walls.add(RectF(w * 0.38f, h * 0.22f, w * 0.46f, h * 0.48f)); barriers.add(RectF(w * 0.55f, h * 0.5f, w * 0.62f, h * 0.58f)) }
            7 -> { carX = w * 0.16f; carY = h * 0.28f; carAngle = 40f; slotX = w * 0.7f; slotY = h * 0.68f; slotAngle = 40f + (level % 10) }
            8 -> { slotX = w * 0.78f; slotY = h * 0.32f; slotAngle = 90f; barriers.add(RectF(w * 0.32f, h * 0.36f, w * 0.4f, h * 0.5f)); walls.add(RectF(w * 0.44f, h * 0.18f, w * 0.5f, h * 0.88f)) }
            9 -> { carX = w * 0.15f; carY = h * 0.65f; carAngle = -10f; slotX = w * 0.7f; slotY = h * 0.28f; slotAngle = 75f; walls.add(RectF(w * 0.34f, h * 0.2f, w * 0.4f, h * 0.85f)) }
            10 -> { carX = w * 0.2f; carY = h * 0.5f; slotX = w * 0.65f; slotY = h * 0.75f; slotAngle = -90f }
            else -> { carX = w * 0.12f; carY = h * 0.4f; carAngle = 20f; slotX = w * (0.6f + (level % 4) * 0.04f); slotY = h * (0.55f + (level % 3) * 0.05f); slotAngle = ((level * 13) % 70).toFloat() }
        }
        val traffic = (1 + band / 2).coerceAtMost(4)
        val colors = intArrayOf(Color.parseColor("#0984e3"), Color.parseColor("#00b894"), Color.parseColor("#fdcb6e"), Color.parseColor("#e84393"))
        for (i in 0 until traffic) {
            val px = w * (0.25f + ((level + i * 3) % 5) * 0.1f)
            val py = if ((level + i) % 2 == 0) h * 0.2f else h * 0.82f
            if (abs(px - carX) < 80f && abs(py - carY) < 80f) continue
            parkedCars.add(ParkedCar(px, py, if (i % 2 == 0) 90f else 0f, colors[(level + i) % colors.size]))
        }
        message = "Lv $level | HOME = menu"
        updateGyroRegistration()
    }

    private fun steerValue(): Int {
        var s = 0; for (r in pointerRole.values) { if (r == ROLE_LEFT) s = -1; if (r == ROLE_RIGHT) s = 1 }; return s
    }
    private fun throttleValue(): Int {
        var t = 0; for (r in pointerRole.values) { if (r == ROLE_GAS) t = 1; if (r == ROLE_REV) t = -1 }; return t
    }
    private fun isBraking() = pointerRole.values.any { it == ROLE_BRAKE }

    private fun update() {
        if (parked || crashed) return
        val thr = throttleValue(); val stBtn = steerValue()
        if (isBraking()) {
            if (speed > 0) speed = (speed - brakeForce).coerceAtLeast(0f)
            else if (speed < 0) speed = (speed + brakeForce).coerceAtMost(0f)
        } else when (thr) {
            1 -> speed = (speed + accel).coerceAtMost(maxSpeed)
            -1 -> speed = (speed - accel).coerceAtLeast(-maxSpeed * 0.5f)
            else -> {
                if (speed > 0) speed = (speed - friction).coerceAtLeast(0f)
                else if (speed < 0) speed = (speed + friction).coerceAtMost(0f)
            }
        }
        var steerAmt = stBtn.toFloat(); if (gyroOn) steerAmt += gyroSteer
        steerAmt = steerAmt.coerceIn(-1.2f, 1.2f)
        if (abs(steerAmt) > 0.05f && abs(speed) > 0.12f) {
            carAngle += steerAmt * turnRate() * (if (speed >= 0) 1f else -1f)
        }
        val rad = Math.toRadians(carAngle.toDouble())
        carX += (sin(rad) * speed).toFloat(); carY -= (cos(rad) * speed).toFloat()
        carX = carX.coerceIn(24f, w - 24f); carY = carY.coerceIn(24f, h - 24f)
        val box = carRect()
        for (wall in walls) if (RectF.intersects(box, wall)) { onCrash(); return }
        for (b in barriers) if (RectF.intersects(box, b)) { onCrash(); return }
        for (pc in parkedCars) {
            if (RectF.intersects(box, RectF(pc.x - 26f, pc.y - 48f, pc.x + 26f, pc.y + 48f))) { onCrash(); return }
        }
        if (checkParked()) onWin()
    }

    private fun onCrash() {
        crashed = true; pointerRole.clear(); speed = 0f; crashCount++
        blastX = carX; blastY = carY; blastTimer = 40
        message = "Crash! RETRY or HOME"
        sfxCrash(); vibrateCrash(); saveStats()
    }
    private fun onWin() {
        parked = true; pointerRole.clear(); speed = 0f
        val elapsed = ((SystemClock.elapsedRealtime() - levelStartMs) / 1000L).toInt()
        stars = when { abs(speed) < 1.2f && elapsed <= 30 -> 3; abs(speed) < 2.5f -> 2; else -> 1 }
        totalStars += stars; winCount++
        score += 80 + stars * 40 + level * 5 + (40 - elapsed).coerceAtLeast(0)
        if (level >= maxLevelReached && level < MAX_LEVEL) maxLevelReached = level + 1
        message = "Parked! $stars star | Next or HOME"
        sfxWin(); vibrate(55L); saveStats()
    }

    private fun carRect() = RectF(carX - carWidth / 2, carY - carLength / 2, carX + carWidth / 2, carY + carLength / 2)
    private fun checkParked(): Boolean {
        val dx = carX - slotX; val dy = carY - slotY; val dist = sqrt(dx * dx + dy * dy)
        var ad = abs(norm(carAngle - slotAngle)); if (ad > 180f) ad = 360f - ad
        return dist < 54f && ad < 34f && abs(speed) < 3.8f
    }
    private fun norm(a: Float): Float { var x = a % 360f; if (x < 0) x += 360f; return x }
    private fun roleAt(x: Float, y: Float) = when {
        touchLeft.contains(x, y) -> ROLE_LEFT; touchRight.contains(x, y) -> ROLE_RIGHT
        touchGas.contains(x, y) -> ROLE_GAS; touchBrake.contains(x, y) -> ROLE_BRAKE
        touchRev.contains(x, y) -> ROLE_REV; else -> ROLE_NONE
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas); if (!ready) return
        when (state) { MENU -> drawMenu(canvas); LEVELS -> drawLevels(canvas); else -> drawPlay(canvas) }
        if (showDotsMenu) drawDotsPanel(canvas)
    }

    private fun drawMenu(canvas: Canvas) {
        canvas.drawRect(0f, 0f, w, h, menuBg)
        accent.textAlign = Paint.Align.CENTER; accent.textSize = 32f
        canvas.drawText("Royal Parking Pro", w / 2f, h * 0.14f, accent)
        smallText.textAlign = Paint.Align.CENTER
        canvas.drawText("Sound louder on higher levels | Vibrate on crash/lock", w / 2f, h * 0.20f, smallText)
        for (i in cars.indices) {
            val r = carCards[i]; canvas.drawRoundRect(r, 12f, 12f, cardBg)
            if (i == selectedCar) canvas.drawRoundRect(r, 12f, 12f, cardSel)
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cars[i].body }
            canvas.drawRoundRect(r.centerX() - 16f, r.centerY() - 32f, r.centerX() + 16f, r.centerY() + 24f, 8f, 8f, p)
            smallText.textAlign = Paint.Align.CENTER
            canvas.drawText(cars[i].name, r.centerX(), r.bottom - 8f, smallText)
        }
        drawChip(canvas, btnGyro, if (gyroOn) "Gyro ON" else "Gyro OFF", gyroOn)
        drawChip(canvas, btnSens, "Steer: ${steerLabel()}", true)
        drawChip(canvas, btnSound, if (soundOn) "Sound ON" else "Sound OFF", soundOn)
        drawChip(canvas, btnLockCar, if (lockCar) "Car: LOCK" else "Car: AUTO", lockCar)
        canvas.drawRoundRect(btnLevels, 14f, 14f, openPaint)
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("SELECT LEVEL (1-$maxLevelReached)", btnLevels.centerX(), btnLevels.centerY() + 9f, textPaint)
        canvas.drawRoundRect(btnStart, 16f, 16f, startPaint)
        canvas.drawText("START", btnStart.centerX(), btnStart.centerY() + 10f, textPaint)
        drawDotsButton(canvas)
        textPaint.textAlign = Paint.Align.LEFT; smallText.textAlign = Paint.Align.LEFT; accent.textAlign = Paint.Align.LEFT
    }
    private fun drawChip(canvas: Canvas, r: RectF, label: String, on: Boolean) {
        canvas.drawRoundRect(r, 10f, 10f, if (on) btnOn else btnFace)
        smallText.textAlign = Paint.Align.CENTER; canvas.drawText(label, r.centerX(), r.centerY() + 6f, smallText)
    }
    private fun drawLevels(canvas: Canvas) {
        canvas.drawRect(0f, 0f, w, h, menuBg)
        accent.textAlign = Paint.Align.CENTER
        canvas.drawText("Unlocked 1-$maxLevelReached / $MAX_LEVEL", w / 2f, 36f, accent)
        val startLv = levelPage * 20 + 1
        for (i in 0 until 20) {
            val lv = startLv + i; if (lv > MAX_LEVEL) break
            val r = levelCells[i]; val unlocked = lv <= maxLevelReached
            canvas.drawRoundRect(r, 10f, 10f, if (unlocked) openPaint else lockPaint)
            textPaint.textAlign = Paint.Align.CENTER; textPaint.textSize = 20f
            canvas.drawText(if (unlocked) "$lv" else "X$lv", r.centerX(), r.centerY() + 7f, textPaint)
        }
        textPaint.textSize = 26f
        canvas.drawRoundRect(btnPagePrev, 10f, 10f, btnFace); canvas.drawRoundRect(btnPageNext, 10f, 10f, btnFace)
        canvas.drawRoundRect(btnBack, 10f, 10f, startPaint)
        smallText.textAlign = Paint.Align.CENTER
        canvas.drawText("Prev", btnPagePrev.centerX(), btnPagePrev.centerY() + 6f, smallText)
        canvas.drawText("Next", btnPageNext.centerX(), btnPageNext.centerY() + 6f, smallText)
        canvas.drawText("Back", btnBack.centerX(), btnBack.centerY() + 6f, smallText)
        drawDotsButton(canvas); smallText.textAlign = Paint.Align.LEFT; accent.textAlign = Paint.Align.LEFT
    }
    private fun drawPlay(canvas: Canvas) {
        canvas.drawRect(0f, 0f, w, h, skyPaint); canvas.drawRect(0f, h * 0.1f, w, h, groundShade)
        canvas.drawRect(0f, h * 0.1f, w, h, asphalt)
        canvas.drawRoundRect(w * 0.12f, h * 0.16f, w * 0.92f, h * 0.9f, 16f, 16f, asphaltDark)
        for (i in 1..7) { val y = h * 0.18f + h * 0.7f * i / 8f; canvas.drawLine(w * 0.14f, y, w * 0.9f, y, linePaint) }
        for (wall in walls) { canvas.drawRoundRect(wall, 5f, 5f, wallPaint); canvas.drawRect(wall.left, wall.top - 8f, wall.right, wall.top, wallTop) }
        for (b in barriers) canvas.drawRoundRect(b, 4f, 4f, barrierPaint)
        for (pc in parkedCars) {
            canvas.save(); canvas.translate(pc.x, pc.y); canvas.rotate(pc.angle)
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pc.color }
            canvas.drawRoundRect(-22f, -44f, 22f, 44f, 10f, 10f, p); canvas.restore()
        }
        canvas.save(); canvas.translate(slotX, slotY); canvas.rotate(slotAngle)
        canvas.drawRoundRect(-slotW / 2, -slotH / 2, slotW / 2, slotH / 2, 12f, 12f, slotFill)
        canvas.drawRoundRect(-slotW / 2, -slotH / 2, slotW / 2, slotH / 2, 12f, 12f, slotStroke)
        smallText.textAlign = Paint.Align.CENTER; smallText.color = Color.parseColor("#00cec9")
        canvas.drawText("P", 0f, 8f, smallText); smallText.color = Color.parseColor("#dfe6e9"); smallText.textAlign = Paint.Align.LEFT; canvas.restore()
        drawCar(canvas)
        if (blastTimer > 0) { blastPaint.textSize = 50f + (40 - blastTimer); canvas.drawText("*", blastX, blastY, blastPaint) }
        canvas.drawRect(0f, 0f, w, 50f, uiBg)
        smallText.textAlign = Paint.Align.LEFT; canvas.drawText("Lv $level/$MAX_LEVEL  Score $score", 120f, 30f, smallText)
        canvas.drawRoundRect(btnRetry, 10f, 10f, homePaint)
        smallText.textAlign = Paint.Align.CENTER; canvas.drawText("HOME", btnRetry.centerX(), btnRetry.centerY() + 6f, smallText)
        drawDotsButton(canvas)
        canvas.drawRect(0f, 50f, w, 74f, uiBg); smallText.textAlign = Paint.Align.LEFT; canvas.drawText(message, 12f, 66f, smallText)
        val st = steerValue(); val th = throttleValue(); val br = isBraking()
        drawCtrl(canvas, btnLeft, "<", st < 0, btnFace, btnOn); drawCtrl(canvas, btnRight, ">", st > 0, btnFace, btnOn)
        drawCtrl(canvas, btnGas, "GAS", th > 0, gasFace, gasOn); drawCtrl(canvas, btnBrake, "BRK", br, brakeFace, brakeOn)
        drawCtrl(canvas, btnRev, "REV", th < 0, revFace, revOn)
        val spdBg = RectF(w / 2 - 70f, h - 36f, w / 2 + 70f, h - 8f)
        canvas.drawRoundRect(spdBg, 12f, 12f, uiBg); smallText.textAlign = Paint.Align.CENTER
        canvas.drawText("SPD ${"%.1f".format(abs(speed))}", spdBg.centerX(), spdBg.centerY() + 6f, smallText)
        if (parked || crashed) {
            if (parked) {
                canvas.drawRoundRect(btnNext, 16f, 16f, startPaint); textPaint.textAlign = Paint.Align.CENTER
                canvas.drawText(if (level < MAX_LEVEL) "NEXT LEVEL" else "FINISH", btnNext.centerX(), btnNext.centerY() + 10f, textPaint)
            }
            canvas.drawRoundRect(btnHome, 16f, 16f, homePaint); textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText("HOME (Menu)", btnHome.centerX(), btnHome.centerY() + 10f, textPaint)
            textPaint.textAlign = Paint.Align.LEFT
        }
    }
    private fun steerLabel() = when (steerSens) { 0 -> "Soft"; 2 -> "Big"; else -> "Normal" }
    private fun drawDotsButton(canvas: Canvas) {
        canvas.drawRoundRect(btnDots, 10f, 10f, btnFace); textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText(":", btnDots.centerX(), btnDots.centerY() + 9f, textPaint); textPaint.textAlign = Paint.Align.LEFT
    }
    private fun drawDotsPanel(canvas: Canvas) {
        canvas.drawRoundRect(dotsPanel, 14f, 14f, panelBg)
        var ty = dotsPanel.top + 32f; val cx = dotsPanel.left + 14f
        accent.textSize = 20f; canvas.drawText("Royal Parking Pro", cx, ty, accent); ty += 30f
        smallText.textSize = 17f
        canvas.drawText("Score: $score", cx, ty, smallText); ty += 24f
        canvas.drawText("Wins: $winCount  Crashes: $crashCount", cx, ty, smallText); ty += 24f
        canvas.drawText("Max Lv: $maxLevelReached", cx, ty, smallText); ty += 24f
        canvas.drawText("SFX vol scales with level", cx, ty, smallText)
    }
    private fun drawCtrl(canvas: Canvas, r: RectF, label: String, on: Boolean, off: Paint, active: Paint) {
        canvas.drawRoundRect(r, r.height() / 2.2f, r.height() / 2.2f, if (on) active else off)
        textPaint.textAlign = Paint.Align.CENTER; textPaint.textSize = 20f
        canvas.drawText(label, r.centerX(), r.centerY() + 7f, textPaint); textPaint.textSize = 26f; textPaint.textAlign = Paint.Align.LEFT
    }
    private fun drawCar(canvas: Canvas) {
        canvas.save(); canvas.translate(carX, carY); canvas.rotate(carAngle)
        val l = carLength / 2; val ww = carWidth / 2
        canvas.drawRoundRect(-ww, -l, ww, l, 12f, 12f, carBody)
        canvas.drawRoundRect(-ww * 0.72f, -l * 0.22f, ww * 0.72f, l * 0.32f, 8f, 8f, carDark)
        canvas.drawRoundRect(-ww * 0.6f, -l * 0.12f, ww * 0.6f, l * 0.22f, 6f, 6f, carGlass)
        canvas.drawCircle(-ww * 0.5f, -l + 10, 5f, carLight); canvas.drawCircle(ww * 0.5f, -l + 10, 5f, carLight)
        canvas.restore()
    }

    override fun onTouch(v: View?, event: MotionEvent): Boolean {
        if (!ready) return true
        val action = event.actionMasked
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            val i = event.actionIndex; val x = event.getX(i); val y = event.getY(i)
            if (btnDots.contains(x, y)) { showDotsMenu = !showDotsMenu; sfxClick(); invalidate(); return true }
            if (showDotsMenu && !dotsPanel.contains(x, y)) showDotsMenu = false
            if (state == PLAY && btnRetry.contains(x, y)) { goHome(); return true }
        }
        return when (state) { MENU -> touchMenu(event); LEVELS -> touchLevels(event); else -> touchPlay(event) }
    }
    private fun goHome() {
        state = MENU; pointerRole.clear(); parked = false; crashed = false; speed = 0f
        showDotsMenu = false; sensorManager.unregisterListener(this); saveStats(); sfxHome()
    }
    private fun touchMenu(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_DOWN) return true
        val x = event.x; val y = event.y
        for (i in cars.indices) if (carCards[i].contains(x, y)) { selectedCar = i; saveStats(); sfxClick(); invalidate(); return true }
        if (btnGyro.contains(x, y)) { gyroOn = !gyroOn; saveStats(); sfxClick(); return true }
        if (btnSens.contains(x, y)) { steerSens = (steerSens + 1) % 3; saveStats(); sfxClick(); return true }
        if (btnSound.contains(x, y)) { soundOn = !soundOn; saveStats(); sfxClick(); return true }
        if (btnLockCar.contains(x, y)) { lockCar = !lockCar; saveStats(); sfxClick(); return true }
        if (btnLevels.contains(x, y)) { state = LEVELS; levelPage = 0; sfxClick(); return true }
        if (btnStart.contains(x, y)) {
            applySkin(); setupLevel(maxLevelReached.coerceIn(1, MAX_LEVEL)); state = PLAY; sfxStart()
        }
        return true
    }
    private fun touchLevels(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_DOWN) return true
        val x = event.x; val y = event.y
        if (btnBack.contains(x, y)) { state = MENU; sfxClick(); return true }
        if (btnPagePrev.contains(x, y)) { levelPage = (levelPage - 1).coerceAtLeast(0); sfxClick(); return true }
        if (btnPageNext.contains(x, y)) { levelPage = (levelPage + 1).coerceAtMost(4); sfxClick(); return true }
        val startLv = levelPage * 20 + 1
        for (i in 0 until 20) {
            val lv = startLv + i; if (lv > MAX_LEVEL) break
            if (levelCells[i].contains(x, y)) {
                if (lv <= maxLevelReached) { applySkin(); setupLevel(lv); state = PLAY; sfxStart() }
                else { sfxLock(); vibrate(120L) }
                return true
            }
        }
        return true
    }
    private fun touchPlay(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = event.actionIndex; val id = event.getPointerId(i)
                val x = event.getX(i); val y = event.getY(i)
                if (crashed && !btnHome.contains(x, y) && !btnRetry.contains(x, y)) { setupLevel(level); sfxClick(); return true }
                if (parked && btnNext.contains(x, y)) {
                    if (level < MAX_LEVEL) { sfxNext(); setupLevel(level + 1) } else goHome()
                    return true
                }
                if ((parked || crashed) && btnHome.contains(x, y)) { goHome(); return true }
                val role = roleAt(x, y); if (role != ROLE_NONE) pointerRole[id] = role
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val id = event.getPointerId(i); if (pointerRole.containsKey(id)) continue
                    val role = roleAt(event.getX(i), event.getY(i)); if (role != ROLE_NONE) pointerRole[id] = role
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL ->
                pointerRole.remove(event.getPointerId(event.actionIndex))
        }
        return true
    }
}
