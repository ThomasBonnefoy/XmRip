package com.xmrip.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Rect
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * Conteneur des pages : une seule visible, et un glissement horizontal du doigt fait passer à la page voisine (la
 * page suit le doigt, avec une résistance élastique aux deux bouts). Le glissement vertical reste aux pages
 * (défilement). Les vues de [noSwipe] (carte du mode, curseurs, égaliseur) gardent leurs propres gestes horizontaux.
 */
class PagerFrame(context: Context) : FrameLayout(context) {
    var current = 0
        private set
    var onPageSelected: ((Int) -> Unit)? = null
    val noSwipe = ArrayList<View>()

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val flingMin = ViewConfiguration.get(context).scaledMinimumFlingVelocity * 6
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var blocked = false
    private var offset = 0f
    private var velocity: VelocityTracker? = null
    private var settle: ValueAnimator? = null
    private val hit = Rect()

    /** Affiche [index] tout de suite, sans glissement. */
    fun showPage(index: Int) {
        settle?.cancel()
        current = index
        offset = 0f
        layoutPages()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutPages()
    }

    private fun layoutPages() {
        val w = width.toFloat()
        for (i in 0 until childCount) {
            val page = getChildAt(i)
            val x = (i - current) * w + offset
            val visible = i == current || (offset != 0f && abs(x) < w)
            page.visibility = if (visible) View.VISIBLE else View.GONE
            page.translationX = if (w > 0f) x else 0f
        }
    }

    private fun startsOnNoSwipe(ev: MotionEvent): Boolean = noSwipe.any { v ->
        v.isShown && v.getGlobalVisibleRect(hit) && hit.contains(ev.rawX.toInt(), ev.rawY.toInt())
    }

    private fun isHorizontal(ev: MotionEvent): Boolean {
        val dx = abs(ev.x - downX)
        return dx > slop * 1.5f && dx > abs(ev.y - downY) * 1.7f
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                dragging = false
                blocked = startsOnNoSwipe(ev) || childCount < 2
                velocity?.recycle()
                velocity = VelocityTracker.obtain().also { it.addMovement(ev) }
                // Une page en train de revenir en place se rattrape au doigt.
                if (settle?.isRunning == true) {
                    settle?.cancel()
                    dragging = true
                    downX = ev.x - offset
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(ev)
                if (!blocked && !dragging && isHorizontal(ev)) {
                    dragging = true
                    downX = ev.x
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
        }
        return dragging
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (velocity == null) velocity = VelocityTracker.obtain()
        velocity?.addMovement(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> if (!dragging) {
                downX = ev.x
                downY = ev.y
                blocked = startsOnNoSwipe(ev)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && !blocked && isHorizontal(ev)) {
                    dragging = true
                    downX = ev.x
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                if (dragging) {
                    var dx = ev.x - downX
                    val atEdge = (current == 0 && dx > 0) || (current == childCount - 1 && dx < 0)
                    if (atEdge) dx *= 0.3f
                    offset = dx
                    layoutPages()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    velocity?.computeCurrentVelocity(1000)
                    settleFrom(velocity?.xVelocity ?: 0f)
                }
                dragging = false
                velocity?.recycle()
                velocity = null
            }
        }
        return true
    }

    private fun settleFrom(vx: Float) {
        val w = width.toFloat()
        var target = current
        if ((offset < -w * 0.22f || vx < -flingMin) && current < childCount - 1) target = current + 1
        else if ((offset > w * 0.22f || vx > flingMin) && current > 0) target = current - 1
        val from = offset
        val to = (current - target) * w
        settle = ValueAnimator.ofFloat(from, to).apply {
            duration = (180 + 160 * abs(to - from) / w.coerceAtLeast(1f)).toLong()
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener {
                offset = it.animatedValue as Float
                layoutPages()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: android.animation.Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (cancelled) return
                    val changed = target != current
                    current = target
                    offset = 0f
                    layoutPages()
                    if (changed) onPageSelected?.invoke(target)
                }
            })
            start()
        }
    }
}
