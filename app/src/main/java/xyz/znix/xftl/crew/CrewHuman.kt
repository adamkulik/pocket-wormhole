package xyz.znix.xftl.crew

import xyz.znix.xftl.Animations
import xyz.znix.xftl.layout.Room
import xyz.znix.xftl.rendering.Image

class CrewHuman(blueprint: CrewBlueprint, animations: Animations, room: Room, mode: SlotType) :
    LivingCrew(blueprint, animations, room, mode) {

    override fun drawImage(x0: Float, y0: Float, x1: Float, y1: Float, baseFrame: Image, alpha: Float) {
        // For humans, enemies always draw without colouring.
        // IDK if they're also always male in vanilla, since that's likely
        // stored next to the colour there too, but if so I'm fine with an
        // inconsistency there.
        val skipColours = ownerShip?.isPlayerShip != true

        info.drawImage(x0, y0, x1, y1, baseFrame, alpha, skipColours)
    }
}
