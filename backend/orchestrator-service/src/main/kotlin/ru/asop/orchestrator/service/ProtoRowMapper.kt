package ru.asop.orchestrator.service

import com.fasterxml.jackson.databind.JsonNode
import com.google.protobuf.Descriptors
import com.google.protobuf.Message
import org.springframework.stereotype.Component

/**
 * Маппит JSON-строку (ответ мастер-сервиса) в Protobuf Row-сообщение
 * сгенерированного класса (ru.asop.proto.v1.XxxRow) через descriptor API.
 * Поле proto (snake_case) сопоставляется с JSON-ключом (camelCase).
 */
@Component
class ProtoRowMapper {

    fun buildRowMessage(table: String, node: JsonNode): Message {
        val clsName = "ru.asop.proto.v1.${camel(table)}Row"
        val builderClass = Class.forName(clsName)
        val builder = builderClass.getMethod("newBuilder").invoke(null) as Message.Builder

        for (field in builder.descriptorForType.fields) {
            val value = node.get(snakeToCamel(field.name)) ?: node.get(field.name) ?: continue
            if (value.isNull || value.isMissingNode) continue
            builder.setField(field, convert(field, value))
        }
        return builder.build()
    }

    private fun convert(field: Descriptors.FieldDescriptor, value: JsonNode): Any {
        return when (field.javaType) {
            Descriptors.FieldDescriptor.JavaType.STRING -> value.asText()
            Descriptors.FieldDescriptor.JavaType.INT -> value.asInt()
            Descriptors.FieldDescriptor.JavaType.LONG -> value.asLong()
            Descriptors.FieldDescriptor.JavaType.BOOLEAN -> value.asBoolean()
            Descriptors.FieldDescriptor.JavaType.DOUBLE -> value.asDouble()
            Descriptors.FieldDescriptor.JavaType.FLOAT -> value.asDouble().toFloat()
            Descriptors.FieldDescriptor.JavaType.BYTE_STRING -> value.binaryValue()?.let { com.google.protobuf.ByteString.copyFrom(it) } ?: com.google.protobuf.ByteString.EMPTY
            Descriptors.FieldDescriptor.JavaType.ENUM -> throw IllegalArgumentException("enum fields not supported: ${field.name}")
            Descriptors.FieldDescriptor.JavaType.MESSAGE -> throw IllegalArgumentException("message fields not supported: ${field.name}")
        }
    }

    private fun camel(table: String): String {
        val parts = table.split("_").drop(1) // strip "asop"
        return parts.joinToString("") { it.capitalize() }
    }

    private fun snakeToCamel(name: String): String {
        if (!name.contains('_')) return name
        val parts = name.split("_")
        return parts.first() + parts.drop(1).joinToString("") { it.capitalize() }
    }
}
