package xyz.znix.xftl.sector

import org.jdom2.Element
import xyz.znix.xftl.Datafile
import xyz.znix.xftl.vanillasave.VanillaRng

/**
 * Vanilla's event creation, as far as its random numbers go: which draws
 * creating an event takes, in vanilla's order, so the sector map generator
 * ([VanillaSectorLayout]) stays in step with vanilla's random stream and picks
 * the same event for each beacon. It walks the raw event XML the way vanilla
 * loads it; the events themselves are then taken from the [EventManager].
 *
 * Like vanilla it keeps state: crew names aren't reused for the whole run,
 * while a unique event is only made once, and a text from a text list only
 * picked once, within a sector map (see [startAttempt]).
 */
class VanillaEventWalker(df: Datafile, private val ae: Boolean) {
    // Event list entries are names (String) or inline events (Element)
    private val events = HashMap<String, Element>()
    private val lists = HashMap<String, List<Any>>()
    private val textLists = HashMap<String, MutableList<Element>>()
    private val textListsFull = HashMap<String, List<Element>>()
    private val ships = HashSet<String>()
    private val blueprintLists = HashSet<String>()
    private val removed = HashSet<String>()

    /**
     * Crew races vanilla can pick at random: name, weight (6 - rarity), alphabetically;
     * rarity 0 is left out. The rarities are the crew blueprints' unless the
     * sector's <rarityList> sets them (see [setSector]).
     */
    private val races = ArrayList<Pair<String, Int>>()
    private val blueprintRarity = HashMap<String, Int>()
    private val sectorRarity = HashMap<String, Map<String, Int>>()
    private val colourLayers = HashMap<String, Int>()

    /** Sector types (sector_data.xml): start event, events (name, min, max) in file order. */
    val sectorStart = HashMap<String, String>()
    val sectorEvents = HashMap<String, List<Triple<String, Int, Int>>>()

    /**
     * Crew names (BlueprintManager::GetCrewName): every name list without a
     * language, as one sorted list of distinct names, and the names used so far,
     * which vanilla draws again (GetUnusedCrewName, up to 21 tries). Vanilla
     * has the player's crew in the used list.
     */
    private val crewNames = ArrayList<String>()
    val usedNames = ArrayList<String>()

    /** Each draw with what it was for, when set (for comparing with vanilla). */
    var trace: ((String) -> Unit)? = null

    init {
        for (name in EVENT_FILES) {
            if (!ae && name.startsWith("dlc")) continue
            val file = df.getOrNull("data/$name.xml") ?: continue
            for (elem in df.parseXML(file).rootElement.children) {
                val n = elem.getAttributeValue("name") ?: continue
                when (elem.name) {
                    "event" -> events[n] = elem
                    "eventList" -> lists[n] = elem.getChildren("event").map { it.getAttributeValue("load") ?: it }
                    "textList" -> {
                        textLists[n] = elem.getChildren("text").toMutableList()
                        textListsFull[n] = elem.getChildren("text").toList()
                    }
                    "ship" -> ships += n
                }
            }
        }
        for (elem in df.parseXML(df["data/sector_data.xml"]).rootElement.getChildren("sectorDescription")) {
            val n = elem.getAttributeValue("name") ?: continue
            elem.getChildTextTrim("startEvent")?.let { sectorStart[n] = it }
            elem.getChild("rarityList")?.let { list ->
                sectorRarity[n] = list.getChildren("blueprint").associate {
                    it.getAttributeValue("name") to (it.getAttributeValue("rarity")?.toIntOrNull() ?: 0)
                }
            }
            sectorEvents[n] = elem.getChildren("event").map {
                Triple(
                    it.getAttributeValue("name"), it.getAttributeValue("min")?.toIntOrNull() ?: 0,
                    it.getAttributeValue("max")?.toIntOrNull() ?: 0
                )
            }
        }
        for (list in df.parseXML(df["data/names.xml"]).rootElement.getChildren("nameList")) {
            if (list.getAttributeValue("language") != null) continue
            crewNames += list.getChildren("name").map { it.textTrim }
        }
        // A std::map by name: sorted, each name once
        val sorted = crewNames.distinct().sortedWith { a, b -> compareBytes(a, b) }
        crewNames.clear()
        crewNames += sorted
        for (name in BLUEPRINT_FILES) {
            if (!ae && name.startsWith("dlc")) continue
            val file = df.getOrNull("data/$name.xml") ?: continue
            for (elem in df.parseXML(file).rootElement.children) {
                val n = elem.getAttributeValue("name") ?: continue
                when (elem.name) {
                    "blueprintList" -> blueprintLists += n
                    "crewBlueprint" -> {
                        blueprintRarity[n] = elem.getChildTextTrim("rarity")?.toIntOrNull() ?: 0
                        colourLayers[n] = elem.getChild("colorList")?.getChildren("layer")?.size ?: 0
                    }
                }
            }
        }
        setSector(null)
    }

