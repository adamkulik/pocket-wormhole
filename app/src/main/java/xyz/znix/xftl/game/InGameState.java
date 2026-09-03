package xyz.znix.xftl.game;

import kotlin.random.Random;
import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.JDOMException;
import org.jdom2.input.SAXBuilder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.newdawn.slick.SlickException;
import org.newdawn.slick.util.InputAdapter;
import xyz.znix.xftl.*;
import xyz.znix.xftl.ai.ShipAI;
import xyz.znix.xftl.crew.*;
import xyz.znix.xftl.devutil.DebugConsole;
import xyz.znix.xftl.devutil.DebugFlagManager;
import xyz.znix.xftl.drones.AbstractExternalDrone;
import xyz.znix.xftl.hangar.EditableShip;
import xyz.znix.xftl.layout.Room;
import xyz.znix.xftl.math.IPoint;
import xyz.znix.xftl.math.Point;
import xyz.znix.xftl.math.RoomPoint;
import xyz.znix.xftl.rendering.*;
import xyz.znix.xftl.savegame.ObjectRefs;
import xyz.znix.xftl.savegame.RefLoader;
import xyz.znix.xftl.savegame.SaveUtil;
import xyz.znix.xftl.sector.*;
import xyz.znix.xftl.shipgen.EnemyShipSpec;
import xyz.znix.xftl.shipgen.ShipGenerator;
import xyz.znix.xftl.sys.GameContainer;
import xyz.znix.xftl.sys.Input;
import xyz.znix.xftl.sys.ResourceContext;
import xyz.znix.xftl.systems.*;
import xyz.znix.xftl.ui.SpecDeserialiser;
import xyz.znix.xftl.ui.UIProvider;
import xyz.znix.xftl.ui.Widget;
import xyz.znix.xftl.weapons.Damage;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

public class InGameState extends MainGame.GameState {
    @Nullable // Null only when running tests
    private final MainGame mainGame;

    private final GameContent content;

    private Difficulty difficulty = Difficulty.NORMAL;

    // These are copied from the GameContent object for convenience
    private final Datafile df;
    private final boolean enableAdvancedEdition;
    private final BlueprintManager blueprintManager;
    private final EventManager eventManager;

    private final SpecDeserialiser uiLoader = new SpecDeserialiser(new GameUIProvider());

    private GameMap gameMap;
    private Ship player;
    private Ship enemy;
    private ShipAI enemyAI;

    private LootPool lootPool;

    private SILFontLoader pauseFont;

    private Image missingImage;

    private final Point tempPoint = new Point(0, 0);

    private Room hoveredRoom;
    private RoomClickListener clickEvent;
    private final boolean[] mouseDownPrev = new boolean[3];

    private ITooltipProvider lastFameTooltip;

    private PlayerShipUI shipUI;
    private HostileShipUI hostileShipUI;

    private boolean enemyIsHostile;
    private boolean enemyInteriorVisible;

    private Beacon currentBeacon;
    private boolean paused;

    // The list of all the sectors the ship has visited, including
    // the current one.
    private final ArrayList<GameMap.SectorInfo> visitedSectors = new ArrayList<>();

    private DebugConsole debugConsole;
    private boolean debugConsoleVisible;

    private DebugFlagManager debugFlags = new DebugFlagManager();

    private boolean isCurrentlyLoadingSave;

    // Used for animation rendering only
    private float renderingDeltaTime;

    private final Point playerShipOffset = new Point(0, 0);

    /**
     * The quest events that couldn't be fit in the current sector,
     * and were delayed to the next one.
     */
    private final ArrayList<Event> delayedQuests = new ArrayList<>();

    /**
     * This is the list of all the player-owned crew, as used by the main UI
     * and the ship upgrade window.
     */
    private final ArrayList<LivingCrew> playerCrew = new ArrayList<>();

    /**
     * The buttons each hotkey is bound to.
     * <p>
     * This is calculated from the profile and hotkey manager, so it shouldn't
     * be modified externally.
     */
    private final HashMap<Hotkey, HotkeyButton> reverseHotkeyBindings = new HashMap<>();
    private final HashMap<HotkeyButton, Hotkey> forwardHotkeyBindings = new HashMap<>();

    /**
     * The hotkeys that have been pressed and queued up to be used by the game.
     * <p>
     * This is done in case a key is somehow dispatched while update() is running.
     */
    private final List<Hotkey> queuedHotkeys = Collections.synchronizedList(new ArrayList<>());

    public InGameState(MainGame mainGame, GameContent content, String playerShipName, Difficulty difficulty, EditableShip customised) {
        this(mainGame, content);
        this.difficulty = difficulty;

        gameMap = new GameMap(df, eventManager, enableAdvancedEdition, Random.Default);

        // Start at the first beacon of the first sector
        // Be sure we do this after creating the player ship, it's used by the enemy AI
        // Note that we don't store the current sector anywhere - it's determined via
        // the current beacon.
        Sector firstSector = gameMap.generateSector(gameMap.getSectors().get(0).get(0), this);
        setCurrentBeacon(firstSector.getStartBeacon());

        // Do this after setting the initial beacon, since the ship reads the current
        // beacon when calculating its power values.
        createNewPlayerShip(playerShipName, customised);

        // Don't create PlayerShipUI in automated tests
        if (mainGame != null) {
            shipUI = new PlayerShipUI(player, this);
        }

        // Show the starting beacon dialogue, since it wasn't displayed earlier
        // due to shipUI not existing yet.
        showEventDialogue(currentBeacon.getEvent(), Random.Default.nextInt());
    }

    /**
     * Load a previously-saved game.
     */
    public InGameState(MainGame mainGame, GameContent content, Document saveGame) {
        this(mainGame, content);

        Element root = saveGame.getRootElement();
        if (!root.getName().equals("xftlSaveGame")) {
            throw new IllegalArgumentException("Bad save-game root element name: " + root.getName());
        }

        // If AE was enabled on the save, make sure our content also has it.
        boolean saveEnabledAE = Boolean.parseBoolean(saveGame.getRootElement().getAttributeValue("enableAE"));
        if (saveEnabledAE != content.enableAdvancedEdition) {
            throw new IllegalArgumentException("Cannot load a game with content that doesn't match it's AE state!");
        }

        difficulty = Difficulty.valueOf(root.getAttributeValue("difficulty"));

        isCurrentlyLoadingSave = true;
        loadGameState(root);
        isCurrentlyLoadingSave = false;

        // This just loads stuff from XML, we don't need to serialise it
        lootPool = new LootPool(blueprintManager, currentBeacon.getSector().getType());

        // Give all the ships an update, for stuff like the
        // doors automatically opening. This must only be done once all
        // the ships are loaded, otherwise it'd break hacking since the
        // hacked system would think it wasn't being hacked any more.
        for (Beacon beacon : currentBeacon.getSector().getBeacons()) {
            if (beacon.getShip() != null)
                beacon.getShip().update(0f);
        }
        player.update(0f);

        // Start the music up again.
        // We need a special exception here for the continuous-save-load mode,
        // as otherwise it'll be restarting the music every frame. As the music
        // isn't saved, this would use a lot of CPU for no benefit.
        // To do this, just check if it's already playing something. Since
        // the sound manager is part of the game content, it's not cleared
        // across a reload.
        if (getSounds() instanceof RealSoundManager real && real.getCurrentMusic() == null) {
            loadSectorMusic(currentBeacon.getSector().getType());
        }

        updatePlayerCrew();
    }

    private InGameState(MainGame mainGame, GameContent content) {
        this.mainGame = mainGame;
        this.content = content;

        // Load up the convenience variables
        df = content.datafile;
        enableAdvancedEdition = content.enableAdvancedEdition;
        blueprintManager = content.blueprintManager;
        eventManager = content.eventManager;

        // Don't load any assets while running automated tests
        if (isRunningAutomatedTest())
            return;

        pauseFont = getFont("c&c");

        // Load a bunch of images that we'll need, to make them available for later.
        List<SystemBlueprint> systems = blueprintManager.getBlueprints().values().stream()
                .filter(bp -> bp instanceof SystemBlueprint)
                .map(bp -> (SystemBlueprint) bp)
                .collect(Collectors.toList());
        for (SystemBlueprint system : systems) {
            getImg(system.getRoomIconPath());
        }

        for (SystemBlueprint system : systems) {
            getImg("img/icons/s_" + system.getType() + "_red1.png");
            getImg("img/icons/s_" + system.getType() + "_orange1.png");
            getImg("img/icons/s_" + system.getType() + "_grey1.png");
            getImg("img/icons/s_" + system.getType() + "_green1.png");
        }

        updateHotkeyBindings();

        // Issue #4: the beacon-arrival flare (glowing star that sweeps along
        // the hull as the ship scales up).
        jumpFlare = getImg("img/effects/glowing_star.png");
    }

