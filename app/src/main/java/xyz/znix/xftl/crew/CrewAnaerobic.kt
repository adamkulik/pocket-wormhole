package xyz.znix.xftl.crew

import xyz.znix.xftl.Animations
import xyz.znix.xftl.layout.Room

class CrewAnaerobic(blueprint: CrewBlueprint, animations: Animations, room: Room, mode: SlotType) :
    LivingCrew(blueprint, animations, room, mode) {

    override val movementSpeed: Float get() = BASE_MOVEMENT_SPEED * 0.85f
    override val suffocationMultiplier: Float get() = 0f
    override val anaerobicOxygenDrainRate: Float get() = 0.08f
}
