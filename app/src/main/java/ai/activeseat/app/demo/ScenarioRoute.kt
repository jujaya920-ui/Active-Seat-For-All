package ai.activeseat.app.demo

import ai.activeseat.app.nav.NavCamera
import ai.activeseat.app.nav.NavCaution
import ai.activeseat.app.nav.NavMark
import ai.activeseat.app.nav.NavRoute
import ai.activeseat.app.nav.PlannedRoute

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
    cameras = cameras.map { it.toNavCamera() },
    cautionPoints = cautionPoints.map { it.toNavCaution() },
)

/**
 * The route guided before the driver left it. Its length is `routeLengthM`, since no timeline was
 * driven on it. Cameras, caution points and front events exist only for the driven route, so it
 * keeps those on the stretch both share.
 */
fun Scenario.toPlannedRoute(): PlannedRoute? = plannedRoute?.let { planned ->
    val shared = { atM: Double -> atM <= planned.leaveAtM }
    PlannedRoute(
        route = NavRoute(
            title = title,
            routePx = planned.routePx,
            lengthM = planned.routeLengthM,
            turns = planned.turns.map { it.toNavMark() },
            frontEvents = frontEvents.filter { shared(it.atM) }.map { it.toNavMark() },
            cameras = cameras.filter { shared(it.atM) }.map { it.toNavCamera() },
            cautionPoints = cautionPoints.filter { shared(it.atM) }.map { it.toNavCaution() },
        ),
        leaveAtM = planned.leaveAtM,
    )
}

private fun Scenario.timelineDistanceM() = timeline.zipWithNext().sumOf { (a, b) ->
    (a.speedKph + b.speedKph) / 2 * KPH_TO_MPS * (b.t - a.t)
}

private fun RouteMark.toNavMark() = NavMark(atM, type, description)

private fun CameraMark.toNavCamera() = NavCamera(atM, id, type, speedLimit, sectionAvrSpeed)

private fun CautionMark.toNavCaution() = NavCaution(atM, id, type)
