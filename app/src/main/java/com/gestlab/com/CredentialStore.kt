package com.gestlab.com

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Identifiants VPN. Le mot de passe est chiffré par une clé de l'Android Keystore. */
data class VpnCredentials(val username: String, val password: String)

class CredentialStore(context: Context) {

    private val prefs = context.getSharedPreferences("vpn_credentials", Context.MODE_PRIVATE)

    fun load(): VpnCredentials? {
        val user = prefs.getString(KEY_USER, null) ?: return null
        val enc = prefs.getString(KEY_PASS, null) ?: return null
        val pass = try {
            decrypt(enc)
        } catch (e: Exception) {
            // Clé perdue (ex. restauration sur un autre téléphone) : redemander.
            clear()
            return null
        }
        return VpnCredentials(user, pass)
    }

    fun lastUsername(): String = prefs.getString(KEY_USER, "") ?: ""

    fun save(credentials: VpnCredentials) {
        prefs.edit()
            .putString(KEY_USER, credentials.username)
            .putString(KEY_PASS, encrypt(credentials.password))
            .apply()
    }

    /** Oublie le mot de passe (l'identifiant reste prérempli). */
    fun clearPassword() {
        prefs.edit().remove(KEY_PASS).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val out = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String {
        val data = Base64.decode(encoded, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data, 0, IV_SIZE))
        return String(cipher.doFinal(data, IV_SIZE, data.size - IV_SIZE), Charsets.UTF_8)
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "gestlab_vpn_credentials"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        const val KEY_USER = "username"
        const val KEY_PASS = "password"
    }
}
