/*
 *
 * Part of codes was taken from Mirai Native
 *
 * Copyright (C) 2020 iTX Technologies
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 * @author PeratX
 * @website https://github.com/iTXTech/mirai-native
 *
 */
package com.github.yyuueexxiinngg.onebot.util

import com.github.yyuueexxiinngg.onebot.PluginBase
import com.github.yyuueexxiinngg.onebot.PluginBase.db
import com.github.yyuueexxiinngg.onebot.PluginBase.saveImageAsync
import com.github.yyuueexxiinngg.onebot.PluginBase.saveRecordAsync
import com.github.yyuueexxiinngg.onebot.PluginSettings
import com.github.yyuueexxiinngg.onebot.logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import net.mamoe.mirai.Bot
import net.mamoe.mirai.contact.Contact
import net.mamoe.mirai.contact.Group
import net.mamoe.mirai.message.data.*
import net.mamoe.mirai.message.data.Image.Key.queryUrl
import net.mamoe.mirai.message.data.MessageChain.Companion.deserializeJsonToMessageChain
import net.mamoe.mirai.message.data.MessageSource.Key.quote
import net.mamoe.mirai.utils.ExternalResource.Companion.toExternalResource
import net.mamoe.mirai.utils.ExternalResource.Companion.uploadAsImage
import net.mamoe.mirai.utils.MiraiExperimentalApi
import net.mamoe.mirai.utils.MiraiInternalApi
import java.io.File
import java.net.URL
import java.util.*

suspend fun messageToMiraiMessageChains(
    bot: Bot,
    contact: Contact?,
    message: Any?,
    raw: Boolean = false
): MessageChain? {
    when (message) {
        is String -> {
            return if (raw) {
                PlainText(message).toMessageChain()
            } else {
                codeToChain(bot, message, contact)
            }
        }
        is JsonArray -> {
            var messageChain = buildMessageChain { }
            for (msg in message) {
                try {
                    val data = msg.jsonObject["data"]
                    when (msg.jsonObject["type"]?.jsonPrimitive?.content) {
                        "text" -> messageChain += PlainText(data!!.jsonObject["text"]!!.jsonPrimitive.content)
                        else -> messageChain += textToMessageInternal(bot, contact, msg)
                    }
                } catch (e: NullPointerException) {
                    logger.warning("Got null when parsing CQ message array")
                    continue
                }
            }
            return messageChain
        }
        is JsonObject -> {
            return try {
                val data = message.jsonObject["data"]
                when (message.jsonObject["type"]?.jsonPrimitive?.content) {
                    "text" -> PlainText(data!!.jsonObject["text"]!!.jsonPrimitive.content).toMessageChain()
                    else -> textToMessageInternal(bot, contact, message).toMessageChain()
                }
            } catch (e: NullPointerException) {
                logger.warning("Got null when parsing CQ message object")
                null
            }
        }
        is JsonPrimitive -> {
            return if (raw) {
                PlainText(message.content).toMessageChain()
            } else {
                codeToChain(bot, message.content, contact)
            }
        }
        else -> {
            logger.warning("Cannot determine type of " + message.toString())
            return null
        }
    }
}


private suspend fun textToMessageInternal(bot: Bot, contact: Contact?, message: Any): Message {
    when (message) {
        is String -> {
            if (message.startsWith("[CQ:") && message.endsWith("]")) {
                val parts = message.substring(4, message.length - 1).split(",", limit = 2)

                val args: HashMap<String, String> = if (parts.size == 2) {
                    parts[1].toMap()
                } else {
                    HashMap()
                }
                return convertToMiraiMessage(bot, contact, parts[0], args)
            }
            return PlainText(message.unescape())
        }
        is JsonObject -> {
            val type = message.jsonObject["type"]!!.jsonPrimitive.content
            val data = message.jsonObject["data"] ?: return MSG_EMPTY
            val args = data.jsonObject.keys.associateWith { data.jsonObject[it]!!.jsonPrimitive.content }
            return convertToMiraiMessage(bot, contact, type, args)
        }
        else -> return MSG_EMPTY
    }
}

