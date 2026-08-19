package com.recoverpro.server.common;

import com.recoverpro.server.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SYSTEM-PLAN 26.2: an unvalidated sort parameter is an injection/information-disclosure vector
 * (a caller sorting by an encrypted or unrelated column can infer data) -- 26.2.b's own wording is
 * "allowlist sortable fields explicitly and reject anything else". Before this task,
 * {@code SafeSort.from()} silently substituted the default field for an unrecognized request
 * instead of rejecting it, which doesn't meet TASK 26.2's literal ACCEPTANCE ("an unknown sort
 * parameter returns 400, not a 500 or a leaked column name").
 */
class SafeSortTest {

    private static final Map<String, String> ALLOWED = Map.of("createdAt", "createdAt", "status", "status");

    @Test
    void from_unrecognizedField_rejectsRatherThanSilentlySubstituting() {
        assertThatThrownBy(() -> SafeSort.from("borrowerName", "desc", ALLOWED, "createdAt"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("borrowerName");
    }

    @Test
    void from_allowedField_resolvesToItsMappedColumn() {
        Sort sort = SafeSort.from("status", "asc", ALLOWED, "createdAt");
        assertThat(sort.getOrderFor("status")).isNotNull();
        assertThat(sort.getOrderFor("status").getDirection()).isEqualTo(Sort.Direction.ASC);
    }

    @Test
    void from_nullField_usesDefaultWithoutThrowing() {
        Sort sort = SafeSort.from(null, "desc", ALLOWED, "createdAt");
        assertThat(sort.getOrderFor("createdAt")).isNotNull();
    }

    @Test
    void from_blankField_usesDefaultWithoutThrowing() {
        Sort sort = SafeSort.from("   ", "desc", ALLOWED, "createdAt");
        assertThat(sort.getOrderFor("createdAt")).isNotNull();
    }

    @Test
    void from_unrecognizedDirection_defaultsToDescending() {
        Sort sort = SafeSort.from("createdAt", "sideways", ALLOWED, "createdAt");
        assertThat(sort.getOrderFor("createdAt").getDirection()).isEqualTo(Sort.Direction.DESC);
    }

    /**
     * SYSTEM-PLAN 26.3.a: without a unique final sort key, rows tying on the requested field can
     * appear on two pages or on none as data changes underneath a client paging through results.
     */
    @Test
    void from_alwaysAppendsIdAsFinalTiebreaker() {
        Sort sort = SafeSort.from("status", "asc", ALLOWED, "createdAt");
        assertThat(sort.getOrderFor("id")).isNotNull();
        assertThat(sort.getOrderFor("id").getDirection()).isEqualTo(Sort.Direction.ASC);
    }

    @Test
    void withIdTiebreaker_appendsIdWhenAbsent() {
        Sort sort = SafeSort.withIdTiebreaker(Sort.by(Sort.Direction.DESC, "createdAt"));
        assertThat(sort.getOrderFor("createdAt")).isNotNull();
        assertThat(sort.getOrderFor("id")).isNotNull();
    }

    @Test
    void withIdTiebreaker_doesNotDuplicateWhenAlreadySortingById() {
        Sort sort = SafeSort.withIdTiebreaker(Sort.by(Sort.Direction.DESC, "id"));
        assertThat(sort.toList()).hasSize(1);
    }

    @Test
    void sanitize_unsortedPageable_appliesEndpointDefault() {
        Pageable input = PageRequest.of(2, 10);
        Pageable result = SafeSort.sanitize(input, ALLOWED, "createdAt", Sort.Direction.DESC);

        assertThat(result.getPageNumber()).isEqualTo(2);
        assertThat(result.getPageSize()).isEqualTo(10);
        assertThat(result.getSort().getOrderFor("createdAt").getDirection()).isEqualTo(Sort.Direction.DESC);
    }

    @Test
    void sanitize_unsortedPageable_stillAppliesIdTiebreaker() {
        Pageable input = PageRequest.of(0, 10);
        Pageable result = SafeSort.sanitize(input, ALLOWED, "createdAt", Sort.Direction.DESC);

        assertThat(result.getSort().getOrderFor("id")).isNotNull();
    }

    @Test
    void sanitize_clientRequestedAllowedField_isHonored() {
        Pageable input = PageRequest.of(0, 20, Sort.by(Sort.Direction.ASC, "status"));
        Pageable result = SafeSort.sanitize(input, ALLOWED, "createdAt", Sort.Direction.DESC);

        assertThat(result.getSort().getOrderFor("status")).isNotNull();
        assertThat(result.getSort().getOrderFor("status").getDirection()).isEqualTo(Sort.Direction.ASC);
    }

    /**
     * The concrete scenario this task exists to close: a client that discovered (or guessed) an
     * encrypted/internal entity property name and requested `?sort=` on it directly, bypassing
     * whatever the endpoint's own query params expose.
     */
    @Test
    void sanitize_clientRequestedDisallowedField_rejectsRatherThan500OrSilentSort() {
        Pageable input = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "borrowerName"));

        assertThatThrownBy(() -> SafeSort.sanitize(input, ALLOWED, "createdAt", Sort.Direction.DESC))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("borrowerName");
    }
}
