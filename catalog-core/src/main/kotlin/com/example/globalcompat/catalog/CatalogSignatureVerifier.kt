package com.example.globalcompat.catalog

import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

class CatalogSignatureVerifier(
    publicKeyDer: ByteArray,
) {
    private val publicKey = KeyFactory.getInstance(KEY_ALGORITHM)
        .generatePublic(X509EncodedKeySpec(publicKeyDer.copyOf()))

    fun verify(catalogJson: ByteArray, detachedSignature: ByteArray): Boolean = runCatching {
        Signature.getInstance(SIGNATURE_ALGORITHM).run {
            initVerify(publicKey)
            update(catalogJson)
            verify(detachedSignature)
        }
    }.getOrDefault(false)

    companion object {
        private const val KEY_ALGORITHM = "EC"
        private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"

        fun decodeBase64(base64: String): ByteArray {
            val clean = base64.filterNot(Char::isWhitespace).trimEnd('=')
            val output = ArrayList<Byte>(clean.length * 3 / 4)
            var accumulator = 0
            var bitCount = 0
            clean.forEach { character ->
                val value = BASE64_ALPHABET.indexOf(character)
                require(value >= 0) { "Invalid base64 input" }
                accumulator = (accumulator shl 6) or value
                bitCount += 6
                if (bitCount >= 8) {
                    bitCount -= 8
                    output += ((accumulator shr bitCount) and 0xff).toByte()
                }
            }
            return output.toByteArray()
        }

        fun decodePublicKey(base64: String): ByteArray = decodeBase64(base64)

        private const val BASE64_ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    }
}
