# jsonRecoveryPoC

A Kotlin JVM proof-of-concept library for **fault-tolerant JSON deserialization**.

Instead of throwing when JSON doesn't match the target data class, it **salvages as much valid data as possible** and falls back gracefully.

## Features

- **Type coercion** — `"27"` (string) → `27` (Int), `123` (number) → `"123"` (String)
- **Default value fallback** — missing or failed fields fall back to the data class's default values, or `null` for nullable params
- **Per-item collection salvage** — mixed-type arrays like `["a", 2, null, {}]` for a `Set<String>` keep only what parses
- **Lenient enum matching** — case-insensitive, separator-tolerant, and by ordinal
- **Flexible field names** — resolved via `@SerializedName` (including `alternate`), Kotlin property names, and case-insensitive fallback
- **Recursive recovery** — nested data classes are recovered recursively, with an empty-object `{}` as a last resort for required nested params
- **Coerced map keys** — non-string keys (Int, Boolean, etc.) are coerced where sensible

## Usage

```kotlin
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

val corrupted = """
{
  "full_name": 123,
  "age": "27",
  "tags": ["a", 2, null, {}],
  "address": {"street": 7},
  "friends": [{ "name": "Sam" }, 42, {"name": true}]
}
""".trimIndent()

val p: Person = recoverFromJson(corrupted)
println(p)
```

Output:

```
Person(name=123, age=27, tags=[a], address=Address(street=unknown, city=unknown), friends=[Person(name=Sam, age=0, tags=[], address=Address(street=unknown, city=unknown), friends=[])])
```

## API

- `recoverFromJson<T>(json: String): T` — reified entry point, parses a JSON string and recovers an instance of `T`
- `recoverFromElement(el: JsonElement?, kClass: KClass<T>): T` — recovers from an already-parsed `JsonElement`

## Tech stack

- Kotlin 2.2.0 (JVM toolchain 24)
- [Gson](https://github.com/google/gson) for parsing and as a fallback converter
- [kotlin-reflect](https://kotlinlang.org/api/kotlin.reflect/) for inspecting data class constructors and annotations

## Building

```bash
./gradlew build
```

To try the demo in `src/main/kotlin/Main.kt`, run the `main()` function from your IDE.

## Status

This is a **proof of concept** — not production-ready. Error reporting is minimal, and recovery behavior may change.
