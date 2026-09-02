package xyz.znix.xftl.game;

import org.jdom2.Document;
import org.jdom2.JDOMException;
import org.jdom2.input.SAXBuilder;
import org.jdom2.output.Format;
import org.jdom2.output.XMLOutputter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.newdawn.slick.SlickException;
import org.newdawn.slick.util.InputAdapter;
import xyz.znix.xftl.Datafile;
import xyz.znix.xftl.VanillaDatafile;
import xyz.znix.xftl.devutil.DebugConsole;
import xyz.znix.xftl.hangar.EditableShip;
import xyz.znix.xftl.hangar.SelectShipState;
import xyz.znix.xftl.rendering.Cursor;
import xyz.znix.xftl.rendering.Graphics;
import xyz.znix.xftl.rendering.ShaderProgramme;
import xyz.znix.xftl.sys.DatafileSelectState;
import xyz.znix.xftl.sys.Game;
import xyz.znix.xftl.sys.GameContainer;
import xyz.znix.xftl.sys.PlatformSpecific;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public class MainGame implements Game {
    private VanillaDatafile vanillaDatafile;
    private Datafile datafile;
    private final CommandLineArgs commandLineArgs;

    private GameContainer gameContainer;

    private GameState currentState;

    private InGameState.GameContent content;

    private SaveProfile profile;

    private Cursor currentCursor;

    public MainGame(CommandLineArgs args) {
        this.commandLineArgs = args;
    }

    @Override
    public void init(@NotNull GameContainer gc) throws SlickException {
        gameContainer = gc;

        profile = loadProfile();

        // If we don't have a valid ftl.dat file to use, open the selection screen
        if (!loadDatafile()) {
            switchToDatafileSelect();
            return;
        }

        if (commandLineArgs.newGameShip != null) {
            // Switch right into a new game
            startNewGame(commandLineArgs.newGameShip, Difficulty.NORMAL, null);
        } else if (commandLineArgs.debugLoad != null) {
            Path path = DebugConsole.DEBUG_SAVE_DIR.resolve(commandLineArgs.debugLoad + ".xml");
            Document doc;
            try (BufferedReader reader = Files.newBufferedReader(path)) {
                SAXBuilder builder = com.pocketwormhole.android.SafeXML.builder();
                                doc = builder.build(reader);
            } catch (IOException | JDOMException ex) {
                throw new RuntimeException("Failed to load save", ex);
            }

            loadSavedGame(doc);
        } else {
            // Resume a run saved by Save+Quit, if there is one
            if (tryResumeRun()) {
                return;
            }

            switchToShipSelect();
        }
    }

    @Override
    public void shutdown() {
        // The state and content might be null if there was an exception during
        // initialisation, as this will be run in a finally block.
        // If we throw an exception here, it'll hide the original one that
        // caused the crash in the first place.
        if (currentState != null) {
            currentState.shutdown();
        }

        if (content != null) {
            content.freeResources();
        }
    }

    public void switchToShipSelect() {
        // This may have been called by DatafileSelectState
        if (vanillaDatafile == null) {
            loadDatafile();
        }

        SelectShipState state = new SelectShipState(datafile, this);
        setCurrentState(state);
    }

    public void switchToDatafileSelect() {
        setCurrentState(new DatafileSelectState(this));
    }

    public void startNewGame(@NotNull String shipName, Difficulty difficulty, EditableShip customised) {
        // A new run replaces any saved one
        deleteRunSave();
        InGameState inGameState = new InGameState(this, content, shipName, difficulty, customised);
        setCurrentState(inGameState);
    }

    public void restartGame() {
        InGameState state = (InGameState) currentState;
        startNewGame(state.getPlayer().getName(), state.getDifficulty(), state.getPlayer().getCustomised());
    }

    public void loadSavedGame(Document savedGame) {
        InGameState inGameState = new InGameState(this, content, savedGame);
        setCurrentState(inGameState);
    }

    @Override
    public void update(@NotNull GameContainer gc, float dt) throws SlickException {
        currentState.update(gc, dt);

        // If the profile needs saving, do that now. This means that if there's
        // a bunch of changes in the same update, we won't save multiple times.
        if (profile.getDirty()) {
            saveProfile();
        }
    }

    @Override
    public void render(@NotNull GameContainer gc, Graphics g) throws SlickException {
        // When we use shaders, we have to transform from pixels to NDC
        // If this is set wrong, all the text etc will be transformed wrong.
        ShaderProgramme.getSHADER_SCREEN_SIZE().set(gameContainer.getWidth(), gameContainer.getHeight());

        // Reset the transform from last frame, in case there was a transform
        // call that wasn't inside a pushTransform block.
        g.loadIdentityMatrix();

        currentState.render(gc, g);

        // Check there aren't any mismatched pushTransform/popTransform calls.
        g.checkNoPushedTransforms();

        // Update the mouse cursor, if required.
        Cursor newCursor = currentState.getCurrentCursor();
        if (newCursor != currentCursor) {
            gc.setCursor(newCursor);
            currentCursor = newCursor;
        }
    }

    @Override
    public String getTitle() {
        return "Project Wormhole";
    }

    public SaveProfile getProfile() {
        return profile;
    }

    /**
     * Save the current game to XML, and re-load it.
     * <p>
     * This is only for development use, to find bugs in the serialisation logic!
     */
    public boolean doSaveLoadGame() {
        InGameState oldGame = (InGameState) currentState;
        InGameState newGame;

        Document savedGame;
        try {
            savedGame = oldGame.saveGameState();
            newGame = new InGameState(this, content, savedGame);
        } catch (Exception ex) {
            oldGame.debugFailedSaveRestore();
            ex.printStackTrace();
            return false;
        }

        // Only change the state once we've done the save-load, so if
        // there's an exception while loading the saved state we stay
        // on the old state for ease of debugging.
        setCurrentState(newGame);

        // Copy over some basic UI stuff
        newGame.debugContinuousSaveRestore(oldGame);

        return true;
    }

    public GameState getCurrentState() {
        return currentState;
    }

    public void setCurrentState(GameState currentState) {
        if (this.currentState != null) {
            this.currentState.shutdown();
        }

        this.currentState = currentState;

        gameContainer.getInput().removeAllListeners();
        gameContainer.getInput().addListener(currentState);

        currentState.init(gameContainer);
    }

    public void quitGame() {
        // Save the current run so it can be resumed when the game is reopened.
        // (Upstream never saves here - the comment below is upstream's.)
        if (currentState instanceof InGameState) {
            try {
                Document doc = ((InGameState) currentState).saveGameState();

                Path path = getRunSavePath();
                Path tempFile = path.resolveSibling("run-save-temp.xml");
                Files.createDirectories(path.getParent());

                try (BufferedWriter writer = Files.newBufferedWriter(tempFile, StandardCharsets.UTF_8)) {
                    XMLOutputter xmlOutput = new XMLOutputter(Format.getPrettyFormat());
                    xmlOutput.output(doc, writer);
                }

                // Overwrite the run save with the newly-written one; atomic on
                // most OSes so a crash mid-write can't corrupt the old save.
                Files.move(tempFile, path, StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception ex) {
                // Still quit even if the save failed - but log it loudly.
                System.err.println("Failed to save run on quit:");
                ex.printStackTrace(System.err);
            }
        }

        gameContainer.exit();
    }

    private Path getRunSavePath() {
        return PlatformSpecific.INSTANCE.getSaveGamePath().resolve("run-save.xml");
    }

    private void deleteRunSave() {
        try {
            Files.deleteIfExists(getRunSavePath());
        } catch (IOException ex) {
            System.err.println("Failed to delete run save:");
            ex.printStackTrace(System.err);
        }
    }

    /**
     * Loads a run saved by Save+Quit, if there is one. Vanilla instead shows
     * a Continue button on its main menu, which we don't have yet.
     *
     * @return true if a saved run was restored
     */
    private boolean tryResumeRun() {
        Path path = getRunSavePath();
        if (!Files.exists(path)) {
            return false;
        }

        Document doc;
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            doc = com.pocketwormhole.android.SafeXML.builder().build(reader);
        } catch (IOException | JDOMException ex) {
            System.err.println("Saved run is corrupt, discarding it:");
            ex.printStackTrace(System.err);
            deleteRunSave();
            return false;
        }

        if (!doc.getRootElement().getName().equals("xftlSaveGame")) {
            // Not a run save; discard it rather than failing every launch
            deleteRunSave();
            return false;
        }

        // Valid enough to consume - if restoring fails afterwards the player
        // ends up in the hangar instead of crash-looping on every launch.
        deleteRunSave();

        try {
            loadSavedGame(doc);
        } catch (Exception ex) {
            System.err.println("Failed to restore the saved run:");
            ex.printStackTrace(System.err);
            switchToShipSelect();
            return false;
        }
        return true;
    }

    private boolean loadDatafile() {
        Path ftlDat = findFtlDat();
        if (ftlDat == null) {
            switchToDatafileSelect();
            return false;
        }
        vanillaDatafile = new VanillaDatafile(ftlDat.toFile());

        // TODO make mods adjustable in the ship select screen
        datafile = Datafile.Companion.loadWithMods(vanillaDatafile);

        // Load the game content immediately - this will work until
        // we support mods or turning Advanced Edition on or off.
        content = new InGameState.GameContent(datafile, true);

        return true;
    }

    public static Path findFtlDat() {
        // Let the user override the path with system properties
        String override = System.getProperty("xftl.datafile-path");
        if (override != null) {
            return Path.of(override);
        }
        override = System.getenv("XFTL_DATAFILE");
        if (override != null) {
            return Path.of(override);
        }

        // There's a text file which contains the path to the ftl.dat file
        Path ftlPathFile = PlatformSpecific.INSTANCE.getFtlDatPathFile();
        if (!Files.isRegularFile(ftlPathFile)) {
            // Create the file, so the user can find and edit it
            try {
                Files.write(ftlPathFile, "Put the path to ftl.dat here".getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                System.err.println("Failed to write placeholder to: " + ftlPathFile);
                //noinspection CallToPrintStackTrace
                e.printStackTrace();
            }

            return null;
        }

        String rawPath;
        try {
            rawPath = new String(Files.readAllBytes(ftlPathFile), StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            // Abort in this case, since there's probably something valid there.
            throw new RuntimeException(e);
        }

        Path path;
        try {
            path = Path.of(rawPath);
        } catch (InvalidPathException ignored) {
            System.err.printf("Invalid ftl.dat path (could not parse): '%s'%n", rawPath);
            return null;
        }

        if (!Files.isRegularFile(path)) {
            System.err.printf("Invalid ftl.dat path: '%s'%n", rawPath);
            return null;
        }

        return path;
    }

    private SaveProfile loadProfile() {
        Path profilePath = PlatformSpecific.INSTANCE.getSaveProfilePath();

        if (!Files.exists(profilePath)) {
            return SaveProfile.createBlank();
        }

        // If the profile file does exist, either load it or fail.
        // We *really* don't want to save over the top of it.
        SaveProfile profile = SaveProfile.load(profilePath);

        if (profile == null) {
            // Android port: don't kill the app, just start with a fresh profile
            System.err.println("Failed to load profile at: " + profilePath + " - starting fresh");
            return SaveProfile.createBlank();
        }

        return profile;
    }

    private void saveProfile() {
        // First, save to a temporary file
        Path tempFile = PlatformSpecific.INSTANCE.getSaveGamePath().resolve("profile-save-temp.xml");
        Document doc = profile.save();

        // If we fail to save, don't try again every frame.
        profile.markSaveComplete();

        // Make sure the directory exists
        if (!Files.exists(tempFile.getParent())) {
            try {
                Files.createDirectories(tempFile.getParent());
            } catch (IOException e) {
                // Fine to crash, should happen on startup when we try
                // and save the new, blank profile.
                // If we don't crash, the player might try and play without
                // being able to save their game.
                throw new RuntimeException("Failed to create savegame directory", e);
            }
        }

        try (BufferedWriter writer = Files.newBufferedWriter(tempFile, StandardCharsets.UTF_8)) {
            XMLOutputter xmlOutput = new XMLOutputter(Format.getPrettyFormat());
            xmlOutput.output(doc, writer);

            // Overwrite the main save file with the newly-written one
            // On most OSes this should be atomic, so we shouldn't be able
            // to get a corrupted profile with this.
            Files.move(tempFile, PlatformSpecific.INSTANCE.getSaveProfilePath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            // Hopefully the profile will be saved again, and succeed.
            System.err.println("Failed to save profile:");
            ex.printStackTrace(System.err);
        }
    }

    public static abstract class GameState extends InputAdapter {
        public void init(@NotNull GameContainer container) {
        }

        public abstract void shutdown();

        public abstract void update(@NotNull GameContainer container, float delta) throws SlickException;

        public abstract void render(@NotNull GameContainer container, @NotNull Graphics g) throws SlickException;

        @Nullable
        public Cursor getCurrentCursor() {
            return null;
        }
    }

    public static class CommandLineArgs {
        // If a new game should be started, this is the name of the ship to use.
        public String newGameShip;

        // If a save created with the debug 'save' command should be loaded, this is it's name.
        public String debugLoad;
    }
}
