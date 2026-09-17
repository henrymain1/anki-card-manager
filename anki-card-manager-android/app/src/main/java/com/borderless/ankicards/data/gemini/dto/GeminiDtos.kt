package com.borderless.ankicards.data.gemini.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Minimal subset of the Gemini REST API request/response shape.
 * See: https://ai.google.dev/api/generate-content
 */

@Serializable
data class GenerateContentRequest(
    val contents: List<Content>,
    val generationConfig: GenerationConfig? = null
)

@Serializable
data class Content(
    val role: String? = null,
    val parts: List<Part>
)

@Serializable
data class Part(
    val text: String? = null,
    @SerialName("inlineData") val inlineData: InlineData? = null
)

@Serializable
data class InlineData(
    val mimeType: String,
    val data: String
)

@Serializable
data class GenerationConfig(
    val temperature: Float? = null,
    val responseMimeType: String? = null,
    val responseModalities: List<String>? = null,
    /**
     * Optional JSON schema. When set together with
     * [responseMimeType] = `"application/json"`, Gemini will reply with a JSON
     * object whose shape matches the schema. Used for structured card-field
     * generation: we send a schema describing one string property per LLM
     * field on the card type, and Gemini fills them in.
     */
    val responseSchema: JsonElement? = null,
    /**
     * Image-generation output config (Nano Banana family). Holds the
     * output `imageSize`, `aspectRatio`, and `personGeneration` fields.
     * Verified against a real AI Studio request body for Nano Banana 2 —
     * field shape from there is `generationConfig.imageConfig.imageSize: "512"`.
     */
    val imageConfig: ImageConfig? = null,
    /**
     * Controls how many reasoning tokens the model burns before producing
     * output. For image generation, `"MINIMAL"` is a free speedup —
     * deep thinking doesn't meaningfully improve image quality.
     */
    val thinkingConfig: ThinkingConfig? = null
)

@Serializable
data class ImageConfig(
    val aspectRatio: String? = null,
    val imageSize: String? = null,
    val personGeneration: String? = null
)

@Serializable
data class ThinkingConfig(
    val thinkingLevel: String? = null
)

@Serializable
data class GenerateContentResponse(
    val candidates: List<Candidate> = emptyList()
)

@Serializable
data class Candidate(
    val content: Content? = null,
    val finishReason: String? = null
)
