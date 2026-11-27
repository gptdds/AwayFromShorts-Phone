package com.afs.guard.data;

import com.afs.guard.Rule;

import java.util.List;
import java.util.Map;

public interface RuleRepository {
    List<DataRecords.RuleRecord> all();
    DataRecords.RuleRecord find(String packageName);
    DataRecords.RuleRecord save(String packageName, Rule rule, String source, String reason);
    void remove(String packageName, String source, String reason);
    void replaceAll(Map<String, Rule> rules, String source, String reason);
}

