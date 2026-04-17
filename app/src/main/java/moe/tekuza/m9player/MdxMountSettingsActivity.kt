package moe.tekuza.m9player

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.tekuza.m9player.ui.theme.TsetTheme
import java.util.Locale

class MdxMountSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        applySavedAppLanguage(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TsetTheme {
                MdxMountSettingsScreen(
                    onBack = { finish() }
                )
            }
        }
    }
}

private data class MdxTreeMatchSub(
    val document: DocumentFile,
    val relativeDir: String
)

private fun collectMdxFilesRecursivelyForSettings(root: DocumentFile?): List<MdxTreeMatchSub> {
    if (root == null || !root.exists()) return emptyList()
    val out = mutableListOf<MdxTreeMatchSub>()
    val stack = ArrayDeque<Pair<DocumentFile, String>>()
    stack.add(root to "")
    while (stack.isNotEmpty()) {
        val (current, currentPath) = stack.removeLast()
        current.listFiles().forEach { file ->
            when {
                file.isDirectory -> {
                    val nextPath = listOf(currentPath, file.name.orEmpty())
                        .filter { it.isNotBlank() }
                        .joinToString("/")
                    stack.add(file to nextPath)
                }
                file.isFile && file.name.orEmpty().lowercase(Locale.US).endsWith(".mdx") -> {
                    out += MdxTreeMatchSub(document = file, relativeDir = currentPath)
                }
            }
        }
    }
    return out.sortedBy { it.document.name.orEmpty().lowercase(Locale.US) }
}

@Composable
private fun MdxMountSettingsScreen(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var mountState by remember { mutableStateOf(loadMdxMountState(context)) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun persist(next: MdxMountState) {
        mountState = next
        saveMdxMountState(context, next)
        invalidateDictionaryLookupCaches()
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri == null) {
            loading = false
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            keepReadPermission(context, uri)
            val matches = withContext(Dispatchers.IO) {
                val root = DocumentFile.fromTreeUri(context, uri)
                collectMdxFilesRecursivelyForSettings(root)
            }
            if (matches.isEmpty()) {
                loading = false
                error = context.getString(R.string.mdx_error_no_file)
                return@launch
            }
            val existingByKey = mountState.entries.associateBy { it.cacheKey }.toMutableMap()
            matches.forEach { match ->
                val mdxUri = match.document.uri
                keepReadPermission(context, mdxUri)
                val displayName = match.document.name.orEmpty().ifBlank { "mounted.mdx" }
                val mdxUriValue = mdxUri.toString()
                val mountCacheKey = "mdx_mount_${buildDictionaryCacheKey(mdxUriValue, displayName)}"
                existingByKey[mountCacheKey] = MdxMountedEntry(
                    treeUri = uri.toString(),
                    mdxUri = mdxUriValue,
                    displayName = displayName,
                    cacheKey = mountCacheKey,
                    relativeDir = match.relativeDir,
                    enabled = true
                )
            }
            persist(mountState.copy(enabled = true, entries = existingByKey.values.toList()))
            loading = false
            error = null
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.common_back)) }
            Text(stringResource(R.string.settings_mdx_title), style = MaterialTheme.typography.titleLarge)
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Checkbox(
                checked = mountState.enabled,
                onCheckedChange = { checked ->
                    persist(mountState.copy(enabled = checked))
                }
            )
            Text(if (mountState.enabled) stringResource(R.string.mdx_enabled) else stringResource(R.string.mdx_disabled))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    loading = true
                    picker.launch(null)
                },
                enabled = !loading
            ) {
                Text(if (loading) stringResource(R.string.mdx_scanning) else stringResource(R.string.mdx_add_folder))
            }
            OutlinedButton(onClick = onBack) { Text(stringResource(R.string.mdx_done)) }
        }

        Text(
            stringResource(R.string.mdx_mounted_count, mountState.entries.size),
            style = MaterialTheme.typography.bodySmall
        )
        if (error != null) {
            Text(stringResource(R.string.mdx_error_prefix, error.orEmpty()), color = MaterialTheme.colorScheme.error)
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (mountState.entries.isEmpty()) {
                Text(stringResource(R.string.mdx_empty))
            } else {
                mountState.entries.forEach { entry ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(entry.displayName.ifBlank { "mounted.mdx" })
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        persist(
                                            mountState.copy(
                                                entries = mountState.entries.map { current ->
                                                    if (current.cacheKey == entry.cacheKey) {
                                                        current.copy(enabled = !current.enabled)
                                                    } else current
                                                }
                                            )
                                        )
                                    }
                                ) {
                                    Text(if (entry.enabled) stringResource(R.string.mdx_disable) else stringResource(R.string.mdx_enable))
                                }
                                OutlinedButton(
                                    onClick = {
                                        persist(
                                            mountState.copy(
                                                entries = mountState.entries.filterNot { it.cacheKey == entry.cacheKey }
                                            )
                                        )
                                    }
                                ) {
                                    Text(stringResource(R.string.mdx_remove))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
