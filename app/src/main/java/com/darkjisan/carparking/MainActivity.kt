package com.darkjisan.carparking

import android.app.Activity
import android.content.Context
import android.graphics.*
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
}

data class CarSkin(val name: String, val body: Int, val dark: Int)

class GameView(context: Context) : View(context), View.OnTouchListener {

    companion object {
        const val MENU = 0
        const val PLAY = 1
        // Pointer roles
        const val ROLE_NONE = 0
        const val ROLE_LEFT = 1
        const val ROLE_RIGHT = 2
        const val ROLE_GAS = 3
        const val ROLE_REV = 4
    }

    private var state = MENU
    private var w = 0f
    private var h = 0f
    private var ready = false

    private val cars = listOf(
        CarSkin("Red Racer", Color.parseColor("#e74c3c"), Color.parseColor("#2c3e50")),
        CarSkin("Blue Bolt", Color.parseColor("#3498db"), Color.parseColor("#2c3e50")),
        CarSkin("Green GT", Color.parseColor("#1abc9c"), Color.parseColor("#2c3e50")),
        CarSkin("Gold King", Color.parseColor("#f1c40f"), Color.parseColor("#6c5ce7")),
        CarSkin("Purple Pro", Color.parseColor("#9b59b6"), Color.parseColor("#2c3e50"))
    )
    private var selectedCar = 0

    private var carX = 200f
    private var carY = 400f
    private var carAngle = 0f
    private var speed = 0f
    private val maxSpeed = 7.0f
    private val accel = 0.22f
    private val friction = 0.09f
    private val turnRate = 3.2f
    private val carLength = 102f
    private val carWidth = 54f

    /** Sticky multi-touch: pointerId -> role. Finger keeps role until lift. */
    private val pointerRole = HashMap<Int, Int>()

    private var level = 1
    private var score = 0
    private var parked = false
    private var crashed = false
    private var message = "Car choose karo → START"
    private var stars = 0
    private var totalStars = 0
    private var levelStartMs = 0L
    private var bestTimeSec = 9999

    private var slotX = 0f
    private var slotY = 0f
    private var slotW = 86f
    private var slotH = 148f
    private var slotAngle = 0f
    private val walls = mutableListOf<RectF>()
    private val barriers = mutableListOf<RectF>()

    // Visual hit areas (drawn) + larger touch pads
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
    private val btnMenu = RectF()
    private val carCards = Array(5) { RectF() }

