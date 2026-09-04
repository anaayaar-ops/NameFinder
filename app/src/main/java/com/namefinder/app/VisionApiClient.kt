package com.namefinder.app

import android.graphics.Bitmap
import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * يرسل صورة (سكرين شوت) لخدمة Google Cloud Vision - Web Detection،
 * ويرجع أفضل تخمين نصي (عادة اسم الشخصية أو العمل الفني المرتبط بالصورة).
 *
 * لازم مفتاح API من Google Cloud Console (فعّل خدمة Cloud Vision API).
 */
object VisionApiClient {

    private val client = OkHttpClient()

    fun detectName(bitmap: Bitmap, apiKey: String): String? {
        if (apiKey.isBlank()) return "لازم تضيف مفتاح API من إعدادات التطبيق"

        val base64Image = bitmapToBase64(bitmap)

        val requestJson = JSONObject().apply {
            put("requests", JSONArray().put(
                JSONObject().apply {
                    put("image", JSONObject().put("content", base64Image))
                    put("features", JSONArray().put(
                        JSONObject().apply {
                            put("type", "WEB_DETECTION")
                            put("maxResults", 15)
                        }
                    ))
                }
            ))
        }

        val body = requestJson.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("https://vision.googleapis.com/v1/images:annotate?key=$apiKey")
            .post(body)
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string() ?: return null
                parseBestGuess(responseBody)
            }
        } catch (e: Exception) {
            "خطأ بالاتصال: ${e.message}"
        }
    }

    private fun parseBestGuess(json: String): String? {
        return try {
            val root = JSONObject(json)
            val responses = root.getJSONArray("responses")
            if (responses.length() == 0) return null
            val webDetection = responses.getJSONObject(0).optJSONObject("webDetection") ?: return null

            // ١) أولوية: bestGuessLabels (أدق تخمين نصي لمحتوى الصورة - لكن غالبًا فاضي)
            val bestGuessLabels = webDetection.optJSONArray("bestGuessLabels")
            if (bestGuessLabels != null && bestGuessLabels.length() > 0) {
                val label = bestGuessLabels.getJSONObject(0).optString("label")
                if (label.isNotBlank()) return label
            }

            // ٢) webEntities: الكيانات المرتبطة بالصورة، مرتبة حسب درجة الثقة (score)
            // هذا الحقل غالبًا أدق من bestGuessLabels لصور الشخصيات والرسوم
            val entities = webDetection.optJSONArray("webEntities")
            if (entities != null) {
                var bestDesc: String? = null
                var bestScore = -1.0
                for (i in 0 until entities.length()) {
                    val entity = entities.getJSONObject(i)
                    val desc = entity.optString("description")
                    val score = entity.optDouble("score", 0.0)
                    if (desc.isNotBlank() && score > bestScore) {
                        bestScore = score
                        bestDesc = desc
                    }
                }
                if (!bestDesc.isNullOrBlank()) return bestDesc
            }

            // ٣) احتياطي: أول نتيجة من الصفحات المطابقة تمامًا (عنوان الصفحة غالبًا فيه الاسم)
            val fullMatches = webDetection.optJSONArray("pagesWithMatchingImages")
            if (fullMatches != null && fullMatches.length() > 0) {
                val title = fullMatches.getJSONObject(0).optString("pageTitle")
                if (title.isNotBlank()) return title
            }

            // ٤) احتياطي أخير: أول عنوان من الصفحات المطابقة جزئيًا (partial matches)
            val partialMatches = webDetection.optJSONArray("partialMatchingImages")
            if (partialMatches != null && partialMatches.length() > 0) {
                val title = partialMatches.getJSONObject(0).optString("pageTitle")
                if (title.isNotBlank()) return title
            }

            null
        } catch (e: Exception) {
            null
        }
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, stream)
        val bytes = stream.toByteArray()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
}
