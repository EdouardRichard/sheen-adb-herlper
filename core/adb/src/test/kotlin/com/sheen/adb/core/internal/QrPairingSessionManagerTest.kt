package com.sheen.adb.core.internal

import com.sheen.adb.core.AdbConnectionState
import com.sheen.adb.core.AdbEndpoint
import com.sheen.adb.core.AdbError
import com.sheen.adb.core.AdbOperationResult
import com.sheen.adb.core.DisconnectionReason
import com.sheen.adb.core.PairingAttemptId
import com.sheen.adb.core.PairingMethod
import com.sheen.adb.core.PairingSecret
import com.sheen.adb.core.QrPairingMaterial
import com.sheen.adb.core.WirelessAddress
import com.sheen.adb.core.WirelessObservationId
import com.sheen.adb.core.WirelessServiceObservation
import com.sheen.adb.core.WirelessServiceStatus
import com.sheen.adb.core.WirelessServiceType
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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

    @Test
    fun `manager owns QR material and only exact resolved observation reaches pairing without connecting`() = runBlocking {
        val factory = RecordingFactory()
        val manager = qrManager(factory)
        val attemptId = PairingAttemptId.of("manager-attempt-success")
        val created = manager.createQrPairingAttempt(attemptId)
        assertTrue(created is AdbOperationResult.Success<*>)
        val material = (created as AdbOperationResult.Success<QrPairingMaterial>).value
        val serviceName = serviceNameFrom(material)

        val wrong = manager.pairQrObservation(
            attemptId,
            resolvedPairingObservation("different-synthetic-service"),
        )
        assertTrue(wrong is AdbOperationResult.Failure)
        assertTrue(material.payload != null, "An unrelated observation must not consume the active material")
        assertTrue(factory.calls.isEmpty())

        val paired = manager.pairQrObservation(attemptId, resolvedPairingObservation(serviceName))

        assertTrue(paired is AdbOperationResult.Success<*>)
        assertTrue(factory.calls.single().shape == CredentialShape.QR_PASSWORD)
        assertEquals(factory.calls.single().length, STANDARD_QR_PASSWORD_LENGTH)
        assertEquals(factory.openCalls, 0, "QR authorization must not open an ADB Session")
        assertEquals(manager.connectionState.value, AdbConnectionState.Disconnected())
        assertEquals(material.payload, null, "The same public material reference must be invalidated at terminal state")
    }

    @Test
    fun `cancelled and stale QR attempts cannot consume a replacement attempt`() = runBlocking {
        val factory = RecordingFactory()
        val manager = qrManager(factory)
        val firstId = PairingAttemptId.of("manager-attempt-old")
        val first = (
            manager.createQrPairingAttempt(firstId) as AdbOperationResult.Success<QrPairingMaterial>
        ).value
        val firstService = serviceNameFrom(first)

        assertTrue(manager.cancelQrPairing(firstId) is AdbOperationResult.Success<*>)
        assertEquals(first.payload, null)
        val replacementId = PairingAttemptId.of("manager-attempt-replacement")
        val replacement = (
            manager.createQrPairingAttempt(replacementId) as AdbOperationResult.Success<QrPairingMaterial>
        ).value

        val stale = manager.pairQrObservation(firstId, resolvedPairingObservation(firstService))

        assertTrue(stale is AdbOperationResult.Failure)
        assertTrue(factory.calls.isEmpty())
        assertTrue(replacement.payload != null, "A stale attempt must not invalidate its replacement")
        manager.close()
        assertEquals(replacement.payload, null, "Manager close must invalidate retained QR material")
    }

    @Test
    fun `QR protocol timeout invalidates material and returns a structured timeout`() = runBlocking {
        val factory = RecordingFactory(pairBehavior = PairBehavior.WAIT_FOREVER)
        val manager = qrManager(factory)
        val attemptId = PairingAttemptId.of("manager-attempt-timeout")
        val material = (
            manager.createQrPairingAttempt(attemptId) as AdbOperationResult.Success<QrPairingMaterial>
        ).value
        val serviceName = serviceNameFrom(material)

        val result = manager.pairQrObservation(
            attemptId,
            resolvedPairingObservation(serviceName),
            timeout = 25.milliseconds,
        )

        assertTrue(result is AdbOperationResult.Failure)
        assertTrue((result as AdbOperationResult.Failure).error is AdbError.Timeout)
        assertEquals(material.payload, null)
        assertEquals(factory.calls.single().length, STANDARD_QR_PASSWORD_LENGTH)
    }

    @Test
    fun `active Session rejects QR attempt creation without replacing the Session`() = runBlocking {
        val client = RecordingClient()
        val factory = RecordingFactory(client = client)
        val manager = qrManager(factory)
        assertTrue(manager.connect(CONNECT_ENDPOINT) is AdbOperationResult.Success)
        val connected = manager.connectionState.value as AdbConnectionState.Connected

        val result = manager.createQrPairingAttempt(PairingAttemptId.of("manager-attempt-conflict"))

        assertTrue(result is AdbOperationResult.Failure)
        assertSame((result as AdbOperationResult.Failure).error, AdbError.PairingSessionConflict)
        assertEquals(manager.connectionState.value, connected)
        assertFalse(client.closed.get())
        assertTrue(factory.calls.isEmpty())
    }

    @Test
    fun `cancellation wins over a later protocol success and invalidates material once`() = runBlocking {
        val factory = RecordingFactory(pairBehavior = PairBehavior.BLOCK_UNTIL_RELEASE)
        val manager = qrManager(factory)
        val attemptId = PairingAttemptId.of("manager-attempt-cancel-race")
        val material = (
            manager.createQrPairingAttempt(attemptId) as AdbOperationResult.Success<QrPairingMaterial>
        ).value
        val observation = resolvedPairingObservation(serviceNameFrom(material))
        val pairing = async { manager.pairQrObservation(attemptId, observation) }
        factory.pairStarted.await()

        val cancelled = manager.cancelQrPairing(attemptId)
        factory.pairRelease.complete(Unit)
        val pairingResult = pairing.await()

        assertTrue(cancelled is AdbOperationResult.Success<*>)
        assertSame(pairingResult, AdbOperationResult.Cancelled)
        assertEquals(material.payload, null)
        assertEquals(factory.calls.single().length, STANDARD_QR_PASSWORD_LENGTH)
    }

    private fun qrManager(factory: RecordingFactory): DefaultAdbSessionManager = DefaultAdbSessionManager(
        clientFactory = factory,
        ioDispatcher = Dispatchers.Unconfined,
    )

    private fun serviceNameFrom(material: QrPairingMaterial): String =
        checkNotNull(material.payload).substringAfter(";S:").substringBefore(";P:")

    private fun resolvedPairingObservation(serviceName: String): WirelessServiceObservation = WirelessServiceObservation(
        observationId = WirelessObservationId("synthetic-observation-${serviceName.hashCode()}"),
        serviceType = WirelessServiceType.PAIRING,
        serviceName = serviceName,
        addresses = listOf(WirelessAddress.Ipv4(192, 0, 2, 10)),
        port = 47111,
        status = WirelessServiceStatus.RESOLVED,
        lastSeenAt = 1_000L,
    )

    private fun assertCleared(chars: CharArray) {
        assertTrue(chars.all { it == '\u0000' }, "Pairing characters must be cleared on every terminal path")
    }

    private enum class PairBehavior {
        RETURN,
        WAIT_FOREVER,
        CANCEL,
        BLOCK_UNTIL_RELEASE,
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
        val pairStarted = CompletableDeferred<Unit>()
        val pairRelease = CompletableDeferred<Unit>()
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
                    pairingCode.isNotEmpty() -> CredentialShape.QR_PASSWORD
                    else -> CredentialShape.OTHER
                },
                length = pairingCode.size,
            )
            pairStarted.complete(Unit)
            when (pairBehavior) {
                PairBehavior.RETURN -> Unit
                PairBehavior.WAIT_FOREVER -> awaitCancellation()
                PairBehavior.CANCEL -> throw CancellationException("synthetic cancellation")
                PairBehavior.BLOCK_UNTIL_RELEASE -> pairRelease.await()
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
        const val QR_PASSWORD_LENGTH = 19
        const val STANDARD_QR_PASSWORD_LENGTH = 12
        const val SIX_DIGIT_CODE_LENGTH = 6
        val QR_ALPHABET = charArrayOf('A', 'b', '7', '-', '_')
        val QR_ENDPOINT = AdbEndpoint("qr-target.invalid", 47101)
        val CODE_ENDPOINT = AdbEndpoint("code-target.invalid", 47102)
        val CONNECT_ENDPOINT = AdbEndpoint("connected-target.invalid", 47103)
    }
}
