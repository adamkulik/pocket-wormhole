package xyz.znix.xftl.game

import org.jdom2.Element

/**
 * One finished run, for the profile's stats: what vanilla's stats screen
 * (and its ae_prof.sav) counts. Crew hired isn't tracked yet, so it's 0.
 */
class RunRecord(
    val shipName: String,
    val shipId: String,
    val score: Int,
    /** 1-based, as vanilla shows it. */
    val sector: Int,
    val victory: Boolean,
    val difficulty: Difficulty,
    val advancedEdition: Boolean,
    val shipsDefeated: Int,
    val beaconsExplored: Int,
    val scrapCollected: Int,
    val crewHired: Int = 0
) {
    fun toXML(): Element = Element("run")
        .setAttribute("shipName", shipName)
        .setAttribute("shipId", shipId)
        .setAttribute("score", score.toString())
        .setAttribute("sector", sector.toString())
        .setAttribute("victory", victory.toString())
        .setAttribute("difficulty", difficulty.name)
        .setAttribute("ae", advancedEdition.toString())
        .setAttribute("ships", shipsDefeated.toString())
        .setAttribute("beacons", beaconsExplored.toString())
        .setAttribute("scrap", scrapCollected.toString())
        .setAttribute("crewHired", crewHired.toString())

    companion object {
        fun fromXML(elem: Element): RunRecord? = try {
            RunRecord(
                shipName = elem.getAttributeValue("shipName"),
                shipId = elem.getAttributeValue("shipId"),
                score = elem.getAttributeValue("score").toInt(),
                sector = elem.getAttributeValue("sector").toInt(),
                victory = elem.getAttributeValue("victory").toBoolean(),
                difficulty = Difficulty.valueOf(elem.getAttributeValue("difficulty")),
                advancedEdition = elem.getAttributeValue("ae").toBoolean(),
                shipsDefeated = elem.getAttributeValue("ships").toInt(),
                beaconsExplored = elem.getAttributeValue("beacons").toInt(),
                scrapCollected = elem.getAttributeValue("scrap").toInt(),
                crewHired = elem.getAttributeValue("crewHired")?.toInt() ?: 0
            )
        } catch (e: Exception) {
            null
        }
    }
}
