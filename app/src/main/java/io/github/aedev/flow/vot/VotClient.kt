package io.github.aedev.flow.vot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

enum class VotStatus {
    SUCCESS, WAITING, FAILED
}

data class VotResult(
    val status: VotStatus,
    val audioUrl: String? = null,
    val message: String? = null
)

class VotClient(private val httpClient: OkHttpClient = OkHttpClient()) {

    companion object {
        // Актуальный HMAC-ключ и компонент из репозитория ilyhalight/voice-over-translation
        private const val HMAC_KEY = "bt8xH3VOlb4mqf0nqAibnDOoiPlXsisf"
        private const val YA_COMPONENT = "22.8.0.2145"
        private const val API_URL = "https://api.browser.yandex.ru/video-translation/translate"
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 YaBrowser/24.4.0.0 Safari/537.36"
    }

    /**
     * Запрашивает перевод у Яндекса и ждёт готовности (если перевод ещё в очереди).
     */
    suspend fun getTranslation(
        videoId: String,
        durationSec: Double,
        sourceLang: String = "en",
        targetLang: String = "ru"
    ): VotResult = withContext(Dispatchers.IO) {
        val videoUrl = "https://youtu.be/$videoId"
        
        // Пробуем опросить сервер (до 15 попыток с паузой, если статус WAITING)
        repeat(15) { attempt ->
            val payload = encodeTranslationRequest(
                url = videoUrl,
                duration = durationSec,
                firstRequest = (attempt == 0),
                requestLang = sourceLang,
                responseLang = targetLang
            )
            val signature = hmacSha256(payload)
            val uuid = UUID.randomUUID().toString().replace("-", "")

            val request = Request.Builder()
                .url(API_URL)
                .addHeader("Accept", "application/x-protobuf")
                .addHeader("User-Agent", USER_AGENT)
                .addHeader("Vtrans-Signature", signature)
                .addHeader("Sec-Vtrans-Token", uuid)
                .post(payload.toRequestBody("application/x-protobuf".toMediaType()))
                .build()

            try {
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext VotResult(VotStatus.FAILED, message = "HTTP ${response.code}")
                    }
                    val bytes = response.body?.bytes()
                        ?: return@withContext VotResult(VotStatus.FAILED, message = "Пустой ответ")

                    val parsed = decodeTranslationResponse(bytes)
                    when (parsed.status) {
                        VotStatus.SUCCESS -> return@withContext parsed
                        VotStatus.FAILED -> return@withContext parsed
                        VotStatus.WAITING -> {
                            // Перевод готовится на серверах Яндекса, ждём 5 секунд и повторяем
                            delay(5000)
                        }
                    }
                }
            } catch (e: Exception) {
                return@withContext VotResult(VotStatus.FAILED, message = e.localizedMessage)
            }
        }
        VotResult(VotStatus.FAILED, message = "Превышено время ожидания перевода")
    }

    private fun hmacSha256(data: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(HMAC_KEY.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(data).joinToString("") { "%02x".format(it) }
    }

    /**
     * Ручная упаковка Protobuf-сообщения VideoTranslationRequest
     * Поля по схеме VOT:
     * 3 (string) = url
     * 5 (bool)   = firstRequest
     * 6 (double) = duration
     * 7 (int32)  = unknown0 (1)
     * 8 (string) = language (исходный язык)
     * 14 (string)= responseLanguage (целевой язык)
     */
    private fun encodeTranslationRequest(
        url: String,
        duration: Double,
        firstRequest: Boolean,
        requestLang: String,
        responseLang: String
    ): ByteArray {
        val out = ByteArrayOutputStream()

        fun writeVarint(v: Int) {
            var value = v
            while ((value and 0x7F.inv()) != 0) {
                out.write((value and 0x7F) or 0x80)
                value = value ushr 7
            }
            out.write(value and 0x7F)
        }

        fun writeString(fieldNumber: Int, str: String) {
            val bytes = str.toByteArray(Charsets.UTF_8)
            writeVarint((fieldNumber shl 3) or 2) // wire type 2 (length-delimited)
            writeVarint(bytes.size)
            out.write(bytes)
        }

        fun writeBool(fieldNumber: Int, value: Boolean) {
            writeVarint((fieldNumber shl 3) or 0) // wire type 0 (varint)
            writeVarint(if (value) 1 else 0)
        }

        fun writeDouble(fieldNumber: Int, value: Double) {
            writeVarint((fieldNumber shl 3) or 1) // wire type 1 (64-bit)
            val bits = java.lang.Double.doubleToRawLongBits(value)
            for (i in 0..7) {
                out.write(((bits ushr (i * 8)) and 0xFF).toInt())
            }
        }

        writeString(3, url)
        writeBool(5, firstRequest)
        writeDouble(6, duration)
        writeBool(7, true) // unknown0 = 1
        writeString(8, requestLang)
        writeString(14, responseLang)

        return out.toByteArray()
    }

    /**
     * Чтение Protobuf-ответа VideoTranslationResponse:
     * 1 (string) = url (ссылка на mp3 с переводом)
     * 4 (int32)  = status (1 = SUCCESS, 2 = WAITING, 0/3 = FAILED)
     * 9 (string) = message (описание ошибки)
     */
    private fun decodeTranslationResponse(bytes: ByteArray): VotResult {
        var pos = 0
        var url: String? = null
        var statusInt = -1
        var message: String? = null

        fun readVarint(): Int {
            var result = 0
            var shift = 0
            while (pos < bytes.size) {
                val b = bytes[pos++].toInt()
                result = result or ((b and 0x7F) shl shift)
                if ((b and 0x80) == 0) break
                shift += 7
            }
            return result
        }

        while (pos < bytes.size) {
            val tag = readVarint()
            val fieldNumber = tag ushr 3
            val wireType = tag and 0x07

            when (wireType) {
                0 -> { // Varint
                    val v = readVarint()
                    if (fieldNumber == 4) statusInt = v
                }
                1 -> pos += 8 // 64-bit skip
                2 -> { // Length-delimited (String / Bytes)
                    val len = readVarint()
                    if (pos + len <= bytes.size) {
                        val str = String(bytes, pos, len, Charsets.UTF_8)
                        if (fieldNumber == 1) url = str
                        if (fieldNumber == 9) message = str
                    }
                    pos += len
                }
                5 -> pos += 4 // 32-bit skip
                else -> break
            }
        }

        val votStatus = when (statusInt) {
            1 -> if (!url.isNullOrEmpty()) VotStatus.SUCCESS else VotStatus.FAILED
            2 -> VotStatus.WAITING
            else -> VotStatus.FAILED
        }
        return VotResult(status = votStatus, audioUrl = url, message = message)
    }
}
