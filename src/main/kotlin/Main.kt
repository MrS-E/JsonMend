@file:Suppress("UNCHECKED_CAST")

import com.google.gson.*
import com.google.gson.annotations.SerializedName
import kotlin.reflect.*
import kotlin.reflect.full.*
import kotlin.reflect.jvm.isAccessible

private val gson: Gson = GsonBuilder().create()

/** Entry point */
inline fun <reified T : Any> recoverFromJson(json: String): T =
    recoverFromElement(JsonParser.parseString(json), T::class)

fun <T : Any> recoverFromElement(el: JsonElement?, kClass: KClass<T>): T {
    // If the target is NOT a data class, try primitive/collection handling
    if (!kClass.isData) {
        return parseNonData(el, kClass) ?: error("Cannot parse ${kClass.simpleName} and no fallback.")
    }

    val ctor = kClass.primaryConstructor
        ?: error("Data class ${kClass.simpleName} must have a primary constructor")
    ctor.isAccessible = true

    val jsonObj = el?.asJsonObjectOrNull()
    val args = mutableMapOf<KParameter, Any?>()

    for (param in ctor.parameters) {
        val targetType = param.type
        val isNullable = targetType.isMarkedNullable
        val jsonNameCandidates = jsonNamesForParam(param, kClass)

        val valueEl = jsonObj?.let { findByAnyName(it, jsonNameCandidates) }
            ?: jsonObj?.let { findByAnyNameCaseInsensitive(it, jsonNameCandidates) }

        val parsed: Any? = try {
            parseByType(valueEl, targetType)
        } catch (_: Throwable) {
            null
        }

        when {
            parsed != null -> args[param] = parsed
            parsed == null && param.isOptional -> {
                // omit to use default value
            }
            parsed == null && isNullable -> args[param] = null
            parsed == null -> {
                // last resort: if it's a data class param, try empty object {}
                val classifier = targetType.classifier as? KClass<*>
                if (classifier?.isData == true) {
                    args[param] = recoverFromElement(JsonObject(), classifier as KClass<Any>)
                } else {
                    error("Required non-null param '${param.name}' could not be recovered for ${kClass.simpleName}")
                }
            }
        }
    }

    return ctor.callBy(args)
}

/* ------------ Helpers: name resolution, parsing, type checks ------------ */

private fun jsonNamesForParam(param: KParameter, owner: KClass<*>): List<String> {
    val base = param.name ?: ""
    val prop = owner.memberProperties.find { it.name == base }
    val annOnParam = param.annotations.filterIsInstance<SerializedName>().firstOrNull()
    val annOnProp = prop?.annotations?.filterIsInstance<SerializedName>()?.firstOrNull()

    val names = mutableListOf<String>()
    fun collect(a: SerializedName?) {
        if (a != null) {
            names += a.value
            names += a.alternate
        }
    }
    names += base
    collect(annOnParam)
    collect(annOnProp)
    return names.distinct()
}

private fun findByAnyName(obj: JsonObject, names: List<String>): JsonElement? =
    names.asSequence().mapNotNull { n -> if (obj.has(n)) obj.get(n) else null }.firstOrNull()

private fun findByAnyNameCaseInsensitive(obj: JsonObject, names: List<String>): JsonElement? {
    val map = obj.entrySet().associate { it.key.lowercase() to it.value }
    return names.firstNotNullOfOrNull { map[it.lowercase()] }
}

/* ---------- helpers ---------- */

