package com.gifvision.app.ui.master

import androidx.compose.runtime.Composable
import com.gifvision.app.ui.components.BlendPreviewThumbnail
import com.gifvision.app.ui.components.preview.GifPreviewCard
import com.gifvision.app.ui.resources.PanelCopy
import com.gifvision.app.ui.state.MasterBlendConfig

@Composable
internal fun MasterPreviewCard(state: MasterBlendConfig) {
    GifPreviewCard(
        title = PanelCopy.MASTER_PREVIEW_TITLE,
        previewContent = {
            BlendPreviewThumbnail(
                path = state.masterGifPath,
                emptyStateText = PanelCopy.MASTER_PREVIEW_EMPTY_STATE,
                contentDescription = "Master blend preview"
            )
        },
        actions = {}
    )
}
