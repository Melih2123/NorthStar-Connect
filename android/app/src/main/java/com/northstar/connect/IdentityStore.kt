package com.northstar.connect

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object IdentityStore {
    private const val PREFS = "northstar_identity"
    private const val KEY_ALIAS = "northstar.account.token.aes"

    fun save(context: Context, accountId: String, username: String, accessToken: String) {
        val encrypted = encrypt(accessToken)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("account_id", accountId)
            .putString("username", username)
            .putString("access_token_enc", encrypted)
            .apply()
    }

    fun accountId(context: Context): String =
        prefs(context).getString("account_id", "").orEmpty()

    fun username(context: Context): String =
        prefs(context).getString("username", "").orEmpty()

    fun accessToken(context: Context): String? = runCatching {
        val encrypted = prefs(context).getString("access_token_enc", null) ?: return null
        decrypt(encrypted)
    }.getOrNull()

    fun isSignedIn(context: Context): Boolean = !accessToken(context).isNullOrBlank()

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val cipherText = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val packed = cipher.iv + cipherText
        return Base64.encodeToString(packed, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String {
        val packed = Base64.decode(encoded, Base64.NO_WRAP)
        require(packed.size > 12) { "Stored account token is invalid." }
        val iv = packed.copyOfRange(0, 12)
        val cipherText = packed.copyOfRange(12, packed.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(cipherText), Charsets.UTF_8)
    }
}
