package com.samourai.sentinel.data

import kotlin.collections.ArrayList

data class PubKeyCollection(
        var collectionLabel: String = "",
        var balance: Long = 0L,
        var id: String = "",
        var lastRefreshed: Long = 0L,
        var pubs: ArrayList<PubKeyModel> = arrayListOf(),
        var isImportFromWallet: Boolean = false
) {
    fun updateBalance() {
        if (pubs.isEmpty()) {
            this.balance = 0L
            return
        }
        this.balance = pubs.map { it.balance }.toLongArray().reduce { acc, l -> acc + l }
    }

    fun getPubKey(pubKey: String): PubKeyModel? {
        return this.pubs.find { it.pubKey.lowercase() == pubKey.lowercase() }
    }
}