package com.vibecoded.planner.system

import android.app.Notification

/**
 * Standard Android badges come from the notification channel + setNumber. Older MIUI
 * launchers additionally read a private "extraNotification" field; HyperOS may ignore
 * both. Everything here is best effort and must never crash.
 */
object Badges {
    fun applyXiaomiCount(notification: Notification, count: Int) {
        try {
            val field = notification.javaClass.getDeclaredField("extraNotification")
            field.isAccessible = true
            val extra = field.get(notification) ?: return
            extra.javaClass.getDeclaredMethod("setMessageCount", Int::class.javaPrimitiveType)
                .invoke(extra, count)
        } catch (t: Throwable) {
            // Not a Xiaomi build, or the private API changed.
        }
    }
}
