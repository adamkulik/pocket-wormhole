package xyz.znix.xftl.sys;

import org.jetbrains.annotations.NotNull;
import org.newdawn.slick.SlickException;
import xyz.znix.xftl.rendering.Graphics;

// Modified for XFTL, came from Slick

/**
 * The main game interface that should be implemented by any game being developed
 * using the container system. There will be some utility type sub-classes as development
 * continues.
 *
 * @author kevin
 * @see org.newdawn.slick.BasicGame
 */
public interface Game {
    /**
     * Initialise the game. This can be used to load static resources. It's called
     * before the game loop starts
     *
     * @param container The container holding the game
     * @throws SlickException Throw to indicate an internal error
     */
    void init(@NotNull GameContainer container) throws SlickException;

    /**
     * Called when the game is being shut down, after the last render.
     * <p>
     * This is when native resources should be deallocated.
     */
    void shutdown();

    /**
     * Update the game logic here. No rendering should take place in this method
     * though it won't do any harm.
     *
     * @param container The container holing this game
     * @param delta     The amount of time that's passed since last update in seconds
     * @throws SlickException Throw to indicate an internal error
     */
    void update(@NotNull GameContainer container, float delta) throws SlickException;

    /**
     * Render the game's screen here.
     *
     * @param container The container holing this game
     * @param g         The graphics context that can be used to render. However, normal rendering
     *                  routines can also be used.
     * @throws SlickException Throw to indicate an internal error
     */
    void render(@NotNull GameContainer container, Graphics g) throws SlickException;

    /**
     * Get the title of this game
     *
     * @return The title of the game
     */
    String getTitle();
}
