package com.vilementar.watchinstaller

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import java.util.regex.Pattern

object SdbClient {

    const val DEFAULT_SDB_PORT = 26101

    // Protocol Constants
    private const val A_CNXN = 0x4E584E43
    private const val A_AUTH = 0x48545541
    private const val A_OPEN = 0x4E45504F
    private const val A_OKAY = 0x59414B4F
    private const val A_CLSE = 0x45534C43
    private const val A_WRTE = 0x45545257

    private const val A_VERSION = 0x00100000
    private const val A_MAX_PAYLOAD = 0x00040000 // 256 KB

    private const val AUTH_TOKEN = 1
    private const val AUTH_SIGNATURE = 2
    private const val AUTH_RSAPUBLICKEY = 3

    private const val ID_SEND = 0x444E4553 // "SEND"
    private const val ID_DATA = 0x41544144 // "DATA"
    private const val ID_DONE = 0x454E4F44 // "DONE"
    private const val ID_OKAY = 0x59414B4F // "OKAY"
    private const val ID_FAIL = 0x4C494146 // "FAIL"

    data class SdbPacket(
        val command: Int,
        val arg0: Int,
        val arg1: Int,
        val data: ByteArray
    ) {
        val dataLength: Int = data.size
        val checksum: Int = data.fold(0) { acc, byte -> acc + (byte.toInt() and 0xFF) }
        val magic: Int = command xor -1

        fun serialize(): ByteArray {
            val buf = ByteBuffer.allocate(24 + data.size).order(ByteOrder.LITTLE_ENDIAN)
            buf.putInt(command)
            buf.putInt(arg0)
            buf.putInt(arg1)
            buf.putInt(dataLength)
            buf.putInt(checksum)
            buf.putInt(magic)
            buf.put(data)
            return buf.array()
        }
    }

    class SdbSession(
        private val socket: Socket,
        private val inStream: InputStream,
        private val outStream: OutputStream
    ) : Closeable {
        private var nextLocalId = 1

        @Synchronized
        fun sendPacket(pkt: SdbPacket) {
            outStream.write(pkt.serialize())
            outStream.flush()
        }

        @Synchronized
        fun readPacket(timeoutMs: Int = 20000): SdbPacket {
            socket.soTimeout = timeoutMs
            val hdr = ByteArray(24)
            readFully(hdr)
            val bb = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN)
            val cmd = bb.int
            val arg0 = bb.int
            val arg1 = bb.int
            val len = bb.int
            val chk = bb.int
            val magic = bb.int

            if ((cmd xor -1) != magic) {
                throw IOException("Corrupted packet header magic: cmd=0x${Integer.toHexString(cmd)}")
            }

            val data = ByteArray(len)
            if (len > 0) {
                readFully(data)
            }
            return SdbPacket(cmd, arg0, arg1, data)
        }

        private fun readFully(buffer: ByteArray) {
            var totalRead = 0
            while (totalRead < buffer.size) {
                val r = inStream.read(buffer, totalRead, buffer.size - totalRead)
                if (r < 0) throw EOFException("Unexpected end of stream (read $totalRead / ${buffer.size})")
                totalRead += r
            }
        }

        fun drainOldPackets() {
            try {
                socket.soTimeout = 50
                while (inStream.available() > 0) {
                    readPacket(50)
                }
            } catch (e: Exception) {
                // Expected timeout when fully drained
            }
        }

        fun openStream(service: String): Pair<Int, Int> {
            drainOldPackets()
            val localId = nextLocalId++
            val payload = (service + "\u0000").toByteArray(Charsets.US_ASCII)
            sendPacket(SdbPacket(A_OPEN, localId, 0, payload))

            val deadline = System.currentTimeMillis() + 30000
            while (System.currentTimeMillis() < deadline) {
                val resp = readPacket(10000)
                if (resp.arg1 == localId) {
                    if (resp.command == A_OKAY) {
                        val remoteId = resp.arg0
                        return Pair(localId, remoteId)
                    } else if (resp.command == A_CLSE) {
                        throw IOException("Stream '$service' was immediately closed by watch")
                    }
                }
                // Ack or discard lingering packets from earlier streams
                if (resp.command == A_WRTE) {
                    sendPacket(SdbPacket(A_OKAY, resp.arg1, resp.arg0, ByteArray(0)))
                }
            }
            throw IOException("Timeout waiting for stream '$service' OKAY")
        }

