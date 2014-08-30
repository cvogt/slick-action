Preview: Automatic connection management in Slick

Currently, working with JDBC connections aka Slick Sessions is only marginally better than with JDBC itself. In particular people struggle with having to either pass the Session everywhere or create sessions everywhere and with leaking sessions across threads/futures or past the session lifetime. Also, Slick currently doesn't help you with deciding where to run queries and transactions in master-slave replication setups.

We are going to change that for Slick 2.2. This is a preview implementation, which is usable with Slick 2.1. The core is actually not even Slick dependent and could be used with ReactiveMongo, Anorm or else. The following features are supported (many specific to Slick, but some may be able to be generalized):

- composition of multiple queries and arbitrary Scala code as "Action"s
  similar to how slick already allows composing pure "Query"s
  - uses for-expressions/.map/.flatMap for composition
  - does not require a database configuration or connection
  - database configuration or connection only required for execution,
    so you can limit who is allowed to do that
- minimal resource usage
  - by default acquires an individual connection for each 
    query/transaction and closes it immediately after
    (should be used with a connection pool)
  - allows forcing the same connection for multiple queries
- type-safety
  - tracking of read/write/async queries through your app
  - prevents passing sessions across threads
  - prevents leaking sessions past lifetime
  - prevents transactions over multiple data sources
  - "unsafe" feature creating side-effects on the jdbc connection
- optional master/slave support
  - when not in a transaction, automatically use slave for each 
    read, master for each write
  - for a transaction, automatic use slave if only reads are 
    involved, otherwise use master
- multi-db support: composition/tracking distinguishing multiple dbs
- simplistic async support

See tests for usage examples: https://github.com/cvogt/slick-action/tree/master/src/test/scala/org/cvogt/action

The implementation uses something similar to the FP "Reader" design pattern, for those who know it.

It hasn't been reviewed within the Slick and Play teams and it obviously hasn't been battle tested. Expect bugs and things to change to better integrate it with Play, the upcoming Async support in Slick 2.2 and general feedback. Feel free to open tickets and send PRs.