    /**
     * Every attempt at a sector map starts with the text lists whole again
     * and every unique event available again (vanilla's EventGenerator::Clear):
     * an event is only unique within one sector map, and a text is only used
     * up within one.
     */
    fun startAttempt() {
        for ((n, full) in textListsFull) {
            val list = textLists[n]!!
            list.clear()
            list.addAll(full)
        }
        removed.clear()
    }

    /** The crew rarities for a sector type: its <rarityList> over the blueprints'. */
    fun setSector(name: String?) {
        val rarity = HashMap(blueprintRarity)
        name?.let { sectorRarity[it] }?.let { rarity.putAll(it) }
        races.clear()
        for ((n, r) in rarity.toSortedMap()) if (r != 0) races += Pair(n, 6 - r)
    }

    private fun draw(rng: VanillaRng, what: String): Int {
        val v = rng.next()
        trace?.invoke("$what = $v")
        return v
    }

    /**
     * The event vanilla made: a named event, or an event written inline in
     * an event list (the list and the index in it).
     */
    class Made(val name: String?, val list: String?, val index: Int)

    /** The XML of an event [get] made, to check what kind of event it is. */
    fun element(made: Made): Element? {
        made.name?.let { return events[it] }
        return lists[made.list ?: return null]?.getOrNull(made.index) as? Element
    }

    /**
     * EventGenerator::GetBaseEvent with no seed: retries until an event is
     * made. Null if there's no such event (or it's a unique one already made).
     */
    fun get(name: String, level: Int, rng: VanillaRng): Made? {
        if (name !in lists && (name !in events || name in removed)) return null
        while (true) {
            create(name, level, rng)?.let { return it }
        }
    }

    /** CreateEvent: the event made, or null if it couldn't be. */
    private fun create(name: String, level: Int, rng: VanillaRng, parentShip: String? = null): Made? {
        var entry: Any = name
        var listName: String? = null
        var index = 0
        while (entry is String && entry in lists) {
            val r = draw(rng, "list $entry")
            listName = if (ae && "OVERRIDE_$entry" in lists) "OVERRIDE_$entry" else entry
            val list = lists[listName]!!
            index = Integer.remainderUnsigned(r, list.size)
            entry = list[index]
        }
        val elem = when (entry) {
            is Element -> entry
            else -> (if (entry in removed) null else events[entry]) ?: return null
        }
        val label = (entry as? String) ?: elem.getAttributeValue("name") ?: "(inline)"
        if (!createFrom(elem, level, rng, label, parentShip)) return null
        return if (entry is String) Made(entry, null, 0) else Made(null, listName, index)
    }

