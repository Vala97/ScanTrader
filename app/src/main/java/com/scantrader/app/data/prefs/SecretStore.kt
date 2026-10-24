package com.scantrader.app.data.prefs

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * CardTrader API token, encrypted with a key that stays in the Android Keystore.
 * The ciphertext is not a source file and is never logged.
 */
class SecretStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun hasCardTraderToken(): Boolean = !getCardTraderToken().isNullOrBlank()

    fun getCardTraderToken(): String? {
        val payload = prefs.getString(TOKEN, null) ?: return null
        return runCatching { decrypt(payload) }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun setCardTraderToken(token: String?) {
        val clean = token?.trim().orEmpty()
        if (clean.isEmpty()) {
            prefs.edit().remove(TOKEN).apply()
            return
        }
        prefs.edit().putString(TOKEN, encrypt(clean)).apply()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(iv.size + body.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(body, 0, combined, iv.size, body.size)
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    private fun decrypt(payload: String): String {
        val combined = Base64.decode(payload, Base64.NO_WRAP)
        require(combined.size > GCM_IV_LENGTH)
        val iv = combined.copyOfRange(0, GCM_IV_LENGTH)
        val body = combined.copyOfRange(GCM_IV_LENGTH, combined.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(body), Charsets.UTF_8)
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFS = "scantrader_secrets"
        const val TOKEN = "cardtrader_token"
        const val KEY_ALIAS = "scantrader_cardtrader_token"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_LENGTH = 12
    }
}
