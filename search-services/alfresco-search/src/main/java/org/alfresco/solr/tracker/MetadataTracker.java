/*-
 * #%L
 * Alfresco Solr Search
 * %%
 * Copyright (C) 2005 - 2020 Alfresco Software Limited
 * %%
 * This file is part of the Alfresco software. 
 * If the software was purchased under a paid Alfresco license, the terms of 
 * the paid license agreement will prevail.  Otherwise, the software is 
 * provided under the following open source license terms:
 * 
 * Alfresco is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * Alfresco is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public License
 * along with Alfresco. If not, see <http://www.gnu.org/licenses/>.
 * #L%
 */
package org.alfresco.solr.tracker;

import static org.alfresco.repo.index.shard.ShardMethodEnum.DB_ID_RANGE;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.alfresco.error.AlfrescoRuntimeException;
import org.alfresco.httpclient.AuthenticationException;
import org.alfresco.solr.BoundedDeque;
import org.alfresco.solr.InformationServer;
import org.alfresco.solr.NodeReport;
import org.alfresco.solr.TrackerState;
import org.alfresco.solr.adapters.IOpenBitSet;
import org.alfresco.solr.client.GetNodesParameters;
import org.alfresco.solr.client.Node;
import org.alfresco.solr.client.Node.SolrApiNodeStatus;
import org.alfresco.solr.client.SOLRAPIClient;
import org.alfresco.solr.client.Transaction;
import org.alfresco.solr.client.Transactions;
import org.alfresco.util.Pair;
import org.apache.commons.codec.EncoderException;
import org.json.JSONException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/*
 * This tracks two things: transactions and metadata nodes
 * @author Ahmed Owian
 */
public class MetadataTracker extends ActivatableTracker
{
    protected final static Logger log = LoggerFactory.getLogger(MetadataTracker.class);
    private static final int DEFAULT_TRANSACTION_DOCS_BATCH_SIZE = 100;
    private static final int DEFAULT_NODE_BATCH_SIZE = 10;
    private int transactionDocsBatchSize = DEFAULT_TRANSACTION_DOCS_BATCH_SIZE;
    private int nodeBatchSize = DEFAULT_NODE_BATCH_SIZE;
    private ConcurrentLinkedQueue<Long> transactionsToReindex = new ConcurrentLinkedQueue<>();
    private ConcurrentLinkedQueue<Long> transactionsToIndex = new ConcurrentLinkedQueue<>();
    private ConcurrentLinkedQueue<Long> transactionsToPurge = new ConcurrentLinkedQueue<>();
    private ConcurrentLinkedQueue<Long> nodesToReindex = new ConcurrentLinkedQueue<>();
    private ConcurrentLinkedQueue<Long> nodesToIndex = new ConcurrentLinkedQueue<>();
    private ConcurrentLinkedQueue<Long> nodesToPurge = new ConcurrentLinkedQueue<>();
    private ConcurrentLinkedQueue<String> queriesToReindex = new ConcurrentLinkedQueue<>();

    /**
     * Check if nextTxCommitTimeService is available in the repository.
     * This service is used to find the next available transaction commit time from a given time,
     * so periods of time where no document updating is happening can be skipped while getting
     * pending transactions list.
     *
     * {@link org.alfresco.solr.client.SOLRAPIClient#GET_NEXT_TX_COMMIT_TIME}
     */
    private boolean nextTxCommitTimeServiceAvailable = false;

    /**
     * Check if txInteravlCommitTimeService is available in the repository.
     * This service returns the minimum and the maximum commit time for transactions in a node id range,
     * so method sharding DB_ID_RANGE can skip transactions not relevant for the DB ID range.
     *
     * {@link org.alfresco.solr.client.SOLRAPIClient#GET_TX_INTERVAL_COMMIT_TIME}
     */
    private boolean txIntervalCommitTimeServiceAvailable = false;
    /** Whether the cascade tracking is enabled. */
    private boolean cascadeTrackerEnabled = true;

    /**
     * Transaction Id range to get the first transaction in database.
     * 0-2000 by default.
     */
    private Pair<Long, Long> minTxnIdRange;

    public MetadataTracker(Properties p, SOLRAPIClient client, String coreName,
            InformationServer informationServer)
    {
        this(p, client, coreName, informationServer, false);
    }

    /**
     * MetadataTracker constructor
     *
     * @param p includes SOLR core properties (from environment variables and properties file)
     * @param client Alfresco Repository http client
     * @param coreName Name of the SOLR Core (alfresco, archive)
     * @param informationServer SOLR Information Server
     * @param checkRepoServicesAvailability is true if Repo Services availability needs to be checked
     */
    public MetadataTracker( Properties p, SOLRAPIClient client, String coreName,
                InformationServer informationServer, boolean checkRepoServicesAvailability)
    {
        super(p, client, coreName, informationServer, Tracker.Type.METADATA);
        transactionDocsBatchSize = Integer.parseInt(p.getProperty("alfresco.transactionDocsBatchSize", "100"));
        nodeBatchSize = Integer.parseInt(p.getProperty("alfresco.nodeBatchSize", "10"));
        threadHandler = new ThreadHandler(p, coreName, "MetadataTracker");
        cascadeTrackerEnabled = informationServer.cascadeTrackingEnabled();
        String[] minTxninitialRangeString = p.getProperty("solr.initial.transaction.range", "0-2000").split("-");
        minTxnIdRange = new Pair<Long, Long>(Long.valueOf(minTxninitialRangeString[0]), Long.valueOf(minTxninitialRangeString[1]));
        
        // In order to apply performance optimizations, checking the availability of Repo Web Scripts is required.
        // As these services are available from ACS 6.2
        if (checkRepoServicesAvailability)
        {
            // Try invoking getNextTxCommitTime service
            try
            {
                client.getNextTxCommitTime(coreName, 0l);
                nextTxCommitTimeServiceAvailable = true;
            }
            catch (NoSuchMethodException e)
            {
                log.warn("nextTxCommitTimeService is not available. Upgrade your ACS Repository version in order to use this feature: {} ", e.getMessage());
            }
            catch (Exception e)
            {
                log.error("Checking nextTxCommitTimeService failed.", e);
            }
    
            // Try invoking txIntervalCommitTime service
            if (shardMethod.equals(DB_ID_RANGE))
            {
                try
                {
                    client.getTxIntervalCommitTime(coreName, 0l, 0l);
                    txIntervalCommitTimeServiceAvailable = true;
                }
                catch (NoSuchMethodException e)
                {
                    log.warn("txIntervalCommitTimeServiceAvailable is not available. Upgrade your ACS Repository version " +
                            "to use this feature with DB_ID_RANGE sharding: {} ", e.getMessage());
                }
                catch (Exception e)
                {
                    log.error("Checking txIntervalCommitTimeServiceAvailable failed.", e);
                }
            }
        }
    
    }

