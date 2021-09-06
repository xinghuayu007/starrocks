// This file is licensed under the Elastic License 2.0. Copyright 2021 StarRocks Limited.

package com.starrocks.sql.optimizer.rule.implementation;

import com.google.common.collect.Lists;
import com.starrocks.sql.optimizer.OptExpression;
import com.starrocks.sql.optimizer.OptimizerContext;
import com.starrocks.sql.optimizer.operator.OperatorType;
import com.starrocks.sql.optimizer.operator.logical.LogicalMysqlScanOperator;
import com.starrocks.sql.optimizer.operator.pattern.Pattern;
import com.starrocks.sql.optimizer.operator.physical.PhysicalMysqlScan;
import com.starrocks.sql.optimizer.rule.RuleType;

import java.util.List;

public class MysqlScanImplementationRule extends ImplementationRule {
    public MysqlScanImplementationRule() {
        super(RuleType.IMP_MYSQL_LSCAN_TO_PSCAN, Pattern.create(OperatorType.LOGICAL_MYSQL_SCAN));
    }

    @Override
    public List<OptExpression> transform(OptExpression input, OptimizerContext context) {
        LogicalMysqlScanOperator logical = (LogicalMysqlScanOperator) input.getOp();
        PhysicalMysqlScan physical = new PhysicalMysqlScan(logical.getTable(), logical.getColumnRefMap());

        physical.setPredicate(logical.getPredicate());
        physical.setLimit(logical.getLimit());

        OptExpression result = new OptExpression(physical);
        return Lists.newArrayList(result);
    }
}