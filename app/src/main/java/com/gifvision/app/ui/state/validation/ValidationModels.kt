package com.gifvision.app.ui.state.validation

/** Represents validation feedback for a single stream render request. */
sealed interface StreamValidation {
    /** Stream state passes all validation rules. */
    object Valid : StreamValidation

    /** Stream cannot render until the listed [reasons] are resolved. */
    data class Error(val reasons: List<String>) : StreamValidation
}

/** Represents validation feedback for the per-layer blend surface. */
sealed interface LayerBlendValidation {
    /** Layer blend inputs are ready for dispatch. */
    object Ready : LayerBlendValidation

    /** Layer blend is blocked until the listed [reasons] are addressed. */
    data class Blocked(val reasons: List<String>) : LayerBlendValidation
}

/** Represents validation feedback for the master blend surface. */
sealed interface MasterBlendValidation {
    /** Master blend inputs are ready for dispatch. */
    object Ready : MasterBlendValidation

    /** Master blend is blocked until the listed [reasons] are resolved. */
    data class Blocked(val reasons: List<String>) : MasterBlendValidation
}
