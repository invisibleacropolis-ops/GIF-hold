package com.gifvision.app.navigation

/**
 * Centralizes route names used by the navigation host. Keeping them in one place prevents
 * accidental typos when wiring the bottom navigation and Compose destinations.
 */
sealed class GifVisionDestination(val route: String) {
    data object Layer : GifVisionDestination("layer/{layerId}") {
        const val ARG_LAYER_ID = "layerId"

        /** Helper for producing a concrete route such as "layer/1". */
        fun createRoute(layerId: Int) = "layer/$layerId"
    }

    data object MasterBlend : GifVisionDestination("masterBlend")
}
