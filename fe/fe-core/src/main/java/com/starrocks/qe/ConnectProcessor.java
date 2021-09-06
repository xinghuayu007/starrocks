// This file is made available under Elastic License 2.0.
// This file is based on code available under the Apache license here:
//   https://github.com/apache/incubator-doris/blob/master/fe/fe-core/src/main/java/org/apache/doris/qe/ConnectProcessor.java

// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package com.starrocks.qe;

import com.google.common.base.Strings;
import com.starrocks.analysis.KillStmt;
import com.starrocks.analysis.SqlParser;
import com.starrocks.analysis.SqlScanner;
import com.starrocks.analysis.StatementBase;
import com.starrocks.analysis.UserIdentity;
import com.starrocks.catalog.Catalog;
import com.starrocks.catalog.Column;
import com.starrocks.catalog.Database;
import com.starrocks.catalog.Table;
import com.starrocks.cluster.ClusterNamespace;
import com.starrocks.common.AnalysisException;
import com.starrocks.common.DdlException;
import com.starrocks.common.ErrorCode;
import com.starrocks.common.ErrorReport;
import com.starrocks.common.UserException;
import com.starrocks.common.util.DebugUtil;
import com.starrocks.common.util.SqlParserUtils;
import com.starrocks.common.util.UUIDUtil;
import com.starrocks.metric.MetricRepo;
import com.starrocks.mysql.MysqlChannel;
import com.starrocks.mysql.MysqlCommand;
import com.starrocks.mysql.MysqlPacket;
import com.starrocks.mysql.MysqlProto;
import com.starrocks.mysql.MysqlSerializer;
import com.starrocks.mysql.MysqlServerStatusFlag;
import com.starrocks.plugin.AuditEvent.EventType;
import com.starrocks.proto.PQueryStatistics;
import com.starrocks.service.FrontendOptions;
import com.starrocks.thrift.TMasterOpRequest;
import com.starrocks.thrift.TMasterOpResult;
import com.starrocks.thrift.TQueryOptions;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.StringReader;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousCloseException;
import java.util.List;

/**
 * Process one mysql connection, receive one pakcet, process, send one packet.
 */
public class ConnectProcessor {
    private static final Logger LOG = LogManager.getLogger(ConnectProcessor.class);

    private final ConnectContext ctx;
    private ByteBuffer packetBuf;

    private StmtExecutor executor = null;

    public ConnectProcessor(ConnectContext context) {
        this.ctx = context;
    }

    // COM_INIT_DB: change current database of this session.
    private void handleInitDb() {
        String dbName = new String(packetBuf.array(), 1, packetBuf.limit() - 1);
        if (Strings.isNullOrEmpty(ctx.getClusterName())) {
            ctx.getState().setError("Please enter cluster");
            return;
        }
        dbName = ClusterNamespace.getFullName(ctx.getClusterName(), dbName);
        try {
            ctx.getCatalog().changeDb(ctx, dbName);
        } catch (DdlException e) {
            ctx.getState().setError(e.getMessage());
            return;
        }

        ctx.getState().setOk();
    }

    // COM_QUIT: set killed flag and then return OK packet.
    private void handleQuit() {
        ctx.setKilled();
        ctx.getState().setOk();
    }

    // COM_CHANGE_USER: change current user within this connection
    private void handleChangeUser() throws IOException {
        if (!MysqlProto.changeUser(ctx, packetBuf)) {
            LOG.warn("Failed to execute command `Change user`.");
            return;
        }
        handleResetConnnection();
    }

    // COM_RESET_CONNECTION: reset current connection session variables
    private void handleResetConnnection() throws IOException {
        resetConnectionSession();
        ctx.getState().setOk();
    }

    // process COM_PING statement, do nothing, just return one OK packet.
    private void handlePing() {
        ctx.getState().setOk();
    }

    private void resetConnectionSession() {
        // reconstruct serializer
        ctx.getSerializer().reset();
        ctx.getSerializer().setCapability(ctx.getCapability());
        // reset session variable
        ctx.resetSessionVariable();
    }

