package com.decikeeper.app.ui

import android.view.View
import android.widget.ImageView
import com.decikeeper.app.R
import com.decikeeper.app.manager.SnackJarPresentation

/** 只显示两个非交互角落图；不添加窗口、不延迟关闭、不接管相机或挑战生命周期。 */
internal object AlarmMascotPresenter {
    fun render(root: View, cooling: Boolean, connecting: Boolean, completed: Boolean) {
        val pose = SnackJarPresentation.alarmPose(cooling, connecting, completed)
        bind(root.findViewById(R.id.image_alarm_mascot), when (pose) {
            SnackJarPresentation.AlarmPose.WAKING -> R.drawable.mascot_waking
            SnackJarPresentation.AlarmPose.HAPPY -> R.drawable.mascot_happy
            else -> null
        })
        bind(root.findViewById(R.id.image_alarm_scan_mascot),
            if (pose == SnackJarPresentation.AlarmPose.BRUSHING) R.drawable.mascot_brushing else null)
    }

    private fun bind(view: ImageView?, drawable: Int?) {
        view ?: return
        view.isClickable = false
        view.isFocusable = false
        view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        view.visibility = if (drawable == null) View.GONE else View.VISIBLE
        if (drawable != null && view.tag != drawable) { view.setImageResource(drawable); view.tag = drawable }
    }
}
