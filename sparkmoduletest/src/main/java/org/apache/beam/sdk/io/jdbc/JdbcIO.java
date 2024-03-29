package org.apache.beam.sdk.io.jdbc;

import com.google.auto.value.AutoValue;
import org.apache.beam.sdk.annotations.Experimental;
import org.apache.beam.sdk.coders.Coder;
import org.apache.beam.sdk.options.ValueProvider;
import org.apache.beam.sdk.transforms.*;
import org.apache.beam.sdk.transforms.display.DisplayData;
import org.apache.beam.sdk.util.BackOff;
import org.apache.beam.sdk.util.BackOffUtils;
import org.apache.beam.sdk.util.FluentBackoff;
import org.apache.beam.sdk.util.Sleeper;
import org.apache.beam.sdk.values.PBegin;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.PCollectionView;
import org.apache.beam.sdk.values.PDone;
import org.apache.commons.dbcp2.BasicDataSource;
import org.apache.commons.dbcp2.DataSourceConnectionFactory;
import org.apache.commons.dbcp2.PoolableConnectionFactory;
import org.apache.commons.dbcp2.PoolingDataSource;
import org.apache.commons.pool2.impl.GenericObjectPool;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.joda.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import javax.sql.DataSource;
import java.io.IOException;
import java.io.Serializable;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import static com.google.common.base.Preconditions.checkArgument;

/**
 * 自动调用一些方法hive不支持
 *
 * JdbcIO.DataSourceConfiguration.create(
 *                                         cpds).withConnectionProperties("hive")
 *                                         设置定义hive操作
 *
 * @Author: limeng
 * @Date: 2019/7/25 17:03
 */
@Experimental(Experimental.Kind.SOURCE_SINK)
public class JdbcIO {

    private static final Logger LOG = LoggerFactory.getLogger(JdbcIO.class);

    /**
     * Read data from a JDBC datasource.
     *
     * @param <T> Type of the data to be read.
     */
    public static <T> Read<T> read() {
        return new AutoValue_JdbcIO_Read.Builder<T>().setFetchSize(DEFAULT_FETCH_SIZE).build();
    }

    /**
     * Like {@link #read}, but executes multiple instances of the query substituting each element of a
     * {@link PCollection} as query parameters.
     *
     * @param <ParameterT> Type of the data representing query parameters.
     * @param <OutputT> Type of the data to be read.
     */
    public static <ParameterT, OutputT> ReadAll<ParameterT, OutputT> readAll() {
        return new AutoValue_JdbcIO_ReadAll.Builder<ParameterT, OutputT>()
                .setFetchSize(DEFAULT_FETCH_SIZE)
                .build();
    }

    private static final long DEFAULT_BATCH_SIZE = 1000L;
    private static final int DEFAULT_FETCH_SIZE = 50_000;

    /**
     * Write data to a JDBC datasource.
     *
     * @param <T> Type of the data to be written.
     */
    public static <T> Write<T> write() {
        return new AutoValue_JdbcIO_Write.Builder<T>()
                .setBatchSize(DEFAULT_BATCH_SIZE)
                .setRetryStrategy(new DefaultRetryStrategy())
                .build();
    }

    /**
     * This is the default {@link Predicate} we use to detect DeadLock. It basically test if the
     * {@link SQLException#getSQLState()} equals 40001. 40001 is the SQL State used by most of
     * database to identify deadlock.
     */
    public static class DefaultRetryStrategy implements RetryStrategy {
        @Override
        public boolean apply(SQLException e) {
            return "40001".equals(e.getSQLState());
        }
    }

    private JdbcIO() {}

    /**
     * An interface used by {@link JdbcIO.Read} for converting each row of the {@link ResultSet} into
     * an element of the resulting {@link PCollection}.
     */
    @FunctionalInterface
    public interface RowMapper<T> extends Serializable {
        T mapRow(ResultSet resultSet) throws Exception;
    }

    /**
     * A POJO describing a {@link DataSource}, either providing directly a {@link DataSource} or all
     * properties allowing to create a {@link DataSource}.
     */
    @AutoValue
    public abstract static class DataSourceConfiguration implements Serializable {
        @Nullable
        abstract ValueProvider<String> getDriverClassName();

