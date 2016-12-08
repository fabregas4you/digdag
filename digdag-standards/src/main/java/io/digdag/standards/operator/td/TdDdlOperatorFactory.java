package io.digdag.standards.operator.td;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.ImmutableList;
import com.google.inject.Inject;
import io.digdag.client.config.Config;
import io.digdag.client.config.ConfigException;
import io.digdag.core.Environment;
import io.digdag.spi.Operator;
import io.digdag.spi.OperatorFactory;
import io.digdag.spi.OperatorContext;
import io.digdag.spi.SecretAccessList;
import io.digdag.spi.TaskExecutionException;
import io.digdag.spi.TaskRequest;
import io.digdag.spi.TaskResult;
import io.digdag.standards.operator.DurationInterval;
import io.digdag.standards.operator.state.TaskState;
import io.digdag.util.BaseOperator;
import org.immutables.value.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static com.google.common.collect.Iterables.concat;
import static io.digdag.standards.operator.state.PollingRetryExecutor.pollingRetryExecutor;
import static io.digdag.standards.operator.td.BaseTdJobOperator.configSelectorBuilder;
import static java.util.Locale.ENGLISH;

public class TdDdlOperatorFactory
        implements OperatorFactory
{
    private static Logger logger = LoggerFactory.getLogger(TdDdlOperatorFactory.class);
    private final Map<String, String> env;
    private final DurationInterval retryInterval;

    @Inject
    public TdDdlOperatorFactory(@Environment Map<String, String> env, Config systemConfig)
    {
        this.env = env;
        this.retryInterval = TDOperator.retryInterval(systemConfig);
    }

    public String getType()
    {
        return "td_ddl";
    }

    @Override
    public SecretAccessList getSecretAccessList()
    {
        return configSelectorBuilder()
            .build();
    }

    @Override
    public Operator newOperator(OperatorContext context)
    {
        return new TdDdlOperator(context);
    }

    private class TdDdlOperator
            extends BaseOperator
    {
        private final TaskState state;

        public TdDdlOperator(OperatorContext context)
        {
            super(context);
            this.state = TaskState.of(request);
        }

        @Override
        public TaskResult runTask()
        {
            Config params = request.getConfig().mergeDefault(
                    request.getConfig().getNestedOrGetEmpty("td"));

            List<String> dropDatabaseList = params.getListOrEmpty("drop_databases", String.class);
            List<String> createDatabaseList = params.getListOrEmpty("create_databases", String.class);
            List<String> emptyDatabaseList = params.getListOrEmpty("empty_databases", String.class);

            List<TableParam> dropTableList = params.getListOrEmpty("drop_tables", TableParam.class);
            List<TableParam> createTableList = params.getListOrEmpty("create_tables", TableParam.class);
            List<TableParam> emptyTableList = params.getListOrEmpty("empty_tables", TableParam.class);

            List<RenameTableConfig> renameTableList = params.getListOrEmpty("rename_tables", RenameTableConfig.class);

            List<Consumer<TDOperator>> operations = new ArrayList<>();

            for (String d : concat(dropDatabaseList, emptyDatabaseList)) {
                operations.add(op -> {
                    logger.info("Deleting TD database {}", d);
                    op.withDatabase(d).ensureDatabaseDeleted(d);
                });
            }
            for (String d : concat(createDatabaseList, emptyDatabaseList)) {
                operations.add(op -> {
                    logger.info("Creating TD database {}", op.getDatabase(), d);
                    op.withDatabase(d).ensureDatabaseCreated(d);
                });
            }
            for (TableParam t : concat(dropTableList, emptyTableList)) {
                operations.add(op -> {
                    logger.info("Deleting TD table {}.{}", op.getDatabase(), t);
                    op.withDatabase(t.getDatabase().or(op.getDatabase())).ensureTableDeleted(t.getTable());
                });
            }
            for (TableParam t : concat(createTableList, emptyTableList)) {
                operations.add(op -> {
                    logger.info("Creating TD table {}.{}", op.getDatabase(), t);
                    op.withDatabase(t.getDatabase().or(op.getDatabase())).ensureTableCreated(t.getTable());
                });
            }
            for (RenameTableConfig r : renameTableList) {
                TableParam from = r.getFromTable();
                String to = r.getToTable();
                if (to.contains(".")) {
                    throw new ConfigException("'to' option of rename_tables must not include database name");
                }
                operations.add(op -> {
                    logger.info("Renaming TD table {}.{} -> {}", op.getDatabase(), from, to);
                    op.withDatabase(from.getDatabase().or(op.getDatabase())).ensureExistentTableRenamed(from.getTable(), to);
                });
            }

            try (TDOperator op = TDOperator.fromConfig(env, params, context.getSecrets().getSecrets("td"))) {
                // make sure that all "from" tables exist so that ignoring 404 Not Found in
                // op.ensureExistentTableRenamed is valid.
                boolean renameFromChecked = state.params().get("rename_from_checked", boolean.class, false);
                if (!renameFromChecked && !renameTableList.isEmpty()) {
                    for (RenameTableConfig r : renameTableList) {
                        TableParam from = r.getFromTable();
                        String database = from.getDatabase().or(op.getDatabase());
                        if (!op.withDatabase(database).tableExists(from.getTable())) {
                            throw new ConfigException(String.format(ENGLISH,
                                        "Renaming table %s doesn't exist", database, r.getFromTable().getTable()));
                        }
                    }
                    state.params().set("rename_from_checked", true);
                }

                int operation = state.params().get("operation", int.class, 0);
                for (int i = operation; i < operations.size(); i++) {
                    state.params().set("operation", i);
                    if (i < operation) {
                        continue;
                    }
                    Consumer<TDOperator> o = operations.get(i);
                    pollingRetryExecutor(state, "retry")
                            .retryUnless(TDOperator::isDeterministicClientException)
                            .withRetryInterval(retryInterval)
                            .withErrorMessage("DDL operation failed")
                            .runAction(s -> o.accept(op));
                }
            }

            return TaskResult.empty(request);
        }
    }

    @Value.Immutable
    @Value.Style(visibility = Value.Style.ImplementationVisibility.PACKAGE)
    interface RenameTableConfig
    {
        @JsonProperty("from")
        TableParam getFromTable();

        @JsonProperty("to")
        String getToTable();
    }
}
