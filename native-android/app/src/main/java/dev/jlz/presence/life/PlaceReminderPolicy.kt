package dev.jlz.presence.life

import dev.jlz.presence.place.SemanticPlace

object PlaceReminderPolicy {
    fun keys(previous: SemanticPlace?, current: SemanticPlace, freshnessMs: Long, confidence: Float): List<String> {
        if (previous == null || previous == SemanticPlace.UNKNOWN || current == SemanticPlace.UNKNOWN ||
            previous == current || freshnessMs !in 0..300_000L || !confidence.isFinite() || confidence < 0.55f) return emptyList()
        return buildList {
            if (previous == SemanticPlace.HOME) add("depart_home")
            if (previous == SemanticPlace.OFFICE) add("depart_office")
            if (current == SemanticPlace.HOME) add("arrive_home")
            if (current == SemanticPlace.OFFICE) add("arrive_office")
        }
    }
}