@Suppress("UNCHECKED_CAST")
fun <T : Any> parseNonData(el: JsonElement?, kClass: KClass<T>): T? {
    if (el == null || el.isJsonNull) return null

    when (kClass) {
        String::class  -> return el.asStringOrNull() as T?
        Int::class     -> return el.asIntOrNull() as T?
        Long::class    -> return el.asLongOrNull() as T?
        Double::class  -> return el.asDoubleOrNull() as T?
        Float::class   -> return el.asFloatOrNull() as T?
        Boolean::class -> return el.asBooleanOrNull() as T?
        Char::class    -> return el.asStringOrNull()?.firstOrNull() as T?
    }

    if (kClass.java.isEnum) {
        return parseEnum(el, kClass)  // may return null
    }

    if (kClass == List::class || kClass == MutableList::class) {
        val list = if (el.isJsonArray) el.asJsonArray.map { parseLoose(it) } else listOf(parseLoose(el))
        return list as T
    }
    if (kClass == Set::class || kClass == MutableSet::class) {
        val set = if (el.isJsonArray) el.asJsonArray.map { parseLoose(it) }.toSet() else setOf(parseLoose(el))
        return set as T
    }
    if (kClass == Map::class || kClass == MutableMap::class) {
        val map = if (el.isJsonObject) {
            el.asJsonObject.entrySet().associate { (k, v) -> k to parseLoose(v) }
        } else emptyMap()
        return map as T
    }

    if (kClass.java.isArray) {
        // Try Gson first
        runCatching { return gson.fromJson(el, kClass.java) as T }.getOrNull()
        // Fallback loose
        val asList = if (el.isJsonArray) el.asJsonArray.map { parseLoose(it) } else listOf(parseLoose(el))
        val anyArray = java.lang.reflect.Array.newInstance(Any::class.java, asList.size)
        asList.forEachIndexed { i, v -> java.lang.reflect.Array.set(anyArray, i, v) }
        return anyArray as T
    }

    return runCatching { gson.fromJson(el, kClass.java) as T }.getOrNull()
}

@Suppress("UNCHECKED_CAST")
private fun <T : Any> parseEnum(el: JsonElement, kClass: KClass<T>): T? {
    val raw = el.asStringOrNull() ?: return null
    val constants = kClass.java.enumConstants as Array<Enum<*>>

    // Try name (case-insensitive, lenient on separators)
    val norm = raw.trim().replace('-', '_').replace(' ', '_')
    constants.firstOrNull { it.name.equals(raw, ignoreCase = true) }
        ?: constants.firstOrNull { it.name.equals(norm, ignoreCase = true) }
            ?.let { return it as T }

    // Try ordinal
    raw.toIntOrNull()?.let { ord ->
        constants.getOrNull(ord)?.let { return it as T }
    }

    return null
}

private fun parseLoose(el: JsonElement?): Any? {
    if (el == null || el.isJsonNull) return null
    return when {
        el.isJsonPrimitive -> {
            val p = el.asJsonPrimitive
            when {
                p.isBoolean -> p.asBoolean
                p.isNumber  -> runCatching { p.asLong }.getOrElse { runCatching { p.asDouble }.getOrElse { p.asString } }
                else        -> p.asString
            }
        }
        el.isJsonArray -> el.asJsonArray.map { parseLoose(it) }
        el.isJsonObject -> el.asJsonObject.entrySet().associate { (k, v) -> k to parseLoose(v) }
        else -> null
    }
}

private fun parseByType(el: JsonElement?, kType: KType): Any? {
    val classifier = kType.classifier as? KClass<*> ?: return null

    if (el == null || el.isJsonNull) return null

    // Collections
    if (classifier == List::class || classifier == MutableList::class) {
        val argType = kType.arguments.firstOrNull()?.type ?: return emptyList<Any>()
        return parseCollection(el, argType, toSet = false)
    }
    if (classifier == Set::class || classifier == MutableSet::class) {
        val argType = kType.arguments.firstOrNull()?.type ?: return emptySet<Any>()
        return parseCollection(el, argType, toSet = true)
    }
    if (classifier == Map::class || classifier == MutableMap::class) {
        // Try best-effort map recovery (string keys expected)
        val keyType = kType.arguments.getOrNull(0)?.type
        val valueType = kType.arguments.getOrNull(1)?.type
        return parseMap(el, keyType, valueType)
    }

    // Data class recursion
    if (classifier.isData) {
        return recoverFromElement(el, classifier as KClass<Any>)
    }

    // Primitive-ish
    parsePrimitiveLike(el, classifier)?.let { return it }

    // Fallback: let Gson try raw convert to target type
    return try {
        gson.fromJson(el, gsonTypeOf(kType))
    } catch (_: Throwable) {
        null
    }
}

private fun parseCollection(el: JsonElement, argType: KType, toSet: Boolean): Any {
    val empty = if (toSet) emptySet<Any>() else emptyList<Any>()
    if (!el.isJsonArray) {
        // Not an array → try salvage single item
        val one = parseByType(el, argType)
        return if (one != null) {
            if (toSet) setOf(one) else listOf(one)
        } else empty
    }

    val arr = el.asJsonArray
    // Attempt full-parse via Gson first
    val full = runCatching {
        val raw = if (toSet) mutableSetOf<Any>() else mutableListOf<Any>()
        for (item in arr) {
            val v = parseByType(item, argType)
            if (v == null) throw IllegalStateException()
            raw += v
        }
        raw
    }.getOrNull()

    if (full != null) return if (toSet) full.toSet() else full.toList()

    // Whole list failed → try per-item salvage
    val salvaged = mutableListOf<Any>()
    for (item in arr) {
        val v = runCatching { parseByType(item, argType) }.getOrNull()
        if (v != null) salvaged += v
    }
    return if (toSet) salvaged.toSet() else salvaged.toList()
}