    private void auditAfterExec(String origStmt, StatementBase parsedStmt, PQueryStatistics statistics) {
        // slow query
        long endTime = System.currentTimeMillis();
        long elapseMs = endTime - ctx.getStartTime();

        ctx.getAuditEventBuilder().setEventType(EventType.AFTER_QUERY)
                .setState(ctx.getState().toString()).setQueryTime(elapseMs)
                .setScanBytes(statistics == null ? 0 : statistics.scan_bytes)
                .setScanRows(statistics == null ? 0 : statistics.scan_rows)
                .setReturnRows(ctx.getReturnRows())
                .setStmtId(ctx.getStmtId())
                .setQueryId(ctx.getQueryId() == null ? "NaN" : ctx.getQueryId().toString());

        if (ctx.getState().isQuery()) {
            MetricRepo.COUNTER_QUERY_ALL.increase(1L);
            if (ctx.getState().getStateType() == QueryState.MysqlStateType.ERR) {
                // err query
                MetricRepo.COUNTER_QUERY_ERR.increase(1L);
            } else {
                // ok query
                MetricRepo.COUNTER_QUERY_SUCCESS.increase(1L);
                MetricRepo.HISTO_QUERY_LATENCY.update(elapseMs);
            }
            ctx.getAuditEventBuilder().setIsQuery(true);
        } else {
            ctx.getAuditEventBuilder().setIsQuery(false);
        }

        ctx.getAuditEventBuilder().setFeIp(FrontendOptions.getLocalHostAddress());

        // We put origin query stmt at the end of audit log, for parsing the log more convenient.
        if (!ctx.getState().isQuery() && (parsedStmt != null && parsedStmt.needAuditEncryption())) {
            ctx.getAuditEventBuilder().setStmt(parsedStmt.toSql());
        } else if (ctx.getState().isQuery() && containsComment(origStmt)) {
            // avoid audit log can't replay
            ctx.getAuditEventBuilder().setStmt(origStmt);
        } else {
            ctx.getAuditEventBuilder().setStmt(origStmt.replace("\n", " "));
        }

        Catalog.getCurrentAuditEventProcessor().handleAuditEvent(ctx.getAuditEventBuilder().build());
    }

    private boolean containsComment(String sql) {
        return (sql.contains("--")) || sql.contains("#");
    }

    private void addFinishedQueryDetail() {
        QueryDetail queryDetail = ctx.getQueryDetail();
        if (queryDetail == null || !queryDetail.getQueryId().equals(DebugUtil.printId(ctx.getQueryId()))) {
            return;
        }

        long endTime = System.currentTimeMillis();
        long elapseMs = endTime - ctx.getStartTime();

        if (ctx.getState().getStateType() == QueryState.MysqlStateType.ERR) {
            queryDetail.setState(QueryDetail.QueryMemState.FAILED);
            queryDetail.setErrorMessage(ctx.getState().getErrorMessage());
        } else {
            queryDetail.setState(QueryDetail.QueryMemState.FINISHED);
        }
        queryDetail.setEndTime(endTime);
        queryDetail.setLatency(elapseMs);
        QueryDetailQueue.addAndRemoveTimeoutQueryDetail(queryDetail);
    }