    private val skyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val asphalt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#2d3436") }
    private val asphaltDark = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1e272e") }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#fdcb6e")
        strokeWidth = 3f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(26f, 16f), 0f)
    }
    private val slotStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00b894")
        style = Paint.Style.STROKE
        strokeWidth = 8f
    }
    private val slotFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00b894")
        alpha = 60
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
    private val uiBg = Paint().apply { color = Color.parseColor("#0a0a12"); alpha = 220 }
    private val menuBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#0f0c29") }
    private val cardBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1a1a2e") }
    private val cardSel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#a29bfe"); style = Paint.Style.STROKE; strokeWidth = 6f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 32f; typeface = Typeface.DEFAULT_BOLD
    }
    private val smallText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#dfe6e9"); textSize = 22f
    }
    private val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#a29bfe"); textSize = 28f; typeface = Typeface.DEFAULT_BOLD
    }
    private val btnFace = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#2d3436") }
    private val btnOn = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#6c5ce7") }
    private val gasFace = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#00b894") }
    private val gasOn = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#55efc4") }
    private val revFace = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#d63031") }
    private val revOn = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#ff7675") }
    private val startPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#00b894") }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#a29bfe"); style = Paint.Style.STROKE; strokeWidth = 4f
    }
    private val logoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#a29bfe"); style = Paint.Style.FILL
    }
    private val logoChrome = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#dfe6e9"); style = Paint.Style.STROKE; strokeWidth = 5f
    }

    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private val loop = object : Runnable {
        override fun run() {
            if (!running) return
            if (ready && state == PLAY) update()
            invalidate()
            handler.postDelayed(this, 16L)
        }
    }

    init {
        setOnTouchListener(this)
        setBackgroundColor(Color.parseColor("#0f0c29"))
    }

    fun resumeGame() {
        if (!running) { running = true; handler.post(loop) }
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
            0f, 0f, 0f, h * 0.28f,
            Color.parseColor("#0f0c29"), Color.parseColor("#302b63"), Shader.TileMode.CLAMP
        )
        layoutControls()
        layoutMenu()
        ready = true
    }

    private fun layoutControls() {
        val s = min(w, h) * 0.145f
        val pad = 16f
        // Visual buttons
        btnLeft.set(pad, h - s * 2.2f - pad, pad + s, h - s - pad)
        btnRight.set(pad + s + 14f, h - s * 2.2f - pad, pad + s * 2f + 14f, h - s - pad)
        btnGas.set(w - pad - s, h - s * 2.2f - pad, w - pad, h - s - pad)
        btnRev.set(w - pad - s, h - s - pad + 6f, w - pad, h - pad)
        // Larger invisible touch pads so multi-touch is easy
        val grow = 18f
        touchLeft.set(btnLeft.left - grow, btnLeft.top - grow, btnLeft.right + grow, btnLeft.bottom + grow)
        touchRight.set(btnRight.left - grow, btnRight.top - grow, btnRight.right + grow, btnRight.bottom + grow)
        touchGas.set(btnGas.left - grow, btnGas.top - grow, btnGas.right + grow, btnGas.bottom + grow)
        touchRev.set(btnRev.left - grow, btnRev.top - grow, btnRev.right + grow, btnRev.bottom + grow)
        btnNext.set(w / 2f - 130f, h * 0.48f, w / 2f + 130f, h * 0.48f + 72f)
        btnMenu.set(w - 160f, 16f, w - 20f, 58f)
    }

    private fun layoutMenu() {
        val cardW = min(w * 0.155f, 132f)
        val cardH = cardW * 1.2f
        val gap = 14f
        val total = cardW * 5 + gap * 4
        var x = (w - total) / 2f
        val y = h * 0.42f
        for (i in 0 until 5) {
            carCards[i].set(x, y, x + cardW, y + cardH)
            x += cardW + gap
        }
        btnStart.set(w / 2f - 140f, h * 0.74f, w / 2f + 140f, h * 0.74f + 74f)
    }

    private fun applySkin() {
        val c = cars[selectedCar]
        carBody.color = c.body
        carDark.color = c.dark
    }

    private fun setupLevel(lv: Int) {
        if (w < 10f) return
        level = lv
        parked = false
        crashed = false
        speed = 0f
        stars = 0
        walls.clear()
        barriers.clear()
        pointerRole.clear()
        levelStartMs = SystemClock.elapsedRealtime()

        val tight = (1f - lv.coerceAtMost(15) * 0.015f).coerceAtLeast(0.75f)
        slotW = 86f * tight
        slotH = 148f * tight

        when {
            lv == 1 -> {
                carX = w * 0.14f; carY = h * 0.55f; carAngle = 0f
                slotX = w * 0.8f; slotY = h * 0.55f; slotAngle = 0f
                walls.add(RectF(0f, h * 0.1f, 22f, h))
                walls.add(RectF(w - 22f, h * 0.1f, w, h))
                message = "Rule: Green P mein dheere park = zyada stars"
            }
            lv == 2 -> {
                carX = w * 0.12f; carY = h * 0.78f; carAngle = 0f
                slotX = w * 0.78f; slotY = h * 0.32f; slotAngle = 90f
                walls.add(RectF(w * 0.36f, h * 0.12f, w * 0.43f, h * 0.92f))
                barriers.add(RectF(w * 0.52f, h * 0.58f, w * 0.6f, h * 0.72f))
                message = "Side park + orange barrier se bacho"
            }
            lv == 3 -> {
                carX = w * 0.18f; carY = h * 0.2f; carAngle = 90f
                slotX = w * 0.72f; slotY = h * 0.68f; slotAngle = -25f
                walls.add(RectF(w * 0.4f, h * 0.28f, w * 0.56f, h * 0.36f))
                walls.add(RectF(w * 0.5f, h * 0.18f, w * 0.57f, h * 0.55f))
                message = "Angle park - 2 ungli use karo"
            }
            else -> {
                carX = w * 0.12f; carY = h * 0.55f; carAngle = (lv % 3) * 10f
                slotX = w * 0.75f; slotY = h * (0.28f + (lv % 5) * 0.08f)
                slotAngle = ((lv * 20) % 80).toFloat()
                walls.add(RectF(w * 0.33f, h * 0.12f, w * 0.4f, h * 0.9f))
                if (lv >= 5) walls.add(RectF(w * 0.52f, h * 0.2f, w * 0.58f, h * 0.55f))
                if (lv >= 7) barriers.add(RectF(w * 0.6f, h * 0.58f, w * 0.7f, h * 0.68f))
                message = "Level $lv Hard - slow + seedha = 3 stars"
            }
        }
        applySkin()
    }

    private fun steerValue(): Int {
        var s = 0
        for (r in pointerRole.values) {
            when (r) {
                ROLE_LEFT -> s = -1
                ROLE_RIGHT -> s = 1
            }
        }
        return s
    }

    private fun throttleValue(): Int {
        var t = 0
        for (r in pointerRole.values) {
            when (r) {
                ROLE_GAS -> t = 1
                ROLE_REV -> t = -1
            }
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

        // Always allow steering when throttle held OR moving
        if (steer != 0 && (abs(speed) > 0.05f || throttle != 0)) {
            val dir = when {
                speed > 0.05f -> 1f
                speed < -0.05f -> -1f
                throttle < 0 -> -1f
                else -> 1f
            }
            carAngle += steer * turnRate * dir
        }

        val rad = Math.toRadians(carAngle.toDouble())
        carX += (sin(rad) * speed).toFloat()
        carY -= (cos(rad) * speed).toFloat()
        carX = carX.coerceIn(28f, w - 28f)
        carY = carY.coerceIn(28f, h - 28f)

        val box = carRect()
        for (wall in walls) if (RectF.intersects(box, wall)) { crash(); return }
        for (b in barriers) if (RectF.intersects(box, b)) { crash(); return }

        if (checkParked()) {
            parked = true
            pointerRole.clear()
            speed = 0f
            val elapsed = ((SystemClock.elapsedRealtime() - levelStartMs) / 1000L).toInt()
            if (elapsed < bestTimeSec) bestTimeSec = elapsed
            val slowBonus = ((maxSpeed - abs(speed)) * 20).toInt().coerceAtLeast(10)
            val timeBonus = (45 - elapsed).coerceAtLeast(0) * 3
            stars = when {
                abs(speed) < 1.1f && elapsed <= 25 -> 3
                abs(speed) < 2.3f -> 2
                else -> 1
            }
            totalStars += stars
            score += 100 + slowBonus + timeBonus + level * 40
            message = "Park OK! ${stars}★  +$score pts  Time:${elapsed}s"
        }
    }

    private fun crash() {
        crashed = true
        pointerRole.clear()
        speed = 0f
        message = "Crash! Touch screen = retry same level"
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
        return dist < 50f && ad < 30f && abs(speed) < 3.5f
    }

    private fun norm(a: Float): Float {
        var x = a % 360f
        if (x < 0) x += 360f
        return x
    }

    private fun roleAt(x: Float, y: Float): Int = when {
        touchLeft.contains(x, y) -> ROLE_LEFT
        touchRight.contains(x, y) -> ROLE_RIGHT
        touchGas.contains(x, y) -> ROLE_GAS
        touchRev.contains(x, y) -> ROLE_REV
        else -> ROLE_NONE
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!ready) return
        if (state == MENU) drawMenu(canvas) else drawPlay(canvas)
    }

    private fun drawLogo(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        canvas.drawCircle(cx, cy, r, logoPaint)
        canvas.drawCircle(cx, cy, r * 0.92f, logoChrome)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = r * 0.9f
        canvas.drawText("J", cx, cy + r * 0.32f, textPaint)
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = 32f
    }

    private fun drawMenu(canvas: Canvas) {
        canvas.drawRect(0f, 0f, w, h, menuBg)
        drawLogo(canvas, w / 2f, h * 0.16f, min(w, h) * 0.08f)
        accent.textAlign = Paint.Align.CENTER
        accent.textSize = 40f
        canvas.drawText("Jishan Gaming", w / 2f, h * 0.28f, accent)
        smallText.textAlign = Paint.Align.CENTER
        canvas.drawText("1) Car choose  2) START  3) 2 ungli se control", w / 2f, h * 0.34f, smallText)
        canvas.drawText("Total ★ $totalStars    Score $score", w / 2f, h * 0.39f, smallText)

        for (i in cars.indices) {
            val r = carCards[i]
            canvas.drawRoundRect(r, 18f, 18f, cardBg)
            if (i == selectedCar) canvas.drawRoundRect(r, 18f, 18f, cardSel)
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cars[i].body }
            val cx = r.centerX(); val cy = r.centerY() - 12f
            canvas.drawRoundRect(cx - 24f, cy - 42f, cx + 24f, cy + 42f, 12f, 12f, p)
            smallText.textAlign = Paint.Align.CENTER
            canvas.drawText(cars[i].name, cx, r.bottom - 12f, smallText)
        }

        canvas.drawRoundRect(btnStart, 22f, 22f, startPaint)
        canvas.drawRoundRect(btnStart, 22f, 22f, ringPaint)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = 34f
        canvas.drawText("START", btnStart.centerX(), btnStart.centerY() + 12f, textPaint)
        textPaint.textAlign = Paint.Align.LEFT
        smallText.textAlign = Paint.Align.LEFT
        accent.textAlign = Paint.Align.LEFT
    }

    private fun drawPlay(canvas: Canvas) {
        canvas.drawRect(0f, 0f, w, h, skyPaint)
        canvas.drawRect(0f, h * 0.1f, w, h, asphalt)
        canvas.drawRect(w * 0.18f, h * 0.18f, w * 0.88f, h * 0.88f, asphaltDark)
        for (i in 1..9) {
            val y = h * 0.1f + h * 0.9f * i / 10f
            canvas.drawLine(24f, y, w - 24f, y, linePaint)
        }
        for (wall in walls) {
            canvas.drawRoundRect(wall, 6f, 6f, wallPaint)
            canvas.drawRect(wall.left, wall.top - 10f, wall.right, wall.top, wallTop)
        }
        for (b in barriers) canvas.drawRoundRect(b, 5f, 5f, barrierPaint)

        canvas.save()
        canvas.translate(slotX, slotY)
        canvas.rotate(slotAngle)
        canvas.drawRoundRect(-slotW / 2, -slotH / 2, slotW / 2, slotH / 2, 12f, 12f, slotFill)
        canvas.drawRoundRect(-slotW / 2, -slotH / 2, slotW / 2, slotH / 2, 12f, 12f, slotStroke)
        smallText.textAlign = Paint.Align.CENTER
        smallText.color = Color.parseColor("#00b894")
        smallText.textSize = 28f
        canvas.drawText("P", 0f, 10f, smallText)
        smallText.color = Color.parseColor("#dfe6e9")
        smallText.textSize = 22f
        smallText.textAlign = Paint.Align.LEFT
        canvas.restore()

        drawCar(canvas)

        canvas.drawRect(0f, 0f, w, 70f, uiBg)
        canvas.drawText("Jishan Gaming", 14f, 28f, accent)
        canvas.drawText("Lv $level   Score $score   ★$totalStars", 14f, 56f, smallText)
        canvas.drawRoundRect(btnMenu, 14f, 14f, btnFace)
        smallText.textAlign = Paint.Align.CENTER
        canvas.drawText("MENU", btnMenu.centerX(), btnMenu.centerY() + 7f, smallText)
        smallText.textAlign = Paint.Align.LEFT

        canvas.drawRect(0f, 70f, w, 104f, uiBg)
        canvas.drawText(message, 14f, 93f, smallText)

        val st = steerValue()
        val th = throttleValue()
        drawRoundCtrl(canvas, btnLeft, "◀", st < 0, btnFace, btnOn)
        drawRoundCtrl(canvas, btnRight, "▶", st > 0, btnFace, btnOn)
        drawRoundCtrl(canvas, btnGas, "GAS", th > 0, gasFace, gasOn)
        drawRoundCtrl(canvas, btnRev, "REV", th < 0, revFace, revOn)

        // speed meter
        smallText.textAlign = Paint.Align.CENTER
        canvas.drawText("SPD ${"%.1f".format(abs(speed))}", w / 2f, h - 24f, smallText)
        smallText.textAlign = Paint.Align.LEFT

        if (parked) {
            canvas.drawRoundRect(btnNext, 20f, 20f, startPaint)
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText("NEXT LEVEL ▶", btnNext.centerX(), btnNext.centerY() + 12f, textPaint)
            textPaint.textAlign = Paint.Align.LEFT
        }
    }

    private fun drawRoundCtrl(
        canvas: Canvas, r: RectF, label: String, on: Boolean, off: Paint, active: Paint
    ) {
        canvas.drawRoundRect(r, r.height() / 2f, r.height() / 2f, if (on) active else off)
        if (on) canvas.drawRoundRect(r, r.height() / 2f, r.height() / 2f, ringPaint)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = 26f
        canvas.drawText(label, r.centerX(), r.centerY() + 9f, textPaint)
        textPaint.textSize = 32f
        textPaint.textAlign = Paint.Align.LEFT
    }

    private fun drawCar(canvas: Canvas) {
        canvas.save()
        canvas.translate(carX, carY)
        canvas.rotate(carAngle)
        val l = carLength / 2
        val ww = carWidth / 2
        canvas.drawRoundRect(-ww + 6, -l + 8, ww + 10, l + 12, 12f, 12f, shadowPaint)
        canvas.drawRoundRect(-ww, -l, ww, l, 14f, 14f, carBody)
        canvas.drawRoundRect(-ww * 0.72f, -l * 0.22f, ww * 0.72f, l * 0.32f, 8f, 8f, carDark)
        canvas.drawRoundRect(-ww * 0.62f, -l * 0.12f, ww * 0.62f, l * 0.22f, 6f, 6f, carGlass)
        canvas.drawCircle(-ww * 0.5f, -l + 11, 6f, carLight)
        canvas.drawCircle(ww * 0.5f, -l + 11, 6f, carLight)
        canvas.drawCircle(-ww * 0.48f, l - 9, 5f, rearPaint)
        canvas.drawCircle(ww * 0.48f, l - 9, 5f, rearPaint)
        canvas.restore()
    }

    override fun onTouch(v: View?, event: MotionEvent): Boolean {
        if (!ready) return true

        if (state == MENU) {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                val x = event.x; val y = event.y
                for (i in carCards.indices) if (carCards[i].contains(x, y)) {
                    selectedCar = i; invalidate(); return true
                }
                if (btnStart.contains(x, y)) {
                    applySkin()
                    level = 1; score = 0
                    setupLevel(1)
                    state = PLAY
                    message = "2 ungli: ek GAS, ek ◀▶"
                }
            }
            return true
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = event.actionIndex
                val id = event.getPointerId(i)
                val x = event.getX(i)
                val y = event.getY(i)

                if (btnMenu.contains(x, y)) {
                    state = MENU
                    pointerRole.clear()
                    return true
                }
                if (crashed) {
                    setupLevel(level)
                    return true
                }
                if (parked && btnNext.contains(x, y)) {
                    setupLevel(level + 1)
                    return true
                }

                val role = roleAt(x, y)
                if (role != ROLE_NONE) {
                    // Sticky: this finger keeps this role until UP
                    pointerRole[id] = role
                }
            }
            MotionEvent.ACTION_MOVE -> {
                // Do NOT reassign other fingers. Only if a pointer has no role and enters a pad, assign.
                // If pointer already has role, KEEP it (sticky) even if it slides a bit.
                for (i in 0 until event.pointerCount) {
                    val id = event.getPointerId(i)
                    if (pointerRole.containsKey(id)) continue
                    val role = roleAt(event.getX(i), event.getY(i))
                    if (role != ROLE_NONE) pointerRole[id] = role
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val id = event.getPointerId(event.actionIndex)
                pointerRole.remove(id)
            }
        }
        return true
    }
}
