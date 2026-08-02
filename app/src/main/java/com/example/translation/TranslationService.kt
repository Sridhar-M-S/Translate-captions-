package com.example.translation

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class TranslationService {

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    /**
     * Removes emojis, musical notes, and non-dialogue decorative symbols from caption text.
     */
    fun cleanDialogue(text: String): String {
        return text.replace(Regex("[\\p{So}\\p{Cs}\\p{Cn}\\p{Cf}\\p{Co}\\p{Symbol}]"), "")
            .replace(Regex("[♪♫♬♩♥★♦♣♠➔→←↔▲▼◄►■□▪▫•●○#\\[\\]{}]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /**
     * Translates the given text into the target language using Google's free translation API.
     */
    suspend fun translateGoogle(text: String, targetLanguage: String): TranslationResult = withContext(Dispatchers.IO) {
        try {
            val cleanedText = cleanDialogue(text)
            if (cleanedText.isEmpty()) {
                return@withContext TranslationResult.Success(text, "", "auto")
            }

            val actualTargetLang = if (targetLanguage == "tanglish") "ta" else targetLanguage
            val encodedText = URLEncoder.encode(cleanedText, "UTF-8")
            val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=$actualTargetLang&dt=t&q=$encodedText"

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext TranslationResult.Error("HTTP Error: ${response.code}")
                }

                val body = response.body?.string() ?: return@withContext TranslationResult.Error("Empty response body")
                val jsonArray = JSONArray(body)
                val sentencesArray = jsonArray.getJSONArray(0)
                val translatedBuilder = StringBuilder()
                var detectedLang = "en"

                for (i in 0 until sentencesArray.length()) {
                    val sentence = sentencesArray.getJSONArray(i)
                    translatedBuilder.append(sentence.getString(0))
                }

                if (jsonArray.length() > 2) {
                    try {
                        detectedLang = jsonArray.getString(2)
                    } catch (e: Exception) {
                        detectedLang = "auto"
                    }
                }

                return@withContext TranslationResult.Success(
                    originalText = text,
                    translatedText = translatedBuilder.toString(),
                    detectedLanguage = detectedLang
                )
            }
        } catch (e: Exception) {
            Log.e("TranslationService", "Google translation failed", e)
            return@withContext TranslationResult.Error(e.localizedMessage ?: "Unknown error")
        }
    }

    /**
     * Translates using the Gemini API via direct HTTP calls to remain 100% stable and crash-free.
     */
    suspend fun translateGemini(text: String, targetLanguage: String): TranslationResult = withContext(Dispatchers.IO) {
        val cleanedText = cleanDialogue(text)
        if (cleanedText.isEmpty()) {
            return@withContext TranslationResult.Success(text, "", "auto")
        }

        val apiKey = com.example.BuildConfig.GEMINI_API_KEY
        if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY") {
            Log.d("TranslationService", "No Gemini key, falling back to Google Translate")
            return@withContext translateGoogle(text, targetLanguage)
        }

        try {
            val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=$apiKey"
            
            val langName = when (targetLanguage) {
                "ta" -> "Tamil"
                "hi" -> "Hindi"
                "te" -> "Telugu"
                "kn" -> "Kannada"
                "ml" -> "Malayalam"
                "tanglish" -> "Tanglish (Tamil + English). The translation should sound like how people in Tamil Nadu naturally speak in everyday conversation. Mix common English words naturally, use Roman letters (English alphabet), not Tamil script. Keep grammar natural instead of doing a word-for-word translation. Examples: 'Please wait for a moment.' -> 'Konjam wait pannunga.', 'The meeting will start in five minutes.' -> 'Innum 5 minutes la meeting start aagum.', 'Click the button and continue.' -> 'Button click pannitu continue pannunga.'"
                else -> "Tamil"
            }

            val prompt = """
                Translate the following movie/video subtitle dialogue into extremely natural, conversational, and contextually accurate $langName. 
                Ignore any emojis, symbols, or non-dialogue characters if present. 
                Do NOT output any explanations, tags, notes, or original text. ONLY output the translated dialogue text.
                
                Subtitle: "$cleanedText"
            """.trimIndent()

            // JSON Request Body
            val requestJson = JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply {
                                put("text", prompt)
                            })
                        })
                    })
                })
            }

            val mediaType = "application/json; charset=utf-8".toMediaType()
            val requestBody = requestJson.toString().toRequestBody(mediaType)

            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .header("Content-Type", "application/json")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w("TranslationService", "Gemini API HTTP Error: ${response.code}, falling back")
                    return@withContext translateGoogle(text, targetLanguage)
                }

                val bodyString = response.body?.string() ?: return@withContext translateGoogle(text, targetLanguage)
                val jsonResponse = JSONObject(bodyString)
                val candidates = jsonResponse.getJSONArray("candidates")
                val firstCandidate = candidates.getJSONObject(0)
                val content = firstCandidate.getJSONObject("content")
                val parts = content.getJSONArray("parts")
                val textResult = parts.getJSONObject(0).getString("text").trim().removeSurrounding("\"")

                if (textResult.isNotEmpty()) {
                    return@withContext TranslationResult.Success(
                        originalText = text,
                        translatedText = textResult,
                        detectedLanguage = "auto"
                    )
                } else {
                    return@withContext translateGoogle(text, targetLanguage)
                }
            }
        } catch (e: Exception) {
            Log.e("TranslationService", "Gemini HTTP failed, falling back to Google", e)
            return@withContext translateGoogle(text, targetLanguage)
        }
    }
}

sealed class TranslationResult {
    data class Success(val originalText: String, val translatedText: String, val detectedLanguage: String) : TranslationResult()
    data class Error(val message: String) : TranslationResult()
}
