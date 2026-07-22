package com.sheen.adb.core.internal

import com.sheen.adb.core.AdbConnectionState
import com.sheen.adb.core.AdbEndpoint
import com.sheen.adb.core.AdbError
import com.sheen.adb.core.AdbOperationResult
import com.sheen.adb.core.DisconnectionReason
import com.sheen.adb.core.PairingMethod
import com.sheen.adb.core.PairingSecret
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.testng.Assert.assertEquals
import org.testng.Assert.assertFalse
import org.testng.Assert.assertSame
import org.testng.Assert.assertTrue
import org.testng.annotations.Test
import kotlin.time.Duration.Companion.milliseconds

class QrPairingSessionManagerTest {
    @Test
    fun `QR password and six digit code share the protocol pairing path without retaining either secret`() = runBlocking {
        val factory = RecordingFactory()
        val manager = DefaultAdbSessionManager(factory, Dispatchers.Unconfined)
        val qrChars = CharArray(QR_PASSWORD_LENGTH) { index -> QR_ALPHABET[index % QR_ALPHABET.size] }
        val codeChars = CharArray(SIX_DIGIT_CODE_LENGTH) { index -> ('0'.code + index).toChar() }

        val qrResult = manager.pairWithSecret(
            pairingEndpoint = QR_ENDPOINT,
            pairingSecret = PairingSecret(qrChars),
            method = PairingMethod.QR,
        )
        val codeResult = manager.pair(CODE_ENDPOINT, codeChars)

        assertTrue(qrResult is AdbOperationResult.Success<*>)
        assertTrue(codeResult is AdbOperationResult.Success)
        assertEquals(
            factory.calls,
            listOf(
                PairCall(CredentialShape.QR_PASSWORD, QR_PASSWORD_LENGTH),
                PairCall(CredentialShape.SIX_DIGIT_CODE, SIX_DIGIT_CODE_LENGTH),
            ),
        )
        assertCleared(qrChars)
        assertCleared(codeChars)
        assertEquals(manager.connectionState.value, AdbConnectionState.Disconnected())
        assertEquals(factory.openCalls, 0, "Pairing authorization must not create a connected ADB Session")
    }

    @Test
    fun `QR timeout maps to pair timeout and clears the caller owned characters`() = runBlocking {
        val factory = RecordingFactory(pairBehavior = PairBehavior.WAIT_FOREVER)
        val manager = DefaultAdbSessionManager(factory, Dispatchers.Unconfined)
        val qrChars = CharArray(QR_PASSWORD_LENGTH) { index -> QR_ALPHABET[index % QR_ALPHABET.size] }

        val result = manager.pairWithSecret(
            pairingEndpoint = QR_ENDPOINT,
            pairingSecret = PairingSecret(qrChars),
            method = PairingMethod.QR,
            timeout = 25.milliseconds,
        )

        assertTrue(result is AdbOperationResult.Failure)
        assertTrue((result as AdbOperationResult.Failure).error is AdbError.Timeout)
        assertCleared(qrChars)
        assertEquals(factory.calls.single().shape, CredentialShape.QR_PASSWORD)
    }

    @Test
    fun `QR cancellation maps to cancelled and clears the caller owned characters`() = runBlocking {
        val factory = RecordingFactory(pairBehavior = PairBehavior.CANCEL)
        val manager = DefaultAdbSessionManager(factory, Dispatchers.Unconfined)
        val qrChars = CharArray(QR_PASSWORD_LENGTH) { index -> QR_ALPHABET[index % QR_ALPHABET.size] }

        val result = manager.pairWithSecret(
            pairingEndpoint = QR_ENDPOINT,
            pairingSecret = PairingSecret(qrChars),
            method = PairingMethod.QR,
        )

        assertSame(result, AdbOperationResult.Cancelled)
        assertCleared(qrChars)
        assertEquals(
            (manager.connectionState.value as AdbConnectionState.Disconnected).reason,
            DisconnectionReason.PAIR_CANCELLED,
        )
    }

