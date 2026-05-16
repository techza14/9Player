package moe.tekuza.m9player

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import moe.tekuza.m9player.ui.theme.TsetTheme

class UpdateSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TsetTheme {
                UpdateSettingsScreen(onBack = { finish() })
            }
        }
    }
}

@Composable
private fun UpdateSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var config by remember { mutableStateOf(loadAppUpdateConfig(context)) }
    var checking by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var statusText by remember { mutableStateOf<String?>(null) }
    var availableRelease by remember { mutableStateOf<AppUpdateRelease?>(null) }

    fun refreshConfig() {
        config = loadAppUpdateConfig(context)
    }

    fun checkForUpdate() {
        if (checking || downloading) return
        checking = true
        statusText = context.getString(R.string.update_checking)
        scope.launch {
            val result = checkLatestAppUpdate(context)
            saveAppUpdateCheckedAt(context, System.currentTimeMillis())
            refreshConfig()
            checking = false
            when (result) {
                is AppUpdateCheckResult.UpdateAvailable -> {
                    availableRelease = result.release
                    statusText = context.getString(R.string.update_available, result.release.displayName)
                }
                is AppUpdateCheckResult.UpToDate -> {
                    availableRelease = null
                    statusText = context.getString(R.string.update_up_to_date, result.latestVersion)
                }
                is AppUpdateCheckResult.NoApkAsset -> {
                    availableRelease = null
                    statusText = context.getString(R.string.update_no_apk, result.latestVersion)
                }
                is AppUpdateCheckResult.Failed -> {
                    availableRelease = null
                    statusText = context.getString(R.string.update_failed, result.message)
                }
            }
        }
    }

    fun downloadAndInstall(release: AppUpdateRelease) {
        if (checking || downloading) return
        downloading = true
        progress = null
        statusText = context.getString(R.string.update_downloading)
        scope.launch {
            val result = downloadAppUpdateApk(context, release) { next ->
                scope.launch(Dispatchers.Main) { progress = next }
            }
            downloading = false
            progress = null
            result
                .onSuccess { file ->
                    val launched = launchAppUpdateInstall(context, file)
                    statusText = if (launched) {
                        context.getString(R.string.update_install_started)
                    } else {
                        context.getString(R.string.update_install_failed)
                    }
                }
                .onFailure { error ->
                    statusText = context.getString(
                        R.string.update_failed,
                        error.message ?: error.javaClass.simpleName
                    )
                }
        }
    }

    SettingsScaffold(
        title = stringResource(R.string.settings_update_title),
        onBack = onBack
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.update_auto_check),
                            modifier = Modifier.weight(1f).padding(end = 12.dp)
                        )
                        Switch(
                            checked = config.autoUpdateEnabled,
                            onCheckedChange = { enabled ->
                                saveAutoUpdateEnabled(context, enabled)
                                refreshConfig()
                                Toast.makeText(
                                    context,
                                    if (enabled) R.string.update_auto_enabled else R.string.update_auto_disabled,
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        )
                    }
                    Text(
                        text = stringResource(R.string.update_current_version, resolveAppVersionName(context)),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    statusText?.let { Text(it) }
                    if (downloading) {
                        progress?.let {
                            LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth())
                        } ?: LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            enabled = !checking && !downloading,
                            onClick = { checkForUpdate() }
                        ) {
                            Text(stringResource(R.string.update_check_now))
                        }
                        val release = availableRelease
                        if (release != null) {
                            Button(
                                enabled = !checking && !downloading,
                                onClick = { downloadAndInstall(release) }
                            ) {
                                Text(stringResource(R.string.update_download_install))
                            }
                        }
                    }
                }
            }
        }
    }
}
