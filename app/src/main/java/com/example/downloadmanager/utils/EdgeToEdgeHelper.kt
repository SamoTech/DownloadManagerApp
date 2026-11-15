package com.example.downloadmanager.utils

import android.graphics.Color
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams

/**
 * Unified Edge-to-Edge and Display Cutout (notch) helper for Android 7–16+ (API 24–36).
 *
 * - Enables full-bleed edge-to-edge rendering with light icons over dark `#0F172A` headers
 *   and adaptive navigation bar styling.
 * - Configures display cutout mode (`shortEdges` / `always`) so the header background fills
 *   around camera notches and punch-holes seamlessly without overlapping interactive UI.
 * - Preserves each view's original XML padding/margins cleanly across repeated inset passes.
 */
object EdgeToEdgeHelper {

    fun enableWithDarkHeader(
        activity: ComponentActivity,
        darkNavigationBar: Boolean = false
    ) {
        val navStyle = if (darkNavigationBar) {
            SystemBarStyle.dark(Color.TRANSPARENT)
        } else {
            SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT)
        }
        activity.enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = navStyle
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            activity.window.attributes = activity.window.attributes.apply {
                layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                } else {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
        }
    }

    /**
     * Pads the top (status bar + display cutout) and horizontal (landscape cutout / nav bar)
     * edges of a top app bar or header container, adjusting fixed heights automatically if needed.
     */
    fun applyTopHeaderInsets(view: View, padHorizontal: Boolean = true) {
        val baseLeft = view.paddingLeft
        val baseTop = view.paddingTop
        val baseRight = view.paddingRight
        val baseBottom = view.paddingBottom
        val baseHeight = view.layoutParams?.height ?: ViewGroup.LayoutParams.WRAP_CONTENT

        ViewCompat.setOnApplyWindowInsetsListener(view) { v, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout()
            )
            if (baseHeight > 0) {
                v.updateLayoutParams<ViewGroup.LayoutParams> {
                    height = baseHeight + insets.top
                }
            }
            v.setPadding(
                if (padHorizontal) baseLeft + insets.left else baseLeft,
                baseTop + insets.top,
                if (padHorizontal) baseRight + insets.right else baseRight,
                baseBottom
            )
            windowInsets
        }
        ViewCompat.requestApplyInsets(view)
    }

    /**
     * Pads the bottom and horizontal edges of a scrolling container (`RecyclerView`, `ScrollView`,
     * `NestedScrollView`) so content scrolls behind the navigation bar cleanly while remaining reachable.
     */
    fun applyScrollingBottomInsets(
        view: View,
        includeIme: Boolean = false,
        padHorizontal: Boolean = true
    ) {
        val baseLeft = view.paddingLeft
        val baseTop = view.paddingTop
        val baseRight = view.paddingRight
        val baseBottom = view.paddingBottom
        if (view is ViewGroup) {
            view.clipToPadding = false
        }

        val mask = if (includeIme) {
            WindowInsetsCompat.Type.navigationBars() or
                WindowInsetsCompat.Type.displayCutout() or
                WindowInsetsCompat.Type.ime()
        } else {
            WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.displayCutout()
        }

        ViewCompat.setOnApplyWindowInsetsListener(view) { v, windowInsets ->
            val insets = windowInsets.getInsets(mask)
            v.setPadding(
                if (padHorizontal) baseLeft + insets.left else baseLeft,
                baseTop,
                if (padHorizontal) baseRight + insets.right else baseRight,
                baseBottom + insets.bottom
            )
            windowInsets
        }
        ViewCompat.requestApplyInsets(view)
    }

    /**
     * Pads horizontal cutout/nav-bar edges for body containers when the bottom bar handles the bottom inset.
     */
    fun applyHorizontalCutoutInsets(view: View) {
        val baseLeft = view.paddingLeft
        val baseTop = view.paddingTop
        val baseRight = view.paddingRight
        val baseBottom = view.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(view) { v, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(
                baseLeft + insets.left,
                baseTop,
                baseRight + insets.right,
                baseBottom
            )
            windowInsets
        }
        ViewCompat.requestApplyInsets(view)
    }

    /**
     * Offsets left/right margins on card views so their card background clears landscape notches/cutouts
     * while preserving their base XML horizontal margins.
     */
    fun applyHorizontalCutoutMargins(view: View) {
        val lp = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val baseLeft = lp.leftMargin
        val baseTop = lp.topMargin
        val baseRight = lp.rightMargin
        val baseBottom = lp.bottomMargin

        ViewCompat.setOnApplyWindowInsetsListener(view) { v, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                leftMargin = baseLeft + insets.left
                topMargin = baseTop
                rightMargin = baseRight + insets.right
                bottomMargin = baseBottom
            }
            windowInsets
        }
        ViewCompat.requestApplyInsets(view)
    }

    /**
     * Pads a docked bottom action bar (adjusting fixed heights automatically) above navigation bar / IME / cutouts.
     */
    fun applyBottomBarInsets(
        view: View,
        includeIme: Boolean = false,
        padHorizontal: Boolean = true
    ) {
        val baseLeft = view.paddingLeft
        val baseTop = view.paddingTop
        val baseRight = view.paddingRight
        val baseBottom = view.paddingBottom
        val baseHeight = view.layoutParams?.height ?: ViewGroup.LayoutParams.WRAP_CONTENT

        val mask = if (includeIme) {
            WindowInsetsCompat.Type.navigationBars() or
                WindowInsetsCompat.Type.displayCutout() or
                WindowInsetsCompat.Type.ime()
        } else {
            WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.displayCutout()
        }

        ViewCompat.setOnApplyWindowInsetsListener(view) { v, windowInsets ->
            val insets = windowInsets.getInsets(mask)
            if (baseHeight > 0) {
                v.updateLayoutParams<ViewGroup.LayoutParams> {
                    height = baseHeight + insets.bottom
                }
            }
            v.setPadding(
                if (padHorizontal) baseLeft + insets.left else baseLeft,
                baseTop,
                if (padHorizontal) baseRight + insets.right else baseRight,
                baseBottom + insets.bottom
            )
            windowInsets
        }
        ViewCompat.requestApplyInsets(view)
    }

    /**
     * Offsets bottom and horizontal margins on floating controls (e.g. FABs) so they float above
     * gesture navigation bars and side cutouts.
     */
    fun applyBottomFabMarginInsets(view: View) {
        val lp = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val baseLeft = lp.leftMargin
        val baseTop = lp.topMargin
        val baseRight = lp.rightMargin
        val baseBottom = lp.bottomMargin

        ViewCompat.setOnApplyWindowInsetsListener(view) { v, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                leftMargin = baseLeft + insets.left
                topMargin = baseTop
                rightMargin = baseRight + insets.right
                bottomMargin = baseBottom + insets.bottom
            }
            windowInsets
        }
        ViewCompat.requestApplyInsets(view)
    }
}