    MetadataTracker()
    {
        super(Tracker.Type.METADATA);
    }

    @Override
    protected void doTrack(String iterationId) throws AuthenticationException, IOException, JSONException, EncoderException
    {
        log.debug("### MetadataTracker doTrack ###");
        // MetadataTracker must wait until ModelTracker has run
        ModelTracker modelTracker = this.infoSrv.getAdminHandler().getTrackerRegistry().getModelTracker();
        if (modelTracker != null && modelTracker.hasModels())
        {
            trackRepository();
        }
        else
        {
            invalidateState();
        }
    }

    public void maintenance() throws Exception
    {
        purgeTransactions();
        purgeNodes();
        reindexTransactions();
        reindexNodes();
        reindexNodesByQuery();
        indexTransactions();
        indexNodes();
    }

    public boolean hasMaintenance()
    {
        return  transactionsToReindex.size() > 0 ||
                transactionsToIndex.size() > 0 ||
                transactionsToPurge.size() > 0 ||
                nodesToReindex.size() > 0 ||
                nodesToIndex.size() > 0 ||
                nodesToPurge.size() > 0 ||
                queriesToReindex.size() > 0;
    }

    private void trackRepository() throws IOException, AuthenticationException, JSONException, EncoderException
    {
        log.debug("####### MetadataTracker trackRepository Start #######");
        checkShutdown();

        // Check we are tracking the correct repository
        TrackerState state = super.getTrackerState();
        log.debug("####### MetadataTracker check CYCLE #######");
        log.debug(String.format("%s ### state: %s", coreName, state.toString()));
        if(state.getTrackerCycles() == 0)
        {
            //We have a new tracker state so do the checks.
            checkRepoAndIndexConsistency(state);
        }

        if(docRouter instanceof DBIDRangeRouter)
        {
            DBIDRangeRouter dbidRangeRouter = (DBIDRangeRouter)docRouter;
            long indexCap = infoSrv.getIndexCap();
            long endRange = dbidRangeRouter.getEndRange();
            assert(indexCap == -1 || indexCap >= endRange);

            if(indexCap > endRange) {
                dbidRangeRouter.setExpanded(true);
                dbidRangeRouter.setEndRange(indexCap);
            }

            dbidRangeRouter.setInitialized(true);
        }

        checkShutdown();
        trackTransactions();
    }

