package xyz.znix.xftl.game

import xyz.znix.xftl.AnimationSpec
import xyz.znix.xftl.Animations
import xyz.znix.xftl.SILFontLoader
import xyz.znix.xftl.Ship
import xyz.znix.xftl.layout.Room
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.random
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.sector.ASBTarget
import xyz.znix.xftl.weapons.ASBProjectile
import kotlin.random.Random

/**
 * Runs the Anti-Ship Battery hazard (issue #77) for the current beacon.
 *
 * Vanilla behaviour pinned from FTLGame.exe 1.6.14 by Ghidra (see
 * issue-77-110-asb-plan.md, "EXE RE-PIN"): applying the PDS environment
 * schedules the first damaging volley 20-25 s out, and ambient cosmetic
 * shots every 2.0-3.9 s (0.5 s after a damaging shot). Every volley
 * fires one shot at a random room of each matching ship (target:
 * player/enemy/all), each arriving from off-screen the top. Each shot's
 * launch plays fleetFireLight (ambient) or fleetFireHeavy (damaging);
 * while a damaging shot is imminent (last 5 s) the "ASB TARGET LOCKED!"
 * banner (HL1 font, by-eye pinned) shows and the surgeWarning alarm
 * sounds once (by-ear pinned - the round-1 exe pick environWarning was
 * wrong). The HUD shows
 * the red danger_pds icon with a DANGER! label (green icon + ALLIES!
 * when the ASB targets the enemy, i.e. friendly batteries).
 *
 * The exe's queued-shot list is only ever filled by the save loader, so
 * how a fresh fight schedules its damaging shots is unpinned - we use
 * the simple 20-25 s cycle, which matches every pinned constant.
 *
 * Arming is event-driven: `<environment type="PDS" target="...">` on any
 * loaded event (the fleet-elite fights, NO_FUEL_FLEET_DLC, the Lanius
 * assist, PDS_TEST, the fed-base assist branches...) arms the battery
 * via [InGameState.loadEventShip]. The state is transient - it isn't
 * saved (vanilla does serialise it; see the plan's follow-up note).
 */
class ASBController(private val game: InGameState) {
    private var armedTarget: ASBTarget? = null

    // Time until the next ambient (cosmetic) shot.
    private var ambientTimer = 0f

    // Time until the next damaging shot.
    private var salvoTimer = 0f

    // Whether the environWarning alarm has played for the current
    // LOCKED banner window (edge-triggered, like vanilla's banner).
    private var alarmPlayed = false

    // Lazy: the controller is constructed alongside InGameState, before
    // its datafile is ready for image/sound/font loads.
    private val iconRed by lazy { game.getImg("img/warnings/danger_pds.png") }
    private val iconGreen by lazy { game.getImg("img/warnings/danger_pds_green.png") }
    private val backglowRed by lazy { game.getImg("img/warnings/backglow_warning_red.png") }
    private val backglowGreen by lazy { game.getImg("img/warnings/backglow_warning_green.png") }
    private val fireLightSound by lazy { game.sounds.getSampleOrWarn("fleetFireLight") }
    private val fireHeavySound by lazy { game.sounds.getSampleOrWarn("fleetFireHeavy") }

    // The label/banner fonts - the banner is separate since round-3 (the
    // label's JustinFont11Bold is pinned, the banner font isn't). Both are
    // debug-cyclable via the asbfont / asbbanner console commands while we
    // pin which vanilla fonts the exe uses.
    private var loadedFontName: String? = null
    private var labelFontCache: SILFontLoader? = null
    private var loadedBannerFontName: String? = null
    private var bannerFontCache: SILFontLoader? = null

    private fun labelFont(): SILFontLoader {
        val name = debugLabelFont ?: LABEL_FONT
        if (labelFontCache == null || loadedFontName != name) {
            labelFontCache = game.getFont(name)
            loadedFontName = name
        }
        return labelFontCache!!
    }

