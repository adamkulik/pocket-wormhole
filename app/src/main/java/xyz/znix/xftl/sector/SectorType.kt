package xyz.znix.xftl.sector

import org.jdom2.Element
import xyz.znix.xftl.GameText
import xyz.znix.xftl.mapChildrenText
import xyz.znix.xftl.requireAttributeValue
import xyz.znix.xftl.requireAttributeValueInt

class SectorType(private val eventManager: EventManager, elem: Element) {
    val name: String = elem.requireAttributeValue("name")
    val soundtracks: List<String> = elem.getChild("trackList").mapChildrenText("track")
    val startEventName: String = let {
        // The final sector seems to have the wrong thing set here,
        // starting you at a random event rather than the initial dialogue.
        if (name == "FINAL")
            return@let "LAST_STAND_START"

        elem.getChildTextTrim("startEvent") ?: "START_BEACON"
    }
    val startEvent: IEvent get() = startEventName.let { eventManager[it] }

    /**
     * The (zero-indexed) earliest sector this is allowed to occur at.
     */
    val minSector: Int = elem.getAttributeValue("minSector")?.toInt() ?: -1

    val unique: Boolean = elem.getAttributeValue("unique")!!.toBoolean()

    val events: List<EventInfo> = elem.getChildren("event").map {
        val name = it.requireAttributeValue("name")
        val count = it.requireAttributeValueInt("min")..it.requireAttributeValueInt("max")
        EventInfo(name, count, eventManager[name])
    }

    val nameTextId: GameText
    val shortTextId: GameText

    val rarityOverrides: Map<String, Int>

    init {
        val nameList = elem.getChild("nameList")
        check(nameList.children.size == 1)
        val nameElem = nameList.getChild("name")

        // In vanilla, both of these are specified as localisation keys for
        // all sector types. However, mods don't have to.
        // I haven't checked if the short text behaviour is correct, this is
        // just a guess.
        // This is reproduced with the Insurrection mod.
        nameTextId = GameText.parse(nameElem)
        shortTextId = nameElem.getAttributeValue("short")?.let { GameText.localised(it) } ?: nameTextId

        // Load the rarity overrides
        check(elem.getChildren("rarityList").size <= 1)
        rarityOverrides = elem.getChild("rarityList")?.children?.map { e ->
            check(e.name == "blueprint")
            check(e.children.size == 0)
            Pair(e.requireAttributeValue("name"), e.requireAttributeValueInt("rarity"))
        }?.toMap() ?: emptyMap()
    }

    class EventInfo(val name: String, val count: IntRange, val event: IEvent)
}
