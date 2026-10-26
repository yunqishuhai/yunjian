package io.github.miyouchat

import io.github.miyouchat.data.*
import org.junit.Assert.*
import org.junit.Test

class EmoticonLibraryTest {
    @Test fun cacheRoundTripPreservesGroupingAndCustomSendMetadata() {
        val original = listOf(Emoticon("送花花", "https://example.com/a.png", "3", "米游兔"),
            Emoticon("自定义表情", "https://example.com/b.gif", "custom", "自定义", "123", "{\"id\":\"123\"}", "CustomEmoticonAuditStatusPass"))
        assertEquals(original, EmoticonLibrary.decode(EmoticonLibrary.encode(original)))
    }
    @Test fun customAndOfficialNamesCannotCollide() {
        val official = Emoticon("同名", "https://example.com/a.png")
        val custom = official.copy(customId = "123")
        assertNotEquals(official.key, custom.key)
        assertNotEquals(custom.key, custom.copy(customId = "456").key)
    }
    @Test fun onlyExplicitlyApprovedCustomEmoticonsAreSendable() {
        val custom = Emoticon("图", "https://example.com/a.png", customId = "123")
        for (status in listOf("", "unknown", "CustomEmoticonAuditStatusPend", "CustomEmoticonAuditStatusNoPass"))
            assertFalse(custom.copy(auditStatus = status).canSend)
        assertTrue(custom.copy(auditStatus = "CustomEmoticonAuditStatusPass").canSend)
        assertTrue(custom.copy(customId = "").canSend)
    }
}
