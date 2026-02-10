package com.ooustream.iptv.common

import androidx.fragment.app.FragmentTransaction
import com.ooustream.iptv.R

enum class TransitionDirection {
    FORWARD, BACK, PLAYER, HOME
}

object FragmentTransitions {
    fun apply(transaction: FragmentTransaction, direction: TransitionDirection) {
        when (direction) {
            TransitionDirection.FORWARD -> transaction.setCustomAnimations(
                R.anim.slide_in_right, R.anim.slide_out_left,
                R.anim.slide_in_left, R.anim.slide_out_right
            )
            TransitionDirection.BACK -> transaction.setCustomAnimations(
                R.anim.slide_in_left, R.anim.slide_out_right,
                R.anim.slide_in_right, R.anim.slide_out_left
            )
            TransitionDirection.PLAYER -> transaction.setCustomAnimations(
                R.anim.fade_slide_up, R.anim.fade_out,
                R.anim.fade_in, R.anim.fade_slide_down
            )
            TransitionDirection.HOME -> transaction.setCustomAnimations(
                R.anim.zoom_in, R.anim.zoom_out,
                R.anim.zoom_in, R.anim.zoom_out
            )
        }
    }
}
