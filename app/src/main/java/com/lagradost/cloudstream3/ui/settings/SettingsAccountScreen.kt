package com.lagradost.cloudstream3.ui.settings

import android.content.Context
import android.content.DialogInterface
import android.text.InputType
import android.text.format.DateUtils
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatEditText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.fragment.app.FragmentActivity
import com.lagradost.cloudstream3.CommonActivity.activity
import com.lagradost.cloudstream3.CommonActivity.onDialogDismissedEvent
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.cloudsync.CloudSyncBackend
import com.lagradost.cloudstream3.cloudsync.CloudSyncManager
import com.lagradost.cloudstream3.cloudsync.CloudSyncResult
import com.lagradost.cloudstream3.cloudsync.CloudSyncSettings
import com.lagradost.cloudstream3.cloudsync.CloudSyncStatus
import com.lagradost.cloudstream3.cloudsync.GoogleDriveAuthorization
import com.lagradost.cloudstream3.cloudsync.GoogleDriveAuthorizationOutcome
import com.lagradost.cloudstream3.cloudsync.WebDavSyncConfig
import com.lagradost.cloudstream3.services.CloudSyncWorkManager
import com.lagradost.cloudstream3.syncproviders.AccountManager.Companion.aniListApi
import com.lagradost.cloudstream3.syncproviders.AccountManager.Companion.animeSkipApi
import com.lagradost.cloudstream3.syncproviders.AccountManager.Companion.kitsuApi
import com.lagradost.cloudstream3.syncproviders.AccountManager.Companion.malApi
import com.lagradost.cloudstream3.syncproviders.AccountManager.Companion.openSubtitlesApi
import com.lagradost.cloudstream3.syncproviders.AccountManager.Companion.simklApi
import com.lagradost.cloudstream3.syncproviders.AccountManager.Companion.subDlApi
import com.lagradost.cloudstream3.syncproviders.PlainAuthRepo
import com.lagradost.cloudstream3.syncproviders.SubtitleRepo
import com.lagradost.cloudstream3.syncproviders.SyncRepo
import com.lagradost.cloudstream3.ui.settings.SettingsAccount.Companion.addAccount
import com.lagradost.cloudstream3.ui.settings.SettingsAccount.Companion.showLoginInfo
import com.lagradost.cloudstream3.utils.AppContextUtils.html
import com.lagradost.cloudstream3.utils.BackupUtils
import com.lagradost.cloudstream3.utils.BiometricAuthenticator
import com.lagradost.cloudstream3.utils.BiometricAuthenticator.authCallback
import com.lagradost.cloudstream3.utils.BiometricAuthenticator.biometricPrompt
import com.lagradost.cloudstream3.utils.BiometricAuthenticator.deviceHasPasswordPinLock
import com.lagradost.cloudstream3.utils.BiometricAuthenticator.isAuthEnabled
import com.lagradost.cloudstream3.utils.BiometricAuthenticator.promptInfo
import com.lagradost.cloudstream3.utils.BiometricAuthenticator.startBiometricAuthentication
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.Coroutines.runOnMainThread
import com.lagradost.cloudstream3.utils.SingleSelectionHelper.showBottomDialogText
import com.lagradost.cloudstream4.AppSettings
import com.lagradost.cloudstream4.compose.PHONE
import com.lagradost.cloudstream4.compose.isLayout
import com.lagradost.cloudstream4.rememberAppSettings
import com.mihon.presentation.settings.Preference
import com.mihon.presentation.settings.SearchableSettings
import kotlinx.collections.immutable.persistentListOf

object SettingsAccountScreen : SearchableSettings, BiometricAuthenticator.BiometricCallback {
    val syncApis = persistentListOf(
        SyncRepo(malApi),
        SyncRepo(kitsuApi),
        SyncRepo(aniListApi),
        SyncRepo(simklApi),
        SubtitleRepo(openSubtitlesApi),
        SubtitleRepo(subDlApi),
        PlainAuthRepo(animeSkipApi),
    )
    private fun updateAuthPreference(context: Context, enabled: Boolean) {
        val settings = AppSettings(context)
        settings.security.biometrics.set(enabled)
    }

