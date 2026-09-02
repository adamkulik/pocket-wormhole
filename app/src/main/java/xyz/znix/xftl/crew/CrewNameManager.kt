package xyz.znix.xftl.crew

import xyz.znix.xftl.Datafile
import kotlin.random.Random

/**
 * Stores all the available crew names, from names.xml.
 */
class CrewNameManager(df: Datafile, private val language: String) {
    val names: List<CrewNameList>

    init {
        val allNames = ArrayList<CrewNameList>()

        val root = df.parseXML(df["data/names.xml"]).rootElement

        for (child in root.children) {
            require(child.name == "nameList") { "Found non-nameList element in names.xml root" }

            val listNames = ArrayList<String>()
            val shortVersions = HashMap<String, String>()

            for (nameElem in child.children) {
                require(nameElem.name == "name") { "Found non-name element in nameList (in names.xml)" }
                val name = nameElem.textTrim
                listNames.add(name)

                val shortName = nameElem.getAttributeValue("short")
                if (shortName != null) {
                    shortVersions[name] = shortName
                }
            }

            val isMale = child.getAttributeValue("sex") == "male"
            val listLanguage = child.getAttributeValue("language") ?: "en"

            allNames.add(CrewNameList(listNames, shortVersions, isMale, listLanguage))
        }

        val matchingLanguage = allNames.filter { it.language == language }
        if (matchingLanguage.isNotEmpty()) {
            names = matchingLanguage
        } else {
            names = allNames.filter { it.language == "en" }
        }
    }

    fun getForGender(male: Boolean?, rand: Random): String {
        val lists = names.filter {
            // If a gender is specified, filter on that
            if (male != null) {
                if (male != it.isMale)
                    return@filter false
            }

            return@filter true
        }

        return lists.random(rand).names.random(rand)
    }

    fun findShort(name: String): String? {
        for (list in names) {
            list.shortVersions[name]?.let { return it }
        }
        return null
    }
}

class CrewNameList(
    val names: List<String>,

    /**
     * This list's mapping between full and shortened names.
     *
     * This is used for names that have custom shortened versions.
     */
    val shortVersions: Map<String, String>,

    val isMale: Boolean,
    val language: String
)
