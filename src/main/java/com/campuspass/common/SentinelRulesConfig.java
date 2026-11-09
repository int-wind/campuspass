package com.campuspass.common;

import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;

@Configuration
public class SentinelRulesConfig {
    @Value("${campuspass.sentinel.event-list-qps:200}") private double eventListQps;
    @Value("${campuspass.sentinel.event-detail-qps:300}") private double eventDetailQps;

    @EventListener(ApplicationReadyEvent.class)
    public void loadRules() {
        FlowRule list = new FlowRule("events:list").setGrade(RuleConstant.FLOW_GRADE_QPS).setCount(eventListQps);
        FlowRule detail = new FlowRule("events:detail").setGrade(RuleConstant.FLOW_GRADE_QPS).setCount(eventDetailQps);
        FlowRuleManager.loadRules(List.of(list, detail));
    }
}
