package ai.activeseat.app.prediction

import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

private const val PREDICT_PATH = "/api/predict"
private const val CONNECT_TIMEOUT_MS = 2000
private const val READ_TIMEOUT_MS = 3000

/** One axis of a prediction. [strength] is 0..1 and [probabilities] covers every direction. */
data class AxisPrediction(
    val value: String,
    val probability: Double,
    val strength: Double,
    val probabilities: Map<String, Double>,
)

data class Prediction(
    val horizonSec: Double,
    val lateral: AxisPrediction,
    val longitudinal: AxisPrediction,
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
        Prediction(
            horizonSec = item.getDouble("horizonSec"),
            lateral = parseAxis(item.getJSONObject("lateral")),
            longitudinal = parseAxis(item.getJSONObject("longitudinal")),
            hint = item.getDouble("hint"),
        )
    }
}

private fun parseAxis(axis: JSONObject): AxisPrediction {
    val probabilities = axis.getJSONObject("probabilities")
    return AxisPrediction(
        value = axis.getString("value"),
        probability = axis.getDouble("probability"),
        strength = axis.getDouble("strength"),
        probabilities = probabilities.keys().asSequence().associateWith(probabilities::getDouble),
    )
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
