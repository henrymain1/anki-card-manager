package com.borderless.ankicards.data.settings

/**
 * The user's choice of card-generation loading animation. Persisted in
 * [SettingsRepository] as a short [code] so the stored value survives the enum
 * changing shape.
 *
 * [Random] means "pick a different one each generation". The concrete styles
 * mirror `ui.common.CardLoaderStyle`; the generator screen maps this preference
 * onto that UI enum (resolving [Random] to an actual style per generation). We
 * keep this enum in the data layer so persistence doesn't depend on a UI type.
 */
enum class LoaderStylePreference(val code: String, val displayName: String) {
    /** A different animation each time you generate. Default. */
    Random("random", "Surprise me"),

    /** The pulsing 3D orb that emits waves. */
    Orb("orb", "Orb"),

    /** Drifting sparkle particles. */
    Sparkles("sparkles", "Sparkles");

    companion object {
        fun fromCode(code: String?): LoaderStylePreference =
            entries.firstOrNull { it.code == code } ?: Random
    }
}
