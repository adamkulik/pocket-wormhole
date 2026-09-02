package xyz.znix.xftl.weapons

import org.jdom2.Element
import xyz.znix.xftl.*
import xyz.znix.xftl.crew.ShipDamage
import xyz.znix.xftl.drones.CombatDrone
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.layout.Room
import xyz.znix.xftl.math.*
import xyz.znix.xftl.rendering.BulkColourRenderer
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.savegame.ObjectRefs
import xyz.znix.xftl.savegame.RefLoader
import xyz.znix.xftl.savegame.SaveUtil
import xyz.znix.xftl.systems.SelectedTarget
import kotlin.math.*
import kotlin.random.Random
import kotlin.random.nextInt

class BeamBlueprint(xml: Element) : AbstractWeaponBlueprint(xml) {
    val length: Int = xml.getChildTextTrim("length").toInt()

    /**
     * Makes ion beams hit shield like zoltan shield. This is a Hyperspace tag.
     *
     * Without it, the ion damage is applied every frame.
     */
    val ionBeamFix: Boolean = xml.getChildTextTrim("ionBeamFix")?.toBoolean() ?: false

    // See the note on SPEED_MULTIPLIER.
    val speedPixelsPerSecond = (speed ?: DEFAULT_SPEED) * SPEED_MULTIPLIER

    val fireDuration: Float = 1f * length / speedPixelsPerSecond

    val beamColour: Colour? = xml.getChild("color")?.let {
        Colour(
            it.getChildTextTrim("r").toInt(),
            it.getChildTextTrim("g").toInt(),
            it.getChildTextTrim("b").toInt()
        )
    }

    private val transparentColour = Colour(beamColour ?: DEFAULT_COLOUR).also {
        it.a = 220f / 255f // This is largely guessed
    }

    override fun buildInstance(ship: Ship): AbstractWeaponInstance {
        return BeamInstance(ship)
    }

