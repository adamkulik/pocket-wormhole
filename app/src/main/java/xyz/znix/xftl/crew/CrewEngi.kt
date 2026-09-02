package xyz.znix.xftl.crew

import xyz.znix.xftl.Animations
import xyz.znix.xftl.layout.Room

class CrewEngi(blueprint: CrewBlueprint, animations: Animations, room: Room, mode: SlotType) :
    LivingCrew(blueprint, animations, room, mode) {

    override val repairSpeed: Float get() = super.repairSpeed * 2f
    override val attackDamageMult: Float get() = super.attackDamageMult * 0.5f
}
