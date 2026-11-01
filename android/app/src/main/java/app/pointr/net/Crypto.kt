package app.pointr.net

import android.util.Base64
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Mirrors the Windows side: P-256 ECDH for pairing, HMAC-SHA256 for everything after. */
internal object Crypto {
    fun newKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    fun publicKeyB64(kp: KeyPair): String = b64(kp.public.encoded) // X.509 SubjectPublicKeyInfo

    fun sharedSecret(kp: KeyPair, peerB64: String): ByteArray {
        val peer = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(unb64(peerB64)))
        return KeyAgreement.getInstance("ECDH").run {
            init(kp.private)
            doPhase(peer, true)
            generateSecret()
        }
    }

    fun hmac(key: ByteArray, data: String): ByteArray = hmac(key, data.toByteArray())

    fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(data) }

    fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
    fun unb64(s: String): ByteArray = Base64.decode(s, Base64.DEFAULT)
}
