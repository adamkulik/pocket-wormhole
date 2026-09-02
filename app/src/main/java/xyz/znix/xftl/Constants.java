package xyz.znix.xftl;

import xyz.znix.xftl.rendering.Colour;

public class Constants {
    public static final int ROOM_SIZE = 35;

    public static final Colour FLOOR_COLOUR = new Colour(230, 226, 219);
    public static final Colour FLOOR_COLOUR_NO_VISION = new Colour(55, 55, 55);
    public static final Colour FLOOR_COLOUR_NO_OXYGEN = new Colour(254, 178, 171);
    public static final Colour FLOOR_GRID_COLOUR = new Colour(0, 0, 0, 66);
    public static final Colour ROOM_BORDER_COLOUR = Colour.black;
    public static final Colour ROOM_BORDER_COLOUR_SELECTED = Colour.yellow;
    public static final Colour ROOM_BORDER_COLOUR_SELECTED_INNER = new Colour(255, 188, 0);

    // An image tint applied to broken doors
    public static final Colour DOOR_BROKEN_FILTER = new Colour(1f, 0.6f, 0.6f);

    public static final Colour SYSTEM_NORMAL = new Colour(125, 125, 125);
    public static final Colour SYSTEM_IONISED = new Colour(133, 231, 237);
    public static final Colour SYSTEM_HACKED = new Colour(207, 70, 253);
    public static final Colour SYSTEM_DAMAGED = new Colour(255, 153, 76);
    public static final Colour SYSTEM_BROKEN = new Colour(255, 0, 0);

    public static final Colour UI_TEXT_COLOUR_1 = new Colour(40, 78, 82);
    public static final Colour UI_BUTTON_HOVER = new Colour(255, 230, 94);
    public static final Colour UI_BACKGROUND_GLOW_COLOUR = new Colour(243, 255, 238);
    public static final Colour UI_SCRAP_TEXT_COLOUR = new Colour(243, 255, 230);

    public static final Colour PAUSE_TEXT_COLOUR = new Colour(220, 220, 220);

    public static final Colour SYS_ENERGY_ACTIVE = new Colour(100, 255, 100);
    public static final Colour SYS_ENERGY_DEPOWERED = new Colour(251, 251, 251);
    public static final Colour SYS_ENERGY_BROKEN = new Colour(255, 50, 50);
    public static final Colour SYS_ENERGY_SABOTAGE = new Colour(252, 53, 49);
    public static final Colour SYS_ENERGY_REPAIR = new Colour(255, 255, 50);
    public static final Colour SYS_ENERGY_ZOLTAN = new Colour(255, 250, 90);
    public static final Colour SYS_ENERGY_PURCHASE = new Colour(104, 98, 59);
    public static final Colour SYS_ENERGY_PURCHASE_HOVER = new Colour(164, 146, 108);
    public static final Colour SYS_ENERGY_PURCHASE_UNDOABLE = new Colour(255, 255, 100);
    public static final Colour SYS_ENERGY_EVENT_LOCKED = new Colour(44, 206, 225);
    public static final Colour SYS_ENERGY_BATTERY_OUTLINE = new Colour(226, 114, 31);
    public static final Colour SYS_ENERGY_ION_STORM = new Colour(40, 210, 230);

    public static final Colour WEAPONS_ITEM_DESELECTED = new Colour(150, 150, 150);
    public static final Colour WEAPONS_ITEM_SELECTED = UI_BACKGROUND_GLOW_COLOUR;
    public static final Colour WEAPONS_ITEM_CHARGED = new Colour(120, 255, 120);
    public static final Colour WEAPONS_ITEM_TARGETING = new Colour(255, 120, 120);
    public static final Colour WEAPONS_ITEM_DRONE_COOLDOWN = new Colour(255, 100, 100);

    public static final Colour WEAPONS_ITEM_ENERGY_UNPOWERED = WEAPONS_ITEM_DESELECTED;
    public static final Colour WEAPONS_ITEM_ENERGY_POWERED = WEAPONS_ITEM_SELECTED;
    public static final Colour WEAPONS_ITEM_ENERGY_CHARGED = WEAPONS_ITEM_CHARGED;
    public static final Colour WEAPONS_ITEM_ENERGY_ZOLTAN = SYS_ENERGY_ZOLTAN;

    public static final Colour DRONE_COOLDOWN_BACKGROUND = new Colour(255, 0, 0, 64);

