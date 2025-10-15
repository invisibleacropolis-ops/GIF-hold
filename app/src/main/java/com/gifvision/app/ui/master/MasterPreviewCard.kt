package com.gifvision.app.ui.master

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gifvision.app.ui.components.BlendPreviewThumbnail
import com.gifvision.app.ui.state.MasterBlendConfig

@Composable
internal fun MasterPreviewCard(state: MasterBlendConfig) {
    Card(colors = CardDefaults.cardColors()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(text = "Master Preview", style = MaterialTheme.typography.titleLarge)
            BlendPreviewThumbnail(
                path = state.masterGifPath,
                emptyStateText = "Generate Master Blend",
                contentDescription = "Master blend preview"
            )
        }
    }
}
