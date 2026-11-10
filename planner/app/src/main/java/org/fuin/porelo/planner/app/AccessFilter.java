package org.fuin.porelo.planner.app;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Lets a call through only when it carries the planner's token, on every path and dispatch, before
 * anything else runs.
 */
public final class AccessFilter implements Filter {

    private static final String PREFIX = "Bearer ";

    private final AccessToken token;

    /**
     * Makes the filter.
     *
     * @param token The token every call must carry.
     */
    public AccessFilter(final AccessToken token) {
        this.token = token;
    }

    @Override
    public void doFilter(
            final ServletRequest request, final ServletResponse response, final FilterChain chain)
            throws IOException, ServletException {
        final String header = ((HttpServletRequest) request).getHeader("Authorization");
        if (header != null
                && header.startsWith(PREFIX)
                && token.matches(header.substring(PREFIX.length()))) {
            chain.doFilter(request, response);
            return;
        }
        // Set, never sendError: that would hand the call on to Spring's error page.
        final HttpServletResponse refused = (HttpServletResponse) response;
        refused.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        refused.setHeader("WWW-Authenticate", "Bearer");
        refused.setContentLength(0);
    }
}
