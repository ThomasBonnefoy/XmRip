package com.xmrip.desktop

import com.xmrip.app.protocol.HeadsetLook
import com.xmrip.app.protocol.MdrSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelColorTest {
    private fun handshake(fake: FakeHeadset, seed: Int? = null): MdrSession {
        val session = MdrSession(fake.input, fake.output)
        if (seed != null) session.seedDeviceInfo("WH-1000XM6", "3.1.5", seed)
        session.handshakeAndBattery()
        return session
    }

    @Test
    fun `lit chaque code de couleur annonce par le casque`() {
        for (code in 0..14) {
            val session = handshake(FakeHeadset.xm6(code))
            assertEquals(code, session.modelColor, "code $code")
            assertEquals(code, session.report?.modelColor, "rapport, code $code")
            assertEquals(64, session.report?.levelPercent)
        }
    }

    @Test
    fun `un casque sans reponse de couleur ne bloque pas le handshake`() {
        val start = System.currentTimeMillis()
        val session = handshake(FakeHeadset.xm6(color = null))
        assertNull(session.modelColor)
        assertEquals(64, session.report?.levelPercent)
        assertTrue(System.currentTimeMillis() - start < 4000, "attente de la couleur trop longue")
    }

    @Test
    fun `une couleur deja connue n'est pas redemandee`() {
        val fake = FakeHeadset.xm6(color = 6)
        val session = handshake(fake, seed = 5)
        assertEquals(5, session.modelColor)
        assertTrue(fake.requests.none { it.take(2) == listOf(0x04, 0x03) })
    }

    @Test
    fun `la photo suit la couleur et retombe sur le noir si inconnue`() {
        val expected = mapOf(
            null to HeadsetLook.BLACK, 0 to HeadsetLook.BLACK, 1 to HeadsetLook.BLACK, 2 to HeadsetLook.SILVER,
            3 to HeadsetLook.SILVER, 4 to HeadsetLook.BLACK, 5 to HeadsetLook.BLUE, 6 to HeadsetLook.PINK,
            7 to HeadsetLook.SAND, 8 to HeadsetLook.OLIVE, 9 to HeadsetLook.OLIVE, 10 to HeadsetLook.SAND,
            11 to HeadsetLook.SAND, 12 to HeadsetLook.SAND, 13 to HeadsetLook.SAND, 14 to HeadsetLook.BLACK,
            99 to HeadsetLook.BLACK,
        )
        for ((code, look) in expected) assertEquals(look, HeadsetLook.of(code), "code $code")
    }
}