    private fun bannerFont(): SILFontLoader {
        val name = debugBannerFont ?: BANNER_FONT
        if (bannerFontCache == null || loadedBannerFontName != name) {
            bannerFontCache = game.getFont(name)
            loadedBannerFontName = name
        }
        return bannerFontCache!!
    }

    /**
     * The lock-alarm sound, debug-cyclable via the asbsound console command
     * while we pin which vanilla sound the banner window plays (the round-1
     * exe pick environWarning is NOT confirmed by ear).
     */
    private fun alarmSound() = game.sounds.getSampleOrWarn(debugAlarmSound ?: ALARM_SOUND)

    /**
     * Arm the battery for the current beacon. [resetCycle] restarts the
     * 20-25 s damaging-shot delay - used when a new hostile battle
     * starts, so each fight begins with a fresh cycle.
     */
    fun arm(target: ASBTarget, resetCycle: Boolean) {
        val wasDisarmed = armedTarget == null
        armedTarget = target
        if (resetCycle || wasDisarmed)
            restartCycle()
    }

    fun disarm() {
        armedTarget = null
    }

    private fun restartCycle() {
        salvoTimer = (SALVO_DELAY_MIN..SALVO_DELAY_MAX).random(Random)
        ambientTimer = (AMBIENT_DELAY_MIN..AMBIENT_DELAY_MAX).random(Random)
        alarmPlayed = false
    }

    fun update(dt: Float) {
        val target = armedTarget ?: return

        salvoTimer -= dt
        ambientTimer -= dt

        // Debug banner force-show (asbbanner preview) - runs the same
        // drawing path as the real LOCKED window.
        if (debugBannerTimer > 0f)
            debugBannerTimer -= dt

        // The LOCKED banner window opens: sound the alarm once (vanilla
        // plays a warning sound with the warning_pds_locked banner).
        if (salvoTimer <= LOCKED_BANNER_TIME && !alarmPlayed) {
            alarmPlayed = true
            alarmSound()?.play()
        }

        if (salvoTimer <= 0f) {
            fireVolley(target, real = true)
            salvoTimer = (SALVO_DELAY_MIN..SALVO_DELAY_MAX).random(Random)

            // The exe's next-shot timer after a queued shot is 0.5 s.
            ambientTimer = AFTER_SALVO_DELAY
            alarmPlayed = false
            return
        }

        if (ambientTimer <= 0f) {
            fireVolley(target, real = false)
            ambientTimer = (AMBIENT_DELAY_MIN..AMBIENT_DELAY_MAX).random(Random)
        }
    }

    private fun fireVolley(target: ASBTarget, real: Boolean) {
        // The exe fires one shot per matching ship per volley, each at a
        // random room of that ship.
        if (target == ASBTarget.PLAYER || target == ASBTarget.ALL)
            fireAt(game.player, real)
        if ((target == ASBTarget.ENEMY || target == ASBTarget.ALL))
            fireAt(game.enemy, real)
    }

    private fun fireAt(ship: Ship?, real: Boolean) {
        if (ship == null)
            return

        val room = ship.rooms.random()
        val shot = ASBProjectile(room, real)

        // Come in from above the target ship, off-screen (exe spawn:
        // (rand 0..800, -400)).
        val centre = room.pixelCentre
        val start = ConstPoint(centre.x + (-150..150).random(Random), -400)
        shot.setInitialPath(start, centre)
        room.ship.projectiles.add(shot)

        (if (real) fireHeavySound else fireLightSound)?.play()

        // The exe spawns a 7-frame fire_smoke puff at the aim point when
        // a shot actually hits (34x34 frames, 1.0 s - see the plan's
        // FUN_005baa50 notes). Ambient shots don't hit, so no puff.
        if (real)
            room.ship.playCentredAnimation(FIRE_SMOKE_ANIM, centre)
    }

