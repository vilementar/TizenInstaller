package com.vilementar.watchinstaller

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import kotlin.concurrent.thread

/**
 * Manages the Bluetooth RFCOMM SDB bridge to Samsung Galaxy Watches.
 * Reverse engineered directly from Samsung's official sdboverbt app (sdboverbt_160523.apk).
 *
 * Sequence:
 * 1. Wakeup watch SDB daemon via UUID 39E9AE15-62E4-4529-9019-8A2C07A27051 (connect & close).
 * 2. Delay 1000ms.
 * 3. Connect to SDB RFCOMM daemon via UUID b6a09fda-886e-45ad-9c36-5050db58c8ff.
 * 4. Spin up local loopback TCP proxy on 127.0.0.1:26101 bridging SDB client to RFCOMM socket.
 */
object BtBridgeManager : Closeable {

    private const val SDB_INIT_UUID_STR = "39E9AE15-62E4-4529-9019-8A2C07A27051"
    private const val SDB_UUID_STR = "b6a09fda-886e-45ad-9c36-5050db58c8ff"

    private val SDB_INIT_UUID = UUID.fromString(SDB_INIT_UUID_STR)
    private val SDB_UUID = UUID.fromString(SDB_UUID_STR)

    private var activeBtSocket: BluetoothSocket? = null
    private var activeServerSocket: ServerSocket? = null
    private var activeClientSocket: Socket? = null
    private var isBridgeRunning = false

    const val LOCAL_BRIDGE_PORT = 26101

    val isConnected: Boolean
        get() = activeBtSocket?.isConnected == true && isBridgeRunning

    @SuppressLint("MissingPermission")
    suspend fun startBridge(
        device: BluetoothDevice,
        onLog: (String) -> Unit
    ): Int = withContext(Dispatchers.IO) {
        stopBridge()

        val devName = try { device.name ?: device.address } catch (e: Exception) { device.address }
        onLog("Initializing SDB over Bluetooth with $devName...")

        // Step 1: Wake up watch SDB daemon via SDB_INIT_UUID
        try {
            onLog("Sending SDB wakeup signal to watch...")
            val initSocket = try {
                device.createRfcommSocketToServiceRecord(SDB_INIT_UUID)
            } catch (e: Exception) {
                device.createInsecureRfcommSocketToServiceRecord(SDB_INIT_UUID)
            }
            initSocket.connect()
            initSocket.close()
            onLog("✔ Watch SDB wakeup signal acknowledged.")
        } catch (e: Exception) {
            onLog("Wakeup trigger note: ${e.message ?: "daemon may already be active"}")
        }

        // Wait 1 second as official sdboverbt does
        try { Thread.sleep(1000) } catch (e: Exception) {}

        // Step 2: Connect to SDB RFCOMM daemon
        onLog("Connecting to SDB RFCOMM daemon on watch...")
        val btSocket = try {
            val sock = device.createRfcommSocketToServiceRecord(SDB_UUID)
            sock.connect()
            sock
        } catch (e1: Exception) {
            onLog("Secure RFCOMM connect failed (${e1.message}), attempting insecure RFCOMM fallback...")
            val sock2 = device.createInsecureRfcommSocketToServiceRecord(SDB_UUID)
            sock2.connect()
            sock2
        }

        activeBtSocket = btSocket
        onLog("✔ Bluetooth RFCOMM channel established with watch!")

        // Step 3: Start local loopback TCP proxy on 127.0.0.1:26101
        val server = try {
            ServerSocket(LOCAL_BRIDGE_PORT, 5, InetAddress.getByName("127.0.0.1"))
        } catch (e: Exception) {
            ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"))
        }
        val port = server.localPort
        activeServerSocket = server
        isBridgeRunning = true

        onLog("Local SDB bridge listening on 127.0.0.1:$port")

        // Step 4: Launch proxy listener thread
        thread(name = "BtSdbProxy-Accept", isDaemon = true) {
            try {
                while (isBridgeRunning && !server.isClosed) {
                    val clientSock = server.accept()
                    activeClientSocket = clientSock
                    clientSock.tcpNoDelay = true

                    // Pipe: Client (TCP) -> Watch (Bluetooth)
                    thread(name = "TCP-to-BT", isDaemon = true) {
                        val buf = ByteArray(16384)
                        try {
                            val tcpIn = DataInputStream(clientSock.getInputStream())
                            val btOut = DataOutputStream(btSocket.outputStream)
                            while (isBridgeRunning && !clientSock.isClosed && btSocket.isConnected) {
                                val n = tcpIn.read(buf)
                                if (n < 0) break
                                btOut.write(buf, 0, n)
                                btOut.flush()
                            }
                        } catch (e: Exception) {}
                    }

                    // Pipe: Watch (Bluetooth) -> Client (TCP)
                    thread(name = "BT-to-TCP", isDaemon = true) {
                        val buf = ByteArray(16384)
                        try {
                            val btIn = DataInputStream(btSocket.inputStream)
                            val tcpOut = DataOutputStream(clientSock.getOutputStream())
                            while (isBridgeRunning && !clientSock.isClosed && btSocket.isConnected) {
                                val n = btIn.read(buf)
                                if (n < 0) break
                                tcpOut.write(buf, 0, n)
                                tcpOut.flush()
                            }
                        } catch (e: Exception) {}
                    }
                }
            } catch (e: Exception) {
                // Server closed
            }
        }

        port
    }

    override fun close() {
        stopBridge()
    }

    fun stopBridge() {
        isBridgeRunning = false
        try { activeClientSocket?.close() } catch (e: Exception) {}
        try { activeServerSocket?.close() } catch (e: Exception) {}
        try { activeBtSocket?.close() } catch (e: Exception) {}
        activeClientSocket = null
        activeServerSocket = null
        activeBtSocket = null
    }
}
