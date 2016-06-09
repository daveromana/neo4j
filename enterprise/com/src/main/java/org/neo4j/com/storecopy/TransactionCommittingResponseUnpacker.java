/*
 * Copyright (c) 2002-2016 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
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
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.com.storecopy;

import java.io.IOException;

import org.neo4j.com.ComException;
import org.neo4j.com.Response;
import org.neo4j.com.TransactionStream;
import org.neo4j.com.TransactionStreamResponse;
import org.neo4j.function.Supplier;
import org.neo4j.helpers.collection.Visitor;
import org.neo4j.kernel.KernelHealth;
import org.neo4j.kernel.api.KernelTransaction;
import org.neo4j.kernel.api.exceptions.Status;
import org.neo4j.kernel.impl.api.BatchingTransactionRepresentationStoreApplier;
import org.neo4j.kernel.impl.api.KernelTransactionImplementation;
import org.neo4j.kernel.impl.api.KernelTransactions;
import org.neo4j.kernel.impl.api.TransactionRepresentationStoreApplier;
import org.neo4j.kernel.impl.api.index.IndexUpdatesValidator;
import org.neo4j.kernel.impl.api.index.ValidatedIndexUpdates;
import org.neo4j.kernel.impl.locking.LockGroup;
import org.neo4j.kernel.impl.logging.LogService;
import org.neo4j.kernel.impl.transaction.CommittedTransactionRepresentation;
import org.neo4j.kernel.impl.transaction.TransactionRepresentation;
import org.neo4j.kernel.impl.transaction.log.Commitment;
import org.neo4j.kernel.impl.transaction.log.LogFile;
import org.neo4j.kernel.impl.transaction.log.TransactionAppender;
import org.neo4j.kernel.impl.transaction.log.TransactionIdStore;
import org.neo4j.kernel.impl.transaction.log.rotation.LogRotation;
import org.neo4j.kernel.impl.transaction.tracing.LogAppendEvent;
import org.neo4j.kernel.impl.util.Access;
import org.neo4j.kernel.impl.util.UnsatisfiedDependencyException;
import org.neo4j.kernel.lifecycle.Lifecycle;
import org.neo4j.logging.Log;

import static org.neo4j.kernel.impl.api.TransactionApplicationMode.EXTERNAL;

/**
 * Receives and unpacks {@link Response responses}.
 * Transaction obligations are handled by {@link TransactionObligationFulfiller} and
 * {@link TransactionStream transaction streams} are {@link TransactionRepresentationStoreApplier applied to the
 * store},
 * in batches.
 * <p/>
 * It is assumed that any {@link TransactionStreamResponse response carrying transaction data} comes from the one
 * and same thread.
 */
public class TransactionCommittingResponseUnpacker implements ResponseUnpacker, Lifecycle
{
    public interface Dependencies
    {
        BatchingTransactionRepresentationStoreApplier transactionRepresentationStoreApplier();

        IndexUpdatesValidator indexUpdatesValidator();

        LogFile logFile();

        LogRotation logRotation();

        KernelHealth kernelHealth();

        // Components that change during role switches

        Supplier<TransactionObligationFulfiller> transactionObligationFulfiller();

        Supplier<TransactionAppender> transactionAppender();

        KernelTransactions kernelTransactions();

        LogService logService();

        long idReuseSafeZoneTime();
    }

    public static final int DEFAULT_BATCH_SIZE = 100;
    private final TransactionQueue transactionQueue;
    // Visits all queued transactions, committing them
    private final TransactionVisitor batchCommitter = new TransactionVisitor()
    {
        @Override
        public void visit( CommittedTransactionRepresentation transaction, TxHandler handler,
                Access<Commitment> commitmentAccess ) throws IOException
        {
            // Tuck away the Commitment returned from the call to append. We'll use each Commitment right before
            // applying each transaction.
            Commitment commitment = appender.append( transaction.getTransactionRepresentation(),
                    transaction.getCommitEntry().getTxId() );
            commitmentAccess.set( commitment );
        }
    };
    // Visits all queued, and recently appended, transactions, applying them to the store
    private final TransactionVisitor batchApplier = new TransactionVisitor()
    {
        @Override
        public void visit( CommittedTransactionRepresentation transaction, TxHandler handler,
                Access<Commitment> commitmentAccess ) throws IOException
        {
            long transactionId = transaction.getCommitEntry().getTxId();
            TransactionRepresentation representation = transaction.getTransactionRepresentation();
            commitmentAccess.get().publishAsCommitted();
            try ( LockGroup locks = new LockGroup();
                  ValidatedIndexUpdates indexUpdates = indexUpdatesValidator.validate( representation ) )
            {
                storeApplier.apply( representation, indexUpdates, locks, transactionId, EXTERNAL );
                handler.accept( transaction );
            }
        }
    };
    private final TransactionVisitor batchCloser = new TransactionVisitor()
    {
        @Override
        public void visit( CommittedTransactionRepresentation transaction, TxHandler handler,
                Access<Commitment> commitmentAccess ) throws IOException
        {
            Commitment commitment = commitmentAccess.get();
            if ( commitment.markedAsCommitted() )
            {
                commitment.publishAsApplied();
            }
        }
    };

