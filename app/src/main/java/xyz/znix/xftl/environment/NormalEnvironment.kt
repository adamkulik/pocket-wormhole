package xyz.znix.xftl.environment

import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.sector.Beacon

/**
 * The environment used at a regular beacon.
 */
class NormalEnvironment(game: InGameState, beacon: Beacon) : AbstractEnvironment(game, beacon) {
    override val type: Beacon.EnvironmentType get() = Beacon.EnvironmentType.NORMAL
}