        @Nullable
        abstract ValueProvider<String> getUrl();

        @Nullable
        abstract ValueProvider<String> getUsername();

        @Nullable
        abstract ValueProvider<String> getPassword();

        @Nullable
        abstract ValueProvider<String> getConnectionProperties();

        @Nullable
        abstract DataSource getDataSource();

        abstract Builder builder();

        @AutoValue.Builder
        abstract static class Builder {
            abstract Builder setDriverClassName(ValueProvider<String> driverClassName);

            abstract Builder setUrl(ValueProvider<String> url);

            abstract Builder setUsername(ValueProvider<String> username);

            abstract Builder setPassword(ValueProvider<String> password);

            abstract Builder setConnectionProperties(ValueProvider<String> connectionProperties);

            abstract Builder setDataSource(DataSource dataSource);

            abstract DataSourceConfiguration build();
        }

        public static DataSourceConfiguration create(DataSource dataSource) {
            checkArgument(dataSource != null, "dataSource can not be null");
            checkArgument(dataSource instanceof Serializable, "dataSource must be Serializable");
            return new AutoValue_JdbcIO_DataSourceConfiguration.Builder()
                    .setDataSource(dataSource)
                    .build();
        }

        public static DataSourceConfiguration create(String driverClassName, String url) {
            checkArgument(driverClassName != null, "driverClassName can not be null");
            checkArgument(url != null, "url can not be null");
            return new AutoValue_JdbcIO_DataSourceConfiguration.Builder()
                    .setDriverClassName(ValueProvider.StaticValueProvider.of(driverClassName))
                    .setUrl(ValueProvider.StaticValueProvider.of(url))
                    .build();
        }

        public static DataSourceConfiguration create(
                ValueProvider<String> driverClassName, ValueProvider<String> url) {
            checkArgument(driverClassName != null, "driverClassName can not be null");
            checkArgument(url != null, "url can not be null");
            return new AutoValue_JdbcIO_DataSourceConfiguration.Builder()
                    .setDriverClassName(driverClassName)
                    .setUrl(url)
                    .build();
        }

        public DataSourceConfiguration withUsername(String username) {
            return builder().setUsername(ValueProvider.StaticValueProvider.of(username)).build();
        }

        public DataSourceConfiguration withUsername(ValueProvider<String> username) {
            return builder().setUsername(username).build();
        }

        public DataSourceConfiguration withPassword(String password) {
            return builder().setPassword(ValueProvider.StaticValueProvider.of(password)).build();
        }

        public DataSourceConfiguration withPassword(ValueProvider<String> password) {
            return builder().setPassword(password).build();
        }

        /**
         * Sets the connection properties passed to driver.connect(...). Format of the string must be
         * [propertyName=property;]*
         *
         * <p>NOTE - The "user" and "password" properties can be add via {@link #withUsername(String)},
         * {@link #withPassword(String)}, so they do not need to be included here.
         */
        public DataSourceConfiguration withConnectionProperties(String connectionProperties) {
            checkArgument(connectionProperties != null, "connectionProperties can not be null");
            return builder()
                    .setConnectionProperties(ValueProvider.StaticValueProvider.of(connectionProperties))
                    .build();
        }

        /** Same as {@link #withConnectionProperties(String)} but accepting a ValueProvider. */
        public DataSourceConfiguration withConnectionProperties(
                ValueProvider<String> connectionProperties) {
            checkArgument(connectionProperties != null, "connectionProperties can not be null");
            return builder().setConnectionProperties(connectionProperties).build();
        }

        private void populateDisplayData(DisplayData.Builder builder) {
            if (getDataSource() != null) {
                builder.addIfNotNull(DisplayData.item("dataSource", getDataSource().getClass().getName()));
            } else {
                builder.addIfNotNull(DisplayData.item("jdbcDriverClassName", getDriverClassName()));
                builder.addIfNotNull(DisplayData.item("jdbcUrl", getUrl()));
                builder.addIfNotNull(DisplayData.item("username", getUsername()));
            }
        }