    /**
     * Checks the first and last TX time
     * @param state the state of this tracker
     * @throws AuthenticationException
     * @throws IOException
     * @throws JSONException
     */
    private void checkRepoAndIndexConsistency(TrackerState state) throws AuthenticationException, IOException, JSONException
    {
        Transactions firstTransactions = null;
        if (state.getLastGoodTxCommitTimeInIndex() == 0)
        {
            state.setCheckedLastTransactionTime(true);
            state.setCheckedFirstTransactionTime(true);
            log.info("No transactions found - no verification required");

            firstTransactions = client.getTransactions(null, minTxnIdRange.getFirst(), null, minTxnIdRange.getSecond(), 1);
            if (!firstTransactions.getTransactions().isEmpty())
            {
                Transaction firstTransaction = firstTransactions.getTransactions().get(0);
                long firstTransactionCommitTime = firstTransaction.getCommitTimeMs();
                state.setLastGoodTxCommitTimeInIndex(firstTransactionCommitTime);
                setLastTxCommitTimeAndTxIdInTrackerState(firstTransactions, state);
            }
        }

        if (!state.isCheckedFirstTransactionTime())
        {

            // On Shards configured with DB_ID_RANGE, the first indexed transaction can be
            // different from the first transaction in the repository as some transactions
            // are skipped if they are not related with the range of the Shard.
            // Getting the minCommitTime for the Shard is enough in order to check
            // that the first transaction is present.
            long minCommitTime = 0l;
            if (docRouter instanceof DBIDRangeRouter && txIntervalCommitTimeServiceAvailable)
            {
                try
                {
                    DBIDRangeRouter dbIdRangeRouter = (DBIDRangeRouter) docRouter;
                    Pair<Long, Long> commitTimes = client.getTxIntervalCommitTime(coreName,
                            dbIdRangeRouter.getStartRange(), dbIdRangeRouter.getEndRange());
                    minCommitTime = commitTimes.getFirst();
                }
                catch (NoSuchMethodException e)
                {
                    log.warn("txIntervalCommitTimeServiceAvailable is not available. If you are using DB_ID_RANGE shard method, "
                            + "upgrade your ACS Repository version in order to use the skip transactions feature: {} ", e.getMessage());
                }
            }

            // When a Shard with DB_ID_RANGE method is empty, minCommitTime is -1.
            // No firstTransaction checking is required for this case.
            if (minCommitTime != -1l) {

                firstTransactions = client.getTransactions(minCommitTime, 0L, null, 2000l, 1);
                if (!firstTransactions.getTransactions().isEmpty())
                {
                    Transaction firstTransaction = firstTransactions.getTransactions().get(0);
                    long firstTxId = firstTransaction.getId();
                    long firstTransactionCommitTime = firstTransaction.getCommitTimeMs();
                    int setSize = this.infoSrv.getTxDocsSize(""+firstTxId, ""+firstTransactionCommitTime);

                    if (setSize == 0)
                    {
                        log.error("First transaction was not found with the correct timestamp.");
                        log.error("SOLR has successfully connected to your repository  however the SOLR indexes and repository database do not match.");
                        log.error("If this is a new or rebuilt database your SOLR indexes also need to be re-built to match the database.");
                        log.error("You can also check your SOLR connection details in solrcore.properties.");
                        throw new AlfrescoRuntimeException("Initial transaction not found with correct timestamp");
                    }
                    else if (setSize == 1)
                    {
                        state.setCheckedFirstTransactionTime(true);
                        log.info("Verified first transaction and timestamp in index");
                    }
                    else
                    {
                        log.warn("Duplicate initial transaction found with correct timestamp");
                    }
                }
            }
        }

        // Checks that the last TxId in solr is <= last TxId in repo
        if (!state.isCheckedLastTransactionTime())
        {
            if (firstTransactions == null)
            {
                firstTransactions = client.getTransactions(null, minTxnIdRange.getFirst(), null, minTxnIdRange.getSecond(), 1);
            }

            setLastTxCommitTimeAndTxIdInTrackerState(firstTransactions, state);
            Long maxTxnCommitTimeInRepo = firstTransactions.getMaxTxnCommitTime();
            Long maxTxnIdInRepo = firstTransactions.getMaxTxnId();
            if (maxTxnCommitTimeInRepo != null && maxTxnIdInRepo != null)
            {
                Transaction maxTxInIndex = this.infoSrv.getMaxTransactionIdAndCommitTimeInIndex();
                if (maxTxInIndex.getCommitTimeMs() > maxTxnCommitTimeInRepo)
                {
                    log.error("Last transaction was found in index with timestamp later than that of repository.");
                    log.error("Max Tx In Index: " + maxTxInIndex.getId() + ", In Repo: " + maxTxnIdInRepo);
                    log.error("Max Tx Commit Time In Index: " + maxTxInIndex.getCommitTimeMs() + ", In Repo: "
                            + maxTxnCommitTimeInRepo);
                    log.error("SOLR has successfully connected to your repository  however the SOLR indexes and repository database do not match.");
                    log.error("If this is a new or rebuilt database your SOLR indexes also need to be re-built to match the database.");
                    log.error("You can also check your SOLR connection details in solrcore.properties.");
                    throw new AlfrescoRuntimeException("Last transaction found in index with incorrect timestamp");
                }
                else
                {
                    state.setCheckedLastTransactionTime(true);
                    log.info("Verified last transaction timestamp in index less than or equal to that of repository.");
                }
            }
        }
    }
    
    private void indexTransactions() throws IOException, AuthenticationException, JSONException
    {
        long startElapsed = System.nanoTime();
        
        int docCount = 0;
        boolean requiresCommit = false;
        while (transactionsToIndex.peek() != null)
        {
            Long transactionId = transactionsToIndex.poll();
            if (transactionId != null)
            {
                Transactions transactions = client.getTransactions(null, transactionId, null, transactionId+1, 1);
                if ((transactions.getTransactions().size() > 0) && (transactionId.equals(transactions.getTransactions().get(0).getId())))
                {
                    Transaction info = transactions.getTransactions().get(0);

                    GetNodesParameters gnp = new GetNodesParameters();
                    ArrayList<Long> txs = new ArrayList<Long>();
                    txs.add(info.getId());
                    gnp.setTransactionIds(txs);
                    gnp.setStoreProtocol(storeRef.getProtocol());
                    gnp.setStoreIdentifier(storeRef.getIdentifier());
                    updateShardProperty();

                    shardProperty.ifPresent(p -> gnp.setShardProperty(p));

                    gnp.setCoreName(coreName);

                    List<Node> nodes = client.getNodes(gnp, (int) info.getUpdates());
                    for (Node node : nodes)
                    {
                        docCount++;
                        if (log.isDebugEnabled())
                        {
                            log.debug(node.toString());
                        }
                        this.infoSrv.indexNode(node, false);
                        checkShutdown();
                    }

                    // Index the transaction doc after the node - if this is not found then a reindex will be done.
                    this.infoSrv.indexTransaction(info, false);
                    log.info("INDEX ACTION - Transaction {} has been indexed", transactionId);
                    requiresCommit = true;

                    trackerStats.addTxDocs(nodes.size());
                }
                else
                {
                    log.info("INDEX ACTION - Transaction {} was not found in database, it has NOT been reindexed", transactionId);
                }
            }

            if (docCount > batchCount)
            {
                if(this.infoSrv.getRegisteredSearcherCount() < getMaxLiveSearchers())
                {
                    checkShutdown();
                    //this.infoSrv.commit();
                    long endElapsed = System.nanoTime();
                    trackerStats.addElapsedNodeTime(docCount, endElapsed-startElapsed);
                    startElapsed = endElapsed;
                    docCount = 0;
                    requiresCommit = false;
                }
            }
        }
        if (requiresCommit || (docCount > 0))
        {
            checkShutdown();
            //this.infoSrv.commit();
            long endElapsed = System.nanoTime();
            trackerStats.addElapsedNodeTime(docCount, endElapsed - startElapsed);
        }
    }