    static final String msg = "Kernel panic detected: pulled transactions cannot be applied to a non-healthy database. "
            + "In order to resolve this issue a manual restart of this instance is required.";

    private final Dependencies dependencies;
    private TransactionAppender appender;
    private BatchingTransactionRepresentationStoreApplier storeApplier;
    private IndexUpdatesValidator indexUpdatesValidator;
    private TransactionObligationFulfiller obligationFulfiller;
    private LogFile logFile;
    private LogRotation logRotation;
    private KernelHealth kernelHealth;
    private Log log;
    private KernelTransactions kernelTransactions;
    private long idReuseSafeZoneTime;
    private volatile boolean stopped;

    public TransactionCommittingResponseUnpacker( Dependencies dependencies )
    {
        this( dependencies, DEFAULT_BATCH_SIZE );
    }

    public TransactionCommittingResponseUnpacker( Dependencies dependencies, int maxBatchSize )
    {
        this.dependencies = dependencies;
        this.transactionQueue = new TransactionQueue( maxBatchSize );
    }

    private static TransactionObligationFulfiller resolveTransactionObligationFulfiller(
            Supplier<TransactionObligationFulfiller> supplier)
    {
        try
        {
            return supplier.get();
        }
        catch ( UnsatisfiedDependencyException e )
        {
            return new TransactionObligationFulfiller()
            {
                @Override
                public void fulfill( long toTxId )
                {
                    throw new UnsupportedOperationException( "Should not be called" );
                }
            };
        }
    }

    @Override
    public void unpackResponse( Response<?> response, final TxHandler txHandler ) throws IOException
    {
        if ( stopped )
        {
            throw new IllegalStateException( "Component is currently stopped" );
        }

        try
        {
            response.accept( new BatchingResponseHandler( txHandler ) );
        }
        finally
        {
            if ( response.hasTransactionsToBeApplied() )
            {
                applyQueuedTransactions();
            }
        }
    }