@OptIn(MiraiExperimentalApi::class)
private suspend fun convertToMiraiMessage(
    bot: Bot,
    contact: Contact?,
    type: String,
    args: Map<String, String>
): Message {
    when (type) {
        "at" -> {
            if (args["qq"] == "all") {
                return AtAll
            } else {
                return if (contact !is Group) {
                    logger.debug("不能在私聊中发送 At。")
                    MSG_EMPTY
                } else {
                    val member = contact[args["qq"]!!.toLong()]
                    if (member == null) {
                        logger.debug("无法找到群员：${args["qq"]}")
                        MSG_EMPTY
                    } else {
                        At(member)
                    }
                }
            }
        }
        "face" -> {
            return Face(args["id"]!!.toInt())
        }
        "emoji" -> {
            return PlainText(String(Character.toChars(args["id"]!!.toInt())))
        }
        "image" -> {
            return tryResolveMedia("image", contact, args)
        }
        "share" -> {
            return RichMessageHelper.share(
                args["url"]!!,
                args["title"],
                args["content"],
                args["image"]
            )
        }
        "record" -> {
            return tryResolveMedia("record", contact, args)
        }
        "contact" -> {
            return if (args["type"] == "qq") {
                RichMessageHelper.contactQQ(bot, args["id"]!!.toLong())
            } else {
                RichMessageHelper.contactGroup(bot, args["id"]!!.toLong())
            }
        }
        "music" -> {
            return when (args["type"]) {
                "qq" -> QQMusic.send(args["id"]!!)
                "163" -> NeteaseMusic.send(args["id"]!!)
                "custom" -> Music.custom(
                    args["url"]!!,
                    args["audio"]!!,
                    args["title"]!!,
                    args["content"],
                    args["image"]
                )
                else -> throw IllegalArgumentException("Custom music share not supported anymore")
            }
        }
        "shake" -> {
            return PokeMessage.ChuoYiChuo
        }
        "poke" -> {
            PokeMessage.values.forEach {
                if (it.pokeType == args["type"]!!.toInt() && it.id == args["id"]!!.toInt()) {
                    return it
                }
            }
            return MSG_EMPTY
        }
        // Could be changed at anytime.
        "nudge" -> {
            val target = args["qq"] ?: error("Nudge target `qq` must not ne null.")
            if (contact is Group) {
                contact.members[target.toLong()]?.nudge()?.sendTo(contact)
            } else {
                contact?.let { bot.friends[target.toLong()]?.nudge()?.sendTo(it) }
            }
            return MSG_EMPTY
        }
        "xml" -> {
            return xmlMessage(args["data"]!!)
        }
        "json" -> {
            return if (args["data"]!!.contains("\"app\":")) {
                LightApp(args["data"]!!)
            } else {
                jsonMessage(args["data"]!!)
            }
        }
        "reply" -> {
            if (PluginSettings.db.enable) {
                db?.apply {
                    return String(
                        get(
                            args["id"]!!.toInt().toByteArray()
                        )
                    ).deserializeJsonToMessageChain().sourceOrNull?.quote() ?: MSG_EMPTY
                }
            }
        }
        else -> {
            logger.debug("不支持的 CQ码：${type}")
        }
    }
    return MSG_EMPTY
}


private val MSG_EMPTY = PlainText("")

private fun String.escape(): String {
    return replace("&", "&amp;")
        .replace("[", "&#91;")
        .replace("]", "&#93;")
        .replace(",", "&#44;")
}

private fun String.unescape(): String {
    return replace("&amp;", "&")
        .replace("&#91;", "[")
        .replace("&#93;", "]")
        .replace("&#44;", ",")
}

private fun String.toMap(): HashMap<String, String> {
    val map = HashMap<String, String>()
    split(",").forEach {
        val parts = it.split("=", limit = 2)
        map[parts[0].trim()] = parts[1].unescape()
    }
    return map
}

