package xyz.znix.xftl.crew

import xyz.znix.xftl.Animations
import xyz.znix.xftl.layout.Room

class CrewSlug(blueprint: CrewBlueprint, animations: Animations, room: Room, mode: SlotType) :
    LivingCrew(blueprint, animations, room, mode) {

    override val isMindControlResistant: Boolean get() = true

    // Telepathy is implemented in Ship.drawInterior (red-tinted crew
    // silhouettes via AbstractCrew.drawTelepathy) - NOT as room vision.
}