        DataSource buildDatasource() throws Exception {
            DataSource current = null;
            if (getDataSource() != null) {
                current = getDataSource();
            } else {
                BasicDataSource basicDataSource = new BasicDataSource();
                if (getDriverClassName() != null) {
                    basicDataSource.setDriverClassName(getDriverClassName().get());
                }
                if (getUrl() != null) {
                    basicDataSource.setUrl(getUrl().get());
                }
                if (getUsername() != null) {
                    basicDataSource.setUsername(getUsername().get());
                }
                if (getPassword() != null) {
                    basicDataSource.setPassword(getPassword().get());
                }
                if (getConnectionProperties() != null && getConnectionProperties().get() != null) {
                    basicDataSource.setConnectionProperties(getConnectionProperties().get());
                }
                current = basicDataSource;
            }

            // wrapping the datasource as a pooling datasource
            DataSourceConnectionFactory connectionFactory = new DataSourceConnectionFactory(current);
            PoolableConnectionFactory poolableConnectionFactory =
                    new PoolableConnectionFactory(connectionFactory, null);
            GenericObjectPoolConfig poolConfig = new GenericObjectPoolConfig();
            poolConfig.setMaxTotal(1);
            poolConfig.setMinIdle(0);
            poolConfig.setMinEvictableIdleTimeMillis(10000);
            poolConfig.setSoftMinEvictableIdleTimeMillis(30000);
            GenericObjectPool connectionPool =
                    new GenericObjectPool(poolableConnectionFactory, poolConfig);
            poolableConnectionFactory.setPool(connectionPool);
            poolableConnectionFactory.setDefaultAutoCommit(false);
            poolableConnectionFactory.setDefaultReadOnly(false);
            PoolingDataSource poolingDataSource = new PoolingDataSource(connectionPool);
            return poolingDataSource;
        }
    }

    /**
     * An interface used by the JdbcIO Write to set the parameters of the {@link PreparedStatement}
     * used to setParameters into the database.
     */
    @FunctionalInterface
    public interface StatementPreparator extends Serializable {
        void setParameters(PreparedStatement preparedStatement) throws Exception;
    }

    /** Implementation of {@link #read}. */
    @AutoValue
    public abstract static class Read<T> extends PTransform<PBegin, PCollection<T>> {
        @Nullable
        abstract DataSourceConfiguration getDataSourceConfiguration();

        @Nullable
        abstract ValueProvider<String> getQuery();

        @Nullable
        abstract StatementPreparator getStatementPreparator();

        @Nullable
        abstract RowMapper<T> getRowMapper();

        @Nullable
        abstract Coder<T> getCoder();

        abstract int getFetchSize();

        abstract Builder<T> toBuilder();

        @AutoValue.Builder
        abstract static class Builder<T> {
            abstract Builder<T> setDataSourceConfiguration(DataSourceConfiguration config);

            abstract Builder<T> setQuery(ValueProvider<String> query);

            abstract Builder<T> setStatementPreparator(StatementPreparator statementPreparator);

            abstract Builder<T> setRowMapper(RowMapper<T> rowMapper);

            abstract Builder<T> setCoder(Coder<T> coder);

            abstract Builder<T> setFetchSize(int fetchSize);

            abstract Read<T> build();
        }

        public Read<T> withDataSourceConfiguration(DataSourceConfiguration configuration) {
            return toBuilder().setDataSourceConfiguration(configuration).build();
        }

        public Read<T> withQuery(String query) {
            checkArgument(query != null, "query can not be null");
            return withQuery(ValueProvider.StaticValueProvider.of(query));
        }

        public Read<T> withQuery(ValueProvider<String> query) {
            checkArgument(query != null, "query can not be null");
            return toBuilder().setQuery(query).build();
        }

        public Read<T> withStatementPreparator(StatementPreparator statementPreparator) {
            checkArgument(statementPreparator != null, "statementPreparator can not be null");
            return toBuilder().setStatementPreparator(statementPreparator).build();
        }

        public Read<T> withRowMapper(RowMapper<T> rowMapper) {
            checkArgument(rowMapper != null, "rowMapper can not be null");
            return toBuilder().setRowMapper(rowMapper).build();
        }

        public Read<T> withCoder(Coder<T> coder) {
            checkArgument(coder != null, "coder can not be null");
            return toBuilder().setCoder(coder).build();
        }

