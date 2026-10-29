package com.samourai.sentinel.data.repository

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.samourai.sentinel.api.ApiService
import com.samourai.sentinel.api.ApiService.ApiNotConfigured
import com.samourai.sentinel.core.SentinelState
import com.samourai.sentinel.data.AddressTypes
import com.samourai.sentinel.data.PubKeyCollection
import com.samourai.sentinel.data.PubKeyModel
import com.samourai.sentinel.data.Tx
import com.samourai.sentinel.data.Utxo
import com.samourai.sentinel.data.WalletResponse
import com.samourai.sentinel.data.db.dao.TxDao
import com.samourai.sentinel.data.db.dao.UtxoDao
import com.samourai.sentinel.helpers.fromJSON
import com.samourai.sentinel.ui.utils.logThreadInfo
import com.samourai.sentinel.util.UtxoMetaUtil
import com.samourai.sentinel.util.apiScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Response
import org.json.JSONObject
import org.koin.java.KoinJavaComponent.inject
import timber.log.Timber

/**
 * sentinel-android
 *
 */


/**
 * Repository class for handling transaction related to a specific collection
 * Each collection needs to maintain its own repo for handling tx's
 * Tx data will be saved in a separate file
 * transactions that belongs to collection will be stored in a separate file
 */
class TransactionsRepository {

    private val txDao: TxDao by inject(TxDao::class.java)
    private val utxoDao: UtxoDao by inject(UtxoDao::class.java)
    private val apiService: ApiService by inject(ApiService::class.java)
    private val collectionRepository: CollectionRepository by inject(CollectionRepository::class.java)
    private val feeRepository: FeeRepository by inject(FeeRepository::class.java)
    val loading: MutableLiveData<MutableList<Boolean>> = MutableLiveData(mutableListOf())

    //track currently loading collection
    var loadingCollectionId = ""

    fun getTransactionsLiveData(collectionId: String): LiveData<List<Tx>> {
        return txDao.getAllTx(collectionId)
    }

    /**
     *  Api call for wallet api
     *  using coroutines concurrent execution
     */
    suspend fun fetchFromServer(collectionId: String) {
        try {
            logThreadInfo("fetchFromServer")
            val collection = collectionRepository.findById(collectionId) ?: return
            var newTransactions: ArrayList<Tx> = arrayListOf();
            val utxos: ArrayList<Utxo> = arrayListOf();
            val jobs: ArrayList<Deferred<Response>> = arrayListOf()
            collection.pubs.forEach {
                val item = apiScope.async {
                    apiService.getWallet(it.pubKey)
                }
                jobs.add(item);
            }
            apiScope.launch(Dispatchers.Main) {
                loading.value = loading.value?.apply { add(true) }
            }

            jobs.forEach { job ->
                val index = jobs.indexOf(job)
                loadingCollectionId = collectionId
                try {
                    val res = jobs[index].await()
                    res.body?.let { it ->
                        val resString = it.string()
                        val pubKeyAssociated = collection.pubs[index]
                        val jsonObject = JSONObject(resString)
                        if (jsonObject.has("status")) {
                            if (jsonObject.getString("status") == "error") {
                                throw CancellationException("Invalid token")
                            }
                        }
                        val response: WalletResponse = fromJSON<WalletResponse>(resString)!!
                        Timber.i("fetchFromServer: ${response.info.fees}")
                        response.info.fees?.let { it1 -> feeRepository.parse(it1) }
                        SentinelState.blockHeight = response.info.latest_block
                        var latestBlockHeight = 1L
                        if (SentinelState.blockHeight != null)
                            latestBlockHeight = SentinelState.blockHeight?.height!!
                        val items = response.txs.map { tx ->
                            tx.hash = tx.hash + "-" + collectionId
                            tx.associatedPubKey = pubKeyAssociated.pubKey
                            val txBlockHeight = tx.block_height ?: 0
                            if (tx.block_height != null)
                                txDao.updateTxConfirmations(tx.hash, latestBlockHeight.minus(tx.block_height).plus(1))
                            tx.collectionId = collectionId
                            tx.confirmations =
                                if (latestBlockHeight > 0L && txBlockHeight > 0L)
                                        (latestBlockHeight.minus(txBlockHeight)) + 1
                                else 0
                            tx
                        }
                        response.unspent_outputs?.let {
                            val list = response.unspent_outputs.toMutableList().map {
                                it.pubKey = pubKeyAssociated.pubKey
                                it.idx = "${it.txHash}:${it.txOutputN}"
                                it.collectionId = collectionId
                                if (it.xpub != null) {
                                    it.path = it.xpub?.path!!
                                }
                                it
                            }.toList()
                            utxos.addAll(list)
                        }
                        collection.pubs[index].balance = response.wallet.final_balance
                        if (collection.pubs[index].type != AddressTypes.ADDRESS) {
                            val address =
                                response.addresses?.find { address -> address.address == collection.pubs[index].pubKey }
                            if (address != null) {
                                address.accountIndex?.let { accIndex ->
                                    collection.pubs[index].account_index = accIndex
                                }
                                address.changeIndex?.let { changeIndex ->
                                    collection.pubs[index].change_index = changeIndex
                                }
                            }
                        }
                        newTransactions.addAll(items)
                        collection.updateBalance()
                        collection.lastRefreshed = System.currentTimeMillis()
                        val item =
                            collectionRepository.pubKeyCollections.find { collection -> collection.id == collectionId }
                                ?: return
                        collectionRepository.update(
                            collection,
                            collectionRepository.pubKeyCollections.indexOf(item)
                        )
                    }
                } catch (e: Exception) {
                    throw  e
                }
            }
            apiScope.launch(Dispatchers.Main) {
                loading.value = loading.value?.apply { remove(true) }
            }

            withContext(Dispatchers.IO) {
                utxoDao.deleteByCollection(collectionId)
                txDao.deleteByCollectionID(collectionId)
            }
            newTransactions = keepTransactionWihVariousPubkeys(newTransactions)
            saveTx(newTransactions, collectionId)
            saveUtxos(utxos, collectionId)
        } catch (e: Exception) {
            throw  e
        }
    }