        fun execShell(command: String, onOutputChunk: (String) -> Unit = {}): String {
            val (localId, remoteId) = openStream("shell:$command")
            val sb = StringBuilder()
            val deadline = System.currentTimeMillis() + 120000

            while (System.currentTimeMillis() < deadline) {
                val pkt = readPacket(30000)
                if (pkt.arg1 == localId) {
                    if (pkt.command == A_WRTE) {
                        val str = String(pkt.data, Charsets.UTF_8)
                        sb.append(str)
                        onOutputChunk(str)
                        sendPacket(SdbPacket(A_OKAY, localId, remoteId, ByteArray(0)))
                    } else if (pkt.command == A_CLSE) {
                        sendPacket(SdbPacket(A_CLSE, localId, remoteId, ByteArray(0)))
                        break
                    }
                }
            }
            return sb.toString()
        }

        fun pushBytes(
            remotePath: String,
            data: ByteArray,
            onProgress: (Float) -> Unit
        ) {
            val (localId, remoteId) = openStream("sync:")

            // 1. Send SEND header
            val pathWithMode = "$remotePath,0666"
            val pathBytes = pathWithMode.toByteArray(Charsets.US_ASCII)
            val sendBuf = ByteBuffer.allocate(8 + pathBytes.size).order(ByteOrder.LITTLE_ENDIAN)
            sendBuf.putInt(ID_SEND)
            sendBuf.putInt(pathBytes.size)
            sendBuf.put(pathBytes)

            sendPacket(SdbPacket(A_WRTE, localId, remoteId, sendBuf.array()))
            val respSend = readPacketForStream(localId, 30000)
            if (respSend.command != A_OKAY) throw IOException("Failed to send sync SEND: response was 0x${Integer.toHexString(respSend.command)}")

            // 2. Stream DATA chunks (16 KB chunks for high reliability and throughput)
            val chunkSize = 16384
            var sent = 0
            while (sent < data.size) {
                val len = Math.min(chunkSize, data.size - sent)
                val dataBuf = ByteBuffer.allocate(8 + len).order(ByteOrder.LITTLE_ENDIAN)
                dataBuf.putInt(ID_DATA)
                dataBuf.putInt(len)
                dataBuf.put(data, sent, len)

                sendPacket(SdbPacket(A_WRTE, localId, remoteId, dataBuf.array()))
                val respData = readPacketForStream(localId, 30000)
                if (respData.command != A_OKAY) throw IOException("Failed to send sync DATA chunk")

                sent += len
                onProgress(sent.toFloat() / data.size)
            }

            // 3. Send DONE packet
            val doneBuf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            doneBuf.putInt(ID_DONE)
            doneBuf.putInt((System.currentTimeMillis() / 1000).toInt())
            sendPacket(SdbPacket(A_WRTE, localId, remoteId, doneBuf.array()))
            val respDone = readPacketForStream(localId, 30000)
            if (respDone.command != A_OKAY) throw IOException("Failed to send sync DONE")

            // 4. Read server OKAY / FAIL response
            val replyPkt = readPacketForStream(localId, 30000)
            if (replyPkt.command == A_WRTE) {
                sendPacket(SdbPacket(A_OKAY, localId, remoteId, ByteArray(0)))
                val bb = ByteBuffer.wrap(replyPkt.data).order(ByteOrder.LITTLE_ENDIAN)
                val status = bb.int
                if (status != ID_OKAY) {
                    val msgLen = if (replyPkt.data.size >= 8) bb.int else 0
                    val errMsg = if (msgLen > 0 && replyPkt.data.size >= 8 + msgLen) {
                        String(replyPkt.data, 8, msgLen, Charsets.UTF_8)
                    } else "Push failed"
                    throw IOException("Watch rejected file upload: $errMsg")
                }
            }

            // 5. Close sync stream
            sendPacket(SdbPacket(A_CLSE, localId, remoteId, ByteArray(0)))
            try {
                readPacketForStream(localId, 3000)
            } catch (e: Exception) {
                // Handled gracefully
            }
        }

