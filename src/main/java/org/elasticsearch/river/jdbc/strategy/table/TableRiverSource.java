/*
 * Licensed to ElasticSearch and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. ElasticSearch licenses this
 * file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.elasticsearch.river.jdbc.strategy.table;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.elasticsearch.action.bulk.BulkItemResponse;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.logging.ESLoggerFactory;
import org.elasticsearch.river.jdbc.strategy.simple.SimpleRiverSource;
import org.elasticsearch.river.jdbc.support.Operations;
import org.elasticsearch.river.jdbc.support.ValueListener;

/**
 * River source implementation of the 'table' strategy
 *
 * @author Jörg Prante <joergprante@gmail.com>
 */
public class TableRiverSource extends SimpleRiverSource {

    private final ESLogger logger = ESLoggerFactory.getLogger(TableRiverSource.class.getName());

    @Override
    public String strategy() {
        return "table";
    }

    @Override
    public String fetch() throws SQLException, IOException {
        Connection connection = connectionForReading();
        String[] optypes = new String[]{Operations.OP_CREATE, Operations.OP_INDEX, Operations.OP_DELETE, Operations.OP_UPDATE};

        long now = System.currentTimeMillis();
        Timestamp timestampFrom = new Timestamp(now - context.pollingInterval().millis());
        Timestamp timestampNow = new Timestamp(now);

        for (String optype : optypes) {
            PreparedStatement statement;
            try {
            	if(acknowledge()) {
            		logger.trace("fetching all riveritems with source_operation {}", optype);
            		statement = connection.prepareStatement(
                        "select * from \"" + context.riverName() + "\" where \"source_operation\" = ? order by \"source_timestamp\"");
            	} else {
            		logger.trace("fetching all riveritems with source_operation {} and source_timestamp between {} and {} ", timestampFrom,timestampNow);
            		statement = connection.prepareStatement(
                        "select * from \"" + context.riverName() + "\" where \"source_operation\" = ? and \"source_timestamp\" between ? and ? order by \"source_timestamp\""
                    );
            	}

            } catch (SQLException e) {
                // hsqldb
            	if(acknowledge()){
            		statement = connection.prepareStatement(
                        "select * from " + context.riverName() + " where \"source_operation\" = ? order by \"source_timestamp\""
                    );
            	} else {
            		statement = connection.prepareStatement(
                        "select * from " + context.riverName() + " where \"source_operation\" = ? and \"source_timestamp\" between ? and ? order by \"source_timestamp\""
                    );
            	}
            }
            statement.setString(1, optype);
            if(!acknowledge()){
	            statement.setTimestamp(2, timestampFrom);
	            statement.setTimestamp(3, timestampNow);
            }
            ResultSet results;
            try {
                results = executeQuery(statement);
            } catch (SQLException e) {
                // mysql
            	if(acknowledge()) {
            		statement = connection.prepareStatement("select * from " + context.riverName() + " where source_operation = ? order by source_timestamp");
            	} else {
            		statement = connection.prepareStatement(
                        "select * from " + context.riverName() + " where source_operation = ? and source_timestamp between ? and ? order by source_timestamp"
                    );
            	}

                statement.setString(1, optype);
                if(!acknowledge()){
	                statement.setTimestamp(2, timestampFrom);
	                statement.setTimestamp(3, timestampNow);
                }
                results = executeQuery(statement);
            }
            try {
                ValueListener listener = new TableValueListener()
                        .target(context.riverMouth())
                        .digest(context.digesting());
                merge(results, listener); // ignore digest
            } catch (Exception e) {
                throw new IOException(e);
            }
            close(results);
            close(statement);
            sendAcknowledge();
        }
        return null;
    }

    /**
     * Acknowledge a bulk item response back to the river table. Fill columns
     * target_timestamp, taget_operation, target_failed, target_message.
     *
     * @param response
     * @throws IOException
     */
    @Override
    public SimpleRiverSource acknowledge(BulkResponse response) throws IOException {
        String riverName = context.riverName();

        if (response == null) {
            logger.warn("({}) can't acknowledge null bulk response", riverName);
        }
        
        // if acknowlegde is disabled return current. 
        if(!acknowledge()){
        	return this;
        }
        
        try {
            for (BulkItemResponse resp : response.items()) {
                PreparedStatement pstmt;
                List<Object> params;
                
                try {
                    pstmt = prepareUpdate("insert into \""+riverName+"_ack\" (\"_index\",\"_type\",\"_id\","+
                    		"\"target_timestamp\",\"target_operation\",\"target_failed\",\"target_message\") values (?,?,?,?,?,?,?)");

                } catch (SQLException e) {
                    try {
                        // hsqldb
                    	pstmt = prepareUpdate("insert into " + riverName + "_ack (\"_index\",\"_type\",\"_id\","+
                        		"\"target_timestamp\",\"target_operation\",\"target_failed\",\"target_message\") values (?,?,?,?,?,?,?)");
                    } catch (SQLException e1) {
                        // mysql
                    	pstmt = prepareUpdate("insert into " + riverName + "_ack (_index,_type,_id,"+
                        		"target_timestamp,target_operation,target_failed,target_message) values (?,?,?,?,?,?,?)");
                    }
                }
                params = new ArrayList<Object>();
                params.add(resp.getIndex());
                params.add(resp.getType());
                params.add(resp.getId());
                params.add(new Timestamp(new Date().getTime()));
                params.add(resp.opType());
                params.add(resp.isFailed());
                params.add(resp.getFailureMessage());
                bind(pstmt, params);
                executeUpdate(pstmt);
                close(pstmt);
            }
        } catch (SQLException ex) {
            throw new IOException(ex);
        }
        return this;
    }
}
