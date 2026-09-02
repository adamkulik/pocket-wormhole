package xyz.znix.xftl.sector

import org.jdom2.Document
import org.jdom2.Element
import xyz.znix.xftl.BlueprintManager
import xyz.znix.xftl.Datafile
import xyz.znix.xftl.Translator
import xyz.znix.xftl.requireAttributeValue
import xyz.znix.xftl.shipgen.EnemyShipSpec

class EventManager(
    val df: Datafile,
    private val translator: Translator,
    private val bp: BlueprintManager,
    eventFileNames: List<String>
) {
    private val events = HashMap<String, IEvent>()
    private val textLists = HashMap<String, TextList>()
    private val imageLists = HashMap<String, ImageList>()
    private val ships = HashMap<String, EnemyShipSpec>()

    private val byDeserialisationId = HashMap<String, Event>()
    private val byChoiceId = HashMap<String, Choice>()

    // For the debug console
    val eventNames: Collection<String> = events.keys

    init {
        imageLists[ImageList.NONE.name] = ImageList.NONE

        for (event in eventFileNames) {
            loadEvents(df.parseXML(df[event]), true)
        }
        for (event in eventFileNames) {
            loadEvents(df.parseXML(df[event]), false)
        }

        // Make sure all the referenced ships do exist
        for (ev in events.values) {
            if (ev is Event) {
                check(ev.loadShipName == null || ships.containsKey(ev.loadShipName))
            } else if (ev is EventList) {
                // Load the lazy-loadable events list, to make sure we're not going
                // to crash at runtime because one an event doesn't exist.
                ev.events.toString()
            }
        }
    }

    /**
     * Load the standard vanilla event files.
     */
    constructor(df: Datafile, translator: Translator, bp: BlueprintManager) :
            this(df, translator, bp, FILE_NAMES.map { "data/$it.xml" })

    operator fun get(name: String): IEvent = events[name] ?: error("Missing event $name")

    fun getByDeserialisationId(id: String): Event {
        return byDeserialisationId[id] ?: error("Missing event with deserialisation ID '$id'")
    }

    fun getChoiceByDeserialisationId(id: String): Choice {
        return byChoiceId[id] ?: error("Missing choice with deserialisation ID '$id'")
    }

    fun getShip(name: String): EnemyShipSpec = ships[name] ?: error("Missing enemy ship spec '$name'")
    fun getImageList(name: String): ImageList = imageLists[name] ?: error("Missing image list '$name'")

    fun hasShip(name: String): Boolean = ships.containsKey(name)
    fun getShips(): Collection<EnemyShipSpec> = ships.values

    private fun loadEvents(doc: Document, resourcePass: Boolean) {
        val root = doc.rootElement
        check(root.name == "FTL")

        for (elem in root.children) {
            // Everything is loaded in two passes: once for the textlists/images (later is not yet implemented), and
            // a second time to load the events (which reference the resources).
            if (resourcePass) {
                when (elem.name) {
                    "textList" -> loadTextList(elem)
                    "imageList" -> loadImageList(elem)
                }
            } else {
                when (elem.name) {
                    "textList", "imageList" -> Unit // Handled in the resource pass
                    "eventList" -> loadEventList(elem)
                    "ship" -> loadShip(elem)
                    "eventCounts" -> loadEventCounts(elem)
                    "event" -> {
                        val name = elem.requireAttributeValue("name")
                        val serialId = "rootEvent::$name"
                        if (eventCheck(name, false))
                            events[name] = loadEvent(elem, name, serialId).value
                    }

                    else -> error("Unknown eventfile item ${elem.name}")
                }
            }
        }
    }

    private fun loadEventList(elem: Element) {
        check(elem.name == "eventList")
        val name = elem.requireAttributeValue("name")
        if (!eventCheck(name, true)) return

        val events = elem.children.withIndex().map {
            val serialId = "$name::eventList${it.index}"
            loadEvent(it.value, "$name.${it.index}", serialId)
        }
        this.events[name] = EventList(name, events)
    }

    private fun eventCheck(name: String, eventList: Boolean): Boolean {
        if (this.events.containsKey(name))
            println("Found duplicate event $name")

        // TODO make sure I've set these up right - I'm not completely sure about this, we could be losing events
        when (name) {
            "NEBULA_PIRATE" -> return !eventList // The individual event for this is definitely a testing event
            "BOARDERS_PIRATE" -> return eventList
            "NEBULA_REBEL" -> return !eventList
        }

        // If an event is duplicated, the latter one overwrites the former.
        // This is widely used by mods, as they append a modified copy of an event.
        return true
    }

    private fun loadTextList(elem: Element) {
        check(elem.name == "textList")
        val name = elem.requireAttributeValue("name")
        val texts = elem.children.map { loadText(it) }
        textLists[name] = TextList(name, texts)
    }

    private fun loadEventCounts(elem: Element) {
        check(elem.name == "eventCounts")

        // It appears this element is unused. It does not appear in the game binary. It looks
        // like it used to hold the the likelihood of events per sector (1..7), but that's now
        // stored in the sector type definitions (so a sector 4 nebula will have different contents
        // to an engi-controlled sector 4)
    }

    private fun loadShip(elem: Element) {
        check(elem.name == "ship")

        val ship = EnemyShipSpec(elem, bp, this)
        ships[ship.name] = ship
    }

    fun loadEmbeddedEvent(elem: Element, uniqueId: String): Lazy<IEvent> {
        val deserialisationId = "embeddedEvent::$uniqueId::${elem.name}"
        return loadEvent(elem, "$uniqueId.${elem.name}", deserialisationId, true)
    }

    private fun loadEvent(elem: Element, debugId: String, serialId: String, embed: Boolean = false): Lazy<IEvent> {
        if (!embed)
            check(elem.name == "event")

        elem.getAttributeValue("load")?.let {
            return lazy { events[it] ?: error("Missing event '$it', referenced from '$debugId'") }
        }

        // Build a mapping of the events by their deserialisation ID, which
        // we'll use to pick out events when the game is being loaded.
        // Mods can overwrite events, so we may need to add a number to make
        // this name unique.
        var uniqueId = serialId
        var suffix = 0
        while (byDeserialisationId.containsKey(uniqueId)) {
            uniqueId = "$serialId-$suffix"
            suffix++
        }

        val text = elem.getChild("text")?.let(::loadText)
        val choices = elem.getChildren("choice").withIndex().mapNotNull { loadChoice(it.value, it.index, uniqueId) }
        val event = Event(text, choices, elem, debugId, uniqueId, ::getImageList, ::loadText)

        byDeserialisationId[uniqueId] = event

        return lazyOf(event)
    }

    private fun loadChoice(elem: Element, choiceIndex: Int, parentDeserialisationId: String): Choice? {
        check(elem.name == "choice")

        // Apparently if the event element is missing it crashes in vanilla,
        // but that only happens when the event is loaded.
        // Thus mods (notably Multiverse 5.3) contain unused events without
        // an event node in one of their choices.
        val eventElem = elem.getChild("event")
        if (eventElem == null) {
            println("[WARN] Missing <event> tag in choice with parent deserialisation ID '$parentDeserialisationId'")
            return null
        }

        val text = elem.getChild("text").let(::loadText)
        val deserialisationId = "$parentDeserialisationId::choice$choiceIndex"
        val event = loadEvent(eventElem, "choice.ukn", deserialisationId)

        // We could use the same deserialisation ID for a choice and it's event,
        // but it's probably a bit nicer not to - if you're not that familiar
        // with the serialisation system, having a different name would let you
        // tell apart choices and their events.
        val choiceId = "choiceOf::$deserialisationId"
        val choice = Choice(text, event, elem, choiceId)

        if (byChoiceId.containsKey(choiceId)) {
            error("Choice deserialisation ID '$choiceId' is not unique!")
        }
        byChoiceId[choiceId] = choice

        return choice
    }

    private fun loadText(elem: Element): IEventText {
        check(elem.name == "text")
        elem.getAttributeValue("load")?.let {
            return textLists[it] ?: error("Missing textList $it")
        }
        elem.getAttributeValue("id")?.let {
            return EventText(translator[it])
        }
        // The CRYSTAL_HELP_DIG event has an empty string for
        // the crew clone message (since that is never triggered).
        return EventText(elem.textTrim)
    }

    private fun loadImageList(elem: Element) {
        if (elem.getAttributeValue("ui") == "ipad") return

        val name = elem.requireAttributeValue("name")
        val images = ArrayList<EnvironmentImage>()
        for (child in elem.children) {
            check(child.name == "img")
            val path = "img/" + child.textTrim
            if (df.getOrNull(path) == null) {
                println("[WARN] Non-existent image '$path' specified in image list '$name'")
                continue
            }
            images += EnvironmentImage(path)
        }
        imageLists[name] = ImageList(name, images)
    }

    companion object {
        private val FILE_NAMES = listOf(
            "events",
            "events_boss",
            "events_crystal",
            "events_engi",
            "events_fuel",
            "events_imageList",
            "events_mantis",
            "events_nebula",
            "events_pirate",
            "events_rebel",
            "events_rock",
            "events_ships",
            "events_slug",
            "events_zoltan",
            "nameEvents",
            "newEvents",

            "dlcEvents_anaerobic",
            // "dlcEventsOverwrite", // TODO set up overwrite support
            "dlcEvents"
        )
    }
}
