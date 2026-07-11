package com.sheen.adb.core.internal

import com.sheen.adb.core.AdbError
import com.sheen.adb.core.AdbOperationStage
import java.io.EOFException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.ProtocolException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

internal object AdbExceptionMapper {
    fun map(error: Throwable, stage: AdbOperationStage): AdbError {
        val causes = generateSequence(error) { it.cause }.take(8).toList()
        return when {
            causes.any { it is SocketTimeoutException } -> AdbError.Timeout(stage)
            causes.any { it is UnknownHostException || it is NoRouteToHostException } ->
                AdbError.NetworkUnreachable(stage)
            causes.any { it is ConnectException } -> AdbError.DeviceRejected(stage)
            causes.any { it is SSLException || it.javaClass.simpleName.contains("Auth", ignoreCase = true) } ->
                AdbError.AuthenticationFailed(stage)
            causes.any { it is EOFException || it.javaClass.simpleName.contains("StreamClosed") } ->
                AdbError.RemoteClosed(stage)
            causes.any { it is ProtocolException || it is IllegalArgumentException } ->
                AdbError.ProtocolIncompatible(stage)
            else -> AdbError.Unknown(stage)
        }
    }

    fun safeTechnicalDetails(error: Throwable, endpoint: com.sheen.adb.core.AdbEndpoint?): String {
        val type = error.javaClass.simpleName.ifBlank { "Throwable" }
        val target = endpoint?.redacted() ?: "<无目标>"
        return "type=$type; target=$target"
    }
}