    @Override
    public void init(@NotNull GameContainer container) {
        super.init(container);

        // Feed inputs to the debug console.
        container.getInput().addListener(new InputAdapter() {
            @Override
            public void keyPressed(int key, char c) {
                // The debug console blocks all hotkeys
                if (debugConsoleVisible) {
                    getDebugConsole().keyPressed(key, c);
                    return;
                }

                // If the window uses the key for typing, then prevent it
                // from being used as a hotkey too.
                if (shipUI.onTextInput(key, c)) {
                    container.getInput().clearInputPressedRecord();
                    return;
                }

                // Check if there's a hotkey associated with this button
                HotkeyButton button = HotkeyButton.BY_KEY_ID.get(key);
                if (button == null) {
                    return;
                }
                Hotkey matching = forwardHotkeyBindings.get(button);
                if (matching == null) {
                    return;
                }

                // And if so, queue it for the next update
                queuedHotkeys.add(matching);
            }

            @Override
            public void mouseWheelMoved(int change) {
                if (debugConsoleVisible) {
                    getDebugConsole().mouseWheelMoved(change);
                    return;
                }

                shipUI.mouseScroll(change);
            }

            @Override
            public void mousePressed(int button, int x, int y) {
                if (debugConsoleVisible)
                    debugConsole.mousePressed(button, x, y);
            }

            @Override
            public void mouseReleased(int button, int x, int y) {
                if (debugConsoleVisible)
                    debugConsole.mouseReleased(button, x, y);
            }

            @Override
            public void mouseDragged(int oldX, int oldY, int newX, int newY) {
                if (debugConsoleVisible)
                    debugConsole.mouseDragged(oldX, oldY, newX, newY);
            }
        });
    }

    @Override
    public void shutdown() {
        // Our resource context is owned by the game data, so we don't need to free it here.
    }

    private void createNewPlayerShip(String shipName, EditableShip customised) {
        ShipBlueprint blueprint = blueprintManager.getShip(shipName);
        player = new Ship(blueprint, this, customised, null, null);
        player.loadDefaultContents();

        for (ShipBlueprint.InitialCrewSpec spec : blueprint.getInitialCrew()) {
            CrewBlueprint race = (CrewBlueprint) blueprintManager.get(spec.getRace());
            for (int i = 0; i < spec.getAmount(); i++) {
                LivingCrewInfo info = LivingCrewInfo.generateRandom(race, this);
                player.addCrewMember(info, true, false);
            }
        }

        // Save the default crew positions, so the player can immediately
        // load them again without first having to save them.
        player.saveCrewPositions();

        // Set the default power levels. Don't turn on weapons or drones, both
        // to avoid running out of power for other stuff, and to avoid wasting
        // drone parts.
        player.updateAvailablePower();
        for (MainSystem system : player.getMainSystems()) {
            if (system instanceof Weapons || system instanceof Drones)
                continue;

            // As a hack, turn artillery off so what's below doesn't charge it.
            if (system instanceof Artillery)
                continue;

            for (int i = 0; i < system.getBlueprint().getMaxPower(); i++) {
                system.increasePower();
            }
        }

        // Properly calculate the evasion chance, so it shows properly while
        // paused in the start-of-game event, and to charge up the shield in
        // that same time.
        // Call it repeatedly to charge up the level-2 starting shields
        // on Mantis B. This is a bit hacky, but it's fine for now.
        for (int i = 0; i < 5; i++) {
            player.update(10f);
        }
    }

    @Override
    public void update(@NotNull GameContainer container, float delta) throws SlickException {
        renderingDeltaTime = delta;

        // The game stays frozen while an arrival or jump-out animation
        // plays (issue #4).
        if (!isPaused() && playerFlyIn <= 0 && playerJumpOut <= 0 && enemyJumpOut <= 0)
            updateGameState(delta);

        // Tick the jump-out animations (issue #4). When the player's
        // jump-out finishes, the beacon actually changes and the arrival
        // animation plays at the new beacon. When the enemy's finishes, the
        // ship is finally removed.
        if (playerJumpOut > 0) {
            playerJumpOut = Math.max(0f, playerJumpOut - delta / JUMP_OUT_TIME);
            if (playerJumpOut == 0 && jumpOutTarget != null) {
                Beacon target = jumpOutTarget;
                jumpOutTarget = null;
                setCurrentBeacon(target);

                // Auto-save on arrival (after the beacon change has fully
                // resolved), so a force-close can only cost the player the
                // current beacon, like vanilla FTL.
                mainGame.writeRunSave();
            }
        }

        if (enemyJumpOut > 0) {
            enemyJumpOut = Math.max(0f, enemyJumpOut - delta / JUMP_OUT_TIME);
            if (enemyJumpOut == 0) {
                IEvent jumpEvent = enemyJumpEvent;
                enemyJumpEvent = null;
                setEnemy(null);
                currentBeacon.setShip(null);

                if (jumpEvent != null) {
                    showEventDialogue(jumpEvent.resolve(), Random.Default.nextInt());
                }
            }
        }

        // Tick the beacon-arrival animation; the deferred arrival dialogues
        // only show once it's done (issue #4).
        if (playerFlyIn > 0) {
            playerFlyIn = Math.max(0f, playerFlyIn - delta / ARRIVAL_TIME);
        }

        if (arrivalEventsPending && playerFlyIn <= 0 && !isPaused()) {
            arrivalEventsPending = false;
            if (arrivalShowBeaconEvent) {
                arrivalShowBeaconEvent = false;
                showEventDialogue(currentBeacon.getEvent(), currentBeacon.getEnvironmentSeed());
            }
            if (arrivalEliteEvent != null) {
                Event eliteEvent = arrivalEliteEvent;
                arrivalEliteEvent = null;
                showEventDialogue(eliteEvent, Random.Default.nextInt());
            }
        }

        Input in = container.getInput();

        // For debugging, this lets you either step or fast-forward through time.
        // Don't do this in response to typing on the debug console, though.
        if (!debugConsoleVisible) {
            HotkeyButton fastForwardButton = reverseHotkeyBindings.get(getHotkeyManager().getKeyFastForward());
            HotkeyButton stepButton = reverseHotkeyBindings.get(getHotkeyManager().getKeyFrameStep());

            if (fastForwardButton != null && in.isKeyDown(fastForwardButton.getKeyID())) {
                updateGameState(delta * 4);
            }
            if (fastForwardButton != null && in.isKeyPressed(stepButton.getKeyID())) {
                updateGameState(0.01f);
            }
        }

        shipUI.updateAlways(delta);

        hoveredRoom = null;

        // Don't touch sounds in automated tests
        if (content.sounds instanceof RealSoundManager sounds) {
            sounds.updateLoopedSounds(isPaused());
            sounds.setCombatMusic(isInDanger());
            sounds.updateMusic(delta);
            sounds.setSoundEffectVolume(mainGame.getProfile().getSoundVolume());
            sounds.setMusicVolume(mainGame.getProfile().getMusicVolume());
        }

        HotkeyButton consoleButton = reverseHotkeyBindings.get(getHotkeyManager().getKeyDevConsole());
        if (consoleButton != null && in.isKeyPressed(consoleButton.getKeyID())) {
            debugConsoleVisible = !debugConsoleVisible;

            // Clear out any pending key presses, so keys pressed while
            // the console is open don't have an effect once it's closed.
            in.clearInputPressedRecord();
        }

        if (debugConsoleVisible) {
            getDebugConsole().update(container, delta);
        }

        while (!queuedHotkeys.isEmpty()) {
            dispatchHotkey(queuedHotkeys.remove(0), in);
        }

        // Escape is special, it's not a hotkey and can't be rebound.
        if (in.isKeyPressed(Input.KEY_ESCAPE)) {
            shipUI.escapePressed();
        }

        boolean rightClicked = false;

        for (int i = 0; i < 3; i++) {
            boolean prev = mouseDownPrev[i];
            boolean now = in.isMouseButtonDown(i);

            // Block mouse input while the debug console is open, to avoid
            // accidentally clicking on something under the console.
            if (debugConsoleVisible) {
                now = false;
            }

            if (now && !prev) {
                if (i == Input.MOUSE_RIGHT_BUTTON) {
                    clickEvent = null;
                    rightClicked = true;
                }

                shipUI.mouseClick(i, in.getMouseX(), in.getMouseY(), playerShipOffset);
            } else if (prev && !now) {
                shipUI.mouseUp(i, in.getMouseX(), in.getMouseY(), playerShipOffset);
            }
            mouseDownPrev[i] = now;
        }

        shipUI.updateUI(in.getMouseX(), in.getMouseY(), playerShipOffset);

        // Figure out when the player right-clicks
        // an enemy room - this is used for controlling boarders.
        if (rightClicked && enemy != null) {
            tempPoint.setX(container.getInput().getMouseX());
            tempPoint.setY(container.getInput().getMouseY());
            tempPoint.minusAssign(hostileShipUI.getShipPos());
            enemy.screenPosToShipPos(tempPoint);

            RoomPoint rp = enemy.shipToRoomPos(tempPoint);
            if (rp != null) {
                shipUI.enemyRoomRightClicked(rp.getRoom(), enemy);
            }
        }

        updateClickEvent(container);

        // Hammer away at the serialisation logic, if that flag is set.
        // Only do it while un-paused, since we can't properly use
        // the console if we're constantly reloading.
        if (debugFlags.getContinuousSaveLoad().getSet() && !isPaused()) {
            mainGame.doSaveLoadGame();
        }
    }

