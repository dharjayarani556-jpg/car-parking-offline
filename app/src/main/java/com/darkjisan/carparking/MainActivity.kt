package com.darkjisan.carparking

import android.app.Activity
import android.content.Context
import android.graphics.*
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
}

data class CarSkin(val name: String, val body: Int, val dark: Int)

class GameView(context: Context) : View(context), View.OnTouchListener {

    companion object {
        const val STATE_MENU = 0
        const val STATE_PLAY = 1
    }

    private var state = STATE_MENU
    private var w = 0f
    private var h = 0f
    private var ready = false

    private val cars = listOf(
        CarSkin("Red Racer", Color.parseColor("#d63031"), Color.parseColor("#2d3436")),
        CarSkin("Blue Bolt", Color.parseColor("#0984e3"), Color.parseColor("#2d3436")),
        CarSkin("Green GT", Color.parseColor("#00b894"), Color.parseColor("#2d3436")),
        CarSkin("Gold King", Color.parseColor("#fdcb6e"), Color.parseColor("#6c5ce7")),
        CarSkin("Purple Pro", Color.parseColor("#6c5ce7"), Color.parseColor("#2d3436"))
    )
    private var selectedCar = 0

    private var carX = 200f
    private var carY = 400f
    private var carAngle = 0f
    private var speed = 0f
    private val maxSpeed = 7.2f
    private val accel = 0.2f
    private val friction = 0.08f
    private val turnSpeed = 3.0f
    private val carLength = 100f
    private val carWidth = 52f

    // Multi-touch: track which pointers hold which controls
    private var throttle = 0
    private var steer = 0
    private val pointerSteer = HashMap<Int, Int>()   // pointerId -> -1/0/1
    private val pointerThrottle = HashMap<Int, Int>()

    private var level = 1
    private var score = 0
    private var parked = false
    private var crashed = false
    private var message = "Car choose karo, phir START"
    private var stars = 0
    private var totalStars = 0

    private var slotX = 0f
    private var slotY = 0f
    private var slotW = 80f
    private var slotH = 140f
    private var slotAngle = 0f
    private val walls = mutableListOf<RectF>()
    private val barriers = mutableListOf<RectF>()