    // process COM_QUERY statement,
    private void handleQuery() {
        MetricRepo.COUNTER_REQUEST_ALL.increase(1L);
        // convert statement to Java string
        String originStmt = null;
        try {
            byte[] bytes = packetBuf.array();
            int ending = packetBuf.limit() - 1;
            while (ending >= 1 && bytes[ending] == '\0') {
                ending--;
            }
            originStmt = new String(bytes, 1, ending, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            // impossible
            LOG.error("UTF8 is not supported in this environment.");
            ctx.getState().setError("Unsupported character set(UTF-8)");
            return;
        }
        ctx.getAuditEventBuilder().reset();
        ctx.getAuditEventBuilder()
                .setTimestamp(System.currentTimeMillis())
                .setClientIp(ctx.getMysqlChannel().getRemoteHostPortString())
                .setUser(ctx.getQualifiedUser())
                .setDb(ctx.getDatabase());

        // execute this query.
        StatementBase parsedStmt = null;
        try {
            ctx.setQueryId(UUIDUtil.genUUID());
            List<StatementBase> stmts = analyze(originStmt);
            for (int i = 0; i < stmts.size(); ++i) {
                ctx.getState().reset();
                if (i > 0) {
                    ctx.resetRetureRows();
                    ctx.setQueryId(UUIDUtil.genUUID());
                }
                parsedStmt = stmts.get(i);
                parsedStmt.setOrigStmt(new OriginStatement(originStmt, i));

                executor = new StmtExecutor(ctx, parsedStmt);
                ctx.setExecutor(executor);

                ctx.setIsLastStmt(i == stmts.size() - 1);

                executor.execute();

                // do not execute following stmt when current stmt failed, this is consistent with mysql server
                if (ctx.getState().getStateType() == QueryState.MysqlStateType.ERR) {
                    break;
                }

                if (i != stmts.size() - 1) {
                    // NOTE: set serverStatus after executor.execute(),
                    //      because when execute() throws exception, the following stmt will not execute
                    //      and the serverStatus with MysqlServerStatusFlag.SERVER_MORE_RESULTS_EXISTS will
                    //      cause client error: Packet sequence number wrong
                    ctx.getState().serverStatus |= MysqlServerStatusFlag.SERVER_MORE_RESULTS_EXISTS;
                    finalizeCommand();
                }
            }
        } catch (IOException e) {
            // Client failed.
            LOG.warn("Process one query failed because IOException: ", e);
            ctx.getState().setError("StarRocks process failed");
        } catch (UserException e) {
            LOG.warn("Process one query failed because.", e);
            ctx.getState().setError(e.getMessage());
            // set is as ANALYSIS_ERR so that it won't be treated as a query failure.
            ctx.getState().setErrType(QueryState.ErrType.ANALYSIS_ERR);
        } catch (Throwable e) {
            // Catch all throwable.
            // If reach here, maybe StarRocks bug.
            LOG.warn("Process one query failed because unknown reason: ", e);
            ctx.getState().setError("Unexpected exception: " + e.getMessage());
            if (parsedStmt instanceof KillStmt) {
                // ignore kill stmt execute err(not monitor it)
                ctx.getState().setErrType(QueryState.ErrType.ANALYSIS_ERR);
            }
        }

        // audit after exec
        // replace '\n' to '\\n' to make string in one line
        // TODO(cmy): when user send multi-statement, the executor is the last statement's executor.
        // We may need to find some way to resolve this.
        if (executor != null) {
            auditAfterExec(originStmt, executor.getParsedStmt(), executor.getQueryStatisticsForAuditLog());
        } else {
            // executor can be null if we encounter analysis error.
            auditAfterExec(originStmt, null, null);
        }

        addFinishedQueryDetail();
    }

    // analyze the origin stmt and return multi-statements
    private List<StatementBase> analyze(String originStmt) throws AnalysisException {
        LOG.debug("the originStmts are: {}", originStmt);
        // Parse statement with parser generated by CUP&FLEX
        SqlScanner input = new SqlScanner(new StringReader(originStmt), ctx.getSessionVariable().getSqlMode());
        SqlParser parser = new SqlParser(input);
        try {
            return SqlParserUtils.getMultiStmts(parser);
        } catch (Error e) {
            throw new AnalysisException("Please check your sql, we meet an error when parsing.", e);
        } catch (AnalysisException e) {
            LOG.warn("origin_stmt: " + originStmt + "; Analyze error message: " + parser.getErrorMsg(originStmt), e);
            String errorMessage = parser.getErrorMsg(originStmt);
            if (errorMessage == null) {
                throw e;
            } else {
                throw new AnalysisException(errorMessage, e);
            }
        } catch (Exception e) {
            // TODO(lingbin): we catch 'Exception' to prevent unexpected error,
            // should be removed this try-catch clause future.
            LOG.warn("origin_stmt: " + originStmt + "; exception: ", e);
            String errorMessage = e.getMessage();
            if (errorMessage == null) {
                throw new AnalysisException("Internal Error");
            } else {
                throw new AnalysisException("Internal Error: " + errorMessage);
            }
        }
    }

    // Get the column definitions of a table
    private void handleFieldList() throws IOException {
        // Already get command code.
        String tableName = null;
        String pattern = null;
        try {
            tableName = new String(MysqlProto.readNulTerminateString(packetBuf), "UTF-8");
            pattern = new String(MysqlProto.readEofString(packetBuf), "UTF-8");
        } catch (UnsupportedEncodingException e) {
            // Impossible!!!
            LOG.error("Unknown UTF-8 character set.");
            return;
        }
        if (Strings.isNullOrEmpty(tableName)) {
            ctx.getState().setError("Empty tableName");
            return;
        }
        Database db = ctx.getCatalog().getDb(ctx.getDatabase());
        if (db == null) {
            ctx.getState().setError("Unknown database(" + ctx.getDatabase() + ")");
            return;
        }
        db.readLock();
        try {
            Table table = db.getTable(tableName);
            if (table == null) {
                ctx.getState().setError("Unknown table(" + tableName + ")");
                return;
            }

            MysqlSerializer serializer = ctx.getSerializer();
            MysqlChannel channel = ctx.getMysqlChannel();

            // Send fields
            // NOTE: Field list doesn't send number of fields
            List<Column> baseSchema = table.getBaseSchema();
            for (Column column : baseSchema) {
                serializer.reset();
                serializer.writeField(db.getFullName(), table.getName(), column, true);
                channel.sendOnePacket(serializer.toByteBuffer());
            }

        } finally {
            db.readUnlock();
        }
        ctx.getState().setEof();
    }

    private void dispatch() throws IOException {
        int code = packetBuf.get();
        MysqlCommand command = MysqlCommand.fromCode(code);
        if (command == null) {
            ErrorReport.report(ErrorCode.ERR_UNKNOWN_COM_ERROR);
            ctx.getState().setError("Unknown command(" + command + ")");
            LOG.warn("Unknown command(" + command + ")");
            return;
        }
        ctx.setCommand(command);
        ctx.setStartTime();

        switch (command) {
            case COM_INIT_DB:
                handleInitDb();
                break;
            case COM_QUIT:
                handleQuit();
                break;
            case COM_QUERY:
                handleQuery();
                ctx.setStartTime();
                break;
            case COM_FIELD_LIST:
                handleFieldList();
                break;
            case COM_CHANGE_USER:
                handleChangeUser();
                break;
            case COM_RESET_CONNECTION:
                handleResetConnnection();
                break;
            case COM_PING:
                handlePing();
                break;
            default:
                ctx.getState().setError("Unsupported command(" + command + ")");
                LOG.warn("Unsupported command(" + command + ")");
                break;
        }
    }

    private ByteBuffer getResultPacket() {
        MysqlPacket packet = ctx.getState().toResponsePacket();
        if (packet == null) {
            // possible two cases:
            // 1. handler has send response
            // 2. this command need not to send response
            return null;
        }

        MysqlSerializer serializer = ctx.getSerializer();
        serializer.reset();
        packet.writeTo(serializer);
        return serializer.toByteBuffer();
    }

    // use to return result packet to user
    private void finalizeCommand() throws IOException {
        ByteBuffer packet = null;
        if (executor != null && executor.isForwardToMaster()) {
            // for ERR State, set packet to remote packet(executor.getOutputPacket())
            //      because remote packet has error detail
            // but for not ERR (OK or EOF) State, we should check whether stmt is ShowStmt,
            //      because there is bug in remote packet for ShowStmt on lower fe version
            //      bug is: Success ShowStmt should be EOF package but remote packet is not
            // so we should use local packet(getResultPacket()),
            // there is no difference for Success ShowStmt between remote package and local package in new version fe
            if (ctx.getState().getStateType() == QueryState.MysqlStateType.ERR) {
                packet = executor.getOutputPacket();
                // Protective code
                if (packet == null) {
                    packet = getResultPacket();
                    if (packet == null) {
                        LOG.debug("packet == null");
                        return;
                    }
                }
            } else {
                ShowResultSet resultSet = executor.getShowResultSet();
                // for lower version fe, all forwarded command is OK or EOF State, so we prefer to use remote packet for compatible
                // ShowResultSet is null means this is not ShowStmt, use remote packet(executor.getOutputPacket())
                // or use local packet (getResultPacket())
                if (resultSet == null) {
                    packet = executor.getOutputPacket();
                } else {
                    executor.sendShowResult(resultSet);
                    packet = getResultPacket();
                    if (packet == null) {
                        LOG.debug("packet == null");
                        return;
                    }
                }
            }
        } else {
            packet = getResultPacket();
            if (packet == null) {
                LOG.debug("packet == null");
                return;
            }
        }

        MysqlChannel channel = ctx.getMysqlChannel();
        channel.sendAndFlush(packet);

        // only change lastQueryId when current command is COM_QUERY
        if (ctx.getCommand() == MysqlCommand.COM_QUERY) {
            ctx.setLastQueryId(ctx.queryId);
            ctx.setQueryId(null);
        }
    }

    public TMasterOpResult proxyExecute(TMasterOpRequest request) {
        ctx.setDatabase(request.db);
        ctx.setQualifiedUser(request.user);
        ctx.setCatalog(Catalog.getCurrentCatalog());
        ctx.getState().reset();
        if (request.isSetCluster()) {
            ctx.setCluster(request.cluster);
        }
        if (request.isSetResourceInfo()) {
            ctx.getSessionVariable().setResourceGroup(request.getResourceInfo().getGroup());
        }
        if (request.isSetUser_ip()) {
            ctx.setRemoteIP(request.getUser_ip());
        }
        if (request.isSetTime_zone()) {
            ctx.getSessionVariable().setTimeZone(request.getTime_zone());
        }
        if (request.isSetStmt_id()) {
            ctx.setForwardedStmtId(request.getStmt_id());
        }
        if (request.isSetSqlMode()) {
            ctx.getSessionVariable().setSqlMode(request.sqlMode);
        }
        if (request.isSetEnableStrictMode()) {
            ctx.getSessionVariable().setEnableInsertStrict(request.enableStrictMode);
        }
        if (request.isSetCurrent_user_ident()) {
            UserIdentity currentUserIdentity = UserIdentity.fromThrift(request.getCurrent_user_ident());
            ctx.setCurrentUserIdentity(currentUserIdentity);
        }
        if (request.isSetIsLastStmt()) {
            ctx.setIsLastStmt(request.isIsLastStmt());
        } else {
            // if the caller is lower version fe, request.isSetIsLastStmt() may return false.
            // in this case, set isLastStmt to true, because almost stmt is single stmt
            // but when the original stmt is multi stmt the caller will encounter mysql error: Packet sequence number wrong
            ctx.setIsLastStmt(true);
        }

        if (request.isSetQuery_options()) {
            TQueryOptions queryOptions = request.getQuery_options();
            if (queryOptions.isSetMem_limit()) {
                ctx.getSessionVariable().setMaxExecMemByte(queryOptions.getMem_limit());
            }
            if (queryOptions.isSetQuery_timeout()) {
                ctx.getSessionVariable().setQueryTimeoutS(queryOptions.getQuery_timeout());
            }
            if (queryOptions.isSetLoad_mem_limit()) {
                ctx.getSessionVariable().setLoadMemLimit(queryOptions.getLoad_mem_limit());
            }
            if (queryOptions.isSetMax_scan_key_num()) {
                ctx.getSessionVariable().setMaxScanKeyNum(queryOptions.getMax_scan_key_num());
            }
            if (queryOptions.isSetMax_pushdown_conditions_per_column()) {
                ctx.getSessionVariable().setMaxPushdownConditionsPerColumn(
                        queryOptions.getMax_pushdown_conditions_per_column());
            }
        } else {
            // for compatibility, all following variables are moved to TQueryOptions.
            if (request.isSetExecMemLimit()) {
                ctx.getSessionVariable().setMaxExecMemByte(request.getExecMemLimit());
            }
            if (request.isSetQueryTimeout()) {
                ctx.getSessionVariable().setQueryTimeoutS(request.getQueryTimeout());
            }
            if (request.isSetLoadMemLimit()) {
                ctx.getSessionVariable().setLoadMemLimit(request.loadMemLimit);
            }
        }

        if (request.isSetQueryId()) {
            ctx.setQueryId(UUIDUtil.fromTUniqueid(request.getQueryId()));
        }

        ctx.setThreadLocalInfo();

        if (ctx.getCurrentUserIdentity() == null) {
            // if we upgrade Master FE first, the request from old FE does not set "current_user_ident".
            // so ctx.getCurrentUserIdentity() will get null, and causing NullPointerException after using it.
            // return error directly.
            TMasterOpResult result = new TMasterOpResult();
            ctx.getState().setError(
                    "Missing current user identity. You need to upgrade this Frontend to the same version as Master Frontend.");
            result.setMaxJournalId(Catalog.getCurrentCatalog().getMaxJournalId().longValue());
            result.setPacket(getResultPacket());
            return result;
        }

        StmtExecutor executor = null;
        try {
            // 0 for compatibility.
            int idx = request.isSetStmtIdx() ? request.getStmtIdx() : 0;
            executor = new StmtExecutor(ctx, new OriginStatement(request.getSql(), idx), true);
            executor.execute();
        } catch (IOException e) {
            // Client failed.
            LOG.warn("Process one query failed because IOException: ", e);
            ctx.getState().setError("StarRocks process failed: " + e.getMessage());
        } catch (Throwable e) {
            // Catch all throwable.
            // If reach here, maybe StarRocks bug.
            LOG.warn("Process one query failed because unknown reason: ", e);
            ctx.getState().setError("Unexpected exception: " + e.getMessage());
        }
        // no matter the master execute success or fail, the master must transfer the result to follower
        // and tell the follower the current jounalID.
        TMasterOpResult result = new TMasterOpResult();
        result.setMaxJournalId(Catalog.getCurrentCatalog().getMaxJournalId().longValue());
        // following stmt will not be executed, when current stmt is failed,
        // so only set SERVER_MORE_RESULTS_EXISTS Flag when stmt executed successfully
        if (!ctx.getIsLastStmt()
                && ctx.getState().getStateType() != QueryState.MysqlStateType.ERR) {
            ctx.getState().serverStatus |= MysqlServerStatusFlag.SERVER_MORE_RESULTS_EXISTS;
        }
        result.setPacket(getResultPacket());
        result.setState(ctx.getState().getStateType().toString());
        if (executor != null && executor.getProxyResultSet() != null) {
            result.setResultSet(executor.getProxyResultSet().tothrift());
        }
        return result;
    }

    // handle one process
    public void processOnce() throws IOException {
        // set status of query to OK.
        ctx.getState().reset();
        executor = null;

        // reset sequence id of MySQL protocol
        final MysqlChannel channel = ctx.getMysqlChannel();
        channel.setSequenceId(0);
        // read packet from channel
        try {
            packetBuf = channel.fetchOnePacket();
            if (packetBuf == null) {
                throw new IOException("Error happened when receiving packet.");
            }
        } catch (AsynchronousCloseException e) {
            // when this happened, timeout checker close this channel
            // killed flag in ctx has been already set, just return
            return;
        }

        // dispatch
        dispatch();
        // finalize
        finalizeCommand();

        ctx.setCommand(MysqlCommand.COM_SLEEP);
    }

    public void loop() {
        while (!ctx.isKilled()) {
            try {
                processOnce();
            } catch (Exception e) {
                // TODO(zhaochun): something wrong
                LOG.warn("Exception happened in one seesion(" + ctx + ").", e);
                ctx.setKilled();
                break;
            }
        }
    }
}