    private fun createFrom(elem: Element, level: Int, rng: VanillaRng, label: String, parentShip: String?): Boolean {
        // Text from a text list: picked, then taken out of the list (unless it's the last one)
        elem.getChild("text")?.getAttributeValue("load")?.let { listName ->
            val texts = textLists[listName]
            if (texts != null && texts.isNotEmpty()) {
                val i = draw(rng, "$label text $listName") % texts.size
                if (texts.size >= 2) texts.removeAt(i)
            }
        }

        // Resources (item_modify): how many of them, then fuel, drones, missiles, scrap
        val mod = elem.getChild("item_modify")
        fun range(e: Element?) = Triple(
            e?.getAttributeValue("min")?.toIntOrNull() ?: 0,
            e?.getAttributeValue("max")?.toIntOrNull() ?: 0,
            e?.getAttributeValue("chance")?.toFloatOrNull() ?: -1f
        )

        fun roll(r: Triple<Int, Int, Float>, what: String): Int {
            if (r.third > 0f && draw(rng, "$label $what chance") % 100 < r.third * 100f) return 0
            if (r.second == 0) return r.first
            return r.first + draw(rng, "$label $what") % (r.second + 1 - r.first)
        }

        val items = mod?.getChildren("item")?.associateBy { it.getAttributeValue("type") } ?: emptyMap()
        val count = range(mod)
        var n = if (count.first != 0 || count.second != 0) roll(count, "item count") else 100
        if (draw(rng, "$label fuel?") % 4 < n) {
            if (roll(range(items["fuel"]), "fuel") > 0) n--
        }
        if (draw(rng, "$label drones?") % 3 < n) {
            if (roll(range(items["drones"]), "drones") > 0) n--
        }
        if (draw(rng, "$label missiles?") % 2 < n) {
            if (roll(range(items["missiles"]), "missiles") > 0) n--
        }
        draw(rng, "$label scrap?")
        if (n > 0) roll(range(items["scrap"]), "scrap")

        // Crew member
        elem.getChild("crewMember")?.let { c ->
            val amount = c.getAttributeValue("amount")?.toIntOrNull() ?: 0
            if (amount < 1) return@let
            val cls = c.getAttributeValue("class") ?: "random"
            val race = if (cls == "random") {
                races[weighted(races.map { it.second }, draw(rng, "$label crew race"))].first
            } else {
                unusedName(rng, "$label crew name ($cls)")
                cls
            }
            repeat(colourLayers[race] ?: 0) { draw(rng, "$label crew colour $race") }
            if (c.getAttributeValue("id").isNullOrEmpty() && c.textTrim.isEmpty())
                unusedName(rng, "$label crew name")
            if (SKILLS.none { c.getAttributeValue(it) != null })
                crewSkills(level, rng, label)
        }

        // Weapon, drone and augment rewards: a draw if random or from a blueprint list
        for (tag in listOf("weapon", "drone", "augment")) {
            val n2 = elem.getChild(tag)?.getAttributeValue("name") ?: continue
            if (n2 == "RANDOM" || n2 in blueprintLists) draw(rng, "$label $tag $n2")
        }

        elem.getChild("autoReward")?.let { autoReward(it, rng, label, elem) }

        // An enemy ship: its seed. A <ship> without load (eg hostile="true" in
        // a choice) is the ship of the nearest event above it that has one,
        // through event lists too.
        val shipTag = elem.getChild("ship")
        val ship = shipTag?.getAttributeValue("load") ?: parentShip
        if (shipTag != null && ship != null) {
            if (ship in lists) draw(rng, "$label ship list $ship")
            if (ship in ships) draw(rng, "$label ship seed $ship")
        }

        // Choices: each one's event, then its text if it's from a list
        for (choice in elem.getChildren("choice")) {
            val ev = choice.getChild("event") ?: continue
            val load = ev.getAttributeValue("load")
            val ok = if (load != null) create(load, level, rng, ship) != null
            else createFrom(ev, level, rng, "$label>", ship)
            if (!ok) return false
            choice.getChild("text")?.getAttributeValue("load")?.let { draw(rng, "$label choice text $it") }
        }

        // Boarders: how many, unless the event says
        if (elem.getChild("boarders")?.getAttributeValue("amount") == null)
            draw(rng, "$label boarders")

        if (elem.getAttributeValue("unique") == "true") elem.getAttributeValue("name")?.let { removed += it }
        return true
    }

    /** GetUnusedCrewName: names until one isn't used yet (21 tries), which is then used. */
    private fun unusedName(rng: VanillaRng, what: String) {
        var name: String
        var tries = 21
        while (true) {
            name = crewNames[draw(rng, what) % crewNames.size]
            if (usedNames.isEmpty() || name !in usedNames) break
            if (--tries == 0) break
        }
        usedNames += name
    }