    @Nullable
    @Override
    public Cursor getCurrentCursor() {
        return shipUI.getCurrentCursor();
    }

    private void updateClickEvent(GameContainer container) {
        // Handle stuff like weapon targeting where the player
        // highlights a room on the enemy (or their) ship.
        if (clickEvent == null)
            return;

        // Hovering over the enemy
        if (enemy != null && enemyInteriorVisible) {
            tempPoint.setX(container.getInput().getMouseX());
            tempPoint.setY(container.getInput().getMouseY());
            tempPoint.minusAssign(hostileShipUI.getShipPos());
            enemy.screenPosToShipPos(tempPoint);

            RoomPoint rp = enemy.shipToRoomPos(tempPoint);
            if (rp != null)
                hoveredRoom = rp.getRoom();
        }

        // Hovering over the player
        tempPoint.setX(container.getInput().getMouseX());
        tempPoint.setY(container.getInput().getMouseY());
        tempPoint.minusAssign(playerShipOffset);
        player.screenPosToShipPos(tempPoint);

        RoomPoint rp = player.shipToRoomPos(tempPoint);
        if (rp != null)
            hoveredRoom = rp.getRoom();

        if (hoveredRoom != null && !clickEvent.canTargetRoom(hoveredRoom)) {
            hoveredRoom = null;
        }

        if (hoveredRoom != null && clickEvent != null && mouseDownPrev[Input.MOUSE_LEFT_BUTTON]) {
            var prev = clickEvent;
            clickEvent = null;
            prev.roomClicked(hoveredRoom, container);
        }
    }

    private void dispatchHotkey(Hotkey key, Input in) {
        if (key.getId().equals(VanillaHotkeys.PAUSE))
            paused = !paused;

        boolean shiftPressed = in.isKeyDown(Input.KEY_LSHIFT);
        for (int i = 0; i < VanillaHotkeys.WEAPON_SLOTS.size(); i++) {
            if (key.getId().equals(VanillaHotkeys.WEAPON_SLOTS.get(i))) {
                shipUI.weaponHotkeyPressed(i, shiftPressed);
            }
        }
        // TODO drone hotkeys
        // TODO autofire

        shipUI.hotkeyPressed(key, in);
    }

    @Override
    public void render(@NotNull GameContainer container, @NotNull Graphics g) throws SlickException {
        g.clear(Colour.black);

        currentBeacon.getEnvironment(this).renderBackground(container, g);

        // Set the position of the player's ship, based on whether or not
        // there's also an enemy ship.
        if (enemy == null) {
            playerShipOffset.setX(350);
        } else if (enemy.isUsingBossUI()) {
            playerShipOffset.setX(150);
        } else {
            playerShipOffset.setX(200);
        }
        playerShipOffset.setY(170);

        // Get the player's ship away from the top UI
        g.pushTransform();
        g.translate(playerShipOffset.getX(), playerShipOffset.getY());

        // Issue #4: beacon arrival, like vanilla. Phase 1 - the ship is
        // small and mostly transparent while a glowing star sweeps the hull
        // from bow to stern and the ship fades in. Phase 2 - the star is
        // done, and the ship smoothly scales up to full size.
        float arrivalSweep = 0f;
        if (playerFlyIn > 0) {
            float p = 1f - playerFlyIn;                       // 0 -> 1
            float sweepFrac = Math.min(1f, p / ARRIVAL_SWEEP_END);
            arrivalSweep = sweepFrac * sweepFrac * (3f - 2f * sweepFrac);

            float scaleFrac = Math.max(0f, (p - ARRIVAL_SWEEP_END) / (1f - ARRIVAL_SWEEP_END));
            float scale = 0.12f + 0.88f * (scaleFrac * scaleFrac * (3f - 2f * scaleFrac));
            player.setRenderAlpha(0.15f + 0.85f * arrivalSweep);

            float centreX = player.getHullCentreX();
            float centreY = player.getHullCentreY();

            g.translate(centreX, centreY);
            g.scale(scale, scale);
            g.translate(-centreX, -centreY);
        }

        // Leaving ships play the same animation reversed (issue #4): they
        // scale down first, then the star sweeps back (stern -> bow) while
        // the ship fades out.
        float jumpOutSweep = 0f;
        if (playerJumpOut > 0) {
            float p = 1f - playerJumpOut;                     // 0 -> 1
            float centreX = player.getHullCentreX();
            float centreY = player.getHullCentreY();

            if (p < JUMP_OUT_SCALE_END) {
                float t = p / JUMP_OUT_SCALE_END;
                float smooth = t * t * (3f - 2f * t);
                float scale = 1f - 0.88f * smooth;

                g.translate(centreX, centreY);
                g.scale(scale, scale);
                g.translate(-centreX, -centreY);
            } else {
                float fade = (p - JUMP_OUT_SCALE_END) / (1f - JUMP_OUT_SCALE_END);
                float smooth = fade * fade * (3f - 2f * fade);
                jumpOutSweep = smooth;
                player.setRenderAlpha(1f - 0.85f * smooth);

                g.translate(centreX, centreY);
                g.scale(0.12f, 0.12f);
                g.translate(-centreX, -centreY);
            }
        }

        player.render(g, true, hoveredRoom);

        // The glowing star rides on top of the hull, unscaled.
        //
        // Arrival: small at the bow, largest amidships, gone before the
        // scale-up starts.
        if (playerFlyIn > 0 && arrivalSweep < 1f) {
            float starSize = player.getHullImage().getHeight()
                    * (0.2f + 1.6f * (float) Math.sin(Math.PI * arrivalSweep));
            float flareX = player.getHullRightX()
                    + (player.getHullLeftX() - player.getHullRightX()) * arrivalSweep;
            float centreY = player.getHullCentreY();
            float alpha = 0.55f + 0.45f * (float) Math.sin(Math.PI * arrivalSweep);

            jumpFlare.draw(flareX - starSize / 2f, centreY - starSize / 2f,
                    starSize, starSize, new Colour(1f, 1f, 1f, alpha));
        }

        // Jump-out: sweeps back the other way (stern -> bow) during the
        // fade-out phase.
        if (playerJumpOut > 0 && jumpOutSweep > 0f) {
            float starSize = player.getHullImage().getHeight()
                    * (0.2f + 1.6f * (float) Math.sin(Math.PI * jumpOutSweep));
            float flareX = player.getHullLeftX()
                    + (player.getHullRightX() - player.getHullLeftX()) * jumpOutSweep;
            float centreY = player.getHullCentreY();
            float alpha = 0.55f + 0.45f * (float) Math.sin(Math.PI * jumpOutSweep);

            jumpFlare.draw(flareX - starSize / 2f, centreY - starSize / 2f,
                    starSize, starSize, new Colour(1f, 1f, 1f, alpha));
        }

        player.setRenderAlpha(1f);

        if (player.getWeapons() != null) {
            // Some modded ships don't have a weapons system
            player.renderTargeting(g, player.getWeapons().getSelectedTargets());
        }
        g.popTransform();

        shipUI.render(container, g);

        if (enemy != null) {
            // If the enemy ship is neutral but we still have crew inside,
            // allow the user to control and recover them.
            boolean anyLivingBoarders = enemy.hasCrewOwnedByShip(player);
            enemyInteriorVisible = enemyIsHostile || anyLivingBoarders;

            hostileShipUI.render(container, g, hoveredRoom, enemyInteriorVisible, enemyIsHostile);
        }

        // Draw the paused text before the UI, so the UI goes on top.
        if (paused) {
            Image pauseImg = getImg("img/Text_pause2.png");
            int imgY = container.getHeight() - 193;
            int imgX = container.getWidth() / 2 - pauseImg.getWidth() / 2;
            pauseImg.draw(imgX, imgY);

            String pauseKey = getButtonNameForHotkey(VanillaHotkeys.PAUSE, "<unbound>");
            String pauseText = getTranslator().get("paused_text").replace("\\1", pauseKey);
            pauseFont.drawStringCentred(
                    container.getWidth() / 2f, imgY + 11 + 53,
                    0,
                    pauseText, Constants.PAUSE_TEXT_COLOUR
            );
        }

        // Draw solar flares and pulsar pulses
        currentBeacon.getEnvironment(this).renderOverlay(container, g);

        shipUI.renderMenus(container, g);

        if (debugConsoleVisible) {
            g.setTooltip(null);
            getDebugConsole().render(container, g);
        }

        // Render the tooltip
        ITooltipProvider tooltip = g.getTooltip();
        if (tooltip != null) {
            Input in = container.getInput();
            tooltip.drawTooltip(g,
                    in.getMouseX(), in.getMouseY(),
                    tooltip != lastFameTooltip,
                    container.getWidth(), container.getHeight()
            );
        }
        lastFameTooltip = tooltip;
    }

