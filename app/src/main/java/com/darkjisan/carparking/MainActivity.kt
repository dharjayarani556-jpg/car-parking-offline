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
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
            window.setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN
            )
            gameView = GameView(this)
            setContentView(gameView)
        } catch (t: Throwable) {
            val tv = android.widget.TextView(this)
            tv.text = "Error: ${t.message}"
            tv.setTextColor(Color.WHITE)
            tv.setBackgroundColor(Color.BLACK)
            tv.setPadding(40, 40, 40, 40)
            setContentView(tv)
        }
    }

    override fun onPause() {
        super.onPause()
        gameView?.pauseGame()
    }

    override fun onResume() {
        super.onResume()
        gameView?.resumeGame()
    }

    override fun onDestroy() {
        super.onDestroy()
        gameView?.release()
    }
}

data class CarSkin(val name: String, val body: Int, val dark: Int)
data class ParkedCar(var x: Float, var y: Float, var angle: Float, var color: Int)

class GameView(context: Context) : View(context), View.OnTouchListener, SensorEventListener {

    companion object {
        const val MENU = 0
        const val LEVELS = 1
        const val PLAY = 2
        const val MAX_LEVEL = 100

        const val ROLE_NONE = 0
        const val ROLE_LEFT = 1
        const val ROLE_RIGHT = 2
        const val ROLE_GAS = 3
        const val ROLE_REV = 4
    }

    private val prefs = context.getSharedPreferences("jishan_park", Context.MODE_PRIVATE)

    private var state = MENU
    private var showDotsMenu = false
    private var w = 0f
    private var h = 0f
    private var ready = false

    private val cars = listOf(
        CarSkin("Red Racer", Color.parseColor("#e74c3c"), Color.parseColor("#2c3e50")),
        CarSkin("Blue Bolt", Color.parseColor("#3498db"), Color.parseColor("#2c3e50")),
        CarSkin("Green GT", Color.parseColor("#1abc9c"), Color.parseColor("#2c3e50")),
        CarSkin("Gold King", Color.parseColor("#f1c40f"), Color.parseColor("#6c5ce7")),
        CarSkin("Purple Pro", Color.parseColor("#9b59b6"), Color.parseColor("#2c3e50")),
        CarSkin("Orange X", Color.parseColor("#e67e22"), Color.parseColor("#2c3e50"))
    )
    private var selectedCar = 0

    // Stats
    private var score = 0
    private var totalStars = 0
    private var crashCount = 0
    private var winCount = 0
    private var maxLevelReached = 1

    // Settings
    private var steerSens = 1 // 0 small, 1 normal, 2 big
    private var gyroOn = false
    private var soundOn = true

    private var carX = 200f
    private var carY = 400f
    private var carAngle = 0f
    private var speed = 0f
    private val maxSpeed = 6.8f
    private val accel = 0.2f
    private val friction = 0.09f
    private val carLength = 100f
    private val carWidth = 52f

    private val pointerRole = HashMap<Int, Int>()
    private var gyroSteer = 0f

    private var level = 1
    private var parked = false
    private var crashed = false
    private var message = ""
    private var stars = 0
    private var levelStartMs = 0L
    private var blastTimer = 0
    private var blastX = 0f
    private var blastY = 0f

    private var slotX = 0f
    private var slotY = 0f
    private var slotW = 88f
    private var slotH = 150f
    private var slotAngle = 0f
    private val walls = mutableListOf<RectF>()
    private val barriers = mutableListOf<RectF>()
    private val parkedCars = mutableListOf<ParkedCar>()

    private var levelPage = 0 // 20 levels per page

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private var tone: ToneGenerator? = try {
        ToneGenerator(AudioManager.STREAM_MUSIC, 60)
    } catch (_: Throwable) {
        null
    }

    // UI rects
    private val btnLeft = RectF()
    private val btnRight = RectF()
    private val btnGas = RectF()
    private val btnRev = RectF()
    private val touchLeft = RectF()
    private val touchRight = RectF()
    private val touchGas = RectF()
    private val touchRev = RectF()
    private val btnNext = RectF()
    private val btnStart = RectF()
    private val btnDots = RectF()
    private val dotsPanel = RectF()
    private val btnLevels = RectF()
    private val btnGyro = RectF()
    private val btnSens = RectF()
    private val btnSound = RectF()
    private val carCards = Array(6) { RectF() }
    private val levelCells = Array(20) { RectF() }
    private val btnPagePrev = RectF()
    private val btnPageNext = RectF()
    private val btnBack = RectF()