    /** CrewBlueprint's random skills for a crew member found in sector [level]. */
    private fun crewSkills(level: Int, rng: VanillaRng, label: String) {
        val (lo, hi) = if (level >= 8) Pair(0, 0) else SKILL_LEVELS[level.coerceAtLeast(0)]
        val ups = lo + draw(rng, "$label crew skill count") % (hi - lo + 1)
        val first = draw(rng, "$label crew skill 1") % 6
        while (draw(rng, "$label crew skill 2") % 6 == first) Unit
        repeat(ups.coerceAtMost(4)) { draw(rng, "$label crew skill up") }
    }

    private fun autoReward(ar: Element, rng: VanillaRng, label: String, elem: Element) {
        var tier = when (ar.getAttributeValue("level")) {
            "LOW" -> 0; "MED" -> 1; "HIGH" -> 2; "RANDOM" -> 3; else -> -1
        }
        if (tier == -1) return
        if (tier == 3) tier = draw(rng, "$label reward tier") % 3
        var type = ar.textTrim
        fun roll(what: String) = draw(rng, "$label reward $what")
        fun other(not: String?): String {
            while (true) {
                val r = RESOURCES[draw(rng, "$label reward other") % 3]
                if (r != not) return r
            }
        }
        when (type) {
            "standard", "stuff" -> {
                roll("scrap")
                val o1 = other(null)
                val o2 = other(o1)
                roll(o1)
                roll(o2)
                if (draw(rng, "$label reward item?") % 100 < (if (type == "standard") 3 else 6)) type = "item"
            }

            "scrap_only" -> roll("scrap")
            else -> {
                val res = RESOURCES.firstOrNull { type == it || type == "${it}_only" }
                if (res != null) {
                    if (type == res) roll("scrap")
                    roll(res)
                }
            }
        }
        // An item, unless the event already gives one
        if (ITEMS.any { elem.getChild(it) != null }) return
        if (type == "item") type = ITEMS[draw(rng, "$label reward item type") % 3]
        if (type in ITEMS) {
            draw(rng, "$label reward $type")
            roll("scrap")
        }
    }

    companion object {
        /** Vanilla's event files, in the order it loads them. */
        val EVENT_FILES = listOf(
            "events", "dlcEvents", "dlcEvents_anaerobic", "newEvents", "events_rebel", "events_mantis",
            "events_engi", "events_pirate", "events_fuel", "events_nebula", "events_ships", "events_imageList",
            "events_crystal", "events_boss", "events_zoltan", "events_slug", "events_rock", "dlcEventsOverwrite"
        )
        val BLUEPRINT_FILES = listOf("blueprints", "dlcBlueprints", "dlcBlueprintsOverwrite")
        private val RESOURCES = listOf("fuel", "missiles", "droneparts")
        private val ITEMS = listOf("weapon", "drone", "augment")
        private val SKILLS = listOf("pilot", "engines", "shields", "weapons", "repair", "combat", "all_skills")

        /** Skill-ups for a crew member found in a sector (0-based): min, max. */
        private val SKILL_LEVELS = listOf(0 to 0, 0 to 0, 0 to 1, 0 to 2, 1 to 2, 1 to 2, 1 to 3, 2 to 3)

        /** std::string order: by UTF-8 bytes. */
        fun compareBytes(a: String, b: String): Int {
            val x = a.toByteArray()
            val y = b.toByteArray()
            for (i in 0 until minOf(x.size, y.size)) {
                val c = (x[i].toInt() and 0xff) - (y[i].toInt() and 0xff)
                if (c != 0) return c
            }
            return x.size - y.size
        }

        /**
         * Vanilla's weighted pick: the items sit in a sum tree (heap order, 1-based),
         * r = draw % total + 1 walks down it. Returns the item's index.
         */
        fun weighted(weights: List<Int>, draw: Int): Int {
            val n = weights.size
            val sum = IntArray(2 * n + 2)
            for (i in n downTo 1) sum[i] = weights[i - 1] + (if (2 * i <= n) sum[2 * i] else 0) +
                    (if (2 * i + 1 <= n) sum[2 * i + 1] else 0)
            var r = draw % sum[1] + 1
            var i = 1
            while (true) {
                val w = weights[i - 1]
                if (r <= w) return i - 1
                r -= w
                val left = 2 * i
                i = left
                if (sum[left] < r) {
                    r -= sum[left]
                    i = left + 1
                }
            }
        }
    }
}
