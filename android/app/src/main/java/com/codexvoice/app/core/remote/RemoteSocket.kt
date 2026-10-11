package com.codexvoice.app.core.remote

import okhttp3.*
import okio.ByteString
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

interface RemoteSocket { fun send(text: String): Boolean; fun close() }
interface SocketObserver { fun opened(); fun message(text: String); fun failed(reason: String) }
fun interface SocketFactory { fun open(address: String, observer: SocketObserver): RemoteSocket }
class OkHttpSocketFactory : SocketFactory {
    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
    override fun open(address: String, observer: SocketObserver): RemoteSocket {
        val socket = client.newWebSocket(Request.Builder().url(address).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = observer.opened()
            override fun onMessage(webSocket: WebSocket, text: String) = observer.message(text)
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) { webSocket.cancel(); observer.failed("invalid_message") }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = observer.failed(if (t is SSLException) "tls_failure" else "unreachable")
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null); observer.failed("closed") }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = observer.failed("closed")
        })
        return object : RemoteSocket {
            override fun send(text: String) = socket.send(text)
            override fun close() { socket.cancel() }
        }
    }
}
