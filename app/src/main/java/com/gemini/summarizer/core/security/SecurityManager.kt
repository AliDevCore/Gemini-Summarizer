package com.gemini.summarizer.core.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class SecurityManager private constructor(context: Context) {

    private val sharedPreferences: SharedPreferences? by lazy {
        try {
            createEncryptedPrefs(context.applicationContext)
        } catch (e: Exception) {
            Log.e("SecurityManager", "Failed to initialize EncryptedSharedPreferences. Attempting recovery.", e)
            try {
                // Recreate after clearing corrupted file
                val prefsFile = java.io.File(context.applicationContext.filesDir.parent, "shared_prefs/gemini_summarizer_secure_prefs.xml")
                if (prefsFile.exists()) {
                    prefsFile.delete()
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

    fun storeApiKey(keyId: String, apiKey: String) {
        val prefs = sharedPreferences ?: throw IllegalStateException("Secure encrypted storage is unavailable. Keys cannot be stored in plaintext.")
        prefs.edit()
            .putString("api_key_$keyId", apiKey.trim())
            .apply()
    }

    fun getApiKey(keyId: String): String? {
        return sharedPreferences?.getString("api_key_$keyId", null)
    }

    fun deleteApiKey(keyId: String) {
        sharedPreferences?.edit()
            ?.remove("api_key_$keyId")
            ?.apply()
    }

    fun clearAllKeys() {
        sharedPreferences?.edit()?.clear()?.apply()
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

