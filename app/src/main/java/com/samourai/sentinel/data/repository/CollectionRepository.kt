package com.samourai.sentinel.data.repository

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.samourai.sentinel.data.PubKeyCollection
import com.samourai.sentinel.data.db.PayloadReadException
import com.samourai.sentinel.data.db.SentinelCollectionStore
import com.samourai.sentinel.util.dataBaseScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.koin.java.KoinJavaComponent.inject
import timber.log.Timber
import java.util.*
import kotlin.collections.ArrayList

class CollectionRepository {

    val pubKeyCollections: ArrayList<PubKeyCollection> = arrayListOf()
    val collectionsLiveData: MutableLiveData<ArrayList<PubKeyCollection>> = MutableLiveData()

    /**
     * Set when the on-disk payload exists but could not be decoded.
     *
     * While this is true the repository will REFUSE to persist, because the
     * in-memory list does not reflect reality and writing it would destroy the
     * user's collections.
     */
    private val readFailure: MutableLiveData<String?> = MutableLiveData(null)

    fun readFailure(): LiveData<String?> = readFailure

    @Volatile
    private var isPayloadUsable = false

    /**
     * Serializes every write to the single payload file.
     *
     * The previous `@Synchronized fun sync()` only guarded the *launching* of the
     * coroutine - the lock was released as soon as `launch` returned, so the
     * actual file writes still raced and could truncate each other.
     */
    private val writeMutex = Mutex()

    private val sentinelCollectionStore: SentinelCollectionStore by inject(SentinelCollectionStore::class.java)

    fun addNew(pubKeyCollection: PubKeyCollection) {
        pubKeyCollection.id = UUID.randomUUID().toString()
        pubKeyCollections.add(pubKeyCollection)
        this.sync()
    }

    fun delete(index: Int) {
        pubKeyCollections.removeAt(index)
        this.sync()
    }

    fun update(pubKeyCollection: PubKeyCollection, index: Int) {
        if (index < 0 || index >= pubKeyCollections.size) return
        pubKeyCollections[index] = pubKeyCollection
        pubKeyCollections[index].updateBalance()
        this.sync()
    }


    fun findById(id: String): PubKeyCollection? {
        return pubKeyCollections.find { it.id == id }
    }

    /**
     * write changes to the db
     * sync needs to be called after changing collection (edit,delete,add)
     */
    fun sync() {
        // Never persist on top of a payload we failed to read.
        if (!isPayloadUsable) {
            Timber.w("sync() suppressed: payload is not in a known-good state")
            emit()
            return
        }

        val snapshot: ArrayList<PubKeyCollection>
        synchronized(pubKeyCollections) {
            val dupRemoved = pubKeyCollections.distinctBy { it.id }
            pubKeyCollections.clear()
            pubKeyCollections.addAll(dupRemoved)
            // Hand the writer its own copy so it cannot observe concurrent mutation
            // from fetchFromServer while serializing.
            snapshot = ArrayList(pubKeyCollections.map { it.copy() })
        }

        dataBaseScope.launch(Dispatchers.IO) {
            writeMutex.withLock {
                try {
                    sentinelCollectionStore.getCollectionStore().write(snapshot)
                } catch (e: Exception) {
                    Timber.e(e, "Failed to persist collections")
                }
            }
        }
        this.emit()
    }

    /**
     * Loads collections from disk.
     *
     * Critically, this is NON-DESTRUCTIVE: the in-memory list is only replaced
     * once the payload has been successfully decoded. Previously the list was
     * cleared *before* the read was attempted, so any failure left an empty list
     * that the next sync() would happily write to disk.
     */
    suspend fun read() = withContext(Dispatchers.IO) {
        try {
            val readValue: ArrayList<PubKeyCollection>? = writeMutex.withLock {
                sentinelCollectionStore.getCollectionStore().readOrThrow<ArrayList<PubKeyCollection>>()
            }

            // Only mutate shared state after a successful decode.
            synchronized(pubKeyCollections) {
                pubKeyCollections.clear()
                if (readValue != null) {
                    pubKeyCollections.addAll(readValue.distinctBy { it.id })
                }
            }
            isPayloadUsable = true
            readFailure.postValue(null)
        } catch (e: PayloadReadException) {
            // Leave pubKeyCollections untouched and block writes so we do not
            // overwrite recoverable data.
            Timber.e(e, "Collections payload unreadable; writes are now blocked")
            isPayloadUsable = false
            readFailure.postValue(e.message)
            throw e
        }
        emit()
    }


    private fun emit() {
        val array = synchronized(pubKeyCollections) {
            pubKeyCollections.distinctBy { it.id }
        }
        collectionsLiveData.postValue(array as ArrayList<PubKeyCollection>)
    }

    fun update(pubKeyCollection: PubKeyCollection) {
        if (this.pubKeyCollections.contains(pubKeyCollection)) {
            return this.update(pubKeyCollection, this.pubKeyCollections.indexOf(pubKeyCollection))
        }
    }

    suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        val item = pubKeyCollections.find { it.id == id }
        pubKeyCollections.remove(item)
        sync()
    }

    /**
     * Deliberately clears all collections. Only for the explicit
     * "clear wallet" / "replace on import" flows.
     */
    fun reset() {
        synchronized(pubKeyCollections) {
            this.pubKeyCollections.clear()
        }
        // An intentional reset must be allowed to persist even if a prior read failed.
        isPayloadUsable = true
        this.sync()
    }

}
