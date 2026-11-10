package com.gemini.summarizer.core.security

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.KeyStore

class SecurityManager private constructor(context: Context) {

    private val sharedPreferences: SharedPreferences? by lazy {
        try {
            createEncryptedPrefs(context.applicationContext)
        } catch (e: Exception) {
            Log.e("SecurityManager", "Failed to initialize EncryptedSharedPreferences. Attempting recovery.", e)
            try {
                // Delete existing encrypted preferences file
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    context.applicationContext.deleteSharedPreferences("gemini_summarizer_secure_prefs")
                } else {
                    val prefsFile = java.io.File(context.applicationContext.filesDir.parent, "shared_prefs/gemini_summarizer_secure_prefs.xml")
                    if (prefsFile.exists()) {
                        prefsFile.delete()
                    }
                }

                // Delete corrupted master key entry from AndroidKeyStore
                try {
                    val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                    keyStore.deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
                } catch (keystoreEx: Exception) {
                    Log.w("SecurityManager", "Failed to clear AndroidKeyStore master key entry", keystoreEx)
                }

                createEncryptedPrefs(context.applicationContext)
            } catch (recoveryEx: Exception) {
                Log.e("SecurityManager", "Keystore recovery failed. Plaintext fallback is strictly prohibited.", recoveryEx)
                null
            }
        }
    }

    private fun createEncryptedPrefs(appContext: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            appContext,
            "gemini_summarizer_secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun isSecureStorageAvailable(): Boolean = sharedPreferences != null

    fun storeApiKey(keyId: String, apiKey: String): Boolean {
        val prefs = sharedPreferences ?: throw IllegalStateException("Secure encrypted storage (Keystore) is unavailable on this device. Keys cannot be stored in plaintext.")
        return prefs.edit()
            .putString("api_key_$keyId", apiKey.trim())
            .commit()
    }

    fun getApiKey(keyId: String): String? {
        return sharedPreferences?.getString("api_key_$keyId", null)
    }

    fun deleteApiKey(keyId: String) {
        sharedPreferences?.edit()
            ?.remove("api_key_$keyId")
            ?.commit()
    }

    fun clearAllKeys() {
        sharedPreferences?.edit()?.clear()?.commit()
    }

    companion object {
        @Volatile
        private var INSTANCE: SecurityManager? = null

        fun getInstance(context: Context): SecurityManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SecurityManager(context.applicationContext).also { INSTANCE = it }
            }
        }

        operator fun invoke(context: Context): SecurityManager = getInstance(context)

        fun maskApiKey(apiKey: String): String {
            if (apiKey.length <= 8) return "********"
            val prefix = apiKey.take(6)
            val suffix = apiKey.takeLast(4)
            return "$prefix...$suffix"
        }
    }
}

