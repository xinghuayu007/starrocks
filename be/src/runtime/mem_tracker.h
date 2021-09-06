// This file is made available under Elastic License 2.0.
// This file is based on code available under the Apache license here:
//   https://github.com/apache/incubator-doris/blob/master/be/src/runtime/mem_tracker.h

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

#ifndef STARROCKS_BE_SRC_QUERY_BE_RUNTIME_MEM_LIMIT_H
#define STARROCKS_BE_SRC_QUERY_BE_RUNTIME_MEM_LIMIT_H

#include <stdint.h>

#include <memory>
#include <mutex>
#include <unordered_map>

#include "common/status.h"
#include "gen_cpp/Types_types.h"
#include "util/metrics.h"
#include "util/runtime_profile.h"
#include "util/spinlock.h"

namespace starrocks {

class ObjectPool;
class MemTracker;
class ReservationTrackerCounters;
class RuntimeState;
class TQueryOptions;

/// A MemTracker tracks memory consumption; it contains an optional limit
/// and can be arranged into a tree structure such that the consumption tracked
/// by a MemTracker is also tracked by its ancestors.
///
/// We use a five-level hierarchy of mem trackers: process, pool, query, fragment
/// instance. Specific parts of the fragment (exec nodes, sinks, etc) will add a
/// fifth level when they are initialized. This function also initializes a user
/// function mem tracker (in the fifth level).
///
/// By default, memory consumption is tracked via calls to Consume()/Release(), either to
/// the tracker itself or to one of its descendents. Alternatively, a consumption metric
/// can specified, and then the metric's value is used as the consumption rather than the
/// tally maintained by Consume() and Release(). A tcmalloc metric is used to track
/// process memory consumption, since the process memory usage may be higher than the
/// computed total memory (tcmalloc does not release deallocated memory immediately).
//
/// GcFunctions can be attached to a MemTracker in order to free up memory if the limit is
/// reached. If LimitExceeded() is called and the limit is exceeded, it will first call
/// the GcFunctions to try to free memory and recheck the limit. For example, the process
/// tracker has a GcFunction that releases any unused memory still held by tcmalloc, so
/// this will be called before the process limit is reported as exceeded. GcFunctions are
/// called in the order they are added, so expensive functions should be added last.
/// GcFunctions are called with a global lock held, so should be non-blocking and not
/// call back into MemTrackers, except to release memory.
//
/// This class is thread-safe.
class MemTracker {
public:
    // I want to get a snapshot of the mem_tracker, but don't want to copy all the field of MemTracker.
    // SimpleItem contains the most important field of MemTracker.
    // Current this is only used for list_mem_usage
    // TODO: use a better name?
    struct SimpleItem {
        std::string label;
        std::string parent_label;
        size_t level = 0;
        int64_t limit = 0;
        int64_t cur_consumption = 0;
        int64_t peak_consumption = 0;
    };

    enum Type { NO_SET, PROCESS, QUERY_POOL, QUERY, LOAD };

    /// 'byte_limit' < 0 means no limit
    /// 'label' is the label used in the usage string (LogUsage())
    /// If 'auto_unregister' is true, never call unregister_from_parent().
    /// If 'log_usage_if_zero' is false, this tracker (and its children) will not be included
    /// in LogUsage() output if consumption is 0.
    MemTracker(int64_t byte_limit = -1, const std::string& label = std::string(), MemTracker* parent = nullptr,
               bool auto_unregister = true, bool log_usage_if_zero = true);

    MemTracker(Type type, int64_t byte_limit = -1, const std::string& label = std::string(),
               MemTracker* parent = nullptr, bool auto_unregister = true, bool log_usage_if_zero = true);

    /// C'tor for tracker for which consumption counter is created as part of a profile.
    /// The counter is created with name COUNTER_NAME.
    MemTracker(RuntimeProfile* profile, int64_t byte_limit, const std::string& label = std::string(),
               MemTracker* parent = nullptr, bool auto_unregister = true);

    ~MemTracker();

    /// Closes this MemTracker. After closing it is invalid to consume memory on this
    /// tracker and the tracker's consumption counter (which may be owned by a
    /// RuntimeProfile, not this MemTracker) can be safely destroyed. MemTrackers without
    /// consumption metrics in the context of a daemon must always be closed.
    /// Idempotent: calling multiple times has no effect.
    void close();

    // Removes this tracker from _parent->_child_trackers.
    void unregister_from_parent() {
        DCHECK(_parent != NULL);
        std::lock_guard<std::mutex> l(_parent->_child_trackers_lock);
        _parent->_child_trackers.erase(_child_tracker_it);
        _child_tracker_it = _parent->_child_trackers.end();
    }

