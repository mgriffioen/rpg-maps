package com.rpgmaps.tabletop.display.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The receiver is JavaScript and parses this JSON by hand, so the exact shape
 * of the wire format is part of the contract with `receiver/index.html`. These
 * tests fail loudly if a rename or a default silently changes it.
 */
class DisplayMessageTest {

    private fun encode(message: DisplayMessage): String =
        DisplayJson.encodeToString(DisplayMessage.serializer(), message)

    private fun decode(json: String): DisplayMessage =
        DisplayJson.decodeFromString(DisplayMessage.serializer(), json)

    @Test
    fun `discriminator is t, which is what the receiver switches on`() {
        val json = encode(ViewportMessage(cx = 100f, cy = 200f, halfH = 300f))
        assertTrue("expected a \"t\" discriminator in $json", json.contains("\"t\":\"view\""))
    }

    @Test
    fun `every message type round trips`() {
        val messages = listOf(
            MapAnnounced("id", "Cragmaw Hideout", 2048, 1536, 1024, 768, 3),
            ImageUrl(3, "/image?rev=3"),
            ImageChunk(3, 0, 12, "image/jpeg", "AAAA"),
            ViewportMessage(1f, 2f, 3f),
            FogOpsMessage(7, listOf(FogOp(true, 12f, 0.3f, listOf(1f, 2f, 3f, 4f)))),
            FogResetMessage(8, "iVBORw0KGgo="),
            FogFillMessage(9, fogged = true),
            GridMessage(true, 64f, 3f, 5f),
            RotationMessage(3),
            BlankMessage(true, "Back in ten"),
            ReceiverHello(1920, 1080, "Chromecast"),
            ResyncRequest(4),
        )

        messages.forEach { original ->
            assertEquals(original, decode(encode(original)))
        }
    }

    @Test
    fun `fog points stay a flat x y run`() {
        val json = encode(FogOpsMessage(1, listOf(FogOp(true, 10f, 0f, listOf(1f, 2f, 3f, 4f)))))
        assertTrue("expected a flat pts array in $json", json.contains("\"pts\":[1.0,2.0,3.0,4.0]"))
    }

    @Test
    fun `unknown fields from a newer receiver are ignored rather than fatal`() {
        val decoded = decode("""{"t":"hello","w":3840,"h":2160,"label":"TV","somethingNew":42}""")
        assertEquals(ReceiverHello(3840, 2160, "TV"), decoded)
    }

    /**
     * The receiver sends the ping as a hardcoded string and the transport
     * compares against the constant, so neither may drift from the serialized
     * form. If this fails, keepalive silently stops working and a half-open
     * socket goes back to being invisible.
     */
    @Test
    fun `keepalive constants match what the serializer produces`() {
        assertEquals(PING_JSON, encode(Ping))
        assertEquals(PONG_JSON, encode(Pong))
        assertEquals(Ping, decode(PING_JSON))
        assertEquals(Pong, decode(PONG_JSON))
    }

    /**
     * The receiver switches on "rot" and reads `quarters`; both renderers use
     * it to decide which screen axis halfH is measured against, so a rename
     * here would silently mis-frame the player view rather than fail loudly.
     */
    @Test
    fun `rotation is sent as quarter turns under the rot tag`() {
        val json = encode(RotationMessage(1))
        assertEquals("""{"t":"rot","quarters":1}""", json)
    }

    @Test
    fun `cast namespace matches the receiver`() {
        // receiver/index.html hardcodes this string; they must not drift.
        assertEquals("urn:x-cast:com.rpgmaps.tabletop", CAST_NAMESPACE)
    }
}