    private void indexNodes() throws IOException, AuthenticationException, JSONException
    {
        boolean requiresCommit = false;
        while (nodesToIndex.peek() != null)
        {
            Long nodeId = nodesToIndex.poll();
            if (nodeId != null)
            {
                Node node = new Node();
                node.setId(nodeId);
                node.setStatus(SolrApiNodeStatus.UNKNOWN);
                node.setTxnId(Long.MAX_VALUE);

                this.infoSrv.indexNode(node, false);
                log.info("INDEX ACTION - Node {} has been reindexed", node.getId());
                requiresCommit = true;
            }
            checkShutdown();
        }

        if(requiresCommit) {
            checkShutdown();
            //this.infoSrv.commit();
        }
    }

    private void reindexTransactions() throws IOException, AuthenticationException, JSONException
    {
        long startElapsed = System.nanoTime();
        int docCount = 0;
        while (transactionsToReindex.peek() != null)
        {
            Long transactionId = transactionsToReindex.poll();

            if (transactionId != null)
            {
                // make sure it is cleaned out so we do not miss deletes
                this.infoSrv.deleteByTransactionId(transactionId);

                Transactions transactions = client.getTransactions(null, transactionId, null, transactionId+1, 1);
                if ((transactions.getTransactions().size() > 0) && (transactionId.equals(transactions.getTransactions().get(0).getId())))
                {
                    Transaction info = transactions.getTransactions().get(0);
                    this.infoSrv.dirtyTransaction(info.getId());
                    GetNodesParameters gnp = new GetNodesParameters();
                    ArrayList<Long> txs = new ArrayList<Long>();
                    txs.add(info.getId());
                    gnp.setTransactionIds(txs);
                    gnp.setStoreProtocol(storeRef.getProtocol());
                    gnp.setStoreIdentifier(storeRef.getIdentifier());
                    gnp.setCoreName(coreName);
                    List<Node> nodes =   client.getNodes(gnp, (int) info.getUpdates());
                    for (Node node : nodes)
                    {
                        docCount++;
                        if (log.isDebugEnabled())
                        {
                            log.debug(node.toString());
                        }
                        this.infoSrv.indexNode(node, true);
                        checkShutdown();
                    }

                    // Index the transaction doc after the node - if this is not found then a reindex will be done.
                    this.infoSrv.indexTransaction(info, true);
                    log.info("REINDEX ACTION - Transaction {} has been reindexed", transactionId);
                }
                else
                {
                    log.info("REINDEX ACTION - Transaction {} was not found in database, it has NOT been reindexed", transactionId);
                }
            }

            if (docCount > batchCount)
            {
                if(this.infoSrv.getRegisteredSearcherCount() < getMaxLiveSearchers())
                {
                    checkShutdown();
                    long endElapsed = System.nanoTime();
                    trackerStats.addElapsedNodeTime(docCount, endElapsed-startElapsed);
                    startElapsed = endElapsed;
                    docCount = 0;
                }
            }
        }
        if (docCount > 0)
        {
            checkShutdown();
            //this.infoSrv.commit();
            long endElapsed = System.nanoTime();
            trackerStats.addElapsedNodeTime(docCount, endElapsed - startElapsed);
        }
    }

    
    private void reindexNodes() throws IOException, AuthenticationException, JSONException
    {
        boolean requiresCommit = false;
        while (nodesToReindex.peek() != null)
        {
            Long nodeId = nodesToReindex.poll();
            if (nodeId != null)
            {
                // make sure it is cleaned out so we do not miss deletes
                this.infoSrv.deleteByNodeId(nodeId);

                Node node = new Node();
                node.setId(nodeId);
                node.setStatus(SolrApiNodeStatus.UNKNOWN);
                node.setTxnId(Long.MAX_VALUE);

                this.infoSrv.indexNode(node, true);
                log.info("REINDEX ACTION - Node {} has been reindexed", node.getId());
                requiresCommit = true;
            }
            checkShutdown();
        }

        if(requiresCommit)
        {
            checkShutdown();
            //this.infoSrv.commit();
        }
    }
    
    private void reindexNodesByQuery() throws IOException, AuthenticationException, JSONException
    {
        boolean requiresCommit = false;
        while (queriesToReindex.peek() != null)
        {
            String query = queriesToReindex.poll();
            if (query != null)
            {
                this.infoSrv.reindexNodeByQuery(query);
                log.info("REINDEX ACTION - Nodes from query {} have been reindexed", query);
                requiresCommit = true;
            }
            checkShutdown();
        }

        if(requiresCommit)
        {
            checkShutdown();
            //this.infoSrv.commit();
        }
    }


    private void purgeTransactions() throws IOException, AuthenticationException, JSONException
    {
        boolean requiresCommit = false;
        while (transactionsToPurge.peek() != null)
        {
            Long transactionId = transactionsToPurge.poll();
            if (transactionId != null)
            {
                // make sure it is cleaned out so we do not miss deletes
                this.infoSrv.deleteByTransactionId(transactionId);
                requiresCommit = true;
                log.info("PURGE ACTION - Purged transactionId {}", transactionId);
            }
            checkShutdown();
        }
        
        if(requiresCommit)
        {
            checkShutdown();
            //this.infoSrv.commit();
        }
    }

    private void purgeNodes() throws IOException, AuthenticationException, JSONException
    {
        while (nodesToPurge.peek() != null)
        {
            Long nodeId = nodesToPurge.poll();
            if (nodeId != null)
            {
                // make sure it is cleaned out so we do not miss deletes
                this.infoSrv.deleteByNodeId(nodeId);
                log.info("PURGE ACTION - Purged nodeId {}", nodeId);
            }
            checkShutdown();
        }
    }

    protected Long getTxFromCommitTime(BoundedDeque<Transaction> txnsFound, long lastGoodTxCommitTimeInIndex) {
        if (txnsFound.size() > 0)
        {
            return txnsFound.getLast().getCommitTimeMs();
        }
        else
        {
            return lastGoodTxCommitTimeInIndex;
        }
    }