        private fun readPacketForStream(targetLocalId: Int, timeoutMs: Int): SdbPacket {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val rem = Math.max(1000, (deadline - System.currentTimeMillis()).toInt())
                val pkt = readPacket(rem)
                if (pkt.arg1 == targetLocalId) {
                    return pkt
                }
                if (pkt.command == A_WRTE) {
                    sendPacket(SdbPacket(A_OKAY, pkt.arg1, pkt.arg0, ByteArray(0)))
                }
            }
            throw IOException("Timeout waiting for packet for stream $targetLocalId")
        }

        override fun close() {
            try { inStream.close() } catch (e: Exception) {}
            try { outStream.close() } catch (e: Exception) {}
            try { socket.close() } catch (e: Exception) {}
        }
    }

    /**
     * Finds active Wi-Fi Network on Android and binds the process and sockets to it,
     * preventing cellular/mobile data from intercepting local watch communication.
     */
    fun getWifiNetwork(context: Context?): Network? {
        if (context == null) return null
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        for (net in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(net) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                return net
            }
        }
        return null
    }

    fun bindToWifi(context: Context?): Boolean {
        if (context == null) return false
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val wifiNet = getWifiNetwork(context) ?: return false
        return cm.bindProcessToNetwork(wifiNet)
    }

    fun getLocalSubnet(context: Context?): String {
        bindToWifi(context)
        // 1. First inspect wlan0 directly
        try {
            val wlan = NetworkInterface.getByName("wlan0")
            if (wlan != null && wlan.isUp) {
                for (addr in Collections.list(wlan.inetAddresses)) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val host = addr.hostAddress ?: continue
                        val parts = host.split(".")
                        if (parts.size == 4) {
                            return "${parts[0]}.${parts[1]}.${parts[2]}"
                        }
                    }
                }
            }
        } catch (e: Exception) {}

        // 2. Fallback across all interfaces
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                if (intf.isLoopback || !intf.isUp) continue
                for (addr in Collections.list(intf.inetAddresses)) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val host = addr.hostAddress ?: continue
                        if (host.startsWith("192.168.")) {
                            val parts = host.split(".")
                            return "${parts[0]}.${parts[1]}.${parts[2]}"
                        }
                    }
                }
            }
        } catch (e: Exception) {}

        return "192.168.1"
    }

    suspend fun scanSubnet(
        context: Context,
        subnetPrefix: String,
        port: Int = DEFAULT_SDB_PORT,
        onProgress: (Float) -> Unit
    ): List<String> = withContext(Dispatchers.IO) {
        bindToWifi(context)
        val foundIps = mutableListOf<String>()
        val total = 254
        var completed = 0

        // Chunk in groups of 25 to avoid overwhelming socket table on mobile OS
        val chunks = (1..total).chunked(25)
        for (chunk in chunks) {
            val tasks = chunk.map { hostId ->
                async {
                    val ip = "$subnetPrefix.$hostId"
                    val isOpen = probePort(context, ip, port, timeoutMs = 700)
                    synchronized(this@SdbClient) {
                        completed++
                        onProgress(completed.toFloat() / total)
                    }
                    if (isOpen) ip else null
                }
            }
            val results = tasks.awaitAll().filterNotNull()
            foundIps.addAll(results)
        }
        foundIps
    }

    fun probePort(context: Context?, ip: String, port: Int = DEFAULT_SDB_PORT, timeoutMs: Int = 1200): Boolean {
        return try {
            val wifiNet = getWifiNetwork(context)
            val sock = Socket()
            wifiNet?.bindSocket(sock)
            sock.connect(InetSocketAddress(ip, port), timeoutMs)
            sock.close()
            true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun connectAndAuthorize(
        context: Context,
        ip: String,
        port: Int = DEFAULT_SDB_PORT,
        log: (String) -> Unit
    ): SdbSession = withContext(Dispatchers.IO) {
        val isLoopback = (ip == "127.0.0.1" || ip == "localhost")
        if (!isLoopback) {
            bindToWifi(context)
            log("Connecting to $ip:$port over Wi-Fi...")
        } else {
            log("Connecting to local SDB bridge at $ip:$port...")
        }

        val sock = Socket()
        if (!isLoopback) {
            val wifiNet = getWifiNetwork(context)
            wifiNet?.bindSocket(sock)
        }
        sock.connect(InetSocketAddress(ip, port), 15000)
        sock.tcpNoDelay = true

        val inStream = sock.getInputStream()
        val outStream = sock.getOutputStream()
        val session = SdbSession(sock, inStream, outStream)

        try {
            // 1. Send A_CNXN
            val hostPayload = "host::\u0000".toByteArray(Charsets.US_ASCII)
            val cnxnPacket = SdbPacket(A_CNXN, A_VERSION, A_MAX_PAYLOAD, hostPayload)
            session.sendPacket(cnxnPacket)

            // 2. Read response
            var resp = session.readPacket(20000)

            // 3. Handle AUTH loop
            val keyData = SdbKeyManager.getKeyData(context)

            while (resp.command == A_AUTH && resp.arg0 == AUTH_TOKEN) {
                log("Watch sent authentication challenge (${resp.data.size} bytes token)")
                val signature = SdbKeyManager.signToken(keyData.privateKey, resp.data)

                // Send signed token
                val authSigPacket = SdbPacket(A_AUTH, AUTH_SIGNATURE, 0, signature)
                session.sendPacket(authSigPacket)

                resp = session.readPacket(30000)

                if (resp.command == A_AUTH && resp.arg0 == AUTH_TOKEN) {
                    log("Key not yet authorized on watch. Sending RSA public key...")
                    log("👉 CHECK WATCH DISPLAY NOW: Tap 'OK / Allow' to confirm debugging!")

                    val pubPayload = (keyData.adbPublicKeyString + "\u0000").toByteArray(Charsets.US_ASCII)
                    val authPubPacket = SdbPacket(A_AUTH, AUTH_RSAPUBLICKEY, 0, pubPayload)
                    session.sendPacket(authPubPacket)

                    resp = session.readPacket(45000)
                }
            }

            if (resp.command != A_CNXN) {
                throw IOException("Authentication failed: expected CNXN, got 0x${Integer.toHexString(resp.command)}")
            }

            val banner = String(resp.data, Charsets.US_ASCII).trim()
            log("✔ Authorized successfully with watch ($banner)")
            session
        } catch (e: Exception) {
            session.close()
            throw e
        }
    }

    /**
     * Executes the installation escalation chain with Mount-Install (-w) as the PRIMARY DEFAULT method,
     * as requested. If mount-install fails, it automatically falls back to standard, debug, and reinstall methods.
     */
    suspend fun installPackage(
        context: Context,
        ip: String,
        port: Int = DEFAULT_SDB_PORT,
        appInfo: TizenAppInfo,
        onLog: (String) -> Unit,
        onProgress: (Float) -> Unit
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val fileName = appInfo.fileName
        val ptype = if (fileName.endsWith(".wgt", ignoreCase = true)) "wgt" else "tpk"
        val rpath = "/opt/usr/apps/tmp/$fileName"

        var session: SdbSession? = null
        try {
            session = connectAndAuthorize(context, ip, port, onLog)

            // 1. Sync device-profile.xml if available
            try {
                val profileBytes = context.assets.open("device-profile.xml").use { it.readBytes() }
                onLog("Synchronizing device-profile.xml...")
                session.execShell("mkdir -p /home/owner/share/tmp/sdk_tools && chmod 777 /home/owner/share/tmp/sdk_tools")
                session.pushBytes("/home/owner/share/tmp/sdk_tools/device-profile.xml", profileBytes) {}
                session.execShell("chmod 666 /home/owner/share/tmp/sdk_tools/device-profile.xml")
            } catch (e: Exception) {
                // Optional
            }

            // 2. Prepare remote tmp dir and push package
            onLog("Pushing $fileName (${appInfo.rawBytes.size / 1024} KB) to watch storage...")
            session.execShell("mkdir -p /opt/usr/apps/tmp && chmod 777 /opt/usr/apps/tmp")
            session.pushBytes(rpath, appInfo.rawBytes, onProgress)
            session.execShell("chmod 666 $rpath")
            onLog("File transfer completed successfully.")

            // 3. Escalation Chain — MOUNT-INSTALL (-w) IS DEFAULT!
            var installedMethod = ""
            var success = false

            // ── Method 1 (DEFAULT): Sideload Mount-Install Bypass (-w) ──
            onLog("  [1/4] Mount-install bypass (pkgcmd -i -w) [DEFAULT]...")
            val out1 = session.execShell("pkgcmd -i -t $ptype -p $rpath -w") { chunk ->
                parseLogProgress(chunk, onLog)
            }
            if (isPkgcmdSuccess(out1)) {
                success = true
                installedMethod = "Mount-Install Bypass (-w)"
            } else {
                val err1 = extractPkgcmdError(out1)
                onLog("      Mount-install failed: $err1. Trying fallback methods...")

                // ── Method 2 (Fallback): Standard Install (pkgcmd -i) ──
                onLog("  [2/4] Fallback: Standard install (pkgcmd -i)...")
                val out2 = session.execShell("pkgcmd -i -t $ptype -p $rpath") { chunk ->
                    parseLogProgress(chunk, onLog)
                }
                if (isPkgcmdSuccess(out2)) {
                    success = true
                    installedMethod = "Standard Install"
                } else {
                    val err2 = extractPkgcmdError(out2)
                    onLog("      Standard install failed: $err2")

                    // ── Method 3 (Fallback): Debug mode (-G) ──
                    onLog("  [3/4] Fallback: Debug mode (-G)...")
                    val out3 = session.execShell("pkgcmd -i -t $ptype -p $rpath -G") { chunk ->
                        parseLogProgress(chunk, onLog)
                    }
                    if (isPkgcmdSuccess(out3)) {
                        success = true
                        installedMethod = "Debug Mode (-G)"
                    } else {
                        val err3 = extractPkgcmdError(out3)
                        onLog("      Debug install failed: $err3")

                        // ── Method 4 (Fallback): Clean reinstall with mount-install ──
                        onLog("  [4/4] Fallback: Clean reinstall (uninstalling ${appInfo.packageId})...")
                        session.execShell("pkgcmd -u -n ${appInfo.packageId}")
                        val out4 = session.execShell("pkgcmd -i -t $ptype -p $rpath -w") { chunk ->
                            parseLogProgress(chunk, onLog)
                        }
                        if (isPkgcmdSuccess(out4)) {
                            success = true
                            installedMethod = "Clean Reinstall"
                        }
                    }
                }
            }

            // Cleanup remote tmp file
            try {
                session.execShell("rm -f $rpath")
            } catch (e: Exception) {}

            if (success) {
                onLog("🎉 ZAINSTALOWANO POMYŚLNIE via $installedMethod!")

                // Auto-launch watch face if applicable
                if (appInfo.isWatchFace) {
                    onLog("Setting active watch face: ${appInfo.packageId}...")
                    session.execShell("launch_app com.samsung.w-home watchface wf_set wf_id \"${appInfo.packageId}\"")
                    onLog("✔ Watch face activated on watch display!")
                }
                return@withContext Pair(true, installedMethod)
            } else {
                onLog("❌ All installation methods failed. Check certificates or device settings.")
                return@withContext Pair(false, "All installation methods failed")
            }
        } finally {
            session?.close()
        }
    }

    private fun parseLogProgress(chunk: String, onLog: (String) -> Unit) {
        val p = Pattern.compile("val\\[(\\d+)\\]")
        val m = p.matcher(chunk)
        while (m.find()) {
            val pct = m.group(1)?.toIntOrNull() ?: continue
            if (pct in 1..99 && pct % 20 == 0) {
                onLog("    Installing: $pct% ...")
            }
        }
    }

    private fun isPkgcmdSuccess(output: String): Boolean {
        if (output.contains("key[end] val[ok]")) return true
        if (output.contains("val[100]") && !output.contains("key[error]") && !output.contains("val[fail]")) return true
        if (output.contains("spend time for pkgcmd is") && !output.contains("key[error]") && !output.contains("val[fail]")) return true
        return false
    }

    private fun extractPkgcmdError(output: String): String {
        val p = Pattern.compile("key\\[error\\]\\s+val\\[(-?\\d+)\\](?:\\s+error message:\\s*:?([^\\n\\r]+))?")
        val m = p.matcher(output)
        if (m.find()) {
            val code = m.group(1) ?: "?"
            val msg = m.group(2)?.trim() ?: ""
            return "code $code ($msg)"
        }
        return "Unknown error"
    }
}
