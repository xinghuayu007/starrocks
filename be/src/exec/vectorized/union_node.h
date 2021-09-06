// This file is licensed under the Elastic License 2.0. Copyright 2021 StarRocks Limited.

#pragma once

#include "exec/exec_node.h"
#include "runtime/runtime_state.h"

namespace starrocks::vectorized {

class UnionNode : public ExecNode {
public:
    UnionNode(ObjectPool* pool, const TPlanNode& tnode, const DescriptorTbl& descs);
    ~UnionNode() override = default;

    Status init(const TPlanNode& tnode, RuntimeState* state) override;
    Status prepare(RuntimeState* state) override;
    Status open(RuntimeState* state) override;
    Status get_next(RuntimeState* state, RowBatch* row_batch, bool* eos) override;
    Status get_next(RuntimeState* state, ChunkPtr* chunk, bool* eos) override;
    Status close(RuntimeState* state) override;

private:
    struct SlotItem {
        SlotId slot_id;
        size_t ref_count;
    };

    void _convert_pass_through_slot_map(const std::map<SlotId, SlotId>& slot_map);

    Status _get_next_passthrough(RuntimeState* state, ChunkPtr* chunk);
    Status _get_next_materialize(RuntimeState* state, ChunkPtr* chunk);
    Status _get_next_const(RuntimeState* state, ChunkPtr* chunk);

    void _move_passthrough_chunk(ChunkPtr& src_chunk, ChunkPtr& dest_chunk);
    void _move_materialize_chunk(ChunkPtr& src_chunk, ChunkPtr& dest_chunk);
    Status _move_const_chunk(ChunkPtr& dest_chunk);

    static void _clone_column(ChunkPtr& dest_chunk, const ColumnPtr& src_column, const SlotDescriptor* dest_slot,
                              size_t row_count);

    static void _move_column(ChunkPtr& dest_chunk, ColumnPtr& src_column, const SlotDescriptor* dest_slot,
                             size_t row_count);

    bool _has_more_passthrough() const { return _child_idx < _first_materialized_child_idx; }

    bool _has_more_materialized() const {
        return _first_materialized_child_idx != _children.size() && _child_idx < _children.size();
    }

    bool _has_more_const(RuntimeState* state) const {
        return state->per_fragment_instance_idx() == 0 && _const_expr_list_idx < _const_expr_lists.size();
    }

    std::vector<std::vector<ExprContext*>> _const_expr_lists;
    std::vector<std::vector<ExprContext*>> _child_expr_lists;

    // the map from slot id of output chunk to slot id of child chunk
    // There may be multiple DestSlotId mapped to the same SrcSlotId,
    // so here we have to decide whether you can MoveColumn according to this situation
    std::vector<std::map<SlotId, SlotItem>> _pass_through_slot_maps;

    size_t _child_idx = 0;
    const int _first_materialized_child_idx = 0;
    int _const_expr_list_idx = 0;

    bool _child_eos = false;
    const int _tuple_id = 0;
    const TupleDescriptor* _tuple_desc = nullptr;
};

} // namespace starrocks::vectorized