package ai.activeseat.app.demo

import ai.activeseat.app.nav.NavMark
import ai.activeseat.app.nav.NavRoute

private const val KPH_TO_MPS = 1.0 / 3.6

/**
 * The only bridge from scenarios to navigation. The length is the integrated timeline, not
 * `routeLengthM`: `routePx` and every `atM` are built on that scale, so this places each
 * vertex at its turn and makes arrival coincide with the end of the timeline.
 */
fun Scenario.toNavRoute() = NavRoute(
    title = title,
    routePx = routePx,
    lengthM = timelineDistanceM().takeIf { it > 0 } ?: routeLengthM,
    turns = turns.map { it.toNavMark() },
    frontEvents = frontEvents.map { it.toNavMark() },
)

private fun Scenario.timelineDistanceM() = timeline.zipWithNext().sumOf { (a, b) ->
    (a.speedKph + b.speedKph) / 2 * KPH_TO_MPS * (b.t - a.t)
}

private fun RouteMark.toNavMark() = NavMark(atM, type, description)
