/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.mysql;

import io.debezium.connector.binlog.InnoDBMvccSnapshot;

public class MysqlInnoDbMvccSnapshotIT extends InnoDBMvccSnapshot<MySqlConnector> implements MySqlCommon {

    @Override
    protected String snapshotMode() {
        return "minimal";
    }
}