    private boolean alreadyFoundTransactions(BoundedDeque<Transaction> txnsFound, Transactions transactions)
    {
        if (txnsFound.size() == 0) { return false; }

        if (transactions.getTransactions().size() == 1)
        {
            return transactions.getTransactions().get(0).getId() == txnsFound.getLast().getId();
        }
        else
        {
            HashSet<Transaction> alreadyFound = new HashSet<Transaction>(txnsFound.getDeque());
            for (Transaction txn : transactions.getTransactions())
            {
                if (!alreadyFound.contains(txn)) { return false; }
            }
            return true;
        }
    }

    protected Transactions getSomeTransactions(BoundedDeque<Transaction> txnsFound, Long fromCommitTime, long timeStep,
                int maxResults, long endTime) throws AuthenticationException, IOException, JSONException, EncoderException, NoSuchMethodException
    {

        long actualTimeStep = timeStep;

        Transactions transactions;
        // step forward in time until we find something or hit the time bound
        // max id unbounded
        Long startTime = fromCommitTime == null  ? 0L : fromCommitTime;
        log.debug(String.format("#### %s MetadataTracker getSomeTransactions start time: %d end: %d",
                  this.coreName,
                  startTime,
                  endTime));
        if(startTime == 0)
        {
            return client.getTransactions(startTime,
                                          null,
                                          startTime + actualTimeStep,
                                          null, 
                                          maxResults);
        }

        do
        {
            transactions = client.getTransactions(startTime, null, startTime + timeStep,
                    null, maxResults);
            startTime += actualTimeStep;
            
            // If no transactions are found, advance the time window to the next available transaction commit time
            if (nextTxCommitTimeServiceAvailable && transactions.getTransactions().size() == 0)
            {
                Long nextTxCommitTime = client.getNextTxCommitTime(coreName, startTime);
                if (nextTxCommitTime != -1)
                {
                    log.info("Advancing transactions from {} to {}", startTime, nextTxCommitTime);
                    transactions = client.getTransactions(nextTxCommitTime, null,
                            nextTxCommitTime + timeStep, null, maxResults);
                }
            }

        } while (((transactions.getTransactions().size() == 0) && (startTime < endTime))
                    || ((transactions.getTransactions().size() > 0) && alreadyFoundTransactions(txnsFound, transactions)));


        return transactions;
    }