        /**
         * This method is used to set the size of the data that is going to be fetched and loaded in
         * memory per every database call. Please refer to: {@link java.sql.Statement#setFetchSize(int)}
         * It should ONLY be used if the default value throws memory errors.
         */
        public Read<T> withFetchSize(int fetchSize) {
            checkArgument(fetchSize > 0, "fetch size must be > 0");
            return toBuilder().setFetchSize(fetchSize).build();
        }

        @Override
        public PCollection<T> expand(PBegin input) {
            checkArgument(getQuery() != null, "withQuery() is required");
            checkArgument(getRowMapper() != null, "withRowMapper() is required");
            checkArgument(getCoder() != null, "withCoder() is required");
            checkArgument(
                    (getDataSourceConfiguration() != null), "withDataSourceConfiguration() is required");

            return input
                    .apply(Create.of((Void) null))
                    .apply(
                            JdbcIO.<Void, T>readAll()
                                    .withDataSourceConfiguration(getDataSourceConfiguration())
                                    .withQuery(getQuery())
                                    .withCoder(getCoder())
                                    .withRowMapper(getRowMapper())
                                    .withFetchSize(getFetchSize())
                                    .withParameterSetter(
                                            (element, preparedStatement) -> {
                                                if (getStatementPreparator() != null) {
                                                    getStatementPreparator().setParameters(preparedStatement);
                                                }
                                            }));
        }

        @Override
        public void populateDisplayData(DisplayData.Builder builder) {
            super.populateDisplayData(builder);
            builder.add(DisplayData.item("query", getQuery()));
            builder.add(DisplayData.item("rowMapper", getRowMapper().getClass().getName()));
            builder.add(DisplayData.item("coder", getCoder().getClass().getName()));
            getDataSourceConfiguration().populateDisplayData(builder);
        }
    }

    /** Implementation of {@link #readAll}. */

    /** Implementation of {@link #read}. */
    @AutoValue
    public abstract static class ReadAll<ParameterT, OutputT>
            extends PTransform<PCollection<ParameterT>, PCollection<OutputT>> {
        @Nullable
        abstract DataSourceConfiguration getDataSourceConfiguration();

        @Nullable
        abstract ValueProvider<String> getQuery();

        @Nullable
        abstract PreparedStatementSetter<ParameterT> getParameterSetter();

        @Nullable
        abstract RowMapper<OutputT> getRowMapper();

        @Nullable
        abstract Coder<OutputT> getCoder();

        abstract int getFetchSize();

        abstract Builder<ParameterT, OutputT> toBuilder();

        @AutoValue.Builder
        abstract static class Builder<ParameterT, OutputT> {
            abstract Builder<ParameterT, OutputT> setDataSourceConfiguration(
                    DataSourceConfiguration config);

            abstract Builder<ParameterT, OutputT> setQuery(ValueProvider<String> query);

            abstract Builder<ParameterT, OutputT> setParameterSetter(
                    PreparedStatementSetter<ParameterT> parameterSetter);

            abstract Builder<ParameterT, OutputT> setRowMapper(RowMapper<OutputT> rowMapper);

            abstract Builder<ParameterT, OutputT> setCoder(Coder<OutputT> coder);

            abstract Builder<ParameterT, OutputT> setFetchSize(int fetchSize);

            abstract ReadAll<ParameterT, OutputT> build();
        }

        public ReadAll<ParameterT, OutputT> withDataSourceConfiguration(
                DataSourceConfiguration configuration) {
            return toBuilder().setDataSourceConfiguration(configuration).build();
        }

        public ReadAll<ParameterT, OutputT> withQuery(String query) {
            checkArgument(query != null, "JdbcIO.readAll().withQuery(query) called with null query");
            return withQuery(ValueProvider.StaticValueProvider.of(query));
        }

        public ReadAll<ParameterT, OutputT> withQuery(ValueProvider<String> query) {
            checkArgument(query != null, "JdbcIO.readAll().withQuery(query) called with null query");
            return toBuilder().setQuery(query).build();
        }

        public ReadAll<ParameterT, OutputT> withParameterSetter(
                PreparedStatementSetter<ParameterT> parameterSetter) {
            checkArgument(
                    parameterSetter != null,
                    "JdbcIO.readAll().withParameterSetter(parameterSetter) called "
                            + "with null statementPreparator");
            return toBuilder().setParameterSetter(parameterSetter).build();
        }

        public ReadAll<ParameterT, OutputT> withRowMapper(RowMapper<OutputT> rowMapper) {
            checkArgument(
                    rowMapper != null,
                    "JdbcIO.readAll().withRowMapper(rowMapper) called with null rowMapper");
            return toBuilder().setRowMapper(rowMapper).build();
        }

        public ReadAll<ParameterT, OutputT> withCoder(Coder<OutputT> coder) {
            checkArgument(coder != null, "JdbcIO.readAll().withCoder(coder) called with null coder");
            return toBuilder().setCoder(coder).build();
        }

        /**
         * This method is used to set the size of the data that is going to be fetched and loaded in
         * memory per every database call. Please refer to: {@link java.sql.Statement#setFetchSize(int)}
         * It should ONLY be used if the default value throws memory errors.
         */
        public ReadAll<ParameterT, OutputT> withFetchSize(int fetchSize) {
            checkArgument(fetchSize > 0, "fetch size must be >0");
            return toBuilder().setFetchSize(fetchSize).build();
        }

        @Override
        public PCollection<OutputT> expand(PCollection<ParameterT> input) {
            return input
                    .apply(
                            ParDo.of(
                                    new ReadFn<>(
                                            getDataSourceConfiguration(),
                                            getQuery(),
                                            getParameterSetter(),
                                            getRowMapper(),
                                            getFetchSize())))
                    .setCoder(getCoder())
                    .apply(new Reparallelize<>());
        }

        @Override
        public void populateDisplayData(DisplayData.Builder builder) {
            super.populateDisplayData(builder);
            builder.add(DisplayData.item("query", getQuery()));
            builder.add(DisplayData.item("rowMapper", getRowMapper().getClass().getName()));
            builder.add(DisplayData.item("coder", getCoder().getClass().getName()));
            getDataSourceConfiguration().populateDisplayData(builder);
        }
    }

