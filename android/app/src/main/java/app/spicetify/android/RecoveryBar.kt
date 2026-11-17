package app.spicetify.android

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Native bar over the player for when the web UI is not usable: shown if the
 * player does not come up, and while safe mode is active. It works even when
 * the page is blank, unlike the Spicetify button inside the player.
 */
class RecoveryBar(context: Context) : LinearLayout(context) {
    private val message = TextView(context)
    private val buttons = LinearLayout(context)

    init {
        orientation = VERTICAL
        val pad = dp(16)
        setPadding(pad, dp(12), pad, dp(8))
        background = GradientDrawable().apply {
            setColor(Color.parseColor("#282828"))
            cornerRadius = dp(12).toFloat()
        }
        elevation = dp(8).toFloat()
        message.setTextColor(Color.WHITE)
        message.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        addView(message)
        buttons.orientation = HORIZONTAL
        buttons.gravity = Gravity.END
        addView(buttons, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        visibility = GONE
    }

    fun show(text: String, vararg actions: Pair<String, () -> Unit>) {
        message.text = text
        buttons.removeAllViews()
        for ((label, action) in actions) {
            buttons.addView(
                Button(context, null, android.R.attr.borderlessButtonStyle).apply {
                    this.text = label
                    setTextColor(Color.parseColor("#1ED760"))
                    isAllCaps = false
                    setOnClickListener { action() }
                },
            )
        }
        visibility = View.VISIBLE
    }

    fun hide() {
        visibility = GONE
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