    protected void trackTransactions() throws AuthenticationException, IOException, JSONException, EncoderException
    {
        long startElapsed = System.nanoTime();

        boolean upToDate = false;
        Transactions transactions;
        BoundedDeque<Transaction> txnsFound = new BoundedDeque<Transaction>(100);
        HashSet<Transaction> txsIndexed = new LinkedHashSet<>();
        long totalUpdatedDocs = 0;
        int docCount = 0;

        do
        {
            try
            {
                /*
                * This write lock is used to lock out the Commit Tracker. The ensures that the MetaDataTracker will
                * not be indexing content while commits or rollbacks are occurring.
                */

                getWriteLock().acquire();

                /*
                * We acquire the tracker state again here and set it globally. This is because the
                * tracker state could have been invalidated due to a rollback by the CommitTracker.
                * In this case the state will revert to the last transaction state record in the index.
                */

                this.state = getTrackerState();


                /*
                *  The fromCommitTime tells getSomeTransactions() where to start, this actually fairly straight forward.
                *
                *  What makes this code so tricky to understand is the state.getTimeToStopIndexing().
                *
                *  There are two scenarios to keep in mind:
                *
                *  1) Full re-index: In this scenario the state.getTimeToStopIndexing() will never stop the indexing.
                *
                *  2) Up-to-date indexing: This is where state.getTimeToStopIndexing() gets interesting. In this scenario
                *  the Solr index is already up to date with the repo and it is tracking new transactions. The state.getTimeToStopIndexing()
                *  in this scenario causes the getSomeTransactions() call to stop returning results if it finds a transaction
                *  beyond a specific point in time. This will break out of this loop and end the tracker run.
                *
                *  The next time the metadata tracker runs the "continueState()" method applies the "hole retention"
                *  to state.getLastGoodTxCommitTimeInIndex(). This causes the state.getLastGoodTxCommitTimeInIndex() to scan
                *  for prior transactions that might have been missed.
                *
                */

                Long fromCommitTime = getTxFromCommitTime(txnsFound,
                        state.getLastIndexedTxCommitTime() == 0 ? state.getLastGoodTxCommitTimeInIndex() : state.getLastIndexedTxCommitTime());
                log.debug("#### Check txnsFound : " + txnsFound.size());
                log.debug("======= fromCommitTime: " + fromCommitTime);

                // When using DB_ID_RANGE, fromCommitTime cannot be before the commit time of the first transaction
                // for the DB_ID_RANGE to be indexed and commit time of the last transaction cannot be lower than fromCommitTime.
                // When there isn't nodes in that range, -1 is returned as commit times
                boolean shardOutOfRange = false;
                if (docRouter instanceof DBIDRangeRouter && txIntervalCommitTimeServiceAvailable)
                {

                    DBIDRangeRouter dbIdRangeRouter = (DBIDRangeRouter) docRouter;
                    Pair<Long, Long> commitTimes = client.getTxIntervalCommitTime(coreName,
                            dbIdRangeRouter.getStartRange(), dbIdRangeRouter.getEndRange());
                    Long shardMinCommitTime = commitTimes.getFirst();
                    Long shardMaxCommitTime = commitTimes.getSecond();

                    // Node Range it's not still available in repository
                    if (shardMinCommitTime == -1)
                    {
                        log.debug(
                                "#### [DB_ID_RANGE] No nodes in range [{}-{}] "
                                        + "exist in the repository. Indexing only latest transaction.",
                                dbIdRangeRouter.getStartRange(), dbIdRangeRouter.getEndRange());
                        shardOutOfRange = true;
                    }
                    if (fromCommitTime > shardMaxCommitTime)
                    {
                        log.debug("#### [DB_ID_RANGE] Last commit time is greater that max commit time in in range [{}-{}]. "
                                        + "Indexing only latest transaction.",
                                dbIdRangeRouter.getStartRange(), dbIdRangeRouter.getEndRange());
                        shardOutOfRange = true;
                    }
                    // Initial commit time for Node Range is greater than calculated from commit time
                    if (fromCommitTime < shardMinCommitTime)
                    {
                        log.debug("#### [DB_ID_RANGE] Skipping transactions from {} to {}",
                                fromCommitTime, shardMinCommitTime);
                        fromCommitTime = shardMinCommitTime;
                    }
                }

                log.debug("#### Get txn from commit time: " + fromCommitTime);
                transactions = getSomeTransactions(txnsFound, fromCommitTime, TIME_STEP_1_HR_IN_MS, 2000,
                                                   state.getTimeToStopIndexing());


                // When transactions are out of Shard range, only the latest transaction needs to be indexed
                // in order to preserve the state up-to-date of the MetadataTracker
                if (shardOutOfRange)
                {
                    Transaction latestTransaction = new Transaction();
                    latestTransaction.setCommitTimeMs(transactions.getMaxTxnCommitTime());
                    latestTransaction.setId(transactions.getMaxTxnId());
                    transactions = new Transactions(
                            Arrays.asList(latestTransaction),
                            transactions.getMaxTxnCommitTime(),
                            transactions.getMaxTxnId());
                    log.debug("#### [DB_ID_RANGE] Latest transaction to be indexed {}", latestTransaction);
                }

                setLastTxCommitTimeAndTxIdInTrackerState(transactions, state);

                log.debug("Scanning transactions ...");
                if (transactions.getTransactions().size() > 0) {
                    log.info(".... from " + transactions.getTransactions().get(0));
                    log.info(".... to " + transactions.getTransactions().get(transactions.getTransactions().size() - 1));
                } else {
                    log.info(".... none found after lastTxCommitTime "
                            + ((txnsFound.size() > 0) ? txnsFound.getLast().getCommitTimeMs() : state
                            .getLastIndexedTxCommitTime()));
                }

                ArrayList<Transaction> txBatch = new ArrayList<>();
                for (Transaction info : transactions.getTransactions()) {

                    /*
                    *  isInIndex is used to ensure transactions that are being re-pulled due to "hole retention" are not re-indexed if
                    *  they have already been indexed.
                    *
                    *  The logic in infoSrv.txnInIndex() first checks an in-memory LRUcache for the txnId. If it doesn't find it in the cache
                    *  it checks the index. The LRUCache is only needed for txnId's that have been indexed but are not yet visible in the index for
                    *  one of two reasons:
                    *
                    *  1) The commit tracker has not yet committed the transaction.
                    *  2) The txnId has been committed to the index but the new searcher has not yet been warmed.
                    *
                    *  This means that to ensure txnId's are not needlessly reprocessed during hole retention, the LRUCache must be large
                    *  enough to cover the time between when a txnId is indexed and when it becomes visible.
                    */

                    boolean isInIndex = (infoSrv.txnInIndex(info.getId(), true) && info.getCommitTimeMs() <= state.getLastIndexedTxCommitTime());
                    if (isInIndex) {
                        txnsFound.add(info);
                    } else {
                        // Make sure we do not go ahead of where we started - we will check the holes here
                        // correctly next time
                        if (info.getCommitTimeMs() > state.getTimeToStopIndexing()) {
                            upToDate = true;
                            break;
                        }

                        txBatch.add(info);
                        if (getUpdateAndDeleteCount(txBatch) > this.transactionDocsBatchSize) {

                            docCount += indexBatchOfTransactions(txBatch);
                            totalUpdatedDocs += docCount;

                            for (Transaction scheduledTx : txBatch) {
                                txnsFound.add(scheduledTx);
                                txsIndexed.add(scheduledTx);
                            }
                            txBatch.clear();
                        }
                    }

                    if (docCount > batchCount) {
                        indexTransactionsAfterAsynchronous(txsIndexed, state);
                        long endElapsed = System.nanoTime();
                        trackerStats.addElapsedNodeTime(docCount, endElapsed - startElapsed);
                        startElapsed = endElapsed;
                        docCount = 0;

                        //Release the write lock allowing the commit tracker to run.
                        this.getWriteLock().release();
                        //Re-acquire the write lock and keep indexing.
                        this.getWriteLock().acquire();
                    }
                    checkShutdown();
                }

                // Index any remaining transactions bringing the index to a consistent state so the CommitTracker can commit if need be.

                if (!txBatch.isEmpty()) {
                    if (this.getUpdateAndDeleteCount(txBatch) > 0) {
                        docCount += indexBatchOfTransactions(txBatch);
                        totalUpdatedDocs += docCount;
                    }

                    for (Transaction scheduledTx : txBatch) {
                        txnsFound.add(scheduledTx);
                        txsIndexed.add(scheduledTx);
                    }
                    txBatch.clear();
                }

                if (txsIndexed.size() > 0) {
                    indexTransactionsAfterAsynchronous(txsIndexed, state);
                    long endElapsed = System.nanoTime();
                    trackerStats.addElapsedNodeTime(docCount, endElapsed - startElapsed);
                    startElapsed = endElapsed;
                    docCount = 0;
                }
            }
            catch(Exception e)
            {
                throw new IOException(e);
            }
            finally
            {
                getWriteLock().release();
            }
        
        }
        while ((transactions.getTransactions().size() > 0) && (upToDate == false));

        log.debug("total number of docs with metadata updated: " + totalUpdatedDocs);
    }

