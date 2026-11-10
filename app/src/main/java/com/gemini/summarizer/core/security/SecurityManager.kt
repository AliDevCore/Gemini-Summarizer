package com.gemini.summarizer.core.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class SecurityManager private constructor(context: Context) {

    private val sharedPreferences: SharedPreferences? by lazy {
        try {
            val masterKey = MasterKey.Builder(context.applicationContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context.applicationContext,
                "gemini_summarizer_secure_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.e("SecurityManager", "Failed to initialize EncryptedSharedPreferences, falling back to private prefs", e)
            try {
                context.applicationContext.getSharedPreferences("gemini_summarizer_secure_prefs_fallback", Context.MODE_PRIVATE)
            } catch (ex: Exception) {
                null
            }
        }
    }

    fun storeApiKey(keyId: String, apiKey: String) {
        sharedPreferences?.edit()
            ?.putString("api_key_$keyId", apiKey.trim())
            ?.apply()
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