    override fun onAuthenticationError() {
        val context = activity ?: return
        updateAuthPreference(context, !isAuthEnabled(context))
    }

    override fun onAuthenticationSuccess() {
        val context = activity ?: return

        if (isAuthEnabled(context)) {
            updateAuthPreference(context, true)
            BackupUtils.backup(context)
            context.showBottomDialogText(
                context.getString(R.string.biometric_setting),
                context.getString(R.string.biometric_warning).html()
            ) { onDialogDismissedEvent }
        } else {
            updateAuthPreference(context, false)
        }
    }

    @Composable
    override fun getTitleRes(): String = stringResource(R.string.category_account)

    @Composable
    override fun getPreferences(): List<Preference> {
        val settings = rememberAppSettings()
        val activity = LocalActivity.current
        val context = LocalContext.current
        var cloudSyncStatus by remember { mutableStateOf(CloudSyncSettings.status(context)) }
        val refreshCloudSyncStatus = {
            cloudSyncStatus = CloudSyncSettings.status(context)
        }
        val enableGoogleDrive = {
            CloudSyncSettings.setGoogleDrive(context)
            CloudSyncWorkManager.configurationChanged(context)
            refreshCloudSyncStatus()
            showToast(R.string.cloud_sync_connected)
        }
        val googleAuthorizationLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult()
        ) { result ->
            when (val outcome = GoogleDriveAuthorization.finish(context, result.data)) {
                GoogleDriveAuthorizationOutcome.Authorized -> enableGoogleDrive()
                GoogleDriveAuthorizationOutcome.Cancelled -> showToast(
                    R.string.cloud_sync_authorization_failed
                )
                GoogleDriveAuthorizationOutcome.ConfigurationRequired -> showToast(
                    R.string.cloud_sync_google_oauth_configuration_required
                )
                GoogleDriveAuthorizationOutcome.Unavailable -> showToast(
                    R.string.cloud_sync_google_play_services_unavailable
                )
                is GoogleDriveAuthorizationOutcome.Failed -> showToast(
                    context.getString(
                        R.string.cloud_sync_connect_failed,
                        outcome.error.message.orEmpty(),
                    )
                )
                is GoogleDriveAuthorizationOutcome.NeedsResolution -> showToast(
                    R.string.cloud_sync_authorization_failed
                )
            }
        }
        val hasSecurity = remember(context) {
            try {
                deviceHasPasswordPinLock(context)
            } catch (_ : Throwable) {
                // e.g preview
                false
            }
        }

