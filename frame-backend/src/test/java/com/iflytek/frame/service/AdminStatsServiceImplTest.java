package com.iflytek.frame.service;

import com.iflytek.frame.dto.DashboardStatsVO;
import com.iflytek.frame.mapper.AdminStatsMapper;
import com.iflytek.frame.service.impl.AdminStatsServiceImpl;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminStatsServiceImplTest {

    @Test
    void getDashboardStatsAssemblesVoFromMapper() {
        AdminStatsMapper mapper = mock(AdminStatsMapper.class);
        DashboardStatsVO raw = new DashboardStatsVO(
                48200, 312, 6, 14, 4.2, List.of()
        );
        when(mapper.dashboardStats()).thenReturn(raw);
        when(mapper.monthlyRevenue()).thenReturn(List.of(
                new DashboardStatsVO.MonthRevenue("2026-03", 8100)
        ));

        AdminStatsService service = new AdminStatsServiceImpl(mapper);
        DashboardStatsVO result = service.getDashboardStats();

        assertThat(result.totalRevenueCents()).isEqualTo(48200);
        assertThat(result.totalOrders()).isEqualTo(312);
        assertThat(result.monthlyRevenue()).hasSize(1);
        assertThat(result.monthlyRevenue().get(0).month()).isEqualTo("2026-03");
    }
}
