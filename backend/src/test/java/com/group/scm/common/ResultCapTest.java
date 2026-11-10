package com.group.scm.common;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/** 列表碰到条数上限时,响应头要带 X-Result-Truncated,前端据此提示"只显示了前 N 条"(2026-10-19)。 */
class ResultCapTest {

    private MockHttpServletResponse bind() {
        MockHttpServletResponse resp = new MockHttpServletResponse();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest(), resp));
        return resp;
    }

    @AfterEach
    void clear() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void reachedLimitMarksHeader() {
        MockHttpServletResponse resp = bind();
        List<Integer> rows = Collections.nCopies(500, 1);
        assertSame(rows, ResultCap.mark(rows, 500));
        assertEquals("500", resp.getHeader(ResultCap.HEADER));
    }

    @Test
    void belowLimitLeavesNoHeader() {
        MockHttpServletResponse resp = bind();
        ResultCap.mark(Collections.nCopies(499, 1), 500);
        assertNull(resp.getHeader(ResultCap.HEADER));
    }

    @Test
    void outsideRequestDoesNothing() {
        List<Integer> rows = Collections.nCopies(3, 1);
        assertSame(rows, ResultCap.mark(rows, 3));
        assertSame(null, ResultCap.mark(null, 3));
    }
}
