package xyz.znix.xftl.game

import org.jdom2.Element
import xyz.znix.xftl.Datafile
import xyz.znix.xftl.GameText
import xyz.znix.xftl.getGameTextChild
import xyz.znix.xftl.requireAttributeValue

class Achievement(elem: Element) {
    val id: String = elem.requireAttributeValue("id")
    val name: GameText = elem.getGameTextChild("name")!!
    val desc: GameText = elem.getGameTextChild("desc")!!
    val img: String = "img/" + elem.getChildTextTrim("img")
    val ship: String? = elem.getChildTextTrim("ship")
    val isMultiDifficulty: Boolean = elem.getChildTextTrim("multiDifficulty") == "1"

    class AchievementTable(df: Datafile) {
        val achievements: Map<String, Achievement>

        init {
            val doc = df.parseXML(df["data/achievements.xml"])
            check(doc.rootElement.name == "FTL")

            achievements = HashMap()

            for (elem in doc.rootElement.getChildren("achievement")) {
                val ach = Achievement(elem)
                achievements[ach.id] = ach
            }
        }

        operator fun get(id: String): Achievement {
            return achievements[id] ?: error("No such achievement with ID '$id'")
        }
    }
}