    private void setLastTxCommitTimeAndTxIdInTrackerState(Transactions transactions, TrackerState state)
    {
        Long maxTxnCommitTime = transactions.getMaxTxnCommitTime();
        if (maxTxnCommitTime != null)
        {
            state.setLastTxCommitTimeOnServer(maxTxnCommitTime);
        }

        Long maxTxnId = transactions.getMaxTxnId();
        if (maxTxnId != null)
        {
            state.setLastTxIdOnServer(maxTxnId);
        }
    }

    private void indexTransactionsAfterAsynchronous(HashSet<Transaction> txsIndexed, TrackerState state)
                throws IOException
    {
        waitForAsynchronous();
        for (Transaction tx : txsIndexed)
        {
            super.infoSrv.indexTransaction(tx, true);
            // Transactions are ordered by commit time and tie-broken by tx id
            if (tx.getCommitTimeMs() > state.getLastIndexedTxCommitTime()
                    || tx.getCommitTimeMs() == state.getLastIndexedTxCommitTime()
                    && tx.getId() > state.getLastIndexedTxId())
            {
                state.setLastIndexedTxCommitTime(tx.getCommitTimeMs());
                state.setLastIndexedTxId(tx.getId());
            }
            trackerStats.addTxDocs((int) (tx.getDeletes() + tx.getUpdates()));
        }
        txsIndexed.clear();
        //super.infoSrv.commit();
    }

    private long getUpdateAndDeleteCount(List<Transaction> txs)
    {
        long count = 0;
        for (Transaction tx : txs)
        {
            count += (tx.getUpdates() + tx.getDeletes());
        }
        return count;
    }

    private int indexBatchOfTransactions(List<Transaction> txBatch) throws AuthenticationException, IOException, JSONException
    {
        int nodeCount = 0;
        ArrayList<Transaction> nonEmptyTxs = new ArrayList<>(txBatch.size());
        GetNodesParameters gnp = new GetNodesParameters();
        ArrayList<Long> txIds = new ArrayList<Long>();
        for (Transaction tx : txBatch)
        {
            if (tx.getUpdates() > 0 || tx.getDeletes() > 0)
            {
                nonEmptyTxs.add(tx);
                txIds.add(tx.getId());
            }
        }

        gnp.setTransactionIds(txIds);
        gnp.setStoreProtocol(storeRef.getProtocol());
        gnp.setStoreIdentifier(storeRef.getIdentifier());
        updateShardProperty();
        shardProperty.ifPresent(p -> gnp.setShardProperty(p));

        gnp.setCoreName(coreName);
        List<Node> nodes = client.getNodes(gnp, Integer.MAX_VALUE);

        ArrayList<Node> nodeBatch = new ArrayList<>();
        for (Node node : nodes)
        {
            if (log.isDebugEnabled())
            {
                log.debug(node.toString());
            }
            nodeBatch.add(node);
            if (nodeBatch.size() > nodeBatchSize)
            {
                nodeCount += nodeBatch.size();
                NodeIndexWorkerRunnable niwr = new NodeIndexWorkerRunnable(this.threadHandler, nodeBatch, this.infoSrv);
                this.threadHandler.scheduleTask(niwr);
                nodeBatch = new ArrayList<>();
            }
        }

        if (nodeBatch.size() > 0)
        {
            nodeCount += nodeBatch.size();
            NodeIndexWorkerRunnable niwr = new NodeIndexWorkerRunnable(this.threadHandler, nodeBatch, this.infoSrv);
            this.threadHandler.scheduleTask(niwr);
            nodeBatch = new ArrayList<>();
        }
        return nodeCount;
    }

    class NodeIndexWorkerRunnable extends AbstractWorkerRunnable
    {
        InformationServer infoServer;
        List<Node> nodes;

        NodeIndexWorkerRunnable(QueueHandler queueHandler, List<Node> nodes, InformationServer infoServer)
        {
            super(queueHandler);
            this.infoServer = infoServer;
            this.nodes = nodes;
        }

        @Override
        protected void doWork() throws IOException, AuthenticationException, JSONException
        { 
            List<Node> filteredNodes = filterNodes(nodes);
            if(filteredNodes.size() > 0)
            {
                this.infoServer.indexNodes(filteredNodes, true);
            }
        }
        
        @Override
        protected void onFail()
        {
        	setRollback(true);
        }
        
        private List<Node> filterNodes(List<Node> nodes)
        {
            ArrayList<Node> filteredList = new ArrayList<Node>(nodes.size());
            for(Node node : nodes)
            {

                if(docRouter.routeNode(shardCount, shardInstance, node))
                {
                    filteredList.add(node);
                }
                else if (cascadeTrackerEnabled)
                {
                    if(node.getStatus() == SolrApiNodeStatus.UPDATED)
                    {
                        Node doCascade = new Node();
                        doCascade.setAclId(node.getAclId());
                        doCascade.setId(node.getId());
                        doCascade.setNodeRef(node.getNodeRef());
                        doCascade.setStatus(SolrApiNodeStatus.NON_SHARD_UPDATED);
                        doCascade.setTenant(node.getTenant());
                        doCascade.setTxnId(node.getTxnId());
                        filteredList.add(doCascade);
                    }
                    else // DELETED & UNKNOWN
                    {
                        // Make sure anything no longer relevant to this shard is deleted.
                        Node doDelete = new Node();
                        doDelete.setAclId(node.getAclId());
                        doDelete.setId(node.getId());
                        doDelete.setNodeRef(node.getNodeRef());
                        doDelete.setStatus(SolrApiNodeStatus.NON_SHARD_DELETED);
                        doDelete.setTenant(node.getTenant());
                        doDelete.setTxnId(node.getTxnId());
                        filteredList.add(doDelete);
                    }
                }
            }
            return filteredList;
        }
    }

