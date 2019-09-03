Run `./launch_pg.sh`

Then within IDE, run `Application`

-------- 


Here's a typical output on my machine, explained:

```
Setting up Database
Dropping Table
sept. 03, 2019 11:24:17 AM io.vertx.sqlclient.impl.SocketConnectionBase
WARNING: Backend notice: severity='NOTICE', code='00000', message='table "huge" does not exist, skipping', detail='null', hint='null', position='null', internalPosition='null', internalQuery='null', where='null', file='tablecmds.c', line='1057', routine='DropErrorMsgNonExistent', schema='null', table='null', column='null', dataType='null', constraint='null'
Create Table
Inserting 100000 items in DB
Counting from table
DB count is 100000
```

This part is the typical setup phase:

100_000 items (simple numbers) are inserted in DB, with primary key, so that access is as fast as possible.
(maybe an even better version of the test would be using `CREATE TEMP TABLE $table ($col BIGINT PRIMARY KEY)` so that each query is even faster)


-------

Then the actual first tests happen.
Two methods are using two different connections obtained from different pools:
* the first one is using pipelining (`pipeliningLimit = Integer.MAX_VALUE`)
* the second one is not (`pipeliningLimit = 1`)
* both are doing the same thing: running 5000 times the same `SELECT $col FROM $table WHERE $col = $1` and aggregating results
* Response time are then measured
* 5000 is a very huge value to emphasize results, obviously in real life we would'nt perform 5000 SELECTs, or we would probably use another strategy like a temporary table `SELECT FROM $table WHERE $col IN (SELECT $col FROM $tmpTable)` (or temporary join table)


```
Running 5000 SELECTs from DB
With pipelining, SELECTs took 1417
Without pipelining, SELECTs took 7580
```
The result shows that with pipelining set up, results are returned faster.
Sounds logical if pipelining is used, there's a single round-trip to the DB. 


------ 
Second test phase.
In this phase, we try to run "multi-selects" (only 100 SELECTS, this time) with a connection using pipelining, but in two different ways.

1. sequentially: we run the 100 selects, wait for results, then continue (100 times)
2. concurrently: we run 100 times the 100 `SELECT` queries sequentially

```
Avg. duration for sequential 21.89ms
Avg. duration for concurrent 976.02ms
```

Results show average time for a single "batched queries".
In this case, it's clear that running sequentially is faster.
It makes sense, because since pipelining is used, the 100*100 concurrent requests are pipelined together. 

--------

Tear-down phase


```
Done
Dropping table
```
