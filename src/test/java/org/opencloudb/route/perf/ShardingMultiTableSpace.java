/*
 * Copyright 2012-2015 org.opencloudb.
 *  
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
 */
/**
 * (created at 2012-5-30)
 */
package org.opencloudb.route.perf;

import java.sql.SQLNonTransientException;

import org.opencloudb.config.model.SchemaConfig;
import org.opencloudb.route.ServerRouter;

/**
 * @author mycat
 */
public class ShardingMultiTableSpace {
    private SchemaConfig schema;

    public ShardingMultiTableSpace() {
        // schema =
        // CobarServer.getInstance().getConfig().getSchemas().get("cndb");
    }

    /**
     * 路由到tableSpace的性能测试
     * 
     * @throws SQLNonTransientException
     */
    public void testTableSpace() throws SQLNonTransientException {
        SchemaConfig schema = getSchema();
        String sql = "select id,member_id,gmt_create from offer where member_id in ('1','22','333','1124','4525')";
        for (int i = 0; i < 100000; i++) {
            ServerRouter.route(schema, sql, null, null);
        }
    }

    protected SchemaConfig getSchema() {
        return schema;
    }

    public static void main(String[] args) throws Exception {
        ShardingMultiTableSpace test = new ShardingMultiTableSpace();
        System.currentTimeMillis();

        long start = System.currentTimeMillis();
        test.testTableSpace();
        long end = System.currentTimeMillis();
        System.out.println("take " + (end - start) + " ms.");
    }
}