    @Test
    fun `pairing while connected preserves the active Session and rejects before protocol pairing`() = runBlocking {
        val client = RecordingClient()
        val factory = RecordingFactory(client = client)
        val manager = DefaultAdbSessionManager(factory, Dispatchers.Unconfined)
        assertTrue(manager.connect(CONNECT_ENDPOINT) is AdbOperationResult.Success)
        val connected = manager.connectionState.value as AdbConnectionState.Connected
        val qrChars = CharArray(QR_PASSWORD_LENGTH) { index -> QR_ALPHABET[index % QR_ALPHABET.size] }

        val result = manager.pairWithSecret(
            pairingEndpoint = QR_ENDPOINT,
            pairingSecret = PairingSecret(qrChars),
            method = PairingMethod.QR,
        )

        assertTrue(result is AdbOperationResult.Failure)
        assertSame((result as AdbOperationResult.Failure).error, AdbError.PairingSessionConflict)
        assertCleared(qrChars)
        assertTrue(factory.calls.isEmpty(), "Conflict must be rejected before Kadb pairing")
        assertFalse(client.closed.get(), "Existing Session client must remain open")
        assertEquals(manager.connectionState.value, connected)
    }

    private fun assertCleared(chars: CharArray) {
        assertTrue(chars.all { it == '\u0000' }, "Pairing characters must be cleared on every terminal path")
    }

    private enum class PairBehavior {
        RETURN,
        WAIT_FOREVER,
        CANCEL,
    }

    private enum class CredentialShape {
        QR_PASSWORD,
        SIX_DIGIT_CODE,
        OTHER,
    }

    private data class PairCall(
        val shape: CredentialShape,
        val length: Int,
    )

    private class RecordingFactory(
        private val client: RecordingClient = RecordingClient(),
        private val pairBehavior: PairBehavior = PairBehavior.RETURN,
    ) : AdbProtocolClientFactory {
        val calls = mutableListOf<PairCall>()
        var openCalls = 0
            private set

        override fun open(endpoint: AdbEndpoint): AdbProtocolClient {
            openCalls += 1
            return client
        }

        override suspend fun pair(endpoint: AdbEndpoint, pairingCode: CharArray) {
            calls += PairCall(
                shape = when {
                    pairingCode.size == SIX_DIGIT_CODE_LENGTH && pairingCode.all(Char::isDigit) -> {
                        CredentialShape.SIX_DIGIT_CODE
                    }
                    pairingCode.size == QR_PASSWORD_LENGTH -> CredentialShape.QR_PASSWORD
                    else -> CredentialShape.OTHER
                },
                length = pairingCode.size,
            )
            when (pairBehavior) {
                PairBehavior.RETURN -> Unit
                PairBehavior.WAIT_FOREVER -> awaitCancellation()
                PairBehavior.CANCEL -> throw CancellationException("synthetic cancellation")
            }
        }

        override fun clearIdentity() = Unit
    }

    private class RecordingClient : AdbProtocolClient {
        val closed = AtomicBoolean(false)

        override fun execute(command: String): ProtocolShellResponse =
            ProtocolShellResponse("ok\n", "", 0, streamsSeparated = true, wasTruncated = false)

        override fun openShellStream(command: String): ProtocolShellStream = object : ProtocolShellStream {
            override fun read(): ProtocolShellPacket = ProtocolShellPacket.Exit(0)
            override fun close() = Unit
        }

        override fun close() {
            closed.set(true)
        }
    }

    private companion object {
        const val QR_PASSWORD_LENGTH = 12
        const val SIX_DIGIT_CODE_LENGTH = 6
        val QR_ALPHABET = charArrayOf('A', 'b', '7', '-', '_')
        val QR_ENDPOINT = AdbEndpoint("qr-target.invalid", 47101)
        val CODE_ENDPOINT = AdbEndpoint("code-target.invalid", 47102)
        val CONNECT_ENDPOINT = AdbEndpoint("connected-target.invalid", 47103)
    }
}