    @Override
    public NodeReport checkNode(Long dbid)
    {
        NodeReport nodeReport = super.checkNode(dbid);

        // In DB
        GetNodesParameters parameters = new GetNodesParameters();
        parameters.setFromNodeId(dbid);
        parameters.setToNodeId(dbid);
        try
        {
            List<Node> dbnodes = client.getNodes(parameters, 1);
            if (dbnodes.size() == 1)
            {
                Node dbnode = dbnodes.get(0);
                nodeReport.setDbNodeStatus(dbnode.getStatus());
                nodeReport.setDbTx(dbnode.getTxnId());
            }
            else
            {
                nodeReport.setDbNodeStatus(SolrApiNodeStatus.UNKNOWN);
                nodeReport.setDbTx(-1L);
            }
        }
        catch (IOException e)
        {
            nodeReport.setDbNodeStatus(SolrApiNodeStatus.UNKNOWN);
            nodeReport.setDbTx(-2L);
        }
        catch (JSONException e)
        {
            nodeReport.setDbNodeStatus(SolrApiNodeStatus.UNKNOWN);
            nodeReport.setDbTx(-3L);
        }
        catch (AuthenticationException e1)
        {
            nodeReport.setDbNodeStatus(SolrApiNodeStatus.UNKNOWN);
            nodeReport.setDbTx(-4L);
        }

        return nodeReport;
    }

    public NodeReport checkNode(Node node)
    {
        return checkNode(node.getId());
    }

    public List<Node> getFullNodesForDbTransaction(Long txid)
    {
        try
        {
            GetNodesParameters gnp = new GetNodesParameters();
            ArrayList<Long> txs = new ArrayList<Long>();
            txs.add(txid);
            gnp.setTransactionIds(txs);
            gnp.setStoreProtocol(storeRef.getProtocol());
            gnp.setStoreIdentifier(storeRef.getIdentifier());
            gnp.setCoreName(coreName);
            return client.getNodes(gnp, Integer.MAX_VALUE);
        }
        catch (IOException e)
        {
            throw new AlfrescoRuntimeException("Failed to get nodes", e);
        }
        catch (JSONException e)
        {
            throw new AlfrescoRuntimeException("Failed to get nodes", e);
        }
        catch (AuthenticationException e)
        {
            throw new AlfrescoRuntimeException("Failed to get nodes", e);
        }
    }

    public IndexHealthReport checkIndex(Long toTx, Long toAclTx, Long fromTime, Long toTime)
                throws IOException, AuthenticationException, JSONException, EncoderException, NoSuchMethodException
    {
        // DB TX Count
        long firstTransactionCommitTime = 0;
        Transactions firstTransactions = client.getTransactions(null, minTxnIdRange.getFirst(), null, minTxnIdRange.getSecond(), 1);
        if(firstTransactions.getTransactions().size() > 0)
        {
            Transaction firstTransaction = firstTransactions.getTransactions().get(0);
            firstTransactionCommitTime = firstTransaction.getCommitTimeMs();
        }

        IOpenBitSet txIdsInDb = infoSrv.getOpenBitSetInstance();
        Long lastTxCommitTime = Long.valueOf(firstTransactionCommitTime);
        if (fromTime != null)
        {
            lastTxCommitTime = fromTime;
        }
        long maxTxId = 0;
        Long minTxId = null;

        Transactions transactions;
        BoundedDeque<Transaction> txnsFound = new  BoundedDeque<Transaction>(100);
        long endTime = System.currentTimeMillis() + infoSrv.getHoleRetention();
        DO: do
        {
            transactions = getSomeTransactions(txnsFound, lastTxCommitTime, TIME_STEP_1_HR_IN_MS, 2000, endTime);
            for (Transaction info : transactions.getTransactions())
            {
                // include
                if (toTime != null)
                {
                    if (info.getCommitTimeMs() > toTime.longValue())
                    {
                        break DO;
                    }
                }
                if (toTx != null)
                {
                    if (info.getId() > toTx.longValue())
                    {
                        break DO;
                    }
                }

                // bounds for later loops
                if (minTxId == null)
                {
                    minTxId = info.getId();
                }
                if (maxTxId < info.getId())
                {
                    maxTxId = info.getId();
                }

                lastTxCommitTime = info.getCommitTimeMs();
                txIdsInDb.set(info.getId());
                txnsFound.add(info);
            }
        }
        while (transactions.getTransactions().size() > 0);

        return this.infoSrv.reportIndexTransactions(minTxId, txIdsInDb, maxTxId);
    }

    public void addTransactionToPurge(Long txId)
    {
        this.transactionsToPurge.offer(txId);
    }

    public void addNodeToPurge(Long nodeId)
    {
        this.nodesToPurge.offer(nodeId);
    }

    public void addTransactionToReindex(Long txId)
    {
        this.transactionsToReindex.offer(txId);
    }

    public void addNodeToReindex(Long nodeId)
    {
        nodesToReindex.offer(nodeId);
    }

    public void addTransactionToIndex(Long txId)
    {
        transactionsToIndex.offer(txId);
    }

    @Override
    protected void clearScheduledMaintenanceWork()
    {
        logAndClear(transactionsToIndex, "Transactions to be indexed");
        logAndClear(nodesToIndex, "Nodes to be indexed");

        logAndClear(transactionsToReindex, "Transactions to be re-indexed");
        logAndClear(nodesToReindex, "Nodes to be re-indexed");

        logAndClear(transactionsToPurge, "Transactions to be purged");
        logAndClear(nodesToPurge, "Nodes to be purged");
    }

    public void addNodeToIndex(Long nodeId)
    {
        this.nodesToIndex.offer(nodeId);
    }

    public void invalidateState() {
        super.invalidateState();
        infoSrv.clearProcessedTransactions();
    }

    /**
     * @param query
     */
    public void addQueryToReindex(String query)
    {
        this.queriesToReindex.offer(query);
    }


}