    private val skyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val asphalt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#2d3436") }
    private val asphaltDark = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1e272e") }
    private val asphaltLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#fdcb6e")
        strokeWidth = 3f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(28f, 18f), 0f)
    }
    private val slotStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00b894")
        style = Paint.Style.STROKE
        strokeWidth = 8f
    }
    private val slotFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00b894")
        alpha = 55
    }
    private val wallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#636e72") }
    private val wallTop = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#dfe6e9") }
    private val barrierPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#e17055") }
    private val carBody = Paint(Paint.ANTI_ALIAS_FLAG)
    private val carDark = Paint(Paint.ANTI_ALIAS_FLAG)
    private val carGlass = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#74b9ff")
        alpha = 200
    }
    private val carLight = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#ffeaa7") }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        alpha = 75
    }
    private val rearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#ff7675") }
    private val uiBg = Paint().apply { color = Color.parseColor("#0a0a12"); alpha = 210 }
    private val menuBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#0f0c29") }
    private val cardBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1a1a2e") }
    private val cardSel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6c5ce7")
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 34f
        typeface = Typeface.DEFAULT_BOLD
    }
    private val smallText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#dfe6e9")
        textSize = 24f
    }
    private val accentText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#a29bfe")
        textSize = 30f
        typeface = Typeface.DEFAULT_BOLD
    }
    private val btnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#2d3436") }
    private val btnActive = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#6c5ce7") }
    private val startBtnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#00b894") }
    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#fdcb6e")
        textSize = 36f
    }

    private val btnLeft = RectF()
    private val btnRight = RectF()
    private val btnGas = RectF()
    private val btnBrake = RectF()
    private val btnNext = RectF()
    private val btnStart = RectF()
    private val carCards = Array(5) { RectF() }

    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private val loop = object : Runnable {
        override fun run() {
            if (!running) return
            if (ready && state == STATE_PLAY) update()
            invalidate()
            handler.postDelayed(this, 16L)
        }
    }

    init {
        setOnTouchListener(this)
        setBackgroundColor(Color.parseColor("#0f0c29"))
    }

    fun resumeGame() {
        if (!running) {
            running = true
            handler.post(loop)
        }
    }

    fun pauseGame() {
        running = false
        handler.removeCallbacks(loop)
    }

    override fun onSizeChanged(width: Int, height: Int, oldw: Int, oldh: Int) {
        if (width <= 0 || height <= 0) return
        w = width.toFloat()
        h = height.toFloat()
        skyPaint.shader = LinearGradient(
            0f, 0f, 0f, h * 0.3f,
            Color.parseColor("#0f0c29"),
            Color.parseColor("#302b63"),
            Shader.TileMode.CLAMP
        )
        layoutButtons()
        layoutMenu()
        ready = true
    }

    private fun layoutButtons() {
        val s = min(w, h) * 0.14f
        val pad = 18f
        btnLeft.set(pad, h - s * 2.15f - pad, pad + s, h - s - pad * 1.05f)
        btnRight.set(pad + s + 12, h - s * 2.15f - pad, pad + s * 2 + 12, h - s - pad * 1.05f)
        btnGas.set(w - pad - s, h - s * 2.15f - pad, w - pad, h - s - pad * 1.05f)
        btnBrake.set(w - pad - s, h - s - pad, w - pad, h - pad)
        btnNext.set(w / 2 - 120, h * 0.5f, w / 2 + 120, h * 0.5f + 70)
    }

    private fun layoutMenu() {
        val cardW = min(w * 0.16f, 140f)
        val cardH = cardW * 1.15f
        val total = cardW * 5 + 16 * 4
        var x = (w - total) / 2f
        val y = h * 0.38f
        for (i in 0 until 5) {
            carCards[i].set(x, y, x + cardW, y + cardH)
            x += cardW + 16f
        }
        btnStart.set(w / 2 - 130, h * 0.72f, w / 2 + 130, h * 0.72f + 70)
    }

    private fun applyCarSkin() {
        val c = cars[selectedCar]
        carBody.color = c.body
        carDark.color = c.dark
    }

    private fun setupLevel(lv: Int) {
        if (w < 10f || h < 10f) return
        level = lv
        parked = false
        crashed = false
        speed = 0f
        stars = 0
        walls.clear()
        barriers.clear()
        clearPointers()

        // Harder as level goes up: tighter slot, more walls
        val tight = 1f - (lv.coerceAtMost(12) * 0.02f)
        slotW = 80f * tight
        slotH = 140f * tight

        when {
            lv == 1 -> {
                carX = w * 0.15f; carY = h * 0.55f; carAngle = 0f
                slotX = w * 0.78f; slotY = h * 0.55f; slotAngle = 0f
                // lot borders
                walls.add(RectF(0f, h * 0.12f, 24f, h))
                walls.add(RectF(w - 24f, h * 0.12f, w, h))
                message = "Level 1 - Seedha park (GAS + steer ek saath)"
            }
            lv == 2 -> {
                carX = w * 0.12f; carY = h * 0.75f; carAngle = 0f
                slotX = w * 0.75f; slotY = h * 0.35f; slotAngle = 90f
                walls.add(RectF(w * 0.35f, h * 0.15f, w * 0.42f, h * 0.9f))
                barriers.add(RectF(w * 0.5f, h * 0.55f, w * 0.58f, h * 0.7f))
                message = "Level 2 - Side parking, deewar se bacho"
            }
            lv == 3 -> {
                carX = w * 0.2f; carY = h * 0.2f; carAngle = 90f
                slotX = w * 0.7f; slotY = h * 0.65f; slotAngle = -30f
                walls.add(RectF(w * 0.4f, h * 0.3f, w * 0.55f, h * 0.38f))
                walls.add(RectF(w * 0.48f, h * 0.2f, w * 0.55f, h * 0.55f))
                barriers.add(RectF(w * 0.25f, h * 0.5f, w * 0.35f, h * 0.58f))
                message = "Level 3 - Angle park"
            }
            lv == 4 -> {
                carX = w * 0.15f; carY = h * 0.5f; carAngle = 0f
                slotX = w * 0.8f; slotY = h * 0.25f; slotAngle = 0f
                walls.add(RectF(w * 0.3f, h * 0.15f, w * 0.36f, h * 0.45f))
                walls.add(RectF(w * 0.3f, h * 0.55f, w * 0.36f, h * 0.9f))
                walls.add(RectF(w * 0.55f, h * 0.35f, w * 0.62f, h * 0.75f))
                message = "Level 4 - Narrow path"
            }
            else -> {
                carX = w * 0.12f; carY = h * 0.55f; carAngle = (lv % 2) * 15f
                slotX = w * 0.72f + (lv % 3) * 10f
                slotY = h * (0.3f + (lv % 4) * 0.1f)
                slotAngle = ((lv * 25) % 90).toFloat()
                walls.add(RectF(w * 0.32f, h * 0.15f, w * 0.4f, h * 0.88f))
                if (lv >= 6) walls.add(RectF(w * 0.5f, h * 0.2f, w * 0.56f, h * 0.5f))
                if (lv >= 8) barriers.add(RectF(w * 0.58f, h * 0.55f, w * 0.68f, h * 0.65f))
                message = "Level $lv - Hard mode - slow + accurate"
            }
        }
        applyCarSkin()
    }

    private fun clearPointers() {
        pointerSteer.clear()
        pointerThrottle.clear()
        steer = 0
        throttle = 0
    }

    private fun recomputeControls() {
        steer = 0
        for (v in pointerSteer.values) if (v != 0) { steer = v; break }
        throttle = 0
        for (v in pointerThrottle.values) if (v != 0) { throttle = v; break }
    }

    private fun update() {
        if (parked || crashed) return

        when (throttle) {
            1 -> speed = (speed + accel).coerceAtMost(maxSpeed)
            -1 -> speed = (speed - accel).coerceAtLeast(-maxSpeed * 0.6f)
            else -> {
                if (speed > 0) speed = (speed - friction).coerceAtLeast(0f)
                else if (speed < 0) speed = (speed + friction).coerceAtMost(0f)
            }
        }

        // Steering works while moving OR while holding gas/rev (more responsive)
        if (abs(speed) > 0.08f || throttle != 0) {
            val dir = when {
                speed > 0.05f -> 1f
                speed < -0.05f -> -1f
                throttle < 0 -> -1f
                else -> 1f
            }
            carAngle += steer * turnSpeed * dir
        }

        val rad = Math.toRadians(carAngle.toDouble())
        carX += (sin(rad) * speed).toFloat()
        carY -= (cos(rad) * speed).toFloat()
        carX = carX.coerceIn(30f, w - 30f)
        carY = carY.coerceIn(30f, h - 30f)

        val box = carRect()
        for (wall in walls) {
            if (RectF.intersects(box, wall)) {
                onCrash()
                return
            }
        }
        for (b in barriers) {
            if (RectF.intersects(box, b)) {
                onCrash()
                return
            }
        }

        if (checkParked()) {
            parked = true
            clearPointers()
            val slowBonus = ((maxSpeed - abs(speed)) * 15).toInt()
            score += 100 + slowBonus + level * 50
            stars = when {
                abs(speed) < 1.0f -> 3
                abs(speed) < 2.2f -> 2
                else -> 1
            }
            totalStars += stars
            message = "Parked! Stars:$stars  Score:$score  → Next"
            speed = 0f
        }
    }

    private fun onCrash() {
        crashed = true
        clearPointers()
        message = "Crash! Screen touch = retry"
        speed = 0f
    }

    private fun carRect(): RectF {
        val hw = carWidth / 2
        val hl = carLength / 2
        return RectF(carX - hw, carY - hl, carX + hw, carY + hl)
    }

    private fun checkParked(): Boolean {
        val dx = carX - slotX
        val dy = carY - slotY
        val dist = sqrt(dx * dx + dy * dy)
        var ad = abs(normalizeAngle(carAngle - slotAngle))
        if (ad > 180f) ad = 360f - ad
        // Slightly easier success so stars register
        val distOk = dist < (48f - level.coerceAtMost(8))
        val angleOk = ad < (32f - level.coerceAtMost(10) * 0.8f)
        val speedOk = abs(speed) < 3.2f
        return distOk && angleOk && speedOk
    }

    private fun normalizeAngle(a: Float): Float {
        var x = a % 360f
        if (x < 0) x += 360f
        return x
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!ready || w <= 0f) {
            canvas.drawColor(Color.parseColor("#0f0c29"))
            return
        }
        if (state == STATE_MENU) {
            drawMenu(canvas)
            return
        }
        drawPlay(canvas)
    }

    private fun drawMenu(canvas: Canvas) {
        canvas.drawRect(0f, 0f, w, h, menuBg)
        accentText.textAlign = Paint.Align.CENTER
        accentText.textSize = 42f
        canvas.drawText("Jishan Gaming", w / 2f, h * 0.14f, accentText)
        accentText.textSize = 30f
        smallText.textAlign = Paint.Align.CENTER
        canvas.drawText("Car select karo, phir START dabao", w / 2f, h * 0.22f, smallText)
        canvas.drawText("Total Stars: $totalStars   Best Score: $score", w / 2f, h * 0.28f, smallText)

        for (i in cars.indices) {
            val r = carCards[i]
            canvas.drawRoundRect(r, 16f, 16f, cardBg)
            if (i == selectedCar) canvas.drawRoundRect(r, 16f, 16f, cardSel)
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cars[i].body }
            val cx = r.centerX()
            val cy = r.centerY() - 10f
            canvas.drawRoundRect(cx - 22f, cy - 40f, cx + 22f, cy + 40f, 10f, 10f, p)
            smallText.textAlign = Paint.Align.CENTER
            canvas.drawText(cars[i].name, cx, r.bottom - 14f, smallText)
        }

        canvas.drawRoundRect(btnStart, 20f, 20f, startBtnPaint)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = 32f
        canvas.drawText("START GAME", btnStart.centerX(), btnStart.centerY() + 12f, textPaint)
        textPaint.textAlign = Paint.Align.LEFT
        smallText.textAlign = Paint.Align.LEFT
        accentText.textAlign = Paint.Align.LEFT
    }

    private fun drawPlay(canvas: Canvas) {
        canvas.drawRect(0f, 0f, w, h, skyPaint)
        canvas.drawRect(0f, h * 0.1f, w, h, asphalt)

        // Map grid / lanes
        for (i in 1..10) {
            val y = h * 0.1f + (h * 0.9f) * i / 11f
            canvas.drawLine(30f, y, w - 30f, y, asphaltLine)
        }
        // center lot shade
        canvas.drawRect(w * 0.2f, h * 0.2f, w * 0.85f, h * 0.85f, asphaltDark)

        for (wall in walls) {
            canvas.drawRoundRect(wall, 6f, 6f, wallPaint)
            canvas.drawRect(wall.left, wall.top - 10f, wall.right, wall.top, wallTop)
        }
        for (b in barriers) {
            canvas.drawRoundRect(b, 4f, 4f, barrierPaint)
        }

        canvas.save()
        canvas.translate(slotX, slotY)
        canvas.rotate(slotAngle)
        canvas.drawRoundRect(-slotW / 2, -slotH / 2, slotW / 2, slotH / 2, 10f, 10f, slotFill)
        canvas.drawRoundRect(-slotW / 2, -slotH / 2, slotW / 2, slotH / 2, 10f, 10f, slotStroke)
        // P mark
        smallText.textAlign = Paint.Align.CENTER
        smallText.color = Color.parseColor("#00b894")
        canvas.drawText("P", 0f, 8f, smallText)
        smallText.color = Color.parseColor("#dfe6e9")
        smallText.textAlign = Paint.Align.LEFT
        canvas.restore()

        drawCar(canvas)

        canvas.drawRect(0f, 0f, w, 76f, uiBg)
        canvas.drawText("Jishan Gaming", 16f, 30f, accentText)
        canvas.drawText("Lv $level", 16f, 60f, textPaint)
        canvas.drawText("Score $score", w * 0.32f, 48f, textPaint)
        canvas.drawText("Stars $totalStars", w * 0.58f, 48f, starPaint)

        canvas.drawRect(0f, 76f, w, 112f, uiBg)
        canvas.drawText(message, 16f, 100f, smallText)

        drawBtn(canvas, btnLeft, "<<", steer < 0)
        drawBtn(canvas, btnRight, ">>", steer > 0)
        drawBtn(canvas, btnGas, "GAS", throttle > 0)
        drawBtn(canvas, btnBrake, "REV", throttle < 0)

        if (parked) {
            canvas.drawRoundRect(btnNext, 18f, 18f, startBtnPaint)
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText("Next Level >>", btnNext.centerX(), btnNext.centerY() + 12f, textPaint)
            textPaint.textAlign = Paint.Align.LEFT
        }
    }

    private fun drawBtn(canvas: Canvas, r: RectF, label: String, on: Boolean) {
        canvas.drawRoundRect(r, 22f, 22f, if (on) btnActive else btnPaint)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = 26f
        canvas.drawText(label, r.centerX(), r.centerY() + 9f, textPaint)
        textPaint.textSize = 34f
        textPaint.textAlign = Paint.Align.LEFT
    }

    private fun drawCar(canvas: Canvas) {
        canvas.save()
        canvas.translate(carX, carY)
        canvas.rotate(carAngle)
        val l = carLength / 2
        val ww = carWidth / 2
        canvas.drawRoundRect(-ww + 6, -l + 8, ww + 10, l + 12, 12f, 12f, shadowPaint)
        canvas.drawRoundRect(-ww, -l, ww, l, 12f, 12f, carBody)
        canvas.drawRoundRect(-ww * 0.75f, -l * 0.25f, ww * 0.75f, l * 0.35f, 8f, 8f, carDark)
        canvas.drawRoundRect(-ww * 0.65f, -l * 0.15f, ww * 0.65f, l * 0.25f, 6f, 6f, carGlass)
        canvas.drawCircle(-ww * 0.55f, -l + 10, 6f, carLight)
        canvas.drawCircle(ww * 0.55f, -l + 10, 6f, carLight)
        canvas.drawCircle(-ww * 0.5f, l - 8, 5f, rearPaint)
        canvas.drawCircle(ww * 0.5f, l - 8, 5f, rearPaint)
        canvas.restore()
    }

    private fun hitControl(x: Float, y: Float): Pair<String, Int> {
        return when {
            btnLeft.contains(x, y) -> "steer" to -1
            btnRight.contains(x, y) -> "steer" to 1
            btnGas.contains(x, y) -> "gas" to 1
            btnBrake.contains(x, y) -> "gas" to -1
            else -> "none" to 0
        }
    }

    override fun onTouch(v: View?, event: MotionEvent): Boolean {
        if (!ready) return true

        if (state == STATE_MENU) {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                val x = event.x
                val y = event.y
                for (i in carCards.indices) {
                    if (carCards[i].contains(x, y)) {
                        selectedCar = i
                        invalidate()
                        return true
                    }
                }
                if (btnStart.contains(x, y)) {
                    applyCarSkin()
                    level = 1
                    score = 0
                    setupLevel(1)
                    state = STATE_PLAY
                    message = "Level 1 - GAS + steer dono ek saath dabao"
                    invalidate()
                }
            }
            return true
        }

        // PLAY: multi-touch
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = event.actionIndex
                val id = event.getPointerId(idx)
                val x = event.getX(idx)
                val y = event.getY(idx)

                if (crashed) {
                    setupLevel(level)
                    return true
                }
                if (parked && btnNext.contains(x, y)) {
                    setupLevel(level + 1)
                    return true
                }

                val (type, value) = hitControl(x, y)
                if (type == "steer") pointerSteer[id] = value
                if (type == "gas") pointerThrottle[id] = value
                recomputeControls()
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val id = event.getPointerId(i)
                    val x = event.getX(i)
                    val y = event.getY(i)
                    val (type, value) = hitControl(x, y)
                    // update this pointer's binding
                    pointerSteer.remove(id)
                    pointerThrottle.remove(id)
                    if (type == "steer") pointerSteer[id] = value
                    if (type == "gas") pointerThrottle[id] = value
                }
                recomputeControls()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val idx = event.actionIndex
                val id = event.getPointerId(idx)
                pointerSteer.remove(id)
                pointerThrottle.remove(id)
                recomputeControls()
            }
        }
        return true
    }
}