    /**
     * Update the in-game state.
     * <p>
     * This is only public so that automated tests can call it - it should
     * NOT be called elsewhere from the main code.
     */
    public void updateGameState(float dt) {
        currentBeacon.getEnvironment(this).update(dt);

        if (shipUI != null)
            shipUI.update();

        player.update(dt);

        updatePlayerCrew();

        if (enemy != null) {
            enemy.update(dt);

            // Since we try and limit direct communications between
            // the ships, pass information about the cloak status back
            // and forth here.
            player.setOpponentCloakActive(enemy.isCloakActive());
            enemy.setOpponentCloakActive(player.isCloakActive());

            if (enemyIsHostile) {
                enemyAI.update(dt);
            }

            if (enemy.isGone()) {
                // Be sure to check if the enemy is still hostile, if we've
                // already killed their crew we can't get a second lot of
                // rewards if they later blow up.
                if (enemy.getSpec() != null && enemyIsHostile) {
                    IEvent event = enemy.getSpec().getDestroyed();
                    if (event != null)
                        showEventDialogue(event.resolve(), Random.Default.nextInt());
                }

                if (enemy.getBoss() != null) {
                    enemy.getBoss().bossShipKilled(enemy);
                }

                setEnemy(null);
                currentBeacon.setShip(null);

                // At this point enemy may be null if they
                // were destroyed, so we can't use that any more.
                // Just returning here keeps things simple.
                return;
            }

            Float escapeTimer = enemy.getEscapeTimer();
            if (escapeTimer != null && escapeTimer <= 0f) {
                // TODO play the jumping away animation

                // Note we have to fetch this now, before we null
                // out the enemy ship.
                IEvent jumpEvent = enemy.getSpec() == null ? null : enemy.getSpec().getGotaway();

                setEnemy(null);
                currentBeacon.setShip(null);

                if (jumpEvent != null) {
                    showEventDialogue(jumpEvent.resolve(), Random.Default.nextInt());
                }

                return;
            }

            // Check if the enemy crew is dead (including any aboard the player ship).
            // Note we check the crew owners, so that mind-controlling the last
            // crew doesn't break it.
            boolean anyCrewLeft = enemy.hasCrewOwnedByShip(enemy);
            boolean anyBoardersLeft = player.hasCrewOwnedByAnyOtherShip();
            if (!anyCrewLeft && !anyBoardersLeft && !enemy.isAutoScout() && enemyIsHostile) {
                if (enemy.getSpec() != null) {
                    IEvent event = enemy.getSpec().getDeadCrew();
                    if (event != null)
                        showEventDialogue(event.resolve(), Random.Default.nextInt());
                } else if (enemy.getBoss() != null) {
                    // TODO turn the enemy into an autoscout
                    // Event event = eventManager.get("BOSS_AUTOMATED").resolve();
                    // showEventDialogue(event);
                }

                setEnemyIsHostile(false);

                // If we jump back, they shouldn't re-appear.
                // This also means there won't be a danger mark
                // on the map.
                currentBeacon.setShip(null);
            }
        } else {
            player.setOpponentCloakActive(false);
        }

        if (!isInDanger()) {
            // If the player isn't fighting a ship, there are no borders (not yet implemented), and they're not
            // in a dangerous environment (eg, an asteroid field) then let them jump instantly.
            player.setFtlChargeProgress(1);
        }
    }

    // Issue #4: when arriving at a beacon the game pauses while the ship
    // materialises: a glowing star sweeps from bow to stern as the ship
    // fades in, then it smoothly scales up to full size. playerFlyIn goes
    // from 1 (just arrived) to 0 (done).
    private float playerFlyIn = 0f;
    private boolean arrivalEventsPending = false;
    private boolean arrivalShowBeaconEvent = false;
    private Event arrivalEliteEvent;
    public Image jumpFlare;   // issue #4: shared by the arrival and jump-away animations
    private static final float ARRIVAL_TIME = 1.4f;

    // The reverse animation when the player jumps away (issue #4): the ship
    // scales down while the star sweeps back (stern -> bow) and it fades
    // out. When it finishes, the beacon actually changes.
    private float playerJumpOut = 0f;
    private Beacon jumpOutTarget;
    private static final float JUMP_OUT_TIME = 1.2f;
    private static final float JUMP_OUT_SCALE_END = 0.35f;

    // Same animation for an enemy ship jumping away mid-combat.
    private float enemyJumpOut = 0f;
    private IEvent enemyJumpEvent;

    // Fraction of the animation spent on the star sweep + fade-in; the
    // remainder is the ship's scale-up.
    private static final float ARRIVAL_SWEEP_END = 0.65f;

    public void setCurrentBeacon(Beacon currentBeacon) {
        boolean beaconChanged = this.currentBeacon == null || this.currentBeacon != currentBeacon;

        if (this.currentBeacon == null || this.currentBeacon.getSector() != currentBeacon.getSector()) {
            SectorType sectorType = currentBeacon.getSector().getType();
            lootPool = new LootPool(blueprintManager, sectorType);

            // Add any quests we didn't have time for last time
            for (Event quest : delayedQuests) {
                currentBeacon.getSector().addQuest(currentBeacon, quest, true);
            }
            delayedQuests.clear();

            // Switch to the new sector's music
            loadSectorMusic(sectorType);
        }

        this.currentBeacon = currentBeacon;

        // Keep track of which sectors we visit, to show on the sector map UI.
        GameMap.SectorInfo sectorInfo = currentBeacon.getSector().getInfo();
        if (!visitedSectors.contains(sectorInfo)) {
            visitedSectors.add(sectorInfo);
        }

        setEnemyIsHostile(true);
        setEnemy(currentBeacon.getShip());

        // This is called for the first beacon before the player is created.
        if (player != null) {
            // Make the store button appear and disappear.
            shipUI.updateButtons();

            player.resetAfterJump();
            if (currentBeacon.getState() == Beacon.State.UNVISITED) {
                // Deferred until the arrival animation finishes (issue #4).
                arrivalShowBeaconEvent = true;
            }
        }

        // Spawn in a new rebel elite every jump for overtaken beacons
        if (currentBeacon.getState() == Beacon.State.OVERTAKEN) {
            // See doc/sector-map for information on these events

            String eventName;

            if (currentBeacon.isExit()) {
                eventName = "FLEET_EASY_BEACON";
                if (difficulty != Difficulty.EASY) {
                    // This is correct - use _DLC for non-easy difficulties!
                    eventName += "_DLC";
                }
            } else {
                eventName = "FLEET_EASY";

                if (currentBeacon.getEnvironmentType() == Beacon.EnvironmentType.NEBULA) {
                    eventName += "_NEBULA";
                } else if (enableAdvancedEdition) {
                    eventName += "_DLC";
                }
            }

            Event event = eventManager.get(eventName).resolve();
            arrivalEliteEvent = event;
        }

        // If the flagship is here, spawn it in. Doing this last overwrites
        // the rebel elite that's already been spawned.
        trySpawnBoss();

        currentBeacon.setVisited(true);

        // Fly the ship in, like vanilla does when arriving at a beacon
        // (this includes the first beacon of a run and loading a game).
        // Fly the ship in, like vanilla does when arriving at a beacon
        // (this includes the first beacon of a run and loading a game).
        if (beaconChanged) {
            playerFlyIn = 1f;
            arrivalEventsPending = arrivalShowBeaconEvent || arrivalEliteEvent != null;
        }
    }

    /**
     * Play the jump-out animation (issue #4), then switch to the target
     * beacon - where the arrival animation plays. Used when the player
     * jumps to a beacon from the star map.
     */
    public void beginJumpOut(Beacon target) {
        // If an animation is still in progress, switch immediately.
        if (playerFlyIn > 0 || playerJumpOut > 0 || enemyJumpOut > 0) {
            setCurrentBeacon(target);
            mainGame.writeRunSave();
            return;
        }

        jumpOutTarget = target;
        playerJumpOut = 1f;
    }

