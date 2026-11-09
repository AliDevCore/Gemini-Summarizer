package com.gemini.summarizer.core.security

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class SecurityManager(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val sharedPreferences = EncryptedSharedPreferences.create(
        context,
        "gemini_summarizer_secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun storeApiKey(keyId: String, apiKey: String) {
        sharedPreferences.edit()
            .putString("api_key_$keyId", apiKey.trim())
            .apply()
    }

    fun getApiKey(keyId: String): String? {
        return sharedPreferences.getString("api_key_$keyId", null)
    }

    fun deleteApiKey(keyId: String) {
        sharedPreferences.edit()
            .remove("api_key_$keyId")
            .apply()
    }

    fun clearAllKeys() {
        sharedPreferences.edit().clear().apply()
    }

    companion object {
        fun maskApiKey(apiKey: String): String {
            if (apiKey.length <= 8) return "********"
            val prefix = apiKey.take(6)
            val suffix = apiKey.takeLast(4)
            return "$prefix...$suffix"
        }
    }
}