    public static final Colour JUMP_READY = new Colour(235, 245, 0);
    public static final Colour JUMP_READY_TEXT = new Colour(37, 74, 77);
    public static final Colour JUMP_READY_TEXT_HOVER = new Colour(62, 125, 131);
    public static final Colour JUMP_DISABLED = new Colour(164, 171, 160);
    public static final Colour JUMP_DISABLED_TEXT = new Colour(25, 49, 51);

    public static final Colour JUMP_NO_FUEL_TINT = new Colour(0.0f, 0.0f, 0.0f, 0.4f);

    public static final Colour SECTOR_CUTOUT = new Colour(53, 75, 89);
    public static final Colour SECTOR_CUTOUT_TEXT = new Colour(235, 245, 229);
    public static final Colour SECTOR_CUTOUT_TEXT_GREEN = new Colour(46, 236, 54);
    public static final Colour SECTOR_CUTOUT_TEXT_PURPLE = new Colour(177, 190, 179);

    public static final Colour STORE_BUY_HOVER = new Colour(245, 238, 163);
    public static final Colour STORE_SELL_TITLE = new Colour(28, 21, 21);

    public static final Colour TEXT_OPTION_BLUE = new Colour(0, 195, 255);
    public static final Colour TEXT_OPTION_HOVER = new Colour(243, 255, 80);
    public static final Colour TEXT_OPTION_DISABLED = WEAPONS_ITEM_DESELECTED;

    public static final Colour REWARDS_ICONS = SYS_ENERGY_ACTIVE;
    public static final Colour REWARDS_NEGATIVE_ICONS = SYS_ENERGY_BROKEN;
    public static final Colour REWARDS_BACKGROUND = new Colour(0, 0, 0, 128);

    public static final Colour CREW_DESELECTED_BG = SYS_ENERGY_REPAIR;
    public static final Colour CREW_SELECTED_BG = SYS_ENERGY_ACTIVE;
    public static final Colour CREW_HOSTILE_BG = SYS_ENERGY_BROKEN;

    // Note: divide the colours by 4 if they're greyed-out because another
    // sector is hovered.
    public static final Colour SECTOR_CIVILIAN = new Colour(135, 199, 74);
    public static final Colour SECTOR_HOSTILE = new Colour(214, 50, 50);
    public static final Colour SECTOR_NEBULA = new Colour(128, 51, 210);

    // The colours of the branch lines that link up the sectors
    public static final Colour SECTOR_BRANCH = new Colour(255, 255, 255);
    public static final Colour SECTOR_BRANCH_GREYED = new Colour(125, 125, 125);
    public static final Colour SECTOR_BRANCH_HOVER = SYS_ENERGY_ACTIVE;
    public static final Colour SECTOR_BRANCH_PATH = SYS_ENERGY_REPAIR;

    // The colours of the background and text in the new sector window, which
    // describes the different sector colours.
    public static final Colour SECTOR_TYPE_CUTOUT = new Colour(31, 19, 19);
    public static final Colour SECTOR_TYPE_CUTOUT_TEXT = SECTOR_CUTOUT_TEXT;

    // These describe the name label box that shows the name of the next two sectors
    public static final Colour SECTOR_NAME_BACKGROUND = new Colour(0f, 0f, 0f, 0.8f);
    public static final Colour SECTOR_NAME_TEXT = SECTOR_BRANCH;
    public static final Colour SECTOR_NAME_TEXT_GREYED = SECTOR_BRANCH_GREYED;
    public static final Colour SECTOR_NAME_TEXT_HOVER = SYS_ENERGY_REPAIR;

    // These are the colours of the lines that connect beacons in the jump window
    public static final Colour BEACON_LINE_PLAYER = WEAPONS_ITEM_CHARGED;
    public static final Colour BEACON_LINE_HOVER = Colour.yellow;
    public static final Colour BEACON_LINE_FLAGSHIP = new Colour(230, 100, 100);

    public static final Colour BEACON_NEBULA_CIRCLE = new Colour(0f, 0f, 1f, 0.25f);

