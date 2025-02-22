package org.jetbrains.exposed.sql.transactions

import org.jetbrains.annotations.TestOnly
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.SqlLogger
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.exposedLogger
import org.jetbrains.exposed.sql.statements.api.ExposedConnection
import org.jetbrains.exposed.sql.statements.api.ExposedSavepoint
import java.sql.SQLException
import java.util.concurrent.ThreadLocalRandom

class ThreadLocalTransactionManager(
    private val db: Database,
    private val setupTxConnection: ((ExposedConnection<*>, TransactionInterface) -> Unit)? = null
) : TransactionManager {
    @Volatile
    override var defaultRepetitionAttempts: Int = db.config.defaultRepetitionAttempts
        @Deprecated("Use DatabaseConfig to define the defaultRepetitionAttempts")
        @TestOnly
        set

    @Volatile
    override var defaultMinRepetitionDelay: Long = db.config.defaultMinRepetitionDelay
        @Deprecated("Use DatabaseConfig to define the defaultMinRepetitionDelay")
        @TestOnly
        set

    @Volatile
    override var defaultMaxRepetitionDelay: Long = db.config.defaultMaxRepetitionDelay
        @Deprecated("Use DatabaseConfig to define the defaultMaxRepetitionDelay")
        @TestOnly
        set

    @Volatile
    override var defaultIsolationLevel: Int = db.config.defaultIsolationLevel
        get() {
            if (field == -1) {
                field = Database.getDefaultIsolationLevel(db)
            }
            return field
        }
        @Deprecated("Use DatabaseConfig to define the defaultIsolationLevel")
        @TestOnly
        set

    @Volatile
    override var defaultReadOnly: Boolean = db.config.defaultReadOnly

    val threadLocal = ThreadLocal<Transaction>()

    override fun newTransaction(isolation: Int, readOnly: Boolean, outerTransaction: Transaction?): Transaction {
        val transaction = outerTransaction?.takeIf { !db.useNestedTransactions } ?: Transaction(
            ThreadLocalTransaction(
                db = db,
                readOnly = outerTransaction?.readOnly ?: readOnly,
                transactionIsolation = outerTransaction?.transactionIsolation ?: isolation,
                setupTxConnection = setupTxConnection,
                threadLocal = threadLocal,
                outerTransaction = outerTransaction
            )
        )

        return transaction.apply { bindTransactionToThread(this) }
    }

    override fun currentOrNull(): Transaction? = threadLocal.get()

    override fun bindTransactionToThread(transaction: Transaction?) {
        if (transaction != null) {
            threadLocal.set(transaction)
        } else {
            threadLocal.remove()
        }
    }

    private class ThreadLocalTransaction(
        override val db: Database,
        private val setupTxConnection: ((ExposedConnection<*>, TransactionInterface) -> Unit)?,
        override val transactionIsolation: Int,
        override val readOnly: Boolean,
        val threadLocal: ThreadLocal<Transaction>,
        override val outerTransaction: Transaction?
    ) : TransactionInterface {

        private val connectionLazy = lazy(LazyThreadSafetyMode.NONE) {
            outerTransaction?.connection ?: db.connector().apply {
                setupTxConnection?.invoke(this, this@ThreadLocalTransaction) ?: run {
                    // The order of setters here is important.
                    // Transaction isolation should go first as the readOnly or autoCommit can start transaction with wrong isolation level
                    // Some drivers start a transaction right after `setAutoCommit(false)`,
                    // which makes `setReadOnly` throw an exception if it is called after `setAutoCommit`
                    transactionIsolation = this@ThreadLocalTransaction.transactionIsolation
                    readOnly = this@ThreadLocalTransaction.readOnly
                    autoCommit = false
                }
            }
        }
        override val connection: ExposedConnection<*>
            get() = connectionLazy.value

        private val useSavePoints = outerTransaction != null && db.useNestedTransactions
        private var savepoint: ExposedSavepoint? = if (useSavePoints) {
            connection.setSavepoint(savepointName)
        } else null

        override fun commit() {
            if (connectionLazy.isInitialized()) {
                if (!useSavePoints) {
                    connection.commit()
                } else {
                    // do nothing in nested. close() will commit everything and release savepoint.
                }
            }
        }

        override fun rollback() {
            if (connectionLazy.isInitialized() && !connection.isClosed) {
                if (useSavePoints && savepoint != null) {
                    connection.rollback(savepoint!!)
                    savepoint = connection.setSavepoint(savepointName)
                } else {
                    connection.rollback()
                }
            }
        }

        override fun close() {
            try {
                if (!useSavePoints) {
                    if (connectionLazy.isInitialized()) connection.close()
                } else {
                    savepoint?.let {
                        connection.releaseSavepoint(it)
                        savepoint = null
                    }
                }
            } finally {
                threadLocal.set(outerTransaction)
            }
        }

        private val savepointName: String
            get() {
                var nestedLevel = 0
                var currenTransaction = outerTransaction
                while (currenTransaction?.outerTransaction != null) {
                    nestedLevel++
                    currenTransaction = currenTransaction.outerTransaction
                }
                return "Exposed_savepoint_$nestedLevel"
            }
    }
}

fun <T> transaction(db: Database? = null, statement: Transaction.() -> T): T =
    transaction(
        db.transactionManager.defaultIsolationLevel,
        db.transactionManager.defaultRepetitionAttempts,
        db.transactionManager.defaultReadOnly,
        db,
        db.transactionManager.defaultMinRepetitionDelay,
        db.transactionManager.defaultMaxRepetitionDelay,
        statement
    )