@OptIn(MiraiExperimentalApi::class, MiraiInternalApi::class)
suspend fun Message.toCQString(): String {
    return when (this) {
        is PlainText -> content.escape()
        is At -> "[CQ:at,qq=$target]"
        is Face -> "[CQ:face,id=$id]"
        is VipFace -> "[CQ:vipface,id=${kind.id},name=${kind.name},count=${count}]"
        is PokeMessage -> "[CQ:poke,id=${id},type=${pokeType},name=${name}]"
        is AtAll -> "[CQ:at,qq=all]"
        is Image -> "[CQ:image,file=${imageId},url=${queryUrl().escape()}]"
        is FlashImage -> "[CQ:image,file=${image.imageId},url=${image.queryUrl().escape()},type=flash]"
        is ServiceMessage -> with(content) {
            when {
                contains("xml version") -> "[CQ:xml,data=${content.escape()}]"
                else -> "[CQ:json,data=${content.escape()}]"
            }
        }
        is LightApp -> "[CQ:json,data=${content.escape()}]"
        is MessageSource -> ""
        is QuoteReply -> "[CQ:reply,id=${source.internalIds.toMessageId(source.botId, source.fromId)}]"
        is OnlineAudio -> "[CQ:record,url=${urlForDownload.escape()},file=${fileMd5.toUHexString("")}]"
        else -> "此处消息的转义尚未被插件支持"
    }
}

suspend fun codeToChain(bot: Bot, message: String, contact: Contact?): MessageChain {
    return buildMessageChain {
        if (message.contains("[CQ:")) {
            var interpreting = false
            val sb = StringBuilder()
            var index = 0
            message.forEach { c: Char ->
                if (c == '[') {
                    if (interpreting) {
                        logger.error("CQ消息解析失败：$message，索引：$index")
                        return@forEach
                    } else {
                        interpreting = true
                        if (sb.isNotEmpty()) {
                            val lastMsg = sb.toString()
                            sb.delete(0, sb.length)
                            +textToMessageInternal(bot, contact, lastMsg)
                        }
                        sb.append(c)
                    }
                } else if (c == ']') {
                    if (!interpreting) {
                        logger.error("CQ消息解析失败：$message，索引：$index")
                        return@forEach
                    } else {
                        interpreting = false
                        sb.append(c)
                        if (sb.isNotEmpty()) {
                            val lastMsg = sb.toString()
                            sb.delete(0, sb.length)
                            +textToMessageInternal(bot, contact, lastMsg)
                        }
                    }
                } else {
                    sb.append(c)
                }
                index++
            }
            if (sb.isNotEmpty()) {
                +textToMessageInternal(bot, contact, sb.toString())
            }
        } else {
            +PlainText(message.unescape())
        }
    }
}

fun getDataFile(type: String, name: String): File? {
    arrayOf(
        File(PluginBase.dataFolder, type).absolutePath + File.separatorChar,
        "data" + File.separatorChar + type + File.separatorChar,
        System.getProperty("java.library.path")
            .substringBefore(";") + File.separatorChar + "data" + File.separatorChar + type + File.separatorChar,
        ""
    ).forEach {
        val f = File(it + name).absoluteFile
        if (f.exists()) {
            return f
        }
    }
    return null
}

