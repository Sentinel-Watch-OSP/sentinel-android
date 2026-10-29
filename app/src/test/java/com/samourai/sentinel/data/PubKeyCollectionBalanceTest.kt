package com.samourai.sentinel.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Guards the balance aggregation paths that previously used `reduce`, which
 * throws UnsupportedOperationException on an empty collection.
 */
class PubKeyCollectionBalanceTest {

    @Test
    fun `updateBalance on a collection with no pubkeys does not throw`() {
        val collection = PubKeyCollection(collectionLabel = "empty")
        collection.updateBalance()
        assertEquals(0L, collection.balance)
    }

    @Test
    fun `updateBalance sums pubkey balances`() {
        val collection = PubKeyCollection(
            collectionLabel = "wallet",
            pubs = arrayListOf(
                PubKeyModel(pubKey = "a", label = "a", type = AddressTypes.BIP84, balance = 100L),
                PubKeyModel(pubKey = "b", label = "b", type = AddressTypes.BIP84, balance = 250L)
            )
        )
        collection.updateBalance()
        assertEquals(350L, collection.balance)
    }

    /**
     * Mirrors HomeViewModel.updateBalance(), which now uses sumOf instead of
     * reduce so that an empty list yields 0 rather than crashing.
     */
    @Test
    fun `summing an empty list of collections yields zero`() {
        val collections = emptyList<PubKeyCollection>()
        assertEquals(0L, collections.sumOf { it.balance })
    }

    @Test
    fun `summing collections aggregates across all of them`() {
        val collections = listOf(
            PubKeyCollection(collectionLabel = "one", balance = 500L),
            PubKeyCollection(collectionLabel = "two", balance = 750L)
        )
        assertEquals(1250L, collections.sumOf { it.balance })
    }
}
