@file:OptIn(ExperimentalStdlibApi::class)

package aquadx

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File
import java.lang.reflect.Field
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*
import java.util.concurrent.locks.Lock
import kotlin.reflect.KCallable
import kotlin.reflect.KClass
import kotlin.reflect.KMutableProperty1
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.jvmErasure

typealias Var<T, V> = KMutableProperty1<T, V>
typealias Str = String
typealias Bool = Boolean
typealias JavaSerializable = java.io.Serializable

typealias JDict = Map<String, Any?>
typealias MutJDict = MutableMap<String, Any?>

@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY, AnnotationTarget.PROPERTY_GETTER)
@Retention(AnnotationRetention.RUNTIME)
annotation class Doc(
    val desc: String,
    val ret: String = ""
)

@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class SettingField(
    val game: String
)

// Reflection
@Suppress("UNCHECKED_CAST")
fun <T : Any> KClass<T>.vars() = memberProperties.mapNotNull { it as? Var<T, Any> }
fun <T : Any> KClass<T>.varsMap() = vars().associateBy { it.name }
fun <T : Any> KClass<T>.getters() = java.methods.filter { it.name.startsWith("get") }
fun <T : Any> KClass<T>.gettersMap() = getters().associateBy { it.name.removePrefix("get").firstCharLower() }
infix fun KCallable<*>.returns(type: KClass<*>) = returnType.jvmErasure.isSubclassOf(type)
@Suppress("UNCHECKED_CAST")
fun <C, T: Any> Var<C, T>.setCast(obj: C, value: String) = set(obj, when (returnType.classifier) {
    String::class -> value
    Int::class -> value.toInt()
    Boolean::class -> value.toBoolean()
    else -> 400 - "Invalid field type $returnType"
} as T)
inline fun <reified T: Any> Field.gets(obj: Any): T? = get(obj)?.let { it as T }

// HTTP
class ApiException(val code: Int, message: Str) : RuntimeException(message) {
    companion object {
        val log = LoggerFactory.getLogger(ApiException::class.java)
    }
}
operator fun Int.minus(message: String): Nothing {
    ApiException.log.info("> Error $this: $message")
    throw ApiException(this, message)
}
fun <R> parsing(block: () -> R) = try { block() }
catch (e: ApiException) { throw e }
catch (e: Exception) { 400 - e.message.toString() }

// Email validation
// https://www.baeldung.com/java-email-validation-regex
val emailRegex = "^(?=.{1,64}@)[\\p{L}0-9_-]+(\\.[\\p{L}0-9_-]+)*@[^-][\\p{L}0-9-]+(\\.[\\p{L}0-9-]+)*(\\.[\\p{L}]{2,})$".toRegex()
fun Str.isValidEmail(): Bool = emailRegex.matches(this)

// Class resource
object Ext { val log = logger() }
fun res(name: Str) = Ext::class.java.getResourceAsStream(name)
fun resStr(name: Str) = res(name)?.reader()?.readText()