    inner class BeamInstance(ship: Ship) : AbstractWeaponInstance(this, ship) {
        override val isFiring: Boolean get() = target != null && isPowered
        private val firingTime: Float get() = max(0f, attackTimer - preFireDuration)

        /**
         * True if the beam is currently swiping across the enemy ship.
         *
         * This is distinct from [isFiring] because that's true during
         * the animation that runs before the beam switches on.
         */
        val isBeamOn: Boolean get() = isFiring && attackTimer >= preFireDuration
        private var attackTimer: Float = 0f

        private var target: SelectedTarget.BeamAim? = null
        private val originPos = Point(0, 0)

        // This is effectively a RoomPoint, but split up to make serialisation easier.
        private var lastPos = Point(INVALID_CELL_POS)
        private var lastRoomId: Int? = null

        private val contact: FTLAnimation

        // The duration and number of frames in the pre-firing animation
        private val preFireFrames: Int
        private val preFireDuration: Float

        // Copy this in as a mutable variable so it can be changed for drones.
        private var fireDuration: Float = this@BeamBlueprint.fireDuration

        // True if this beam is ready to pierce Zoltan shields. This has
        // to be kept, so a beam can instantly pierce a shield deployed
        // by a shield over-charger drone.
        private var superShieldReady: Boolean = false

        // This is the chain count at the moment the weapon started firing.
        // This is used for damage-chaining beams, which mods add.
        private var firingChainCount: Int = 0

        // This is used to show the damage number against super shields.
        // It's a bit horrible, but figuring out where the beam is coming
        // from when we deal super shield damage would be even worse.
        private var shieldHitPos: IPoint? = null

        var isOnDrone: Boolean = false

        init {
            // Turns out this is a one-frame animation, leave it looping in case
            // a mod replaces it or something.
            contact = ship.sys.animations[projectile!!].startLooping(ship.sys)

            // Beams set a per-frame time of 2/frameCount for frames between
            // the charged and fire frames.
            val frameDuration = 2f / animation.length
            preFireFrames = animation.fireFrame - animation.chargedFrame
            preFireDuration = preFireFrames * frameDuration
        }

        override fun render(g: Graphics) {
            if (isBeamOn) {
                // Draw the beam line
                // Note that since we're in image space, forwards is up so forwards
                // is negative Y. Use some 'long enough' arbitrary length.
                // Note we have to do this first, as we shift the beam inwards a little
                // to fix the flagship beam.
                val offset = animation.firePoint
                val damage = computeDamage().hullDamage
                val visibleStrength = max(1, damage)
                drawBeam(
                    g, ship.sys, visibleStrength,
                    offset + ConstPoint(0, 10),
                    offset + ConstPoint(0, -5000),
                    1.5f // Close enough to pi/2 since we won't see it
                )

                // Manually compute the frames rather than using an animation, since
                // we're controlling the laser progress on the same timer.
                val progress = firingTime / fireDuration
                val firingFrames = animation.length - animation.fireFrame
                val frameNum = animation.fireFrame + (firingFrames * progress).toInt()

                // Use coerceIn to ensure we don't access an invalid frame if the progress
                // is somehow exactly fireDuration.
                animation.spriteAt(ship.sys, frameNum.coerceIn(0 until animation.length)).draw()
            } else if (isFiring) {
                // Render the pre-beam-on animation
                // This is used by mods, for example Multiverse's LANCE_1 beam.
                val progress = (attackTimer / preFireDuration).coerceIn(0f, 1f)
                val frameNum = animation.chargedFrame + (preFireFrames * progress).toInt()
                animation.spriteAt(ship.sys, frameNum).draw()
            } else {
                super.render(g)
            }
        }

        fun renderInbound(g: Graphics) {
            renderInbound(g, originPos)
        }

        fun drawDroneBeam(g: Graphics, drone: CombatDrone) {
            if (!isFiring)
                return

            val fc = drone.flightController
            val droneCentre = Point(fc.position)

            // For some stupid reason, the beam doesn't come clean
            // out of the centre of the drone - it has to be offset
            // slightly.
            // For example, the Beam 2 drone is shifted one pixel
            // to the right, so we need to shift the beam origin
            // by that amount too.
            droneCentre.x += cos(fc.rotation).roundToInt()
            droneCentre.y += sin(fc.rotation).roundToInt()

            renderInbound(g, droneCentre)
        }

        private fun renderInbound(g: Graphics, from: IPoint) {
            // Draw the beam on the enemy ship, including the little contact burning animation

            // This runs during the pre-firing animation, so check if
            // the beam is actually turned on yet or not.
            if (!isBeamOn)
                return

            // This means we don't have to put !! on every usage of target
            val target = target!!

            // Figure out where the beam touches the ship
            val targetPos = pointAtTime(firingTime)

            // Calculate the point where the beam and the shield bubble intersect
            // (Note that if the shields are off, we still do this - but the beams
            //  on each side will appear the same, so it doesn't matter)

            val targetShip = target.targetShip
            val shieldSize = targetShip.shieldHalfSize
            val shieldOrigin = targetShip.shieldOrigin

            val intersections = findIntersections(from, targetPos, shieldSize, shieldOrigin)

            // If we only cross the shield layer once, pick the first solution.
            // Note it's valid to never cross the shield line, or cross it twice,
            // if the beam leaves the shield layer.
            val iFirst = intersections.first
            val iSecond = intersections.second
            shieldHitPos = intersections.first
            val shieldPoint: IPoint = when {
                iFirst == null -> targetPos // No attenuation
                iSecond == null -> iFirst // Crosses the bubble once, normal.

                // The beam is going into one side of the shield bubble
                // and out the other.
                // Check which is nearest, so the attenuation happens when
                // the line first crosses the shield bubble.
                iFirst.distToSq(from) < iSecond.distToSq(from) -> iFirst
                else -> iSecond
            }

            // Beams that do no damage (eg the fire beam) still have a line
            // drawn, which is the same as a one-power beam.
            val visualPower = computeDamage().hullDamage.coerceAtLeast(1)
            val piercing = computeDamageWithShields(targetShip)?.hullDamage?.coerceAtLeast(1)

            // Find the shield bubble's tangent, and use that to set the angle
            // of the end of the beam, so it cleanly lines up with the shield.
            // This can be tested on long but thin Zoltan ships, where if this
            // is wrong the angle of the end of the beam and the shields
            // will be easily visible.
            // Tangent equation from https://math.stackexchange.com/a/990013.
            val shieldNormal = atan2(
                (shieldPoint.y - shieldOrigin.y) * shieldSize.x.f.pow(2),
                (shieldPoint.x.f - shieldOrigin.x) * shieldSize.y.f.pow(2)
            )
            drawBeam(g, ship.sys, visualPower, from, shieldPoint, shieldNormal)

            // Draw the inside-the-shield-bubble part
            if (piercing == null)
                return

            // Draw the beam from the target pos to the shield, so we can re-use
            // the line end angle stuff.
            drawBeam(g, ship.sys, piercing, targetPos, shieldPoint, shieldNormal)

            // Draw the contact animation
            // FIXME hardcode the offset of the contact point relative
            //  to the image - I can't see this specified anywhere.
            contact.draw(targetPos.x - 24f, targetPos.y - 32f, beamColour ?: DEFAULT_COLOUR)
        }

        override fun update(dt: Float, chargeTime: Float, canCharge: Boolean) {
            super.update(dt, chargeTime, canCharge)

            if (isBeamOn) {
                val targetShip = target!!.targetShip

                // Don't block charging while firing - it does appear
                // to charge up even while firing!

                // Step across all the points the beam has moved
                // across in this frame. This is done to ensure
                // the beam can't skip rooms when running with
                // high delta-times.
                val onePixelTime = fireDuration / target!!.length
                val newFiringTime = min(firingTime + dt, fireDuration)
                var t = firingTime
                val tmp = Point(0, 0)
                while (t < newFiringTime) {
                    // Check if we've potentially crossed into a new room
                    tmp.set(pointAtTime(t))
                    t += onePixelTime
                    targetShip.screenPosToShipPos(tmp)

                    if (tmp != lastPos) {
                        lastPos.set(tmp)
                        val room = targetShip.shipToRoomPos(tmp)?.room

                        // Is this point outside the ship?
                        if (room == null) {
                            lastRoomId = null
                            continue
                        }

                        val damage = computeDamageWithShields(targetShip)

                        // Couldn't pierce the shields?
                        // If so, update the last room - that way, if the shields
                        // break while the beam is inside a room, it won't damage
                        // that room (which matches vanilla). This can be used to
                        // deal crew damage without doing any hull damage.
                        if (damage == null) {
                            lastRoomId = room.id
                            continue
                        }

                        // If we hit a new room, damage it
                        if (lastRoomId != room.id) {
                            lastRoomId = room.id

                            val roomDamage = damage.copy()

                            // Don't do any crew damage, we do that per-cell
                            roomDamage.pureCrewDamage = 0
                            roomDamage.noCrewDamage = true

                            // Same for fire/breach/stun
                            roomDamage.fireChance = 0
                            roomDamage.breachChance = 0
                            roomDamage.stunChance = 0

                            // The damage number pops up right above where the beam is
                            targetShip.damage(room, roomDamage, pointAtTime(t))
                        }

                        // Deal crew damage - this is done on entry to a new cell, not only to a new room.
                        val crewDamage = damage.effectiveCrewDamage.f
                        val crew = room.crew.filter { it.findNearestRoomPos().shipPoint posEq tmp }

                        for (crewmember in crew) {
                            crewmember.dealDamage(ShipDamage(crewDamage, damage))
                        }

                        // Fires and breaches (the latter is unused in vanilla) also
                        // only spawn on entry to a new cell.
                        val roomSlot = room.pointToSlot(tmp - room.position)
                        if (Random.rollChance(damage.fireChance)) {
                            room.spawnFireAt(roomSlot)
                        }
                        if (Random.rollChance(damage.breachChance)) {
                            room.spawnBreachAt(roomSlot)
                        }
                    }
                }

                // Damage the super-shield, if applicable.
                updateSuperShield(firingTime, newFiringTime)

                // Save our updated progress.
                attackTimer = preFireDuration + newFiringTime

                // Check if we've hit the end of our travel.
                if (firingTime >= fireDuration) {
                    stopFiring()
                }

                contact.update(dt)
            } else if (isFiring) {
                // Play the pre-firing animation
                attackTimer += dt
            } else if (target != null) {
                // If we aren't shooting then we reset the weapon.
                // This handles the weapon being depowered while firing.
                stopFiring()
            }
        }

        private fun stopFiring() {
            target?.targetShip?.inboundBeams?.remove(this)

            attackTimer = 0f
            superShieldReady = false
            target = null

            // Reset these to make the savegame more compact
            fireDuration = this@BeamBlueprint.fireDuration
            firingChainCount = 0

            // Without this, if the first room hit was the same room as the
            // last one hit on the previous shot, no damage would be dealt.
            lastPos.set(INVALID_CELL_POS)
            lastRoomId = null

            originPos.set(ConstPoint.ZERO)
        }

        private fun updateSuperShield(oldFiringTime: Float, newFiringTime: Float) {
            // Guard against zero-time updates to avoid doing damage
            // more than once.
            if (oldFiringTime == newFiringTime) {
                return
            }

            val targetShip = target!!.targetShip

            // Check if we crossed the two points that let us arm our
            // super-shield piercing. Note that this stays armed, so that
            // if a super-shield drone creates a shield we'll remove it
            // instantly.
            val oldProgress = firingTime / fireDuration
            val newProgress = newFiringTime / fireDuration
            if (SUPER_SHIELD_HIT_1 in oldProgress..newProgress) {
                superShieldReady = true
            }
            if (SUPER_SHIELD_HIT_2 in oldProgress..newProgress && target!!.length > 20) {
                superShieldReady = true
            }

            // Apply damage when applicable.
            if (targetShip.superShield > 0 && superShieldReady) {
                superShieldReady = false
                // TODO ion armour (reverse ion field)
                // All beams do at least one damage against super-shields.
                // Clamp here to avoid repairing them if the weapon does negative damage.
                val damage = max(damage, 1) + max(ionDamage, 0) * 2

                if (shieldHitPos != null && damage > 0) {
                    targetShip.showDamageTextAt(shieldHitPos!!, damage, Constants.DAMAGE_COLOUR_ZOLTAN)
                }

                targetShip.superShield -= damage
            }

            // Apply the ion damage against the shields system.
            // Without the ionBeamFix, this re-creates a vanilla bug where
            // this damage is applied every frame, instantly zapping
            // the shields system.
            val activeShields = targetShip.shields?.activeShields ?: 0
            if (targetShip.superShield == 0 && activeShields > 0 && (superShieldReady || !ionBeamFix)) {
                superShieldReady = false
                targetShip.attackShieldsIon(Damage(type).copyIon())
            }
        }

        fun fire(target: SelectedTarget.BeamAim) {
            this.target = target
            target.targetShip.inboundBeams.add(this)

            type.launchSounds?.get()?.play()

            firingChainCount = chainCount
            fire()

            // Find the point the beam is going to come from. Only do this once per
            // shot so the beam doesn't bounce around.
            // This is mostly guessed from images of FTL, and may vary quite a bit.
            // So long as it looks reasonable though that shouldn't be an issue, since
            // this is purely cosmetic.
            // It can start in any of the four corners, and apply a large amount of
            // randomisation in addition.
            originPos.set(1000, 1000)

            if (VisualRandom.nextBoolean())
                originPos.x *= -1
            if (VisualRandom.nextBoolean())
                originPos.y *= -1

            val range = 350
            originPos.x += VisualRandom.nextInt(-range..range)
            originPos.y += VisualRandom.nextInt(-range..range)

            originPos += target.targetShip.shieldOrigin
        }

        fun fireFromDrone(drone: CombatDrone, target: SelectedTarget.BeamAim, duration: Float) {
            this.target = target
            fireDuration = duration
        }

        // For use by drones, so they can angle themselves correctly.
        fun getCurrentTargetPoint(): IPoint {
            if (!isFiring)
                return ConstPoint.ZERO

            return pointAtTime(firingTime)
        }

        private fun pointAtTime(time: Float): IPoint {
            val distanceAcross = (target!!.length * time / fireDuration).toInt()

            return target!!.startShipPoint + ConstPoint(
                (distanceAcross * cos(target!!.angle)).toInt(),
                (distanceAcross * sin(target!!.angle)).toInt()
            )
        }

        /**
         * Generate a beam swipe, as used by the drones and enemy ship AI.
         *
         * This starts from the specified room, and moving towards
         * the furthest-away room in that ship.
         */
        fun buildLongestAim(startRoom: Room): SelectedTarget.BeamAim {
            val furthestRoom = startRoom.ship.rooms.maxBy { it.pixelCentre.distToSq(startRoom.pixelCentre) }
            return buildAim(startRoom, furthestRoom, true)
        }

        fun buildAim(startRoom: Room, endRoom: Room, stopAtEnd: Boolean): SelectedTarget.BeamAim {
            val aim = SelectedTarget.BeamAim(this, -1, startRoom.ship, startRoom.pixelCentre)
            aim.angle = atan2(
                endRoom.pixelCentre.y.f - startRoom.pixelCentre.y,
                endRoom.pixelCentre.x.f - startRoom.pixelCentre.x
            )

            if (stopAtEnd) {
                // Don't swipe off the side of the ship, into empty space.
                aim.length = min(startRoom.pixelCentre.distTo(endRoom.pixelCentre), length)
            }

            aim.updateHitRooms()

            return aim
        }

        private fun computeDamage(): Damage {
            val damage = Damage(type)
            if (type.boost?.type == BoostType.DAMAGE) {
                damage.applyWeaponChaining((type.boost.perShot * firingChainCount).toInt())
            }
            return damage
        }

        /**
         * Calculate the damage we'll deal after passing through the
         * given ship's shields layer.
         *
         * This returns null if we can't penetrate their shields.
         */
        private fun computeDamageWithShields(targetShip: Ship): Damage? {
            if (targetShip.superShield > 0)
                return null

            val damage = computeDamage()

            val shieldLayers = targetShip.shields?.activeShields ?: 0

            // Check if we can get past the enemy shields.
            // This is a bit weird, here's a few notable cases:
            //    2 dmg + 2 sp, after 2 shields damage drops by 1 each
            //    0 dmg + 2 sp, after 1 shields "damage" is done, after two no longer
            //   -2 dmg + 4 sp, after 1 shield beam penetrates, afterwards no longer
            val piercesShields = damage.hullDamage + type.shieldPiercing > shieldLayers || shieldLayers == 0
            if (!piercesShields)
                return null

            // If we're doing negative damage (hull repair beams), then
            // we'll do the full repair amount if we penetrate the shields.
            // Otherwise, reduce the damage based on the number of shields.
            if (damage.hullDamage > 0) {
                val effectiveShields = (shieldLayers - type.shieldPiercing).coerceAtLeast(0)
                damage.hullDamage = (damage.hullDamage - effectiveShields).coerceAtLeast(0)
            }

            return damage
        }

        override fun saveToXML(elem: Element, refs: ObjectRefs) {
            super.saveToXML(elem, refs)

            SaveUtil.addTagFloat(elem, "attackTimer", attackTimer, 0f)
            SaveUtil.addTagInt(elem, "firingChainCount", firingChainCount, 0)
            SaveUtil.addTagFloat(elem, "fireDuration", fireDuration, this@BeamBlueprint.fireDuration)
            SaveUtil.addTagBoolIfTrue(elem, "superShieldReady", superShieldReady)

            if (target != null) {
                val targetElem = Element("target")
                target!!.saveToXML(targetElem, refs)
                elem.addContent(targetElem)
            }

            if (!(originPos posEq ConstPoint.ZERO)) {
                SaveUtil.addPoint(elem, "originPos", originPos)
            }

            if (!(lastPos posEq INVALID_CELL_POS)) {
                SaveUtil.addPoint(elem, "lastPos", lastPos)
            }
            SaveUtil.addTagInt(elem, "lastRoomId", lastRoomId, null)
        }

        override fun loadFromXML(elem: Element, refs: RefLoader) {
            super.loadFromXML(elem, refs)

            attackTimer = SaveUtil.getOptionalTagFloat(elem, "attackTimer") ?: 0f
            firingChainCount = SaveUtil.getOptionalTagInt(elem, "firingChainCount") ?: 0
            fireDuration = SaveUtil.getOptionalTagFloat(elem, "fireDuration") ?: this@BeamBlueprint.fireDuration

            val targetElem = elem.getChild("target")
            if (targetElem != null) {
                SelectedTarget.loadFromXML(targetElem, refs, { this }) { target ->
                    this.target = target as SelectedTarget.BeamAim

                    // Without this, the beam graphic won't render on the target ship.
                    // Note this only runs if the target is non-null.
                    if (!isOnDrone) {
                        target.targetShip.inboundBeams.add(this)
                    }
                }
            }

            if (elem.getChild("originPos") != null) {
                originPos.set(SaveUtil.getPoint(elem, "originPos"))
            }

            if (elem.getChild("lastPos") != null) {
                lastPos.set(SaveUtil.getPoint(elem, "lastPos"))
            }
            lastRoomId = SaveUtil.getOptionalTagInt(elem, "lastRoomId")
        }
    }

