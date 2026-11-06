package com.xmrip.desktop

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Faux pont : le faux casque [FakeHeadset.xm6] servi en TCP à la place de tools/pc-bridge, pour essayer le banc
 * (`xm.sh`, app macOS) sans casque, ou avec une autre couleur que celle du sien. Un client à la fois, comme le vrai pont.
 *
 *   ./gradlew -Dorg.gradle.configureondemand=true :desktop:runFakeHeadset --args="--port 9877 --color 5"
 *   ./gradlew -Dorg.gradle.configureondemand=true :desktop:run --args="--port 9877 battery"
 *
 * Il ne répond qu'au handshake (firmware, modèle, couleur, batterie, bruit, volume) et acquitte tout le reste : les
 * écritures passent, les lectures de réglages, d'égaliseur ou d'appareils expirent.
 */
fun main(args: Array<String>) {
    var port = 9877
    var color: Int? = 1
    val it = args.iterator()
    while (it.hasNext()) {
        when (it.next()) {
            "--port" -> port = it.next().toInt()
            "--color" -> color = it.next().let { v -> if (v == "none") null else v.toInt() }
        }
    }
    ServerSocket(port, 1, InetAddress.getByName("127.0.0.1")).use { server ->
        println("Faux casque WH-1000XM6 (couleur ${color ?: "muette"}) sur 127.0.0.1:$port")
        while (true) server.accept().use { serve(it, FakeHeadset.xm6(color)) }
    }
}

/** Relaie les octets entre le client et le faux casque jusqu'à ce que le client ferme. */
private fun serve(socket: Socket, fake: FakeHeadset) {
    println("Client connecté")
    val open = AtomicBoolean(true)
    thread(isDaemon = true, name = "fake-rx") {
        val buffer = ByteArray(1024)
        try {
            while (true) {
                val n = socket.getInputStream().read(buffer)
                if (n < 0) break
                fake.output.write(buffer, 0, n)
            }
        } catch (_: Exception) {
        }
        open.set(false)
    }
    val out = socket.getOutputStream()
    val buffer = ByteArray(1024)
    try {
        while (open.get()) {
            val n = fake.input.read(buffer, 0, buffer.size)
            if (n > 0) out.apply { write(buffer, 0, n); flush() } else Thread.sleep(5)
        }
    } catch (_: Exception) {
    }
    println("Client parti (${fake.requests.size} commandes reçues)")
}
