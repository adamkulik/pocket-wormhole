package xyz.znix.xftl.savegame

import org.jdom2.Element
import xyz.znix.xftl.Ship
import xyz.znix.xftl.layout.Room
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.math.IPoint
import xyz.znix.xftl.math.RoomPoint

object SaveUtil {
    // Saving
    fun addPoint(parent: Element, newName: String, point: IPoint) {
        val elem = Element(newName)
        elem.setAttribute("x", point.x.toString())
        elem.setAttribute("y", point.y.toString())
        parent.addContent(elem)
    }

    fun addRoomPoint(parent: Element, newName: String, point: RoomPoint) {
        val elem = Element(newName)
        // Use different element names to make this incompatible with addPoint,
        // to prevent them from being used in place of each other.
        elem.setAttribute("rx", point.x.toString())
        elem.setAttribute("ry", point.y.toString())
        elem.setAttribute("rid", point.room.id.toString())
        parent.addContent(elem)
    }

    fun addTag(parent: Element, newName: String, value: String) {
        // Using an attribute rather than an element contents means that
        // whitespace won't be changed by formatting the XML, and the
        // element name won't be repeated in the closing tag.
        val elem = Element(newName)
        elem.setAttribute("v", value)
        parent.addContent(elem)
    }

    fun addTagInt(parent: Element, newName: String, value: Int?, omitIfEqual: Int?) {
        if (value != omitIfEqual)
            addTagInt(parent, newName, value)
    }

    fun addTagInt(parent: Element, newName: String, value: Int?) {
        val elem = Element(newName)
        elem.setAttribute("i", value?.toString() ?: "null")
        parent.addContent(elem)
    }

    fun addTagFloat(parent: Element, newName: String, value: Float?, omitIfEqual: Float?) {
        if (value != omitIfEqual)
            addTagFloat(parent, newName, value)
    }

    fun addTagFloat(parent: Element, newName: String, value: Float?) {
        val elem = Element(newName)
        elem.setAttribute("f", value?.toString() ?: "null")
        parent.addContent(elem)
    }

    fun addTagBool(parent: Element, newName: String, value: Boolean) {
        val elem = Element(newName)
        elem.setAttribute("b", value.toString())
        parent.addContent(elem)
    }

    fun addTagBoolIfTrue(parent: Element, newName: String, value: Boolean) {
        if (value)
            addTagBool(parent, newName, value)
    }

    fun addRef(parent: Element, newName: String, refs: ObjectRefs, value: ISerialReferencable?) {
        // IDR stands for 'ID Reference'
        val elem = Element(newName)
        elem.setAttribute("idr", refs[value])
        parent.addContent(elem)
    }

    fun addRoomRef(parent: Element, newName: String, refs: ObjectRefs, room: Room) {
        val elem = Element(newName)
        elem.setAttribute("shipRef", refs[room.ship])
        elem.setAttribute("roomId", room.id.toString())
        parent.addContent(elem)
    }

    /**
     * Add the attribute that denotes an element represents an object
     * that can be referenced by its unique ID.
     */
    fun addObjectId(elem: Element, refs: ObjectRefs, obj: ISerialReferencable) {
        val id = refs[obj]
        elem.setAttribute("oid", id)
    }

    // Reading

    fun getPoint(parent: Element, name: String): ConstPoint {
        val elem = parent.getChild(name) ?: error("Missing point '$name'")
        val x = elem.getAttributeValue("x").toInt()
        val y = elem.getAttributeValue("y").toInt()
        return ConstPoint(x, y)
    }

    fun getRoomPoint(parent: Element, name: String, ship: Ship): RoomPoint {
        val elem = parent.getChild(name) ?: error("Missing room point '$name'")
        val x = elem.getAttributeValue("rx").toInt()
        val y = elem.getAttributeValue("ry").toInt()
        val roomId = elem.getAttributeValue("rid").toInt()
        val room = ship.rooms[roomId]
        return RoomPoint(room, x, y)
    }

    fun getTag(parent: Element, name: String): String {
        val elem = parent.getChild(name) ?: error("Missing tag '$name'")
        return elem.getAttributeValue("v")
    }

    fun getOptionalTag(parent: Element, name: String): String? {
        val elem = parent.getChild(name) ?: return null
        return elem.getAttributeValue("v")
    }

    fun getOptionalTagInt(parent: Element, name: String): Int? {
        val elem = parent.getChild(name) ?: return null
        return elem.getAttributeValue("i").toInt()
    }

    fun getTagIntOrNull(parent: Element, name: String): Int? {
        val elem = parent.getChild(name) ?: error("Missing int tag '$name'")
        val value = elem.getAttributeValue("i")
        if (value == "null")
            return null
        return value.toInt()
    }

    fun getTagInt(parent: Element, name: String): Int {
        return getTagIntOrNull(parent, name) ?: error("Null int tag '$name'")
    }

    fun getOptionalTagFloat(parent: Element, name: String): Float? {
        val elem = parent.getChild(name) ?: return null
        val value = elem.getAttributeValue("f")
        return value.toFloat()
    }