    private fun drawBeam(g: Graphics, game: InGameState, power: Int, src: IPoint, dst: IPoint, dstAngle: Float) {
        // The beam is drawn as a transparent-red-transparent gradient line
        // Find the tangent line of src-dst, and use that to find the edges of the beam
        val tangent = MutFPoint(-(src.y - dst.y).f, (src.x - dst.x).f)
        val length = sqrt(tangent.xf * tangent.xf + tangent.yf * tangent.yf)
        tangent.xf /= length
        tangent.yf /= length

        // Scale up the tangent vector based on the per-side width
        val width = 3 * power
        tangent.xf *= width
        tangent.yf *= width

        val srcA = src.fConst + tangent
        val srcB = src.fConst - tangent

        // The destination point has an angle, which determines the angle the
        // end of the beam is 'cut' at - this is so that it aligns cleanly with
        // the shield. Without this, when firing at a Zoltan ship (for example)
        // there's obviously a difference in angle between the end of the beam
        // and the shield, which looks rather ugly.
        // Thus dstAngle gives us the angle between the beam's tangent and
        // the shield's tangent, which we can then use to build a second tangent
        // line along the shield.
        val beamAngle = atan2(dst.y.f - src.y, dst.x.f - src.x)
        val angleDifference = dstAngle - beamAngle
        val dstWidth = width / cos(angleDifference)
        val dstTangentAngle = beamAngle - PIf / 2 + angleDifference
        val dstTangentX = cos(dstTangentAngle) * dstWidth
        val dstTangentY = sin(dstTangentAngle) * dstWidth

        val dstA = ConstFPoint(dst.x + dstTangentX, dst.y + dstTangentY)
        val dstB = ConstFPoint(dst.x - dstTangentX, dst.y - dstTangentY)

        g.drawCustomQuads { quads ->
            drawGradient(quads, srcA, srcB, dstA, dstB, transparentColour)
        }

        if (game.debugFlags.showBeamVectors.set) {
            // This lets us check if our normal calculation is correct
            drawDebugVectorAngle(g, dst, dstAngle, 20f, Colour.yellow)
        }
    }

