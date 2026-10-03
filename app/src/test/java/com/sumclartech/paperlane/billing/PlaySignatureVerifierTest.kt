package com.sumclartech.paperlane.billing

import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaySignatureVerifierTest {
    @Test fun authenticSignaturePassesAndPayloadChangesFail() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val payload = "{\"packageName\":\"com.sumclartech.pdfix\",\"purchaseState\":0}"
        val signer = Signature.getInstance("SHA1withRSA").apply {
            initSign(pair.private)
            update(payload.toByteArray(Charsets.UTF_8))
        }
        val signature = Base64.getEncoder().encodeToString(signer.sign())
        val verifier = PlaySignatureVerifier(Base64.getEncoder().encodeToString(pair.public.encoded))
        assertTrue(verifier.isConfigured)
        assertTrue(verifier.verify(payload, signature))
        assertFalse(verifier.verify(payload.replace(":0", ":2"), signature))
        assertFalse(verifier.verify(payload, "not base64!"))
    }

    @Test fun missingOrMalformedPublicKeyNeverGrantsEntitlement() {
        assertFalse(PlaySignatureVerifier("").isConfigured)
        assertFalse(PlaySignatureVerifier("").verify("anything", "anything"))
        assertFalse(PlaySignatureVerifier("invalid").verify("anything", "anything"))
    }
}
