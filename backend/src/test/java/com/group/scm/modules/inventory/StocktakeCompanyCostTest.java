package com.group.scm.modules.inventory;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.group.scm.common.service.DocNoService;
import com.group.scm.common.service.OrgScopeService;
import com.group.scm.modules.close.service.PeriodCloseService;
import com.group.scm.modules.inventory.entity.*;
import com.group.scm.modules.inventory.mapper.*;
import com.group.scm.modules.inventory.service.*;
import com.group.scm.modules.notif.service.NotifService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StocktakeCompanyCostTest {
    private static BigDecimal n(String value) { return new BigDecimal(value); }

    @ParameterizedTest
    @CsvSource({"100.00,10.00,20.00,120.00", "200.00,20.00,40.00,240.00", "0.00,0.00,0.00,0.00"})
    @SuppressWarnings("unchecked")
    void zeroWarehouseGainUsesLockedCompanyPrice(String oldAmount, String currentPrice, String expectedGain, String expectedTotal) {
        var heads = mock(BizStocktakeMapper.class);
        var lines = mock(BizStocktakeLineMapper.class);
        var db = mock(JdbcTemplate.class);
        var ledger = mock(InvLedgerSupport.class);
        var balances = mock(InvBalanceMapper.class);
        var pools = mock(InvCostPoolMapper.class);
        var flows = mock(InvTransactionMapper.class);
        var engine = new OrgCostEngine(ledger, balances, pools, flows, db);
        ReflectionTestUtils.setField(engine, "avgScale", 2);
        var wa = new WaInventoryService(balances, flows, db, ledger, engine);
        var zeroWh = balance(10L, "0", "0.00", "0.00");
        var otherWh = balance(11L, "10", oldAmount, currentPrice);
        var pool = new InvCostPool();
        pool.setId(1L); pool.setOrgId(1L); pool.setMaterialId(5L);
        pool.setQty(n("10")); pool.setAmount(n(oldAmount)); pool.setAvgPrice(n(currentPrice));
        when(ledger.orgOf(10L)).thenReturn(1L);
        when(ledger.lockCompanyCostForStocktake(3L)).thenReturn(10L);
        when(ledger.orgMode()).thenReturn(true);
        when(pools.findForUpdate(1L, 5L)).thenReturn(pool);
        when(balances.find(5L, 10L)).thenReturn(zeroWh);
        when(balances.findOrgMaterialForUpdate(1L, 5L)).thenReturn(List.of(zeroWh, otherWh));
        when(balances.updateById(any(InvBalance.class))).thenReturn(1);
        when(pools.updateById(any(InvCostPool.class))).thenReturn(1);
        var h = new BizStocktake();
        h.setId(3L); h.setOrgId(1L); h.setWarehouseId(10L); h.setDocNo("TEST-STOCKTAKE");
        h.setStatus("SUBMITTED"); h.setTakeDate(LocalDate.of(2038, 2, 1));
        var line = new BizStocktakeLine();
        line.setId(4L); line.setMaterialId(5L); line.setBookQty(n("0"));
        line.setUnitPrice(n("0.00")); line.setActualQty(n("2"));
        when(heads.selectById(3L)).thenReturn(h);
        when(heads.update(isNull(), any(Wrapper.class))).thenReturn(1);
        when(lines.selectList(any(Wrapper.class))).thenReturn(List.of(line));
        var service = new StocktakeService(heads, lines, db, mock(OrgScopeService.class), wa,
                mock(PeriodCloseService.class), mock(DocNoService.class), mock(NotifService.class));

        service.approve(3L);

        assertEquals(0, h.getGainAmt().compareTo(n(expectedGain)));
        assertEquals(0, line.getUnitPrice().compareTo(n(currentPrice)));
        assertEquals(0, pool.getQty().compareTo(n("12")));
        assertEquals(0, pool.getAmount().compareTo(n(expectedTotal)));
        verify(balances, never()).find(5L, 10L); // A stale ordinary warehouse read must not price this gain.
        var ordered = inOrder(ledger, db, pools, heads);
        ordered.verify(ledger).lockCompanyCostForStocktake(3L);
        ordered.verify(ledger).lockCompanyCost(1L);
        ordered.verify(db).queryForList(eq("SELECT id FROM md_warehouse WHERE id = ? FOR UPDATE"), eq(Long.class), eq(10L));
        ordered.verify(heads).selectById(3L);
        ordered.verify(pools).findForUpdate(1L, 5L);
    }

    private static InvBalance balance(long warehouse, String qty, String amount, String price) {
        var result = new InvBalance();
        result.setId(warehouse); result.setMaterialId(5L); result.setWarehouseId(warehouse);
        result.setQty(n(qty)); result.setAmount(n(amount)); result.setAvgPrice(n(price));
        return result;
    }
}
