package ai.activeseat.app.prediction

import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

private const val PREDICT_PATH = "/api/predict"
private const val CONNECT_TIMEOUT_MS = 2000
private const val READ_TIMEOUT_MS = 3000

data class Prediction(
    val horizonSec: Double,
    val lateral: String,
    val lateralProbability: Double,
    val longitudinal: String,
    val longitudinalProbability: Double,
    val hint: Double,
)

sealed interface PredictResult {
    data object NotConfigured : PredictResult
    data class Success(val rttMs: Long, val serverMs: Long, val predictions: List<Prediction>) : PredictResult
    data class Failure(val message: String) : PredictResult
}

fun parsePredictResponse(body: String): List<Prediction> {
    val items = JSONObject(body).getJSONArray("predictions")
    return List(items.length()) { index ->
        val item = items.getJSONObject(index)
        val lateral = item.getJSONObject("lateral")
        val longitudinal = item.getJSONObject("longitudinal")
        Prediction(
            horizonSec = item.getDouble("horizonSec"),
            lateral = lateral.getString("value"),
            lateralProbability = lateral.getDouble("probability"),
            longitudinal = longitudinal.getString("value"),
            longitudinalProbability = longitudinal.getDouble("probability"),
            hint = item.getDouble("hint"),
        )
    }
}

/** Blocking client for `POST {url}/api/predict`. `url` is the server root. */
class PredictClient(url: String, private val token: String) {
    private val baseUrl = url.trim().trimEnd('/')

    val isConfigured: Boolean = baseUrl.isNotEmpty() && token.isNotBlank()

    fun predict(input: PredictionInput): PredictResult {
        if (!isConfigured) return PredictResult.NotConfigured
        val startedNs = System.nanoTime()
        return try {
            val connection = URL(baseUrl + PREDICT_PATH).openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("x-demo-token", token)
            connection.outputStream.use { it.write(input.toJson().toString().toByteArray()) }

            // Bodies are read to the end and disconnect() is not called, so the socket stays reusable.
            val code = connection.responseCode
            if (code !in 200..299) {
                connection.errorStream?.use { it.readBytes() }
                return PredictResult.Failure("http $code")
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val rttMs = (System.nanoTime() - startedNs) / 1_000_000
            PredictResult.Success(rttMs, JSONObject(body).optLong("serverMs"), parsePredictResponse(body))
        } catch (e: Exception) {
            PredictResult.Failure(e.message ?: e.javaClass.simpleName)
        }
    }
}
