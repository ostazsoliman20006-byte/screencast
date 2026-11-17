package com.screencast.app

import android.util.Log
import java.io.BufferedOutputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class StreamServer(private val port: Int = 8080) {

    companion object {
        private const val TAG = "StreamServer"
        private const val BOUNDARY = "screencastframe"
    }

    private var serverSocket: ServerSocket? = null
    private val running = AtomicBoolean(false)
    private val clients = CopyOnWriteArrayList<OutputStream>()
    private val executor = Executors.newCachedThreadPool()

    val connectedClients: Int
        get() = clients.size

    fun start() {
        if (running.get()) return
        running.set(true)
        executor.execute {
            try {
                serverSocket = ServerSocket(port)
                Log.i(TAG, "خادم البث يعمل على المنفذ $port")
                while (running.get()) {
                    val socket = serverSocket?.accept() ?: break
                    executor.execute { handleClient(socket) }
                }
            } catch (e: Exception) {
                if (running.get()) Log.e(TAG, "خطأ في خادم البث: ${e.message}")
            }
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.getInputStream().bufferedReader().readLine()

            val out = BufferedOutputStream(socket.getOutputStream())
            val header = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: multipart/x-mixed-replace; boundary=$BOUNDARY\r\n" +
                    "Cache-Control: no-cache\r\n" +
                    "Connection: close\r\n\r\n"
            out.write(header.toByteArray())
            out.flush()

            clients.add(out)
            Log.i(TAG, "رسيفر جديد متصل. العدد الحالي: ${clients.size}")
        } catch (e: Exception) {
            Log.e(TAG, "خطأ أثناء اتصال عميل: ${e.message}")
            try { socket.close() } catch (_: Exception) {}
        }
    }

    fun pushFrame(jpegBytes: ByteArray) {
        if (clients.isEmpty()) return

        val frameHeader = "--$BOUNDARY\r\n" +
                "Content-Type: image/jpeg\r\n" +
                "Content-Length: ${jpegBytes.size}\r\n\r\n"

        val deadClients = mutableListOf<OutputStream>()
        for (client in clients) {
            try {
                client.write(frameHeader.toByteArray())
                client.write(jpegBytes)
                client.write("\r\n".toByteArray())
                client.flush()
            } catch (e: Exception) {
                deadClients.add(client)
            }
        }
        if (deadClients.isNotEmpty()) {
            clients.removeAll(deadClients)
            Log.i(TAG, "تم فصل ${deadClients.size} رسيفر. العدد الحالي: ${clients.size}")
        }
    }

    fun stop() {
        running.set(false)
        clients.forEach { try { it.close() } catch (_: Exception) {} }
        clients.clear()
        try { serverSocket?.close() } catch (_: Exception) {}
        executor.shutdownNow()
    }
}
