//  Purchases
//
//  Copyright © 2019 RevenueCat, Inc. All rights reserved.
//

package com.revenuecat.purchases.common.caching

import android.content.SharedPreferences
import com.revenuecat.purchases.Offerings
import com.revenuecat.purchases.PurchaserInfo
import com.revenuecat.purchases.common.PurchaseWrapper
import com.revenuecat.purchases.common.attribution.AttributionNetwork
import com.revenuecat.purchases.common.buildPurchaserInfo
import com.revenuecat.purchases.common.debugLog
import com.revenuecat.purchases.common.sha1
import org.json.JSONException
import org.json.JSONObject
import java.util.Date

private const val CACHE_REFRESH_PERIOD = 60000 * 5
private const val SHARED_PREFERENCES_PREFIX = "com.revenuecat.purchases."
internal const val PURCHASER_INFO_SCHEMA_VERSION = 3

class DeviceCache(
    private val preferences: SharedPreferences,
    private val apiKey: String,
    private val offeringsCachedObject: InMemoryCachedObject<Offerings> = InMemoryCachedObject(
        CACHE_REFRESH_PERIOD
    )
) {
    val legacyAppUserIDCacheKey: String by lazy { "$SHARED_PREFERENCES_PREFIX$apiKey" }
    val appUserIDCacheKey: String by lazy { "$SHARED_PREFERENCES_PREFIX$apiKey.new" }
    val attributionCacheKey = "$SHARED_PREFERENCES_PREFIX.attribution"
    val tokensCacheKey: String by lazy { "$SHARED_PREFERENCES_PREFIX$apiKey.tokens" }
    val subscriberAttributesCacheKey: String by lazy { "$SHARED_PREFERENCES_PREFIX$apiKey.subscriberAttributes" }

    var purchaserInfoCachesLastUpdated: Date? = null

    // region app user id

    @Synchronized
    fun getLegacyCachedAppUserID(): String? = preferences.getString(legacyAppUserIDCacheKey, null)

    @Synchronized
    fun getCachedAppUserID(): String? = preferences.getString(appUserIDCacheKey, null)

    @Synchronized
    fun cacheAppUserID(appUserID: String) {
        preferences.edit().putString(appUserIDCacheKey, appUserID).apply()
    }

    @Synchronized
    fun clearCachesForAppUserID() {
        preferences.edit()
            .clearPurchaserInfo()
            .clearAppUserID()
            .apply()
        clearPurchaserInfoCacheTimestamp()
        clearOfferingsCache()
    }

    private fun SharedPreferences.Editor.clearPurchaserInfo(): SharedPreferences.Editor {
        getCachedAppUserID()?.let {
            remove(purchaserInfoCacheKey(it))
        }
        getLegacyCachedAppUserID()?.let {
            remove(purchaserInfoCacheKey(it))
        }
        return this
    }

    private fun SharedPreferences.Editor.clearAppUserID(): SharedPreferences.Editor {
        remove(appUserIDCacheKey)
        remove(legacyAppUserIDCacheKey)
        return this
    }

    // endregion

    // region purchaser info
    fun purchaserInfoCacheKey(appUserID: String) = "$legacyAppUserIDCacheKey.$appUserID"

    fun getCachedPurchaserInfo(appUserID: String): PurchaserInfo? {
        return preferences.getString(purchaserInfoCacheKey(appUserID), null)
            ?.let { json ->
                try {
                    val cachedJSONObject = JSONObject(json)
                    val schemaVersion = cachedJSONObject.optInt("schema_version")
                    return if (schemaVersion == PURCHASER_INFO_SCHEMA_VERSION) {
                        cachedJSONObject.buildPurchaserInfo()
                    } else {
                        null
                    }
                } catch (e: JSONException) {
                    null
                }
            }
    }

    @Synchronized
    fun cachePurchaserInfo(appUserID: String, info: PurchaserInfo) {
        val jsonObject = info.jsonObject.also {
            it.put("schema_version", PURCHASER_INFO_SCHEMA_VERSION)
        }
        preferences.edit()
            .putString(
                purchaserInfoCacheKey(appUserID),
                jsonObject.toString()
            ).apply()

        setPurchaserInfoCacheTimestampToNow()
    }

    @Synchronized
    fun isPurchaserInfoCacheStale(): Boolean {
        return purchaserInfoCachesLastUpdated?.let { cachesLastUpdated ->
            Date().time - cachesLastUpdated.time >= CACHE_REFRESH_PERIOD
        } ?: true
    }

    @Synchronized
    fun clearPurchaserInfoCacheTimestamp() {
        purchaserInfoCachesLastUpdated = null
    }

    @Synchronized
    fun setPurchaserInfoCacheTimestampToNow() {
        purchaserInfoCachesLastUpdated = Date()
    }

    // endregion

    // region attribution data

    @Synchronized
    fun getCachedAttributionData(network: AttributionNetwork, userId: String): String? =
        preferences.getString(getAttributionDataCacheKey(userId, network), null)

    @Synchronized
    fun cacheAttributionData(
        network: AttributionNetwork,
        userId: String,
        cacheValue: String
    ) {
        preferences.edit().putString(getAttributionDataCacheKey(userId, network), cacheValue)
            .apply()
    }

    @Synchronized
    fun clearLatestAttributionData(userId: String) {
        val editor = preferences.edit()
        AttributionNetwork.values().forEach { network ->
            editor.remove(getAttributionDataCacheKey(userId, network))
        }
        editor.apply()
    }

    // endregion

    // region purchase tokens

    @Synchronized
    fun getPreviouslySentHashedTokens(): Set<String> {
        return (preferences.getStringSet(tokensCacheKey, emptySet())?.toSet() ?: emptySet()).also {
            debugLog("[QueryPurchases] Tokens already posted: $it")
        }
    }

    @Synchronized
    fun addSuccessfullyPostedToken(token: String) {
        debugLog("[QueryPurchases] Saving token $token with hash ${token.sha1()}")
        getPreviouslySentHashedTokens().let {
            debugLog("[QueryPurchases] Tokens in cache before saving $it")
            setSavedTokenHashes(it.toMutableSet().apply { add(token.sha1()) })
        }
    }

    @Synchronized
    private fun setSavedTokenHashes(newSet: Set<String>) {
        debugLog("[QueryPurchases] Saving tokens $newSet")
        preferences.edit().putStringSet(tokensCacheKey, newSet).apply()
    }

    /**
     * Removes from the database all hashed tokens that are not considered active anymore, i.e. all
     * consumed in-apps or inactive subscriptions hashed tokens that are still in the local cache.
     */
    @Synchronized
    fun cleanPreviouslySentTokens(
        activeSubsHashedTokens: Set<String>,
        unconsumedInAppsHashedTokens: Set<String>
    ) {
        debugLog("[QueryPurchases] Cleaning previously sent tokens")
        setSavedTokenHashes(
            (activeSubsHashedTokens + unconsumedInAppsHashedTokens).intersect(
                getPreviouslySentHashedTokens()
            )
        )
    }

    /**
     * Returns a list containing all tokens that are in [activeSubsByTheirHashedToken] and
     * [activeInAppsByTheirHashedToken] map that are not present in the device cache.
     * In other words, returns all hashed tokens that are active and have not
     * been posted to our backend yet.
     */
    @Synchronized
    fun getActivePurchasesNotInCache(
        activeSubsByTheirHashedToken: Map<String, PurchaseWrapper>,
        activeInAppsByTheirHashedToken: Map<String, PurchaseWrapper>
    ): List<PurchaseWrapper> {
        return activeSubsByTheirHashedToken
            .plus(activeInAppsByTheirHashedToken)
            .minus(getPreviouslySentHashedTokens())
            .values.toList()
    }

    // endregion

    // region offerings

    val cachedOfferings: Offerings?
        get() = offeringsCachedObject.cachedInstance

    @Synchronized
    fun cacheOfferings(offerings: Offerings) {
        offeringsCachedObject.cacheInstance(offerings)
    }

    @Synchronized
    fun isOfferingsCacheStale(): Boolean = offeringsCachedObject.isCacheStale()

    @Synchronized
    fun clearOfferingsCacheTimestamp() {
        offeringsCachedObject.clearCacheTimestamp()
    }

    @Synchronized
    fun setOfferingsCacheTimestampToNow() {
        offeringsCachedObject.updateCacheTimestamp(Date())
    }

    // endregion

    private fun clearOfferingsCache() {
        offeringsCachedObject.clearCache()
    }

    private fun getAttributionDataCacheKey(
        userId: String,
        network: AttributionNetwork
    ) = "$attributionCacheKey.$userId.$network"

    // region utils

    fun getJSONObjectOrNull(key: String): JSONObject? {
        return preferences.getString(key, null)?.let { json ->
            try {
                JSONObject(json)
            } catch (e: JSONException) {
                null
            }
        }
    }

    fun putString(
        cacheKey: String,
        value: String
    ) {
        preferences.edit().putString(
            cacheKey,
            value
        ).apply()
    }

    fun remove(
        cacheKey: String
    ) {
        preferences.edit().remove(cacheKey).apply()
    }

    fun findKeysThatStartWith(
        cacheKey: String
    ): Set<String> {
        return preferences.all
            ?.filterKeys { it.startsWith(cacheKey) }
            ?.keys ?: emptySet()
    }

    fun newKey(
        key: String
    ) = "$SHARED_PREFERENCES_PREFIX$apiKey.$key"

    // endregion
}
