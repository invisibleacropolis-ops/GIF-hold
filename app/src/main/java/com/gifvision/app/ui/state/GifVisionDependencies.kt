package com.gifvision.app.ui.state

import android.app.Application
import com.gifvision.app.media.AndroidShareRepository
import com.gifvision.app.media.FfmpegKitGifProcessingCoordinator
import com.gifvision.app.media.GifProcessingCoordinator
import com.gifvision.app.media.GifProcessingNotificationAdapter
import com.gifvision.app.media.MediaRepository
import com.gifvision.app.media.ScopedStorageMediaRepository
import com.gifvision.app.media.ShareRepository

/**
 * Aggregates the concrete dependencies consumed by [GifVisionViewModel]. Centralizing the factory
 * keeps construction logic in one place so tests and previews can supply lightweight substitutes
 * without modifying the view-model implementation.
 */
data class GifVisionDependencies(
    val mediaRepository: MediaRepository,
    val processingCoordinator: GifProcessingCoordinator,
    val shareRepository: ShareRepository,
    val notificationAdapter: GifProcessingNotificationAdapter
) {
    companion object {
        fun default(
            application: Application,
            notificationAdapter: GifProcessingNotificationAdapter = GifProcessingNotificationAdapter.Noop
        ): GifVisionDependencies {
            val mediaRepository = ScopedStorageMediaRepository(application)
            val processingCoordinator = FfmpegKitGifProcessingCoordinator(
                context = application,
                mediaRepository = mediaRepository,
                notificationAdapter = notificationAdapter
            )
            val shareRepository = AndroidShareRepository(application)
            return GifVisionDependencies(
                mediaRepository = mediaRepository,
                processingCoordinator = processingCoordinator,
                shareRepository = shareRepository,
                notificationAdapter = notificationAdapter
            )
        }
    }
}
