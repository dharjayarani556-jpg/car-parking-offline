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
    private var g: GameView? = null
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        try {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_FULLSCREEN)
            g = GameView(this); setContentView(g)
        } catch (t: Throwable) {
            val tv = android.widget.TextView(this); tv.text = t.message; tv.setTextColor(Color.WHITE); tv.setBackgroundColor(Color.BLACK); setContentView(tv)
        }
    }
    override fun onPause() { super.onPause(); g?.pause() }
    override fun onResume() { super.onResume(); g?.resume() }
    override fun onDestroy() { super.onDestroy(); g?.release() }
}

data class Skin(val n: String, val c: Int)
data class NPC(var x: Float, var y: Float, var a: Float, var c: Int)

class GameView(ctx: Context) : View(ctx), View.OnTouchListener, SensorEventListener {
    companion object {
        const val MENU=0; const val LEVELS=1; const val PLAY=2; const val MAX=100
        const val L=1; const val R=2; const val GAS=3; const val REV=4; const val BRK=5
    }
    private val pref = ctx.getSharedPreferences("rpp", Context.MODE_PRIVATE)
    private var state = MENU; private var dots = false
    private var W=0f; private var H=0f; private var ready=false
    private val skins = listOf(
        Skin("Red", Color.parseColor("#e74c3c")), Skin("Blue", Color.parseColor("#3498db")),
        Skin("Green", Color.parseColor("#1abc9c")), Skin("Gold", Color.parseColor("#f1c40f")),
        Skin("Purple", Color.parseColor("#9b59b6")), Skin("Orange", Color.parseColor("#e67e22"))
    )
    private var carI=0; private var lockCar=true
    private var score=0; private var starsT=0; private var crashes=0; private var wins=0; private var maxLv=1
    private var sens=0; private var gyro=false; private var sound=true
    private var cx=200f; private var cy=400f; private var ang=0f; private var spd=0f
    private val maxSpd=6.2f; private val acc=0.18f; private val fric=0.1f
    private val roles = HashMap<Int,Int>(); private var gSteer=0f
    private var level=1; private var parked=false; private var crashed=false; private var msg=""
    private var stars=0; private var t0=0L; private var blast=0; private var bx=0f; private var by=0f
    private var sx=0f; private var sy=0f; private var sw=94f; private var sh=156f; private var sa=0f
    private val walls=mutableListOf<RectF>(); private val bars=mutableListOf<RectF>(); private val npcs=mutableListOf<NPC>()
    private var page=0
    private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private var tone: ToneGenerator? = try { ToneGenerator(AudioManager.STREAM_MUSIC, 55) } catch (_:Throwable){null}

    private val bL=RectF(); private val bR=RectF(); private val bG=RectF(); private val bB=RectF(); private val bV=RectF()
    private val tL=RectF(); private val tR=RectF(); private val tG=RectF(); private val tB=RectF(); private val tV=RectF()
    private val bNext=RectF(); private val bStart=RectF(); private val bDots=RectF(); private val panel=RectF()
    private val bLevels=RectF(); private val bHome=RectF(); private val bGyro=RectF(); private val bSens=RectF()
    private val bSound=RectF(); private val bLock=RectF(); private val cards=Array(6){RectF()}
    private val cells=Array(20){RectF()}; private val bPrev=RectF(); private val bNxt=RectF(); private val bBack=RectF()

