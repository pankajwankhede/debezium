/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.relational.history;

import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.debezium.config.Configuration;
import io.debezium.function.Predicates;
import io.debezium.relational.Tables;
import io.debezium.relational.ddl.DdlParser;
import io.debezium.text.ParsingException;

/**
 * @author Randall Hauch
 *
 */
public abstract class AbstractDatabaseHistory implements DatabaseHistory {

    protected final Logger logger = LoggerFactory.getLogger(getClass());
    protected Configuration config;
    private HistoryRecordComparator comparator = HistoryRecordComparator.INSTANCE;
    private boolean skipUnparseableDDL;
    private Predicate<String> ddlFilter = (x -> false);

    protected AbstractDatabaseHistory() {
    }

    @Override
    public void configure(Configuration config, HistoryRecordComparator comparator) {
        this.config = config;
        this.comparator = comparator != null ? comparator : HistoryRecordComparator.INSTANCE;
        this.skipUnparseableDDL = config.getBoolean(DatabaseHistory.SKIP_UNPARSEABLE_DDL_STATEMENTS);

        final String ddlFilter = config.getString(DatabaseHistory.DDL_FILTER);
        this.ddlFilter = (ddlFilter != null) ? Predicates.includes(ddlFilter) : this.ddlFilter;
    }

    @Override
    public void start() {
        // do nothing
    }

    @Override
    public final void record(Map<String, ?> source, Map<String, ?> position, String databaseName, Tables schema, String ddl)
            throws DatabaseHistoryException {
            storeRecord(new HistoryRecord(source, position, databaseName, ddl));
    }

    @Override
    public final void recover(Map<String, ?> source, Map<String, ?> position, Tables schema, DdlParser ddlParser) {
        logger.debug("Recovering DDL history for source partition {} and offset {}", source, position);
        HistoryRecord stopPoint = new HistoryRecord(source, position, null, null);
        recoverRecords(schema, ddlParser, recovered -> {
            if (comparator.isAtOrBefore(recovered, stopPoint)) {
                String ddl = recovered.ddl();
                if (ddl != null) {
                    ddlParser.setCurrentSchema(recovered.databaseName()); // may be null
                    if (ddlFilter.test(ddl)) {
                        logger.info("a DDL '{}' was filtered out of processing", ddl);
                        return;
                    }
                    try {
                        logger.debug("Applying: {}", ddl);
                        ddlParser.parse(ddl, schema);
                    } catch (final ParsingException e) {
                        if (skipUnparseableDDL) {
                            logger.warn("Ignoring unparseable statements '{}' stored in database history: {}", ddl, e);
                        } else {
                            logger.error("Cannot parse DDL statements {} stored in history, exiting", ddl);
                            throw e;
                        }
                    }
                }
            } else {
                logger.debug("Skipping: {}", recovered.ddl());
            }
        });
    }

    protected abstract void storeRecord(HistoryRecord record) throws DatabaseHistoryException;

    protected abstract void recoverRecords(Tables schema, DdlParser ddlParser, Consumer<HistoryRecord> records);

    @Override
    public void stop() {
        // do nothing
    }
}
