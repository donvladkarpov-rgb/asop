package ru.asop.terminal.db

import org.json.JSONArray
import org.json.JSONObject
import ru.asop.proto.v1.CardIdentity as CardIdentityProto

object CardIdentityCodec {

    /** Собирает proto CardIdentity из canonical JSON identity. */
    fun fromJson(json: JSONObject): CardIdentityProto {
        val builder = CardIdentityProto.newBuilder()
            .setCardId(json.optString("cardId", ""))
            .setUid(json.optString("uid", ""))
            .setRegionId(json.optString("regionId", ""))
            .setOrganizerId(json.optString("organizerId", ""))
            .setCarrierId(json.optString("carrierId", ""))
            .setCardsDistributorId(json.optString("cardsDistributorId", ""))
            .setAuditServiceId(json.optString("auditServiceId", ""))
            .setUserId(json.optString("userId", ""))
        val roles = json.optJSONArray("roles")
        if (roles != null) {
            for (i in 0 until roles.length()) {
                builder.addRoles(roles.optString(i, ""))
            }
        }
        return builder.build()
    }

    /** Собирает proto CardIdentity напрямую из полей + uuId = cardId. */
    fun fromFields(
        cardId: String,
        uid: String,
        regionId: String,
        organizerId: String,
        carrierId: String,
        cardsDistributorId: String,
        auditServiceId: String,
        userId: String,
        roles: List<String>
    ): CardIdentityProto {
        return CardIdentityProto.newBuilder()
            .setCardId(cardId)
            .setUid(uid)
            .setRegionId(regionId)
            .setOrganizerId(organizerId)
            .setCarrierId(carrierId)
            .setCardsDistributorId(cardsDistributorId)
            .setAuditServiceId(auditServiceId)
            .setUserId(userId)
            .addAllRoles(roles)
            .build()
    }

    /** Преобразует proto CardIdentity обратно в JSONObject (для совместимости с существующим кодом). */
    fun toJson(proto: CardIdentityProto): JSONObject {
        return JSONObject().apply {
            put("cardId", proto.cardId)
            put("uid", proto.uid)
            put("regionId", proto.regionId)
            put("organizerId", proto.organizerId)
            put("carrierId", proto.carrierId)
            put("cardsDistributorId", proto.cardsDistributorId)
            put("auditServiceId", proto.auditServiceId)
            put("userId", proto.userId)
            val arr = JSONArray()
            proto.rolesList.forEach { arr.put(it) }
            put("roles", arr)
        }
    }

    /** Сериализует proto в байты для записи в File 0 карты. */
    fun serialize(proto: CardIdentityProto): ByteArray = proto.toByteArray()

    /** Десериализует байты из File 0 карты в proto CardIdentity. */
    fun deserialize(bytes: ByteArray): CardIdentityProto? = try {
        CardIdentityProto.parseFrom(bytes)
    } catch (e: Exception) {
        null
    }
}
