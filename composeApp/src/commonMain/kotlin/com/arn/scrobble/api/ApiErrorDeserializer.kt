package com.arn.scrobble.api

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

@Serializable(with = ApiErrorDeserializer::class)
data class ApiErrorResponse(
    val code: Int,
    val message: String,
)


object ApiErrorDeserializer : KSerializer<ApiErrorResponse> {

    override val descriptor = buildClassSerialDescriptor("ApiErrorResponse") {
        element<Int>("code")
        element<String>("message")
    }

    override fun serialize(encoder: Encoder, value: ApiErrorResponse) {
        val composite = encoder.beginStructure(descriptor)
        composite.encodeIntElement(descriptor, 0, value.code)
        composite.encodeStringElement(descriptor, 1, value.message)
        composite.endStructure(descriptor)
    }

//    val variant1 = "{\"error\":{\"#text\":\"Invalid resource specified\",\"code\":\"7\"}}" // gnufm
//    val variant2 =
//        "{\"message\":\"Invalid API key - You must be granted a valid key by last.fm\",\"error\":10}" // lastfm
//    val variant3 = "{\"code\": 200, \"error\": \"Invalid Method\"}" // listenbrainz
//    val variant4 = "{\"error\": \"Invalid token\"}" // maloja
//    val variant5 =  "{\"error\":{\"message\":\"Stuff\",\"status\": 404}}" // Spotify

    override fun deserialize(decoder: Decoder): ApiErrorResponse {
        val apiErrorResponse: ApiErrorResponse = if (decoder is JsonDecoder) {
            when (val jsonElement = decoder.decodeJsonElement()) {
                is JsonObject -> {
                    when (val errorObject = jsonElement["error"]) {
                        is JsonObject -> {
                            val code =
                                (errorObject["code"] ?: errorObject["status"])?.jsonPrimitive?.int
                                    ?: 0
                            val message =
                                (errorObject["message"]
                                    ?: errorObject["#text"])?.jsonPrimitive?.content
                                    ?: ""

                            ApiErrorResponse(
                                code = code,
                                message = message
                            )
                        }

                        is JsonPrimitive -> {
                            var message: String
                            val code: Int
                            if (errorObject.jsonPrimitive.isString) {
                                code = jsonElement["code"]?.jsonPrimitive?.int ?: 0
                                message = errorObject.jsonPrimitive.content
                            } else {
                                code = errorObject.jsonPrimitive.int
                                message = jsonElement["message"]?.jsonPrimitive?.content ?: ""
                            }

                            message = when (code) { // lastfm errors
                                17 -> "This profile is private"
                                8 -> "Last.fm is temporarily unavailable. Try again later."
                                9 -> "Session expired. Logout and log in again."
                                else -> message
                            }

                            ApiErrorResponse(
                                code = code,
                                message = message
                            )
                        }

                        else -> throw SerializationException("Unknown JSON structure")
                    }
                }

                else -> throw SerializationException("Unknown JSON structure")
            }
        } else {
            // Non-JSON: try normal decoding
            val composite = decoder.beginStructure(descriptor)
            var code = 0
            var message = ""
            loop@ while (true) {
                when (val index = composite.decodeElementIndex(descriptor)) {
                    0 -> code = composite.decodeIntElement(descriptor, index)
                    1 -> message = composite.decodeStringElement(descriptor, index)
                    else -> break@loop
                }
            }

            ApiErrorResponse(
                code = code,
                message = message
            )
        }

        return apiErrorResponse
    }

}