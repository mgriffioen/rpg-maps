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
    fun `a ping is tagged mark, since ping is the keepalive`() {
        val json = encode(MarkMessage(512.5f, 384.25f))
        // receiver/index.html reads msg.x and msg.y off this directly.
        assertEquals("""{"t":"mark","x":512.5,"y":384.25}""", json)
    }

    @Test
    fun `every message type round trips`() {
        val messages = listOf(
            MapAnnounced("id", "Cragmaw Hideout", 2048, 1536, 1024, 768, 3),
            ImageUrl(3, "/image?rev=3"),
            ImageChunk(3, 0, 12, "image/jpeg", "AAAA"),
            ViewportMessage(1f, 2f, 3f),
            FogOpsMessage(7, listOf(FogOp(true, 12f, 0.3f, listOf(1f, 2f, 3f, 4f)))),
            FogOpsMessage(
                8,
                listOf(FogOp(false, 0f, 0.4f, listOf(5f, 6f, 7f, 8f), FogShape.OVAL)),
            ),
            FogResetMessage(8, "iVBORw0KGgo="),
            FogFillMessage(9, fogged = true),
            GridMessage(true, 64f, 3f, 5f),
            RotationMessage(3),
            MarkMessage(512.5f, 384.25f),
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

    /**
     * The receiver reads `op.shape` as a lowercase string and defaults a
     * missing one to a brush stroke. Both halves of that matter: the names
     * must not become Kotlin's SCREAMING_CASE, and an op sent without the
     * field must still paint freehand.
     */
    @Test
    fun `fog shapes serialise as lowercase names and default to brush`() {
        val oval = encode(FogOpsMessage(1, listOf(FogOp(true, 0f, 0f, listOf(1f, 2f, 3f, 4f), FogShape.OVAL))))
        assertTrue("expected a lowercase shape in ${'$'}oval", oval.contains("\"shape\":\"oval\""))

        val rect = encode(FogOpsMessage(1, listOf(FogOp(true, 0f, 0f, listOf(1f, 2f, 3f, 4f), FogShape.RECT))))
        assertTrue("expected a lowercase shape in ${'$'}rect", rect.contains("\"shape\":\"rect\""))

        val withoutShape = DisplayJson.decodeFromString(
            DisplayMessage.serializer(),
            """{"t":"fog","seq":1,"ops":[{"reveal":true,"radius":9.0,"softness":0.0,"pts":[1.0,2.0]}]}""",
        ) as FogOpsMessage
        assertEquals(FogShape.BRUSH, withoutShape.ops.first().shape)
    }

    @Test
    fun `cast namespace matches the receiver`() {
        // receiver/index.html hardcodes this string; they must not drift.
        assertEquals("urn:x-cast:com.rpgmaps.tabletop", CAST_NAMESPACE)
    }
}
