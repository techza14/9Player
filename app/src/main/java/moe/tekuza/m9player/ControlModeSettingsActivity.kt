package moe.tekuza.m9player

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
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
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import moe.tekuza.m9player.ui.theme.TsetTheme

class ControlModeSettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TsetTheme {
                ControlModeSettingsScreen(onBack = { finish() })
            }
        }
    }
}

@Composable
private fun ControlModeSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val config = remember { loadGamepadControlConfig(context) }
    var dimScreenInControlMode by remember {
        mutableStateOf(config.dimScreenInControlMode)
    }
    var singleTapCollectOnlyInControlMode by remember {
        mutableStateOf(config.singleTapCollectOnlyInControlMode)
    }
    var powerSaveBlackScreenInControlMode by remember {
        mutableStateOf(config.powerSaveBlackScreenInControlMode)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.common_back)) }
            Text(stringResource(R.string.control_mode_title), style = MaterialTheme.typography.titleLarge)
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.control_mode_dim_screen),
                        modifier = Modifier.weight(1f).padding(end = 12.dp)
                    )
                    Switch(
                        checked = dimScreenInControlMode,
                        onCheckedChange = { checked ->
                            dimScreenInControlMode = checked
                            saveDimScreenInControlMode(context, checked)
                        }
                    )
                }
                Text(stringResource(R.string.control_mode_dim_screen_help))
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.control_mode_single_tap_collect),
                        modifier = Modifier.weight(1f).padding(end = 12.dp)
                    )
                    Switch(
                        checked = singleTapCollectOnlyInControlMode,
                        onCheckedChange = { checked ->
                            singleTapCollectOnlyInControlMode = checked
                            saveSingleTapCollectOnlyInControlMode(context, checked)
                        }
                    )
                }
                Text(stringResource(R.string.control_mode_single_tap_collect_help))
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.control_mode_black_screen),
                        modifier = Modifier.weight(1f).padding(end = 12.dp)
                    )
                    Switch(
                        checked = powerSaveBlackScreenInControlMode,
                        onCheckedChange = { checked ->
                            powerSaveBlackScreenInControlMode = checked
                            savePowerSaveBlackScreenInControlMode(context, checked)
                        }
                    )
                }
                Text(stringResource(R.string.control_mode_black_screen_help))
            }
        }
    }
}