    private void trySpawnBoss() {
        BossManager boss = currentBeacon.getSector().getBosses().stream()
                .filter(thisBoss -> thisBoss.getBeacon() == currentBeacon)
                .findFirst().orElse(null);

        // No boss at this beacon?
        if (boss == null) {
            return;
        }

        // If there's a ship at the current beacon, get rid of it. This avoids
        // it sticking around after the flagship leaves, and saves space
        // in the serialised game.
        // Note the flagship isn't marked as belonging to a beacon, since it's
        // generated whenever the player jumps there.
        currentBeacon.setShip(null);

        setEnemy(boss.createShip());
        setEnemyIsHostile(true);
    }

    /**
     * Switch to using a new sector's music.
     * <p>
     * This switches to the start of the first track in this sector, so it
     * shouldn't be called unless there's a new sector.
     */
    private void loadSectorMusic(SectorType type) {
        List<MusicSpec> tracks = type.getSoundtracks().stream()
                .map(name -> getSounds().getTrack(name))
                .collect(java.util.stream.Collectors.toList());
        getSounds().switchMusicList(tracks);
    }

    public void loadEventShip(Event event) {
        if (event.getLoadShipName() != null) {
            EnemyShipSpec spec = eventManager.getShip(event.getLoadShipName());
            int sector = currentBeacon.getSector().getSectorNumber();
            setEnemy(content.generator.buildShip(this, spec, sector, difficulty, null));

            // Ships aren't hostile by default
            setEnemyIsHostile(false);
        }
        Boolean hostileState = event.getLoadShipHostile();
        if (hostileState != null) {
            this.currentBeacon.setShip(hostileState ? enemy : null);
            setEnemyIsHostile(hostileState);
        }
    }

    // For use by the debug console
    public void debugSpawnShip(EnemyShipSpec spec, Difficulty difficulty, int sector, int seed) {
        setEnemy(content.generator.buildShip(this, spec, sector, difficulty, seed));
        currentBeacon.setShip(enemy);
        setEnemyIsHostile(true);
    }

    private void setEnemy(Ship enemy) {
        this.enemy = enemy;

        // player=null when we're starting a new game, in which case enemy=null too.
        if (player != null) {
            player.enemyShipUpdated();
        }

        if (enemy != null) {
            enemyAI = new ShipAI(enemy, Objects.requireNonNull(player));
            hostileShipUI = new HostileShipUI(this, enemy);
            enemy.enemyShipUpdated();
        } else {
            enemyAI = null;
            hostileShipUI = null;
        }

        // Update the drones system, as drones may need to disappear
        // when the enemy ship is changed.
        // Note the player won't be set by now, in the case of the first beacon.
        if (player != null && player.getDrones() != null) {
            player.getDrones().enemyShipUpdated();
        }

        // Turn off any drones flying around the player's ship if they belong
        // to a now-gone ship.
        if (player != null) {
            for (AbstractExternalDrone drone : player.getExternalDrones()) {
                if (isShipPresent(drone.getOwnerShip()))
                    continue;

                drone.setPowered(false);
            }
        }
    }

    // This is, somewhat ironically, only used by the debug console itself.
    public void reloadDebugConsole() {
        debugConsole = null;
        debugConsoleVisible = false;
    }

    public void reloadDebugFlags() {
        debugFlags = new DebugFlagManager();
    }

    public boolean isInDanger() {
        if (enemy != null && enemyIsHostile)
            return true;

        if (!player.getIntruders().isEmpty())
            return true;

        return currentBeacon.getEnvironmentType().isDangerous();
    }

    /**
     * Add a quest event marker, as required by an event.
     */
    @NotNull
    public QuestAddResult addQuest(Event questEvent) {
        Sector sector = currentBeacon.getSector();
        boolean wasAdded = sector.addQuest(currentBeacon, questEvent, false);

        if (wasAdded) {
            return QuestAddResult.CURRENT_SECTOR;
        } else if (sector.getSectorNumber() >= 6) {
            // If we're on sector 7 (6 when zero-indexed) or later,
            // the quest is skipped as we shouldn't put quests into
            // the last stand.
            return QuestAddResult.TOO_LATE;
        } else {
            delayedQuests.add(questEvent);
            return QuestAddResult.NEXT_SECTOR;
        }
    }

    /**
     * Save the entire game state to a new XML document.
     * <p>
     * This can then be trivially serialised, and loaded back into
     * a new {@link InGameState} instance.
     */
    public Document saveGameState() {
        Element root = new Element("xftlSaveGame");
        root.setAttribute("enableAE", Boolean.toString(enableAdvancedEdition));
        root.setAttribute("difficulty", difficulty.name());

        ObjectRefs refs = new ObjectRefs();
        Sector sector = currentBeacon.getSector();

        // Generate IDs for all known ships
        refs.register(player, "player");
        for (Beacon beacon : sector.getBeacons()) {
            refs.register(beacon.getShip(), "ship");
        }
        if (enemy != null && enemy.getBoss() != null) {
            refs.register(enemy, "bossShip");
        }
        boolean saveDefeatedEnemy = enemy != null && !enemyIsHostile && enemy.hasCrewOwnedByShip(player);
        if (saveDefeatedEnemy) {
            refs.register(enemy, "defeatedEnemy");
        }

        // Add the ID for the boss managers. We have to do this now, so the IDs
        // are available when we're writing the boss ship if there is one.
        for (BossManager boss : sector.getBosses()) {
            refs.register(boss, "boss");
        }

        // If the player ship has a custom layout, save that.
        if (player.getCustomised() != null) {
            Element customLayout = new Element("customLayout");
            player.getCustomised().saveToXML(customLayout);
            root.addContent(customLayout);
        }

        // Serialise the player ship
        Element playerShip = new Element("playerShip");
        player.saveToXML(playerShip, refs);
        root.addContent(playerShip);

        // Serialise the flagship, as it's not stored at a beacon like normal ships.
        if (enemy != null && enemy.getBoss() != null) {
            Element flagshipElem = new Element("bossShip");
            enemy.saveToXML(flagshipElem, refs);
            root.addContent(flagshipElem);
        }

        // If there's a neutral ship with some player-owned crew in it, serialise it.
        // Once you crew-kill a ship it's not saved by the beacon, so without this
        // the ship would disappear if you save-loaded, taking your crew with it.
        if (saveDefeatedEnemy) {
            Element flagshipElem = new Element("defeatedEnemy");
            enemy.saveToXML(flagshipElem, refs);
            root.addContent(flagshipElem);
        }

        // Serialise the sector map (the one you access via exit beacons)
        Element mapXML = new Element("gameMap");
        gameMap.saveToXML(mapXML, refs);
        root.addContent(mapXML);

        // Serialise the sector
        Element sectorXML = new Element("currentSector");
        ObjectRefs sectorRefs = sector.saveToXML(sectorXML, refs);
        root.addContent(sectorXML);

        // Save the beacon the player is currently at - this is how
        // we'll know what enemy ship to load, we'll just use whatever
        // is at the same beacon.
        SaveUtil.INSTANCE.addRef(root, "currentBeacon", sectorRefs, currentBeacon);

        // Save the list of visited sectors, which is used by the sector map.
        Element visitedSectorsXML = new Element("visitedSectors");
        for (GameMap.SectorInfo visited : visitedSectors) {
            String ref = refs.get(visited);
            Element visitedElem = new Element("sectorInfo");
            visitedElem.setAttribute("idr", ref);
            visitedSectorsXML.addContent(visitedElem);
        }
        root.addContent(visitedSectorsXML);

        // If the player saves while they're currently in the event UI, save that.
        Element shipUiXML = new Element("shipUI");
        shipUI.saveToXML(shipUiXML, refs);
        root.addContent(shipUiXML);

        return new Document(root);
    }

