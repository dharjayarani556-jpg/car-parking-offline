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
        const val PLAY = 1
        const val PANEL = 2
        const val ROLE_NONE = 0
        const val ROLE_LEFT = 1
        const val ROLE_RIGHT = 2
        const val ROLE_GAS = 3
        const val ROLE_REV = 4
        const val MAX_LEVEL = 100
    }

    private val prefs = context.getSharedPreferences("jishan_park", Context.MODE_PRIVATE)
    private var state = MENU
    private var w = 0f
    private var h = 0f
    private var ready = false

    private val cars = listOf(
        CarSkin("Red", Color.parseColor("#e74c3c"), Color.parseColor("#2c3e50")),
        CarSkin("Blue", Color.parseColor("#3498db"), Color.parseColor("#2c3e50")),
        CarSkin("Green", Color.parseColor("#1abc9c"), Color.parseColor("#2c3e50")),
        CarSkin("Gold", Color.parseColor("#f1c40f"), Color.parseColor("#6c5ce7")),
        CarSkin("Purple", Color.parseColor("#9b59b6"), Color.parseColor("#2c3e50"))
    )
    private var selectedCar = 0

    private var carX = 200f
    private var carY = 400f
    private var carAngle = 0f
    private var speed = 0f
    private val maxSpeed = 6.8f
    private val accel = 0.2f
    private val friction = 0.085f
    private var turnRate = 3.0f
    private val carLength = 100f
    private val carWidth = 52f

    private val pointerRole = HashMap<Int, Int>()

    private var level = 1
    private var score = 0
    private var parked = false
    private var crashed = false
    private var message = "Car choose → START"
    private var stars = 0
    private var totalStars = 0
    private var crashes = 0
    private var wins = 0
    private var maxReached = 1
    private var levelStartMs = 0L

    // Settings
    private var steerBig = true
    private var gyroOn = false
    private var gyroSteer = 0f

    private var slotX = 0f
    private var slotY = 0f
    private var slotW = 88f
    private var slotH = 150f
    private var slotAngle = 0f
    private val walls = mutableListOf<RectF>()
    private val barriers = mutableListOf<RectF>()
    private val parkedCars = mutableListOf<ParkedCar>()

    // Blast effect
    private var blastX = 0f
    private var blastY = 0f
    private var blastLife = 0

    // Level select scroll in panel
    private var panelScroll = 0f

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private var tone: ToneGenerator? = try {
        ToneGenerator(AudioManager.STREAM_MUSIC, 60)
    } catch (_: Throwable) { null }

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
    private val panelRect = RectF()
    private val carCards = Array(5) { RectF() }

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
        color = Color.parseColor("#74b9ff"); alpha = 200
    }
    private val carLight = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#ffeaa7") }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; alpha = 75 }
    private val rearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#ff7675") }
    private val uiBg = Paint().apply { color = Color.parseColor("#0a0a12"); alpha = 200 }
    private val menuBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#0f0c29") }
    private val cardBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1a1a2e") }
    private val cardSel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#a29bfe"); style = Paint.Style.STROKE; strokeWidth = 5f
    }
    private val panelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#12121f"); alpha = 245 }
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
    private val lockedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#4a4a5a") }
    private val blastPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#fdcb6e"); textSize = 64f; textAlign = Paint.Align.CENTER
    }

    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private val loop = object : Runnable {
        override fun run() {
            if (!running) return
            if (ready && state == PLAY) update()
            if (blastLife > 0) blastLife--
            invalidate()
            handler.postDelayed(this, 16L)
        }
    }

    init {
        setOnTouchListener(this)
        loadStats()
        setBackgroundColor(Color.parseColor("#0f0c29"))
    }

    private fun loadStats() {
        score = prefs.getInt("score", 0)
        totalStars = prefs.getInt("stars", 0)
        crashes = prefs.getInt("crashes", 0)
        wins = prefs.getInt("wins", 0)
        maxReached = prefs.getInt("maxReached", 1).coerceIn(1, MAX_LEVEL)
        steerBig = prefs.getBoolean("steerBig", true)
        gyroOn = prefs.getBoolean("gyroOn", false)
        selectedCar = prefs.getInt("car", 0).coerceIn(0, cars.lastIndex)
    }

    private fun saveStats() {
        prefs.edit()
            .putInt("score", score)
            .putInt("stars", totalStars)
            .putInt("crashes", crashes)
            .putInt("wins", wins)
            .putInt("maxReached", maxReached)
            .putBoolean("steerBig", steerBig)
            .putBoolean("gyroOn", gyroOn)
            .putInt("car", selectedCar)
            .apply()
    }

    fun resumeGame() {
        if (!running) {
            running = true
            handler.post(loop)
        }
        if (gyroOn) registerGyro()
    }

    fun pauseGame() {
        running = false
        handler.removeCallbacks(loop)
        unregisterGyro()
        saveStats()
    }

    fun release() {
        unregisterGyro()
        tone?.release()
        tone = null
        saveStats()
    }

    private fun registerGyro() {
        rotationSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    private fun unregisterGyro() {
        try { sensorManager.unregisterListener(this) } catch (_: Throwable) {}
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (!gyroOn || event == null || state != PLAY) return
        // Use Y axis tilt for steer approx
        val v = if (event.values.size >= 2) event.values[1] else 0f
        gyroSteer = (-v * 2.2f).coerceIn(-1.2f, 1.2f)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun beep(type: Int) {
        try { tone?.startTone(type, 120) } catch (_: Throwable) {}
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
        val scale = if (steerBig) 0.155f else 0.115f
        val s = min(w, h) * scale
        val pad = 14f
        btnLeft.set(pad, h - s * 2.15f - pad, pad + s, h - s - pad)
        btnRight.set(pad + s + 12f, h - s * 2.15f - pad, pad + s * 2 + 12f, h - s - pad)
        btnGas.set(w - pad - s, h - s * 2.15f - pad, w - pad, h - s - pad)
        btnRev.set(w - pad - s, h - s - pad + 4f, w - pad, h - pad)
        val g = 22f
        touchLeft.set(btnLeft.left - g, btnLeft.top - g, btnLeft.right + g, btnLeft.bottom + g)
        touchRight.set(btnRight.left - g, btnRight.top - g, btnRight.right + g, btnRight.bottom + g)
        touchGas.set(btnGas.left - g, btnGas.top - g, btnGas.right + g, btnGas.bottom + g)
        touchRev.set(btnRev.left - g, btnRev.top - g, btnRev.right + g, btnRev.bottom + g)
        btnNext.set(w / 2 - 120f, h * 0.46f, w / 2 + 120f, h * 0.46f + 68f)
        btnDots.set(w - 70f, 8f, w - 12f, 52f)
        panelRect.set(w * 0.12f, h * 0.08f, w * 0.88f, h * 0.92f)
        btnStart.set(w / 2 - 130f, h * 0.78f, w / 2 + 130f, h * 0.78f + 68f)
        val cardW = min(w * 0.14f, 120f)
        val gap = 12f
        val total = cardW * 5 + gap * 4
        var x = (w - total) / 2
        val y = h * 0.4f
        for (i in 0 until 5) {
            carCards[i].set(x, y, x + cardW, y + cardW * 1.15f)
            x += cardW + gap
        }
    }

    private fun applySkin() {
        carBody.color = cars[selectedCar].body
        carDark.color = cars[selectedCar].dark
    }

    /** Build one of 100 levels — medium hard, varied. */
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
        levelStartMs = SystemClock.elapsedRealtime()

        val t = 1f - (level * 0.0025f).coerceAtMost(0.22f)
        slotW = 90f * t
        slotH = 152f * t

        // Borders always
        walls.add(RectF(0f, h * 0.08f, 18f, h))
        walls.add(RectF(w - 18f, h * 0.08f, w, h))
        walls.add(RectF(0f, h - 18f, w, h))

        val pattern = (level - 1) % 10
        carX = w * 0.12f
        carY = h * 0.55f
        carAngle = 0f

        when (pattern) {
            0 -> { // straight
                slotX = w * 0.82f; slotY = h * 0.55f; slotAngle = 0f
                if (level > 5) addSideCars(w * 0.45f, h * 0.3f, 3)
            }
            1 -> { // side bay
                carY = h * 0.75f
                slotX = w * 0.75f; slotY = h * 0.32f; slotAngle = 90f
                walls.add(RectF(w * 0.38f, h * 0.12f, w * 0.44f, h * 0.88f))
                addSideCars(w * 0.55f, h * 0.6f, 2)
            }
            2 -> { // angle
                carX = w * 0.15f; carY = h * 0.22f; carAngle = 85f
                slotX = w * 0.72f; slotY = h * 0.65f; slotAngle = -28f
                walls.add(RectF(w * 0.42f, h * 0.3f, w * 0.55f, h * 0.38f))
                barriers.add(RectF(w * 0.3f, h * 0.5f, w * 0.38f, h * 0.58f))
            }
            3 -> { // corridor
                slotX = w * 0.8f; slotY = h * 0.28f; slotAngle = 0f
                walls.add(RectF(w * 0.3f, h * 0.12f, w * 0.36f, h * 0.48f))
                walls.add(RectF(w * 0.3f, h * 0.58f, w * 0.36f, h * 0.9f))
                walls.add(RectF(w * 0.55f, h * 0.35f, w * 0.61f, h * 0.75f))
            }
            4 -> { // U shape feel
                carY = h * 0.7f; carAngle = -10f
                slotX = w * 0.7f; slotY = h * 0.35f; slotAngle = 180f
                walls.add(RectF(w * 0.35f, h * 0.2f, w * 0.7f, h * 0.28f))
                walls.add(RectF(w * 0.35f, h * 0.2f, w * 0.42f, h * 0.7f))
                addSideCars(w * 0.5f, h * 0.55f, 2)
            }
            5 -> {
                slotX = w * 0.78f; slotY = h * 0.5f; slotAngle = 15f
                barriers.add(RectF(w * 0.4f, h * 0.4f, w * 0.48f, h * 0.55f))
                barriers.add(RectF(w * 0.55f, h * 0.25f, w * 0.62f, h * 0.35f))
                addSideCars(w * 0.25f, h * 0.3f, 2)
            }
            6 -> {
                carX = w * 0.2f; carY = h * 0.8f; carAngle = 0f
                slotX = w * 0.55f; slotY = h * 0.25f; slotAngle = 90f
                walls.add(RectF(w * 0.35f, h * 0.35f, w * 0.75f, h * 0.42f))
                addSideCars(w * 0.7f, h * 0.55f, 3)
            }
            7 -> {
                slotX = w * 0.8f; slotY = h * 0.7f; slotAngle = -10f
                walls.add(RectF(w * 0.32f, h * 0.15f, w * 0.4f, h * 0.85f))
                walls.add(RectF(w * 0.55f, h * 0.15f, w * 0.62f, h * 0.5f))
            }
            8 -> {
                carAngle = 30f
                slotX = w * 0.75f; slotY = h * 0.4f; slotAngle = 45f
                barriers.add(RectF(w * 0.4f, h * 0.55f, w * 0.55f, h * 0.62f))
                addSideCars(w * 0.28f, h * 0.25f, 2)
            }
            else -> {
                slotX = w * 0.78f; slotY = h * (0.3f + (level % 5) * 0.08f)
                slotAngle = ((level * 17) % 70).toFloat()
                walls.add(RectF(w * 0.34f, h * 0.15f, w * 0.41f, h * 0.88f))
                if (level > 20) barriers.add(RectF(w * 0.5f, h * 0.45f, w * 0.58f, h * 0.55f))
                addSideCars(w * 0.55f, h * 0.7f, 1 + level % 3)
            }
        }

        // Extra difficulty after level 30 / 60
        if (level >= 30) {
            barriers.add(RectF(w * 0.48f, h * 0.7f, w * 0.56f, h * 0.78f))
        }
        if (level >= 60) {
            walls.add(RectF(w * 0.62f, h * 0.2f, w * 0.68f, h * 0.45f))
        }

        applySkin()
        message = "Level $level / $MAX_LEVEL — Green P mein park"
        beep(ToneGenerator.TONE_PROP_BEEP)
    }

    private fun addSideCars(baseX: Float, baseY: Float, count: Int) {
        val colors = intArrayOf(
            Color.parseColor("#636e72"), Color.parseColor("#2d3436"),
            Color.parseColor("#b2bec3"), Color.parseColor("#0984e3")
        )
        for (i in 0 until count) {
            parkedCars.add(
                ParkedCar(
                    baseX + i * 70f,
                    baseY + (i % 2) * 40f,
                    if (i % 2 == 0) 0f else 90f,
                    colors[i % colors.size]
                )
            )
        }
    }

    private fun steerValue(): Int {
        var s = 0
        for (r in pointerRole.values) {
            if (r == ROLE_LEFT) s = -1
            if (r == ROLE_RIGHT) s = 1
        }
        if (gyroOn && abs(gyroSteer) > 0.15f) {
            s = if (gyroSteer < 0) -1 else 1
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
        val steer = steerValue()
        val throttle = throttleValue()

        when (throttle) {
            1 -> speed = (speed + accel).coerceAtMost(maxSpeed)
            -1 -> speed = (speed - accel).coerceAtLeast(-maxSpeed * 0.55f)
            else -> {
                if (speed > 0) speed = (speed - friction).coerceAtLeast(0f)
                else if (speed < 0) speed = (speed + friction).coerceAtMost(0f)
            }
        }

        if (steer != 0 && (abs(speed) > 0.05f || throttle != 0 || gyroOn)) {
            val dir = when {
                speed > 0.05f -> 1f
                speed < -0.05f -> -1f
                throttle < 0 -> -1f
                else -> 1f
            }
            val extra = if (gyroOn) abs(gyroSteer).coerceAtMost(1f) else 1f
            carAngle += steer * turnRate * dir * extra
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
            val pr = RectF(pc.x - 28f, pc.y - 45f, pc.x + 28f, pc.y + 45f)
            if (RectF.intersects(box, pr)) { onCrash(); return }
        }

        if (checkParked()) {
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
            wins++
            score += 80 + stars * 30 + level * 5
            if (level >= maxReached && level < MAX_LEVEL) maxReached = level + 1
            else if (level == MAX_LEVEL) maxReached = MAX_LEVEL
            maxReached = maxReached.coerceAtMost(MAX_LEVEL)
            saveStats()
            message = "Win! ${stars}★  Level $level clear"
            beep(ToneGenerator.TONE_PROP_ACK)
        }
    }

    private fun onCrash() {
        crashed = true
        crashes++
        pointerRole.clear()
        speed = 0f
        blastX = carX
        blastY = carY
        blastLife = 40
        saveStats()
        message = "💥 Crash! Touch = retry"
        beep(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD)
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
        val distLim = 52f - (level * 0.08f).coerceAtMost(12f)
        val angLim = 32f - (level * 0.05f).coerceAtMost(8f)
        return dist < distLim && ad < angLim && abs(speed) < 3.4f
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
            PLAY -> {
                drawPlay(canvas)
                if (state == PLAY) { /* panel opened sets PANEL */ }
            }
            PANEL -> {
                drawPlay(canvas)
                drawPanel(canvas)
            }
        }
    }

    private fun drawMenu(canvas: Canvas) {
        canvas.drawRect(0f, 0f, w, h, menuBg)
        accent.textAlign = Paint.Align.CENTER
        accent.textSize = 38f
        canvas.drawText("Jishan Gaming", w / 2f, h * 0.14f, accent)
        smallText.textAlign = Paint.Align.CENTER
        canvas.drawText("100 Levels  •  Offline Parking", w / 2f, h * 0.22f, smallText)
        canvas.drawText("★$totalStars  Wins:$wins  Crash:$crashes  MaxLv:$maxReached", w / 2f, h * 0.3f, smallText)

        for (i in cars.indices) {
            val r = carCards[i]
            canvas.drawRoundRect(r, 16f, 16f, cardBg)
            if (i == selectedCar) canvas.drawRoundRect(r, 16f, 16f, cardSel)
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cars[i].body }
            canvas.drawRoundRect(r.centerX() - 22f, r.centerY() - 36f, r.centerX() + 22f, r.centerY() + 36f, 10f, 10f, p)
            smallText.textAlign = Paint.Align.CENTER
            canvas.drawText(cars[i].name, r.centerX(), r.bottom - 10f, smallText)
        }

        // options row
        smallText.textAlign = Paint.Align.CENTER
        canvas.drawText(
            "Steer: ${if (steerBig) "BIG" else "SMALL"}   Gyro: ${if (gyroOn) "ON" else "OFF"}",
            w / 2f, h * 0.68f, smallText
        )
        canvas.drawText("Menu se change • 3 dots in game", w / 2f, h * 0.73f, smallText)

        canvas.drawRoundRect(btnStart, 20f, 20f, startPaint)
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("START", btnStart.centerX(), btnStart.centerY() + 10f, textPaint)
        textPaint.textAlign = Paint.Align.LEFT
        smallText.textAlign = Paint.Align.LEFT
        accent.textAlign = Paint.Align.LEFT
    }

    private fun drawPlay(canvas: Canvas) {
        canvas.drawRect(0f, 0f, w, h, skyPaint)
        canvas.drawRect(0f, h * 0.08f, w, h, asphalt)
        canvas.drawRect(w * 0.15f, h * 0.15f, w * 0.9f, h * 0.9f, asphaltDark)
        for (i in 1..8) {
            val y = h * 0.08f + h * 0.9f * i / 9f
            canvas.drawLine(20f, y, w - 20f, y, linePaint)
        }
        for (wall in walls) {
            canvas.drawRoundRect(wall, 5f, 5f, wallPaint)
            canvas.drawRect(wall.left, wall.top - 8f, wall.right, wall.top, wallTop)
        }
        for (b in barriers) canvas.drawRoundRect(b, 4f, 4f, barrierPaint)

        for (pc in parkedCars) {
            canvas.save()
            canvas.translate(pc.x, pc.y)
            canvas.rotate(pc.angle)
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pc.color }
            canvas.drawRoundRect(-26f, -42f, 26f, 42f, 10f, 10f, p)
            canvas.restore()
        }

        canvas.save()
        canvas.translate(slotX, slotY)
        canvas.rotate(slotAngle)
        canvas.drawRoundRect(-slotW / 2, -slotH / 2, slotW / 2, slotH / 2, 12f, 12f, slotFill)
        canvas.drawRoundRect(-slotW / 2, -slotH / 2, slotW / 2, slotH / 2, 12f, 12f, slotStroke)
        smallText.textAlign = Paint.Align.CENTER
        smallText.color = Color.parseColor("#00b894")
        canvas.drawText("P", 0f, 8f, smallText)
        smallText.color = Color.parseColor("#dfe6e9")
        smallText.textAlign = Paint.Align.LEFT
        canvas.restore()

        drawCar(canvas)

        if (blastLife > 0) {
            blastPaint.alpha = (blastLife * 6).coerceAtMost(255)
            blastPaint.textSize = 40f + (40 - blastLife)
            canvas.drawText("💥", blastX, blastY, blastPaint)
        }

        // Minimal top: only 3 dots
        canvas.drawRoundRect(btnDots, 16f, 16f, btnFace)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = 28f
        canvas.drawText("⋮", btnDots.centerX(), btnDots.centerY() + 10f, textPaint)

        // thin status line
        smallText.textAlign = Paint.Align.LEFT
        canvas.drawText("Lv $level/$MAX_LEVEL  ★$stars  $message", 12f, 28f, smallText)

        val st = steerValue()
        val th = throttleValue()
        drawCtrl(canvas, btnLeft, "◀ STEER", st < 0, btnFace, btnOn)
        drawCtrl(canvas, btnRight, "STEER ▶", st > 0, btnFace, btnOn)
        drawCtrl(canvas, btnGas, "GAS", th > 0, gasFace, gasOn)
        drawCtrl(canvas, btnRev, "REV", th < 0, revFace, revOn)

        smallText.textAlign = Paint.Align.CENTER
        canvas.drawText(
            "SPD ${"%.1f".format(abs(speed))}  ${if (gyroOn) "GYRO ON" else ""}",
            w / 2f, h - 14f, smallText
        )
        smallText.textAlign = Paint.Align.LEFT

        if (parked) {
            canvas.drawRoundRect(btnNext, 18f, 18f, startPaint)
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(
                if (level < MAX_LEVEL) "NEXT LEVEL ▶" else "ALL CLEAR ★",
                btnNext.centerX(), btnNext.centerY() + 10f, textPaint
            )
            textPaint.textAlign = Paint.Align.LEFT
        }
    }

    private fun drawPanel(canvas: Canvas) {
        canvas.drawRoundRect(panelRect, 24f, 24f, panelBg)
        var y = panelRect.top + 36f
        val cx = panelRect.centerX()
        accent.textAlign = Paint.Align.CENTER
        canvas.drawText("Jishan Menu", cx, y, accent)
        y += 36f
        smallText.textAlign = Paint.Align.CENTER
        canvas.drawText("Score: $score", cx, y, smallText); y += 26f
        canvas.drawText("Wins: $wins    Crashes: $crashes", cx, y, smallText); y += 26f
        canvas.drawText("Stars: $totalStars    Max Level: $maxReached", cx, y, smallText); y += 34f

        // toggles
        canvas.drawText(
            "[Steer ${if (steerBig) "BIG" else "SMALL"}]   [Gyro ${if (gyroOn) "ON" else "OFF"}]",
            cx, y, smallText
        )
        y += 22f
        canvas.drawText("Tap labels to toggle  •  Tap level to play (unlocked)", cx, y, smallText)
        y += 30f

        // level grid 1..100
        val cols = 10
        val cell = (panelRect.width() - 40f) / cols
        val startY = y
        for (lv in 1..MAX_LEVEL) {
            val i = lv - 1
            val col = i % cols
            val row = i / cols
            val left = panelRect.left + 20f + col * cell
            val top = startY + row * (cell * 0.55f) - panelScroll
            if (top < panelRect.top + 20 || top > panelRect.bottom - 30) continue
            val r = RectF(left + 2, top, left + cell - 4, top + cell * 0.48f)
            val unlocked = lv <= maxReached
            canvas.drawRoundRect(r, 8f, 8f, if (unlocked) cardBg else lockedPaint)
            smallText.textAlign = Paint.Align.CENTER
            smallText.color = if (unlocked) Color.WHITE else Color.GRAY
            canvas.drawText("$lv", r.centerX(), r.centerY() + 6f, smallText)
            smallText.color = Color.parseColor("#dfe6e9")
        }
        smallText.textAlign = Paint.Align.CENTER
        canvas.drawText("Tap outside / ⋮ to close", cx, panelRect.bottom - 16f, smallText)
        smallText.textAlign = Paint.Align.LEFT
        accent.textAlign = Paint.Align.LEFT
    }

    private fun drawCtrl(canvas: Canvas, r: RectF, label: String, on: Boolean, off: Paint, active: Paint) {
        canvas.drawRoundRect(r, r.height() / 2.2f, r.height() / 2.2f, if (on) active else off)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = if (steerBig) 22f else 18f
        canvas.drawText(label, r.centerX(), r.centerY() + 7f, textPaint)
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
        canvas.drawRoundRect(-ww * 0.7f, -l * 0.2f, ww * 0.7f, l * 0.3f, 8f, 8f, carDark)
        canvas.drawRoundRect(-ww * 0.6f, -l * 0.1f, ww * 0.6f, l * 0.2f, 6f, 6f, carGlass)
        canvas.drawCircle(-ww * 0.5f, -l + 10, 5f, carLight)
        canvas.drawCircle(ww * 0.5f, -l + 10, 5f, carLight)
        canvas.drawCircle(-ww * 0.45f, l - 8, 4f, rearPaint)
        canvas.drawCircle(ww * 0.45f, l - 8, 4f, rearPaint)
        canvas.restore()
    }

    private fun levelCellAt(x: Float, y: Float): Int {
        val cols = 10
        val cell = (panelRect.width() - 40f) / cols
        var y0 = panelRect.top + 36f + 36f + 26f + 26f + 34f + 22f + 30f
        for (lv in 1..MAX_LEVEL) {
            val i = lv - 1
            val col = i % cols
            val row = i / cols
            val left = panelRect.left + 20f + col * cell
            val top = y0 + row * (cell * 0.55f) - panelScroll
            val r = RectF(left + 2, top, left + cell - 4, top + cell * 0.48f)
            if (r.contains(x, y)) return lv
        }
        return -1
    }

    override fun onTouch(v: View?, event: MotionEvent): Boolean {
        if (!ready) return true
        val x = event.x
        val y = event.y

        if (state == MENU) {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                for (i in carCards.indices) if (carCards[i].contains(x, y)) {
                    selectedCar = i; saveStats(); return true
                }
                // quick toggles on menu text area
                if (y > h * 0.64f && y < h * 0.72f) {
                    if (x < w / 2) steerBig = !steerBig else {
                        gyroOn = !gyroOn
                        if (gyroOn) registerGyro() else unregisterGyro()
                    }
                    layoutAll()
                    saveStats()
                    return true
                }
                if (btnStart.contains(x, y)) {
                    applySkin()
                    setupLevel(maxReached.coerceIn(1, MAX_LEVEL))
                    state = PLAY
                    if (gyroOn) registerGyro()
                    message = "2 ungli: GAS + STEER"
                }
            }
            return true
        }

        if (state == PANEL) {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                if (!panelRect.contains(x, y) || btnDots.contains(x, y)) {
                    state = PLAY
                    return true
                }
                // toggle steer / gyro by y band
                if (y < panelRect.top + 200f) {
                    if (x < panelRect.centerX()) {
                        steerBig = !steerBig
                        layoutAll()
                    } else {
                        gyroOn = !gyroOn
                        if (gyroOn) registerGyro() else unregisterGyro()
                    }
                    saveStats()
                    return true
                }
                val lv = levelCellAt(x, y)
                if (lv in 1..maxReached) {
                    setupLevel(lv)
                    state = PLAY
                    beep(ToneGenerator.TONE_PROP_BEEP)
                }
            }
            return true
        }

        // PLAY
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = event.actionIndex
                val id = event.getPointerId(i)
                val px = event.getX(i)
                val py = event.getY(i)
                if (btnDots.contains(px, py)) {
                    state = PANEL
                    pointerRole.clear()
                    return true
                }
                if (crashed) {
                    setupLevel(level)
                    return true
                }
                if (parked && btnNext.contains(px, py)) {
                    if (level < MAX_LEVEL) setupLevel(level + 1)
                    else state = MENU
                    return true
                }
                val role = roleAt(px, py)
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
