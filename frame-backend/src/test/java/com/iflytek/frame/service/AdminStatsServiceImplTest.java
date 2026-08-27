package com.iflytek.frame.service;

import com.iflytek.frame.dto.DashboardStatsVO;
import com.iflytek.frame.mapper.AdminStatsMapper;
import com.iflytek.frame.service.impl.AdminStatsServiceImpl;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminStatsServiceImplTest {

    @Test
    void getDashboardStatsAssemblesVoFromMapperWithRange() {
        AdminStatsMapper mapper = mock(AdminStatsMapper.class);
        DashboardStatsVO raw = new DashboardStatsVO(
                48200, 312, 6, 14, 4.2,
                List.<DashboardStatsVO.MonthRevenue>of(),
                List.<DashboardStatsVO.ProductUnits>of(),
                List.<DashboardStatsVO.TrendPoint>of(),
                List.<DashboardStatsVO.ProductShare>of()
        );
        when(mapper.dashboardStats(any(), any(), any())).thenReturn(raw);

        AdminStatsService service = new AdminStatsServiceImpl(mapper);
        DashboardStatsVO result = service.getDashboardStats("30d", null, null);

        assertThat(result.totalRevenueCents()).isEqualTo(48200);
        verify(mapper).dashboardStats(any(), any(), any());
    }
}