    private void loadGameState(Element root) {
        RefLoader refs = new RefLoader();

        // If the player ship has a custom layout, load that.
        Element customLayout = root.getChild("customLayout");
        EditableShip customised = null;
        if (customLayout != null) {
            customised = EditableShip.loadFromXML(customLayout);
        }

        // If there's another ship at this beacon, it's hostile.
        // Set this now so we can un-set it for crew-killed ships.
        enemyIsHostile = true;

        // Load the player ship
        Element playerShip = root.getChild("playerShip");
        player = deserialiseSingleShip(playerShip, refs, customised);

        // Load the flagship, if we're fighting it.
        Element flagshipElem = root.getChild("bossShip");
        Ship overrideCurrentBeacon = null;
        if (flagshipElem != null) {
            overrideCurrentBeacon = deserialiseSingleShip(flagshipElem, refs, null);
        }

        // Load a ship we crew-killed but still has our crew
        Element defeatedElem = root.getChild("defeatedEnemy");
        if (defeatedElem != null) {
            overrideCurrentBeacon = deserialiseSingleShip(defeatedElem, refs, null);
            enemyIsHostile = false;
        }

        // Load the game map. The sector needs to reference it's SectorInfo
        // objects, so we need a separate reference loader we can switch
        // to resolve mode before loading the sector.
        RefLoader mapRefLoader = new RefLoader();
        Element mapXML = root.getChild("gameMap");
        gameMap = new GameMap(content, mapXML, mapRefLoader);
        mapRefLoader.switchToResolveMode();

        // Load the current sector - it looks odd to have a floating constructor,
        // but we only need it to create the current beacon and register it with
        // the reference loader. We then use our normal trick of only referring
        // to the current beacon, and letting that imply the current sector.
        Element sectorXML = root.getChild("currentSector");
        new Sector(sectorXML, refs, this, mapRefLoader);

        Element shipUiXML = root.getChild("shipUI");
        shipUI = new PlayerShipUI(player, this);
        shipUI.loadFromXML(shipUiXML, refs);

        // This resolves any async-resolved object references.
        refs.switchToResolveMode();

        // Load the list of visited sectors, which is used by the sector map.
        for (Element visitedElem : root.getChild("visitedSectors").getChildren("sectorInfo")) {
            String ref = visitedElem.getAttributeValue("idr");
            GameMap.SectorInfo visited = mapRefLoader.resolve(GameMap.SectorInfo.class, ref);
            visitedSectors.add(visited);
        }

        // We can finally load out our current beacon.
        currentBeacon = SaveUtil.INSTANCE.getRefImmediate(root, "currentBeacon", refs, Beacon.class);
        Objects.requireNonNull(currentBeacon);

        // Load in the previous enemy
        if (overrideCurrentBeacon != null) {
            setEnemy(overrideCurrentBeacon);
        } else {
            setEnemy(currentBeacon.getShip());
        }

        // We might have loaded a beacon with a store
        shipUI.updateButtons();
    }

    /**
     * Deserialise a player or enemy ship.
     * <p>
     * This should only be used by deserialisation code, specifically
     * the player ship deserialisation in this class and the enemy ship
     * deserialisation in beacons.
     */
    public Ship deserialiseSingleShip(Element shipElement, RefLoader refs, EditableShip customised) {
        // Load the appropriate ship definition XML element from the blueprints
        String shipId = shipElement.getAttributeValue("shipId");

        String specId = shipElement.getAttributeValue("specId");
        EnemyShipSpec spec;
        if (specId.equals("null")) {
            spec = null;
        } else {
            spec = content.eventManager.getShip(specId);
        }

        ShipBlueprint blueprint = blueprintManager.getShip(shipId);

        // And use that to build and deserialise the ship
        // Note we pass in null as the boss - that'll be updated if this turns out to be a boss.
        Ship ship = new Ship(blueprint, this, customised, spec, null);
        ship.loadFromXml(shipElement, refs);

        return ship;
    }

    @NotNull
    public Image getImg(String name) {
        Image img = content.images.get(name);
        if (img != null)
            return img;

        // This is just a way to access the image by a path, since we need
        // that for the missing animation.
        if (name.equals(Constants.MISSING_FILE_PATH))
            return getMissingImage();

        FTLFile file = df.getOrNull(name);
        if (file != null) {
            img = df.readImage(content.resourceContext, file);
        } else {
            System.out.printf("[WARN] Missing image: '%s'%n", name);
            img = getMissingImage();
        }
        content.images.put(name, img);
        return img;
    }

    /**
     * Loads an image if it's file exists, or returns null.
     * <p>
     * Try to avoid this if you can - normally image paths are specified in
     * some predictable way, so you shouldn't be guessing their names.
     */
    @Nullable
    public Image getImgIfExists(String name) {
        Image img = content.images.get(name);
        if (img != null)
            return img;

        FTLFile file = df.getOrNull(name);
        if (file == null)
            return null;

        img = df.readImage(content.resourceContext, file);
        content.images.put(name, img);
        return img;
    }

    /**
     * Creates a new {@link SILFontLoader} for a given font. This caches the bitmap and character
     * data, but the scale of each returned fontloader is completely independent.
     *
     * @param name The name of the font - the font at 'fonts/$name.font' is loaded
     * @return The new {@link SILFontLoader} instance
     */
    @NotNull
    public SILFontLoader getFont(String name) {
        SILFontLoader font = content.fonts.get(name);
        if (font != null) {
            // Make a new instance sharing the font data
            return new SILFontLoader(font);
        }

        font = new SILFontLoader(content.resourceContext, df, df.get("fonts/" + name + ".font"));
        content.fonts.put(name, font);
        return new SILFontLoader(font);
    }

    /**
     * Create a new {@link SILFontLoader} (see {@link #getFont(String)}), and also set the font's scale
     */
    @NotNull
    public SILFontLoader getFont(String name, float scale) {
        SILFontLoader font = getFont(name);
        font.setScale(scale);
        return font;
    }

    /**
     * Get or create a cursor for a given image path.
     */
    @NotNull
    public Cursor getCursor(String path) {
        Cursor cursor = content.cursors.get(path);
        if (cursor != null) {
            return cursor;
        }

        // For overlaying cursors
        String[] parts = path.split(",");
        FTLFile main = df.get(parts[0]);
        FTLFile overlay = parts.length == 2 ? df.get(parts[1]) : null;

        cursor = new Cursor(content.resourceContext, df, main, overlay);
        content.cursors.put(path, cursor);
        return cursor;
    }

    public Animations getAnimations() {
        return content.animations;
    }

    public SoundManager getSounds() {
        return content.sounds;
    }

    public SpecDeserialiser getUiLoader() {
        return uiLoader;
    }

    public RoomClickListener getClickEvent() {
        return clickEvent;
    }

    public void setClickEvent(RoomClickListener clickEvent) {
        this.clickEvent = clickEvent;
    }

    public Translator getTranslator() {
        return content.translator;
    }

    public GameMap getGameMap() {
        return gameMap;
    }

    public Beacon getCurrentBeacon() {
        return currentBeacon;
    }

    public BlueprintManager getBlueprintManager() {
        return blueprintManager;
    }

    public boolean isPaused() {
        return paused || shipUI.isWindowOpen();
    }

    /**
     * Try to avoid using, this is generally indicative of little hacks.
     * <p>
     * It's generally better to go through SlickGame or something else to
     * keep the various moving parts separate.
     */
    public PlayerShipUI getShipUI() {
        return shipUI;
    }

    /**
     * Try to avoid using, this is generally indicative of little hacks.
     */
    public Ship getPlayer() {
        return player;
    }

    /**
     * Try to avoid using, this is generally indicative of little hacks.
     */
    public Ship getEnemy() {
        return enemy;
    }

    /**
     * Find the ship that's the enemy of the specified one.
     * <p>
     * This should be used for things like drones and teleporters
     * which need to access the enemy ship if installed on the player,
     * or the player ship if installed on the enemey.
     */
    @Nullable
    public Ship getEnemyOf(Ship source) {
        // If there isn't a fight going on, no-one should be shooting at anyone else.
        if (!enemyIsHostile) {
            return null;
        }

        if (source == player) {
            return enemy;
        } else if (source == enemy) {
            return player;
        } else {
            // Some ship that isn't present
            return null;
        }
    }

    /**
     * Check if the given ship is either the player or enemy.
     * <p>
     * This is intended for drones, which need to self-destruct
     * when the ship powering them is gone (regardless of which
     * ship had the system installed, or who jumped away).
     */
    public boolean isShipPresent(@NotNull Ship ship) {
        Objects.requireNonNull(ship);
        return ship == enemy || ship == player;
    }

    /**
     * Try to avoid using, this is generally indicative of little hacks.
     */
    public IPoint getEnemyPosition() {
        return hostileShipUI.getShipPos();
    }

    public LootPool getLootPool() {
        return lootPool;
    }

    public EventManager getEventManager() {
        return eventManager;
    }

    public HotkeyManager getHotkeyManager() {
        return content.hotkeyManager;
    }

    public List<GameMap.SectorInfo> getVisitedSectors() {
        return Collections.unmodifiableList(visitedSectors);
    }

    public CrewNameManager getNameManager() {
        return content.nameManager;
    }

    public GameContent getContent() {
        return content;
    }

    public DebugFlagManager getDebugFlags() {
        return debugFlags;
    }