suspend fun tryResolveMedia(type: String, contact: Contact?, args: Map<String, String>): Message {
    var media: Message? = null
    var mediaBytes: ByteArray? = null
    var mediaUrl: String? = null

    withContext(Dispatchers.IO) {
        if (args.containsKey("file")) {
            with(args["file"]!!) {
                when {
                    startsWith("base64://") -> {
                        mediaBytes = Base64.getDecoder().decode(args["file"]!!.replace("base64://", ""))
                    }
                    startsWith("http") -> {
                        mediaUrl = args["file"]
                    }
                    else -> {
                        val filePath = args["file"]!!
                        if (filePath.startsWith("file:///")) {
                            var fileUri = URL(args["file"]).toURI()
                            if (fileUri.authority != null && fileUri.authority.isNotEmpty()) {
                                fileUri = URL("file://" + args["file"]!!.substring("file:".length)).toURI()
                            }
                            val file = File(fileUri).absoluteFile
                            if (file.exists() && file.canRead()) {
                                mediaBytes = file.readBytes()
                            }
                        } else {
                            if (type == "image") {
                                media = tryResolveCachedImage(filePath, contact)
                            } else if (type == "record") {
                                media = tryResolveCachedRecord(filePath, contact)
                            }
                            if (media == null) {
                                val file = getDataFile(type, filePath)
                                if (file != null && file.canRead()) {
                                    mediaBytes = file.readBytes()
                                }
                            }
                        }
                        if (mediaBytes == null) {
                            if (args.containsKey("url")) {
                                mediaUrl = args["url"]!!
                            }
                        }
                    }
                }
            }
        } else if (args.containsKey("url")) {
            mediaUrl = args["url"]!!
        }

        if (mediaBytes == null && mediaUrl != null) {
            var useCache = true
            if (args.containsKey("cache")) {
                try {
                    useCache = args["cache"]?.toIntOrNull() != 0
                } catch (e: Exception) {
                    logger.debug(e.message)
                }
            }

            val timeoutSecond = (if (args.containsKey("timeout")) args["timeout"]?.toIntOrNull() else null) ?: 0
            val useProxy = (if (args.containsKey("proxy")) args["proxy"]?.toIntOrNull() == 1 else null) ?: false
            val urlHash = md5(mediaUrl!!).toUHexString("")

            when (type) {
                "image" -> {
                    if (useCache) {
                        media = tryResolveCachedImage(urlHash, contact)
                    }

                    if (media == null || !useCache) {
                        mediaBytes = HttpClient.getBytes(mediaUrl!!, timeoutSecond * 1000L, useProxy)
                        media = mediaBytes?.toExternalResource()?.use { it.uploadAsImage(contact!!) }
                        if (useCache) {
                            var imageType = "unknown"
                            val imageMD5 = mediaBytes?.let {
                                imageType = getImageType(it)
                                md5(it)
                            }?.toUHexString("")
                            if (imageMD5 != null) {
                                val imgContent = constructCacheImageMeta(
                                    imageMD5,
                                    mediaBytes?.size,
                                    (media as Image?)?.queryUrl(),
                                    imageType
                                )
                                logger.info("此链接图片将缓存为$urlHash.cqimg")
                                saveImageAsync("$urlHash.cqimg", imgContent).start()
                            }
                        }
                    }
                }
                "record" -> {
                    if (useCache) {
                        media = tryResolveCachedRecord(urlHash, contact)
                    }
                    if (media == null || !useCache) {
                        mediaBytes = HttpClient.getBytes(mediaUrl!!, timeoutSecond * 1000L, useProxy)
                        media = mediaBytes?.toExternalResource()?.use { res ->
                            contact?.let { (it as Group).uploadAudio(res) }
                        }

                        if (useCache && mediaBytes != null) {
                            saveRecordAsync("$urlHash.cqrecord", mediaBytes!!).start()
                        }
                    }
                }
            }
        }
    }

    if (media == null && mediaBytes == null) {
        return PlainText("插件无法获取到媒体" + if (mediaUrl != null) ", 媒体链接: $mediaUrl" else "")
    }

    when (type) {
        "image" -> {
            val flash = args.containsKey("type") && args["type"] == "flash"
            if (media == null) {
                media = withContext(Dispatchers.IO) {
                    mediaBytes!!.toExternalResource().use { res ->
                        contact!!.uploadImage(res)
                    }
                }
            }

            return if (flash) {
                (media as Image).flash()
            } else {
                media as Image
            }
        }
        "record" -> {
            if (media == null) {
                media =
                    withContext(Dispatchers.IO) {
                        mediaBytes!!.toExternalResource().use { (contact!! as Group).uploadAudio(it) }
                    }
            }
            return media as Audio
        }
    }
    return PlainText("插件无法获取到媒体" + if (mediaUrl != null) ", 媒体链接: $mediaUrl" else "")
}