package xyz.znix.xftl.crew

import xyz.znix.xftl.Animations
import xyz.znix.xftl.layout.Room

class CrewGhost(blueprint: CrewBlueprint, animations: Animations, room: Room, mode: SlotType) :
    LivingCrew(blueprint, animations, room, mode) {

    override val maxHealth: Float get() = 50f
    override val suffocationMultiplier: Float get() = 0f

    // TODO somehow make them use humans sheets and animations but with
    //   reduced opacity! (otherwise crash if encountered since no animations defined)
}