    private fun keepTransactionWihVariousPubkeys(transactions: ArrayList<Tx>): ArrayList<Tx> {
        val groupedTransactions = transactions.groupBy { it.hash }
        val hashes: HashMap<String, Int> = hashMapOf()
        groupedTransactions.forEach {
            if (it.value.size > 1) {
                hashes[it.value[0].hash] = 0
            }
        }
        transactions.forEach {
            if (hashes.contains(it.hash)) {
                val counter = hashes[it.hash]?.plus(1)
                hashes[it.hash] = counter!!.toInt()
                it.hash = it.hash + "-" + hashes[it.hash]
            }
        }
        return transactions
    }

    fun loadingState(): LiveData<MutableList<Boolean>> {
        return loading
    }

    private fun saveTx(transactions: ArrayList<Tx>, collectionId: String) = apiScope.launch {
        withContext(Dispatchers.IO) {
            transactions.forEach {
                txDao.insert(it)
            }
        }
    }


    private fun saveUtxos(utxos: ArrayList<Utxo>, collectionId: String) = apiScope.launch {
        val collection = collectionRepository.findById(collectionId)
        withContext(Dispatchers.IO) {
            utxoDao.getUTXObyCollectionAsList(collectionId)
                .forEach {
                    val isExist =
                        utxos.find { utxo -> (utxo.txHash == it.txHash && it.txOutputN == utxo.txOutputN) }
                   //if utxos is not present in the new list it will be removed from the local db
                    if(isExist == null){
                        if (it.txOutputN != null && it.txHash != null) {
                            utxoDao.delete(it.txHash!!, it.txOutputN!!)
                        }
                    }
                }

            UtxoMetaUtil.getBlockedAssociatedWithPubKeyList(collection!!.pubs.map { it.pubKey }.toList())
                .forEach {
                    val isExist =
                        utxos.find { utxo -> (utxo.txHash == it.hash && it.txOutputN == utxo.txOutputN) }
                    //if utxos is not present in the new list it will be removed from the Blocked UTXOs List
                    if(isExist == null){
                        UtxoMetaUtil.remove(it.hash, it.txOutputN)
                    }
                }
            utxos.forEach {
                utxoDao.insert(it)
            }
        }
    }

    suspend fun removeTxsRelatedToPubKey(pubKeyModel: PubKeyModel, collectionId: String) {
        val collection = collectionRepository.findById(collectionId)
        if (collection != null) {
            txDao.deleteByCollectionAndPubkey(collection.id, pubKeyModel.pubKey)
            utxoDao.deleteByCollection(collection.id)
        }
    }

    suspend fun fetchFromServer(collection: PubKeyCollection) {
        return this.fetchFromServer(collection.id)
    }

    suspend fun fetchUTXOS(collectionId: String) {
        try {
            val collection = collectionRepository.findById(collectionId) ?: return
            val utxos: ArrayList<Utxo> = arrayListOf();
            val jobs: ArrayList<Deferred<Response>> = arrayListOf()
            collection.pubs.forEach {
                val item = apiScope.async {
                    apiService.getWallet(it.pubKey)
                }
                jobs.add(item);
            }

            jobs.forEach { job ->
                val index = jobs.indexOf(job)
                loadingCollectionId = collectionId
                try {
                    val res = jobs[index].await()
                    res.body?.let { it ->
                        val resString = it.string()
                        val pubKeyAssociated = collection.pubs[index]
                        val response: WalletResponse = fromJSON<WalletResponse>(resString)!!

                        response.unspent_outputs?.let {
                            val list = response.unspent_outputs.toMutableList().map {
                                it.pubKey = pubKeyAssociated.pubKey
                                it.idx = "${it.txHash}:${it.txOutputN}"
                                it.collectionId = collectionId
                                if (it.xpub != null) {
                                    it.path = it.xpub?.path!!
                                }
                                it
                            }.toList()
                            utxos.addAll(list)
                        }
                    }
                } catch (e: Exception) { Timber.e(e) }
            }

            withContext(Dispatchers.IO) {
                utxoDao.deleteByCollection(collectionId)
            }

            saveUtxos(utxos, collectionId)
        } catch (_: Exception) {
        } catch (e: ApiNotConfigured) {
            Timber.e(e)
        }
    }
    }