package xyz.znix.xftl.crew

import org.jdom2.Element
import xyz.znix.xftl.Blueprint
import xyz.znix.xftl.GameText
import xyz.znix.xftl.layout.Room
import xyz.znix.xftl.rendering.Colour

class CrewBlueprint(elem: Element) : Blueprint(elem) {
    override val cost: Int = elem.getChildTextTrim("cost").toInt()

    /**
     * A list of text ID keys corresponding to this race's special abilities.
     *
     * This is used for UI purposes only.
     */
    val powerStringIds: List<GameText> =
        elem.getChild("powerList")?.getChildren("power")?.map { GameText.parse(it) }
            ?: emptyList()

    /**
     * A list of all the colour filters that can be used as a filter
     * for rendering the layer1 (or in the case of humans, layer2) images.
     */
    val colourFilters: List<List<Colour>> = elem.getChild("colorList")
        ?.getChildren("layer")?.map { parseColourLayer(it) }
        ?: emptyList()

    /**
     * The images representing each of the layers tinted by [colourFilters].
     */
    val layerImageNames: List<String>

    /**
     * In the case of humans, this is the female version of [layerImageNames].
     *
     * For all other races, this is empty.
     */
    val femaleLayerImageNames: List<String>

    /**
     * The number of colour variations this crewmember has, not including
     * gender for humans.
     */
    val baseNumberOfColours: Int = colourFilters.maxOfOrNull { it.size } ?: 0

    /**
     * The number of colour variations this crewmember has.
     *
     * This is distinct from [colourFilters].size, as humans have male/female variants.
     */
    val numberOfColours: Int = run {
        return@run when (name) {
            "human" -> baseNumberOfColours * 2
            else -> baseNumberOfColours
        }
    }

    init {
        // Load all the layer images for this race
        layerImageNames = ArrayList()
        for (i in 1..colourFilters.size) {
            layerImageNames += "img/people/${name}_layer$i.png"
        }

        if (name == "human") {
            femaleLayerImageNames = layerImageNames.map { it.replace("human", "female") }
        } else {
            femaleLayerImageNames = emptyList()
        }
    }

    fun spawn(room: Room, mode: AbstractCrew.SlotType): LivingCrew {
        val animations = room.ship.sys.animations

        return when (name) {
            "human" -> CrewHuman(this, animations, room, mode)
            "engi" -> CrewEngi(this, animations, room, mode)
            "mantis" -> CrewMantis(this, animations, room, mode)
            "rock" -> CrewRock(this, animations, room, mode)
            "energy" -> CrewZoltan(this, animations, room, mode)
            "slug" -> CrewSlug(this, animations, room, mode)
            "crystal" -> CrewCrystal(this, animations, room, mode)
            "anaerobic" -> CrewAnaerobic(this, animations, room, mode)
            "ghost" -> CrewGhost(this, animations, room, mode)

            // Until we've implemented all the others, don't give a hard error here.
            else -> {
                println("Warning: Cannot spawn crew of unimplemented race '$name', replacing with human")
                val human = room.ship.sys.blueprintManager["human"] as CrewBlueprint
                return human.spawn(room, mode)
            }
        }
    }

    companion object {
        private fun parseColourLayer(layerElem: Element): List<Colour> {
            val colourElements = layerElem.getChildren("color")

            return colourElements.map {
                // Alpha is 0-1, the others are 0-255
                Colour(
                    it.getAttributeValue("r").toInt() / 255f,
                    it.getAttributeValue("g").toInt() / 255f,
                    it.getAttributeValue("b").toInt() / 255f,
                    it.getAttributeValue("a").toFloat()
                )
            }
        }
    }
}
