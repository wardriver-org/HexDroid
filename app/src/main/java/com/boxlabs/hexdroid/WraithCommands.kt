package com.boxlabs.hexdroid

/** Commands supported by the Wraith DCC panel; arguments never become additional lines. */
internal object WraithCommands {
    val queries = linkedMapOf(
        "Help" to "help", "My identity" to "whoami", "Status" to "status",
        "Bots (hub)" to "bots", "Bot tree (hub)" to "bottree",
    )

    fun line(command: String, argument: String = ""): String? {
        if (command in queries.values && argument.isEmpty()) return ".$command"
        if (command == "relay" && argument.matches(Regex("[A-Za-z0-9_\\-\\[\\]{}^`|]{1,64}")))
            return ".relay $argument"
        return null
    }
}