    private val skyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val asphalt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#2d3436") }
    private val asphaltDark = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1e272e") }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#fdcb6e"); strokeWidth = 3f; style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(24f, 14f), 0f)
    }
    private val slotStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00b894"); style = Paint.Style.STROKE; strokeWidth = 8f
    }
    private val slotFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00b894"); alpha = 55
    }
    private val wallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#636e72") }
    private val wallTop = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#dfe6e9") }
    private val barrierPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#e17055") }
    private val carBody = Paint(Paint.ANTI_ALIAS_FLAG)
    private val carDark = Paint(Paint.ANTI_ALIAS_FLAG)
    private val carGlass = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#74b9ff"); alpha = 210
    }
    private val carLight = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#ffeaa7") }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; alpha = 80 }
    private val rearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#ff7675") }
    private val uiBg = Paint().apply { color = Color.parseColor("#0a0a12"); alpha = 200 }
    private val menuBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#0f0c29") }
    private val cardBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1a1a2e") }
    private val cardSel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#a29bfe"); style = Paint.Style.STROKE; strokeWidth = 5f
    }
    private val panelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1e1e30"); alpha = 245 }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 28f; typeface = Typeface.DEFAULT_BOLD
    }
    private val smallText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#dfe6e9"); textSize = 20f
    }
    private val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#a29bfe"); textSize = 26f; typeface = Typeface.DEFAULT_BOLD
    }
    private val btnFace = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#2d3436") }
    private val btnOn = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#6c5ce7") }
    private val gasFace = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#00b894") }
    private val gasOn = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#55efc4") }
    private val revFace = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#d63031") }
    private val revOn = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#ff7675") }
    private val startPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#00b894") }
    private val lockPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#636e72") }
    private val openPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#6c5ce7") }
    private val blastPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#fdcb6e"); textSize = 64f; textAlign = Paint.Align.CENTER
    }

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

    init {
        setOnTouchListener(this)
        setBackgroundColor(Color.parseColor("#0f0c29"))
        loadStats()
    }

    private fun loadStats() {
        score = prefs.getInt("score", 0)
        totalStars = prefs.getInt("stars", 0)
        crashCount = prefs.getInt("crashes", 0)
        winCount = prefs.getInt("wins", 0)
        maxLevelReached = prefs.getInt("maxLevel", 1).coerceIn(1, MAX_LEVEL)
        steerSens = prefs.getInt("sens", 1)
        gyroOn = prefs.getBoolean("gyro", false)
        soundOn = prefs.getBoolean("sound", true)
        selectedCar = prefs.getInt("car", 0).coerceIn(0, cars.lastIndex)
    }

    private fun saveStats() {
        prefs.edit()
            .putInt("score", score)
            .putInt("stars", totalStars)
            .putInt("crashes", crashCount)
            .putInt("wins", winCount)
            .putInt("maxLevel", maxLevelReached)
            .putInt("sens", steerSens)
            .putBoolean("gyro", gyroOn)
            .putBoolean("sound", soundOn)
            .putInt("car", selectedCar)
            .apply()
    }

    fun resumeGame() {
        if (!running) {
            running = true
            handler.post(loop)
        }
        updateGyroRegistration()
    }

    fun pauseGame() {
        running = false
        handler.removeCallbacks(loop)
        sensorManager.unregisterListener(this)
        saveStats()
    }

    fun release() {
        tone?.release()
        tone = null
        sensorManager.unregisterListener(this)
    }

    private fun updateGyroRegistration() {
        sensorManager.unregisterListener(this)
        if (gyroOn && rotationSensor != null && state == PLAY) {
            sensorManager.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (!gyroOn || event == null || state != PLAY) return
        // Landscape: use Y axis tilt for steer approx
        gyroSteer = (-event.values[1] * 0.35f).coerceIn(-1.2f, 1.2f)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun beep(type: Int) {
        if (!soundOn) return
        try {
            tone?.startTone(type, 120)
        } catch (_: Throwable) {
        }
    }

    override fun onSizeChanged(width: Int, height: Int, oldw: Int, oldh: Int) {
        if (width <= 0 || height <= 0) return
        w = width.toFloat()
        h = height.toFloat()
        skyPaint.shader = LinearGradient(
            0f, 0f, 0f, h * 0.25f,
            Color.parseColor("#0f0c29"), Color.parseColor("#302b63"), Shader.TileMode.CLAMP
        )
        layoutAll()
        ready = true
    }

    private fun layoutAll() {
        val s = min(w, h) * 0.14f
        val pad = 14f
        btnLeft.set(pad, h - s * 2.15f - pad, pad + s, h - s - pad)
        btnRight.set(pad + s + 12f, h - s * 2.15f - pad, pad + s * 2 + 12f, h - s - pad)
        btnGas.set(w - pad - s, h - s * 2.15f - pad, w - pad, h - s - pad)
        btnRev.set(w - pad - s, h - s - pad + 4f, w - pad, h - pad)
        val g = 20f
        touchLeft.set(btnLeft.left - g, btnLeft.top - g, btnLeft.right + g, btnLeft.bottom + g)
        touchRight.set(btnRight.left - g, btnRight.top - g, btnRight.right + g, btnRight.bottom + g)
        touchGas.set(btnGas.left - g, btnGas.top - g, btnGas.right + g, btnGas.bottom + g)
        touchRev.set(btnRev.left - g, btnRev.top - g, btnRev.right + g, btnRev.bottom + g)
        btnNext.set(w / 2 - 130f, h * 0.46f, w / 2 + 130f, h * 0.46f + 70f)
        btnDots.set(w - 70f, 10f, w - 12f, 52f)
        dotsPanel.set(w - 320f, 56f, w - 12f, 320f)
        btnStart.set(w / 2 - 140f, h * 0.78f, w / 2 + 140f, h * 0.78f + 68f)
        btnLevels.set(w / 2 - 140f, h * 0.68f, w / 2 + 140f, h * 0.68f + 56f)

        val cardW = min(w * 0.13f, 110f)
        val cardH = cardW * 1.15f
        val total = cardW * 6 + 10f * 5
        var x = (w - total) / 2f
        val y = h * 0.36f
        for (i in 0 until 6) {
            carCards[i].set(x, y, x + cardW, y + cardH)
            x += cardW + 10f
        }

        // level grid 5x4
        val cellW = (w - 80f) / 5f
        val cellH = (h - 140f) / 4f
        var idx = 0
        for (row in 0 until 4) {
            for (col in 0 until 5) {
                val lx = 40f + col * cellW + 6f
                val ly = 70f + row * cellH + 6f
                levelCells[idx].set(lx, ly, lx + cellW - 12f, ly + cellH - 12f)
                idx++
            }
        }
        btnPagePrev.set(40f, h - 56f, 160f, h - 16f)
        btnPageNext.set(w - 160f, h - 56f, w - 40f, h - 16f)
        btnBack.set(w / 2 - 70f, h - 56f, w / 2 + 70f, h - 16f)
        btnGyro.set(w * 0.08f, h * 0.58f, w * 0.32f, h * 0.64f)
        btnSens.set(w * 0.34f, h * 0.58f, w * 0.66f, h * 0.64f)
        btnSound.set(w * 0.68f, h * 0.58f, w * 0.92f, h * 0.64f)
    }

    private fun turnRate(): Float = when (steerSens) {
        0 -> 2.0f
        2 -> 4.2f
        else -> 3.0f
    }

    private fun applySkin() {
        val c = cars[selectedCar]
        carBody.color = c.body
        carDark.color = c.dark
    }

    /** Build one of 100 levels — medium hard, progressive */
    private fun setupLevel(lv: Int) {
        if (w < 10f) return
        level = lv.coerceIn(1, MAX_LEVEL)
        parked = false
        crashed = false
        speed = 0f
        stars = 0
        walls.clear()
        barriers.clear()
        parkedCars.clear()
        pointerRole.clear()
        gyroSteer = 0f
        blastTimer = 0
        levelStartMs = SystemClock.elapsedRealtime()

        val t = 1f - (level - 1) * 0.0025f
        slotW = (90f * t).coerceAtLeast(70f)
        slotH = (152f * t).coerceAtLeast(120f)

        // Borders always
        walls.add(RectF(0f, h * 0.08f, 18f, h))
        walls.add(RectF(w - 18f, h * 0.08f, w, h))
        walls.add(RectF(0f, h * 0.08f, w, h * 0.08f + 14f))

        val pattern = (level - 1) % 10
        val difficulty = (level - 1) / 10 // 0..9 bands

        carX = w * 0.14f
        carY = h * 0.55f
        carAngle = 0f

        when (pattern) {
            0 -> { // straight
                slotX = w * 0.82f; slotY = h * 0.55f; slotAngle = 0f
            }
            1 -> { // side
                carY = h * 0.75f
                slotX = w * 0.78f; slotY = h * 0.3f; slotAngle = 90f
                walls.add(RectF(w * 0.38f, h * 0.15f, w * 0.44f, h * 0.9f))
            }
            2 -> { // angle
                carX = w * 0.2f; carY = h * 0.22f; carAngle = 90f
                slotX = w * 0.72f; slotY = h * 0.68f; slotAngle = -30f + difficulty
                walls.add(RectF(w * 0.42f, h * 0.3f, w * 0.55f, h * 0.38f))
            }
            3 -> { // corridor
                slotX = w * 0.8f; slotY = h * 0.5f; slotAngle = 0f
                walls.add(RectF(w * 0.3f, h * 0.15f, w * 0.36f, h * 0.42f))
                walls.add(RectF(w * 0.3f, h * 0.58f, w * 0.36f, h * 0.92f))
            }
            4 -> { // reverse start
                carX = w * 0.85f; carY = h * 0.55f; carAngle = 180f
                slotX = w * 0.2f; slotY = h * 0.55f; slotAngle = 180f
            }
            5 -> { // L shape
                carY = h * 0.8f
                slotX = w * 0.75f; slotY = h * 0.25f; slotAngle = 90f
                walls.add(RectF(w * 0.25f, h * 0.4f, w * 0.7f, h * 0.48f))
                walls.add(RectF(w * 0.45f, h * 0.15f, w * 0.52f, h * 0.48f))
            }
            6 -> { // tight gap
                slotX = w * 0.78f; slotY = h * 0.55f; slotAngle = 0f
                walls.add(RectF(w * 0.4f, h * 0.2f, w * 0.48f, h * 0.48f))
                walls.add(RectF(w * 0.4f, h * 0.62f, w * 0.48f, h * 0.88f))
                barriers.add(RectF(w * 0.55f, h * 0.48f, w * 0.62f, h * 0.58f))
            }
            7 -> { // diagonal slot
                carX = w * 0.15f; carY = h * 0.25f; carAngle = 45f
                slotX = w * 0.7f; slotY = h * 0.7f; slotAngle = 45f
                walls.add(RectF(w * 0.35f, h * 0.4f, w * 0.55f, h * 0.48f))
            }
            8 -> { // multi barrier
                slotX = w * 0.8f; slotY = h * 0.35f; slotAngle = 90f
                barriers.add(RectF(w * 0.3f, h * 0.35f, w * 0.38f, h * 0.5f))
                barriers.add(RectF(w * 0.5f, h * 0.55f, w * 0.58f, h * 0.7f))
                walls.add(RectF(w * 0.42f, h * 0.15f, w * 0.48f, h * 0.9f))
            }
            else -> { // challenge mix
                carX = w * 0.15f; carY = h * 0.7f; carAngle = -15f
                slotX = w * 0.75f; slotY = h * 0.3f; slotAngle = 70f
                walls.add(RectF(w * 0.35f, h * 0.2f, w * 0.42f, h * 0.85f))
                barriers.add(RectF(w * 0.55f, h * 0.5f, w * 0.65f, h * 0.6f))
            }
        }

        // Side parked cars (traffic) — more on higher levels
        val traffic = 1 + difficulty / 2
        val colors = intArrayOf(
            Color.parseColor("#0984e3"), Color.parseColor("#00b894"),
            Color.parseColor("#fdcb6e"), Color.parseColor("#e84393")
        )
        for (i in 0 until traffic.coerceAtMost(4)) {
            val px = w * (0.28f + i * 0.12f)
            val py = if (i % 2 == 0) h * 0.22f else h * 0.82f
            // avoid overlapping start/slot roughly
            parkedCars.add(ParkedCar(px, py, if (i % 2 == 0) 90f else 0f, colors[i % colors.size]))
        }

        applySkin()
        message = "Level $level / $MAX_LEVEL  |  Dheere park = ★★★"
        updateGyroRegistration()
    }

    private fun steerValue(): Int {
        var s = 0
        for (r in pointerRole.values) {
            if (r == ROLE_LEFT) s = -1
            if (r == ROLE_RIGHT) s = 1
        }
        return s
    }

    private fun throttleValue(): Int {
        var t = 0
        for (r in pointerRole.values) {
            if (r == ROLE_GAS) t = 1
            if (r == ROLE_REV) t = -1
        }
        return t
    }

    private fun update() {
        if (parked || crashed) return
        val thr = throttleValue()
        val stBtn = steerValue()

        when (thr) {
            1 -> speed = (speed + accel).coerceAtMost(maxSpeed)
            -1 -> speed = (speed - accel).coerceAtLeast(-maxSpeed * 0.55f)
            else -> {
                if (speed > 0) speed = (speed - friction).coerceAtLeast(0f)
                else if (speed < 0) speed = (speed + friction).coerceAtMost(0f)
            }
        }

        var steerAmt = stBtn.toFloat()
        if (gyroOn) steerAmt += gyroSteer
        steerAmt = steerAmt.coerceIn(-1.5f, 1.5f)

        if (abs(steerAmt) > 0.05f && (abs(speed) > 0.05f || thr != 0)) {
            val dir = when {
                speed > 0.05f -> 1f
                speed < -0.05f -> -1f
                thr < 0 -> -1f
                else -> 1f
            }
            carAngle += steerAmt * turnRate() * dir
        }

        val rad = Math.toRadians(carAngle.toDouble())
        carX += (sin(rad) * speed).toFloat()
        carY -= (cos(rad) * speed).toFloat()
        carX = carX.coerceIn(26f, w - 26f)
        carY = carY.coerceIn(26f, h - 26f)

        val box = carRect()
        for (wall in walls) if (RectF.intersects(box, wall)) { onCrash(); return }
        for (b in barriers) if (RectF.intersects(box, b)) { onCrash(); return }
        for (pc in parkedCars) {
            val pr = RectF(pc.x - 26f, pc.y - 48f, pc.x + 26f, pc.y + 48f)
            if (RectF.intersects(box, pr)) { onCrash(); return }
        }

        if (checkParked()) onWin()
    }

    private fun onCrash() {
        crashed = true
        pointerRole.clear()
        speed = 0f
        crashCount++
        blastX = carX
        blastY = carY
        blastTimer = 45
        message = "💥 Crash! Touch = retry"
        beep(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD)
        saveStats()
    }

    private fun onWin() {
        parked = true
        pointerRole.clear()
        speed = 0f
        val elapsed = ((SystemClock.elapsedRealtime() - levelStartMs) / 1000L).toInt()
        stars = when {
            abs(speed) < 1.2f && elapsed <= 30 -> 3
            abs(speed) < 2.5f -> 2
            else -> 1
        }
        totalStars += stars
        winCount++
        score += 80 + stars * 40 + level * 5 + (40 - elapsed).coerceAtLeast(0)
        if (level >= maxLevelReached && level < MAX_LEVEL) {
            maxLevelReached = level + 1
        } else if (level == MAX_LEVEL) {
            maxLevelReached = MAX_LEVEL
        }
        message = "✅ Parked! ${stars}★  Score $score"
        beep(ToneGenerator.TONE_PROP_ACK)
        saveStats()
    }

    private fun carRect() = RectF(
        carX - carWidth / 2, carY - carLength / 2,
        carX + carWidth / 2, carY + carLength / 2
    )

    private fun checkParked(): Boolean {
        val dx = carX - slotX
        val dy = carY - slotY
        val dist = sqrt(dx * dx + dy * dy)
        var ad = abs(norm(carAngle - slotAngle))
        if (ad > 180f) ad = 360f - ad
        return dist < 52f && ad < 32f && abs(speed) < 3.6f
    }

    private fun norm(a: Float): Float {
        var x = a % 360f
        if (x < 0) x += 360f
        return x
    }

    private fun roleAt(x: Float, y: Float) = when {
        touchLeft.contains(x, y) -> ROLE_LEFT
        touchRight.contains(x, y) -> ROLE_RIGHT
        touchGas.contains(x, y) -> ROLE_GAS
        touchRev.contains(x, y) -> ROLE_REV
        else -> ROLE_NONE
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!ready) return
        when (state) {
            MENU -> drawMenu(canvas)
            LEVELS -> drawLevels(canvas)
            else -> drawPlay(canvas)
        }
        if (showDotsMenu) drawDotsPanel(canvas)
    }

    private fun drawMenu(canvas: Canvas) {
        canvas.drawRect(0f, 0f, w, h, menuBg)
        // logo
        canvas.drawCircle(w / 2f, h * 0.12f, 36f, accent)
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("J", w / 2f, h * 0.12f + 12f, textPaint)
        accent.textAlign = Paint.Align.CENTER
        accent.textSize = 36f
        canvas.drawText("Jishan Gaming", w / 2f, h * 0.22f, accent)
        smallText.textAlign = Paint.Align.CENTER
        canvas.drawText("Car select → Levels / START   |   100 Levels", w / 2f, h * 0.28f, smallText)

        for (i in cars.indices) {
            val r = carCards[i]
            canvas.drawRoundRect(r, 14f, 14f, cardBg)
            if (i == selectedCar) canvas.drawRoundRect(r, 14f, 14f, cardSel)
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cars[i].body }
            canvas.drawRoundRect(r.centerX() - 18f, r.centerY() - 36f, r.centerX() + 18f, r.centerY() + 28f, 10f, 10f, p)
            smallText.textAlign = Paint.Align.CENTER
            canvas.drawText(cars[i].name, r.centerX(), r.bottom - 10f, smallText)
        }

        // settings row
        drawSettingChip(canvas, btnGyro, if (gyroOn) "Gyro ON" else "Gyro OFF", gyroOn)
        val sensLabel = when (steerSens) {
            0 -> "Steer: Small"; 2 -> "Steer: Big"; else -> "Steer: Normal"
        }
        drawSettingChip(canvas, btnSens, sensLabel, true)
        drawSettingChip(canvas, btnSound, if (soundOn) "Sound ON" else "Sound OFF", soundOn)

        canvas.drawRoundRect(btnLevels, 16f, 16f, openPaint)
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("SELECT LEVEL (1-$maxLevelReached)", btnLevels.centerX(), btnLevels.centerY() + 10f, textPaint)

        canvas.drawRoundRect(btnStart, 18f, 18f, startPaint)
        canvas.drawText("START Lv $maxLevelReached", btnStart.centerX(), btnStart.centerY() + 12f, textPaint)

        // tiny dots always available
        drawDotsButton(canvas)
        textPaint.textAlign = Paint.Align.LEFT
        smallText.textAlign = Paint.Align.LEFT
        accent.textAlign = Paint.Align.LEFT
    }

    private fun drawSettingChip(canvas: Canvas, r: RectF, label: String, on: Boolean) {
        canvas.drawRoundRect(r, 12f, 12f, if (on) btnOn else btnFace)
        smallText.textAlign = Paint.Align.CENTER
        canvas.drawText(label, r.centerX(), r.centerY() + 7f, smallText)
    }

    private fun drawLevels(canvas: Canvas) {
        canvas.drawRect(0f, 0f, w, h, menuBg)
        accent.textAlign = Paint.Align.CENTER
        canvas.drawText("Levels  |  Unlocked: 1–$maxLevelReached / $MAX_LEVEL", w / 2f, 40f, accent)

        val startLv = levelPage * 20 + 1
        for (i in 0 until 20) {
            val lv = startLv + i
            if (lv > MAX_LEVEL) break
            val r = levelCells[i]
            val unlocked = lv <= maxLevelReached
            canvas.drawRoundRect(r, 12f, 12f, if (unlocked) openPaint else lockPaint)
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.textSize = 22f
            canvas.drawText(if (unlocked) "$lv" else "🔒$lv", r.centerX(), r.centerY() + 8f, textPaint)
        }
        textPaint.textSize = 28f

        canvas.drawRoundRect(btnPagePrev, 12f, 12f, btnFace)
        canvas.drawRoundRect(btnPageNext, 12f, 12f, btnFace)
        canvas.drawRoundRect(btnBack, 12f, 12f, startPaint)
        smallText.textAlign = Paint.Align.CENTER
        canvas.drawText("◀ Prev", btnPagePrev.centerX(), btnPagePrev.centerY() + 7f, smallText)
        canvas.drawText("Next ▶", btnPageNext.centerX(), btnPageNext.centerY() + 7f, smallText)
        canvas.drawText("Back", btnBack.centerX(), btnBack.centerY() + 7f, smallText)
        drawDotsButton(canvas)
        smallText.textAlign = Paint.Align.LEFT
        accent.textAlign = Paint.Align.LEFT
    }

    private fun drawPlay(canvas: Canvas) {
        canvas.drawRect(0f, 0f, w, h, skyPaint)
        canvas.drawRect(0f, h * 0.08f, w, h, asphalt)
        canvas.drawRect(w * 0.16f, h * 0.16f, w * 0.9f, h * 0.9f, asphaltDark)
        for (i in 1..8) {
            val y = h * 0.1f + h * 0.85f * i / 9f
            canvas.drawLine(20f, y, w - 20f, y, linePaint)
        }

        for (wall in walls) {
            canvas.drawRoundRect(wall, 5f, 5f, wallPaint)
            canvas.drawRect(wall.left, wall.top - 8f, wall.right, wall.top, wallTop)
        }
        for (b in barriers) canvas.drawRoundRect(b, 4f, 4f, barrierPaint)

        // parked traffic cars
        for (pc in parkedCars) {
            canvas.save()
            canvas.translate(pc.x, pc.y)
            canvas.rotate(pc.angle)
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pc.color }
            canvas.drawRoundRect(-24f, -46f, 24f, 46f, 10f, 10f, shadowPaint)
            canvas.drawRoundRect(-22f, -44f, 22f, 44f, 10f, 10f, p)
            canvas.restore()
        }

        canvas.save()
        canvas.translate(slotX, slotY)
        canvas.rotate(slotAngle)
        canvas.drawRoundRect(-slotW / 2, -slotH / 2, slotW / 2, slotH / 2, 10f, 10f, slotFill)
        canvas.drawRoundRect(-slotW / 2, -slotH / 2, slotW / 2, slotH / 2, 10f, 10f, slotStroke)
        smallText.textAlign = Paint.Align.CENTER
        smallText.color = Color.parseColor("#00b894")
        canvas.drawText("P", 0f, 8f, smallText)
        smallText.color = Color.parseColor("#dfe6e9")
        smallText.textAlign = Paint.Align.LEFT
        canvas.restore()

        drawCar(canvas)

        if (blastTimer > 0) {
            blastPaint.textSize = 56f + (45 - blastTimer)
            canvas.drawText("💥", blastX, blastY, blastPaint)
        }

        // slim top: only message strip + 3 dots (no permanent name bar clutter)
        canvas.drawRect(0f, 0f, w, 44f, uiBg)
        smallText.textAlign = Paint.Align.LEFT
        canvas.drawText("Lv $level/$MAX_LEVEL  SPD ${"%.1f".format(abs(speed))}  Steer:${steerLabel()}", 12f, 28f, smallText)
        drawDotsButton(canvas)

        canvas.drawRect(0f, 44f, w, 72f, uiBg)
        canvas.drawText(message, 12f, 62f, smallText)

        val st = steerValue()
        val th = throttleValue()
        drawCtrl(canvas, btnLeft, "◀", st < 0, btnFace, btnOn)
        drawCtrl(canvas, btnRight, "▶", st > 0, btnFace, btnOn)
        drawCtrl(canvas, btnGas, "GAS", th > 0, gasFace, gasOn)
        drawCtrl(canvas, btnRev, "REV", th < 0, revFace, revOn)

        // labels near controls
        smallText.textAlign = Paint.Align.CENTER
        canvas.drawText("STEER", (btnLeft.centerX() + btnRight.centerX()) / 2f, btnLeft.top - 8f, smallText)
        canvas.drawText("SPEED", btnGas.centerX(), btnGas.top - 8f, smallText)
        smallText.textAlign = Paint.Align.LEFT

        if (parked) {
            canvas.drawRoundRect(btnNext, 18f, 18f, startPaint)
            textPaint.textAlign = Paint.Align.CENTER
            val next = if (level < MAX_LEVEL) "NEXT LEVEL ▶" else "🎉 FINISH"
            canvas.drawText(next, btnNext.centerX(), btnNext.centerY() + 12f, textPaint)
            textPaint.textAlign = Paint.Align.LEFT
        }
    }

    private fun steerLabel() = when (steerSens) {
        0 -> "Small"; 2 -> "Big"; else -> "Normal"
    }

    private fun drawDotsButton(canvas: Canvas) {
        canvas.drawRoundRect(btnDots, 12f, 12f, btnFace)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = 28f
        canvas.drawText("⋮", btnDots.centerX(), btnDots.centerY() + 10f, textPaint)
        textPaint.textSize = 28f
        textPaint.textAlign = Paint.Align.LEFT
    }

    private fun drawDotsPanel(canvas: Canvas) {
        canvas.drawRoundRect(dotsPanel, 16f, 16f, panelBg)
        var ty = dotsPanel.top + 36f
        val cx = dotsPanel.left + 16f
        accent.textSize = 22f
        canvas.drawText("Jishan Gaming", cx, ty, accent); ty += 34f
        smallText.textSize = 18f
        canvas.drawText("Score: $score", cx, ty, smallText); ty += 28f
        canvas.drawText("Wins: $winCount", cx, ty, smallText); ty += 28f
        canvas.drawText("Crashes: $crashCount 💥", cx, ty, smallText); ty += 28f
        canvas.drawText("Stars: $totalStars ★", cx, ty, smallText); ty += 28f
        canvas.drawText("Max Level: $maxLevelReached / $MAX_LEVEL", cx, ty, smallText); ty += 28f
        canvas.drawText("Steer: ${steerLabel()}", cx, ty, smallText); ty += 28f
        canvas.drawText("Gyro: ${if (gyroOn) "ON" else "OFF"}", cx, ty, smallText); ty += 28f
        canvas.drawText("Tap ⋮ again to close", cx, ty, smallText)
    }

    private fun drawCtrl(canvas: Canvas, r: RectF, label: String, on: Boolean, off: Paint, active: Paint) {
        canvas.drawRoundRect(r, r.height() / 2f, r.height() / 2f, if (on) active else off)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = 24f
        canvas.drawText(label, r.centerX(), r.centerY() + 8f, textPaint)
        textPaint.textSize = 28f
        textPaint.textAlign = Paint.Align.LEFT
    }

    private fun drawCar(canvas: Canvas) {
        canvas.save()
        canvas.translate(carX, carY)
        canvas.rotate(carAngle)
        val l = carLength / 2
        val ww = carWidth / 2
        canvas.drawRoundRect(-ww + 5, -l + 6, ww + 8, l + 10, 12f, 12f, shadowPaint)
        canvas.drawRoundRect(-ww, -l, ww, l, 12f, 12f, carBody)
        canvas.drawRoundRect(-ww * 0.72f, -l * 0.22f, ww * 0.72f, l * 0.32f, 8f, 8f, carDark)
        canvas.drawRoundRect(-ww * 0.6f, -l * 0.12f, ww * 0.6f, l * 0.22f, 6f, 6f, carGlass)
        canvas.drawCircle(-ww * 0.5f, -l + 10, 5f, carLight)
        canvas.drawCircle(ww * 0.5f, -l + 10, 5f, carLight)
        canvas.drawCircle(-ww * 0.45f, l - 8, 4f, rearPaint)
        canvas.drawCircle(ww * 0.45f, l - 8, 4f, rearPaint)
        canvas.restore()
    }

    override fun onTouch(v: View?, event: MotionEvent): Boolean {
        if (!ready) return true
        val action = event.actionMasked

        // Global dots toggle on DOWN
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            val i = event.actionIndex
            val x = event.getX(i)
            val y = event.getY(i)
            if (btnDots.contains(x, y)) {
                showDotsMenu = !showDotsMenu
                invalidate()
                return true
            }
            if (showDotsMenu && !dotsPanel.contains(x, y)) {
                showDotsMenu = false
            }
        }

        when (state) {
            MENU -> return touchMenu(event)
            LEVELS -> return touchLevels(event)
            else -> return touchPlay(event)
        }
    }

    private fun touchMenu(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_DOWN) return true
        val x = event.x; val y = event.y
        for (i in cars.indices) if (carCards[i].contains(x, y)) {
            selectedCar = i; saveStats(); invalidate(); return true
        }
        if (btnGyro.contains(x, y)) {
            gyroOn = !gyroOn; saveStats(); beep(ToneGenerator.TONE_PROP_BEEP); return true
        }
        if (btnSens.contains(x, y)) {
            steerSens = (steerSens + 1) % 3; saveStats(); return true
        }
        if (btnSound.contains(x, y)) {
            soundOn = !soundOn; saveStats(); return true
        }
        if (btnLevels.contains(x, y)) {
            state = LEVELS; levelPage = 0; showDotsMenu = false; return true
        }
        if (btnStart.contains(x, y)) {
            applySkin()
            setupLevel(maxLevelReached.coerceAtMost(MAX_LEVEL))
            state = PLAY
            showDotsMenu = false
            beep(ToneGenerator.TONE_PROP_BEEP)
        }
        return true
    }

    private fun touchLevels(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_DOWN) return true
        val x = event.x; val y = event.y
        if (btnBack.contains(x, y)) { state = MENU; return true }
        if (btnPagePrev.contains(x, y)) {
            levelPage = (levelPage - 1).coerceAtLeast(0); return true
        }
        if (btnPageNext.contains(x, y)) {
            levelPage = (levelPage + 1).coerceAtMost(4); return true // 0..4 = 100 levels
        }
        val startLv = levelPage * 20 + 1
        for (i in 0 until 20) {
            val lv = startLv + i
            if (lv > MAX_LEVEL) break
            if (levelCells[i].contains(x, y)) {
                if (lv <= maxLevelReached) {
                    applySkin()
                    setupLevel(lv)
                    state = PLAY
                    showDotsMenu = false
                    beep(ToneGenerator.TONE_PROP_BEEP)
                } else {
                    message = "🔒 Level $lv locked — pehle clear karo"
                    beep(ToneGenerator.TONE_CDMA_SOFT_ERROR_LITE)
                }
                return true
            }
        }
        return true
    }

    private fun touchPlay(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = event.actionIndex
                val id = event.getPointerId(i)
                val x = event.getX(i)
                val y = event.getY(i)
                if (crashed) {
                    setupLevel(level); return true
                }
                if (parked && btnNext.contains(x, y)) {
                    if (level < MAX_LEVEL) setupLevel(level + 1)
                    else {
                        state = MENU
                        message = "🎉 All 100 levels done!"
                    }
                    return true
                }
                val role = roleAt(x, y)
                if (role != ROLE_NONE) pointerRole[id] = role
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val id = event.getPointerId(i)
                    if (pointerRole.containsKey(id)) continue
                    val role = roleAt(event.getX(i), event.getY(i))
                    if (role != ROLE_NONE) pointerRole[id] = role
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                pointerRole.remove(event.getPointerId(event.actionIndex))
            }
        }
        return true
    }
}
