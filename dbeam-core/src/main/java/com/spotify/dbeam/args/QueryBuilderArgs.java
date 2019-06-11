/*-
 * -\-\-
 * DBeam Core
 * --
 * Copyright (C) 2016 - 2019 Spotify AB
 * --
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * -/-/-
 */

package com.spotify.dbeam.args;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;

import com.google.auto.value.AutoValue;
import com.google.common.collect.Lists;
import java.io.Serializable;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.joda.time.DateTime;
import org.joda.time.Days;
import org.joda.time.LocalDate;
import org.joda.time.ReadablePeriod;

/**
 * A POJO describing how to create queries for DBeam exports.
 */
@AutoValue
public abstract class QueryBuilderArgs implements Serializable {

  public abstract String tableName();

  public abstract SqlQueryWrapper baseSqlQuery();

  public abstract Optional<Integer> limit();

  public abstract Optional<String> partitionColumn();

  public abstract Optional<DateTime> partition();

  public abstract ReadablePeriod partitionPeriod();

  public abstract Optional<String> splitColumn();

  public abstract Optional<Integer> queryParallelism();

  public abstract Builder builder();

  @AutoValue.Builder
  public abstract static class Builder {

    public abstract Builder setTableName(String tableName);

    public abstract Builder setBaseSqlQuery(SqlQueryWrapper baseSqlQuery);

    public abstract Builder setLimit(Integer limit);

    public abstract Builder setLimit(Optional<Integer> limit);

    public abstract Builder setPartitionColumn(String partitionColumn);

    public abstract Builder setPartitionColumn(Optional<String> partitionColumn);

    public abstract Builder setPartition(DateTime partition);

    public abstract Builder setPartition(Optional<DateTime> partition);

    public abstract Builder setPartitionPeriod(ReadablePeriod partitionPeriod);

    public abstract Builder setSplitColumn(String splitColumn);

    public abstract Builder setSplitColumn(Optional<String> splitColumn);

    public abstract Builder setQueryParallelism(Integer parallelism);

    public abstract Builder setQueryParallelism(Optional<Integer> queryParallelism);


    public abstract QueryBuilderArgs build();
  }

  private static Boolean checkTableName(String tableName) {
    return tableName.matches("^[a-zA-Z_][a-zA-Z0-9_]*$");
  }

  public static QueryBuilderArgs create(String tableName) {
    checkArgument(tableName != null,
            "TableName cannot be null");
    SqlQueryWrapper baseSqlQuery = getBaseSqlQuery(tableName, Optional.empty());
    return new AutoValue_QueryBuilderArgs.Builder()
            .setTableName(tableName)
            .setBaseSqlQuery(baseSqlQuery)
            .setPartitionPeriod(Days.ONE)
            .build();
  }

  public static QueryBuilderArgs create(String tableName, final Optional<String> sqlQueryOpt) {
    checkArgument(tableName != null,
            "TableName cannot be null");
    SqlQueryWrapper baseSqlQuery = getBaseSqlQuery(tableName, sqlQueryOpt);
    return new AutoValue_QueryBuilderArgs.Builder()
        .setTableName(tableName)
        .setBaseSqlQuery(baseSqlQuery)
        .setPartitionPeriod(Days.ONE)
        .build();
  }

  private static SqlQueryWrapper getBaseSqlQuery(String tableName, Optional<String> sqlQueryOpt) {
    checkArgument(checkTableName(tableName), "'table' must follow [a-zA-Z_][a-zA-Z0-9_]*");
    if (!sqlQueryOpt.isPresent()) {
      return SqlQueryWrapper.ofTablename(tableName);
    } else {
      String sqlQuery = sqlQueryOpt.get();
      checkArgument(SqlQueryWrapper.checkSqlQuery(sqlQuery), "Invalid SQL query");
      return SqlQueryWrapper.ofRawSql(sqlQuery);
    }
  }