        return persistentListOf(
            Preference.PreferenceGroup(
                title = stringResource(R.string.pref_category_accounts),
                preferenceItems = syncApis.map { api ->
                    Preference.PreferenceItem.TextPreference(
                        title = api.name,
                        icon = api.icon?.let { painterResource(it) },
                        onClick = {
                            val activity = activity ?: return@TextPreference
                            val info = api.authUser()
                            val index =
                                api.accounts.indexOfFirst { account -> account.user.id == info?.id }
                            if (api.accounts.isNotEmpty()) {
                                showLoginInfo(activity, api, info, index)
                            } else {
                                addAccount(activity, api)
                            }
                        })
                } + Preference.PreferenceItem.SwitchPreference(
                    preference = settings.security.skipAccountSelection,
                    title = stringResource(R.string.skip_startup_account_select_pref),
                    icon = painterResource(R.drawable.ic_outline_account_circle_24)
                ),
            ),
            Preference.PreferenceGroup(
                title = stringResource(R.string.cloud_sync_category),
                preferenceItems = persistentListOf(
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(R.string.cloud_sync_status),
                        subtitle = cloudSyncStatus.summary(context),
                        icon = painterResource(R.drawable.baseline_sync_24),
                    ),
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(R.string.cloud_sync_google_drive),
                        subtitle = stringResource(R.string.cloud_sync_google_drive_summary),
                        icon = painterResource(R.drawable.cloud_2_solid),
                        onClick = {
                            GoogleDriveAuthorization.request(context) { outcome ->
                                when (outcome) {
                                    GoogleDriveAuthorizationOutcome.Authorized -> enableGoogleDrive()
                                    GoogleDriveAuthorizationOutcome.Cancelled -> showToast(
                                        R.string.cloud_sync_authorization_failed
                                    )
                                    GoogleDriveAuthorizationOutcome.ConfigurationRequired -> showToast(
                                        R.string.cloud_sync_google_oauth_configuration_required
                                    )
                                    is GoogleDriveAuthorizationOutcome.NeedsResolution -> {
                                        googleAuthorizationLauncher.launch(
                                            IntentSenderRequest.Builder(
                                                outcome.pendingIntent.intentSender
                                            ).build()
                                        )
                                    }
                                    GoogleDriveAuthorizationOutcome.Unavailable -> showToast(
                                        R.string.cloud_sync_google_play_services_unavailable
                                    )
                                    is GoogleDriveAuthorizationOutcome.Failed -> {
                                        showToast(
                                            context.getString(
                                                R.string.cloud_sync_connect_failed,
                                                outcome.error.message.orEmpty(),
                                            )
                                        )
                                    }
                                }
                            }
                        },
                    ),
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(R.string.cloud_sync_webdav),
                        subtitle = stringResource(R.string.cloud_sync_webdav_summary),
                        icon = painterResource(R.drawable.hard_drive_24px),
                        onClick = {
                            showWebDavDialog(
                                context = context,
                                existing = CloudSyncSettings.webDavConfig(context),
                            ) { config ->
                                runCatching { CloudSyncSettings.setWebDav(context, config) }
                                    .onSuccess {
                                        if (cloudSyncStatus.backend == CloudSyncBackend.GOOGLE_DRIVE) {
                                            GoogleDriveAuthorization.revoke(context)
                                        }
                                        CloudSyncWorkManager.configurationChanged(context)
                                        refreshCloudSyncStatus()
                                        showToast(R.string.cloud_sync_connected)
                                    }
                                    .onFailure {
                                        showToast(R.string.cloud_sync_invalid_url)
                                    }
                            }
                        },
                    ),
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(R.string.cloud_sync_now),
                        subtitle = stringResource(R.string.cloud_sync_now_summary),
                        icon = painterResource(R.drawable.ic_refresh),
                        enabled = cloudSyncStatus.backend != CloudSyncBackend.NONE,
                        onClick = {
                            context.ioSafe {
                                val result = CloudSyncManager(context).sync()
                                runOnMainThread {
                                    refreshCloudSyncStatus()
                                    when (result) {
                                        CloudSyncResult.AuthorizationRequired -> showToast(
                                            R.string.cloud_sync_authorization_required
                                        )
                                        CloudSyncResult.Disabled -> Unit
                                        is CloudSyncResult.Success -> showToast(
                                            R.string.cloud_sync_success
                                        )
                                        is CloudSyncResult.Unavailable -> showToast(
                                            context.getString(
                                                R.string.cloud_sync_failed,
                                                result.message.orEmpty(),
                                            )
                                        )
                                    }
                                }
                            }
                        },
                    ),
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(R.string.cloud_sync_disconnect),
                        icon = painterResource(R.drawable.delete_24px),
                        enabled = cloudSyncStatus.backend != CloudSyncBackend.NONE,
                        onClick = {
                            AlertDialog.Builder(context, R.style.AlertDialogCustom)
                                .setTitle(R.string.cloud_sync_disconnect)
                                .setMessage(R.string.cloud_sync_disconnect_confirmation)
                                .setNegativeButton(R.string.cancel, null)
                                .setPositiveButton(R.string.cloud_sync_disconnect_button) { _, _ ->
                                    if (cloudSyncStatus.backend == CloudSyncBackend.GOOGLE_DRIVE) {
                                        GoogleDriveAuthorization.revoke(context)
                                    }
                                    CloudSyncSettings.disconnect(context)
                                    CloudSyncWorkManager.configurationChanged(context)
                                    refreshCloudSyncStatus()
                                }
                                .show()
                        },
                    ),
                ),
            ),
            Preference.PreferenceGroup(
                enabled = hasSecurity && isLayout(PHONE),
                title = stringResource(R.string.pref_category_security),
                preferenceItems = persistentListOf(
                    Preference.PreferenceItem.SwitchPreference(
                        preference = settings.security.biometrics,
                        title = stringResource(R.string.biometric_setting),
                        subtitle = stringResource(R.string.biometric_setting_summary),
                        icon = painterResource(R.drawable.ic_fingerprint),
                        onValueChanged = { _ ->
                            val activity =
                                activity as? FragmentActivity ?: return@SwitchPreference false

                            if (deviceHasPasswordPinLock(activity)) {
                                startBiometricAuthentication(
                                    activity, R.string.biometric_authentication_title, false
                                )
                                promptInfo?.let {
                                    authCallback = this
                                    biometricPrompt?.authenticate(it)
                                }
                            }

                            return@SwitchPreference true
                        })
                )
            )
        )
    }

    private fun CloudSyncStatus.summary(context: Context): String {
        val backendName = when (backend) {
            CloudSyncBackend.NONE -> return context.getString(R.string.cloud_sync_not_configured)
            CloudSyncBackend.WEBDAV -> context.getString(R.string.cloud_sync_webdav)
            CloudSyncBackend.GOOGLE_DRIVE -> context.getString(R.string.cloud_sync_google_drive)
        }
        val detail = when {
            lastError != null -> context.getString(R.string.cloud_sync_last_error, lastError)
            lastSuccessAt > 0 -> context.getString(
                R.string.cloud_sync_last_success,
                DateUtils.getRelativeTimeSpanString(
                    lastSuccessAt,
                    System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS,
                ),
            )
            else -> context.getString(R.string.cloud_sync_waiting)
        }
        return "$backendName • $detail"
    }

    private fun showWebDavDialog(
        context: Context,
        existing: WebDavSyncConfig?,
        onSave: (WebDavSyncConfig) -> Unit,
    ) {
        val padding = (20 * context.resources.displayMetrics.density).toInt()
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, 0, padding, 0)
        }
        val url = AppCompatEditText(context).apply {
            hint = context.getString(R.string.cloud_sync_webdav_url)
            setText(existing?.url.orEmpty())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        val username = AppCompatEditText(context).apply {
            hint = context.getString(R.string.cloud_sync_username)
            setText(existing?.username.orEmpty())
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val password = AppCompatEditText(context).apply {
            hint = context.getString(R.string.cloud_sync_password)
            setText(existing?.password.orEmpty())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        layout.addView(url)
        layout.addView(username)
        layout.addView(password)

        val dialog = AlertDialog.Builder(context, R.style.AlertDialogCustom)
            .setTitle(R.string.cloud_sync_webdav)
            .setMessage(R.string.cloud_sync_webdav_dialog_summary)
            .setView(layout)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.cloud_sync_save, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val config = WebDavSyncConfig(
                    url = url.text?.toString().orEmpty(),
                    username = username.text?.toString().orEmpty(),
                    password = password.text?.toString().orEmpty(),
                )
                if (config.url.isBlank()) {
                    url.error = context.getString(R.string.cloud_sync_invalid_url)
                    return@setOnClickListener
                }
                onSave(config)
                dialog.dismiss()
            }
        }
        dialog.show()
    }
}
