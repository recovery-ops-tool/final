package com.recoverpro.server.common;

import com.recoverpro.server.common.exception.BusinessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.Map;

public final class SafeSort {

    private SafeSort() {}

    /**
     * TASK 26.2: an unrecognized {@code requestedField} REJECTS (throws {@link BusinessException},
     * mapped to 400) rather than silently substituting {@code defaultField} -- 26.2.b's own wording
     * is "allowlist sortable fields explicitly and reject anything else". Silently substituting let
     * a caller believe they got the sort order they asked for when they actually didn't.
     *
     * <p>TASK 26.3.a: the returned {@link Sort} always carries {@code id} as a final tiebreaker
     * (see {@link #withIdTiebreaker}) -- every field this allowlists (createdAt, status, an amount,
     * ...) can tie across many rows, and without a unique final key, rows can appear on two pages
     * or on none as data changes underneath a client paging through results.
     */
    public static Sort from(String requestedField,
                            String requestedDirection,
                            Map<String, String> allowed,
                            String defaultField) {
        String resolved;
        if (requestedField == null || requestedField.isBlank()) {
            resolved = defaultField;
        } else {
            resolved = allowed.get(requestedField);
            if (resolved == null) {
                throw new BusinessException(
                        "Invalid sort field '" + requestedField + "'. Allowed values: " + allowed.keySet());
            }
        }
        Sort.Direction direction = "asc".equalsIgnoreCase(requestedDirection)
                ? Sort.Direction.ASC
                : Sort.Direction.DESC;
        return withIdTiebreaker(Sort.by(direction, resolved));
    }

    /**
     * TASK 26.3.a: appends {@code id} ascending as a deterministic final sort key, unless the sort
     * already sorts by {@code id} (avoids a duplicate/conflicting order clause). Every JPA entity
     * in this codebase has an {@code id} property, so this is safe to apply unconditionally to any
     * {@link Sort} headed for a paginated repository query.
     */
    public static Sort withIdTiebreaker(Sort sort) {
        if (sort.getOrderFor("id") != null) return sort;
        return sort.and(Sort.by(Sort.Direction.ASC, "id"));
    }

    /**
     * TASK 26.2: sanitizes a {@code Pageable} whose {@code Sort} came directly from a client-
     * supplied {@code ?sort=} query parameter -- Spring Data's own
     * {@code PageableHandlerMethodArgumentResolver} applies no allowlisting at all, so a raw
     * {@code Pageable} handed straight to a JPA repository lets a caller sort (and, on an unknown
     * or mistyped property, 500) by any entity field, including encrypted/PII columns never meant
     * to be exposed this way. Unsorted input keeps the endpoint's own default; only the first
     * {@code Sort.Order} is honored, since none of these endpoints need multi-key sort today.
     */
    public static Pageable sanitize(Pageable pageable, Map<String, String> allowed,
                                    String defaultField, Sort.Direction defaultDirection) {
        if (!pageable.getSort().isSorted()) {
            return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                    withIdTiebreaker(Sort.by(defaultDirection, defaultField)));
        }
        Sort.Order requested = pageable.getSort().iterator().next();
        Sort sort = from(requested.getProperty(),
                requested.getDirection().isAscending() ? "asc" : "desc",
                allowed, defaultField);
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sort);
    }
}
