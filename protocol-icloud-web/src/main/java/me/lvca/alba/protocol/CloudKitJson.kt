package me.lvca.alba.protocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

// Lenient readers for CloudKit and other iCloud web JSON: a missing or mistyped value reads as absent.

/** A primitive's content; numbers and booleans read as their text. */
internal fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

internal fun JsonObject.int(name: String): Int? = (this[name] as? JsonPrimitive)?.intOrNull

internal fun JsonObject.long(name: String): Long? = (this[name] as? JsonPrimitive)?.longOrNull

internal fun JsonObject.boolean(name: String): Boolean? = (this[name] as? JsonPrimitive)?.booleanOrNull

internal fun JsonObject.obj(name: String): JsonObject? = this[name] as? JsonObject

/** The array [name], or an empty one when it is absent. */
internal fun JsonObject.array(name: String): JsonArray = this[name] as? JsonArray ?: JsonArray(emptyList())

internal fun JsonObject.recordName(): String? = text("recordName")

internal fun JsonObject.recordType(): String? = text("recordType")

/** The `value` of a CloudKit record field. */
internal fun JsonObject.field(name: String): JsonElement? = (obj("fields")?.get(name) as? JsonObject)?.get("value")

internal fun JsonObject.fieldObject(name: String): JsonObject? = field(name) as? JsonObject

internal fun JsonObject.fieldText(name: String): String? = (field(name) as? JsonPrimitive)?.contentOrNull

internal fun JsonObject.fieldInt(name: String): Int? = (field(name) as? JsonPrimitive)?.intOrNull

internal fun JsonObject.fieldLong(name: String): Long? = (field(name) as? JsonPrimitive)?.longOrNull

/** A CloudKit INT64 or boolean flag field that is set: `1` or `true`. */
internal fun JsonObject.fieldFlag(name: String): Boolean = fieldText(name) in TRUE_FLAGS

/**
 * CloudKit's tombstone: the server deleted the record itself. The upload protocol uses this alone,
 * because a hidden or trashed asset is still the pair it uploaded.
 */
internal fun JsonObject.isTombstone(): Boolean {
    val deleted = this["deleted"] as? JsonPrimitive ?: return false
    return deleted.booleanOrNull == true || deleted.contentOrNull == "true" || deleted.longOrNull == 1L
}

/**
 * Gone from the library catalog: a tombstone, or an asset Photos hides or keeps in Recently Deleted.
 */
internal fun JsonObject.isRemovedFromLibrary(): Boolean =
    text("deleted") in TRUE_FLAGS || fieldFlag("isDeleted") || fieldFlag("isHidden")

private val TRUE_FLAGS = setOf("true", "1")
