package com.vilementar.watchinstaller

import android.content.Context
import android.util.Base64
import java.io.File
import java.io.InputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

object SdbKeyManager {

    private const val KEY_FILE_NAME = "sdbkey_internal.pk8"
    private const val PUB_FILE_NAME = "sdbkey_internal.pub"

    data class KeyData(
        val privateKey: PrivateKey,
        val adbPublicKeyString: String
    )

    private var cachedKey: KeyData? = null

    @Synchronized
    fun getKeyData(context: Context): KeyData {
        cachedKey?.let { return it }

        // 1. Try bundled asset sdbkey.pk8 (pre-authorized from developer PC)
        try {
            val assetMgr = context.assets
            val pk8Bytes = assetMgr.open("sdbkey.pk8").use { it.readBytes() }
            val pubStr = try {
                assetMgr.open("sdbkey.pub").use { it.bufferedReader().readText().trim() }
            } catch (e: Exception) {
                null
            }

            val kf = KeyFactory.getInstance("RSA")
            val privKey = kf.generatePrivate(PKCS8EncodedKeySpec(pk8Bytes)) as RSAPrivateKey

            val adbPub = pubStr ?: buildAdbPublicKey(privKey)
            val kd = KeyData(privKey, adbPub)
            cachedKey = kd
            return kd
        } catch (e: Exception) {
            // Assets not found or invalid, fall back to storage
        }

        // 2. Check local private storage
        val keyFile = File(context.filesDir, KEY_FILE_NAME)
        val pubFile = File(context.filesDir, PUB_FILE_NAME)

        if (keyFile.exists() && pubFile.exists()) {
            try {
                val kf = KeyFactory.getInstance("RSA")
                val privKey = kf.generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
                val pubStr = pubFile.readText().trim()
                val kd = KeyData(privKey, pubStr)
                cachedKey = kd
                return kd
            } catch (e: Exception) {
                keyFile.delete()
                pubFile.delete()
            }
        }

        // 3. Generate new 2048-bit RSA key pair
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val kp: KeyPair = kpg.generateKeyPair()

        val priv = kp.private as RSAPrivateKey
        val pub = kp.public as RSAPublicKey

        val adbPubStr = buildAdbPublicKey(priv)
        try {
            keyFile.writeBytes(priv.encoded)
            pubFile.writeText(adbPubStr)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val kd = KeyData(priv, adbPubStr)
        cachedKey = kd
        return kd
    }

    /**
     * Signs the 20-byte challenge token sent by the watch with SHA-1 DigestInfo prefix,
     * matching OpenSSL's RSA_sign(NID_sha1, token, 20, ...)
     */
    fun signToken(privateKey: PrivateKey, token: ByteArray): ByteArray {
        val sha1Prefix = byteArrayOf(
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
        )
        val digestInfo = ByteArray(sha1Prefix.size + token.size)
        System.arraycopy(sha1Prefix, 0, digestInfo, 0, sha1Prefix.size)
        System.arraycopy(token, 0, digestInfo, sha1Prefix.size, token.size)

        val signer = Signature.getInstance("NONEwithRSA")
        signer.initSign(privateKey)
        signer.update(digestInfo)
        return signer.sign()
    }

    /**
     * Converts an RSA key into the standard ADB / SDB public key struct (524 bytes) in base64:
     * struct RSAPublicKey {
     *     int len;              // 64 words (2048 / 32)
     *     uint32_t n0inv;       // -1 / N[0] mod 2^32
     *     uint32_t n[64];       // modulus
     *     uint32_t rr[64];      // (2^2048)^2 mod n
     *     int exponent;         // e (65537)
     * }
     */
    private fun buildAdbPublicKey(privKey: RSAPrivateKey): String {
        val n = privKey.modulus
        val e = BigInteger.valueOf(65537)

        val words = 64
        val r32 = BigInteger.ONE.shiftLeft(32)
        val n0 = n.remainder(r32)

        // n0inv = -(n0^-1 mod 2^32) mod 2^32
        val inv = n0.modInverse(r32)
        val n0inv = inv.negate().remainder(r32).let { if (it.signum() < 0) it.add(r32) else it }.toLong()

        // rr = (2^2048)^2 mod n
        val r = BigInteger.ONE.shiftLeft(words * 32)
        val rr = r.multiply(r).mod(n)

        val buf = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(words)
        buf.putInt(n0inv.toInt())

        // Modulus words (little-endian array of 32-bit words)
        val nWords = toLittleEndianWords(n, words)
        for (w in nWords) buf.putInt(w)

        // RR words
        val rrWords = toLittleEndianWords(rr, words)
        for (w in rrWords) buf.putInt(w)

        // Exponent
        buf.putInt(e.toInt())

        val b64 = Base64.encodeToString(buf.array(), Base64.NO_WRAP)
        return "$b64 unknown@unknown"
    }

    private fun toLittleEndianWords(value: BigInteger, wordCount: Int): IntArray {
        val result = IntArray(wordCount)
        var rem = value
        val mask = BigInteger.valueOf(0xFFFFFFFFL)
        val shift32 = BigInteger.ONE.shiftLeft(32)

        for (i in 0 until wordCount) {
            val wordVal = rem.and(mask).toLong()
            result[i] = wordVal.toInt()
            rem = rem.shiftRight(32)
        }
        return result
    }
}
