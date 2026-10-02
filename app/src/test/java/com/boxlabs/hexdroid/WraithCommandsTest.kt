package com.boxlabs.hexdroid

import org.junit.Assert.*
import org.junit.Test

class WraithCommandsTest {
    @Test fun rejectsCommandAndArgumentInjection() {
        for (input in listOf("hub\r\n.die", "hub\n.restart", "hub other", "hub\u0000", "", "a".repeat(65)))
            assertNull(WraithCommands.line("relay", input))
        assertNull(WraithCommands.line("status\n.die"))
        assertNull(WraithCommands.line("status", "unexpected"))
        assertNull(WraithCommands.line("die"))
    }

    @Test fun supportsWraithQueriesAndRelay() {
        assertEquals(".status", WraithCommands.line("status"))
        assertEquals(".bottree", WraithCommands.line("bottree"))
        assertEquals(".relay hub-1", WraithCommands.line("relay", "hub-1"))
    }
}
