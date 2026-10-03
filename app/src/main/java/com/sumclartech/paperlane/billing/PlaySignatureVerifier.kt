package com.sumclartech.paperlane.billing

import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/** Verifies the original Play payload, never a reconstructed or modified JSON object. */
class PlaySignatureVerifier(base64PublicKey: String) {
    private val publicKey: PublicKey? = runCatching {
        val normalized = base64PublicKey.filterNot(Char::isWhitespace)
        require(normalized.isNotBlank())
        KeyFactory.getInstance("RSA").generatePublic(
            X509EncodedKeySpec(Base64.getDecoder().decode(normalized)),
        )
    }.getOrNull()

    val isConfigured: Boolean get() = publicKey != null

    fun verify(signedData: String, base64Signature: String): Boolean {
        val key = publicKey ?: return false
        if (signedData.isBlank() || base64Signature.isBlank()) return false
        return runCatching {
            val verifier = Signature.getInstance("SHA1withRSA")
            verifier.initVerify(key)
            verifier.update(signedData.toByteArray(Charsets.UTF_8))
            verifier.verify(Base64.getDecoder().decode(base64Signature))
        }.getOrDefault(false)
    }
}
