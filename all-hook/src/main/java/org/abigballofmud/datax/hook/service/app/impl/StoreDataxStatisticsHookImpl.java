package org.abigballofmud.datax.hook.service.app.impl;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;

import com.alibaba.datax.common.element.Column;
import com.alibaba.datax.common.element.Record;
import com.alibaba.datax.common.exception.DataXException;
import com.alibaba.datax.common.spi.Hook;
import com.alibaba.datax.common.statistics.JobStatistics;
import com.alibaba.datax.common.util.Configuration;
import com.alibaba.datax.core.util.container.CoreConstant;
import com.alibaba.datax.plugin.rdbms.util.DBUtil;
import com.alibaba.datax.plugin.rdbms.util.DBUtilErrorCode;
import com.alibaba.datax.plugin.rdbms.util.DataBaseType;
import com.alibaba.fastjson.JSON;
import com.google.common.collect.Maps;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.lang3.tuple.Triple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>
 * description
 * </p>
 *
 * @author abigballofmud 2020/01/02 15:41
 * @since 1.0
 */
public class StoreDataxStatisticsHookImpl implements Hook {

    private static final Logger LOG = LoggerFactory.getLogger(StoreDataxStatisticsHookImpl.class);

    @Override
    public String getName() {
        return "abigballofmud store datax statistics hook";
    }

    @Override
    public void invoke(Configuration jobConf, Map<String, Number> msg, JobStatistics jobStatistics) {
        LOG.info("into abigballofmud store datax statistics hook");
        LOG.info("jobStatistics: {}", jobStatistics);
        LOG.info("load statistics.properties, path: {}", CoreConstant.DATAX_CONF_STATISTICS_PATH);
        try (BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(new FileInputStream(CoreConstant.DATAX_CONF_STATISTICS_PATH)))) {
            Properties properties = new Properties();
            properties.load(bufferedReader);
            storeToDb(jobStatistics, properties);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void storeToDb(JobStatistics jobStatistics, Properties properties) {
        String tableName = properties.getProperty("tableName");
        String jdbcUrl = properties.getProperty("jdbcUrl");
        String username = properties.getProperty("username");
        String password = properties.getProperty("password");
        try (Connection connection = DBUtil.getConnection(DataBaseType.MySql, jdbcUrl, username, password);
             Statement statement = connection.createStatement()) {
            String columns = DBUtil.getTableColumns(DataBaseType.MySql, jdbcUrl, username, password, tableName)
                    .stream()
                    // 过滤主键 这里主键写死
                    .filter(column -> !"id".equalsIgnoreCase(column))
                    .collect(Collectors.joining(", "));
            LOG.info("columns: {}", columns);
            Triple<List<String>, List<Integer>, List<String>> columnMetaData =
                    DBUtil.getColumnMetaData(connection, tableName, columns);
            List<String> valueHolderList = genValueHolderList(columnMetaData);
            String sql = genSql(valueHolderList, jobStatistics, tableName, columns);
            LOG.debug("sql: {}", sql);
            DBUtil.executeSqlWithoutResultSet(statement, sql);
            LOG.info("jobStatistics insert into table success");
        } catch (SQLException e) {
            LOG.error("store to db error", e);
        }
    }

    private List<String> genValueHolderList(Triple<List<String>, List<Integer>, List<String>> columnMetaData) {
        List<String> valueHolderList = new ArrayList<>(columnMetaData.getLeft().size());
        for (int i = 0; i < columnMetaData.getLeft().size(); i++) {
            switch (columnMetaData.getMiddle().get(i)) {
                case Types.CHAR:
                case Types.NCHAR:
                case Types.CLOB:
                case Types.NCLOB:
                case Types.VARCHAR:
                case Types.LONGVARCHAR:
                case Types.NVARCHAR:
                case Types.LONGNVARCHAR:
                case Types.DATE:
                case Types.TIME:
                case Types.TIMESTAMP:
                case Types.BINARY:
                case Types.VARBINARY:
                case Types.BLOB:
                case Types.LONGVARBINARY:
                case Types.BOOLEAN:
                case Types.BIT:
                    if (columnMetaData.getLeft().get(i).contains("dirty_records")) {
                        valueHolderList.add("%s");
                    } else {
                        valueHolderList.add("'%s'");
                    }
                    break;
                case Types.SMALLINT:
                case Types.INTEGER:
                case Types.BIGINT:
                case Types.NUMERIC:
                case Types.DECIMAL:
                case Types.FLOAT:
                case Types.REAL:
                case Types.DOUBLE:
                case Types.TINYINT:
                    valueHolderList.add("%d");
                    break;
                default:
                    throw DataXException.asDataXException(DBUtilErrorCode.UNSUPPORTED_TYPE, "暂不支持该字段类型请修改表中该字段的类型.");
            }
        }
        return valueHolderList;
    }

    private String genSql(List<String> valueHolderList, JobStatistics jobStatistics, String tableName, String columns) {
        String dirtyRecord;
        List<Pair<Record, String>> dirtyRecordList = jobStatistics.getDirtyRecordList();
        if (!dirtyRecordList.isEmpty()) {
            // 暂时这样
            List<Map<String, Object>> list = new ArrayList<>(dirtyRecordList.size());
            Map<String, Object> map;
            Map<String, Object> tmp;
            for (Pair<Record, String> pair : dirtyRecordList) {
                map = Maps.newHashMapWithExpectedSize(dirtyRecordList.size());
                map.put("errorMessage", pair.getRight().replace("'", "\\\""));
                List<Column> columnList = pair.getLeft().getColumnList();
                for (int i = 0, size = columnList.size(); i < size; i++) {
                    tmp = Maps.newHashMapWithExpectedSize(size);
                    tmp.put("rowData", columnList.get(i).getRawData());
                    tmp.put("byteSize", columnList.get(i).getByteSize());
                    tmp.put("type", columnList.get(i).getType());
                    map.put("col" + i, tmp);
                }
                list.add(map);
            }
            dirtyRecord = String.format("'%s'", JSON.toJSONString(list));
        } else {
            dirtyRecord = null;
        }
        return String.format("INSERT INTO %s(%s) VALUES (%s)",
                tableName,
                columns,
                String.format(String.join(", ", valueHolderList), jobStatistics.getExecId(),
                        jobStatistics.getJsonFileName(),
                        jobStatistics.getJobName(),
                        jobStatistics.getReaderPlugin(),
                        jobStatistics.getWriterPlugin(),
                        jobStatistics.getStartTime(),
                        jobStatistics.getEndTime(),
                        jobStatistics.getTotalCosts(),
                        jobStatistics.getByteSpeedPerSecond(),
                        jobStatistics.getRecordSpeedPerSecond(),
                        jobStatistics.getTotalReadRecords(),
                        jobStatistics.getTotalErrorRecords(),
                        jobStatistics.getJobPath(),
                        jobStatistics.getJobContent(),
                        dirtyRecord
                )
        );
    }

}