    /**
     * Draw a quad that's opaque along a centre line, and fades to the edge.
     */
    private fun drawGradient(
        quads: BulkColourRenderer,
        srcA: FPoint, srcB: FPoint,
        dstA: FPoint, dstB: FPoint,
        colour: Colour
    ) {
        // Find the middle points where the colour should be strongest
        val srcMidX = (srcA.xf + srcB.xf) / 2
        val srcMidY = (srcA.yf + srcB.yf) / 2

        val dstMidX = (dstA.xf + dstB.xf) / 2
        val dstMidY = (dstA.yf + dstB.yf) / 2

        // Fade to transparent, but without blending to another colour in the process.
        val edge = Colour(colour)
        edge.a = 0f

        // Draw the A-side - srcA,srcMid,dstA,dstMid
        quads.pushVert(srcA.xf, srcA.yf, edge)
        quads.pushVert(dstA.xf, dstA.yf, edge)
        quads.pushVert(dstMidX, dstMidY, colour)
        quads.pushVert(srcMidX, srcMidY, colour)

        // Draw the B-side - srcB,srcMid,dstB,dstMid
        quads.pushVert(dstMidX, dstMidY, colour)
        quads.pushVert(srcMidX, srcMidY, colour)
        quads.pushVert(srcB.xf, srcB.yf, edge)
        quads.pushVert(dstB.xf, dstB.yf, edge)
    }