    /// Include counters from a ReservationTracker in logs and other diagnostics.
    /// The counters should be owned by the fragment's RuntimeProfile.
    void enable_reservation_reporting(const ReservationTrackerCounters& counters);

    void consume(int64_t bytes) {
        if (bytes <= 0) {
            if (bytes < 0) release(-bytes);
            return;
        }
        if (_consumption_metric != NULL) {
            RefreshConsumptionFromMetric();
            return;
        }
        for (std::vector<MemTracker*>::iterator tracker = _all_trackers.begin(); tracker != _all_trackers.end();
             ++tracker) {
            (*tracker)->_consumption->add(bytes);
            if ((*tracker)->_consumption_metric == NULL) {
                DCHECK_GE((*tracker)->_consumption->current_value(), 0);
            }
        }
    }

    /// Increases/Decreases the consumption of this tracker and the ancestors up to (but
    /// not including) end_tracker. This is useful if we want to move tracking between
    /// trackers that share a common (i.e. end_tracker) ancestor. This happens when we want
    /// to update tracking on a particular mem tracker but the consumption against
    /// the limit recorded in one of its ancestors already happened.
    void consume_local(int64_t bytes, MemTracker* end_tracker) {
        DCHECK(_consumption_metric == NULL) << "Should not be called on root.";
        for (int i = 0; i < _all_trackers.size(); ++i) {
            if (_all_trackers[i] == end_tracker) return;
            DCHECK(!_all_trackers[i]->has_limit());
            _all_trackers[i]->_consumption->add(bytes);
        }
        DCHECK(false) << "end_tracker is not an ancestor";
    }

    void release_local(int64_t bytes, MemTracker* end_tracker) { consume_local(-bytes, end_tracker); }

    void list_mem_usage(std::vector<SimpleItem>* items, size_t cur_level, size_t upper_level) const {
        SimpleItem item;
        item.label = _label;
        if (_parent != nullptr) {
            item.parent_label = _parent->label();
        } else {
            item.parent_label = "";
        }
        item.level = cur_level;
        item.limit = _limit;
        item.cur_consumption = _consumption->current_value();
        item.peak_consumption = _consumption->value();

        (*items).emplace_back(item);

        if (cur_level < upper_level) {
            std::lock_guard<std::mutex> l(_child_trackers_lock);
            for (const auto& child : _child_trackers) {
                child->list_mem_usage(items, cur_level + 1, upper_level);
            }
        }
    }

    /// Increases consumption of this tracker and its ancestors by 'bytes' only if
    /// they can all consume 'bytes'. If this brings any of them over, none of them
    /// are updated.
    /// Returns true if the try succeeded.
    WARN_UNUSED_RESULT
    bool try_consume(int64_t bytes) {
        if (UNLIKELY(bytes <= 0)) return true;
        if (_consumption_metric != NULL) RefreshConsumptionFromMetric();
        int i;
        // Walk the tracker tree top-down.
        for (i = _all_trackers.size() - 1; i >= 0; --i) {
            MemTracker* tracker = _all_trackers[i];
            const int64_t limit = tracker->limit();
            if (limit < 0) {
                tracker->_consumption->add(bytes); // No limit at this tracker.
            } else {
                // If TryConsume fails, we can try to GC, but we may need to try several times if
                // there are concurrent consumers because we don't take a lock before trying to
                // update _consumption.
                while (true) {
                    if (LIKELY(tracker->_consumption->try_add(bytes, limit))) break;

                    VLOG_RPC << "TryConsume failed, bytes=" << bytes
                             << " consumption=" << tracker->_consumption->current_value() << " limit=" << limit
                             << " attempting to GC";
                    if (UNLIKELY(tracker->GcMemory(limit - bytes))) {
                        DCHECK_GE(i, 0);
                        // Failed for this mem tracker. Roll back the ones that succeeded.
                        for (int j = _all_trackers.size() - 1; j > i; --j) {
                            _all_trackers[j]->_consumption->add(-bytes);
                        }
                        return false;
                    }
                    VLOG_RPC << "GC succeeded, TryConsume bytes=" << bytes
                             << " consumption=" << tracker->_consumption->current_value() << " limit=" << limit;
                }
            }
        }
        // Everyone succeeded, return.
        DCHECK_EQ(i, -1);
        return true;
    }