    // These are the colours of the augment box
    public static final Colour AUGMENT_BOX_OUTLINE = SECTOR_CUTOUT_TEXT;
    public static final Colour AUGMENT_BOX_OUTLINE_HOVER = UI_BUTTON_HOVER;
    public static final Colour AUGMENT_BOX_INSIDE = new Colour(146, 117, 113, 216);
    public static final Colour AUGMENT_NAME_TEXT = Colour.white;
    public static final Colour AUGMENT_EMPTY_OUTLINE = JUMP_DISABLED;
    public static final Colour AUGMENT_EMPTY_INSIDE = new Colour(34, 27, 26);

    public static final Colour SHIELD_BAR_NORMAL = new Colour(27, 132, 255);
    public static final Colour SHIELD_BAR_HACKED = SYSTEM_HACKED;

    // SHIELD_OPACITY_BASE is the base amount of opacity drawn on level-0 shields
    // (if they were to be drawn at all, which they won't), with SHIELD_OPACITY_SCALING
    // then multiplied by the current shield power divided by the maximum number
    // of shield layers (which is 4 for enemy ships and 3.5 for the player for some reason?).
    public static final float SHIELD_OPACITY_BASE = 0.4f;
    public static final float SHIELD_OPACITY_SCALING = (1 - SHIELD_OPACITY_BASE);

    public static final Colour CLONE_DYING_FILTER = new Colour(1f, 0.3f, 0.3f);

    public static final Colour CREW_BOX_NORMAL = new Colour(100, 100, 100);
    public static final Colour CREW_BOX_HOVER = new Colour(235, 235, 235);
    public static final Colour CREW_BOX_SELECT = new Colour(120, 255, 120);
    public static final Colour CREW_BOX_LOW_HEALTH = new Colour(255, 120, 120);
    public static final Colour CREW_BOX_STUNNED = new Colour(255, 255, 100);
    public static final Colour CREW_BOX_MIND_CONTROLLED = new Colour(255, 0, 255);
    public static final Colour CREW_BOX_CLONING = SYSTEM_IONISED;
    public static final Colour CREW_BOX_CLONE_DYING_OVERLAY = new Colour(255, 0, 0, 128);
    public static final float CREW_BOX_BG_ALPHA = 0.25f;
    public static final Colour CREW_BOX_NAME_COLOUR = new Colour(200, 200, 200);

    public static final Colour CREW_RENAME_TEXT_COLOUR = new Colour(245, 50, 50);

    public static final Colour SHIP_HEALTH_LOW = new Colour(255, 92, 92);
    public static final Colour SHIP_HEALTH_MED = new Colour(255, 230, 92);
    public static final Colour SHIP_HEALTH_HIGH = new Colour(120, 255, 120);

    public static final Colour WARNING_COLOUR_RED = new Colour(253, 84, 70);
    public static final Colour WARNING_COLOUR_WHITE = SECTOR_CUTOUT_TEXT;

    public static final Colour SHIP_BOX_NEUTRAL = Colour.white;
    public static final Colour SHIP_BOX_HOSTILE = new Colour(1f, 0.7f, 0.7f);

    public static final Colour SHIP_BOX_TEXT_NEUTRAL = new Colour(116, 119, 114);
    public static final Colour SHIP_BOX_TEXT_HOSTILE = new Colour(116, 83, 80);

    public static final Colour SHIP_STATUS_HOSTILE = SYS_ENERGY_BROKEN;
    public static final Colour SHIP_STATUS_PLAIN = UI_SCRAP_TEXT_COLOUR;

    public static final Colour UPGRADE_DETAILS_BG_ON = new Colour(127, 104, 107);
    public static final Colour UPGRADE_DETAILS_BG_OFF = new Colour(53, 43, 45);
    public static final Colour UPGRADE_DETAILS_POWER_OFF = new Colour(255, 255, 100, 64);

    public static final Colour SOLAR_FLARE_FILTER = new Colour(248, 129, 33);
    public static final Colour PULSAR_PULSE_FILTER = new Colour(133, 231, 237);

    public static final Colour DAMAGE_COLOUR_ION = new Colour(38, 210, 215);
    public static final Colour DAMAGE_COLOUR_ZOLTAN = new Colour(38, 240, 37);
    public static final Colour DAMAGE_COLOUR_SYSTEM = new Colour(243, 100, 93);

    public static final Colour ACHIEVEMENT_OUTLINE = CREW_BOX_NAME_COLOUR;
    public static final Colour ACHIEVEMENT_OUTLINE_HIGHLIGHT = UI_BUTTON_HOVER;

    public static final String MISSING_FILE_PATH = "xftl-missing.png";

    private Constants() {
    }
}