    /** A {@link DoFn} executing the SQL query to read from the database. */
    private static class ReadFn<ParameterT, OutputT> extends DoFn<ParameterT, OutputT> {
        private final DataSourceConfiguration dataSourceConfiguration;
        private final ValueProvider<String> query;
        private final PreparedStatementSetter<ParameterT> parameterSetter;
        private final RowMapper<OutputT> rowMapper;
        private final int fetchSize;

        private DataSource dataSource;
        private Connection connection;

        private ReadFn(
                DataSourceConfiguration dataSourceConfiguration,
                ValueProvider<String> query,
                PreparedStatementSetter<ParameterT> parameterSetter,
                RowMapper<OutputT> rowMapper,
                int fetchSize) {
            this.dataSourceConfiguration = dataSourceConfiguration;
            this.query = query;
            this.parameterSetter = parameterSetter;
            this.rowMapper = rowMapper;
            this.fetchSize = fetchSize;
        }

        @Setup
        public void setup() throws Exception {
            dataSource = dataSourceConfiguration.buildDatasource();
            connection = dataSource.getConnection();
        }

        @ProcessElement
        public void processElement(ProcessContext context) throws Exception {
            try (PreparedStatement statement =
                         connection.prepareStatement(
                                 query.get(), ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
                statement.setFetchSize(fetchSize);
                parameterSetter.setParameters(context.element(), statement);
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        context.output(rowMapper.mapRow(resultSet));
                    }
                }
            }
        }

