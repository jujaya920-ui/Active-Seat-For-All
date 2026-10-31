package ai.activeseat.app.demo

import android.content.Context

private const val SCENARIO_DIR = "scenarios"
private const val SUFFIX = ".json"

/** The only reader of scenario files. Scenario ids are file names without the suffix. */
class ScenarioRepository(context: Context) {
    private val assets = context.applicationContext.assets

    fun ids(): List<String> = assets.list(SCENARIO_DIR).orEmpty()
        .filter { it.endsWith(SUFFIX) }
        .map { it.removeSuffix(SUFFIX) }
        .sorted()

    fun load(id: String): Scenario =
        assets.open("$SCENARIO_DIR/$id$SUFFIX").bufferedReader().use { parseScenario(it.readText()) }
}
