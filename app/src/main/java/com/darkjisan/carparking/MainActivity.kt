package com.darkjisan.carparking

import android.app.Activity
import android.content.Context
import android.graphics.*
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.*
import kotlin.math.*

/**
 * Jishan Gaming - Offline Car Parking
 * Steering, GAS, REV, stars, no online/IAP
 */
class MainActivity : Activity() {

    private lateinit var gameView: GameView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        gameView = GameView(this)
        setContentView(gameView)
    }

    override fun onPause() {
        super.onPause()
        gameView.pauseGame()
    }

    override fun onResume() {
        super.onResume()
        gameView.resumeGame()
    }
}

class GameView(context: Context) : View(context), View.OnTouchListener {

    private var w = 0f
    private var h = 0f

    private var carX = 200f
    private var carY = 400f
    private var carAngle = 0f
    private var speed = 0f
    private val maxSpeed = 7.5f
    private val accel = 0.16f
    private val friction = 0.07f
    private val turnSpeed = 2.6f
    private val carLength = 100f
    private val carWidth = 52f

    private var throttle = 0
    private var steer = 0

    private var level = 1
    private var score = 0
    private var parked = false
    private var crashed = false
    private var message = "Jishan Gaming - Level 1: Slot mein park karo"
    private var stars = 0

    private var slotX = 0f
    private var slotY = 0f
    private var slotW = 72f
    private var slotH = 128f
    private var slotAngle = 0f

    private val walls = mutableListOf<RectF>()