    /// Decreases consumption of this tracker and its ancestors by 'bytes'.
    void release(int64_t bytes) {
        if (bytes <= 0) {
            if (bytes < 0) consume(-bytes);
            return;
        }
        if (_consumption_metric != NULL) {
            RefreshConsumptionFromMetric();
            return;
        }
        for (std::vector<MemTracker*>::iterator tracker = _all_trackers.begin(); tracker != _all_trackers.end();
             ++tracker) {
            (*tracker)->_consumption->add(-bytes);
            /// If a UDF calls FunctionContext::TrackAllocation() but allocates less than the
            /// reported amount, the subsequent call to FunctionContext::Free() may cause the
            /// process mem tracker to go negative until it is synced back to the tcmalloc
            /// metric. Don't blow up in this case. (Note that this doesn't affect non-process
            /// trackers since we can enforce that the reported memory usage is internally
            /// consistent.)
            if ((*tracker)->_consumption_metric == NULL) {
                DCHECK_GE((*tracker)->_consumption->current_value(), 0) << std::endl
                                                                        << (*tracker)->LogUsage(UNLIMITED_DEPTH);
            }
        }

        /// TODO: Release brokered memory?
    }

    // Returns true if a valid limit of this tracker or one of its ancestors is exceeded.
    bool any_limit_exceeded() {
        for (std::vector<MemTracker*>::iterator tracker = _limit_trackers.begin(); tracker != _limit_trackers.end();
             ++tracker) {
            if ((*tracker)->limit_exceeded()) {
                return true;
            }
        }
        return false;
    }

    // Return limit exceeded tracker or null
    MemTracker* find_limit_exceeded_tracker() {
        for (std::vector<MemTracker*>::iterator tracker = _limit_trackers.begin(); tracker != _limit_trackers.end();
             ++tracker) {
            if ((*tracker)->limit_exceeded()) {
                return *tracker;
            }
        }
        return NULL;
    }

    // Returns the maximum consumption that can be made without exceeding the limit on
    // this tracker or any of its parents. Returns int64_t::max() if there are no
    // limits and a negative value if any limit is already exceeded.
    int64_t spare_capacity() const {
        int64_t result = std::numeric_limits<int64_t>::max();
        for (std::vector<MemTracker*>::const_iterator tracker = _limit_trackers.begin();
             tracker != _limit_trackers.end(); ++tracker) {
            int64_t mem_left = (*tracker)->limit() - (*tracker)->consumption();
            result = std::min(result, mem_left);
        }
        return result;
    }

    /// Refresh the memory consumption value from the consumption metric. Only valid to
    /// call if this tracker has a consumption metric.
    void RefreshConsumptionFromMetric() {
        DCHECK(_consumption_metric != nullptr);
        DCHECK(_parent == nullptr);
        _consumption->set(_consumption_metric->value());
    }

    bool limit_exceeded() const { return _limit >= 0 && _limit < consumption(); }

    void set_limit(int64_t limit) { _limit = limit; }

    int64_t limit() const { return _limit; }

    bool has_limit() const { return _limit >= 0; }

    const std::string& label() const { return _label; }

    /// Returns the lowest limit for this tracker and its ancestors. Returns
    /// -1 if there is no limit.
    int64_t lowest_limit() const {
        if (_limit_trackers.empty()) return -1;
        int64_t v = std::numeric_limits<int64_t>::max();
        for (int i = 0; i < _limit_trackers.size(); ++i) {
            DCHECK(_limit_trackers[i]->has_limit());
            v = std::min(v, _limit_trackers[i]->limit());
        }
        return v;
    }

    int64_t consumption() const { return _consumption->current_value(); }

    /// Note that if _consumption is based on _consumption_metric, this will the max value
    /// we've recorded in consumption(), not necessarily the highest value
    /// _consumption_metric has ever reached.
    int64_t peak_consumption() const { return _consumption->value(); }

    MemTracker* parent() const { return _parent; }

    /// Signature for function that can be called to free some memory after limit is
    /// reached. The function should try to free at least 'bytes_to_free' bytes of
    /// memory. See the class header for further details on the expected behaviour of
    /// these functions.
    typedef std::function<void(int64_t bytes_to_free)> GcFunction;

    /// Logs the usage of this tracker and optionally its children (recursively).
    /// If 'logged_consumption' is non-NULL, sets the consumption value logged.
    /// 'max_recursive_depth' specifies the maximum number of levels of children
    /// to include in the dump. If it is zero, then no children are dumped.
    /// Limiting the recursive depth reduces the cost of dumping, particularly
    /// for the process MemTracker.
    /// TODO: once all memory is accounted in ReservationTracker hierarchy, move
    /// reporting there.
    std::string LogUsage(int max_recursive_depth, const std::string& prefix = "",
                         int64_t* logged_consumption = nullptr) const;
    /// Unlimited dumping is useful for query memtrackers or error conditions that
    /// are not performance sensitive
    static const int UNLIMITED_DEPTH = INT_MAX;

