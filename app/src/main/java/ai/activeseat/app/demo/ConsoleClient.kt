package ai.activeseat.app.demo

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

const val CONSOLE_URL = "http://127.0.0.1:8088"
private const val TIMEOUT_MS = 2000

/** All calls throw IOException on failure. */
interface ConsoleApi {
    suspend fun session()
    suspend fun controls(controls: ConsoleControls)
    suspend fun turnSignal(mode: String)
    suspend fun gear(gear: String)
}

/** The emulator's Virtual Console HTTP API. */
class ConsoleClient(
    private val baseUrl: String = CONSOLE_URL,
    private val clientId: String = "activeseat",
) : ConsoleApi {
    override suspend fun session() = request("POST", "/browser/session?client=$clientId")

    override suspend fun controls(controls: ConsoleControls) = request(
        "PUT",
        "/vehicle/controls?speed=${controls.speed}&accelerator=${controls.accelerator}" +
            "&brake=${controls.brake}&steering=${controls.steering}",
    )

    override suspend fun turnSignal(mode: String) = request("PUT", "/vehicle/turn-signal?mode=$mode")

    override suspend fun gear(gear: String) = request("PUT", "/vehicle/gear?gear=$gear")

    private suspend fun request(method: String, path: String) = withContext(Dispatchers.IO) {
        val connection = URL(baseUrl + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("$method $path -> http $code")
            connection.inputStream.use { it.readBytes() }
            Unit
        } finally {
            connection.disconnect()
        }
    }
}
