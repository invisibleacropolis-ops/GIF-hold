package com.gifvision.app.ui.master

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gifvision.app.ui.components.BlendControlsAvailability
import com.gifvision.app.ui.components.BlendControlsCard
import com.gifvision.app.ui.components.GenerateBlendButton
import com.gifvision.app.ui.state.MasterBlendConfig
import com.gifvision.app.ui.state.GifVisionBlendMode

@Composable
internal fun MasterControlsCard(
    state: MasterBlendConfig,
    onModeChange: (GifVisionBlendMode) -> Unit,
    onOpacityChange: (Float) -> Unit,
    onGenerateMasterBlend: () -> Unit,
    onSaveMasterBlend: () -> Unit,
    onShareMasterBlend: () -> Unit
) {
    val availability = BlendControlsAvailability(
        controlsEnabled = state.isEnabled && !state.isGenerating,
        generateEnabled = state.isEnabled && !state.isGenerating,
        isGenerating = state.isGenerating
    )
    val saveEnabled = state.masterGifPath != null && !state.isGenerating
    val shareEnabled = state.masterGifPath != null && !state.isGenerating && !state.shareSetup.isPreparingShare
    val generateLabel = if (state.masterGifPath == null) "Generate Master Blend" else "Regenerate Master Blend"
    val masterOpacitySupportingText = "0.00 shows only Layer 1 Blend · 1.00 fully overlays Layer 2 Blend"

    BlendControlsCard(
        title = "Master Blend",
        availability = availability,
        mode = state.mode,
        opacity = state.opacity,
        onModeChange = onModeChange,
        onOpacityChange = onOpacityChange,
        onGenerateBlend = onGenerateMasterBlend,
        generateLabel = generateLabel,
        opacitySupportingText = masterOpacitySupportingText,
        infoContent = { availabilitySnapshot ->
            when {
                !state.isEnabled -> {
                    Text(
                        text = "Generate blends for Layer 1 and Layer 2 to unlock the master controls.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                availabilitySnapshot.isGenerating -> {
                    Text(
                        text = "Master blend rendering in progress…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        actionContent = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                GenerateBlendButton(
                    enabled = availability.generateEnabled,
                    onGenerate = onGenerateMasterBlend,
                    label = generateLabel,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(
                    onClick = onSaveMasterBlend,
                    enabled = saveEnabled,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Save")
                }
                Button(
                    onClick = onShareMasterBlend,
                    enabled = shareEnabled,
                    colors = ButtonDefaults.filledTonalButtonColors(),
                    modifier = Modifier.weight(1f)
                ) {
                    if (state.shareSetup.isPreparingShare) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.size(8.dp))
                        Text("Preparing…")
                    } else {
                        Text("Share")
                    }
                }
            }
        }
    )
}