    /// Log the memory usage when memory limit is exceeded and return a status object with
    /// details of the allocation which caused the limit to be exceeded.
    /// If 'failed_allocation_size' is greater than zero, logs the allocation size. If
    /// 'failed_allocation_size' is zero, nothing about the allocation size is logged.
    Status MemLimitExceeded(RuntimeState* state, const std::string& details, int64_t failed_allocation = 0);

    static const std::string COUNTER_NAME;

    std::string debug_string() {
        std::stringstream msg;
        msg << "limit: " << _limit << "; "
            << "consumption: " << _consumption->current_value() << "; "
            << "label: " << _label << "; "
            << "all tracker size: " << _all_trackers.size() << "; "
            << "limit trackers size: " << _limit_trackers.size() << "; "
            << "parent is null: " << ((_parent == NULL) ? "true" : "false") << "; ";
        return msg.str();
    }

    bool is_consumption_metric_null() { return _consumption_metric == nullptr; }

    Type type() const { return _type; }

private:
    /// If consumption is higher than max_consumption, attempts to free memory by calling
    /// any added GC functions.  Returns true if max_consumption is still exceeded. Takes
    /// gc_lock. Updates metrics if initialized.
    bool GcMemory(int64_t max_consumption);

    // Walks the MemTracker hierarchy and populates _all_trackers and _limit_trackers
    void Init();

    // Adds tracker to _child_trackers
    void add_child_tracker(MemTracker* tracker) {
        std::lock_guard<std::mutex> l(_child_trackers_lock);
        tracker->_child_tracker_it = _child_trackers.insert(_child_trackers.end(), tracker);
    }

    /// Log consumption of all the trackers provided. Returns the sum of consumption in
    /// 'logged_consumption'. 'max_recursive_depth' specifies the maximum number of levels
    /// of children to include in the dump. If it is zero, then no children are dumped.
    static std::string LogUsage(int max_recursive_depth, const std::string& prefix,
                                const std::list<MemTracker*>& trackers, int64_t* logged_consumption);

    /// Lock to protect GcMemory(). This prevents many GCs from occurring at once.
    std::mutex _gc_lock;

    Type _type;

    int64_t _limit; // in bytes

    std::string _label;
    MemTracker* _parent;

    /// in bytes; not owned
    RuntimeProfile::HighWaterMarkCounter* _consumption;

    /// holds _consumption counter if not tied to a profile
    RuntimeProfile::HighWaterMarkCounter _local_counter;

    /// If non-NULL, used to measure consumption (in bytes) rather than the values provided
    /// to Consume()/Release(). Only used for the process tracker, thus parent_ should be
    /// NULL if _consumption_metric is set.
    UIntGauge* _consumption_metric = nullptr;

    /// If non-NULL, counters from a corresponding ReservationTracker that should be
    /// reported in logs and other diagnostics. Owned by this MemTracker. The counters
    /// are owned by the fragment's RuntimeProfile.
    std::atomic<ReservationTrackerCounters*> _reservation_counters{nullptr};

    std::vector<MemTracker*> _all_trackers;   // this tracker plus all of its ancestors
    std::vector<MemTracker*> _limit_trackers; // _all_trackers with valid limits

    // All the child trackers of this tracker. Used for error reporting only.
    // i.e., Updating a parent tracker does not update the children.
    mutable std::mutex _child_trackers_lock;
    std::list<MemTracker*> _child_trackers;
    // Iterator into _parent->_child_trackers for this object. Stored to have O(1)
    // remove.
    std::list<MemTracker*>::iterator _child_tracker_it;

    /// Functions to call after the limit is reached to free memory.
    std::vector<GcFunction> _gc_functions;

    /// If false, this tracker (and its children) will not be included in LogUsage() output
    /// if consumption is 0.
    bool _log_usage_if_zero;

    /// The number of times the GcFunctions were called.
    IntCounter* _num_gcs_metric = nullptr;

    /// The number of bytes freed by the last round of calling the GcFunctions (-1 before any
    /// GCs are performed).
    IntGauge* _bytes_freed_by_last_gc_metric = nullptr;

    // If true, calls unregister_from_parent() in the dtor. This is only used for
    // the query wide trackers to remove it from the process mem tracker. The
    // process tracker never gets deleted so it is safe to reference it in the dtor.
    // The query tracker has lifetime shared by multiple plan fragments so it's hard
    // to do cleanup another way.
    bool _auto_unregister = false;
};

} // namespace starrocks

#endif