    public DebugConsole getDebugConsole() {
        // Create the debug console if it doesn't already exist
        if (debugConsole == null) {
            debugConsole = new DebugConsole(this);
        }

        return debugConsole;
    }

    /**
     * Stuff that happens in-game should be isolated to this specific game.
     * Think carefully about why you need to use this!
     */
    public MainGame getMainGame() {
        // This is only used for stuff like switching out of the main state,
        // which we don't currently support testing. Since very few things
        // do that, automated testing for that is overkill.
        if (mainGame == null) {
            throw new UnsupportedOperationException("Cannot get main state while running tests");
        }

        return mainGame;
    }

    /**
     * True if stuff is currently being deserialised.
     * <p>
     * It's generally a bit ugly to use this, but it's an easy
     * way to stop stuff from happening while the game is being loaded.
     */
    public boolean isCurrentlyLoadingSave() {
        return isCurrentlyLoadingSave;
    }

    @NotNull
    public Difficulty getDifficulty() {
        return difficulty;
    }

    /**
     * Get the delta-time (of wall-clock time) that has elapsed this frame.
     * <p>
     * This is for use in animations that play regardless of whether the game
     * is paused or not. It must NOT be used for anything related to gameplay,
     * as that would break if we change the game speed.
     */
    public float getRenderingDeltaTime() {
        return renderingDeltaTime;
    }

    /**
     * If there's currently a room click listener, this returns the room that is
     * currently being hovered.
     *
     * @see #setClickEvent(RoomClickListener)
     */
    @Nullable
    public Room getHoveredRoom() {
        return hoveredRoom;
    }

    /**
     * True if this 'game' is actually an automated test.
     * <p>
     * Try to avoid using this where possible to keep the tests accurate,
     * but it's useful for things that relate to sound or rendering.
     */
    public boolean isRunningAutomatedTest() {
        return mainGame == null;
    }

    @NotNull
    public Image getMissingImage() {
        if (missingImage != null)
            return missingImage;

        missingImage = df.readImage(content.resourceContext, df.get("img/nullResource.png"));
        return missingImage;
    }

    public WindowRenderer getWindowRenderer() {
        if (content.windowRenderer != null)
            return content.windowRenderer;

        Image background = getImg("img/window_base.png");
        Image outline = getImg("img/window_outline.png");
        Image mask = getImg("img/window_mask.png");
        content.windowRenderer = new WindowRenderer(background, outline, mask);

        return content.windowRenderer;
    }

    public void givePlayerResources(@NotNull ResourceSet resources) {
        player.setFuelCount(player.getFuelCount() + resources.getFuel());
        player.setDronesCount(player.getDronesCount() + resources.getDroneParts());
        player.setMissilesCount(player.getMissilesCount() + resources.getMissiles());
        player.setScrap(player.getScrap() + resources.getScrap());

        for (Blueprint item : resources.getItems()) {
            player.addBlueprint(item, true);
        }

        for (LivingCrewInfo info : resources.getCrew()) {
            player.addCrewMember(info, false, false);
        }

        for (RemoveCrewEval removed : resources.getLostCrew()) {
            LivingCrew crew = removed.getCrew();
            RemoveCrew info = removed.getInfo();

            if (player.getClonebay() != null) {
                String cloneMessage = info.getCloneText().resolve(Random.Default);
                if (!cloneMessage.isBlank()) {
                    shipUI.showSyntheticDialogue(new DialogueWindow.SyntheticEvent(cloneMessage));
                }

                // Never actually remove the crewmember if they're cloned
                if (info.getClone()) {
                    crew.jumpTo(player.findSpaceForCrew(player.getClonebay().getRoom(), crew.getMode()));
                    crew.onCloned(); // Deducts skills
                    continue;
                }
            }

            if (info.getTurnHostile()) {
                crew.setOwnerShip(null);
            } else {
                crew.removeFromShip();
            }
        }

        // Put all the intruders in the same room, as far as possible.
        int intruderRoomId = Random.Default.nextInt(player.getRooms().size());
        Room intruderRoom = player.getRooms().get(intruderRoomId);

        for (LivingCrewInfo info : resources.getIntruders()) {
            LivingCrew intruder = player.addCrewMember(info, false, true);

            RoomPoint slot = player.findSpaceForCrew(intruderRoom, AbstractCrew.SlotType.INTRUDER);
            intruder.jumpTo(slot);
        }

        // Apply the hull damage
        // TODO play the damage sound effect
        for (EventHullDamage damage : resources.getDamage()) {
            String system = damage.getSystem();
            if (system == null) {
                // A null system means hull damage only
                player.setHealth(player.getHealth() - damage.getAmount());
                continue;
            }

            Room targetRoom;

            if (system.equals("random")) {
                AbstractSystem randomSystem = player.getSystems().get(Random.Default.nextInt(player.getSystems().size()));
                targetRoom = randomSystem.getRoom();
            } else if (system.equals("room")) {
                targetRoom = player.getRooms().get(Random.Default.nextInt(player.getRooms().size()));
            } else {
                Optional<AbstractSystem> foundSystem = player.getSystems().stream()
                        .filter(s -> s.getCodename().equals(system))
                        .findFirst();
                if (foundSystem.isPresent()) {
                    targetRoom = foundSystem.get().getRoom();
                } else {
                    // Just pick a random room if this system isn't installed.
                    // This might not be what FTL does, but it should be close
                    // enough - in vanilla, this is only used to damage your
                    // engines so this case shouldn't come up without mods.
                    targetRoom = player.getRooms().get(Random.Default.nextInt(player.getRooms().size()));
                }
            }

            Objects.requireNonNull(targetRoom);

            player.damage(targetRoom, Damage.hullAndSys(damage.getAmount()), null);

            if (damage.getEffectFire()) {
                targetRoom.spawnFire();
                targetRoom.spawnFire();
            }
            if (damage.getEffectBreach()) {
                targetRoom.spawnBreach();
            }

            // TEST_EVENT is a good way to test this, as it spawns both of them.
        }

        // Apply system/reactor upgrades
        for (EventSystemUpgrade upgrade : resources.getUpgrades()) {
            if (upgrade.getSystem().equals("reactor")) {
                int newPower = player.getPurchasedReactorPower() + upgrade.getAmount();
                player.setPurchasedReactorPower(Math.min(newPower, player.getMaxReactorPower()));
                continue;
            }

            AbstractSystem system = player.getSystems().stream()
                    .filter(s -> s.getCodename().equals(upgrade.getSystem()))
                    .findFirst().orElse(null);

            if (system == null)
                continue;

            int newPower = system.getEnergyLevels() + upgrade.getAmount();
            system.setEnergyLevels(Math.min(system.getBlueprint().getMaxPower(), newPower));
        }

        // Apply the fleet pursuit modifier
        Sector currentSector = currentBeacon.getSector();
        currentSector.setFleetAdvanceModifier(currentSector.getFleetAdvanceModifier() + resources.getModifyPursuit());
    }

    /**
     * Advance the fleet pursuit by the amount determined by the current beacon.
     */
    public void advanceFleet() {
        Sector sector = currentBeacon.getSector();

        // The last stand works differently, capturing two beacons per jump.
        if (sector.isLastStand()) {
            advanceFleetLastStand();
            return;
        }

        Point dangerZone = sector.getDangerZoneCentre();

        int advance = sector.getFleetAdvanceFor(currentBeacon);

        dangerZone.setX(dangerZone.getX() + advance);

        for (Beacon beacon : sector.getBeacons()) {
            int distSq = beacon.getPos().distToSq(dangerZone);
            if (distSq > Sector.DANGER_ZONE_RADIUS_SQUARED)
                continue;

            beacon.setOvertaken(true);
        }

        // The modifier is a counter of how long we apply no or double pursuit,
        // so we have to bring it back towards zero.
        int modifier = sector.getFleetAdvanceModifier();
        if (modifier < 0)
            modifier++;
        if (modifier > 0)
            modifier--;
        sector.setFleetAdvanceModifier(modifier);
    }

    private void advanceFleetLastStand() {
        Sector sector = currentBeacon.getSector();

        // TODO implement fleet advance, capturing two beacons

        // Make the flagship jump
        for (BossManager boss : sector.getBosses()) {
            boss.advanceFleet();
        }
    }