// Date and time
val JST_ZONE = ZoneId.of("Asia/Tokyo")
fun jstNow() = LocalDateTime.now(JST_ZONE)
fun millis() = System.currentTimeMillis()
val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd")
fun LocalDate.isoDate() = format(DATE_FORMAT)
fun String.isoDate() = DATE_FORMAT.parse(this, LocalDate::from)
fun Date.utc() = toInstant().atZone(java.time.ZoneOffset.UTC).toLocalDate()
fun LocalDate.toDate() = Date(atStartOfDay().toInstant(java.time.ZoneOffset.UTC).toEpochMilli())
fun LocalDateTime.isoDateTime() = format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
fun String.isoDateTime() = LocalDateTime.parse(this, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
val URL_SAFE_DT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
fun LocalDateTime.urlSafeStr() = format(URL_SAFE_DT)
val DATE_2018 = LocalDateTime.parse("2018-01-01T00:00:00")

val ALT_DATETIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
fun Str.asDateTime() = try { LocalDateTime.parse(this, DateTimeFormatter.ISO_LOCAL_DATE_TIME) }
catch (e: Exception) { try { LocalDateTime.parse(this, ALT_DATETIME_FORMAT) }
catch (e: Exception) { null } }

val Calendar.year get() = get(Calendar.YEAR)
val Calendar.month get() = get(Calendar.MONTH) + 1
val Calendar.day get() = get(Calendar.DAY_OF_MONTH)
fun cal() = Calendar.getInstance()
fun Date.cal() = Calendar.getInstance().apply { time = this@cal }
operator fun Calendar.invoke(field: Int) = get(field)
val Date.sec get() = time / 1000

// Encodings
fun Long.toHex(len: Int = 16): Str = "0x${this.toString(len).padStart(len, '0').uppercase()}"
fun Map<String, Any>.toUrl() = entries.joinToString("&") { (k, v) -> "$k=$v" }
fun String.firstCharLower() = replaceFirstChar { it.lowercase() }

fun Any.long() = when (this) {
    is Long -> this
    is Boolean -> if (this) 1L else 0
    is Number -> toLong()
    is String -> toLong()
    else -> 400 - "Invalid number: $this"
}
fun Any.uint32() = long() and 0xFFFFFFFF
fun Any.int() = long().toInt()
val Any.long get() = long()
val Any.int get() = int()
val Any.double get() = when (this) {
    is Boolean -> if (this) 1.0 else 0.0
    is Number -> toDouble()
    is String -> toDouble()
    else -> 400 - "Invalid number: $this"
}
operator fun Bool.unaryPlus() = if (this) 1 else 0
val Any?.truthy get() = when (this) {
    null -> false
    is Bool -> this
    is Float -> this != 0f && !isNaN()
    is Double -> this != 0.0 && !isNaN()
    is Number -> this != 0
    is String -> this.isNotBlank()
    is Collection<*> -> isNotEmpty()
    is Map<*, *> -> isNotEmpty()
    else -> true
}
val Any?.str get() = toString()

// Collections
fun <T> ls(vararg args: T) = args.toList()
inline fun <reified T> arr(vararg args: T) = arrayOf(*args)
operator fun <K, V> Map<K, V>.plus(map: Map<K, V>) =
    (if (this is MutableMap) this else mut).apply { putAll(map) }
operator fun <K, V> MutableMap<K, V>.plusAssign(map: Map<K, V>) { putAll(map) }
fun <K, V: Any> Map<K, V?>.vNotNull(): Map<K, V> = filterValues { it != null }.mapValues { it.value!! }
fun <T> MutableList<T>.popAll(list: List<T>) = list.also { removeAll(it) }
fun <T> MutableList<T>.popAll(vararg items: T) = popAll(items.toList())
inline fun <T> Iterable<T>.mapApply(block: T.() -> Unit) = map { it.apply(block) }
inline fun <T> Iterable<T>.mapApplyI(block: T.(Int) -> Unit) = mapIndexed { i, e -> e.apply { block(i) } }
@Suppress("UNCHECKED_CAST")
fun <K, V: Any> Map<K, V?>.recursiveNotNull(): Map<K, V> = mapNotNull { (k, v) ->
    k to if (v is Map<*, *>) (v as Map<Any?, Any?>).recursiveNotNull() else v
}.toMap() as Map<K, V>

val <T> List<T>.mut get() = toMutableList()
val <K, V> Map<K, V>.mut get() = toMutableMap()
val <T> Set<T>.mut get() = toMutableSet()

fun <T> List<T>.unique(fn: (T) -> Any) = distinctBy(fn).ifEmpty { null }

// Optionals
operator fun <T> Optional<T>.invoke(): T? = orElse(null)
fun <T> Optional<T>.expect(message: Str = "Value is not present") = orElseGet { (400 - message) }

// Strings
operator fun Str.get(range: IntRange) = substring(range.first, (range.last + 1).coerceAtMost(length))
operator fun Str.get(start: Int, end: Int) = substring(start, end.coerceAtMost(length))
fun Str.center(width: Int, padChar: Char = ' ') = padStart((length + width) / 2, padChar).padEnd(width, padChar)
fun Str.splitLines() = replace("\r\n", "\n").split('\n')
fun Str.hash(algo: Str) = MessageDigest.getInstance(algo).digest(toByteArray(StandardCharsets.UTF_8))
fun Str.md5() = hash("MD5")
fun Str.fromChusanUsername() = String(this.toByteArray(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8)
fun Str.truncate(len: Int) = if (this.length > len) this.take(len) + "..." else this
val Str.some get() = ifBlank { null }
val ByteArray.hexStr get() = toHexString()

// Coroutine
suspend fun <T> async(block: suspend kotlinx.coroutines.CoroutineScope.() -> T): T = withContext(Dispatchers.IO) { block() }
fun <T> thread(block: () -> T) = Thread { block() }.apply { start() }
fun <T> Lock.maybeLock(block: () -> T) = if (tryLock()) try { block() } finally { unlock() } else null

// Paths
fun path(part1: Str, vararg parts: Str) = Path.of(part1, *parts)
fun Str.path() = Path.of(this)
operator fun Path.div(part: Str) = resolve(part)
operator fun File.div(fileName: Str) = File(this, fileName)
fun Str.ensureEndingSlash() = if (endsWith('/')) this else "$this/"

fun <T: Any> T.logger() = LoggerFactory.getLogger(this::class.java)

// I hate this ;-; (list destructuring)
operator fun <E> List<E>.component6(): E = get(5)
operator fun <E> List<E>.component7(): E = get(6)
operator fun <E> List<E>.component8(): E = get(7)
operator fun <E> List<E>.component9(): E = get(8)
operator fun <E> List<E>.component10(): E = get(9)
operator fun <E> List<E>.component11(): E = get(10)
operator fun <E> List<E>.component12(): E = get(11)
operator fun <E> List<E>.component13(): E = get(12)

inline operator fun <reified E> List<Any?>.invoke(i: Int) = get(i) as E
val empty = emptyList<Any>()

val <F> Pair<F, *>.l get() = component1()
val <S> Pair<*, S>.r get() = component2()

// Database
fun List<List<Any?>>.numCsv(vararg head: Str) = head.joinToString(",") + "\n" +
    joinToString("\n") { it.joinToString(",") }