    private val sky=Paint(Paint.ANTI_ALIAS_FLAG)
    private val road=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.parseColor("#3d4a4f")}
    private val lot=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.parseColor("#2c363b")}
    private val lane=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.parseColor("#f1c40f");strokeWidth=4f;style=Paint.Style.STROKE;pathEffect=DashPathEffect(floatArrayOf(26f,14f),0f)}
    private val edge=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.parseColor("#bdc3c7");style=Paint.Style.STROKE;strokeWidth=3f}
    private val slotS=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.parseColor("#2ecc71");style=Paint.Style.STROKE;strokeWidth=9f}
    private val slotF=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.parseColor("#2ecc71");alpha=70}
    private val wallP=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.parseColor("#7f8c8d")}
    private val wallT=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.parseColor("#ecf0f1")}
    private val barP=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.parseColor("#e67e22")}
    private val body=Paint(Paint.ANTI_ALIAS_FLAG); private val dark=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.parseColor("#2c3e50")}
    private val glass=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.parseColor("#74b9ff");alpha=200}
    private val light=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.parseColor("#ffeaa7")}
    private val shad=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.BLACK;alpha=80}
    private val rear=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.parseColor("#ff7675")}
    private val ui=Paint().apply{color=Color.parseColor("#0a0a12");alpha=210}
    private val menuBg=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.parseColor("#0f0c29")}
    private val cardBg=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.parseColor("#1a1a2e")}
    private val cardSel=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.parseColor("#a29bfe");style=Paint.Style.STROKE;strokeWidth=5f}
    private val panBg=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.parseColor("#1e1e30");alpha=245}
    private val tp=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.WHITE;textSize=26f;typeface=Typeface.DEFAULT_BOLD}
    private val sp=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.parseColor("#ecf0f1");textSize=18f}
    private val ac=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.parseColor("#a29bfe");textSize=28f;typeface=Typeface.DEFAULT_BOLD}
    private val face=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.parseColor("#2d3436")}
    private val on=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.parseColor("#6c5ce7")}
    private val gasF=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.parseColor("#00b894")}
    private val gasO=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.parseColor("#55efc4")}
    private val revF=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.parseColor("#d63031")}
    private val revO=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.parseColor("#ff7675")}
    private val brkF=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.parseColor("#f39c12")}
    private val brkO=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.parseColor("#f1c40f")}
    private val go=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.parseColor("#00b894")}
    private val lockP=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.parseColor("#636e72")}
    private val openP=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.parseColor("#6c5ce7")}
    private val homeP=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.parseColor("#e17055")}
    private val blastP=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.parseColor("#fdcb6e");textSize=56f;textAlign=Paint.Align.CENTER}

    private val hnd=Handler(Looper.getMainLooper()); private var run=false
    private val loop=object:Runnable{ override fun run(){ if(!run)return; if(ready&&state==PLAY) tick(); if(blast>0)blast--; invalidate(); hnd.postDelayed(this,16) } }

    init { setOnTouchListener(this); setBackgroundColor(Color.parseColor("#0f0c29")); load() }
    private fun load(){
        score=pref.getInt("s",0); starsT=pref.getInt("st",0); crashes=pref.getInt("c",0); wins=pref.getInt("w",0)
        maxLv=pref.getInt("m",1).coerceIn(1,MAX); sens=pref.getInt("sens",0); gyro=pref.getBoolean("g",false)
        sound=pref.getBoolean("snd",true); lockCar=pref.getBoolean("lc",true); carI=pref.getInt("ci",0).coerceIn(0,skins.lastIndex)
    }
    private fun save(){ pref.edit().putInt("s",score).putInt("st",starsT).putInt("c",crashes).putInt("w",wins)
        .putInt("m",maxLv).putInt("sens",sens).putBoolean("g",gyro).putBoolean("snd",sound).putBoolean("lc",lockCar).putInt("ci",carI).apply() }
    fun resume(){ if(!run){run=true;hnd.post(loop)}; gyroReg() }
    fun pause(){ run=false; hnd.removeCallbacks(loop); sm.unregisterListener(this); save() }
    fun release(){ tone?.release(); tone=null; sm.unregisterListener(this) }
    private fun gyroReg(){ sm.unregisterListener(this); if(gyro&&sensor!=null&&state==PLAY) sm.registerListener(this,sensor,SensorManager.SENSOR_DELAY_GAME) }
    override fun onSensorChanged(e: SensorEvent?){ if(!gyro||e==null||state!=PLAY)return; gSteer=(-e.values[1]*0.25f).coerceIn(-1f,1f) }
    override fun onAccuracyChanged(s: Sensor?, a: Int){}
    private fun beep(t:Int){ if(!sound)return; try{tone?.startTone(t,100)}catch(_:Throwable){} }

    override fun onSizeChanged(w0:Int,h0:Int,a:Int,b:Int){
        if(w0<=0||h0<=0)return; W=w0.toFloat(); H=h0.toFloat()
        sky.shader=LinearGradient(0f,0f,0f,H*0.35f,Color.parseColor("#1a0a3e"),Color.parseColor("#4a3f8c"),Shader.TileMode.CLAMP)
        lay(); ready=true
    }
    private fun lay(){
        val s=min(W,H)*0.12f; val p=12f
        bL.set(p, H-s*2.05f-p, p+s, H-s-p*0.9f); bR.set(p+s+10f, H-s*2.05f-p, p+s*2+10f, H-s-p*0.9f)
        bG.set(W-p-s, H-s*3.15f-p, W-p, H-s*2.1f-p); bB.set(W-p-s, H-s*2.05f-p, W-p, H-s-p*0.9f); bV.set(W-p-s, H-s-p+2f, W-p, H-p)
        val g=18f
        tL.set(bL.left-g,bL.top-g,bL.right+g,bL.bottom+g); tR.set(bR.left-g,bR.top-g,bR.right+g,bR.bottom+g)
        tG.set(bG.left-g,bG.top-g,bG.right+g,bG.bottom+g); tB.set(bB.left-g,bB.top-g,bB.right+g,bB.bottom+g); tV.set(bV.left-g,bV.top-g,bV.right+g,bV.bottom+g)
        bNext.set(W/2-130f, H*0.42f, W/2+130f, H*0.42f+68f); bHome.set(12f,8f,120f,48f); bDots.set(W-70f,8f,W-12f,48f); panel.set(W-300f,52f,W-12f,320f)
        bStart.set(W/2-140f, H*0.80f, W/2+140f, H*0.80f+64f); bLevels.set(W/2-140f, H*0.70f, W/2+140f, H*0.70f+52f)
        val cw=min(W*0.13f,110f); val ch=cw*1.15f; val tot=cw*6+50f; var x=(W-tot)/2f; val y=H*0.32f
        for(i in 0 until 6){ cards[i].set(x,y,x+cw,y+ch); x+=cw+10f }
        val cellW=(W-80f)/5f; val cellH=(H-140f)/4f; var idx=0
        for(r in 0 until 4) for(c in 0 until 5){ val lx=40f+c*cellW+6f; val ly=70f+r*cellH+6f; cells[idx].set(lx,ly,lx+cellW-12f,ly+cellH-12f); idx++ }
        bPrev.set(40f,H-56f,160f,H-16f); bNxt.set(W-160f,H-56f,W-40f,H-16f); bBack.set(W/2-70f,H-56f,W/2+70f,H-16f)
        bGyro.set(W*0.04f,H*0.52f,W*0.24f,H*0.58f); bSens.set(W*0.26f,H*0.52f,W*0.50f,H*0.58f)
        bSound.set(W*0.52f,H*0.52f,W*0.72f,H*0.58f); bLock.set(W*0.74f,H*0.52f,W*0.96f,H*0.58f)
    }
    private fun turn()=when(sens){0->1.45f;2->2.7f;else->2.0f}
    private fun skin(){ body.color=skins[carI].c }

    private fun setup(lv:Int){
        if(W<10f)return; level=lv.coerceIn(1,MAX); parked=false; crashed=false; spd=0f; stars=0
        walls.clear(); bars.clear(); npcs.clear(); roles.clear(); gSteer=0f; blast=0; t0=SystemClock.elapsedRealtime()
        if(!lockCar) carI=(level-1)%skins.size; skin()
        val slots=listOf(
            Triple(0.80f,0.55f,0f), Triple(0.76f,0.28f,90f), Triple(0.72f,0.70f,-20f),
            Triple(0.82f,0.48f,0f), Triple(0.22f,0.55f,180f), Triple(0.70f,0.26f,90f),
            Triple(0.78f,0.62f,12f), Triple(0.68f,0.72f,40f), Triple(0.80f,0.34f,90f), Triple(0.74f,0.52f,-35f)
        )
        val sp=slots[(level-1)%slots.size]; sx=W*sp.first; sy=H*sp.second; sa=sp.third
        cx=W*0.16f; cy=H*0.55f; ang=0f
        walls.add(RectF(0f,H*0.1f,16f,H)); walls.add(RectF(W-16f,H*0.1f,W,H))
        when((level-1)%10){
            1->{ cy=H*0.78f; walls.add(RectF(W*0.40f,H*0.18f,W*0.46f,H*0.88f)) }
            2->{ cx=W*0.18f; cy=H*0.22f; ang=90f; walls.add(RectF(W*0.40f,H*0.32f,W*0.56f,H*0.40f)) }
            3->{ walls.add(RectF(W*0.32f,H*0.18f,W*0.38f,H*0.42f)); walls.add(RectF(W*0.32f,H*0.58f,W*0.38f,H*0.90f)) }
            4->{ cx=W*0.82f; cy=H*0.55f; ang=180f }
            5->{ cy=H*0.80f; walls.add(RectF(W*0.28f,H*0.42f,W*0.68f,H*0.50f)); walls.add(RectF(W*0.48f,H*0.18f,W*0.54f,H*0.50f)) }
            6->{ walls.add(RectF(W*0.42f,H*0.22f,W*0.50f,H*0.48f)); walls.add(RectF(W*0.42f,H*0.62f,W*0.50f,H*0.88f)); bars.add(RectF(W*0.56f,H*0.48f,W*0.64f,H*0.58f)) }
            7->{ cx=W*0.15f; cy=H*0.25f; ang=40f; walls.add(RectF(W*0.36f,H*0.42f,W*0.54f,H*0.50f)) }
            8->{ bars.add(RectF(W*0.30f,H*0.35f,W*0.38f,H*0.50f)); bars.add(RectF(W*0.50f,H*0.55f,W*0.58f,H*0.70f)); walls.add(RectF(W*0.42f,H*0.18f,W*0.48f,H*0.88f)) }
            9->{ cy=H*0.72f; ang=-10f; walls.add(RectF(W*0.36f,H*0.20f,W*0.43f,H*0.85f)); bars.add(RectF(W*0.55f,H*0.48f,W*0.65f,H*0.58f)) }
        }
        val cols=intArrayOf(Color.parseColor("#0984e3"),Color.parseColor("#00b894"),Color.parseColor("#fdcb6e"))
        for(i in 0 until min(1+(level-1)/15,3)){
            val px=W*(0.30f+i*0.12f); val py=if(i%2==0) H*0.20f else H*0.85f
            if(abs(px-sx)>90f) npcs.add(NPC(px,py,if(i%2==0)90f else 0f, cols[i%3]))
        }
        msg="Lv $level | HOME=menu | BRAKE=stop"; gyroReg()
    }

    private fun steer():Int{ var s=0; for(r in roles.values){ if(r==L)s=-1; if(r==R)s=1 }; return s }
    private fun thr():Int{ var t=0; var br=false; for(r in roles.values){ if(r==GAS)t=1; if(r==REV)t=-1; if(r==BRK)br=true }; return if(br)0 else t }
    private fun braking()=roles.values.any{it==BRK}

    private fun tick(){
        if(parked||crashed)return
        val th=thr(); val st=steer()
        if(braking()){ if(spd>0) spd=(spd-0.35f).coerceAtLeast(0f) else if(spd<0) spd=(spd+0.35f).coerceAtMost(0f) }
        else when(th){ 1->spd=(spd+acc).coerceAtMost(maxSpd); -1->spd=(spd-acc).coerceAtLeast(-maxSpd*0.55f)
            else->{ if(spd>0)spd=(spd-fric).coerceAtLeast(0f) else if(spd<0)spd=(spd+fric).coerceAtMost(0f) } }
        var sa=st.toFloat(); if(gyro) sa+=gSteer; sa=sa.coerceIn(-1.2f,1.2f)
        if(abs(sa)>0.05f && (abs(spd)>0.08f||th!=0)){
            val d=when{ spd>0.05f->1f; spd<-0.05f->-1f; th<0->-1f; else->1f }; ang+=sa*turn()*d
        }
        val rad=Math.toRadians(ang.toDouble()); cx+=(sin(rad)*spd).toFloat(); cy-=(cos(rad)*spd).toFloat()
        cx=cx.coerceIn(26f,W-26f); cy=cy.coerceIn(26f,H-26f)
        val box=RectF(cx-26f,cy-50f,cx+26f,cy+50f)
        for(w in walls) if(RectF.intersects(box,w)){ crash(); return }
        for(b in bars) if(RectF.intersects(box,b)){ crash(); return }
        for(n in npcs) if(RectF.intersects(box,RectF(n.x-26f,n.y-48f,n.x+26f,n.y+48f))){ crash(); return }
        val dx=cx-sx; val dy=cy-sy; val dist=sqrt(dx*dx+dy*dy)
        var ad=abs(((ang-sa)%360f+360f)%360f); if(ad>180f) ad=360f-ad
        if(dist<58f && ad<38f && abs(spd)<4f) win()
    }
    private fun crash(){ crashed=true; roles.clear(); spd=0f; crashes++; bx=cx; by=cy; blast=45; msg="Crash! Touch=retry | HOME=menu"; beep(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD); save() }
    private fun win(){
        parked=true; roles.clear(); spd=0f; val el=((SystemClock.elapsedRealtime()-t0)/1000L).toInt()
        stars=when{ abs(spd)<1.3f&&el<=35->3; abs(spd)<2.6f->2; else->1 }; starsT+=stars; wins++
        score+=80+stars*40+level*5
        if(level>=maxLv && level<MAX) maxLv=(level+1).coerceAtMost(MAX)
        msg="Parked! stars=$stars  NEXT or HOME"; beep(ToneGenerator.TONE_PROP_ACK); save()
    }

    private fun roleAt(x:Float,y:Float)=when{
        tL.contains(x,y)->L; tR.contains(x,y)->R; tG.contains(x,y)->GAS; tB.contains(x,y)->BRK; tV.contains(x,y)->REV; else->0
    }

    override fun onDraw(c: Canvas){
        super.onDraw(c); if(!ready)return
        when(state){ MENU->drawMenu(c); LEVELS->drawLevels(c); else->drawPlay(c) }
        if(dots) drawPanel(c)
    }

    private fun logo(c:Canvas,x:Float,y:Float,sz:Float){
        val a=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.parseColor("#6c5ce7")}
        val g=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.parseColor("#fdcb6e");style=Paint.Style.STROKE;strokeWidth=4f}
        val d=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.parseColor("#2d3436")}
        c.save(); c.translate(x-sz*0.15f,y); c.rotate(-12f)
        c.drawRoundRect(-sz*0.45f,-sz*0.6f,sz*0.45f,sz*0.6f,10f,10f,a); c.drawRoundRect(-sz*0.45f,-sz*0.6f,sz*0.45f,sz*0.6f,10f,10f,g); c.restore()
        c.save(); c.translate(x+sz*0.15f,y); c.rotate(10f)
        c.drawRoundRect(-sz*0.45f,-sz*0.6f,sz*0.45f,sz*0.6f,10f,10f,d); c.drawRoundRect(-sz*0.45f,-sz*0.6f,sz*0.45f,sz*0.6f,10f,10f,g)
        tp.textAlign=Paint.Align.CENTER; tp.textSize=sz*0.55f; c.drawText("P",0f,sz*0.18f,tp); c.restore(); tp.textAlign=Paint.Align.LEFT; tp.textSize=26f
    }

    private fun chip(c:Canvas,r:RectF,lab:String,onV:Boolean){
        c.drawRoundRect(r,12f,12f,if(onV)on else face); sp.textAlign=Paint.Align.CENTER; c.drawText(lab,r.centerX(),r.centerY()+7f,sp)
    }
    private fun ctrl(c:Canvas,r:RectF,lab:String,onV:Boolean,off:Paint,act:Paint){
        c.drawRoundRect(r,r.height()/2f,r.height()/2f,if(onV)act else off)
        tp.textAlign=Paint.Align.CENTER; tp.textSize=20f; c.drawText(lab,r.centerX(),r.centerY()+7f,tp); tp.textSize=26f; tp.textAlign=Paint.Align.LEFT
    }

    private fun drawMenu(c:Canvas){
        c.drawRect(0f,0f,W,H,menuBg); logo(c,W/2f,H*0.12f,40f)
        ac.textAlign=Paint.Align.CENTER; ac.textSize=32f; c.drawText("Royal Parking Pro",W/2f,H*0.22f,ac)
        sp.textAlign=Paint.Align.CENTER; c.drawText("100 Levels | Offline",W/2f,H*0.27f,sp)
        for(i in skins.indices){ val r=cards[i]; c.drawRoundRect(r,14f,14f,cardBg); if(i==carI)c.drawRoundRect(r,14f,14f,cardSel)
            val p=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=skins[i].c}
            c.drawRoundRect(r.centerX()-18f,r.centerY()-36f,r.centerX()+18f,r.centerY()+28f,10f,10f,p)
            sp.textAlign=Paint.Align.CENTER; c.drawText(skins[i].n,r.centerX(),r.bottom-10f,sp)
        }
        chip(c,bGyro,if(gyro)"Gyro ON" else "Gyro OFF",gyro)
        chip(c,bSens,when(sens){0->"Steer: Low";2->"Steer: High";else->"Steer: Mid"},true)
        chip(c,bSound,if(sound)"Sound ON" else "Sound OFF",sound)
        chip(c,bLock,if(lockCar)"Car: Locked" else "Car: Auto",lockCar)
        c.drawRoundRect(bLevels,16f,16f,openP); tp.textAlign=Paint.Align.CENTER
        c.drawText("SELECT LEVEL (1-$maxLv)",bLevels.centerX(),bLevels.centerY()+10f,tp)
        c.drawRoundRect(bStart,18f,18f,go); c.drawText("START Lv $maxLv",bStart.centerX(),bStart.centerY()+12f,tp)
        dotsBtn(c); tp.textAlign=Paint.Align.LEFT; sp.textAlign=Paint.Align.LEFT; ac.textAlign=Paint.Align.LEFT
    }

    private fun drawLevels(c:Canvas){
        c.drawRect(0f,0f,W,H,menuBg); ac.textAlign=Paint.Align.CENTER
        c.drawText("Levels | Unlocked 1-$maxLv / $MAX",W/2f,40f,ac)
        val start=page*20+1
        for(i in 0 until 20){ val lv=start+i; if(lv>MAX)break
            val r=cells[i]; val ok=lv<=maxLv; c.drawRoundRect(r,12f,12f,if(ok)openP else lockP)
            tp.textAlign=Paint.Align.CENTER; tp.textSize=22f; c.drawText(if(ok)"$lv" else "X$lv",r.centerX(),r.centerY()+8f,tp)
        }
        tp.textSize=26f; c.drawRoundRect(bPrev,12f,12f,face); c.drawRoundRect(bNxt,12f,12f,face); c.drawRoundRect(bBack,12f,12f,go)
        sp.textAlign=Paint.Align.CENTER; c.drawText("Prev",bPrev.centerX(),bPrev.centerY()+7f,sp)
        c.drawText("Next",bNxt.centerX(),bNxt.centerY()+7f,sp); c.drawText("Back",bBack.centerX(),bBack.centerY()+7f,sp)
        dotsBtn(c); sp.textAlign=Paint.Align.LEFT; ac.textAlign=Paint.Align.LEFT
    }

    private fun drawPlay(c:Canvas){
        c.drawRect(0f,0f,W,H,sky); c.drawRect(0f,H*0.1f,W,H,road)
        c.drawRoundRect(RectF(W*0.12f,H*0.14f,W*0.92f,H*0.90f),14f,14f,lot)
        c.drawRoundRect(RectF(W*0.12f,H*0.14f,W*0.92f,H*0.90f),14f,14f,edge)
        for(i in 1..7){ val y=H*0.16f+H*0.72f*i/8f; c.drawLine(W*0.14f,y,W*0.90f,y,lane) }
        for(w in walls){ c.drawRoundRect(w,5f,5f,wallP); c.drawRect(w.left,w.top-8f,w.right,w.top,wallT) }
        for(b in bars) c.drawRoundRect(b,4f,4f,barP)
        for(n in npcs){ c.save(); c.translate(n.x,n.y); c.rotate(n.a); val p=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=n.c}
            c.drawRoundRect(-24f,-46f,24f,46f,10f,10f,shad); c.drawRoundRect(-22f,-44f,22f,44f,10f,10f,p); c.restore() }
        c.save(); c.translate(sx,sy); c.rotate(sa)
        c.drawRoundRect(-sw/2,-sh/2,sw/2,sh/2,10f,10f,slotF); c.drawRoundRect(-sw/2,-sh/2,sw/2,sh/2,10f,10f,slotS)
        sp.textAlign=Paint.Align.CENTER; sp.color=Color.parseColor("#2ecc71"); c.drawText("P",0f,8f,sp); sp.color=Color.parseColor("#ecf0f1"); sp.textAlign=Paint.Align.LEFT; c.restore()
        // car
        c.save(); c.translate(cx,cy); c.rotate(ang)
        c.drawRoundRect(-21f,-44f,29f,56f,12f,12f,shad); c.drawRoundRect(-26f,-50f,26f,50f,12f,12f,body)
        c.drawRoundRect(-18f,-12f,18f,16f,8f,8f,dark); c.drawRoundRect(-15f,-6f,15f,12f,6f,6f,glass)
        c.drawCircle(-13f,-40f,5f,light); c.drawCircle(13f,-40f,5f,light); c.drawCircle(-12f,42f,4f,rear); c.drawCircle(12f,42f,4f,rear); c.restore()
        if(blast>0){ blastP.textSize=50f+(45-blast); c.drawText("*",bx,by,blastP) }

        c.drawRect(0f,0f,W,52f,ui); c.drawRoundRect(bHome,12f,12f,homeP)
        sp.textAlign=Paint.Align.CENTER; c.drawText("HOME",bHome.centerX(),bHome.centerY()+7f,sp); sp.textAlign=Paint.Align.LEFT
        c.drawText("Lv $level/$MAX",130f,32f,sp); dotsBtn(c)
        c.drawRect(0f,52f,W,78f,ui); c.drawText(msg,12f,70f,sp)

        val st=steer(); val th=thr(); val br=braking()
        ctrl(c,bL,"<",st<0,face,on); ctrl(c,bR,">",st>0,face,on)
        ctrl(c,bG,"GAS",th>0,gasF,gasO); ctrl(c,bB,"BRAKE",br,brkF,brkO); ctrl(c,bV,"REV",th<0,revF,revO)
        sp.textAlign=Paint.Align.CENTER; c.drawText("STEER",(bL.centerX()+bR.centerX())/2f,bL.top-6f,sp)
        c.drawText("SPD ${"%.1f".format(abs(spd))}",W/2f,H-12f,sp); sp.textAlign=Paint.Align.LEFT
        if(parked){ c.drawRoundRect(bNext,18f,18f,go); tp.textAlign=Paint.Align.CENTER
            c.drawText(if(level<MAX)"NEXT LEVEL" else "FINISH",bNext.centerX(),bNext.centerY()+12f,tp); tp.textAlign=Paint.Align.LEFT }
    }

    private fun dotsBtn(c:Canvas){ c.drawRoundRect(bDots,12f,12f,face); tp.textAlign=Paint.Align.CENTER; c.drawText(":",bDots.centerX(),bDots.centerY()+10f,tp); tp.textAlign=Paint.Align.LEFT }
    private fun drawPanel(c:Canvas){
        c.drawRoundRect(panel,16f,16f,panBg); var ty=panel.top+34f; val x=panel.left+16f
        ac.textSize=18f; c.drawText("Royal Parking Pro",x,ty,ac); ty+=28f; sp.textSize=16f
        c.drawText("Score $score  Wins $wins  Crash $crashes",x,ty,sp); ty+=24f
        c.drawText("Stars $starsT  MaxLv $maxLv  Steer ${when(sens){0->"Low";2->"High";else->"Mid"}}",x,ty,sp); ty+=24f
        c.drawText("HOME = game home screen",x,ty,sp)
    }

    override fun onTouch(v:View?,e:MotionEvent):Boolean{
        if(!ready)return true
        val a=e.actionMasked
        if(a==MotionEvent.ACTION_DOWN||a==MotionEvent.ACTION_POINTER_DOWN){
            val i=e.actionIndex; val x=e.getX(i); val y=e.getY(i)
            if(bDots.contains(x,y)){ dots=!dots; invalidate(); return true }
            if(dots && !panel.contains(x,y)) dots=false
            if(state==PLAY && bHome.contains(x,y)){ state=MENU; roles.clear(); dots=false; save(); return true }
        }
        return when(state){ MENU->tMenu(e); LEVELS->tLevels(e); else->tPlay(e) }
    }

    private fun tMenu(e:MotionEvent):Boolean{
        if(e.actionMasked!=MotionEvent.ACTION_DOWN)return true
        val x=e.x; val y=e.y
        for(i in skins.indices) if(cards[i].contains(x,y)){ carI=i; save(); invalidate(); return true }
        if(bGyro.contains(x,y)){ gyro=!gyro; save(); return true }
        if(bSens.contains(x,y)){ sens=(sens+1)%3; save(); return true }
        if(bSound.contains(x,y)){ sound=!sound; save(); return true }
        if(bLock.contains(x,y)){ lockCar=!lockCar; save(); return true }
        if(bLevels.contains(x,y)){ state=LEVELS; page=0; dots=false; return true }
        if(bStart.contains(x,y)){ skin(); setup(maxLv.coerceAtMost(MAX)); state=PLAY; dots=false; beep(ToneGenerator.TONE_PROP_BEEP) }
        return true
    }
    private fun tLevels(e:MotionEvent):Boolean{
        if(e.actionMasked!=MotionEvent.ACTION_DOWN)return true
        val x=e.x; val y=e.y
        if(bBack.contains(x,y)){ state=MENU; return true }
        if(bPrev.contains(x,y)){ page=(page-1).coerceAtLeast(0); return true }
        if(bNxt.contains(x,y)){ page=(page+1).coerceAtMost(4); return true }
        val start=page*20+1
        for(i in 0 until 20){ val lv=start+i; if(lv>MAX)break
            if(cells[i].contains(x,y)){ if(lv<=maxLv){ skin(); setup(lv); state=PLAY; dots=false; beep(ToneGenerator.TONE_PROP_BEEP) }; return true }
        }
        return true
    }
    private fun tPlay(e:MotionEvent):Boolean{
        when(e.actionMasked){
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN->{
                val i=e.actionIndex; val id=e.getPointerId(i); val x=e.getX(i); val y=e.getY(i)
                if(crashed){ setup(level); return true }
                if(parked && bNext.contains(x,y)){ if(level<MAX) setup(level+1) else { state=MENU; msg="All 100 done!" }; return true }
                val r=roleAt(x,y); if(r!=0) roles[id]=r
            }
            MotionEvent.ACTION_MOVE->{
                for(i in 0 until e.pointerCount){ val id=e.getPointerId(i); if(roles.containsKey(id))continue
                    val r=roleAt(e.getX(i),e.getY(i)); if(r!=0) roles[id]=r }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL-> roles.remove(e.getPointerId(e.actionIndex))
        }
        return true
    }
}
