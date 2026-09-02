package xyz.znix.xftl.crew

import xyz.znix.xftl.Animations
import xyz.znix.xftl.augments.AugmentBlueprint
import xyz.znix.xftl.layout.Room

class CrewCrystal(blueprint: CrewBlueprint, animations: Animations, room: Room, mode: SlotType) :
    LivingCrew(blueprint, animations, room, mode) {

    override val suffocationMultiplier: Float
        get() {
            val oxygenMasks = getAugmentValue(AugmentBlueprint.OXYGEN_MASKS) ?: 1f
            return 0.5f * oxygenMasks
        }

    override val movementSpeed: Float get() = BASE_MOVEMENT_SPEED * 0.8f
    override val maxHealth: Float get() = 125f
    override val fireFightingSpeed: Float get() = 0.83f

    // TODO implement special ability
}