private fun parseMap(el: JsonElement, keyType: KType?, valueType: KType?): Map<Any?, Any?> {
    if (!el.isJsonObject) return emptyMap()
    val obj = el.asJsonObject
    val result = linkedMapOf<Any?, Any?>()
    for ((k, v) in obj.entrySet()) {
        val parsedKey = when {
            keyType == null -> k
            (keyType.classifier as? KClass<*>)?.isData == true -> recoverFromElement(JsonParser.parseString(k), keyType.classifier as KClass<Any>)
            else -> coerceStringKey(k, keyType)
        }
        val parsedVal = if (valueType == null) v else parseByType(v, valueType)
        if (parsedVal != null) result[parsedKey] = parsedVal
    }
    return result
}

private fun coerceStringKey(raw: String, keyType: KType): Any? {
    val k = keyType.classifier as? KClass<*> ?: return raw
    return when (k) {
        String::class -> raw
        Int::class -> raw.toIntOrNull()
        Long::class -> raw.toLongOrNull()
        Double::class -> raw.toDoubleOrNull()
        Float::class -> raw.toFloatOrNull()
        Boolean::class -> raw.equals("true", ignoreCase = true)
        else -> raw // fallback
    }
}

private fun parsePrimitiveLike(el: JsonElement, k: KClass<*>): Any? = when (k) {
    String::class -> el.asStringOrNull()
    Int::class -> el.asIntOrNull()
    Long::class -> el.asLongOrNull()
    Double::class -> el.asDoubleOrNull()
    Float::class -> el.asFloatOrNull()
    Boolean::class -> el.asBooleanOrNull()
    Char::class -> el.asStringOrNull()?.firstOrNull()
    else -> null
}

/* ------------ Gson/KType bridge ------------ */

@OptIn(ExperimentalStdlibApi::class)
private fun gsonTypeOf(kType: KType) = com.google.gson.reflect.TypeToken.get(
    (kType.javaType)
).type

/* ------------ JsonElement safe accessors ------------ */

private fun JsonElement.asJsonObjectOrNull(): JsonObject? = if (isJsonObject) asJsonObject else null
private fun JsonElement.asStringOrNull(): String? = try { if (isJsonNull) null else asString } catch (_: Exception) { null }
private fun JsonElement.asIntOrNull(): Int? = try { if (isJsonNull) null else asInt } catch (_: Exception) { asStringOrNull()?.toIntOrNull() }
private fun JsonElement.asLongOrNull(): Long? = try { if (isJsonNull) null else asLong } catch (_: Exception) { asStringOrNull()?.toLongOrNull() }
private fun JsonElement.asDoubleOrNull(): Double? = try { if (isJsonNull) null else asDouble } catch (_: Exception) { asStringOrNull()?.toDoubleOrNull() }
private fun JsonElement.asFloatOrNull(): Float? = asDoubleOrNull()?.toFloat()
private fun JsonElement.asBooleanOrNull(): Boolean? = try { if (isJsonNull) null else asBoolean } catch (_: Exception) {
    asStringOrNull()?.let { it.equals("true", true) || it == "1" }
}

fun main() {
    data class Address(
        val street: String = "unknown",
        val city: String = "unknown"
    )

    data class Person(
        @SerializedName("full_name", alternate = ["name"])
        val name: String = "n/a",
        val age: Int = 0,
        val tags: Set<String> = emptySet(),
        val address: Address = Address(),
        val friends: List<Person> = emptyList()
    )

    fun demo() {
        val corrupted = """
        {
          "full_name": 123,             // wrong type for name
          "age": "27",                  // string instead of number (recover)
          "tags": ["a", 2, null, {}],   // mixed types -> salvage "a"
          "address": {"street": 7},     // street wrong type, fallback "unknown"
          "friends": [{ "name": "Sam" }, 42, {"name":true}]
        }
    """.trimIndent()

        val p: Person = recoverFromJson(corrupted)
        println(p)
    }

    demo()
}