package com.github.brokenhappy.kotlinconfchallengeplugin.shared.protocol

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class BleEncryption(key: ByteArray) {
    init {
        require(key.size == 32) { "AES-256-GCM requires a 32-byte key, got ${key.size}" }
    }

    private val secretKey = SecretKeySpec(key, "AES")
    private val random = SecureRandom()

    fun encrypt(plaintext: ByteArray): ByteArray {
        val nonce = ByteArray(NONCE_SIZE).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(TAG_BIT_LENGTH, nonce))
        val ciphertext = cipher.doFinal(plaintext)
        return nonce + ciphertext
    }

    fun decrypt(payload: ByteArray): ByteArray {
        require(payload.size > NONCE_SIZE) { "Payload too short to contain nonce" }
        val nonce = payload.copyOfRange(0, NONCE_SIZE)
        val ciphertext = payload.copyOfRange(NONCE_SIZE, payload.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(TAG_BIT_LENGTH, nonce))
        return cipher.doFinal(ciphertext)
    }

    companion object {
        private const val NONCE_SIZE = 12
        private const val TAG_BIT_LENGTH = 128

        fun fromBase64(base64Key: String): BleEncryption =
            BleEncryption(Base64.getDecoder().decode(base64Key))
    }
}
