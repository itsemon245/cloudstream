package com.lagradost.cloudstream3.cloudsync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.utils.DataStoreHelper
import java.nio.ByteBuffer
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class CloudSyncBackend {
    NONE,
    WEBDAV,
    GOOGLE_DRIVE,
}

data class WebDavSyncConfig(
    val url: String,
    val username: String,
    val password: String,
)

data class CloudSyncStatus(
    val backend: CloudSyncBackend,
    val lastAttemptAt: Long,
    val lastSuccessAt: Long,
    val lastError: String?,
)

object CloudSyncSettings {
    private const val KEY_DEVICE_ID = "cloud_sync_device_id"
    private const val KEY_BACKEND = "cloud_sync_backend"
    private const val KEY_WEBDAV_URL = "cloud_sync_webdav_url"
    private const val KEY_WEBDAV_USERNAME = "cloud_sync_webdav_username"
    private const val KEY_WEBDAV_PASSWORD = "cloud_sync_webdav_password"
    private const val KEY_LAST_ATTEMPT = "cloud_sync_last_attempt"
    private const val KEY_LAST_SUCCESS = "cloud_sync_last_success"
    private const val KEY_LAST_ERROR = "cloud_sync_last_error"

    private fun preferences(context: Context) =
        PreferenceManager.getDefaultSharedPreferences(context)

    private fun accountKey(key: String, account: String = DataStoreHelper.currentAccount): String =
        "${key}_$account"

    fun backend(
        context: Context,
        account: String = DataStoreHelper.currentAccount,
    ): CloudSyncBackend {
        val value = preferences(context).getString(accountKey(KEY_BACKEND, account), null)
        return CloudSyncBackend.entries.firstOrNull { it.name == value } ?: CloudSyncBackend.NONE
    }

    fun setGoogleDrive(context: Context) {
        preferences(context).edit {
            putString(accountKey(KEY_BACKEND), CloudSyncBackend.GOOGLE_DRIVE.name)
            remove(accountKey(KEY_WEBDAV_URL))
            remove(accountKey(KEY_WEBDAV_USERNAME))
            remove(accountKey(KEY_WEBDAV_PASSWORD))
            remove(accountKey(KEY_LAST_ERROR))
        }
    }

    fun setWebDav(context: Context, config: WebDavSyncConfig) {
        val normalizedUrl = config.url.trim().trimEnd('/')
        require(normalizedUrl.startsWith("http://") || normalizedUrl.startsWith("https://"))

        preferences(context).edit {
            putString(accountKey(KEY_BACKEND), CloudSyncBackend.WEBDAV.name)
            putString(accountKey(KEY_WEBDAV_URL), normalizedUrl)
            putString(accountKey(KEY_WEBDAV_USERNAME), config.username.trim())
            putString(
                accountKey(KEY_WEBDAV_PASSWORD),
                EncryptedSecretStore.encrypt(config.password),
            )
            remove(accountKey(KEY_LAST_ERROR))
        }
    }

    fun webDavConfig(
        context: Context,
        account: String = DataStoreHelper.currentAccount,
    ): WebDavSyncConfig? {
        val prefs = preferences(context)
        val url = prefs.getString(accountKey(KEY_WEBDAV_URL, account), null) ?: return null
        val encryptedPassword =
            prefs.getString(accountKey(KEY_WEBDAV_PASSWORD, account), null) ?: return null
        val password = EncryptedSecretStore.decrypt(encryptedPassword) ?: return null
        return WebDavSyncConfig(
            url = url,
            username = prefs.getString(accountKey(KEY_WEBDAV_USERNAME, account), "").orEmpty(),
            password = password,
        )
    }

    fun disconnect(context: Context) {
        preferences(context).edit {
            putString(accountKey(KEY_BACKEND), CloudSyncBackend.NONE.name)
            remove(accountKey(KEY_WEBDAV_URL))
            remove(accountKey(KEY_WEBDAV_USERNAME))
            remove(accountKey(KEY_WEBDAV_PASSWORD))
            remove(accountKey(KEY_LAST_ERROR))
        }
    }

    fun deviceId(context: Context): String {
        val prefs = preferences(context)
        prefs.getString(KEY_DEVICE_ID, null)?.let { return it }
        return UUID.randomUUID().toString().also { id ->
            prefs.edit { putString(KEY_DEVICE_ID, id) }
        }
    }

    fun status(
        context: Context,
        account: String = DataStoreHelper.currentAccount,
    ): CloudSyncStatus {
        val prefs = preferences(context)
        return CloudSyncStatus(
            backend = backend(context, account),
            lastAttemptAt = prefs.getLong(accountKey(KEY_LAST_ATTEMPT, account), 0L),
            lastSuccessAt = prefs.getLong(accountKey(KEY_LAST_SUCCESS, account), 0L),
            lastError = prefs.getString(accountKey(KEY_LAST_ERROR, account), null),
        )
    }

    internal fun recordAttempt(context: Context, account: String) {
        preferences(context).edit {
            putLong(accountKey(KEY_LAST_ATTEMPT, account), System.currentTimeMillis())
        }
    }

    internal fun recordSuccess(context: Context, account: String) {
        preferences(context).edit {
            putLong(accountKey(KEY_LAST_SUCCESS, account), System.currentTimeMillis())
            remove(accountKey(KEY_LAST_ERROR, account))
        }
    }

    internal fun recordFailure(context: Context, account: String, message: String?) {
        preferences(context).edit {
            putString(accountKey(KEY_LAST_ERROR, account), message?.take(200))
        }
    }

    internal fun hasAnyConfiguredAccount(context: Context): Boolean {
        return preferences(context).all.any { (key, value) ->
            key.startsWith("${KEY_BACKEND}_") && value != CloudSyncBackend.NONE.name
        }
    }
}

private object EncryptedSecretStore {
    private const val KEY_ALIAS = "cloudstream_cloud_sync_credentials_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val TAG_LENGTH_BITS = 128

    fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val packed = ByteBuffer.allocate(Int.SIZE_BYTES + cipher.iv.size + ciphertext.size)
            .putInt(cipher.iv.size)
            .put(cipher.iv)
            .put(ciphertext)
            .array()
        return Base64.encodeToString(packed, Base64.NO_WRAP)
    }

    fun decrypt(value: String): String? = runCatching {
        val packed = ByteBuffer.wrap(Base64.decode(value, Base64.NO_WRAP))
        val ivSize = packed.int
        require(ivSize in 12..32)
        val iv = ByteArray(ivSize).also(packed::get)
        val ciphertext = ByteArray(packed.remaining()).also(packed::get)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_LENGTH_BITS, iv))
        cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
    }.getOrNull()

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
            generateKey()
        }
    }
}