    /**
     * FOR DEBUG USE ONLY!
     * <p>
     * When reloading the game every frame, some UI stuff (like the selection
     * rectangle) breaks. It'd be a bit silly to serialise this, but it's
     * also very hard to meaningfully play with it being constantly cleared.
     * <p>
     * Thus, this copies over basic UI stuff that's very unlikely to cover
     * up any serialisation bugs.
     */
    void debugContinuousSaveRestore(InGameState prev) {
        // Swap over the entire debug console, since it has no state.
        // Without this, the debug console would close across a reload.
        // Note we copy it whether or not it's open, so we don't loose
        // it's command history when you close it.
        if (prev.debugConsole != null) {
            debugConsole = prev.debugConsole;
            debugConsole.setGame(this);
        }
        debugConsoleVisible = prev.debugConsoleVisible;

        shipUI.debugContinuousSaveRestore(prev.shipUI);
        System.arraycopy(prev.mouseDownPrev, 0, mouseDownPrev, 0, mouseDownPrev.length);

        paused = prev.paused;

        // Copy over the debug flags.
        // It'd be annoying to lose those, since they're not supposed
        // to be saved.
        List<DebugFlagManager.DebugFlag> oldFlags = prev.debugFlags.getAll();
        List<DebugFlagManager.DebugFlag> newFlags = debugFlags.getAll();
        for (int i = 0; i < newFlags.size(); i++) {
            newFlags.get(i).setSet(oldFlags.get(i).getSet());
        }
    }

    /**
     * FOR DEBUGGING USE ONLY!
     * <p>
     * This is called by {@link MainGame} when we're in continuous save-load mode,
     * and there's an exception during the save-load process.
     */
    public void debugFailedSaveRestore() {
        // Pause the game so we don't get an ongoing stream of these errors.
        paused = true;

        // Print a message in the debug console, so the player knows what happened.
        debugConsoleVisible = true;
        getDebugConsole().onFailedSaveRestore();
    }

    /**
     * Update the list of player-owned crew, to account for crew being added or removed.
     */
    public void updatePlayerCrew() {
        // Ignore calls during deserialisation
        if (isCurrentlyLoadingSave)
            return;

        playerCrew.clear();

        addPlayerCrew(player.getCrew());
        if (player.getClonebay() != null)
            addPlayerCrew(player.getClonebay().getQueue());
        if (enemy != null)
            addPlayerCrew(enemy.getCrew());
    }

    private <T extends AbstractCrew> void addPlayerCrew(List<T> crewList) {
        for (AbstractCrew crew : crewList) {
            // Filter out drones
            if (!(crew instanceof LivingCrew living))
                continue;

            // Filter out intruders, not including mind-controlled crew.
            if (living.getOwnerShip() != player)
                continue;

            playerCrew.add(living);
        }
    }

    public List<LivingCrew> getPlayerCrew() {
        return playerCrew;
    }

    public Map<Hotkey, HotkeyButton> getReverseHotkeyBindings() {
        return Collections.unmodifiableMap(reverseHotkeyBindings);
    }

    public String getButtonNameForHotkey(String hotkeyID, String defaultName) {
        Hotkey key = getHotkeyManager().getByID().get(hotkeyID);
        if (key == null) {
            return defaultName;
        }

        HotkeyButton button = reverseHotkeyBindings.get(key);
        if (button == null) {
            return defaultName;
        }

        return getTranslator().get(button.getText());
    }

    /**
     * Update our cache of which key each hotkey is bound to.
     * <p>
     * This should be called if you modify the keybinds inside the [SaveProfile].
     */
    public void updateHotkeyBindings() {
        assert mainGame != null;
        getHotkeyManager().calculateBindings(mainGame.getProfile(), forwardHotkeyBindings, reverseHotkeyBindings);
    }

    private void setEnemyIsHostile(boolean enemyIsHostile) {
        if (this.enemyIsHostile == enemyIsHostile)
            return;

        this.enemyIsHostile = enemyIsHostile;

        if (player != null)
            player.enemyShipUpdated();

        if (enemy != null) {
            enemy.enemyShipUpdated();

            // Turn the enemy weapons off - this is only a visual thing at
            // this point, since the AI isn't being updated to fire them.
            for (Ship.Hardpoint hardpoint : enemy.getHardpoints()) {
                if (hardpoint.getWeapon() == null || enemy.getWeapons() == null)
                    continue;

                enemy.getWeapons().setWeaponPower(hardpoint.getWeapon(), false);
            }
        }
    }

    void showEventDialogue(Event event, int seed) {
        // The UI is null for automated tests
        if (shipUI != null) {
            shipUI.showEventDialogue(event, seed);
        }
    }

    public boolean isPlayerCrewFull() {
        return playerCrew.size() >= 8;
    }

    public boolean playerHasTooManyCrew() {
        return playerCrew.size() > 8;
    }

    public interface RoomClickListener {
        void roomClicked(Room room, GameContainer gc);

        default boolean canTargetRoom(@NotNull Room room) {
            return true;
        }
    }

    public enum QuestAddResult {
        CURRENT_SECTOR,
        NEXT_SECTOR,
        TOO_LATE,
    }

    /**
     * This represents all the parsed resources that can be pulled from a {@link Datafile}.
     * <p>
     * It's kept separate so that stuff like restarting the game (or starting a new game
     * where the mods and Advanced Edition mode are the same) is fast.
     */
    public static class GameContent {
        public final Datafile datafile;
        public final boolean enableAdvancedEdition;

        public final BlueprintManager blueprintManager;
        public final EventManager eventManager;
        public final CrewNameManager nameManager;
        public final Animations animations;
        public final SoundManager sounds;
        public final Translator translator;
        public final ShipGenerator generator;
        public final Achievement.AchievementTable achievements;
        public final ShipFamily.FamilyTable shipFamilies;
        public final HotkeyManager hotkeyManager;

        // These are loaded on-demand
        private final Map<String, Image> images = new HashMap<>();
        private final Map<String, SILFontLoader> fonts = new HashMap<>();
        private final Map<String, Cursor> cursors = new HashMap<>();

        // The context that owns the images and fonts
        private final ResourceContext resourceContext = new ResourceContext();

        // This is only here to avoid wasting heaps of resources in cont-save-load mode.
        private WindowRenderer windowRenderer;

        public GameContent(Datafile datafile, boolean enableAdvancedEdition) {
            this.datafile = datafile;
            this.enableAdvancedEdition = enableAdvancedEdition;

            blueprintManager = new BlueprintManager(datafile, enableAdvancedEdition);
            animations = new Animations(datafile);
            sounds = new RealSoundManager(datafile, resourceContext);
            generator = new ShipGenerator(datafile, blueprintManager);
            translator = new Translator(datafile, "en");
            eventManager = new EventManager(datafile, translator, blueprintManager);
            nameManager = new CrewNameManager(datafile, "en");
            achievements = new Achievement.AchievementTable(datafile);
            hotkeyManager = new HotkeyManager();

            blueprintManager.finishLoading(this);

            // TODO load this via the usual asset system, so mods can override it
            SAXBuilder builder = com.pocketwormhole.android.SafeXML.builder();
                        try {
                Document doc = builder.build(xyz.znix.xftl.sys.XftlResources.open("assets/data/xftl_ships.xml"));
                shipFamilies = new ShipFamily.FamilyTable(doc);
            } catch (JDOMException | IOException e) {
                throw new RuntimeException("Failed to load ship data", e);
            }
        }

        /**
         * Wrap some pre-loaded content.
         * <p>
         * This is used by automated tests.
         */
        public GameContent(
                Datafile datafile, boolean enableAdvancedEdition,
                BlueprintManager blueprintManager, EventManager eventManager,
                CrewNameManager nameManager, Animations animations,
                SoundManager sounds, Translator translator,
                ShipGenerator generator, Achievement.AchievementTable achievements,
                ShipFamily.FamilyTable shipFamilies, HotkeyManager hotkeyManager
        ) {
            this.datafile = datafile;
            this.enableAdvancedEdition = enableAdvancedEdition;
            this.blueprintManager = blueprintManager;
            this.eventManager = eventManager;
            this.nameManager = nameManager;
            this.animations = animations;
            this.sounds = sounds;
            this.translator = translator;
            this.generator = generator;
            this.achievements = achievements;
            this.shipFamilies = shipFamilies;
            this.hotkeyManager = hotkeyManager;
        }

        public void freeResources() {
            resourceContext.freeAll();
        }
    }

    private class GameUIProvider implements UIProvider {
        @NotNull
        @Override
        public SILFontLoader getFont(@NotNull String name) {
            return InGameState.this.getFont(name);
        }

        @NotNull
        @Override
        public Image getImg(@NotNull String path) {
            return InGameState.this.getImg(path);
        }

        @Nullable
        @Override
        public String translate(@NotNull String key) {
            return getTranslator().getTranslations().get(key);
        }

        @Nullable
        @Override
        public Colour getDebugOutlineColour(@NotNull Widget widget) {
            return null;
        }

        @NotNull
        @Override
        public WindowRenderer getWindowRenderer() {
            return content.windowRenderer;
        }
    }
}
