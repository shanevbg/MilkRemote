package com.sheinsez.mdropdx12.remote.data

/**
 * Things the phone is doing that will interrupt a long stream, and what to
 * suggest about each.
 *
 * SUGGESTIONS, not requirements. The app works without any of them — if
 * Android kills the service the user reopens the app, which is a fair price
 * for not nagging. What this avoids is the other outcome: audio that stops
 * after twenty minutes with the screen off and nothing anywhere explaining
 * that the phone decided to do that.
 *
 * Pure, so the decision of what to show is testable without a device.
 */
enum class Suggestion {
    /** Doze will throttle the socket and the service eventually. */
    BatteryOptimization,

    /** A foreground service with no visible notification is killable. */
    Notifications,

    /** Android will revoke permissions from an app it thinks is unused. */
    AppHibernation,
}

data class SystemState(
    val ignoringBatteryOptimizations: Boolean,
    val notificationsEnabled: Boolean,
    val hibernationEnabled: Boolean,
)

object SystemReadiness {

    /**
     * What is worth suggesting, most disruptive first.
     *
     * Nothing is suggested until the user has shown they want long-running
     * behaviour -- `wantsBackground` is the background-service or stream
     * setting being on. Asking a remote-control user to exempt the app from
     * battery optimization is noise; asking someone who streams audio to it
     * is useful.
     */
    fun suggestions(state: SystemState, wantsBackground: Boolean): List<Suggestion> {
        if (!wantsBackground) return emptyList()
        val out = mutableListOf<Suggestion>()
        if (!state.ignoringBatteryOptimizations) out += Suggestion.BatteryOptimization
        if (!state.notificationsEnabled) out += Suggestion.Notifications
        if (state.hibernationEnabled) out += Suggestion.AppHibernation
        return out
    }

    fun title(s: Suggestion): String = when (s) {
        Suggestion.BatteryOptimization -> "Exclude from battery optimization"
        Suggestion.Notifications -> "Allow notifications"
        Suggestion.AppHibernation -> "Stop pausing this app when unused"
    }

    fun reason(s: Suggestion): String = when (s) {
        Suggestion.BatteryOptimization ->
            "Android throttles background network after a while, which stops the " +
                "stream with the screen off."
        Suggestion.Notifications ->
            "The stream runs as a foreground service, and one without a visible " +
                "notification is the first thing Android ends."
        Suggestion.AppHibernation ->
            "Android removes permissions from apps it decides are unused, which " +
                "takes the stream's with them."
    }
}
