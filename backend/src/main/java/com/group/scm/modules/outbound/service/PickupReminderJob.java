package com.group.scm.modules.outbound.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@RequiredArgsConstructor
@Slf4j
public class PickupReminderJob {
    private final JdbcTemplate jdbc;
    private final PickupService pickup;
    private final TransactionTemplate tx;
    @Scheduled(fixedDelayString="${scm.pickup.reminder-ms:3600000}", initialDelay=60000)
    public void run() {
        for(Long id:jdbc.queryForList("SELECT id FROM biz_out_req WHERE deleted=0 AND fulfillment_mode IN ('UNSET','TRANSFER') AND status IN ('APPROVED','PARTIAL_OUT') AND pickup_notice_stage<2 AND (pickup_due_date<CURDATE() OR id IN (SELECT req_id FROM biz_req_fulfillment_event WHERE event_type='CLOSE' AND status='PENDING'))",Long.class)) {
            try {tx.executeWithoutResult(s->pickup.remind(id));} catch(Exception e){log.warn("Pickup reminder failed for request {}: {}",id,e.getClass().getSimpleName());}
        }
    }
}
