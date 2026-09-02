package xyz.znix.xftl.crew

import xyz.znix.xftl.Animations
import xyz.znix.xftl.layout.Room

class CrewMantis(blueprint: CrewBlueprint, animations: Animations, room: Room, mode: SlotType) :
    LivingCrew(blueprint, animations, room, mode) {

    override val repairSpeed: Float get() = super.repairSpeed * 0.5f
    override val attackDamageMult: Float get() = super.attackDamageMult * 1.5f
    override val movementSpeed: Float get() = BASE_MOVEMENT_SPEED * 1.2f
}