    private val skyPaint = Paint().apply {
        shader = LinearGradient(0f, 0f, 0f, 400f,
            Color.parseColor("#1a1a2e"), Color.parseColor("#16213e"), Shader.TileMode.CLAMP)
    }
    private val asphalt = Paint().apply { color = Color.parseColor("#2d3436"); style = Paint.Style.FILL }
    private val asphaltLine = Paint().apply {
        color = Color.parseColor("# recap")
        color = Color.parseColor("#fdcb6e")
        strokeWidth = 3f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(30f, 20f), 0f)
    }
    private val slotStroke = Paint().apply {
        color = Color.parseColor("#00b894")
        style = Paint.Style.STROKE
        strokeWidth = 7f
        isAntiAlias = true
    }
    private val slotFill = Paint().apply {
        color = Color.parseColor("#00b894")
        alpha = 50
        style = Paint.Style.FILL
    }
    private val wallPaint = Paint().apply {
        color = Color.parseColor("#636e72")
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val wallTop = Paint().apply {
        color = Color.parseColor("#b2bec3")
        style = Paint.Style.FILL
    }
    private val carRed = Paint().apply {
        color = Color.parseColor("#d63031")
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val carDark = Paint().apply {
        color = Color.parseColor("#2d3436")
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val carGlass = Paint().apply {
        color = Color.parseColor("#74b9ff")
        alpha = 200
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val carLight = Paint().apply {
        color = Color.parseColor("#ffeaa7")
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val shadowPaint = Paint().apply {
        color = Color.BLACK
        alpha = 70
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val uiBg = Paint().apply { color = Color.parseColor("#0a0a12"); alpha = 200 }
    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 34f
        isAntiAlias = true
        typeface = Typeface.DEFAULT_BOLD
    }
    private val smallText = Paint().apply {
        color = Color.parseColor("#dfe6e9")
        textSize = 24f
        isAntiAlias = true
    }
    private val accentText = Paint().apply {
        color = Color.parseColor("#a29bfe")
        textSize = 28f
        isAntiAlias = true
        typeface = Typeface.DEFAULT_BOLD
    }
    private val btnPaint = Paint().apply {
        color = Color.parseColor("#2d3436")
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val btnActive = Paint().apply {
        color = Color.parseColor("#6c5ce7")
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val starPaint = Paint().apply {
        color = Color.parseColor("#fdcb6e")
        textSize = 40f
        isAntiAlias = true
    }

    private val btnLeft = RectF()
    private val btnRight = RectF()
    private val btnGas = RectF()
    private val btnBrake = RectF()
    private val btnNext = RectF()

    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private val tickMs = 16L

    private val loop = object : Runnable {
        override fun run() {
            if (!running) return
            update()
            invalidate()
            handler.postDelayed(this, tickMs)
        }
    }

    init {
        setOnTouchListener(this)
        setupLevel(1)
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
        w = width.toFloat()
        h = height.toFloat()
        skyPaint.shader = LinearGradient(
            0f, 0f, 0f, h * 0.35f,
            Color.parseColor("#0f0c29"),
            Color.parseColor("#302b63"),
            Shader.TileMode.CLAMP
        )
        layoutButtons()
        setupLevel(level)
    }

    private fun layoutButtons() {
        val s = min(w, h) * 0.13f
        val pad = 20f
        btnLeft.set(pad, h - s * 2.1f - pad, pad + s, h - s - pad * 1.1f)
        btnRight.set(pad + s + 14, h - s * 2.1f - pad, pad + s * 2 + 14, h - s - pad * 1.1f)
        btnGas.set(w - pad - s, h - s * 2.1f - pad, w - pad, h - s - pad * 1.1f)
        btnBrake.set(w - pad - s, h - s - pad, w - pad, h - pad)
        btnNext.set(w / 2 - 110, h * 0.52f, w / 2 + 110, h * 0.52f + 64)
    }

    private fun setupLevel(lv: Int) {
        level = lv
        parked = false
        crashed = false
        speed = 0f
        stars = 0
        walls.clear()

        when (lv) {
            1 -> {
                carX = w * 0.18f; carY = h * 0.55f; carAngle = 0f
                slotX = w * 0.72f; slotY = h * 0.55f; slotAngle = 0f
                message = "Level 1 - Seedha slot mein park karo (dheere)"
            }
            2 -> {
                carX = w * 0.15f; carY = h * 0.72f; carAngle = -20f
                slotX = w * 0.68f; slotY = h * 0.38f; slotAngle = 90f
                walls.add(RectF(w * 0.38f, h * 0.12f, w * 0.44f, h * 0.88f))
                message = "Level 2 - Side parking + deewar se door raho"
            }
            3 -> {
                carX = w * 0.2f; carY = h * 0.22f; carAngle = 90f
                slotX = w * 0.62f; slotY = h * 0.62f; slotAngle = -40f
                walls.add(RectF(w * 0.36f, h * 0.36f, w * 0.52f, h * 0.44f))
                walls.add(RectF(w * 0.48f, h * 0.22f, w * 0.54f, h * 0.5f))
                message = "Level 3 - Angle park - control tight rakho"
            }
            else -> {
                carX = w * 0.18f; carY = h * 0.5f; carAngle = 0f
                slotX = w * 0.68f; slotY = h * 0.45f
                slotAngle = ((lv * 20) % 80).toFloat()
                walls.add(RectF(w * 0.4f, h * 0.15f, w * 0.47f, h * 0.85f))
                message = "Level $lv - Challenge mode - stars ke liye slow park"
            }
        }
    }

    private fun update() {
        if (parked || crashed) return

        when (throttle) {
            1 -> speed = (speed + accel).coerceAtMost(maxSpeed)
            -1 -> speed = (speed - accel).coerceAtLeast(-maxSpeed * 0.55f)
            else -> {
                if (speed > 0) speed = (speed - friction).coerceAtLeast(0f)
                else if (speed < 0) speed = (speed + friction).coerceAtMost(0f)
            }
        }

        if (abs(speed) > 0.12f) {
            val dir = if (speed >= 0) 1f else -1f
            carAngle += steer * turnSpeed * dir
        }

        val rad = Math.toRadians(carAngle.toDouble())
        carX += (sin(rad) * speed).toFloat()
        carY -= (cos(rad) * speed).toFloat()
        carX = carX.coerceIn(40f, w - 40f)
        carY = carY.coerceIn(40f, h - 40f)

        val box = carRect()
        for (wall in walls) {
            if (RectF.intersects(box, wall)) {
                crashed = true
                message = "Crash! Screen touch karke dobara try karo"
                speed = 0f
                return
            }
        }

        if (checkParked()) {
            parked = true
            val slowBonus = ((maxSpeed - abs(speed)) * 12).toInt()
            score += 100 + slowBonus + level * 40
            stars = when {
                abs(speed) < 1.2f -> 3
                abs(speed) < 2.5f -> 2
                else -> 1
            }
            message = "Park ho gaya! ${"★".repeat(stars)}  Score: $score  → Next touch karo"
            speed = 0f
        }
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
        if (ad > 180) ad = 360 - ad
        return dist < 38f && ad < 28f && abs(speed) < 2.8f
    }

    private fun normalizeAngle(a: Float): Float {
        var x = a % 360f
        if (x < 0) x += 360f
        return x
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (w <= 0) return

        // Background
        canvas.drawRect(0f, 0f, w, h, skyPaint)
        canvas.drawRect(0f, h * 0.12f, w, h, asphalt)

        // Parking lot lines
        for (i in 1..8) {
            val y = h * 0.12f + (h * 0.88f) * i / 9f
            canvas.drawLine(40f, y, w - 40f, y, asphaltLine)
        }

        // Walls with simple 3D top edge
        for (wall in walls) {
            canvas.drawRoundRect(wall, 6f, 6f, wallPaint)
            canvas.drawRect(wall.left, wall.top - 8f, wall.right, wall.top, wallTop)
        }

        // Slot
        canvas.save()
        canvas.translate(slotX, slotY)
        canvas.rotate(slotAngle)
        canvas.drawRoundRect(-slotW / 2, -slotH / 2, slotW / 2, slotH / 2, 8f, 8f, slotFill)
        canvas.drawRoundRect(-slotW / 2, -slotH / 2, slotW / 2, slotH / 2, 8f, 8f, slotStroke)
        canvas.restore()

        drawCar(canvas)

        // Top HUD
        canvas.drawRect(0f, 0f, w, 78f, uiBg)
        canvas.drawText("Jishan Gaming", 20f, 32f, accentText)
        canvas.drawText("Level $level", 20f, 62f, textPaint)
        canvas.drawText("Score $score", w * 0.38f, 50f, textPaint)
        if (stars > 0) {
            canvas.drawText("★".repeat(stars), w * 0.72f, 55f, starPaint)
        }

        canvas.drawRect(0f, 78f, w, 118f, uiBg)
        canvas.drawText(message, 20f, 105f, smallText)

        drawBtn(canvas, btnLeft, "◀", steer < 0)
        drawBtn(canvas, btnRight, "▶", steer > 0)
        drawBtn(canvas, btnGas, "GAS", throttle > 0)
        drawBtn(canvas, btnBrake, "REV", throttle < 0)

        if (parked) {
            canvas.drawRoundRect(btnNext, 18f, 18f, btnActive)
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText("Next Level ▶", btnNext.centerX(), btnNext.centerY() + 12f, textPaint)
            textPaint.textAlign = Paint.Align.LEFT
        }
    }

    private fun drawBtn(canvas: Canvas, r: RectF, label: String, on: Boolean) {
        canvas.drawRoundRect(r, 20f, 20f, if (on) btnActive else btnPaint)
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

        // shadow (pseudo-3D)
        canvas.drawRoundRect(-ww + 6, -l + 8, ww + 10, l + 12, 12f, 12f, shadowPaint)

        // body
        canvas.drawRoundRect(-ww, -l, ww, l, 12f, 12f, carRed)
        // roof / cabin height illusion
        canvas.drawRoundRect(-ww * 0.75f, -l * 0.25f, ww * 0.75f, l * 0.35f, 8f, 8f, carDark)
        canvas.drawRoundRect(-ww * 0.65f, -l * 0.15f, ww * 0.65f, l * 0.25f, 6f, 6f, carGlass)

        // lights
        canvas.drawCircle(-ww * 0.55f, -l + 10, 6f, carLight)
        canvas.drawCircle(ww * 0.55f, -l + 10, 6f, carLight)

        // rear lights
        val rear = Paint().apply { color = Color.parseColor("#ff7675"); isAntiAlias = true }
        canvas.drawCircle(-ww * 0.5f, l - 8, 5f, rear)
        canvas.drawCircle(ww * 0.5f, l - 8, 5f, rear)

        canvas.restore()
    }

    override fun onTouch(v: View?, event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_POINTER_DOWN -> {
                if (crashed) {
                    setupLevel(level)
                    return true
                }
                if (parked && btnNext.contains(x, y)) {
                    setupLevel(level + 1)
                    return true
                }
                steer = when {
                    btnLeft.contains(x, y) -> -1
                    btnRight.contains(x, y) -> 1
                    else -> steer
                }
                throttle = when {
                    btnGas.contains(x, y) -> 1
                    btnBrake.contains(x, y) -> -1
                    else -> throttle
                }
                // If touch not on any btn, don't force zero mid-drag of other finger - simplify:
                if (!btnLeft.contains(x, y) && !btnRight.contains(x, y) &&
                    !btnGas.contains(x, y) && !btnBrake.contains(x, y) && !parked
                ) {
                    // ignore
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                if (event.pointerCount <= 1) {
                    steer = 0
                    throttle = 0
                }
            }
        }
        return true
    }
}
