package xyz.znix.xftl.drones

import org.jdom2.Element
import xyz.znix.xftl.Ship
import xyz.znix.xftl.crew.AbstractCrew
import xyz.znix.xftl.crew.AbstractCrewDamage
import xyz.znix.xftl.crew.CrewBlueprint
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.layout.Room
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.savegame.ObjectRefs
import xyz.znix.xftl.savegame.RefLoader
import xyz.znix.xftl.savegame.SaveUtil
import xyz.znix.xftl.weapons.DroneBlueprint

/**
 * A drone that operates on the inside of a ship, like a repair or boarding drone.
 */
abstract class AbstractIndoorsDrone(type: DroneBlueprint) : AbstractDrone(type) {
    /**
     * The type of slot that represents whether this drone counts as a crewmember
     * or an enemy for the purposes of occupying a cell in a room (this is where
     * you can have an enemy and a crewmember standing in the same cell, but not
     * two crewmembers in the same cell).
     */
    abstract val occupancySlotType: AbstractCrew.SlotType

    /**
     * The name of the blueprint for the fake crew race that's used for drawing the drone.
     *
     * This matches up with the spritesheets in img/ship/drones, and with
     * the crewBlueprint in the XML.
     */
    abstract val pawnCodename: String

    /**
     * The ship this drone is aboard. This may be different to [ownerShip],
     * in the case of boarding drones.
     */
    lateinit var ship: Ship
        private set

    var pawn: Pawn? = null
        private set

    /**
     * Spawn in [targetRoom], or one of the nearby rooms if that's full.
     */
    protected fun spawn(targetRoom: Room) {
        ship = targetRoom.ship

        val emptySpace = ship.findSpaceForCrew(targetRoom, occupancySlotType)

        pawn = makePawn(emptySpace.room)
        ship.crew += pawn!!

        // Move the drone to it's starting position
        pawn!!.jumpTo(emptySpace)
    }

    protected open fun makePawn(room: Room): Pawn {
        return Pawn(room)
    }

    override fun removeInstance() {
        super.removeInstance()

        // If the pawn was never spawned, there's nothing to clean up.
        val pawn = this.pawn ?: return
        this.pawn = null

        // Destroy the pawn
        pawn.removeFromShip()
    }

    override fun update(dt: Float) {
        super.update(dt)

        // If something weird happens and our pawn disappears
        // from the enemy ship, assume it was destroyed.
        val pawn = this.pawn
        if (pawn != null && !pawn.room.ship.crew.contains(pawn)) {
            destroy()
        }
    }

    override fun onEnemyShipUpdated() {
        super.onEnemyShipUpdated()

        // If we're on the former enemy ship, destroy ourselves.
        if (pawn != null && ship != ownerShip && ship != ownerShip.sys.getEnemyOf(ownerShip)) {
            destroy()
        }
    }

    protected abstract fun updatePawn(dt: Float)

    protected abstract fun drawPawn()

    private fun getPawnBlueprint(game: InGameState): CrewBlueprint {
        return game.blueprintManager[pawnCodename] as CrewBlueprint
    }

    override fun saveToXML(elem: Element, refs: ObjectRefs) {
        super.saveToXML(elem, refs)

        if (pawn != null) {
            val pawnElem = Element("pawn")
            pawn!!.saveToXML(pawnElem, refs)
            elem.addContent(pawnElem)
        }
    }

    override fun loadFromXML(elem: Element, refs: RefLoader, containingShip: Ship) {
        super.loadFromXML(elem, refs, containingShip)

        ship = containingShip

        val pawnElem = elem.getChild("pawn")
        if (pawnElem != null) {
            // Pick an arbitrary room to spawn in, we'll move while loading the pawn's XML anyway.
            pawn = makePawn(ship.rooms[0])
            pawn!!.loadFromXML(pawnElem, refs)

            // Don't add the pawn to the crew list until we've loaded
            // our owner ship, since that's required for most things.
            refs.addOnResolveFunction {
                ship.crew.add(pawn!!)
            }
        }
    }

