package com.github.kr328.clash.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Решение «обновляться ли и чем» — чистая логика без Android SDK и сети.
 *
 * Всё, что снаружи (два HTTP-запроса, DownloadManager, уведомление, вызов
 * установщика), живёт в [UpdateChecker]; здесь только разбор ответов и выбор.
 */

/** Канал обновлений. Задаётся флейвором сборки, в приложении не переключается. */
enum class UpdateChannel(val id: String) {
    META("meta"),
    ALPHA("alpha");

    companion object {
        /** Плавающий пререлиз alpha-канала: тег постоянный, релиз пересоздаётся. */
        const val ALPHA_TAG = "Prerelease-alpha"

        fun of(id: String): UpdateChannel? = values().firstOrNull { it.id == id }
    }
}

/** Имя файла метаданных сборки, который Gradle кладёт в каждый релиз. */
const val BUILD_METADATA_ASSET = "output-metadata.json"

/** Тег стабильного релиза meta-канала. */
private val STABLE_TAG = Regex("""^v\d+\.\d+\.\d+$""")

sealed class UpdateDecision {
    /** В канале есть сборка свежее установленной. */
    data class Available(
        val versionName: String,
        val versionCode: Long,
        val downloadUrl: String,
        val changelog: String,
        val releaseUrl: String,
    ) : UpdateDecision()

    /** Установлена самая свежая сборка канала. */
    object UpToDate : UpdateDecision()

    /** Обновиться нельзя, и это стоит показать пользователю. */
    data class Failed(val reason: Reason) : UpdateDecision()

    enum class Reason {
        /** Ответ релиза или метаданные сборки не разобрать. */
        MALFORMED_RELEASE,

        /** Релиз не относится к каналу этой сборки. */
        WRONG_CHANNEL,

        /** Сборка в релизе предназначена другому приложению. */
        FOREIGN_APPLICATION,

        /** В релизе нет файла ни под архитектуру устройства, ни универсального. */
        NO_SUITABLE_ASSET,
    }
}

/**
 * Ссылка на ассет релиза по имени файла. Нужна и для метаданных сборки, которые
 * скачиваются вторым запросом, и для самого APK.
 */
fun findAssetUrl(releaseJson: String, assetName: String): String? = runCatching {
    Json.parseToJsonElement(releaseJson).jsonObject.assetUrl(assetName)
}.getOrNull()

/**
 * Решает, предлагать ли обновление.
 *
 * Единственный источник ответов «свежее ли» и «какой файл качать» — метаданные
 * сборки: разбор semver из тега к alpha-каналу неприменим (тег постоянный) и
 * дал бы два разных механизма сравнения в одном классе.
 */
fun decideUpdate(
    channel: UpdateChannel,
    installedVersionCode: Long,
    installedApplicationId: String,
    supportedAbis: List<String>,
    releaseJson: String,
    metadataJson: String,
): UpdateDecision = runCatching {
    val release = Json.parseToJsonElement(releaseJson).jsonObject
    val metadata = Json.parseToJsonElement(metadataJson).jsonObject

    val tag = release.string("tag_name") ?: return UpdateDecision.Failed(
        UpdateDecision.Reason.MALFORMED_RELEASE
    )
    val isPrerelease = release["prerelease"]?.jsonPrimitive?.booleanOrNull ?: false

    // Барьеров у meta-канала два: GitHub сам исключает пререлизы из /latest, а
    // клиент поверх этого проверяет и флаг, и форму тега. Цена ошибки —
    // несовместимый APK у пользователя, поэтому дублирование намеренное.
    val channelMatches = when (channel) {
        UpdateChannel.META -> !isPrerelease && STABLE_TAG.matches(tag)
        UpdateChannel.ALPHA -> tag == UpdateChannel.ALPHA_TAG
    }
    if (!channelMatches) {
        return UpdateDecision.Failed(UpdateDecision.Reason.WRONG_CHANNEL)
    }

    // Метаданные знают, какому приложению предназначена сборка. У meta и alpha
    // разные applicationId — это разные приложения, чужая сборка поверх не
    // встанет, и качать её незачем.
    val metadataApplicationId = metadata.string("applicationId")
        ?: return UpdateDecision.Failed(UpdateDecision.Reason.MALFORMED_RELEASE)
    if (metadataApplicationId != installedApplicationId) {
        return UpdateDecision.Failed(UpdateDecision.Reason.FOREIGN_APPLICATION)
    }

    val elements = (metadata["elements"] as? JsonArray)?.map { it.jsonObject }.orEmpty()
    val anyElement = elements.firstOrNull()
        ?: return UpdateDecision.Failed(UpdateDecision.Reason.MALFORMED_RELEASE)

    // Код версии одинаков у всех записей одной сборки, поэтому свежесть
    // проверяется до выбора файла: устройству с редкой архитектурой незачем
    // получать ошибку, когда обновляться и так не нужно.
    val releaseVersionCode = anyElement["versionCode"]?.jsonPrimitive?.longOrNull
        ?: return UpdateDecision.Failed(UpdateDecision.Reason.MALFORMED_RELEASE)
    if (releaseVersionCode <= installedVersionCode) {
        return UpdateDecision.UpToDate
    }

    // Файл под архитектуру устройства — первая поддерживаемая ABI; если записи
    // под неё нет, берётся универсальная сборка. Угадывания имени по маске
    // здесь нет: имя приходит из метаданных как есть.
    val preferredAbi = supportedAbis.firstOrNull()
    val element = elements.firstOrNull { it.abiFilter() == preferredAbi }
        ?: elements.firstOrNull { it.string("type") == "UNIVERSAL" }
        ?: return UpdateDecision.Failed(UpdateDecision.Reason.NO_SUITABLE_ASSET)

    val outputFile = element.string("outputFile")
        ?: return UpdateDecision.Failed(UpdateDecision.Reason.MALFORMED_RELEASE)
    val downloadUrl = release.assetUrl(outputFile)
        ?: return UpdateDecision.Failed(UpdateDecision.Reason.NO_SUITABLE_ASSET)

    UpdateDecision.Available(
        versionName = element.string("versionName") ?: tag,
        versionCode = releaseVersionCode,
        downloadUrl = downloadUrl,
        changelog = release.string("body").orEmpty().trim(),
        releaseUrl = release.string("html_url").orEmpty(),
    )
}.getOrElse { UpdateDecision.Failed(UpdateDecision.Reason.MALFORMED_RELEASE) }

private fun JsonObject.string(key: String): String? =
    this[key]?.jsonPrimitive?.contentOrNull

private fun JsonObject.assetUrl(name: String): String? =
    (this["assets"] as? JsonArray)
        ?.map { it.jsonObject }
        ?.firstOrNull { it.string("name") == name }
        ?.string("browser_download_url")

/** Архитектура, под которую собрана запись метаданных; null — универсальная. */
private fun JsonObject.abiFilter(): String? =
    (this["filters"] as? JsonArray)
        ?.map { it.jsonObject }
        ?.firstOrNull { it.string("filterType") == "ABI" }
        ?.string("value")