    private void applyQueuedTransactions() throws IOException
    {
        if ( transactionQueue.isEmpty() )
        {
            return;
        }

        /**
         * Problem 1 (Not really a problem but we thought it where)
         *  - chunk of batch is smaller than safe zone
         *  - tx started after activeTransactions() is called
         *  is safe because those transactions will see the latest state of store before chunk is applied and
         *  because chunk is smaller than safe zone we are guarantied to not see two different states of any record
         *  when applying the chunk.
         *
         *    activeTransactions() is called
         *    |        start committing chunk
         * ---|----+---|--|------> TIME
         *         |      |
         *         |      Start applying chunk
         *         New tx starts here. Does not get terminated because not among active transactions, this is safe.
         *
         * Problem 2
         *  - chunk of batch is larger than safe zone
         *  - tx started after activeTransactions() but before apply
         *
         *    activeTransactions() is called
         *    |        start committing chunk
         * ---|--------|+-|------> TIME
         *              | |
         *              | Start applying chunk
         *              New tx starts here. Does not get terminated because not among active transactions, but will read
         *              outdated data and can be affected by reuse contamination.
         */
        // Check timestamp on last transaction in queue
        long newLatestAppliedTime = transactionQueue.last().getCommitEntry().getTimeWritten();
        long chunkLength =  newLatestAppliedTime - transactionQueue.first().getCommitEntry().getTimeWritten();

        // We stop new transactions from starting to avoid problem 1
        boolean feezeActiveTx = false;
        if ( chunkLength > idReuseSafeZoneTime )
        {
            // Problem 2
            kernelTransactions.freezeActiveTx();
            feezeActiveTx = true;
        }
        try
        {
            for ( KernelTransaction tx : kernelTransactions.activeTransactions() )
            {
                long commitTimestamp = ((KernelTransactionImplementation) tx).lastTransactionTimestampWhenStarted();
                if ( commitTimestamp != TransactionIdStore.BASE_TX_COMMIT_TIMESTAMP &&
                     commitTimestamp < newLatestAppliedTime - idReuseSafeZoneTime )
                {
                    tx.markForTermination( Status.Transaction.Outdated );
                }
            }

            // Synchronize to guard for concurrent shutdown
            synchronized ( logFile )
            {
                // Check rotation explicitly, since the version of append that we're calling isn't doing that.
                logRotation.rotateLogIfNeeded( LogAppendEvent.NULL );

                // Check kernel health after log rotation
                if ( !kernelHealth.isHealthy() )
                {
                    Throwable causeOfPanic = kernelHealth.getCauseOfPanic();
                    log.error( msg + " Original kernel panic cause was:\n" + causeOfPanic.getMessage() );
                    throw new IOException( msg, causeOfPanic );
                }

                try
                {
                    // Apply whatever is in the queue
                    if ( transactionQueue.accept( batchCommitter ) > 0 ) // COMMIT
                    {
                        // TODO if this instance is set to "slave_only" then we can actually skip the force call here.
                        // Reason being that even if there would be a reordering in some layer where a store file would be

                        // changed before that change would have ended up in the log, it would be fine sine as a slave
                        // you would pull that transaction again anyhow before making changes to (after reading) any
                        // record.
                        appender.force();
                        try
                        {
                            // Apply all transactions to the store. Only apply, i.e. mark as committed, not closed.
                            // We mark as closed below.
                            transactionQueue.accept( batchApplier ); // APPLY
                            // Ensure that all changes are flushed to the store, we're doing some batching of transactions
                            // here so some shortcuts are taken in places. Although now comes the snapshotTime where we must
                            // ensure that all pending changes are applied and flushed properly.
                            storeApplier.closeBatch();
                        }
                        finally
                        {
                            // Mark the applied transactions as closed. We must do this as a separate step after
                            // applying them, with a closeBatch() call in between, otherwise there might be
                            // threads waiting for transaction obligations to be fulfilled and since they are looking
                            // at last closed transaction id they might get notified to continue before all data
                            // has actually been flushed properly.
                            transactionQueue.accept( batchCloser ); // MARK TXs AS CLOSED
                        }
                    }
                }
                catch ( Throwable cause )
                {

                    kernelHealth.panic( cause );
                    throw cause;
                }
                finally
                {
                    transactionQueue.clear();
                }
            }
        }
        finally
        {
            if ( feezeActiveTx )
            {
                feezeActiveTx = false;
                kernelTransactions.unfreezeActiveTx();
            }
        }
    }

    @Override
    public void init() throws Throwable
    {   // Nothing to init
    }

    @Override
    public void start() throws Throwable
    {
        this.appender = dependencies.transactionAppender().get();
        this.storeApplier = dependencies.transactionRepresentationStoreApplier();
        this.indexUpdatesValidator = dependencies.indexUpdatesValidator();
        this.obligationFulfiller = resolveTransactionObligationFulfiller( dependencies.transactionObligationFulfiller() );
        this.logFile = dependencies.logFile();
        this.logRotation = dependencies.logRotation();
        this.kernelHealth = dependencies.kernelHealth();
        this.log = dependencies.logService().getInternalLogProvider().getLog( getClass() );
        this.kernelTransactions = dependencies.kernelTransactions();
        this.idReuseSafeZoneTime = dependencies.idReuseSafeZoneTime();
        this.stopped = false;
    }

    @Override
    public void stop() throws Throwable
    {
        this.stopped = true;
    }

    @Override
    public void shutdown() throws Throwable
    {   // Nothing to shut down
    }