    private fun drawDebugVectorAngle(g: Graphics, origin: IPoint, angle: Float, length: Float, colour: Colour) {
        drawDebugVector(g, origin, cos(angle) * length, sin(angle) * length, colour)
    }

    private fun drawDebugVector(g: Graphics, origin: IPoint, x: Float, y: Float, colour: Colour) {
        g.colour = colour
        g.drawLine(
            origin.x.f, origin.y.f,
            origin.x + x, origin.y + y
        )
    }

    private fun findIntersections(src: IPoint, dst: IPoint, ellipse: IPoint, centre: IPoint): Pair<IPoint?, IPoint?> {
        // Solve the line-ellipse intersection - find the point that satisfies
        // both the ellipse and line equation, where w and h are the half-width
        // and half-height:
        //
        // x^2/w^2 + y^2/h^2 = 1   (ellipse equation)
        // y=mx+L                  (line equation, using L not c to free it up later)
        //
        // x^2/w^2 + (mx+L)^2/h^2 = 1
        // x^2/w^2 + ((mx)^2 + 2mxL + L^2)/h^2 = 1
        // x^2/w^2 + (mx)^2/h^2 + 2mxL/h^2 + L^2/h^2 - 1 = 0
        // x^2(1/w^2 + m^2/h^2) + 2mxL/h^2 + (L^2/h^2 - 1) = 0
        //
        // ax^2 + bx + c = 0 where:
        //   a = 1/w^2 + m^2/h^2
        //   b = 2mL/h^2
        //   c = L^2/h^2 - 1
        //
        // This is now a quadratic equation, solve it using the quadratic formula.

        // Find the line equation
        val deltaX = dst.x.f - src.x
        val deltaY = dst.y.f - src.y

        // Handle the special-case for vertical lines, since we otherwise
        // divide through by zero. This doesn't result in a crash, instead
        // the beam appears to pass through the shields as everything becomes
        // either NaN or infinity.
        // This can occur with beam drones, and became very quickly obvious
        // once they were first implemented, as it occurred surprisingly often!
        if (abs(deltaX) < 0.001f) {
            // Move the ellipse to the centre
            val x = src.x - centre.x

            // Outside the ellipse's area?
            if (x !in -ellipse.x..ellipse.x)
                return Pair(null, null)

            // Find the y coordinate of the shield at the given x point.
            // (there's another point on the shield line by negating this y)

            val shieldY = (ellipse.y * sqrt(1 - x.f.pow(2) / ellipse.x.f.pow(2))).toInt()

            fun yToPoint(y: Int): IPoint? {
                // Check this point sits inside the line segment.
                val minY = min(src.y, dst.y)
                val maxY = max(src.y, dst.y)
                if (y !in minY..maxY)
                    return null

                return ConstPoint(src.x, y)
            }

            val p1 = yToPoint(centre.y + shieldY)
            val p2 = yToPoint(centre.y - shieldY)

            if (p1 == null) {
                return Pair(p2, null)
            }
            return Pair(p1, p2)
        }

        val m = deltaY / deltaX // Rise over run

        // This is where we subtract out the centre so this is valid when the ellipse
        // is centred at the origin.
        @Suppress("LocalVariableName")
        val L = (src.y - centre.y) - (src.x - centre.x) * m

        val wSq = ellipse.x.f.pow(2)
        val hSq = ellipse.y.f.pow(2)
        val a = 1 / wSq + m.pow(2) / hSq
        val b = 2 * m * L / hSq
        val c = L.pow(2) / hSq - 1

        // Check if the determinant is negative, which implies complex answers
        // (AKA the beam missed the shield)
        val determinant = b.pow(2) - 4 * a * c
        if (determinant < 0)
            return Pair(null, null)

        fun xToPoint(x: Float): IPoint? {
            // Check this point sits inside the line segment.
            val minX = min(src.x, dst.x)
            val maxX = max(src.x, dst.x)
            if (x.toInt() + centre.x !in minX..maxX)
                return null

            val y = m * x + L
            return ConstPoint(x.toInt() + centre.x, y.toInt() + centre.y)
        }

        // Check if there's only one solution (the line is a tangent line to
        // a point on the shield)
        if (determinant == 0f)
            return Pair(xToPoint(-b / (2 * a)), null)

        val plusMinus = sqrt(determinant)

        val p1 = xToPoint((-b + plusMinus) / (2 * a))
        val p2 = xToPoint((-b - plusMinus) / (2 * a))

        // If we have a non-null solution, always return it as the first argument
        return if (p1 == null) Pair(p2, null) else Pair(p1, p2)
    }