    /**
     * Draw the vanilla warning UI: the danger_pds HUD icon at (690, 72)
     * (exe ctor args 0x2b2, 0x48) with the DANGER!/ALLIES! label on its
     * stretched backglow beneath, plus the "ASB TARGET LOCKED!" banner
     * while a damaging shot is imminent.
     */
    @Suppress("UNUSED_PARAMETER")
    fun renderWarnings(g: Graphics) {
        val target = armedTarget ?: return

        val friendly = target == ASBTarget.ENEMY
        val icon = if (friendly) iconGreen else iconRed
        icon.draw(ICON_X - icon.width / 2f, ICON_Y.toFloat())

        drawLabel(friendly)

        if (salvoTimer in 0f..LOCKED_BANNER_TIME || debugBannerTimer > 0f)
            drawLockedBanner()
    }

    private fun drawLabel(friendly: Boolean) {
        val text = game.translator.get(
            if (friendly) "warning_pds_allies" else "warning_environment_danger"
        )
        val font = labelFont()
        val textWidth = font.getWidth(text)
        val textHeight = font.lineSpacing

        // The backglow is stretched to the text's size (exe: drawn at
        // textX - textW/2, textY - 1, textW, textH), centred ~51 px
        // below the icon's top.
        val glow = if (friendly) backglowGreen else backglowRed
        val glowX = ICON_X - textWidth / 2f
        val glowY = ICON_Y + 51f
        glow.draw(glowX - 4f, glowY - 3f, textWidth + 8f, textHeight + 7f)

        font.drawStringCentred(
            ICON_X.toFloat(), glowY + textHeight + 2f, 0f,
            text, if (friendly) ALLIES_COLOUR else DANGER_COLOUR
        )
    }

    private fun drawLockedBanner() {
        val text = game.translator.get("warning_pds_locked")
        val font = bannerFont()
        val lines = text.split("\n")

        font.scale = BANNER_SCALE
        val lineSpacing = font.lineSpacing + 6
        var y = BANNER_BASE_Y
        for (line in lines) {
            font.drawStringCentred(BANNER_X.toFloat(), y.toFloat(), 0f, line, LOCKED_COLOUR)
            y += lineSpacing
        }
        font.scale = 1f
    }

    companion object {
        // Debug: the asbfont / asbbanner console commands override the
        // label / banner fonts while we pin which ones vanilla uses.
        var debugLabelFont: String? = null
        var debugBannerFont: String? = null

        // Debug: the asbsound console command overrides the lock-alarm
        // sound (same hunt); debugBannerTimer force-shows the banner so
        // font/sound tries don't need to wait for a real volley.
        var debugAlarmSound: String? = null
        var debugBannerTimer = 0f

        // The exe constructs the warning UI at (0x2b2, 0x48).
        private const val ICON_X = 690
        private const val ICON_Y = 72

        private const val LOCKED_BANNER_TIME = 5f
        private const val SALVO_DELAY_MIN = 20f
        private const val SALVO_DELAY_MAX = 25f
        private const val AMBIENT_DELAY_MIN = 2f
        private const val AMBIENT_DELAY_MAX = 3.9f
        private const val AFTER_SALVO_DELAY = 0.5f

        private const val LABEL_FONT = "JustinFont11Bold"
        // By-ear/by-eye picks from the round-3 hunt (user, vs vanilla).
        private const val BANNER_FONT = "HL1"
        private const val ALARM_SOUND = "surgeWarning"
        private const val BANNER_SCALE = 2f
        private const val BANNER_X = 640
        private const val BANNER_BASE_Y = 130

        private val DANGER_COLOUR = Colour(255, 60, 50, 255)
        private val ALLIES_COLOUR = Colour(90, 255, 90, 255)
        private val LOCKED_COLOUR = Colour(255, 90, 60, 255)

        // The exe spawns this strip directly by path (img/effects/
        // fire_smoke.png, 7 frames of 34x34, 1.0 s) - it has no
        // animations.xml entry.
        private val FIRE_SMOKE_ANIM = AnimationSpec(
            Animations.SpriteSheetSpec("img/effects/fire_smoke.png", 34, 34, 238, 34),
            "asb_fire_smoke", 0, 0, 7, 1.0f
        )
    }
}