    fun getTagFloatOrNull(parent: Element, name: String): Float? {
        val elem = parent.getChild(name) ?: error("Missing float tag '$name'")
        val value = elem.getAttributeValue("f")
        if (value == "null")
            return null
        return value.toFloat()
    }

    fun getTagFloat(parent: Element, name: String): Float {
        return getTagFloatOrNull(parent, name) ?: error("Null float tag '$name'")
    }

    fun getTagBool(parent: Element, name: String): Boolean {
        return getOptionalTagBool(parent, name) ?: error("Missing boolean tag '$name'")
    }

    fun getOptionalTagBool(parent: Element, name: String): Boolean? {
        val elem = parent.getChild(name) ?: return null
        val value = elem.getAttributeValue("b")!!
        return value.toBoolean()
    }

    fun <T : ISerialReferencable> getOptionalRef(
        parent: Element, name: String, refs: RefLoader, type: Class<T>, callback: (T?) -> Unit
    ) {

        if (parent.getChild(name) == null) {
            callback(null)
            return
        }

        getRef(parent, name, refs, type, callback)
    }

    fun <T : ISerialReferencable> getRef(
        parent: Element, name: String, refs: RefLoader, type: Class<T>, callback: (T?) -> Unit
    ) {
        // IDR stands for 'ID Reference'
        val elem = parent.getChild(name) ?: error("Missing reference tag '$name'")
        val ref = elem.getAttributeValue("idr")!!
        return refs.asyncResolve(type, ref, callback)
    }

    fun <T : ISerialReferencable> getRefImmediate(parent: Element, name: String, refs: RefLoader, type: Class<T>): T? {
        // IDR stands for 'ID Reference'
        val elem = parent.getChild(name) ?: error("Missing reference tag '$name'")
        val ref = elem.getAttributeValue("idr")!!
        return refs.resolve(type, ref)
    }

    fun getRoomRef(parent: Element, name: String, refs: RefLoader, callback: (Room) -> Unit) {
        val elem = parent.getChild(name) ?: error("Missing room reference tag '$name'")
        val shipRef = elem.getAttributeValue("shipRef")
        val roomId = elem.getAttributeValue("roomId").toInt()

        refs.asyncResolve(Ship::class.java, shipRef) { ship ->
            callback(ship!!.rooms[roomId])
        }
    }

    /**
     * This is the counterpart of [addObjectId].
     */
    fun registerObjectId(elem: Element, refs: RefLoader, obj: ISerialReferencable) {
        val id = elem.getAttributeValue("oid") ?: error("Missing object ID!")
        refs.register(obj, id)
    }

    // Attribute setters

    fun addAttr(elem: Element, name: String, value: String) {
        elem.setAttribute(name, value)
    }

    fun addAttrFloat(elem: Element, name: String, value: Float?) {
        elem.setAttribute(name, value?.toString() ?: "null")
    }

    fun addAttrInt(elem: Element, name: String, value: Int?) {
        elem.setAttribute(name, value?.toString() ?: "null")
    }

    fun addAttrBool(elem: Element, name: String, value: Boolean) {
        elem.setAttribute(name, value.toString())
    }

    fun addAttrRef(elem: Element, name: String, refs: ObjectRefs, value: ISerialReferencable?) {
        elem.setAttribute(name, refs[value])
    }

    // Attribute getters

    fun getAttr(elem: Element, name: String): String {
        return elem.getAttributeValue(name) ?: error("Missing string attribute '$name'")
    }

    fun getAttrBool(elem: Element, name: String): Boolean {
        return elem.getAttributeValue(name)?.toBoolean() ?: error("Missing boolean attribute '$name'")
    }

    fun getAttrIntOrNull(elem: Element, name: String): Int? {
        val value = elem.getAttributeValue(name) ?: error("Missing int attribute '$name'")
        if (value == "null")
            return null
        return value.toInt()
    }

    fun getAttrInt(elem: Element, name: String): Int {
        return getAttrIntOrNull(elem, name) ?: error("Non-nullable int attribute '$name' was null!")
    }

    fun getAttrFloatOrNull(elem: Element, name: String): Float? {
        val value = elem.getAttributeValue(name) ?: error("Missing float attribute '$name'")
        if (value == "null")
            return null
        return value.toFloat()
    }

    fun getAttrFloat(elem: Element, name: String): Float {
        return getAttrFloatOrNull(elem, name) ?: error("Non-nullable float attribute '$name' was null!")
    }

    fun <T : ISerialReferencable> getAttrRef(
        elem: Element, name: String, refs: RefLoader, type: Class<T>, callback: (T?) -> Unit
    ) {
        val ref = elem.getAttributeValue(name) ?: error("Missing reference attribute '$name'")
        refs.asyncResolve(type, ref, callback)
    }

    fun <T : ISerialReferencable> getAttrRefImmediate(
        elem: Element, name: String, refs: RefLoader, type: Class<T>
    ): T? {
        val ref = elem.getAttributeValue(name) ?: error("Missing reference attribute '$name'")
        return refs.resolve(type, ref)
    }
}
