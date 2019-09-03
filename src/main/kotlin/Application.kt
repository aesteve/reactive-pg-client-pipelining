import io.reactivex.Completable
import io.reactivex.Observable
import io.reactivex.Single
import io.reactivex.functions.BiFunction
import io.vertx.pgclient.PgConnectOptions
import io.vertx.reactivex.core.RxHelper
import io.vertx.reactivex.core.Vertx
import io.vertx.reactivex.pgclient.PgPool
import io.vertx.reactivex.sqlclient.Row
import io.vertx.reactivex.sqlclient.RowSet
import io.vertx.reactivex.sqlclient.SqlConnection
import io.vertx.reactivex.sqlclient.Tuple
import io.vertx.sqlclient.PoolOptions

object Application {

    val vertx = Vertx.vertx()

    private val setupOpts = getOpts(Integer.MAX_VALUE)
    private val setupPool = PgPool.pool(vertx, setupOpts, PoolOptions().setMaxSize(1))
    private val setupConn = setupPool.rxGetConnection().blockingGet()

    private val noPipeliningOpts = getOpts(1)
    private val noPipeliningPool = PgPool.pool(vertx, noPipeliningOpts, PoolOptions().setMaxSize(10))

    private val maxPipeliningOpts = getOpts(Integer.MAX_VALUE)
    private val maxPipeliningPool = PgPool.pool(vertx, maxPipeliningOpts, PoolOptions().setMaxSize(10))

    private const val table = "huge"
    private const val col = "the_column"
    private const val nbItems = 100_000
    private const val nbItemsToSelectForPipelining = 5000
    private const val nbItemsToSelectForConcurrSequential = 100

    @JvmStatic
    fun main(args: Array<String>) {
        setupDB()
            .toSingleDefault(0L)
            .flatMap {
                println("Counting from table")
                setupConn.rxPreparedQuery("SELECT count(*) as c FROM $table")
                    .map { it.first().getInteger("c") }
            }
            .map {
                println("DB count is $nbItems")
                assert(it == nbItems)
            }
            .flatMap(::runUnitTest)
            .flatMap(::runSequentialTests)
            .map { println("Avg. duration for sequential ${it}ms");0.0 }
            .flatMap(::runConcurrentTests)
            .map { println("Avg. duration for concurrent ${it}ms");0.0 }
            .subscribeOn(RxHelper.scheduler(vertx))
            .doFinally {
                tearDownDB()
                    .subscribeOn(RxHelper.scheduler(vertx))
                    .subscribe()
            }
            .subscribe({
                println("Done")
            }, { it.printStackTrace() })
    }

    private fun runUnitTest(u: Unit): Single<Double> {
        val toSelect = (1..nbItemsToSelectForPipelining).toList()
        println("Running ${toSelect.size} SELECTs from DB")
        var before = System.currentTimeMillis()
        return executeQueries(maxPipeliningPool, toSelect)
            .flatMap {
                assert(it.toList().toSet() == toSelect.toSet())
                println("With pipelining, SELECTs took ${System.currentTimeMillis() - before}")
                before = System.currentTimeMillis()
                executeQueries(noPipeliningPool, toSelect)
            }
            .map {
                assert(it.toList().toSet() == toSelect.toSet())
                println("Without pipelining, SELECTs took ${System.currentTimeMillis() - before}")
                0.0
            }
    }

    private fun runSequentialTests(d: Double): Single<Double> {
        val before = System.currentTimeMillis()
        val toSelect = (1..nbItemsToSelectForConcurrSequential).toList()
        val nbRuns = 100
        for (i in 1..nbRuns) {
            executeQueries(maxPipeliningPool, toSelect).blockingGet()
        }
        return Single.just((System.currentTimeMillis() - before).toDouble() / nbRuns)
    }


    private fun runConcurrentTests(d: Double): Single<Double> {
        val toSelect = (1..nbItemsToSelectForConcurrSequential).toList()
        val nbRuns = 100
        return Observable
            .fromIterable(1..nbRuns)
            .flatMapSingle {
                val before = System.currentTimeMillis()
                executeQueries(maxPipeliningPool, toSelect)
                    .map { System.currentTimeMillis() - before }
            }
            .reduce(listOf(), timesAgg)
            .map(List<Long>::average)
    }

    private val timesAgg: BiFunction<List<Long>, Long, List<Long>> = BiFunction { list, time ->
        list + time
    }

    private fun getOpts(pipeliningLimit: Int) =
        PgConnectOptions()
            .setPipeliningLimit(pipeliningLimit)
            .setCachePreparedStatements(true)
            .setHost("localhost")
            .setPort(5432)
            .setUser("postgres")
            .setPassword("mysecretpassword")
            .setDatabase("postgres")

    private fun executeQueries(pool: PgPool, toSelect: List<Int>): Single<List<Row>> {
        var conn: SqlConnection? = null
        return pool.rxGetConnection()
            .flatMap {
                conn = it
                Observable
                    .fromIterable(toSelect)
                    .flatMapSingle {
                        conn?.rxPreparedQuery("SELECT $col FROM $table WHERE $col = $1", Tuple.of(it))
                    }
                    .reduce(listOf(), agg)
            }
            .doFinally { conn?.close() }
    }

    private val agg: BiFunction<List<Row>, RowSet, List<Row>> = BiFunction { list, rs ->
        list + rs
    }

    private fun setupDB(): Completable {
        println("Setting up Database")
        println("Dropping Table")
        return setupConn
            .rxPreparedQuery("DROP TABLE IF EXISTS $table")
            .flatMap {
                println("Create Table")
                setupConn.rxPreparedQuery("CREATE TABLE $table ($col BIGINT PRIMARY KEY)")
            }
            .flatMap {
                println("Inserting $nbItems items in DB")
                setupConn.rxPreparedBatch("INSERT INTO $table ($col) VALUES ($1)", (1..nbItems).map(Tuple::of))
            }
            .ignoreElement()
    }

    private fun tearDownDB(): Completable {
        println("Dropping table")
        return setupConn.rxPreparedQuery("DROP TABLE IF EXISTS $table").ignoreElement()
    }


}
