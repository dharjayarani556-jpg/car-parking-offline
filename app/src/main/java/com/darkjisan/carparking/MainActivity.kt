package com.darkjisan.carparking

import android.app.Activity
import android.content.Context
import android.graphics.*
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.*
import android.widget.FrameLayout
import kotlin.math.*

/**
 * Offline Car Parking Game
 * - No online / multiplayer / IAP
 * - Free cars
 * - Steering + accel + reverse
 * - Hinglish UI labels
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

    // Screen
    private var w = 0f
    private var h = 0f

    // Car state
    private var carX = 200f
    private var carY = 400f
    private var carAngle = 0f          // degrees
    private var speed = 0f
    private val maxSpeed = 8f
    private val accel = 0.18f
    private val brake = 0.25f
    private val friction = 0.06f
    private val turnSpeed = 2.8f
    private val carLength = 90f
    private val carWidth = 48f

    // Controls
    private var throttle = 0          // -1 reverse, 0 idle, 1 forward
    private var steer = 0             // -1 left, 0 none, 1 right

    // Level
    private var level = 1
    private var score = 0
    private var parked = false
    private var crashed = false
    private var message = "Level 1 - Parking slot mein gadi lagao"
    private var stars = 0

    // Parking slot (target)
    private var slotX = 0f
    private var slotY = 0f
    private var slotW = 70f
    private var slotH = 120f
    private var slotAngle = 0f

    // Walls / obstacles (simple rects in world)
    private val walls = mutableListOf<RectF>()

    // Paint
    private val roadPaint = Paint().apply { color = Color.parseColor("#2C3E50"); style = Paint.Style.FILL }
    private val linePaint = Paint().apply { color = Color.parseColor("#F1C40F"); strokeWidth = 4f; style = Paint.Style.STROKE }
    private val slotPaint = Paint().apply { color = Color.parseColor("#27AE60"); style = Paint.Style.STROKE; strokeWidth = 6f }
    private val slotFill = Paint().apply { color = Color.parseColor("#1ABC9C"); alpha = 60; style = Paint.Style.FILL }
    private val carBody = Paint().apply { color = Color.parseColor("#E74C3C"); style = Paint.Style.FILL; isAntiAlias = true }
    private val carDetail = Paint().apply { color = Color.parseColor("#C0392B"); style = Paint.Style.FILL; isAntiAlias = true }
    private val wallPaint = Paint().apply { color = Color.parseColor("#7F8C8D"); style = Paint.Style.FILL }
    private val uiBg = Paint().apply { color = Color.parseColor("#000000"); alpha = 140 }
    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 36f
        isAntiAlias = true
        typeface = Typeface.DEFAULT_BOLD
    }
    private val smallText = Paint().apply {
        color = Color.parseColor("#ECF0F1")
        textSize = 26f
        isAntiAlias = true
    }
    private val btnPaint = Paint().apply { color = Color.parseColor("#34495E"); style = Paint.Style.FILL; isAntiAlias = true }
    private val btnActive = Paint().apply { color = Color.parseColor("#3498DB"); style = Paint.Style.FILL; isAntiAlias = true }

    // Buttons (screen space)
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
        layoutButtons()
        setupLevel(level)
    }

    private fun layoutButtons() {
        val s = min(w, h) * 0.14f
        val pad = 24f
        // Left side: steer
        btnLeft.set(pad, h - s * 2 - pad * 2, pad + s, h - s - pad * 2)
        btnRight.set(pad + s + 12, h - s * 2 - pad * 2, pad + s * 2 + 12, h - s - pad * 2)
        // Right side: gas / brake
        btnGas.set(w - pad - s, h - s * 2 - pad * 2, w - pad, h - s - pad * 2)
        btnBrake.set(w - pad - s, h - s - pad, w - pad, h - pad)
        btnNext.set(w / 2 - 100, h * 0.55f, w / 2 + 100, h * 0.55f + 70)
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
                carX = w * 0.2f
                carY = h * 0.5f
                carAngle = 0f
                slotX = w * 0.75f
                slotY = h * 0.5f
                slotW = 70f
                slotH = 130f
                slotAngle = 0f
                message = "Level 1 - Seedha parking slot mein lagao"
            }
            2 -> {
                carX = w * 0.15f
                carY = h * 0.7f
                carAngle = -30f
                slotX = w * 0.7f
                slotY = h * 0.35f
                slotW = 70f
                slotH = 130f
                slotAngle = 90f
                // walls
                walls.add(RectF(w * 0.4f, h * 0.1f, w * 0.45f, h * 0.9f))
                message = "Level 2 - Side parking + deewar se bacho"
            }
            3 -> {
                carX = w * 0.2f
                carY = h * 0.2f
                carAngle = 90f
                slotX = w * 0.65f
                slotY = h * 0.65f
                slotW = 75f
                slotH = 125f
                slotAngle = -45f
                walls.add(RectF(w * 0.35f, h * 0.35f, w * 0.55f, h * 0.45f))
                walls.add(RectF(w * 0.5f, h * 0.2f, w * 0.55f, h * 0.5f))
                message = "Level 3 - Angle parking - dheere chalao"
            }
            else -> {
                carX = w * 0.2f
                carY = h * 0.5f
                carAngle = 0f
                slotX = w * 0.7f + (lv % 3) * 20
                slotY = h * 0.4f
                slotW = 70f
                slotH = 130f
                slotAngle = (lv * 15f) % 90f
                walls.add(RectF(w * 0.4f, h * 0.15f, w * 0.48f, h * 0.85f))
                message = "Level $lv - Parking complete karo"
            }
        }
    }

    private fun update() {
        if (parked || crashed) return

        // Speed
        when (throttle) {
            1 -> speed = (speed + accel).coerceAtMost(maxSpeed)
            -1 -> speed = (speed - accel).coerceAtLeast(-maxSpeed * 0.6f)
            else -> {
                if (speed > 0) speed = (speed - friction).coerceAtLeast(0f)
                else if (speed < 0) speed = (speed + friction).coerceAtMost(0f)
            }
        }

        // Steering only when moving
        if (abs(speed) > 0.15f) {
            val dir = if (speed >= 0) 1f else -1f
            carAngle += steer * turnSpeed * dir
        }

        val rad = Math.toRadians(carAngle.toDouble())
        carX += (sin(rad) * speed).toFloat()
        carY -= (cos(rad) * speed).toFloat()

        // Keep roughly on screen
        carX = carX.coerceIn(30f, w - 30f)
        carY = carY.coerceIn(30f, h - 30f)

        // Collision with walls
        val carBox = carRect()
        for (wall in walls) {
            if (RectF.intersects(carBox, wall)) {
                crashed = true
                message = "Crash! Dobara try karo - screen touch karo"
                speed = 0f
                return
            }
        }

        // Parking check
        if (checkParked()) {
            parked = true
            val bonus = (maxSpeed - abs(speed)).toInt() * 10
            score += 100 + bonus + level * 50
            stars = when {
                abs(speed) < 1.5f -> 3
                abs(speed) < 3f -> 2
                else -> 1
            }
            message = "Parked! $stars star  |  Score: $score  |  Next level touch karo"
            speed = 0f
        }
    }

    private fun carRect(): RectF {
        // Approximate AABB for collision
        val hw = carWidth / 2
        val hl = carLength / 2
        return RectF(carX - hw, carY - hl, carX + hw, carY + hl)
    }

    private fun checkParked(): Boolean {
        // Car center near slot center and angle roughly aligned, low speed
        val dx = carX - slotX
        val dy = carY - slotY
        val dist = sqrt(dx * dx + dy * dy)
        var angleDiff = abs(normalizeAngle(carAngle - slotAngle))
        if (angleDiff > 180) angleDiff = 360 - angleDiff
        return dist < 35f && angleDiff < 25f && abs(speed) < 2.5f
    }

    private fun normalizeAngle(a: Float): Float {
        var x = a % 360f
        if (x < 0) x += 360f
        return x
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (w <= 0) return

        // Road background
        canvas.drawRect(0f, 0f, w, h, roadPaint)

        // Lane lines
        linePaint.alpha = 80
        for (i in 1..6) {
            val y = h * i / 7f
            canvas.drawLine(0f, y, w, y, linePaint)
        }
        linePaint.alpha = 255

        // Walls
        for (wall in walls) {
            canvas.drawRoundRect(wall, 8f, 8f, wallPaint)
        }

        // Parking slot
        canvas.save()
        canvas.translate(slotX, slotY)
        canvas.rotate(slotAngle)
        canvas.drawRect(-slotW / 2, -slotH / 2, slotW / 2, slotH / 2, slotFill)
        canvas.drawRect(-slotW / 2, -slotH / 2, slotW / 2, slotH / 2, slotPaint)
        canvas.restore()

        // Car
        drawCar(canvas)

        // HUD
        canvas.drawRect(0f, 0f, w, 70f, uiBg)
        canvas.drawText("Level $level", 24f, 45f, textPaint)
        canvas.drawText("Score: $score", w * 0.35f, 45f, textPaint)
        canvas.drawText(if (parked) "★".repeat(stars) else "", w * 0.7f, 45f, textPaint)

        // Message
        canvas.drawRect(0f, 70f, w, 115f, uiBg)
        canvas.drawText(message, 24f, 100f, smallText)

        // Controls
        drawButton(canvas, btnLeft, "◀", steer < 0)
        drawButton(canvas, btnRight, "▶", steer > 0)
        drawButton(canvas, btnGas, "GAS", throttle > 0)
        drawButton(canvas, btnBrake, "REV", throttle < 0)

        if (parked) {
            canvas.drawRoundRect(btnNext, 16f, 16f, btnActive)
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText("Next Level", btnNext.centerX(), btnNext.centerY() + 12f, textPaint)
            textPaint.textAlign = Paint.Align.LEFT
        }
    }

    private fun drawButton(canvas: Canvas, r: RectF, label: String, active: Boolean) {
        canvas.drawRoundRect(r, 18f, 18f, if (active) btnActive else btnPaint)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = 28f
        canvas.drawText(label, r.centerX(), r.centerY() + 10f, textPaint)
        textPaint.textSize = 36f
        textPaint.textAlign = Paint.Align.LEFT
    }

    private fun drawCar(canvas: Canvas) {
        canvas.save()
        canvas.translate(carX, carY)
        canvas.rotate(carAngle)
        // body
        val l = carLength / 2
        val ww = carWidth / 2
        canvas.drawRoundRect(-ww, -l, ww, l, 10f, 10f, carBody)
        // cabin
        canvas.drawRoundRect(-ww * 0.7f, -l * 0.3f, ww * 0.7f, l * 0.4f, 6f, 6f, carDetail)
        // headlights
        val light = Paint().apply { color = Color.parseColor("#F1C40F") }
        canvas.drawCircle(-ww * 0.5f, -l + 8, 5f, light)
        canvas.drawCircle(ww * 0.5f, -l + 8, 5f, light)
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
                    else -> 0
                }
                throttle = when {
                    btnGas.contains(x, y) -> 1
                    btnBrake.contains(x, y) -> -1
                    else -> 0
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                // Clear if finger up - simple: clear all
                if (event.pointerCount <= 1) {
                    steer = 0
                    throttle = 0
                }
            }
        }
        return true
    }
}