  /**
   * Create queries to be executed for the export job.
   *
   * @param connection A connection which is used to determine limits for parallel queries.
   * @return A list of queries to be executed.
   * @throws SQLException when it fails to find out limits for splits.
   */
  public Iterable<String> buildQueries(Connection connection)
      throws SQLException {
    checkArgument(!queryParallelism().isPresent() || splitColumn().isPresent(),
        "Cannot use queryParallelism because no column to split is specified. "
            + "Please specify column to use for splitting using --splitColumn");
    checkArgument(queryParallelism().isPresent() || !splitColumn().isPresent(),
        "argument splitColumn has no effect since --queryParallelism is not specified");
    queryParallelism().ifPresent(p -> checkArgument(p > 0,
        "Query Parallelism must be a positive number. Specified queryParallelism was %s", p));

    final String limit =
        this.limit().map(l -> SqlQueryWrapper.createSqlLimitCondition(l)).orElse("");

    final String partitionCondition =
        this.partitionColumn()
            .flatMap(
                partitionColumn ->
                    this.partition()
                        .map(
                            partition -> {
                              final LocalDate datePartition = partition.toLocalDate();
                              final String nextPartition =
                                  datePartition.plus(partitionPeriod()).toString();
                              return SqlQueryWrapper.createSqlPartitionCondition(
                                  partitionColumn, datePartition.toString(), nextPartition);
                            }))
            .orElse("");

    if (queryParallelism().isPresent() && splitColumn().isPresent()) {

      long[] minMax = findInputBounds(connection, this.baseSqlQuery(), partitionCondition,
          splitColumn().get());
      long min = minMax[0];
      long max = minMax[1];

      String limitWithParallelism =
          this.limit()
              .map(l -> SqlQueryWrapper.createSqlLimitCondition(l / queryParallelism().get()))
              .orElse("");
      String queryFormat = String
          .format("%s%s%s%s",
                  this.baseSqlQuery(),
                  partitionCondition,
                  "%s", // the split conditions
                  limitWithParallelism);

      return queriesForBounds(min, max, queryParallelism().get(), splitColumn().get(), queryFormat);
    } else {
      return Lists.newArrayList(
          String.format("%s%s%s", this.baseSqlQuery(), partitionCondition, limit));
    }
  }

  /**
   * Helper function which finds the min and max limits for the given split column with the
   * partition conditions.
   *
   * @return A long array of two elements, with [0] being min and [1] being max.
   * @throws SQLException when there is an exception retrieving the max and min fails.
   */
  private long[] findInputBounds(
      Connection connection, SqlQueryWrapper baseSqlQuery,
      String partitionCondition, String splitColumn)
      throws SQLException {
    String minColumnName = "min_s";
    String maxColumnName = "max_s";
    SqlQueryWrapper queryWrapper = baseSqlQuery.generateQueryToGetLimitsOfSplitColumn(
            splitColumn, minColumnName, maxColumnName, partitionCondition);
    long min;
    long max;
    try (Statement statement = connection.createStatement()) {
      final ResultSet
          resultSet =
          statement.executeQuery(queryWrapper.getSqlQuery());
      // Check and make sure we have a record. This should ideally succeed always.
      checkState(resultSet.next(), "Result Set for Min/Max returned zero records");

      // minColumnName and maxColumnName would be both of the same type
      switch (resultSet.getMetaData().getColumnType(1)) {
        case Types.LONGVARBINARY:
        case Types.BIGINT:
        case Types.INTEGER:
          min = resultSet.getLong(minColumnName);
          // TODO
          // check resultSet.wasNull(); NULL -> 0L
          // there is no value to carry on since it will be empty set anyway 
          max = resultSet.getLong(maxColumnName);
          break;
        default:
          throw new IllegalArgumentException("splitColumn should be of type Integer / Long");
      }
    }

    return new long[]{min, max};
  }

  /**
   * Given a min, max and expected queryParallelism, generate all required queries that should be
   * executed.
   */
  protected static Iterable<String> queriesForBounds(long min, long max, int parallelism,
      String splitColumn,
      String queryFormat) {
    // We try not to generate more than queryParallelism. Hence we don't want to loose number by
    // rounding down. Also when queryParallelism is higher than max - min, we don't want 0 queries
    long bucketSize = (long) Math.ceil((double) (max - min) / (double) parallelism);
    bucketSize =
        bucketSize == 0 ? 1 : bucketSize; // If max and min is same, we export only 1 query
    List<String> queries = new ArrayList<>(parallelism);

    String parallelismCondition;
    long i = min;
    while (i + bucketSize < max) {

      // Include lower bound and exclude the upper bound.
      parallelismCondition =
        SqlQueryWrapper.createSqlSplitCondition(
                splitColumn, i, i + bucketSize, true);
      queries.add(String
          .format(queryFormat, parallelismCondition));
      i = i + bucketSize;
    }

    // Add last query
    if (i + bucketSize >= max) {
      // If bucket size exceeds max, we must use max and the predicate
      // should include upper bound.
      parallelismCondition =
        SqlQueryWrapper.createSqlSplitCondition(
                splitColumn, i, max, false);
      queries.add(String
          .format(queryFormat, parallelismCondition));
    }

    // If queryParallelism is higher than max-min, this will generate less queries.
    // But lets never generate more queries.
    checkState(queries.size() <= parallelism,
        "Unable to generate expected number of queries for given min max.");

    return queries;
  }

}
