package top.ntutn.updater

import kotlinx.serialization.json.*
import kotlin.time.Instant

internal class InvalidUpdateResponse : Exception()
internal class UnsafeUpdateUrl : Exception()
private fun invalid(): Nothing = throw InvalidUpdateResponse()
private fun JsonObject.text(key: String, required: Boolean = false): String? {
    val value = this[key] ?: return if (required) invalid() else null
    if (value == JsonNull) return if (required) invalid() else null
    val primitive = value as? JsonPrimitive ?: invalid()
    if (!primitive.isString) invalid()
    return primitive.content.takeIf { it.isNotBlank() } ?: if (required) invalid() else null
}
private fun JsonObject.obj(key: String): JsonObject = this[key] as? JsonObject ?: invalid()
private fun decimal(raw: String): Long = raw.takeIf { it.matches(Regex("[0-9]+")) }?.toLongOrNull() ?: invalid()

internal fun parseRelease(root: JsonObject, config: UpdaterConfig): UpdateRelease {
    fun validateTarget(value: JsonObject) {
        if (value.text("software", true) != config.software || value.text("os", true) != config.target.os.name ||
            value.text("arch", true) != config.target.arch.name || value.text("packageType", true) != config.packageType) invalid()
    }
    validateTarget(root.obj("query"))
    val items = root["items"] as? JsonArray ?: invalid()
    if (items.size != 1) invalid()
    val item = items.single() as? JsonObject ?: invalid()
    validateTarget(item)
    val latest = item.obj("latest")
    if (latest.text("packageType", true) != config.packageType) invalid()
    val code = decimal(latest.text("versionCode", true)!!).takeIf { it > 0 } ?: invalid()
    val version = UpdateVersion(code, latest.text("versionName", true)!!)
    val downloadUrl = latest.text("downloadUrl", true)!!
    if (!isAllowedWebUrl(downloadUrl, config.allowInsecureHttp)) throw UnsafeUpdateUrl()
    return UpdateRelease(
        config.software, config.target, config.packageType, version, downloadUrl,
        latest.text("softwareDisplayName") ?: config.software,
        latest.text("distributionTitle") ?: "", latest.text("packageName") ?: config.packageType,
        latest.text("distributionId"), latest.text("packageId"),
        latest.text("publishedAt")?.let { runCatching { Instant.parse(it) }.getOrNull() },
        latest["packageSize"]?.takeIf { it != JsonNull }?.let { decimal(latest.text("packageSize", true)!!) },
        latest.text("descriptionPlainText"), latest.text("descriptionMarkdown"),
    )
}