    private class BatchingResponseHandler implements Response.Handler,
            Visitor<CommittedTransactionRepresentation,IOException>
    {
        private final TxHandler txHandler;

        private BatchingResponseHandler( TxHandler txHandler )
        {
            this.txHandler = txHandler;
        }

        @Override
        public void obligation( long txId ) throws IOException
        {
            if ( txId == TransactionIdStore.BASE_TX_ID )
            {   // Means "empty" response
                return;
            }

            try
            {
                obligationFulfiller.fulfill( txId );
            }
            catch ( IllegalStateException e )
            {
                throw new ComException( "Failed to pull updates", e )
                        .traceComException( log, "BatchingResponseHandler.obligation" );
            }
            catch ( InterruptedException e )
            {
                throw new IOException( e );
            }
        }

        @Override
        public Visitor<CommittedTransactionRepresentation,IOException> transactions()
        {
            return this;
        }

        @Override
        public boolean visit( CommittedTransactionRepresentation transaction ) throws IOException
        {
            // TODO:
            //      - Change implementation of "freeze active transactions". How to block new transactions from starting
            //      - Maybe move from time -> tx ids as safe zone (or support both)
            //      - Flush buffered ids to disk (if memory intrusive)
            //      - Only do assertOpen after property reads if we are slave
            //      - Place all comments describing the problem / solution in a better place
            //      - Clean up how we handle Transient...Exception, TxTerminatedException, etc. + how we use Status.*

            // PROBLEM
            // A slave can read inconsistent or corrupted data (mixed state records) because of reuse of property ids.
            // This happens when a record that has been read gets reused and then read again or possibly reused in
            // middle of reading a property chain or dynamic record chain.
            // This is guarded for in single instance with the delaying of id reuse. This does not cover the Slave
            // case because the transactions that SET, REMOVE and REUSE the record are applied in batch and thus a
            // slave transaction can see states from all of the transactions that touches the reused record during its
            // lifetime.
            //
            // PROTOTYPED SOLUTION
            // Master and Slave agree on quarantine snapshotTime.
            // Let T = quarantine snapshotTime (more about quarantine snapshotTime further down)
            //
            // -> Master promise to hold all deleted ids in quarantine before reusing them, (T duration).
            //      He thereby creates a safe zone of transactions that among themselves are guaranteed to be free of
            //      id reuse contamination.
            // -> Slave promise to not let any transactions cross the safe zone boundary.
            //      Meaning all transactions that falls out of the safe zone, as updates gets applied,
            //      will need to be terminated, with a hint that they can simply be restarted
            //
            // Safe zone is a snapshotTime frame in Masters domain. All transactions that started and finished within this
            // snapshotTime frame are guarantied to not have read any mixed state records.
            //
            // LARGE ASCII EXAMPLE
            //
            //    x---------------------------------------------------------------------------------->| TIME
            //    |MASTER STATE
            //    |---------------------------------------------------------------------------------->|
            //    |                                                          Batch to apply to slave
            //    |                                                        |<------------------------>|
            //    |
            //    |                                                        A
            //    |SLAVE STATE 1 (before applying batch)         |<---T--->|
            //    |----------------------------------------------+-------->|
            //    |                                                        |
            //    |                                                        |
            //    |                                                        |      B
            //    |SLAVE STATE 2 (mid apply)                            |<-+-T--->|
            //    |-----------------------------------------------------+--+----->|
            //    |                                                        |      |
            //    |                                                        |      |
            //    |                                                        |      |  C
            //    |SLAVE STATE 3 (mid apply / after apply)                 |<---T-+->|
            //    |--------------------------------------------------------+------+->|
            //    |                                                        |      |  |
            //    |                                                        |      |  |
            //    |                                                        |      |  |                D
            //    |SLAVE STATE 4 (after apply)                             |      |  |      |<---T--->|
            //    |--------------------------------------------------------+------+--+------+-------->|
            //
            // * Tx start on slave when slave is in SLAVE STATE 1
            //      - Latest applied transaction has timestamp A and safe zone is A-T.
            //      - Tx.startTime = A
            //
            // Scenario 1 - Tx finish when slave is in SLAVE STATE 2
            //      Latest applied transaction in store has timestamp B and safe zone is B-T.
            //      Tx did not cross the safe zone boundary as Tx.startTime = A > B-T
            //      We can safely assume that Tx did not read any mixed state records.
            //
            // Scenario 2 - Tx has not yet finished in SLAVE STATE 3
            //      Latest applied transaction in store has timestamp C and safe zone is C-T.
            //      We are just about to apply the next part of the batch and push the safe zone window forward.
            //      This will make Tx.startTime = A < C-T. This means Tx is now in risk of reading mixed state records.
            //      We will terminate Tx and let the user try again.
            //
            //
            // small ASCII example
            //                              BATCH pulled by slave
            //                          _________________________
            //                         |                         |
            // |-----------------------|------------------------>|  MASTER
            //
            //  Three slave states, S1, S2 and S3
            //                               ____________________
            //                      ________|_______S3 safe zone |
            //           __________|___S2 safe zone |            |
            //          | S1 safe zone |    |       |            |
            // |--------|----------|-->| ---|------>|----------->|  SLAVE
            //
            // tx started in S1 can finish in S2 but not in S3
            // tx started in S2 can finish in S3.

            if ( transactionQueue.queue( transaction, txHandler ) )
            {
                applyQueuedTransactions();
            }
            return false;
        }
    }
}