        @Teardown
        public void teardown() throws Exception {
            connection.close();
            if (dataSource instanceof AutoCloseable) {
                ((AutoCloseable) dataSource).close();
            }
        }
    }

    /**
     * An interface used by the JdbcIO Write to set the parameters of the {@link PreparedStatement}
     * used to setParameters into the database.
     */
    @FunctionalInterface
    public interface PreparedStatementSetter<T> extends Serializable {
        void setParameters(T element, PreparedStatement preparedStatement) throws Exception;
    }

    /**
     * An interface used to control if we retry the statements when a {@link SQLException} occurs. If
     * {@link RetryStrategy#apply(SQLException)} returns true, {@link Write} tries to replay the
     * statements.
     */
    @FunctionalInterface
    public interface RetryStrategy extends Serializable {
        boolean apply(SQLException sqlException);
    }

    /** A {@link PTransform} to write to a JDBC datasource. */
    @AutoValue
    public abstract static class Write<T> extends PTransform<PCollection<T>, PDone> {
        @Nullable
        abstract DataSourceConfiguration getDataSourceConfiguration();

        @Nullable
        abstract String getStatement();

        abstract long getBatchSize();

        @Nullable
        abstract PreparedStatementSetter<T> getPreparedStatementSetter();

        @Nullable
        abstract RetryStrategy getRetryStrategy();

        abstract Builder<T> toBuilder();

        @AutoValue.Builder
        abstract static class Builder<T> {
            abstract Builder<T> setDataSourceConfiguration(DataSourceConfiguration config);

            abstract Builder<T> setStatement(String statement);

            abstract Builder<T> setBatchSize(long batchSize);

            abstract Builder<T> setPreparedStatementSetter(PreparedStatementSetter<T> setter);

            abstract Builder<T> setRetryStrategy(RetryStrategy deadlockPredicate);

            abstract Write<T> build();
        }

        public Write<T> withDataSourceConfiguration(DataSourceConfiguration config) {
            return toBuilder().setDataSourceConfiguration(config).build();
        }

        public Write<T> withStatement(String statement) {
            return toBuilder().setStatement(statement).build();
        }

        public Write<T> withPreparedStatementSetter(PreparedStatementSetter<T> setter) {
            return toBuilder().setPreparedStatementSetter(setter).build();
        }

        /**
         * Provide a maximum size in number of SQL statenebt for the batch. Default is 1000.
         *
         * @param batchSize maximum batch size in number of statements
         */
        public Write<T> withBatchSize(long batchSize) {
            checkArgument(batchSize > 0, "batchSize must be > 0, but was %s", batchSize);
            return toBuilder().setBatchSize(batchSize).build();
        }

        /**
         * When a SQL exception occurs, {@link Write} uses this {@link RetryStrategy} to determine if it
         * will retry the statements. If {@link RetryStrategy#apply(SQLException)} returns {@code true},
         * then {@link Write} retries the statements.
         */
        public Write<T> withRetryStrategy(RetryStrategy retryStrategy) {
            checkArgument(retryStrategy != null, "retryStrategy can not be null");
            return toBuilder().setRetryStrategy(retryStrategy).build();
        }

        @Override
        public PDone expand(PCollection<T> input) {
            checkArgument(
                    getDataSourceConfiguration() != null, "withDataSourceConfiguration() is required");
            checkArgument(getStatement() != null, "withStatement() is required");
            checkArgument(
                    getPreparedStatementSetter() != null, "withPreparedStatementSetter() is required");

            input.apply(ParDo.of(new WriteFn<>(this)));
            return PDone.in(input.getPipeline());
        }

        private static class WriteFn<T> extends DoFn<T, Void> {

            private final Write<T> spec;

            private static final int MAX_RETRIES = 5;
            private static final FluentBackoff BUNDLE_WRITE_BACKOFF =
                    FluentBackoff.DEFAULT
                            .withMaxRetries(MAX_RETRIES)
                            .withInitialBackoff(Duration.standardSeconds(5));

            private DataSource dataSource;
            private Connection connection;
            private PreparedStatement preparedStatement;
            private boolean hiveStatus = false;
            private List<T> records = new ArrayList<>();

            public WriteFn(Write<T> spec) {
                this.spec = spec;
                ValueProvider<String> connectionProperties = spec.getDataSourceConfiguration().getConnectionProperties();
                if(connectionProperties != null){
                    if(connectionProperties.get().equals("hive")){
                        hiveStatus = true;
                    }
                }
            }

            @Setup
            public void setup() throws Exception {
                dataSource = spec.getDataSourceConfiguration().buildDatasource();
            }

            @StartBundle
            public void startBundle() throws Exception {
                connection = dataSource.getConnection();
                if(!hiveStatus){
                    connection.setAutoCommit(false);
                }
                preparedStatement = connection.prepareStatement(spec.getStatement());
            }

            @ProcessElement
            public void processElement(ProcessContext context) throws Exception {
                T record = context.element();

                records.add(record);

                if (records.size() >= spec.getBatchSize()) {
                    executeBatch();
                }
            }

            private void processRecord(T record, PreparedStatement preparedStatement) {
                try {
                    preparedStatement.clearParameters();
                    spec.getPreparedStatementSetter().setParameters(record, preparedStatement);
                    /**
                     * jdbcio影响hive HivePreparedStatement有些方法不支持
                     */
                    LOG.info("hiveStatus:{}",hiveStatus);
                    if(!hiveStatus){
                        preparedStatement.addBatch();
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }

            @FinishBundle
            public void finishBundle() throws Exception {
                executeBatch();
                try {
                    if (preparedStatement != null) {
                        preparedStatement.close();
                    }
                } finally {
                    if (connection != null) {
                        connection.close();
                    }
                }
            }

            private void executeBatch() throws SQLException, IOException, InterruptedException {
                if (records.isEmpty()) {
                    return;
                }
                Sleeper sleeper = Sleeper.DEFAULT;
                BackOff backoff = BUNDLE_WRITE_BACKOFF.backoff();
                while (true) {
                    try (PreparedStatement preparedStatement =
                                 connection.prepareStatement(spec.getStatement())) {
                        try {
                            // add each record in the statement batch
                            for (T record : records) {
                                processRecord(record, preparedStatement);
                            }
                            // execute the batch
                            /**
                             * jdbcio影响hive HivePreparedStatement有些方法不支持
                             * preparedStatement.executeBatch();
                             * connection.commit();
                             */
                            if(!hiveStatus){
                                preparedStatement.executeBatch();
                                connection.commit();
                            }
                            break;
                        } catch (Exception exception) {
                            /**
                             * jdbcio影响hive HivePreparedStatement有些方法不支持
                             * if (!spec.getRetryStrategy().apply(exception)) {
                             *                                 throw exception;
                             *                             }
                             */
                            if(!hiveStatus){
                                if (!spec.getRetryStrategy().apply((SQLException) exception)) {
                                    throw exception;
                                }
                                preparedStatement.clearBatch();
                            }
                            LOG.warn("Deadlock detected, retrying", exception);
                            // clean up the statement batch and the connection state
                            connection.rollback();
                            if (!BackOffUtils.next(sleeper, backoff)) {
                                // we tried the max number of times
                                throw exception;
                            }
                        }
                    }
                }
                records.clear();
            }

            @Teardown
            public void teardown() throws Exception {
                if (dataSource instanceof AutoCloseable) {
                    ((AutoCloseable) dataSource).close();
                }
            }
        }
    }

    private static class Reparallelize<T> extends PTransform<PCollection<T>, PCollection<T>> {
        @Override
        public PCollection<T> expand(PCollection<T> input) {
            // See https://issues.apache.org/jira/browse/BEAM-2803
            // We use a combined approach to "break fusion" here:
            // (see https://cloud.google.com/dataflow/service/dataflow-service-desc#preventing-fusion)
            // 1) force the data to be materialized by passing it as a side input to an identity fn,
            // then 2) reshuffle it with a random key. Initial materialization provides some parallelism
            // and ensures that data to be shuffled can be generated in parallel, while reshuffling
            // provides perfect parallelism.
            // In most cases where a "fusion break" is needed, a simple reshuffle would be sufficient.
            // The current approach is necessary only to support the particular case of JdbcIO where
            // a single query may produce many gigabytes of query results.
            PCollectionView<Iterable<T>> empty =
                    input
                            .apply("Consume", Filter.by(SerializableFunctions.constant(false)))
                            .apply(View.asIterable());
            PCollection<T> materialized =
                    input.apply(
                            "Identity",
                            ParDo.of(
                                    new DoFn<T, T>() {
                                        @ProcessElement
                                        public void process(ProcessContext c) {
                                            c.output(c.element());
                                        }
                                    })
                                    .withSideInputs(empty));
            return materialized.apply(Reshuffle.viaRandomKey());
        }
    }
}