    companion object {
        // Speed defaults to 5 according to:
        // https://ftl.fandom.com/wiki/Beam_(Weapons)#Beam_weapons_table
        // (By matching the speed of something like the Halbard beam (BEAM_2) with the XML)
        // This obviously isn't in pixels per second - the mini beam
        // has a speed of 3 and a length of 45, and it certainly doesn't
        // take 15 seconds to fire. Playing a YouTube video of someone
        // using Stealth A and firing a mini beam without pausing, and
        // using MPV to get the exact timestamps shows (to within a couple
        // frames at 60fps) a time of 0.933, another one with a Halbard
        // beam on Zoltan A at 0.966 (Billy1Kirby's speedruns are the source
        // videos I used, as a note).
        // This suggests that multiplying the speed by around 16.5 gives
        // us a pixel-per-second speed, though further research (either
        // disassembly or modifying the values in vanilla FTL) may be
        // warranted for high accuracy.
        private const val SPEED_MULTIPLIER = 16.5f

        // The default speed, in the same units as the XML.
        private const val DEFAULT_SPEED = 5

        // A bright red beam? This is largely guessed.
        private val DEFAULT_COLOUR = Colour(255, 30, 30)

        private val INVALID_CELL_POS = ConstPoint(-999, -999)

        private const val SUPER_SHIELD_HIT_1: Float = 0.33f
        private const val SUPER_SHIELD_HIT_2: Float = 0.80f
        private const val SUPER_SHIELD_MIN_LENGTH: Int = 21
    }
}
