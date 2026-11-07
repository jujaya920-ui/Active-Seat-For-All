package ai.activeseat.app.prediction

import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

private const val PREDICT_PATH = "/api/predict"
private const val CONNECT_TIMEOUT_MS = 2000
private const val READ_TIMEOUT_MS = 3000

/**
 * How likely the passenger's body is pushed one way 2 s ahead. [level] is 1..4 (weak..very strong),
 * the rounded [levelMean], and only means something when the push happens.
 */
data class PushPrediction(val pOn: Double, val level: Int, val levelMean: Double)

/**
 * Directions are the passenger's body, not the car: [forward] is braking, [backward] accelerating,
 * [right] the car turning left and [left] the car turning right.
 */
data class PushForecast(
    val horizonSec: Double,
    val forward: PushPrediction,
    val backward: PushPrediction,
    val left: PushPrediction,
    val right: PushPrediction,
)

sealed interface PredictResult {
    data object NotConfigured : PredictResult
    data class Success(val rttMs: Long, val serverMs: Long, val push: PushForecast) : PredictResult
    data class Failure(val message: String) : PredictResult
}

fun parsePredictResponse(body: String): PushForecast {
    val json = JSONObject(body)
    val push = json.getJSONObject("push")
    fun direction(name: String) = push.getJSONObject(name).let {
        PushPrediction(pOn = it.getDouble("pOn"), level = it.getInt("level"), levelMean = it.getDouble("levelMean"))
    }
    return PushForecast(
        horizonSec = json.getDouble("horizonSec"),
        forward = direction("forward"),
        backward = direction("backward"),
        left = direction("left"),
        right = direction("right"),
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
