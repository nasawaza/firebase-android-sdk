// Copyright 2022 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.firebase.firestore.ktx.serialization

import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.builtins.LongAsStringSerializer.descriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.AbstractEncoder
import kotlinx.serialization.encoding.CompositeEncoder
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.serializer

/**
 * The entry point of Firestore Kotlin Serialization Process. It encodes the @[Serializable] objects
 * into nested maps of Firestore supported types.
 *
 * For a @[Serializable] object:
 * - At compile time, a serializer will be generated by the Kotlin serialization compiler plugin (or
 * a custom serializer can be passed in). The structure information of the @[Serializable] object
 * will be recorded inside of the serializer’s descriptor (i.e. the name/type of each property to be
 * encoded, the annotation on each property).
 * - At runtime, during the encoding process, based on the descriptor’s information, a nested map
 * will be generated. Each property which has its own structure (i.e. a nested @[Serializable]
 * object, a nested list) will be encoded as an embedded map nested inside.
 * - At the end of the encoding process, the filled nested map be returned as the encoding result of
 * the @[Serializable] object.
 *
 * @param descriptor: The [SerialDescriptor] of the encoding object.
 * @param depth Object depth, defined as objects within objects.
 * @param callback Sends the encoded nested map back to the caller.
 */
private class FirestoreMapEncoder(
    private val descriptor: SerialDescriptor,
    private val depth: Int = 0,
    private val callback: (MutableMap<String, Any?>) -> Unit
) : AbstractEncoder() {

    /** A map that saves the encoding result. */
    private val encodedMap: MutableMap<String, Any?> = mutableMapOf()

    /**
     * Encoding element's information can be obtained from the [descriptor] by the given [index].
     */
    private var index: Int = 0

    /**
     * Throw IllegalArgumentException if object depth, defined as objects within objects, larger
     * than 500.
     */
    init {
        if (depth == MAX_DEPTH) {
            throw IllegalArgumentException(
                "Exceeded maximum depth of $MAX_DEPTH, which likely indicates there's an object cycle"
            )
        }
    }

    /** Returns the final encoded result. */
    fun serializedResult() = encodedMap.toMap() // a defensive deep copy

    /** The data class records the information for the element that needs to be encoded. */
    private inner class Element(elementIndex: Int = 0) {
        val encodeKey: String = descriptor.getElementName(elementIndex)
        // TODO: Provide elementSerialKind, elementSerialName, and elementAnnotations as properties
    }

    /** Get the field name of an enum via index, and encode it. */
    override fun encodeEnum(enumDescriptor: SerialDescriptor, index: Int) =
        encodeValue(enumDescriptor.getElementName(index))

    override val serializersModule: SerializersModule = EmptySerializersModule

    override fun encodeNull(): Unit = encodedMap.let { it[Element(index++).encodeKey] = null }

    // TODO: Handle @DocumentId and @ServerTimestamp annotations from descriptor
    override fun encodeValue(value: Any): Unit =
        encodedMap.let { it[Element(index++).encodeKey] = value }

    override fun endStructure(descriptor: SerialDescriptor) = callback(encodedMap)

    /**
     * Recursively build the nested map when an encoded property has its own structure (i.e. a
     * nested @[Serializable] object, or a nested list).
     *
     * @param descriptor the [SerialDescriptor] of the @[Serializable] object.
     * @return a CompositeEncoder either to be a [FirestoreMapEncoder] or a [FirestoreListEncoder].
     */
    override fun beginStructure(descriptor: SerialDescriptor): CompositeEncoder {
        // TODO: @DocumentID and @ServerTimeStamp should not be applied on Structures
        if (depth == 0) {
            return FirestoreMapEncoder(descriptor, depth = depth + 1) { encodedMap.putAll(it) }
        }
        val currentElement = Element(index++)
        return when (descriptor.kind) {
            StructureKind.CLASS -> {
                FirestoreMapEncoder(descriptor, depth = depth + 1) {
                    encodedMap[currentElement.encodeKey] = it
                }
            }
            StructureKind.LIST -> {
                FirestoreListEncoder(depth = depth + 1) {
                    encodedMap[currentElement.encodeKey] = it
                }
            }
            else -> {
                throw IllegalArgumentException(
                    "Incorrect format of nested object provided: <$descriptor.kind>"
                )
            }
        }
    }
}

/**
 * Encodes a list of the @[Serializable] objects into a list of Firestore supported types.
 *
 * @param depth Object depth, defined as objects within objects.
 * @param callback PSends the encoded nested map back to the caller.
 */
private class FirestoreListEncoder(
    private val depth: Int = 0,
    private val callback: (MutableList<Any?>) -> Unit
) : AbstractEncoder() {

    /** A list that saves the encoding result. */
    private val encodedList: MutableList<Any?> = mutableListOf()

    /**
     * Throw IllegalArgumentException if object depth, defined as objects within objects, larger
     * than 500.
     */
    init {
        if (depth == MAX_DEPTH) {
            throw IllegalArgumentException(
                "Exceeded maximum depth of $MAX_DEPTH, which likely indicates there's an object cycle"
            )
        }
    }

    override val serializersModule: SerializersModule = EmptySerializersModule

    override fun encodeValue(value: Any): Unit = encodedList.let { it.add(value) }

    override fun encodeNull(): Unit = encodedList.let { it.add(null) }

    override fun endStructure(descriptor: SerialDescriptor) = callback(encodedList)

    /**
     * Recursively encoding if the elements inside of the list are @[Serializable] objects.
     *
     * @param descriptor the [SerialDescriptor] of the @[Serializable] object.
     * @return a [FirestoreMapEncoder] represent the @[Serializable] list element.
     */
    override fun beginStructure(descriptor: SerialDescriptor): CompositeEncoder {
        when (descriptor.kind) {
            StructureKind.CLASS -> {
                return FirestoreMapEncoder(descriptor, depth = depth + 1) { encodedList.add(it) }
            }
            else -> {
                throw IllegalArgumentException(
                    "Incorrect format of nested object provided: <$descriptor.kind>"
                )
            }
        }
    }
}

private const val MAX_DEPTH: Int = 500

/**
 * Encodes a @[Serializable] object to a nested map of Firestore supported types.
 *
 * @param serializer The [SerializationStrategy] of the @[Serializable] object.
 * @param value The @[Serializable] object.
 * @return The encoded nested map of Firestore supported types.
 */
fun <T> encodeToMap(serializer: SerializationStrategy<T>, value: T): Map<String, Any?> {
    val encoder = FirestoreMapEncoder(serializer.descriptor) {}
    encoder.encodeSerializableValue(serializer, value)
    return encoder.serializedResult()
}

inline fun <reified T> encodeToMap(value: T): Map<String, Any?> =
    encodeToMap(serializer(), value)