fun <T> transaction(
    transactionIsolation: Int,
    repetitionAttempts: Int,
    readOnly: Boolean = false,
    db: Database? = null,
    minRepetitionDelay: Long = 0,
    maxRepetitionDelay: Long = 0,
    statement: Transaction.() -> T
): T =
    keepAndRestoreTransactionRefAfterRun(db) {
        val outer = TransactionManager.currentOrNull()

        if (outer != null && (db == null || outer.db == db)) {
            val outerManager = outer.db.transactionManager

            val transaction = outerManager.newTransaction(transactionIsolation, readOnly, outer)
            try {
                transaction.statement().also {
                    if (outer.db.useNestedTransactions) {
                        transaction.commit()
                    }
                }
            } finally {
                TransactionManager.resetCurrent(outerManager)
            }
        } else {
            val existingForDb = db?.transactionManager
            existingForDb?.currentOrNull()?.let { transaction ->
                val currentManager = outer?.db.transactionManager
                try {
                    TransactionManager.resetCurrent(existingForDb)
                    transaction.statement().also {
                        if (db.useNestedTransactions) {
                            transaction.commit()
                        }
                    }
                } finally {
                    TransactionManager.resetCurrent(currentManager)
                }
            } ?: inTopLevelTransaction(
                transactionIsolation,
                repetitionAttempts,
                readOnly,
                db,
                null,
                minRepetitionDelay,
                maxRepetitionDelay,
                statement
            )
        }
    }

fun <T> inTopLevelTransaction(
    transactionIsolation: Int,
    repetitionAttempts: Int,
    readOnly: Boolean = false,
    db: Database? = null,
    outerTransaction: Transaction? = null,
    minRepetitionDelay: Long = 0,
    maxRepetitionDelay: Long = 0,
    statement: Transaction.() -> T
): T {

    fun run(): T {
        var repetitions = 0

        val outerManager = outerTransaction?.db.transactionManager.takeIf { it.currentOrNull() != null }

        var intermediateDelay = minRepetitionDelay
        var retryInterval = if (repetitionAttempts > 0) {
             maxOf((maxRepetitionDelay - minRepetitionDelay) / (repetitionAttempts + 1), 1)
        } else 0

        while (true) {
            db?.let { db.transactionManager.let { m -> TransactionManager.resetCurrent(m) } }
            val transaction = db.transactionManager.newTransaction(transactionIsolation, readOnly, outerTransaction)

            @Suppress("TooGenericExceptionCaught")
            try {
                transaction.db.config.defaultSchema?.let { SchemaUtils.setSchema(it) }
                val answer = transaction.statement()
                transaction.commit()
                return answer
            } catch (e: SQLException) {
                handleSQLException(e, transaction, repetitions)
                repetitions++
                if (repetitions >= repetitionAttempts) {
                    throw e
                }
                // set delay value with an exponential backoff time period.
                val delay = when {
                    minRepetitionDelay < maxRepetitionDelay -> {
                        intermediateDelay += retryInterval * repetitions
                        ThreadLocalRandom.current().nextLong(intermediateDelay, intermediateDelay + retryInterval)
                    }
                    minRepetitionDelay == maxRepetitionDelay -> minRepetitionDelay
                    else -> 0
                }
                exposedLogger.warn("Wait $delay milliseconds before retrying")
                try {
                    Thread.sleep(delay)
                } catch (e: InterruptedException) {
                  // Do nothing
                }
            } catch (e: Throwable) {
                val currentStatement = transaction.currentStatement
                transaction.rollbackLoggingException {
                    exposedLogger.warn(
                        "Transaction rollback failed: ${it.message}. Statement: $currentStatement",
                        it
                    )
                }
                throw e
            } finally {
                TransactionManager.resetCurrent(outerManager)
                closeStatementsAndConnection(transaction)
            }
        }
    }

    return keepAndRestoreTransactionRefAfterRun(db) {
        run()
    }
}

private fun <T> keepAndRestoreTransactionRefAfterRun(db: Database? = null, block: () -> T): T {
    val manager = db.transactionManager
    val currentTransaction = manager.currentOrNull()
    return try {
        block()
    } finally {
        manager.bindTransactionToThread(currentTransaction)
    }
}

internal fun handleSQLException(e: SQLException, transaction: Transaction, repetitions: Int) {
    val exposedSQLException = e as? ExposedSQLException
    val queriesToLog = exposedSQLException?.causedByQueries()?.joinToString(";\n") ?: "${transaction.currentStatement}"
    val message = "Transaction attempt #$repetitions failed: ${e.message}. Statement(s): $queriesToLog"
    exposedSQLException?.contexts?.forEach {
        transaction.interceptors.filterIsInstance<SqlLogger>().forEach { logger ->
            logger.log(it, transaction)
        }
    }
    exposedLogger.warn(message, e)
    transaction.rollbackLoggingException { exposedLogger.warn("Transaction rollback failed: ${it.message}. See previous log line for statement", it) }
}

internal fun closeStatementsAndConnection(transaction: Transaction) {
    val currentStatement = transaction.currentStatement
    @Suppress("TooGenericExceptionCaught")
    try {
        currentStatement?.let {
            it.closeIfPossible()
            transaction.currentStatement = null
        }
        transaction.closeExecutedStatements()
    } catch (e: Exception) {
        exposedLogger.warn("Statements close failed", e)
    }
    transaction.closeLoggingException { exposedLogger.warn("Transaction close failed: ${it.message}. Statement: $currentStatement", it) }
}
