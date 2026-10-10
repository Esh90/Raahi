package com.raahi.app.core.navigation

import android.net.Uri

object Routes {
    const val SPLASH = "splash"
    const val ONB_WELCOME = "onboarding/welcome"
    const val ONB_VOICE = "onboarding/voice"
    const val ONB_SURROUNDINGS = "onboarding/surroundings"
    const val ONB_GESTURES = "onboarding/gestures"
    const val GET_STARTED = "get_started"
    const val PERMISSIONS = "permissions"

    const val HOME = "home"
    const val HISTORY = "history"
    const val SETTINGS = "settings"

    const val GUIDANCE = "guidance"
    const val VOICE = "voice"
    const val FIND = "find"
    const val WHATS_AHEAD = "whats_ahead"
    const val QUICK = "quick_commands"
    const val SUMMARY = "summary"
    const val DETAILS = "details/{id}"
    fun details(id: String) = "details/${Uri.encode(id)}"

    const val SET_ACCESSIBILITY = "settings/accessibility"
    const val SET_VOICE = "settings/voice"
    const val SET_GUIDANCE = "settings/guidance"
    const val SET_CONNECTION = "settings/connection"
    const val SET_APPEARANCE = "settings/appearance"

    const val DIAGNOSTICS = "diagnostics"
    const val CONNECTION_STATUS = "connection_status"
    const val HELP = "help"
    const val PRIVACY = "privacy"
    const val ABOUT = "about"

    const val ERROR = "error/{kind}?target={target}"
    fun error(kind: ErrorKind, target: String? = null) =
        "error/${kind.name}" + (if (target != null) "?target=${Uri.encode(target)}" else "")
}

/** The approved error screens (design 31a-31h). */
enum class ErrorKind {
    CAMERA_PERMISSION, // 31a
    MIC_PERMISSION, // 31b
    SERVER_UNAVAILABLE, // 31c
    CONNECTION, // 31d
    CAMERA_FAILED, // 31e
    NO_SOUND, // 31f
    NOT_FOUND, // 31g
    GENERIC, // 31h
}