    /**
     * An indoors drone comprises two objects: the drone object itself, and
     * a crewmember 'pawn'. This is to allow drones like boarding drones that
     * first cross the screen and can be shot down, or otherwise don't
     * immediately spawn. Also, [AbstractCrew] needs the initial room in its
     * constructor, which is inconvenient for us.
     */
    open inner class Pawn(room: Room) :
        AbstractCrew(getPawnBlueprint(room.ship.sys), room.ship.sys.animations, room) {

        val drone: AbstractIndoorsDrone = this@AbstractIndoorsDrone

        var powerUpDuration: Float = 0f
        var runningLastUpdate: Boolean = false

        override val canManSystem: Boolean get() = false
        override val canFight: Boolean get() = false
        override val canRepair: Boolean get() = false
        override val hasDyingAnimation: Boolean get() = false
        override val suffocationMultiplier: Float get() = 0f
        override val fireDamageMult: Float get() = 0f

        override val mode: SlotType get() = occupancySlotType

        override val providesPlayerVision: Boolean get() = isPowered && ownerShip.isPlayerShip

        override fun update(dt: Float) {
            // If the ship powering this drone has jumped away, destroy it.
            // Also self-destruct if the pawn field no longer points to this
            // pawn for whatever reason. It shouldn't happen, but this could
            // avoid a drone getting stuck on the player ship.
            if (!ship.sys.isShipPresent(ownerShip) || pawn != this) {
                removeFromShip()
                return
            }

            // If the drone was turned off or on again, we only apply that
            // when the game unpauses.
            if (isRunning != runningLastUpdate) {
                if (isRunning) {
                    powerUpDuration = ship.sys.animations["${codename}_power_up"].totalTime
                } else {
                    powerUpDuration = 0f
                }

                runningLastUpdate = isRunning
                updateAnimation()
            }

            // Only progress on movement, fighting, repairs, etc if we're powered,
            // and the power-up/power-down animation isn't playing.
            val on = isRunning && powerUpDuration == 0f
            if (on) {
                super.update(dt)

                // Note the pawn can be null here if it was killed
                // during the update cycle.
                if (pawn != null)
                    updatePawn(dt)
            } else if (health == 0f) {
                // Dying is implemented by super.update, so we have to call it
                // when we're dead, otherwise we'd become invincible while
                // powered off.
                super.update(dt)
            } else {
                icon.update(dt)

                if (powerUpDuration != 0f && isRunning) {
                    powerUpDuration -= dt
                    if (powerUpDuration < 0f) {
                        powerUpDuration = 0f
                        updateAnimation()
                    }
                }
            }
        }

        override fun updateAnimation() {
            if (powerUpDuration != 0f) {
                icon = ship.sys.animations["${codename}_power_up"].startSingle(game)
                return
            }
            if (!runningLastUpdate) {
                icon = ship.sys.animations["${codename}_power_down"].startSingle(game)
                return
            }

            super.updateAnimation()
        }

        override fun draw(g: Graphics) {
            super.draw(g)
            drawPawn()
        }

        override fun dealDamage(damage: AbstractCrewDamage) {
            if (damage.halvedForDrone) {
                damage.amount /= 2f
            }

            super.dealDamage(damage)
        }

        override fun removeFromShip() {
            super.removeFromShip()

            // Cleanly kill the drone
            // If pawn==null then we're being called by removeInstance,
            // in which case we're not supposed to explode.
            if (drone.pawn == null) {
                // Call it again to be certain, though.
                removeInstance()
                return
            }

            drone.pawn = null
            destroy()

            // Play the explosion animation whenever a drone is killed.
            ship.playCentredAnimation(explodeAnimation, getPixelPositionCentre())
            explodeSound.play()
        }

        override fun saveToXML(elem: Element, refs: ObjectRefs) {
            super.saveToXML(elem, refs)

            SaveUtil.addAttrFloat(elem, "powerUpDuration", powerUpDuration)
            SaveUtil.addAttrBool(elem, "onLastUpdate", runningLastUpdate)
        }

        override fun loadFromXML(elem: Element, refs: RefLoader) {
            // These are used to set the animation, so we have to load them first.
            powerUpDuration = SaveUtil.getAttrFloat(elem, "powerUpDuration")
            runningLastUpdate = SaveUtil.getAttrBool(elem, "onLastUpdate")

            super.loadFromXML(elem, refs)
        }
    }
}
